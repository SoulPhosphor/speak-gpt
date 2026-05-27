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

package org.teslasoft.assistant.stt

/**
 * Static registry of the on-device Whisper models we offer. See
 * whisper-local-plan.md for the rationale behind this list (English-capable,
 * Pixel-class hardware).
 *
 * The download URLs point at ggerganov/whisper.cpp on Hugging Face, which is
 * the upstream that publishes the canonical ggml-format weights.
 *
 * sha256 is null when we haven't baked in a known-good hash yet. When null,
 * the downloader skips integrity verification (still works, just less safe);
 * once we capture verified SHAs they can be filled in without any other
 * code change.
 */
object LocalWhisperModels {

    data class Model(
        /** Stable id used in SharedPreferences and as the file name stem. */
        val id: String,
        /** Human-readable name shown in the UI. */
        val displayName: String,
        /** Approximate on-disk size in megabytes, for UI labels. */
        val sizeMb: Int,
        /** ggml binary filename under [LocalWhisperStorage.modelsDir]. */
        val fileName: String,
        /** Direct download URL for the ggml binary. */
        val url: String,
        /** Known-good SHA-256, lowercase hex; null skips verification. */
        val sha256: String?,
        /** Short one-line description for the model row in Settings. */
        val description: String
    )

    val BASE_EN = Model(
        id = "base.en",
        displayName = "base.en",
        sizeMb = 142,
        fileName = "ggml-base.en.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
        sha256 = null,
        description = "Fast default. Clearly better than Google dictation."
    )

    val SMALL_EN = Model(
        id = "small.en",
        displayName = "small.en",
        sizeMb = 466,
        fileName = "ggml-small.en.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin",
        sha256 = null,
        description = "Noticeable accuracy bump. Still real-time on Pixel-class phones."
    )

    val LARGE_V3_TURBO = Model(
        id = "large-v3-turbo",
        displayName = "large-v3-turbo",
        sizeMb = 809,
        fileName = "ggml-large-v3-turbo.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin",
        sha256 = null,
        description = "Max quality. Slower, heavier on battery in long calls."
    )

    /** Display order in the model picker. */
    val ALL: List<Model> = listOf(BASE_EN, SMALL_EN, LARGE_V3_TURBO)

    fun byId(id: String): Model? = ALL.firstOrNull { it.id == id }
}
