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

/** Transaction participant for credential-free Model & Endpoint Settings. */
class ModelEndpointRestoreParticipant internal constructor(
    context: Context,
    private val incoming: File?,
    private val mode: PortableRestoreMode,
    private val stagingRoot: File,
    private val precomputed: PreparedPlan? = null
) : SelectedCategoryRestoreTransaction.Participant {

    data class PreparedPlan(
        val current: ModelEndpointPortableCodec.Data,
        val incoming: ModelEndpointPortableCodec.Data,
        val desired: ModelEndpointPortableCodec.Data,
        val report: ModelEndpointMergePlanner.Report?
    )

    constructor(
        context: Context,
        incoming: File,
        mode: PortableRestoreMode,
        stagingRoot: File
    ) : this(context, incoming, mode, stagingRoot, null)

    internal constructor(
        context: Context,
        plan: PreparedPlan,
        stagingRoot: File
    ) : this(context, null, PortableRestoreMode.MERGE, stagingRoot, plan) {
        validatedIncoming = plan.incoming
        mergeReport = plan.report
    }
    private val appContext = context.applicationContext
    private var validatedIncoming: ModelEndpointPortableCodec.Data? = null

    var mergeReport: ModelEndpointMergePlanner.Report? = null
        private set

    override val categoryKey: String = PortableRestoreCategory.MODEL_ENDPOINT_SETTINGS.key

    override fun validate(): Boolean {
        precomputed?.let {
            validatedIncoming = it.incoming
            mergeReport = it.report
            return true
        }
        val source = incoming ?: return false
        if (!source.isFile || source.length() > ModelEndpointPortableCodec.MAX_ARTIFACT_BYTES) {
            return false
        }
        val parsed = ModelEndpointPortableCodec.parse(source.readText(Charsets.UTF_8))
        validatedIncoming = (parsed as? ModelEndpointPortableCodec.Result.Ok)?.data
        return validatedIncoming != null
    }

    override fun stage(): Boolean {
        val backup = validatedIncoming ?: return false
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) return false
        val currentFile = File(stagingRoot, CURRENT_FILE)
        val current = precomputed?.current ?: run {
            if (ModelEndpointPortableBackup.write(appContext, currentFile) is
                ModelEndpointPortableBackup.Result.Failed
            ) return false
            (ModelEndpointPortableCodec.parse(currentFile.readText(Charsets.UTF_8)) as?
                ModelEndpointPortableCodec.Result.Ok)?.data ?: return false
        }
        if (precomputed != null) {
            currentFile.writeText(ModelEndpointPortableCodec.encode(current), Charsets.UTF_8)
        }
        val desired = precomputed?.desired ?: if (mode == PortableRestoreMode.REPLACE) {
            backup
        } else {
            ModelEndpointMergePlanner.merge(current, backup).also { mergeReport = it.report }.data
        }
        val desiredFile = File(stagingRoot, DESIRED_FILE)
        desiredFile.writeText(ModelEndpointPortableCodec.encode(desired), Charsets.UTF_8)
        return ModelEndpointPortableCodec.parse(desiredFile.readText(Charsets.UTF_8)) is
            ModelEndpointPortableCodec.Result.Ok
    }

    override fun apply(): Boolean = applyFile(File(stagingRoot, DESIRED_FILE))

    override fun rollback(): Boolean = applyFile(File(stagingRoot, CURRENT_FILE))

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun applyFile(file: File): Boolean {
        if (!file.isFile || file.length() > ModelEndpointPortableCodec.MAX_ARTIFACT_BYTES) return false
        val parsed = ModelEndpointPortableCodec.parse(file.readText(Charsets.UTF_8)) as?
            ModelEndpointPortableCodec.Result.Ok ?: return false
        return ModelEndpointPortableRestore.apply(appContext, parsed.data)
    }

    companion object {
        private const val CURRENT_FILE = "current.json"
        private const val DESIRED_FILE = "desired.json"
    }
}
