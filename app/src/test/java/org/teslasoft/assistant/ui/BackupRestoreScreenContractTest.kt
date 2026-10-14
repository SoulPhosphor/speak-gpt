package org.teslasoft.assistant.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreScreenContractTest {

    @Test
    fun mainSettingsPlacesBackupRestoreImmediatelyBeforeAlerts() {
        val layout = source("activity_settings.xml")
        val appearance = layout.indexOf("@+id/tile_appearance")
        val backup = layout.indexOf("@+id/tile_backup_restore")
        val alerts = layout.indexOf("@+id/tile_alert_debug_menu")
        assertTrue(appearance >= 0 && backup > appearance && alerts > backup)
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
        val options = layout.indexOf("@+id/row_backup_options")
        val optionsLayout = source("activity_backup_options.xml")
        val automatic = optionsLayout.indexOf("@layout/section_automatic_backups")
        val recovery = optionsLayout.indexOf("@string/backup_create_section")
        val readable = optionsLayout.indexOf("@string/backup_readable_section")
        val converter = layout.indexOf("@+id/btn_legacy_convert")
        val restoreLayout = source("activity_restore_options.xml")
        val restoreTitle = restoreLayout.indexOf("@string/restore_data_section")
        val categories = restoreLayout.indexOf("@+id/restore_category_chats")
        val portableRestore = restoreLayout.indexOf("@+id/btn_portable_restore")
        val databaseType = restoreLayout.indexOf("@+id/btn_restore_type\"")
        val databaseRestore = restoreLayout.indexOf("@+id/btn_restore_database\"")

        assertTrue(backupTitle >= 0)
        assertTrue(options > backupTitle)
        assertTrue(automatic >= 0 && recovery > automatic)
        assertTrue(readable > optionsLayout.indexOf("@+id/btn_create_recovery"))
        assertTrue(!layout.contains("@layout/section_automatic_backups"))
        assertTrue(!layout.contains("@+id/btn_create_recovery"))
        assertTrue(optionsLayout.contains("@+id/btn_create_recovery"))
        assertTrue(!layout.contains("@string/backup_readable_section"))
        assertTrue(!optionsLayout.contains("ImageButton"))
        val optionsRow = layout.substringAfter("@+id/row_backup_options")
            .substringBefore("<ImageView")
        assertTrue(!optionsRow.contains("Row.Subtitle"))
        assertTrue(javaSource("MemoryBackupRestoreActivity.kt").contains("Intent(this, BackupOptionsActivity::class.java)"))
        assertTrue(javaSource("BackupOptionsActivity.kt").contains("RecoveryBackupActivity.EXTRA_RECOVERY_PROTECTED"))
        assertTrue(converter > options)
        assertTrue(restoreTitle >= 0)
        assertTrue(layout.indexOf("@+id/row_restore_options") > options)
        assertTrue(!layout.contains("@+id/btn_restore_database"))
        assertTrue(!restoreLayout.contains("ImageButton"))
        assertTrue(categories > restoreTitle)
        assertTrue(portableRestore > categories)
        assertTrue(databaseType > portableRestore)
        assertTrue(databaseRestore > databaseType)
    }

    @Test
    fun readableContentUsesRestoreLabelsAndSubtitlesWithChatOptionsImmediatelyAfterChats() {
        val layout = source("activity_backup_options.xml")
        val restore = source("activity_restore_options.xml")
        val categories = listOf("activation_prompts", "profile_images", "chats", "companions",
            "generated_images", "glamours", "lorebooks", "memories", "model_settings", "model_rules",
            "roleplay", "settings", "system_prompts")
        for (category in categories) {
            assertTrue(layout.contains("@+id/switch_readable_$category"))
            assertTrue(layout.contains("@string/restore_category_$category"))
            assertTrue(layout.contains("@string/restore_category_${category}_desc"))
            assertTrue(restore.contains("@+id/restore_category_$category"))
        }
        assertTrue(!layout.contains("CheckBox"))
        assertTrue(!layout.contains("btn_readable_content"))
        val chats = layout.indexOf("@+id/switch_readable_chats")
        val thinking = layout.indexOf("@+id/switch_readable_include_thinking")
        val folders = layout.indexOf("@+id/switch_readable_include_folders")
        assertTrue(chats < thinking && thinking < folders && folders < layout.indexOf("@+id/switch_readable_companions"))
        val desc = layout.indexOf("@string/backup_readable_desc")
        val saved = layout.indexOf("@+id/text_readable_last_success")
        assertTrue(saved > desc && saved < layout.indexOf("@+id/btn_readable_scope"))
    }

    @Test
    fun restoreCategoriesUseCompactCheckboxAndModeRows() {
        val layout = source("activity_restore_options.xml")
        val row = source("view_restore_category.xml")
        assertTrue(row.contains("@+id/category_check"))
        assertTrue(row.contains("@+id/category_mode"))
        assertTrue(row.contains("@+id/category_title"))
        assertTrue(!row.contains("restore_mode_label"))
        assertTrue(row.contains("@string/restore_mode_replace_only"))
        assertTrue(row.contains("Widget.App.Restore.Category.Mode"))
        assertTrue(row.contains("Widget.App.Restore.Category.Title"))
        assertTrue(layout.split("org.teslasoft.assistant.ui.views.RestoreCategoryView").size - 1 == 13)
        assertTrue(layout.contains("@+id/restore_model_credentials_note"))
        val view = find(
            "src/main/java/org/teslasoft/assistant/ui/views/RestoreCategoryView.kt",
            "app/src/main/java/org/teslasoft/assistant/ui/views/RestoreCategoryView.kt"
        ).readText()
        assertTrue(view.contains("modeView.visibility = if (mergeSupported) View.VISIBLE else View.GONE"))
    }

    @Test
    fun chatRestoreTogglesKeepTheApprovedSentenceText() {
        val strings = find(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml"
        ).readText()
        val layout = source("activity_restore_options.xml")
        assertTrue(strings.contains("Always replace old chats with newer ones."))
        assertTrue(strings.contains("Automatically keep both old and new chats if older one has more messages."))
        assertTrue(layout.contains("@+id/switch_restore_chat_always_newer"))
        assertTrue(layout.contains("@+id/switch_restore_chat_keep_both"))
        assertTrue(strings.contains("<string name=\"restore_data_section\">Restore Data</string>"))
    }

    @Test
    fun restoreCategoriesAreListedAlphabetically() {
        val layout = source("activity_restore_options.xml")
        val ids = listOf(
            "restore_category_activation_prompts",
            "restore_category_profile_images",
            "restore_category_chats",
            "restore_category_companions",
            "restore_category_generated_images",
            "restore_category_glamours",
            "restore_category_lorebooks",
            "restore_category_memories",
            "restore_category_model_settings",
            "restore_model_credentials_note",
            "restore_category_model_rules",
            "restore_category_roleplay",
            "restore_category_settings",
            "restore_category_system_prompts"
        ).map { layout.indexOf("@+id/$it\"") }
        assertTrue(ids.all { it >= 0 })
        assertTrue(ids == ids.sorted())
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
            "btn_memory_reset"
        )) {
            val view = layout.substringAfter("@+id/$id").substringBefore("/>")
            assertTrue("$id must be hidden", view.contains("android:visibility=\"gone\""))
        }
        assertTrue(layout.contains("@+id/btn_legacy_convert"))
        assertTrue(activity.contains("btnLegacyConvert?.visibility = View.VISIBLE"))
        assertTrue(activity.contains("btnReset = findViewById(R.id.btn_memory_reset)"))
        assertTrue(activity.contains("btnReset?.setOnClickListener"))
        assertTrue(!layout.contains("_old"))
        assertTrue(!layout.contains("@+id/btn_restore_from_backup"))
    }

    @Test
    fun folderCollisionUsesRenderedCancelMergeCreateOrder() {
        val activity = javaSource("MemoryBackupRestoreActivity.kt")
        val collision = activity.substringAfter("private fun showFolderCollision(")
            .substringBefore("private fun showNewFolderName(")
        assertTrue(collision.contains("setNeutralButton(R.string.btn_cancel)"))
        assertTrue(collision.contains("setNegativeButton(R.string.portable_folder_merge)"))
        assertTrue(collision.contains("setPositiveButton(R.string.portable_folder_create_new)"))
    }

    @Test
    fun restoreFailuresDoNotRenderEnumNamesAsCopy() {
        val activity = javaSource("MemoryBackupRestoreActivity.kt")
        val outcomeFlow = find(
            "src/main/java/org/teslasoft/assistant/ui/PortableRestoreOutcomeFlow.kt",
            "app/src/main/java/org/teslasoft/assistant/ui/PortableRestoreOutcomeFlow.kt"
        ).readText()
        assertTrue(!activity.contains("failed.reason.name.lowercase()"))
        assertTrue(outcomeFlow.contains("chatValidationMessage"))
        assertTrue(outcomeFlow.contains("portable_restore_category_failure"))
        // Phase 12.5 persists the structured report before restart and the
        // recreated screen delegates its one-time rendering to the shared flow.
        assertTrue(activity.contains("PortableRestoreOutcomeFlow.showIfPending"))
    }

    @Test
    fun pendingOuterRestoreIsVisibleAndDoesNotAddDiagnosticLogging() {
        val application = find(
            "src/main/java/org/teslasoft/assistant/app/MainApplication.kt",
            "app/src/main/java/org/teslasoft/assistant/app/MainApplication.kt"
        ).readText()
        val recoveryFlow = find(
            "src/main/java/org/teslasoft/assistant/ui/PortableRestoreRecoveryFlow.kt",
            "app/src/main/java/org/teslasoft/assistant/ui/PortableRestoreRecoveryFlow.kt"
        ).readText()
        assertTrue(application.contains("UnifiedPortableRestoreCoordinator.recoverPending(this)"))
        assertTrue(!application.contains("MemoryLog.log(this, \"PortableRestore\""))
        assertTrue(recoveryFlow.contains("portable_restore_pending_message"))
        assertTrue(recoveryFlow.contains("portable_restore_retry_recovery"))
        assertTrue(recoveryFlow.contains("setCancelable(false)"))
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
