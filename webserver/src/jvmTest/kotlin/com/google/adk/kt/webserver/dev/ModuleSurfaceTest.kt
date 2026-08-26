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

import com.google.adk.kt.webserver.FakeAgentLoader
import com.google.adk.kt.webserver.FakeArtifactService
import com.google.adk.kt.webserver.FakeSessionService
import com.google.adk.kt.webserver.adkApiModule
import com.google.adk.kt.webserver.adkModule
import com.google.adk.kt.webserver.routes.WEB_UI_ENABLED_PROPERTY
import com.google.adk.kt.webserver.telemetry.ApiServerSpanExporter
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.testing.testApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Which endpoints belong to the runtime contract and which are development-only.
 *
 * These assert on the installed route tree rather than on responses, so a route added to the wrong
 * module fails here even when no request would tell the two apart.
 */
@RunWith(JUnit4::class)
class ModuleSurfaceTest {
  private val sessionService = FakeSessionService()
  private val artifactService = FakeArtifactService()
  private val agentLoader = FakeAgentLoader()

  @Test
  fun apiModule_installsTheContractSurface() {
    assertThat(
        routesOf {
          adkApiModule(sessionService, artifactService, agentLoader, ApiServerSpanExporter())
        }
      )
      .containsExactlyElementsIn(CONTRACT_ROUTES)
  }

  @Test
  fun devModule_addsOnlyTheDevelopmentSurface() {
    assertThat(
        routesOf {
          adkDevModule(sessionService, artifactService, agentLoader, ApiServerSpanExporter())
        }
      )
      .containsExactlyElementsIn(CONTRACT_ROUTES + DEVELOPMENT_ONLY_ROUTES)
  }

  @Test
  fun adkModule_stillInstallsTheFullSurface() {
    assertThat(
        routesOf {
          adkModule(sessionService, artifactService, agentLoader, ApiServerSpanExporter())
        }
      )
      .containsExactlyElementsIn(CONTRACT_ROUTES + DEVELOPMENT_ONLY_ROUTES)
  }

  /** The routes [install] mounts, with `adk.web.ui.enabled` cleared so the default applies. */
  private fun routesOf(install: Application.() -> Unit): Set<String> {
    val previous: String? = System.getProperty(WEB_UI_ENABLED_PROPERTY)
    System.clearProperty(WEB_UI_ENABLED_PROPERTY)
    val routes = mutableSetOf<String>()
    try {
      testApplication {
        application {
          install()
          routes += plugin(Routing).leafRoutes()
        }
        // testApplication builds the Application lazily, so force it.
        client.get("/health")
      }
    } finally {
      if (previous != null) System.setProperty(WEB_UI_ENABLED_PROPERTY, previous)
    }
    return routes
  }

  private fun Route.leafRoutes(): List<String> =
    if (children.isEmpty()) listOf(toString()) else children.flatMap { it.leafRoutes() }

  private companion object {
    /** The agent runtime contract, plus the Development UI, which Python gates behind a flag. */
    val CONTRACT_ROUTES =
      setOf(
        "/health/(method:GET)",
        "/version/(method:GET)",
        "/list-apps/(method:GET)",
        "/run/(method:POST)",
        "/run_sse/(method:POST)",
        "/apps/{appName}/users/{userId}/sessions/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/(method:POST)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/(method:POST)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/(method:DELETE)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/(method:POST)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}/(method:DELETE)",
        "/(method:GET)",
        "/dev-ui/(method:GET)",
        "/dev-ui/{...}/(method:GET)",
      )

    /** Mirrors which side Python puts each endpoint on; the URL prefixes differ. */
    val DEVELOPMENT_ONLY_ROUTES =
      setOf(
        "/debug/trace/{eventId}/(method:GET)",
        "/debug/trace/session/{sessionId}/(method:GET)",
        "/apps/{app_name}/eval_sets/(method:GET)",
        "/apps/{app_name}/eval_sets/{eval_set_id}/(method:POST)",
        "/apps/{app_name}/eval_sets/{eval_set_id}/add_session/(method:POST)",
        "/apps/{app_name}/eval_sets/{eval_set_id}/evals/(method:GET)",
        "/apps/{app_name}/eval_sets/{eval_set_id}/run_eval/(method:POST)",
        "/apps/{app_name}/eval_results/(method:GET)",
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/events/{eventId}/graph/(method:GET)",
      )
  }
}
