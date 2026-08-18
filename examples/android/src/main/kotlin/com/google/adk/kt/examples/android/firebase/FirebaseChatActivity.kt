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

package com.google.adk.kt.examples.android.firebase

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.examples.android.common.ScopedExampleActivity
import com.google.adk.kt.examples.android.common.foldTextParts
import com.google.adk.kt.examples.android.common.ui.AdkExamplesTheme
import com.google.adk.kt.examples.android.common.ui.ChatAuthor
import com.google.adk.kt.examples.android.common.ui.ChatMessage
import com.google.adk.kt.examples.android.common.ui.ChatScreen
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.launch

/**
 * Minimal Android example: an [com.google.adk.kt.agents.LlmAgent] backed by the Firebase AI
 * (Gemini) model from the `:google-adk-kotlin-firebase` module. The chat UI exercises plain
 * conversation, tool calling (via [WeatherTools]), and streaming responses (the "Stream" toggle
 * selects the [RunConfig.streamingMode]).
 *
 * The Firebase-setup plumbing lives in [FirebaseAppResolver]; the agent wiring lives in
 * [FirebaseChatAgent]. What remains here is the typical ADK usage: build an [InMemoryRunner] around
 * an agent and drive it with [InMemoryRunner.runAsync].
 *
 * Unlike the on-device examples, this one talks to the cloud Firebase AI (Gemini) backend, so it
 * needs a Firebase configuration and network access; see the app README.md for setup.
 */
class FirebaseChatActivity : ScopedExampleActivity() {

  private val sessionService = InMemorySessionService()
  private var runner: InMemoryRunner? = null

  private val messages = mutableStateListOf<ChatMessage>()
  private var inputEnabled by mutableStateOf(false)
  private var streaming by mutableStateOf(true)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AdkExamplesTheme {
        ChatScreen(
          title = "Firebase AI chat",
          messages = messages,
          inputEnabled = inputEnabled,
          onSend = ::sendToAgent,
          onBack = ::finish,
          streaming = streaming,
          onStreamingChange = { streaming = it },
        )
      }
    }

    val firebaseApp = FirebaseAppResolver.resolve(applicationContext)
    if (firebaseApp == null) {
      messages.add(ChatMessage(ChatAuthor.SYSTEM, FirebaseAppResolver.NO_CONFIG_MESSAGE))
      return
    }

    runner =
      try {
        InMemoryRunner(
          agent = FirebaseChatAgent.create(firebaseApp),
          appName = APP_NAME,
          sessionService = sessionService,
        )
      } catch (e: Throwable) {
        messages.add(
          ChatMessage(
            ChatAuthor.SYSTEM,
            "Failed to build agent: ${e.message ?: e::class.simpleName}",
          )
        )
        return
      }

    messages.add(
      ChatMessage(
        ChatAuthor.SYSTEM,
        "Ready. Try: \"Tell me about the planet Earth\" or " +
          "\"What is the current temperature in Mountain View?\"",
      )
    )
    inputEnabled = true
  }

  private fun sendToAgent(text: String) {
    val activeRunner = runner ?: return
    val streamMode = if (streaming) StreamingMode.SSE else StreamingMode.NONE
    messages.add(ChatMessage(ChatAuthor.USER, text))
    // Every turn runs against the same session, so disable input until this one finishes: a second
    // turn started mid-flight would interleave the two turns' events and reply bubbles. Re-enabled
    // in the `finally` below.
    inputEnabled = false

    scope.launch {
      val reply = StringBuilder()
      var bubble = -1
      try {
        activeRunner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
            runConfig = RunConfig(streamingMode = streamMode),
          )
          .collect { event ->
            if (event.author != FirebaseChatAgent.NAME) return@collect
            val chunk = event.foldTextParts()
            val error = event.errorMessage
            if (chunk.isBlank() && error == null) return@collect
            val partial = event.partial
            // Partial deltas grow the current bubble; a non-partial event ends the segment and
            // resets so the next one gets a fresh bubble. Errors also arrive non-partial with no
            // text, so surface them here.
            runOnUiThread {
              if (partial) {
                bubble = upsertAgentBubble(bubble, reply.append(chunk).toString())
              } else {
                if (chunk.isNotBlank()) {
                  val unused = upsertAgentBubble(bubble, chunk.trim())
                }
                error?.let { messages.add(ChatMessage(ChatAuthor.SYSTEM, "Model error: $it")) }
                reply.setLength(0)
                bubble = -1
              }
            }
          }
      } catch (e: Exception) {
        runOnUiThread {
          messages.add(ChatMessage(ChatAuthor.SYSTEM, "Error: ${e.message ?: e::class.simpleName}"))
        }
      } finally {
        runOnUiThread { inputEnabled = true }
      }
    }
  }

  /**
   * Adds the reply bubble on first call, then updates it in place. Returns its index; UI thread
   * only.
   */
  private fun upsertAgentBubble(index: Int, text: String): Int {
    if (index < 0) {
      messages.add(ChatMessage(ChatAuthor.AGENT, text, FirebaseChatAgent.NAME))
      return messages.lastIndex
    }
    messages[index] = messages[index].copy(text = text)
    return index
  }

  private companion object {
    const val APP_NAME = "FirebaseChatExample"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
  }
}
