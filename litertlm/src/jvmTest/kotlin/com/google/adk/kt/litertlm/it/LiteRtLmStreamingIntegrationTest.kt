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

package com.google.adk.kt.litertlm.it

import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.types.Content as AdkContent
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part as AdkPart
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.Type
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * End-to-end test that drives a real on-device LiteRT-LM model through [LiteRtLmModel] to prove the
 * streaming path behaves as the shared aggregator promises: many partial chunks whose concatenation
 * is reproduced verbatim by a single aggregated final response.
 *
 * The mock-based unit tests can only assume the native runtime streams incremental deltas; this
 * test confirms it against a real model, which is the behavior the callback-to-`Flow` bridge relies
 * on. Set `LITERT_LM_MODEL_PATH` to a `.litertlm` model file (see this module's README) to run it;
 * otherwise every case is skipped.
 */
@RunWith(JUnit4::class)
class LiteRtLmStreamingIntegrationTest {

  private lateinit var model: LiteRtLmModel

  @Before
  fun setUp() {
    val modelPath = resolveModelPath()
    Assume.assumeTrue(
      "Skipping: set LITERT_LM_MODEL_PATH to a .litertlm model file (see this module's README; " +
        "e.g. gemma-4-E2B-it). The tool-calling case additionally needs a tool-capable model.",
      modelPath != null && File(modelPath).exists(),
    )

    // Keep the native runtime's own logging out of the test output.
    runCatching { Engine.setNativeMinLogSeverity(LogSeverity.INFINITY) }

    model = LiteRtLmModel.create(EngineConfig(modelPath = modelPath!!, backend = Backend.CPU()))
  }

  @After
  fun tearDown() {
    if (::model.isInitialized) {
      model.close()
    }
  }

  @Test
  fun generateContent_streamTrue_emitsPartialDeltasThenSingleAggregatedFinal(): Unit = runBlocking {
    // A long, enumerable answer so the reply spans many chunks rather than arriving in one.
    val responses =
      model
        .generateContent(userRequest("Count from 1 to 20, separated by commas."), stream = true)
        .toList()

    val partials = responses.filter { it.partial }
    val finals = responses.filter { !it.partial }

    // Streaming actually streamed: more than one partial chunk arrived from the native runtime.
    assertTrue("expected multiple streamed partials, got ${partials.size}", partials.size > 1)
    // The aggregator closes the turn with exactly one non-partial response, and it comes last.
    assertEquals(1, finals.size)
    assertFalse(responses.last().partial)
    assertNull(responses.last().errorMessage)

    // The core contract of the migration: concatenating the streamed deltas reproduces the final
    // aggregated text exactly.
    val streamedText = partials.joinToString("") { it.text() }
    val finalText = finals.single().text()
    assertTrue("aggregated final text was empty", finalText.isNotEmpty())
    assertEquals(streamedText, finalText)
  }

  @Test
  fun generateContent_streamTrue_aggregatedFinalCarriesTheAnswer(): Unit = runBlocking {
    val responses =
      model
        .generateContent(
          userRequest("What is the capital of Japan? Reply with only the city name."),
          stream = true,
        )
        .toList()

    val finalResponse = responses.last()
    assertFalse(finalResponse.partial)
    assertNull(finalResponse.errorMessage)
    assertTrue(
      "aggregated final should name the capital of Japan",
      finalResponse.text().contains("Tokyo", ignoreCase = true),
    )
  }

  @Test
  fun generateContent_streamFalse_returnsSingleNonPartialResponse(): Unit = runBlocking {
    val responses =
      model
        .generateContent(
          userRequest("What is the capital of France? Reply with only the city name."),
          stream = false,
        )
        .toList()

    assertEquals(1, responses.size)
    val response = responses.single()
    assertFalse(response.partial)
    assertNull(response.errorMessage)
    assertTrue(
      "response should name the capital of France",
      response.text().contains("Paris", ignoreCase = true),
    )
  }

  @Test
  fun generateContent_streamTrue_toolCall_isNotDuplicatedInAggregatedFinal(): Unit = runBlocking {
    val getWeather =
      FunctionDeclaration(
        name = "get_weather",
        description = "Get the current weather for a city.",
        parameters =
          Schema(
            type = Type.OBJECT,
            properties = mapOf("city" to Schema(type = Type.STRING, description = "The city name")),
            required = listOf("city"),
          ),
      )
    val request =
      LlmRequest(
        contents =
          listOf(
            AdkContent(
              role = "user",
              parts =
                listOf(AdkPart(text = "What is the weather in Paris? Use the get_weather tool.")),
            )
          ),
        config =
          GenerateContentConfig(tools = listOf(Tool(functionDeclarations = listOf(getWeather)))),
      )

    val responses = model.generateContent(request, stream = true).toList()

    val finalResponse = responses.last()
    assertFalse(finalResponse.partial)
    assertNull(finalResponse.errorMessage)

    val functionCalls = finalResponse.content?.parts?.mapNotNull { it.functionCall }.orEmpty()
    // Needs a tool-capable model; a model that never calls the tool cannot exercise this case.
    Assume.assumeTrue(
      "model did not call the tool; use a tool-capable model such as gemma-4-E2B-it",
      functionCalls.isNotEmpty(),
    )
    // The runtime surfaces each call once and the aggregator appends without de-duplicating, so the
    // aggregated final carries exactly one call for the single requested tool.
    assertEquals(listOf("get_weather"), functionCalls.map { it.name })
  }

  private companion object {
    fun resolveModelPath(): String? =
      (System.getenv("LITERT_LM_MODEL_PATH") ?: System.getProperty("litert_lm_model_path"))
        ?.ifEmpty { null }

    fun userRequest(text: String): LlmRequest =
      LlmRequest(contents = listOf(AdkContent(role = "user", parts = listOf(AdkPart(text = text)))))

    fun LlmResponse.text(): String =
      content?.parts?.mapNotNull { it.text }?.joinToString("").orEmpty()
  }
}
