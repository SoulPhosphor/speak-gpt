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

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.preferences.ModelEndpointStateGenerationStore

/** Creates the credential-free Model & Endpoint Settings artifact. */
object ModelEndpointPortableBackup {
    sealed class Result {
        data class Ok(val endpointCount: Int, val favoriteCount: Int) : Result()
        data object Failed : Result()
    }

    fun write(context: Context, out: File): Result = try {
        val appContext = context.applicationContext
        val data = ModelEndpointStateGenerationStore.get(appContext).read() ?: return Result.Failed
        val json = ModelEndpointPortableCodec.encode(data)
        out.writeText(json, Charsets.UTF_8)
        Result.Ok(data.endpoints.size, data.favorites.size)
    } catch (_: Exception) {
        Result.Failed
    }
}
