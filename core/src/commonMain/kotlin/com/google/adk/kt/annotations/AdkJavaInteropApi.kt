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

package com.google.adk.kt.annotations

/**
 * Marks an API that exists only to make ADK usable from Java, such as the fluent builders. Kotlin
 * callers should use the primary constructor and named arguments instead; opting in acknowledges
 * that you specifically need the Java-facing surface. Java callers are unaffected, since the opt-in
 * requirement is enforced only by the Kotlin compiler.
 */
@RequiresOptIn(
  message =
    "Java-interop API: prefer the Kotlin constructor and named arguments; opt in only if you need" +
      " the Java-facing surface.",
  level = RequiresOptIn.Level.WARNING,
)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
)
annotation class AdkJavaInteropApi
