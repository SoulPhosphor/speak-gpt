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

import android.content.Context
import java.io.File

/**
 * Where on-device Whisper model files live and how to query/delete them.
 *
 * Models go under `<filesDir>/whisper/`. App private storage means no
 * external storage permission is required and the files disappear if the
 * user uninstalls the app — that's the expected behavior.
 */
object LocalWhisperStorage {

    private const val DIR_NAME = "whisper"

    fun modelsDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun fileFor(context: Context, model: LocalWhisperModels.Model): File =
        File(modelsDir(context), model.fileName)

    fun isInstalled(context: Context, model: LocalWhisperModels.Model): Boolean {
        val f = fileFor(context, model)
        return f.exists() && f.length() > 0
    }

    fun installedModels(context: Context): List<LocalWhisperModels.Model> =
        LocalWhisperModels.ALL.filter { isInstalled(context, it) }

    fun delete(context: Context, model: LocalWhisperModels.Model): Boolean {
        val f = fileFor(context, model)
        if (!f.exists()) return true
        return f.delete()
    }

    fun sizeOnDiskBytes(context: Context, model: LocalWhisperModels.Model): Long {
        val f = fileFor(context, model)
        return if (f.exists()) f.length() else 0L
    }

    fun totalUsedBytes(context: Context): Long =
        installedModels(context).sumOf { sizeOnDiskBytes(context, it) }
}
