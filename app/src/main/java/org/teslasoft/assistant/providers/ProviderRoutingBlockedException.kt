/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.providers

/**
 * Thrown at the request boundary when the saved routing for the model cannot
 * be satisfied (e.g. Only mode with no usable provider). It aborts dispatch so
 * the app never silently falls back to an unrestricted request; the existing
 * generation error path surfaces [message] to the user.
 */
class ProviderRoutingBlockedException(message: String) : Exception(message)
