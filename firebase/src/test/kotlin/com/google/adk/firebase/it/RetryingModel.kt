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
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ServerException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [Model] decorator that retries transient failures with capped exponential backoff and jitter.
 *
 * A non-streaming call is retried on any [RetryConfig.isRetryable] error; a streaming call is
 * retried only while nothing has been emitted yet, so already-delivered partials are never
 * replayed. Permanent errors and the final attempt propagate unchanged.
 */
class RetryingModel(private val delegate: Model, private val config: RetryConfig = RetryConfig()) :
  Model {

  override val name: String
    get() = delegate.name

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
    for (attempt in 0 until config.maxAttempts) {
      var emitted = false
      try {
        delegate.generateContent(request, stream).collect { response ->
          emitted = true
          emit(response)
        }
        return@flow
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val isLastAttempt = attempt == config.maxAttempts - 1
        if (emitted || isLastAttempt || !config.isRetryable(e)) throw e
        delay(config.delayFor(attempt))
      }
    }
  }
}

/** Wraps this model so transient failures are retried with backoff (see [RetryingModel]). */
fun Model.withRetry(config: RetryConfig = RetryConfig()): Model = RetryingModel(this, config)

/**
 * Backoff policy for [RetryingModel]: [maxAttempts] total tries, delays growing from [initialDelay]
 * by [multiplier] up to [maxDelay] and spread by +/-[jitter]. [isRetryable] decides which errors
 * are transient; [random] is injectable so jitter is deterministic in tests.
 */
class RetryConfig(
  val maxAttempts: Int = 3,
  val initialDelay: Duration = 1.seconds,
  val maxDelay: Duration = 30.seconds,
  val multiplier: Double = 2.0,
  val jitter: Double = 0.5,
  val random: Random = Random.Default,
  val isRetryable: (Throwable) -> Boolean = ::isTransientFirebaseError,
)

/** The backoff delay before the retry that follows the given zero-based [attempt]. */
fun RetryConfig.delayFor(attempt: Int): Duration {
  val exponential = initialDelay * multiplier.pow(attempt)
  if (jitter <= 0.0) return minOf(exponential, maxDelay)
  // Cap the base before jittering so the band can't overshoot and pile onto maxDelay.
  val base = minOf(exponential, maxDelay / (1.0 + jitter))
  val factor = 1.0 + jitter * (2.0 * random.nextDouble() - 1.0)
  return base * factor
}

/**
 * Whether a Firebase AI failure is transient and worth retrying: a server 5xx, a request timeout or
 * a rate limit. Content, auth and configuration errors are permanent and fail fast.
 */
fun isTransientFirebaseError(error: Throwable): Boolean =
  error is ServerException || error is RequestTimeoutException || error is QuotaExceededException
