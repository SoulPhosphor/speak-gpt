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

    private val note = PortableRestoreFailureNote()

    override fun failureDetail(): String? = note.detail

    override fun validate(): Boolean {
        note.reset()
        return plan != null || read(DESIRED_FILE) != null
    }

    override fun stage(): Boolean {
        note.reset()
        val prepared = plan ?: return note.fail("no_prepared_plan")
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) return note.fail("staging_directory_unavailable")
        return write(CURRENT_FILE, prepared.current) && write(DESIRED_FILE, prepared.desired)
    }

    override fun apply(): Boolean = replaceFrom(DESIRED_FILE)

    override fun rollback(): Boolean = replaceFrom(CURRENT_FILE)

    private fun replaceFrom(name: String): Boolean {
        note.reset()
        val data = read(name) ?: return false
        return AppSettingsPortableStore.replace(app, data) || note.fail("settings_write_failed")
    }

    override fun cleanup() {
        stagingRoot.deleteRecursively()
    }

    private fun write(name: String, data: AppSettingsPortableData): Boolean =
        AtomicFileWriter.writeAndVerify(File(stagingRoot, name), AppSettingsPortableCodec.encode(data)) ||
            note.fail("staged_write_failed: $name")

    /** Null when unreadable; the reason is recorded in [note]. */
    private fun read(name: String): AppSettingsPortableData? {
        val file = File(stagingRoot, name)
        if (!file.isFile || file.length() > PortableRecoveryLimits.APP_SETTINGS_BYTES) {
            note.fail("staged_copy_unreadable: $name: file is missing or larger than the size limit")
            return null
        }
        return (AppSettingsPortableCodec.parse(file.readText(Charsets.UTF_8)) as?
            AppSettingsPortableCodec.Result.Ok)?.data
            ?: run {
                note.fail("staged_copy_unreadable: $name: settings codec rejected it")
                null
            }
    }

    private companion object {
        const val CURRENT_FILE = "current.json"
        const val DESIRED_FILE = "desired.json"
    }
}
