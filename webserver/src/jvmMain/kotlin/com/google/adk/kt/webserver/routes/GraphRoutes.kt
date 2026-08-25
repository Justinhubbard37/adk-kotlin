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

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.dev.routes.extractGraphParams as devExtractGraphParams
import com.google.adk.kt.webserver.dev.routes.graphRoutes as devGraphRoutes
import com.google.adk.kt.webserver.loaders.AgentLoader
import io.ktor.http.Parameters
import io.ktor.server.routing.Route

/** Kept so callers of the previous package keep compiling; use the `dev` package instead. */
@Deprecated(
  "Moved to the development-only package.",
  ReplaceWith("GraphRoutesError", "com.google.adk.kt.webserver.dev.routes.GraphRoutesError"),
)
typealias GraphRoutesError = com.google.adk.kt.webserver.dev.routes.GraphRoutesError

/** Kept so callers of the previous package keep compiling; use the `dev` package instead. */
@Deprecated(
  "Moved to the development-only package.",
  ReplaceWith("GraphRoutesErrors", "com.google.adk.kt.webserver.dev.routes.GraphRoutesErrors"),
)
typealias GraphRoutesErrors = com.google.adk.kt.webserver.dev.routes.GraphRoutesErrors

/** Kept so callers of the previous package keep compiling; use the `dev` package instead. */
@Deprecated(
  "Moved to the development-only package.",
  ReplaceWith("GraphParams", "com.google.adk.kt.webserver.dev.routes.GraphParams"),
)
typealias GraphParams = com.google.adk.kt.webserver.dev.routes.GraphParams

/** Kept so callers of the previous package keep compiling; use the `dev` package instead. */
@Deprecated(
  "Moved to the development-only package.",
  ReplaceWith("GraphRoutesResult", "com.google.adk.kt.webserver.dev.routes.GraphRoutesResult"),
)
typealias GraphRoutesResult = com.google.adk.kt.webserver.dev.routes.GraphRoutesResult

/** Kept so callers of the previous package keep compiling; use the `dev` package instead. */
@Deprecated(
  "Moved to the development-only package.",
  ReplaceWith(
    "extractGraphParams(parameters)",
    "com.google.adk.kt.webserver.dev.routes.extractGraphParams",
  ),
)
@Suppress("DEPRECATION")
fun extractGraphParams(parameters: Parameters): GraphRoutesResult =
  devExtractGraphParams(parameters)

/** Kept so callers of the previous package keep compiling; use the `dev` package instead. */
@Deprecated(
  "Moved to the development-only package.",
  ReplaceWith(
    "graphRoutes(agentLoader, sessionService)",
    "com.google.adk.kt.webserver.dev.routes.graphRoutes",
  ),
)
fun Route.graphRoutes(agentLoader: AgentLoader, sessionService: SessionService): Unit =
  devGraphRoutes(agentLoader, sessionService)
