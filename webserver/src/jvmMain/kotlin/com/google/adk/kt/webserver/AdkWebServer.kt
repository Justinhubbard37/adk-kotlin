/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.dev.adkDevModule
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import io.ktor.server.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Embedded Ktor server exposing the ADK dev/web API.
 *
 * [start] and [stop] are safe to call from different threads; a [stop] arriving while [start] is
 * still binding aborts it. A failed [start] leaves the engine recorded, so call [stop] before
 * retrying.
 *
 * @property captureMessageContent When true, the server records prompt/response content into
 *   telemetry spans so the Dev UI trace view can display it. This may capture PII and increase span
 *   size, so it defaults to false; enable it only for local development.
 */
class AdkWebServer(
  private val port: Int = 8080,
  private val sessionService: SessionService,
  private val artifactService: ArtifactService,
  private val agentLoader: AgentLoader,
  private val apiServerSpanExporter: ApiServerSpanExporter,
  private val captureMessageContent: Boolean = false,
  private val plugins: List<Plugin> = emptyList(),
) {
  companion object {
    private val logger = LoggerFactory.getLogger(AdkWebServer::class.java)

    /** The ADK Kotlin version reported by the `/version` endpoint. */
    fun adkVersion(): String = com.google.adk.kt.VERSION
  }

  private val lifecycleLock = Any()
  private var server: ApplicationEngine? = null

  fun start(wait: Boolean = false) {
    // Released before the blocking call below, so stop() can still take it.
    val engine =
      synchronized(lifecycleLock) {
        if (server != null) return
        embeddedServer(Netty, port = port) {
            adkModule(
              sessionService,
              artifactService,
              agentLoader,
              apiServerSpanExporter,
              captureMessageContent,
              plugins,
            )
          }
          .also { server = it }
      }
    logger.info("Ktor server starting on port $port")
    engine.start(wait = wait)
  }

  fun stop() {
    synchronized(lifecycleLock) {
      server?.stop(1000, 5000)
      server = null
    }
    logger.info("Ktor server stopped")
  }

  public class StatusAwareLogger(private val delegate: Logger) : Logger by delegate {
    override fun info(msg: String?) {
      if (msg != null && msg.contains("Status: 5")) {
        delegate.warn(msg)
      } else {
        delegate.info(msg)
      }
    }
  }
}

/** Installs the full development surface. Equivalent to [adkDevModule]. */
fun Application.adkModule(
  sessionService: SessionService,
  artifactService: ArtifactService,
  agentLoader: AgentLoader,
  apiServerSpanExporter: ApiServerSpanExporter,
  captureMessageContent: Boolean = false,
  plugins: List<Plugin> = emptyList(),
) {
  adkDevModule(
    sessionService,
    artifactService,
    agentLoader,
    apiServerSpanExporter,
    captureMessageContent,
    plugins,
  )
}
