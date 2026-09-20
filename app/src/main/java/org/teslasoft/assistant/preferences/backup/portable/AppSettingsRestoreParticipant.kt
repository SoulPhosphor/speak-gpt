/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import java.io.File
import org.teslasoft.assistant.util.AtomicFileWriter

/** Replace-only participant for app settings and preferences. */
class AppSettingsRestoreParticipant internal constructor(
    context: Context,
    private val stagingRoot: File,
    private val plan: PreparedPlan? = null
) : SelectedCategoryRestoreTransaction.Participant {
    data class PreparedPlan(
        val current: AppSettingsPortableData,
        val desired: AppSettingsPortableData
    )

    private val app = context.applicationContext

    override val categoryKey: String = PortableRestoreCategory.SETTINGS.key

    override fun validate(): Boolean = plan != null || read(DESIRED_FILE) != null

    override fun stage(): Boolean {
        val prepared = plan ?: return false
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) return false
        return write(CURRENT_FILE, prepared.current) && write(DESIRED_FILE, prepared.desired)
    }

    override fun apply(): Boolean = read(DESIRED_FILE)?.let { AppSettingsPortableStore.replace(app, it) } == true

    override fun rollback(): Boolean = read(CURRENT_FILE)?.let { AppSettingsPortableStore.replace(app, it) } == true

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun write(name: String, data: AppSettingsPortableData): Boolean =
        AtomicFileWriter.writeAndVerify(File(stagingRoot, name), AppSettingsPortableCodec.encode(data))

    private fun read(name: String): AppSettingsPortableData? {
        val file = File(stagingRoot, name)
        if (!file.isFile || file.length() > PortableRecoveryLimits.APP_SETTINGS_BYTES) return null
        return (AppSettingsPortableCodec.parse(file.readText(Charsets.UTF_8)) as?
            AppSettingsPortableCodec.Result.Ok)?.data
    }

    private companion object {
        const val CURRENT_FILE = "current.json"
        const val DESIRED_FILE = "desired.json"
    }
}
