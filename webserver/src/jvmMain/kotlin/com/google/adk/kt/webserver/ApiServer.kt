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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.telemetry.TelemetryConfig
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.models.VersionInfo
import com.google.adk.kt.webserver.routes.appRoutes
import com.google.adk.kt.webserver.routes.artifactRoutes
import com.google.adk.kt.webserver.routes.isWebUiEnabled
import com.google.adk.kt.webserver.routes.runRoutes
import com.google.adk.kt.webserver.routes.sessionRoutes
import com.google.adk.kt.webserver.routes.staticRoutes
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.adk.kt.webserver.telemetry.OpenTelemetryConfig
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * Installs the ADK agent runtime contract: health, version, app discovery, sessions, artifacts and
 * the run endpoints.
 *
 * The Development UI is mounted unless the `adk.web.ui.enabled` property says otherwise; the
 * endpoints the Dev UI itself drives are installed separately.
 */
@OptIn(FrameworkInternalApi::class)
fun Application.adkApiModule(
  sessionService: SessionService,
  artifactService: ArtifactService,
  agentLoader: AgentLoader,
  apiServerSpanExporter: ApiServerSpanExporter,
  captureMessageContent: Boolean = false,
  plugins: List<Plugin> = emptyList(),
) {
  install(CallLogging) {
    level = Level.INFO
    logger = AdkWebServer.StatusAwareLogger(LoggerFactory.getLogger(CallLogging::class.java))
    format { call ->
      val status = call.response.status()
      val httpMethod = call.request.httpMethod.value
      val uri = call.request.uri
      "Status: $status, HTTP method: $httpMethod, URI: $uri"
    }
  }
  install(ContentNegotiation) { json(adkJson) }

  val otelConfig = OpenTelemetryConfig(apiServerSpanExporter)
  val sdkTracerProvider = otelConfig.sdkTracerProvider()
  otelConfig.openTelemetrySdk(sdkTracerProvider)

  // The Dev UI trace view needs message content, but it records potential PII into spans.
  TelemetryConfig.captureMessageContent = captureMessageContent
  if (captureMessageContent) {
    LoggerFactory.getLogger(AdkWebServer::class.java)
      .warn(
        """
        ADK web server enabled telemetry message-content capture: prompt/response content (which
        may contain PII) will be recorded in trace spans. This is intended for local development
        only.
        """
          .trimIndent()
      )
  }

  routing {
    get("/health") { call.respond(mapOf("status" to "ok")) }
    get("/version") {
      call.respond(
        VersionInfo(
          version = AdkWebServer.adkVersion(),
          language = "kotlin",
          languageVersion = System.getProperty("java.version", "unknown"),
        )
      )
    }
    appRoutes(agentLoader)
    artifactRoutes(artifactService)
    runRoutes(agentLoader, sessionService, artifactService, plugins)
    sessionRoutes(sessionService)
    if (this@adkApiModule.isWebUiEnabled(default = true)) {
      staticRoutes(this@adkApiModule)
    }
  }
}
