/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.firebase.it

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for the retry mechanism. They inject the retryable predicate and use a test-local
 * exception, because the Firebase SDK's real transient exceptions can't be constructed here; the
 * live [isTransientFirebaseError] path is exercised in [FirebaseIntegrationTest].
 */
@RunWith(JUnit4::class)
class RetryingModelTest {

  private class Boom : RuntimeException("transient")

  private val request = LlmRequest()

  /** Retries [Boom], with no real waiting and no jitter, so behavior is deterministic and fast. */
  private val fastConfig =
    RetryConfig(
      maxAttempts = 3,
      initialDelay = Duration.ZERO,
      jitter = 0.0,
      isRetryable = { it is Boom },
    )

  /** A model whose successive calls run the given scripted behaviors, counting the calls made. */
  private class ScriptedModel(private vararg val behaviors: () -> Flow<LlmResponse>) : Model {
    var calls = 0
      private set

    override val name = "scripted"

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
      val behavior = behaviors[calls.coerceAtMost(behaviors.size - 1)]
      calls++
      return behavior()
    }
  }

  @Test
  fun nonStreaming_retriesTransientFailure_thenSucceeds() {
    val response = LlmResponse()
    val model =
      ScriptedModel({ flow { throw Boom() } }, { flow { throw Boom() } }, { flowOf(response) })

    val result = runBlocking { RetryingModel(model, fastConfig).generateContent(request).toList() }

    assertThat(model.calls).isEqualTo(3)
    assertThat(result).containsExactly(response)
  }

  @Test
  fun exhaustsAllAttempts_thenThrowsLastError() {
    val model = ScriptedModel({ flow { throw Boom() } })

    val thrown = runBlocking {
      assertFailsWith<Boom> { RetryingModel(model, fastConfig).generateContent(request).toList() }
    }

    assertThat(thrown).isInstanceOf(Boom::class.java)
    assertThat(model.calls).isEqualTo(3) // maxAttempts
  }

  @Test
  fun permanentError_isNotRetried() {
    val model = ScriptedModel({ flow { throw IllegalStateException("permanent") } })

    runBlocking {
      assertFailsWith<IllegalStateException> {
        RetryingModel(model, fastConfig).generateContent(request).toList()
      }
    }

    assertThat(model.calls).isEqualTo(1)
  }

  @Test
  fun streaming_failureBeforeFirstChunk_isRetried() {
    val response = LlmResponse()
    val model = ScriptedModel({ flow { throw Boom() } }, { flowOf(response) })

    val result = runBlocking {
      RetryingModel(model, fastConfig).generateContent(request, stream = true).toList()
    }

    assertThat(model.calls).isEqualTo(2)
    assertThat(result).containsExactly(response)
  }

  @Test
  fun streaming_failureAfterFirstChunk_isNotRetried() {
    val partial = LlmResponse(partial = true)
    val model =
      ScriptedModel(
        {
          flow {
            emit(partial)
            throw Boom()
          }
        },
        { flowOf(LlmResponse()) },
      )

    val emitted = mutableListOf<LlmResponse>()
    runBlocking {
      assertFailsWith<Boom> {
        RetryingModel(model, fastConfig).generateContent(request, stream = true).collect {
          emitted.add(it)
        }
      }
    }

    // The stream had already produced output, so restarting is unsafe: propagate, don't retry.
    assertThat(model.calls).isEqualTo(1)
    assertThat(emitted).containsExactly(partial)
  }

  @Test
  fun delayFor_growsExponentially_untilCapped() {
    val config =
      RetryConfig(initialDelay = 1.seconds, multiplier = 2.0, maxDelay = 5.seconds, jitter = 0.0)

    assertThat(config.delayFor(0)).isEqualTo(1.seconds)
    assertThat(config.delayFor(1)).isEqualTo(2.seconds)
    assertThat(config.delayFor(2)).isEqualTo(4.seconds)
    assertThat(config.delayFor(3)).isEqualTo(5.seconds) // capped at maxDelay
  }

  @Test
  fun delayFor_appliesJitterWithinBounds() {
    val config =
      RetryConfig(
        initialDelay = 10.seconds,
        multiplier = 1.0,
        maxDelay = 100.seconds,
        jitter = 0.5,
        random = Random(1234),
      )

    val delaysMillis = List(100) { config.delayFor(0).inWholeMilliseconds }

    // Every sample stays within the +/-50% band around the 10s base.
    assertThat(delaysMillis.min()).isAtLeast(5_000L) // 10s * (1 - 0.5)
    assertThat(delaysMillis.max()).isAtMost(15_000L) // 10s * (1 + 0.5)
    // Jitter spreads the delay to both sides of the base; a no-op jitter would fail these.
    assertThat(delaysMillis.any { it < 10_000L }).isTrue()
    assertThat(delaysMillis.any { it > 10_000L }).isTrue()
  }

  @Test
  fun delayFor_whenBaseSaturatesCap_keepsTwoSidedJitterWithoutPilingOnMax() {
    // A base far above the cap: clamping after jitter would pin ~half the samples on maxDelay.
    val config =
      RetryConfig(
        initialDelay = 100.seconds,
        multiplier = 2.0,
        maxDelay = 30.seconds,
        jitter = 0.5,
        random = Random(99),
      )

    val delaysMillis = List(200) { config.delayFor(0).inWholeMilliseconds }

    // Stays under the cap with no point mass on it, so concurrent callers do not re-synchronize.
    assertThat(delaysMillis.max()).isLessThan(30_000L)
    assertThat(delaysMillis.none { it == 30_000L }).isTrue()
    assertThat(delaysMillis.distinct().size).isGreaterThan(1)
  }
}
