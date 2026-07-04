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

package org.teslasoft.assistant.preferences.memory.librarian

import android.content.Context
import java.io.File

/**
 * Where embedding model files live and how to query/delete them — the Whisper
 * storage pattern (LocalWhisperStorage), but each model is a DIRECTORY holding
 * two files (transformer + tokenizer), so a model counts as installed only when
 * both are present.
 *
 * Models go under `<filesDir>/embeddings_models/<model id>/`. App-private, so
 * no storage permission and the files vanish on uninstall.
 */
object EmbeddingModelStorage {

    private const val DIR_NAME = "embeddings_models"

    fun rootDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun modelDir(context: Context, model: EmbeddingModels.Model): File {
        val dir = File(rootDir(context), model.id)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun modelFile(context: Context, model: EmbeddingModels.Model): File =
        File(modelDir(context, model), model.modelFileName)

    fun tokenizerFile(context: Context, model: EmbeddingModels.Model): File =
        File(modelDir(context, model), model.tokenizerFileName)

    fun isInstalled(context: Context, model: EmbeddingModels.Model): Boolean {
        val m = modelFile(context, model)
        val t = tokenizerFile(context, model)
        return m.exists() && m.length() > 0 && t.exists() && t.length() > 0
    }

    fun installedModels(context: Context): List<EmbeddingModels.Model> =
        EmbeddingModels.ALL.filter { isInstalled(context, it) }

    /** The single active model: the first installed catalog entry. Simpler than
     *  a stored preference and always consistent with what's on disk. */
    fun activeModel(context: Context): EmbeddingModels.Model? =
        installedModels(context).firstOrNull()

    fun delete(context: Context, model: EmbeddingModels.Model): Boolean {
        val dir = File(rootDir(context), model.id)
        if (!dir.exists()) return true
        return dir.deleteRecursively()
    }

    fun sizeOnDiskBytes(context: Context, model: EmbeddingModels.Model): Long {
        val dir = File(rootDir(context), model.id)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun totalUsedBytes(context: Context): Long =
        installedModels(context).sumOf { sizeOnDiskBytes(context, it) }
}
