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

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.backup.BackupStatusFormatter
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthChecker
import org.teslasoft.assistant.preferences.backup.RecoveryBackupManager
import org.teslasoft.assistant.preferences.backup.RecoveryBackupState
import org.teslasoft.assistant.preferences.memory.MemoryExporter
import org.teslasoft.assistant.preferences.memory.MemorySeedCodec
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager

/**
 * "Memory Backup & Restore" — the Database Health & Backups screen (Build
 * Phase 2). Owner-approved order and wording (July 21 2026): Database Health,
 * Backup Status, Create Backup (manual recovery backup), Portable Copy,
 * Automatic Backups, Reset. The two backup LOCATIONS (manual vs automatic) are
 * kept separate.
 *
 * Two distinct systems live here and stay separate on screen:
 *  - Recovery backups (Create Backup + Automatic Backups) — same-installation
 *    per-type snapshots written to a user-chosen folder.
 *  - Portable Copy — the readable JSON export/import for moving data to another
 *    device or compatible app.
 */
class MemoryBackupRestoreActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    // 1. Database Health
    private var btnCheckIntegrity: MaterialButton? = null
    private var textResultMemory: TextView? = null
    private var textResultLorebook: TextView? = null
    private var textResultUserImage: TextView? = null

    // 2. Backup Status
    private var textStatusMemory: TextView? = null
    private var textStatusLorebooks: TextView? = null
    private var textStatusChats: TextView? = null
    private var textStatusUserImage: TextView? = null

    // 3. Create Backup (manual)
    private var textManualLocation: TextView? = null
    private var btnChangeManualLocation: MaterialButton? = null
    private var btnCreateBackup: MaterialButton? = null

    // 4. Portable Copy
    private var btnPortableExport: MaterialButton? = null
    private var btnPortableImport: MaterialButton? = null

    // 5. Automatic Backups
    private var textAutoLocation: TextView? = null
    private var btnChangeAutoLocation: MaterialButton? = null
    private var switchAutoBackup: MaterialSwitch? = null

    // 6. Reset
    private var btnReset: MaterialButton? = null

    private val importSeedLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSeedFromUri(uri)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportToUri(uri)
    }

    private val manualFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderChosen(uri, manual = true)
    }

    private val autoFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderChosen(uri, manual = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_backup_restore)

        chatId = intent.extras?.getString("chatId", "") ?: ""
        preferences = Preferences.getPreferences(this, chatId)

        bindViews()
        applyTheme()
        initLogic()
    }

    override fun onResume() {
        super.onResume()
        refreshLocations()
        refreshBackupStatus()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)

        btnCheckIntegrity = findViewById(R.id.btn_check_integrity)
        textResultMemory = findViewById(R.id.text_result_memory)
        textResultLorebook = findViewById(R.id.text_result_lorebook)
        textResultUserImage = findViewById(R.id.text_result_userimage)

        textStatusMemory = findViewById(R.id.text_status_memory)
        textStatusLorebooks = findViewById(R.id.text_status_lorebooks)
        textStatusChats = findViewById(R.id.text_status_chats)
        textStatusUserImage = findViewById(R.id.text_status_userimage)

        textManualLocation = findViewById(R.id.text_manual_location)
        btnChangeManualLocation = findViewById(R.id.btn_change_manual_location)
        btnCreateBackup = findViewById(R.id.btn_create_backup)

        btnPortableExport = findViewById(R.id.btn_portable_export)
        btnPortableImport = findViewById(R.id.btn_portable_import)

        textAutoLocation = findViewById(R.id.text_auto_location)
        btnChangeAutoLocation = findViewById(R.id.btn_change_auto_location)
        switchAutoBackup = findViewById(R.id.switch_auto_backup)

        btnReset = findViewById(R.id.btn_memory_reset)
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        ThemeManager.getThemeManager().applyTheme(this, isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true)

        if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
            window.setBackgroundDrawableResource(R.color.amoled_window_background)
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme)
                window.statusBarColor = ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme)
            }
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
            if (Build.VERSION.SDK_INT <= 34) {
                window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
                window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
            }
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        /* ---- 1. Database Health ---- */
        btnCheckIntegrity?.setOnClickListener { onCheckIntegrity() }

        /* ---- 3. Create Backup (manual) ---- */
        btnChangeManualLocation?.setOnClickListener { manualFolderPicker.launch(null) }
        btnCreateBackup?.setOnClickListener { onCreateBackup() }

        /* ---- 4. Portable Copy ---- */
        btnPortableImport?.setOnClickListener {
            importSeedLauncher.launch(arrayOf("application/json", "text/*"))
        }
        btnPortableExport?.setOnClickListener {
            if (!MemoryStore.isProvisioned(this)) {
                Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val stamp = MemoryStore.nowIso().substring(0, 10)
            exportLauncher.launch("memory-export-$stamp.json")
        }

        /* ---- 5. Automatic Backups ---- */
        btnChangeAutoLocation?.setOnClickListener { autoFolderPicker.launch(null) }
        // Set the persisted state BEFORE attaching the listener so the
        // programmatic set does not write back over itself.
        switchAutoBackup?.isChecked = RecoveryBackupState.isEnabled(this)
        switchAutoBackup?.setOnCheckedChangeListener { _, checked ->
            RecoveryBackupState.setEnabled(this, checked)
        }

        /* ---- 6. Reset (bottom) ---- */
        btnReset?.setOnClickListener { showResetDialog() }

        refreshLocations()
        refreshBackupStatus()
    }

    /* ------------------------------ 1. database health ------------------------------ */

    private fun onCheckIntegrity() {
        btnCheckIntegrity?.isEnabled = false
        runOffThread {
            val results = DatabaseHealthChecker.checkAll(this)
            runOnUiThread {
                btnCheckIntegrity?.isEnabled = true
                for (r in results) {
                    val name = when (r.type) {
                        BackupType.MEMORY -> getString(R.string.backup_db_name_memory)
                        BackupType.LOREBOOK -> getString(R.string.backup_db_name_lorebook)
                        BackupType.USER_IMAGE -> getString(R.string.backup_db_name_user_image)
                        else -> continue
                    }
                    val line = when (r.status) {
                        DatabaseHealthChecker.Status.OK -> getString(R.string.backup_check_result_ok, name)
                        DatabaseHealthChecker.Status.DAMAGED -> getString(R.string.backup_check_result_problem, name)
                        DatabaseHealthChecker.Status.UNAVAILABLE -> getString(R.string.backup_check_result_unavailable, name)
                    }
                    val tv = when (r.type) {
                        BackupType.MEMORY -> textResultMemory
                        BackupType.LOREBOOK -> textResultLorebook
                        BackupType.USER_IMAGE -> textResultUserImage
                        else -> null
                    }
                    tv?.text = line
                    tv?.visibility = View.VISIBLE
                }
            }
        }
    }

    /* ------------------------------ 2. backup status ------------------------------ */

    private fun typeLabel(type: BackupType): String = when (type) {
        BackupType.MEMORY -> getString(R.string.backup_type_memory)
        BackupType.LOREBOOK -> getString(R.string.backup_type_lorebooks)
        BackupType.CHATS -> getString(R.string.backup_type_chats)
        BackupType.USER_IMAGE -> getString(R.string.backup_type_user_image)
    }

    private fun statusTemplates() = BackupStatusFormatter.Templates(
        neverBackedUp = getString(R.string.backup_state_never),
        creating = getString(R.string.backup_state_creating),
        backedUp = getString(R.string.backup_state_backed_up),
        failedWithLastGood = getString(R.string.backup_state_failed_last_good),
        failedNoLastGood = getString(R.string.backup_state_failed_none)
    )

    private fun refreshBackupStatus() {
        runOffThread {
            val templates = statusTemplates()
            val lines = LinkedHashMap<BackupType, String>()
            for (type in BackupType.displayOrder) {
                val lastSuccess = RecoveryBackupState.getLastSuccess(this, type)
                val failed = RecoveryBackupState.getConsecutiveFailures(this, type) > 0
                lines[type] = BackupStatusFormatter.statusLine(
                    typeLabel(type), inProgress = false, lastSuccessMillis = lastSuccess, lastFailed = failed, templates = templates
                )
            }
            runOnUiThread {
                textStatusMemory?.text = lines[BackupType.MEMORY]
                textStatusLorebooks?.text = lines[BackupType.LOREBOOK]
                textStatusChats?.text = lines[BackupType.CHATS]
                textStatusUserImage?.text = lines[BackupType.USER_IMAGE]
            }
        }
    }

    private fun setAllRowsCreating() {
        val creating = getString(R.string.backup_state_creating)
        textStatusMemory?.text = "${getString(R.string.backup_type_memory)}: $creating"
        textStatusLorebooks?.text = "${getString(R.string.backup_type_lorebooks)}: $creating"
        textStatusChats?.text = "${getString(R.string.backup_type_chats)}: $creating"
        textStatusUserImage?.text = "${getString(R.string.backup_type_user_image)}: $creating"
    }

    /* ------------------------------ 3. create backup ------------------------------ */

    private fun onCreateBackup() {
        val uriStr = RecoveryBackupState.getManualFolderUri(this)
        if (uriStr == null) {
            // No location yet: the location line already reads "No backup folder
            // selected". Guide the user straight into choosing one.
            manualFolderPicker.launch(null)
            return
        }
        btnCreateBackup?.isEnabled = false
        setAllRowsCreating()
        runOffThread {
            try {
                RecoveryBackupManager.createBackup(this, Uri.parse(uriStr))
            } finally {
                runOnUiThread {
                    btnCreateBackup?.isEnabled = true
                    refreshBackupStatus()
                }
            }
        }
    }

    /* ------------------------------ backup locations ------------------------------ */

    private fun onFolderChosen(uri: Uri, manual: Boolean) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* best-effort; a lost grant surfaces later as a destination-permission failure */ }
        if (manual) RecoveryBackupState.setManualFolderUri(this, uri.toString())
        else RecoveryBackupState.setAutoFolderUri(this, uri.toString())
        refreshLocations()
    }

    private fun refreshLocations() {
        val manual = RecoveryBackupState.getManualFolderUri(this)
        val auto = RecoveryBackupState.getAutoFolderUri(this)
        textManualLocation?.text = if (manual == null) getString(R.string.backup_location_none)
        else getString(R.string.backup_current_location, folderDisplayName(Uri.parse(manual)))
        textAutoLocation?.text = if (auto == null) getString(R.string.backup_location_none)
        else getString(R.string.backup_auto_location, folderDisplayName(Uri.parse(auto)))
    }

    /** Best-effort human name for a SAF tree URI without a ContentResolver query
     *  (safe on the main thread): the last path component of the tree document
     *  id, e.g. "primary:Documents/Backups" -> "Backups". */
    private fun folderDisplayName(uri: Uri): String {
        return try {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val tail = treeId.substringAfterLast('/').substringAfterLast(':')
            if (tail.isNotBlank()) tail else treeId
        } catch (_: Exception) {
            uri.toString()
        }
    }

    /* ------------------------------ 4. portable copy ------------------------------ */

    private fun importSeedFromUri(uri: Uri) {
        runOffThread {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException(getString(R.string.memory_file_unreadable))
            importSeedJson(json)
        }
    }

    /** Runs on a worker thread. First seed into a fresh store also takes the
     *  singletons (archivist settings, retrieval policy). */
    private fun importSeedJson(json: String) {
        val data = MemorySeedCodec.parse(json)
        val store = MemoryStore.getInstance(this)
        val firstSeed = store.getMeta(MemoryStore.META_SEED_IMPORTED_AT) == null
        val report = store.importData(data, overwriteSingletons = firstSeed)
        if (firstSeed) store.setMeta(MemoryStore.META_SEED_IMPORTED_AT, MemoryStore.nowIso())
        runOnUiThread {
            Toast.makeText(this, report.summary(), Toast.LENGTH_LONG).show()
            refreshBackupStatus()
        }
    }

    /** Owner-approved blocking notice (Round 4): no chat backup can be
     *  produced while encrypted chat storage is unavailable. */
    private fun showChatBackupUnavailableDialog() {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.chat_backup_unavailable_title)
            .setMessage(R.string.chat_backup_unavailable_body)
            .setPositiveButton(R.string.chat_backup_unavailable_ok) { _, _ -> }
            .show()
    }

    private fun exportToUri(uri: Uri) {
        runOffThread {
            if (MemoryExporter.isChatListUnavailable(this)) {
                runOnUiThread { showChatBackupUnavailableDialog() }
                return@runOffThread
            }
            val json = MemoryExporter.buildExportJson(this)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                ?: throw IllegalStateException(getString(R.string.memory_file_unreadable))
            MemoryStore.getInstance(this).setMeta(MemoryStore.META_LAST_AUTO_EXPORT_AT, MemoryStore.nowIso())
            runOnUiThread {
                Toast.makeText(this, R.string.memory_export_done, Toast.LENGTH_SHORT).show()
                refreshBackupStatus()
            }
        }
    }

    /* ------------------------------ 6. reset (destructive) ------------------------------ */

    private fun showResetDialog() {
        if (!MemoryStore.isProvisioned(this)) {
            Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val backupBox = MaterialCheckBox(this).apply {
            setText(R.string.mem_reset_backup)
            isChecked = true
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(backupBox)
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.mem_reset_title)
            .setMessage(R.string.mem_reset_message)
            .setView(container)
            .setPositiveButton(R.string.mem_reset_confirm) { _, _ -> doReset(backupBox.isChecked) }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun doReset(backupFirst: Boolean) {
        runOffThread {
            var backupName: String? = null
            if (backupFirst && MemoryExporter.isChatListUnavailable(this)) {
                runOnUiThread { showChatBackupUnavailableDialog() }
                return@runOffThread
            }
            if (backupFirst) {
                backupName = MemoryExporter.writeBackupNow(this)
                if (backupName == null) {
                    runOnUiThread {
                        if (!isFinishing) {
                            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                                .setTitle(R.string.mem_reset_title)
                                .setMessage(R.string.mem_reset_backup_failed)
                                .setPositiveButton(R.string.btn_ok) { _, _ -> }
                                .show()
                        }
                    }
                    return@runOffThread
                }
            }
            MemoryStore.getInstance(this).resetAllMemoryData()
            runOnUiThread {
                val msg = if (backupName != null) getString(R.string.mem_reset_done_backup, backupName)
                else getString(R.string.mem_reset_done)
                if (!isFinishing) {
                    MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                        .setMessage(msg)
                        .setPositiveButton(R.string.btn_ok) { _, _ -> }
                        .show()
                }
                refreshBackupStatus()
            }
        }
    }

    /* ------------------------------ helpers ------------------------------ */

    /** All store work off the main thread, all failures as a toast — never a crash. */
    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.memory_operation_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            actionBar?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )
            val scroll = findViewById<ScrollView>(R.id.scroll)
            scroll?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + pxToDp(24)
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density).toInt()
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            else -> false
        }
    }
}
