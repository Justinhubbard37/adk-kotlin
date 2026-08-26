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

package com.google.adk.kt.webserver.dev

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.adkApiModule
import com.google.adk.kt.webserver.dev.routes.debugRoutes
import com.google.adk.kt.webserver.dev.routes.evalRoutes
import com.google.adk.kt.webserver.dev.routes.graphRoutes
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

/**
 * Installs [adkApiModule] plus the development-only endpoints the Dev UI drives: request traces,
 * evaluation and agent graphs.
 *
 * These read agent state and run debugging code, so they belong on a development machine rather
 * than in a deployment, which should install [adkApiModule] alone. Install one module or the other,
 * never both.
 */
fun Application.adkDevModule(
  sessionService: SessionService,
  artifactService: ArtifactService,
  agentLoader: AgentLoader,
  apiServerSpanExporter: ApiServerSpanExporter,
  captureMessageContent: Boolean = false,
  plugins: List<Plugin> = emptyList(),
) {
  adkApiModule(
    sessionService,
    artifactService,
    agentLoader,
    apiServerSpanExporter,
    captureMessageContent,
    plugins,
  )

  // A second `routing` block merges into the one adkApiModule installed.
  routing {
    debugRoutes(apiServerSpanExporter)
    evalRoutes()
    graphRoutes(agentLoader, sessionService)
  }
}
