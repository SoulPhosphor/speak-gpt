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

package org.teslasoft.assistant.imagegen

/**
 * The normalized image-generation result (image-generation-rebuild-plan.md
 * §9): validated image bytes with their REAL detected type — never an
 * assumed PNG — plus provider metadata when available. Width and height
 * are filled in by the storage layer once the bitmap is decoded; the
 * network layer never decodes pixels.
 *
 * A temporary signed download URL is deliberately NOT part of this result:
 * it must never be persisted (§12), so it dies inside the coordinator.
 */
class GeneratedImageResult(
    val bytes: ByteArray,
    val mimeType: String,
    val fileExtension: String,
    val width: Int? = null,
    val height: Int? = null,
    val providerRequestId: String? = null,
    val providerUsage: String? = null
)
