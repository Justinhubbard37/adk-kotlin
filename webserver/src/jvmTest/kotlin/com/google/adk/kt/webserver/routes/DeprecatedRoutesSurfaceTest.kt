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

@file:Suppress("DEPRECATION")

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.webserver.AgentGraphGenerator
import com.google.adk.kt.webserver.dev.routes.GraphRoutesResult as DevGraphRoutesResult
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import io.ktor.server.routing.Route
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Names every symbol the `dev` package move left behind, so dropping one breaks the build.
 *
 * These are the names released in 0.8.0. A typealias carries no nested classifier and emits no
 * class, so `GraphRoutesResult.Success` needs the `dev` package and a 0.8.0 binary needs a
 * recompile.
 */
@RunWith(JUnit4::class)
class DeprecatedRoutesSurfaceTest {
  @Test
  fun deprecatedGraphTypes_stillResolveAtTheOldNames() {
    val error: GraphRoutesError = GraphRoutesErrors.ERR_MISSING_APP_NAME
    val params = GraphParams(appName = "app", userId = "u", sessionId = "s", eventId = "e")
    val result: GraphRoutesResult = DevGraphRoutesResult.Success(params)

    assertThat(error.message).isEqualTo("Missing appName")
    assertThat((result as DevGraphRoutesResult.Success).params).isEqualTo(params)
  }

  @Test
  fun deprecatedExtractGraphParams_forwardsToTheDevImplementation() {
    val complete =
      parametersOf(
        "appName" to listOf("app"),
        "userId" to listOf("u"),
        "sessionId" to listOf("s"),
        "eventId" to listOf("e"),
      )

    assertThat(extractGraphParams(complete)).isInstanceOf(DevGraphRoutesResult.Success::class.java)
    assertThat(extractGraphParams(Parameters.Empty))
      .isEqualTo(DevGraphRoutesResult.Error(GraphRoutesErrors.ERR_MISSING_APP_NAME))
  }

  @Test
  fun deprecatedRouteFacades_keepTheirReleasedClassNames() {
    // One file per name: re-merging them renames the facades a 0.8.0 binary links against.
    for (name in listOf("DebugRoutesKt", "EvalRoutesKt", "GraphRoutesKt")) {
      assertThat(Class.forName("com.google.adk.kt.webserver.routes.$name")).isNotNull()
    }
  }
}

/** Compile-only: referencing each forwarder here is what fails the build if one is removed. */
@Suppress("unused")
private fun Route.referenceTheDeprecatedRouteFunctions(
  exporter: ApiServerSpanExporter,
  agentLoader: AgentLoader,
  sessionService: SessionService,
) {
  debugRoutes(exporter)
  evalRoutes()
  graphRoutes(agentLoader, sessionService)
  AgentGraphGenerator(agentLoader)
}
