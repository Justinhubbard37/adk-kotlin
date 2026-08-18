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

package com.google.adk.kt.mlkit

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.mlkit.GenaiPromptConversions.toGenerateContentRequest
import com.google.adk.kt.mlkit.GenaiPromptConversions.toLlmResponse
import com.google.adk.kt.mlkit.GenaiPromptTracing.format
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.StreamingResponseAggregator
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [Model] implementation that uses the ML Kit GenAI API to generate content.
 *
 * ML Kit has no per-turn role, so this implementation has these limitations, which stand until the
 * public ML Kit API allows per-turn roles:
 * - Multi-turn is approximated: for multi-turn requests each turn's text is prefixed with a
 *   `[role]:` marker (e.g. `[user]:`/`[model]:`), and a default system instruction requires the
 *   model not to echo a marker back and not to continue the conversation past its own reply.
 * - Tool use is unsupported: `functionCall`/`functionResponse` parts are dropped.
 * - Only the first response candidate is used.
 *
 * @param generativeModel The [GenerativeModel] to use for generation.
 * @param name The name of the model.
 */
class GenaiPrompt
private constructor(val generativeModel: GenerativeModel, override val name: String) : Model {

  companion object {
    val logger = LoggerFactory.getLogger(GenaiPrompt::class)

    /**
     * Creates a [GenaiPrompt] instance with the given [generativeModel] and [name].
     *
     * @param generativeModel The [GenerativeModel] to use for generation.
     * @param name The name of the model.
     */
    fun create(generativeModel: GenerativeModel, name: String = "GenaiPrompt"): GenaiPrompt =
      GenaiPrompt(generativeModel, name)
  }

  private fun convertRequest(request: LlmRequest): GenerateContentRequest {
    return request.toGenerateContentRequest()
  }

  private fun convertResponse(response: GenerateContentResponse): LlmResponse {
    return response.toLlmResponse()
  }

  private fun traceRequest(request: GenerateContentRequest): String {
    return format(request)
  }

  private fun traceResponse(response: GenerateContentResponse): String {
    return format(response)
  }

  private suspend fun generateContentNonStreaming(request: LlmRequest): LlmResponse {
    val genRequest = convertRequest(request)
    logger.trace { traceRequest(genRequest) }
    val genResponse = generativeModel.generateContent(genRequest)
    logger.trace { traceResponse(genResponse) }
    return convertResponse(genResponse).also { logger.trace { format(it) } }
  }

  /** Emits every chunk as a partial [LlmResponse], then the aggregated final one. */
  @OptIn(FrameworkInternalApi::class)
  private fun generateContentStreaming(request: LlmRequest): Flow<LlmResponse> = flow {
    val aggregator = StreamingResponseAggregator()
    val genRequest = convertRequest(request)
    logger.trace { traceRequest(genRequest) }
    generativeModel.generateContentStream(genRequest).collect { chunk ->
      logger.trace { "partial response: ${traceResponse(chunk)}" }
      emit(aggregator.processResponse(convertResponse(chunk)))
    }

    aggregator.aggregate()?.let { response ->
      logger.trace { "final response: ${format(response)}" }
      emit(response)
    }
  }

  final override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
    logger.trace { "request: ${format(request)}, stream: $stream" }
    return if (stream) {
      generateContentStreaming(request)
    } else {
      flow { emit(generateContentNonStreaming(request)) }
    }
  }
}
