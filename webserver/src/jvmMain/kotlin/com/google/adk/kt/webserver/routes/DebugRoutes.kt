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

import com.google.adk.kt.webserver.dev.routes.debugRoutes as devDebugRoutes
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import io.ktor.server.routing.Route

/** Kept so callers of the previous package keep compiling; use the `dev` package instead. */
@Deprecated(
  "Moved to the development-only package.",
  ReplaceWith("debugRoutes(exporter)", "com.google.adk.kt.webserver.dev.routes.debugRoutes"),
)
fun Route.debugRoutes(exporter: ApiServerSpanExporter): Unit = devDebugRoutes(exporter)
