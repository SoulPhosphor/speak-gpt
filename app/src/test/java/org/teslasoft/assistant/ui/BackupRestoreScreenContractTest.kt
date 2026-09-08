package org.teslasoft.assistant.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreScreenContractTest {

    @Test
    fun mainSettingsPlacesBackupRestoreImmediatelyBeforeAlerts() {
        val layout = source("activity_settings.xml")
        val chat = layout.indexOf("@+id/tile_chat_settings")
        val backup = layout.indexOf("@+id/tile_backup_restore")
        val alerts = layout.indexOf("@+id/tile_alert_debug_menu")
        assertTrue(chat >= 0 && backup > chat && alerts > backup)
        assertTrue(
            layout.substringAfter("@+id/tile_alert_debug_menu")
                .substringBefore("</LinearLayout>")
                .contains("layout_constraintTop_toBottomOf=\"@+id/tile_backup_restore\"")
        )
    }

    @Test
    fun memoryManagerNoLongerOwnsTheBackupRestoreRow() {
        assertTrue(!source("activity_memory_manager.xml").contains("row_memory_backup_restore"))
    }

    @Test
    fun backupAndRestoreSectionsFollowTheApprovedVisibleOrder() {
        val layout = source("activity_memory_backup_restore.xml")
        val backupTitle = layout.indexOf("@string/backup_section_title")
        val automatic = layout.indexOf("@layout/section_automatic_backups")
        val recovery = layout.indexOf("@string/backup_create_section")
        val readable = layout.indexOf("@string/backup_readable_section")
        val converter = layout.indexOf("@+id/btn_legacy_convert")
        val restoreTitle = layout.indexOf("@string/restore_data_section")
        val categories = layout.indexOf("@+id/check_restore_chats")
        val portableRestore = layout.indexOf("@+id/btn_portable_restore")
        val databaseType = layout.indexOf("@+id/btn_restore_type\"")
        val databaseRestore = layout.indexOf("@+id/btn_restore_database\"")

        assertTrue(backupTitle >= 0)
        assertTrue(automatic > backupTitle)
        assertTrue(recovery > automatic)
        assertTrue(readable > recovery)
        assertTrue(converter > readable)
        assertTrue(restoreTitle > converter)
        assertTrue(categories > restoreTitle)
        assertTrue(portableRestore > categories)
        assertTrue(databaseType > portableRestore)
        assertTrue(databaseRestore > databaseType)
    }

    @Test
    fun retiredControlsAreHiddenButLegacyConverterAndResetWiringRemain() {
        val layout = source("activity_memory_backup_restore.xml")
        val activity = javaSource("MemoryBackupRestoreActivity.kt")
        for (id in listOf(
            "btn_portable_export",
            "btn_portable_import",
            "btn_companion_download",
            "btn_companion_upload",
            "btn_restore_from_backup",
            "btn_memory_reset"
        )) {
            val view = layout.substringAfter("@+id/$id").substringBefore("/>")
            assertTrue("$id must be hidden", view.contains("android:visibility=\"gone\""))
        }
        assertTrue(layout.contains("@+id/btn_legacy_convert"))
        assertTrue(activity.contains("btnLegacyConvert?.visibility = View.VISIBLE"))
        assertTrue(activity.contains("btnReset = findViewById(R.id.btn_memory_reset)"))
        assertTrue(activity.contains("btnReset?.setOnClickListener"))
    }

    private fun source(name: String): String = find(
        "src/main/res/layout/$name",
        "app/src/main/res/layout/$name"
    ).readText()

    private fun javaSource(name: String): String = find(
        "src/main/java/org/teslasoft/assistant/ui/activities/$name",
        "app/src/main/java/org/teslasoft/assistant/ui/activities/$name"
    ).readText()

    private fun find(vararg candidates: String): File = candidates
        .map(::File)
        .firstOrNull(File::isFile)
        ?: error("source file not found")
}
