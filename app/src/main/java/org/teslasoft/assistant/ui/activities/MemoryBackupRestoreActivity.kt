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
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.ListPopupWindow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import android.provider.DocumentsContract
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.backup.AutoBackupController
import org.teslasoft.assistant.preferences.backup.AutoBackupFailureReason
import org.teslasoft.assistant.preferences.backup.AutoBackupScheduler
import org.teslasoft.assistant.preferences.backup.AutoBackupScheduling
import org.teslasoft.assistant.preferences.backup.AutoBackupStatusPresenter
import org.teslasoft.assistant.preferences.backup.BackupBrand
import org.teslasoft.assistant.preferences.backup.BackupFailurePolicy
import org.teslasoft.assistant.preferences.backup.BackupFrequency
import org.teslasoft.assistant.preferences.backup.BackupLocationDisplay
import org.teslasoft.assistant.preferences.backup.BackupStatusFormatter
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.ByteSizeFormatter
import org.teslasoft.assistant.preferences.backup.DatabaseHealthChecker
import org.teslasoft.assistant.preferences.backup.DatabaseHealthState
import org.teslasoft.assistant.preferences.backup.DatabaseRepairManager
import org.teslasoft.assistant.preferences.backup.RecoveryBackupState
import org.teslasoft.assistant.preferences.backup.RecoveryFileNaming
import org.teslasoft.assistant.ui.DatabaseRecoveryFlows
import org.teslasoft.assistant.preferences.backup.readable.ReadableBackupState
import org.teslasoft.assistant.preferences.backup.readable.ReadableChatBackup
import org.teslasoft.assistant.preferences.memory.MemoryExporter
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemorySeedCodec
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * "Memory Backup & Restore" — the Database Health & Backups screen. Section
 * order is owner-directed and EXACT (July 24 2026, supersedes the July 22
 * order): 1. Backup Status, 2. Database Health, 3. Recovery Backup,
 * 4. Human-Readable Chat Backup, 5. Portable Data Copy, 6. Automatic Backups,
 * 7. Reset. Backup Status leads because it's always current on open, unlike
 * Database Health's result lines which stay blank until the check button is
 * pressed. Do not reorder. The two backup LOCATIONS (manual vs automatic)
 * are kept separate.
 *
 * Three distinct systems live here and stay separate on screen (never
 * conflated — owner directive):
 *  - Recovery Backup — the portable recovery package (RecoveryBackupActivity).
 *  - Human-Readable Chat Backup — a ZIP of chats as readable Text/JSON files.
 *  - Portable Data Copy — the readable JSON export/import of memory data
 *    (import does NOT restore chats; the description says so).
 *
 * NO TOASTS anywhere in this workflow (owner rule): results and failures are
 * persistent inline status text or Material dialogs. Location lines show a
 * persisted friendly folder label — never a raw URI or tree document id.
 */
class MemoryBackupRestoreActivity : FragmentActivity() {

    companion object {
        /** Intent extra: a BackupType key whose A1 repair dialog should open
         *  immediately (the A2 chat banner's Repair action lands here). */
        const val EXTRA_START_REPAIR_FOR = "startRepairFor"
    }

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    // 2. Database Health (on-screen order; see class doc — Backup Status leads)
    private var btnCheckIntegrity: MaterialButton? = null
    private var textCheckProgress: TextView? = null
    private var textResultMemory: TextView? = null
    private var textResultLorebook: TextView? = null
    private var textResultUserImage: TextView? = null

    // One 3-strikes backup-failure dialog per screen visit at most, so a
    // resume loop cannot nag (Build Phase 3 item 8).
    private var backupFailureDialogShown = false

    // 1. Backup Status
    private var textStatusMemory: TextView? = null
    private var textStatusLorebooks: TextView? = null
    private var textStatusChats: TextView? = null
    private var textStatusUserImage: TextView? = null

    // Per-database inline recovery actions, shown under a row ONLY while that
    // database is degraded (corruption confirmed) — not for space/permission
    // backup failures, and never for Chats (not a database). Same actions as
    // the A1 dialog's Repair / Revert.
    private var healthActionsMemory: LinearLayout? = null
    private var healthActionsLorebook: LinearLayout? = null
    private var healthActionsUserImage: LinearLayout? = null
    private var btnHealthRepairMemory: MaterialButton? = null
    private var btnHealthRevertMemory: MaterialButton? = null
    private var btnHealthRepairLorebook: MaterialButton? = null
    private var btnHealthRevertLorebook: MaterialButton? = null
    private var btnHealthRepairUserImage: MaterialButton? = null
    private var btnHealthRevertUserImage: MaterialButton? = null

    // 3. Recovery Backup (manual)
    private var btnCreateRecovery: MaterialButton? = null
    private var textManualLocation: TextView? = null
    private var btnChangeManualLocation: MaterialButton? = null
    private var btnCreateBackup: MaterialButton? = null
    private var textRecoveryTypeProtectedSummary: TextView? = null
    private var textRecoveryTypeUnencryptedSummary: TextView? = null
    private var btnRecoveryType: TextView? = null
    private var recoveryTypeProtected = true

    // 4. Human-Readable Chat Backup (Widget.App.Dropdown.* fields)
    private var btnReadableScope: TextView? = null
    private var btnReadableFormat: TextView? = null
    private var btnReadableCreate: MaterialButton? = null
    private var textReadableStatus: TextView? = null

    // 5. Portable Data Copy
    private var btnPortableExport: MaterialButton? = null
    private var btnPortableImport: MaterialButton? = null
    private var textPortableStatus: TextView? = null

    // 6. Automatic Backups. The toggle and frequency dropdown are restored/
    // visible (owner ruling, July 22 2026) and persist the user's choice
    // ahead of the portable automatic WRITER existing (that piece is still
    // unbuilt - these controls don't make anything run on a schedule yet).
    private var switchAutoBackup: MaterialSwitch? = null
    private var btnAutoFrequency: TextView? = null
    private var autoFrequency = BackupFrequency.DAILY
    private var textAutoLocation: TextView? = null
    private var textAutoStatus: TextView? = null
    private var spinnerAutoStatus: CircularProgressIndicator? = null
    private var btnChangeAutoLocation: MaterialButton? = null

    // True while THIS screen's own manual trigger (the 3-strikes dialog's
    // Retry action) is running an automatic pass — shown immediately,
    // without waiting for AutoBackupController.isRunning() to be polled on a
    // refresh. refreshAutoStatus() also checks isRunning() directly, so a
    // pass started elsewhere (WorkManager / app-open) while this screen is
    // open and gets refreshed is reflected too.
    private var manualAutoBackupRunning = false

    // Guards the auto-backup switch's listener from firing while we set its
    // checked state programmatically (revert-on-cancel, initial load).
    private var suppressAutoToggle = false

    // The user flipped the toggle ON with no destination yet: the folder picker
    // is open, and enabling completes only once a folder is actually chosen. If
    // they cancel the picker, the toggle reverts to off.
    private var pendingEnableAfterPick = false

    // 7. Reset
    private var btnReset: MaterialButton? = null

    private val importSeedLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSeedFromUri(uri)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportToUri(uri)
    }

    private val autoFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderChosen(uri) else onFolderPickCancelled()
    }

    private val readableSaveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> onReadableSaveAsResult(uri) }

    // Human-Readable Chat Backup selections + the staged, verified ZIP waiting
    // for its Save As destination (build-before-Save-As, same architecture as
    // the recovery package). Staging is cleaned on cancel, success, failure,
    // and onDestroy; the incremental baseline advances ONLY after the
    // destination has been verified.
    private var readableScopeAll = true
    private var readableFormat = ReadableChatBackup.Format.TEXT
    private var stagedReadable: File? = null
    private var stagedReadableSha: ByteArray? = null
    private var stagedReadableFingerprints: Map<String, String>? = null
    private var stagedReadableIncremental = false

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
        refreshAutoStatus()
        // Build Phase 3 dialog delivery: a queued startup health notice (A1 /
        // Database Repaired / A6), a repair explicitly requested from the A2
        // chat banner, then the 3-strikes backup-failure sweep — one dialog
        // at a time.
        val repairFor = intent.getStringExtra(EXTRA_START_REPAIR_FOR)
        if (repairFor != null) {
            intent.removeExtra(EXTRA_START_REPAIR_FOR)
            val type = BackupType.fromKey(repairFor)
            if (type != null && DatabaseHealthState.isDegraded(this, type)) {
                DatabaseRecoveryFlows.showProblemDialog(this, type) { refreshBackupStatus() }
                return
            }
        }
        DatabaseRecoveryFlows.showPendingNoticeIfAny(this) { refreshBackupStatus() }
        sweepRepeatedBackupFailures()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)

        btnCheckIntegrity = findViewById(R.id.btn_check_integrity)
        textCheckProgress = findViewById(R.id.text_check_progress)
        textResultMemory = findViewById(R.id.text_result_memory)
        textResultLorebook = findViewById(R.id.text_result_lorebook)
        textResultUserImage = findViewById(R.id.text_result_userimage)

        textStatusMemory = findViewById(R.id.text_status_memory)
        textStatusLorebooks = findViewById(R.id.text_status_lorebooks)
        textStatusChats = findViewById(R.id.text_status_chats)
        textStatusUserImage = findViewById(R.id.text_status_userimage)

        healthActionsMemory = findViewById(R.id.health_actions_memory)
        healthActionsLorebook = findViewById(R.id.health_actions_lorebook)
        healthActionsUserImage = findViewById(R.id.health_actions_userimage)
        btnHealthRepairMemory = findViewById(R.id.btn_health_repair_memory)
        btnHealthRevertMemory = findViewById(R.id.btn_health_revert_memory)
        btnHealthRepairLorebook = findViewById(R.id.btn_health_repair_lorebook)
        btnHealthRevertLorebook = findViewById(R.id.btn_health_revert_lorebook)
        btnHealthRepairUserImage = findViewById(R.id.btn_health_repair_userimage)
        btnHealthRevertUserImage = findViewById(R.id.btn_health_revert_userimage)

        btnCreateRecovery = findViewById(R.id.btn_create_recovery)
        textManualLocation = findViewById(R.id.text_manual_location)
        btnChangeManualLocation = findViewById(R.id.btn_change_manual_location)
        btnCreateBackup = findViewById(R.id.btn_create_backup)
        textRecoveryTypeProtectedSummary = findViewById(R.id.text_recovery_type_protected_summary)
        textRecoveryTypeUnencryptedSummary = findViewById(R.id.text_recovery_type_unencrypted_summary)
        btnRecoveryType = findViewById(R.id.btn_recovery_type)

        btnReadableScope = findViewById(R.id.btn_readable_scope)
        btnReadableFormat = findViewById(R.id.btn_readable_format)
        btnReadableCreate = findViewById(R.id.btn_readable_create)
        textReadableStatus = findViewById(R.id.text_readable_status)

        btnPortableExport = findViewById(R.id.btn_portable_export)
        btnPortableImport = findViewById(R.id.btn_portable_import)
        textPortableStatus = findViewById(R.id.text_portable_status)

        switchAutoBackup = findViewById(R.id.switch_auto_backup)
        btnAutoFrequency = findViewById(R.id.btn_auto_frequency)
        textAutoLocation = findViewById(R.id.text_auto_location)
        textAutoStatus = findViewById(R.id.text_auto_status)
        spinnerAutoStatus = findViewById(R.id.spinner_auto_status)
        btnChangeAutoLocation = findViewById(R.id.btn_change_auto_location)

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

        /* ---- 1. Backup Status: per-database inline recovery actions ---- */
        // Same entry points as the A1 dialog: runRepair is non-destructive
        // salvage; runRevert verifies a backup and shows the "Restore from
        // Backup?" confirmation before overwriting. Both refresh the rows
        // (and re-hide these buttons) when the store is healthy again.
        btnHealthRepairMemory?.setOnClickListener {
            DatabaseRecoveryFlows.runRepair(this, BackupType.MEMORY) { refreshBackupStatus() }
        }
        btnHealthRevertMemory?.setOnClickListener {
            DatabaseRecoveryFlows.runRevert(this, BackupType.MEMORY) { refreshBackupStatus() }
        }
        btnHealthRepairLorebook?.setOnClickListener {
            DatabaseRecoveryFlows.runRepair(this, BackupType.LOREBOOK) { refreshBackupStatus() }
        }
        btnHealthRevertLorebook?.setOnClickListener {
            DatabaseRecoveryFlows.runRevert(this, BackupType.LOREBOOK) { refreshBackupStatus() }
        }
        btnHealthRepairUserImage?.setOnClickListener {
            DatabaseRecoveryFlows.runRepair(this, BackupType.USER_IMAGE) { refreshBackupStatus() }
        }
        btnHealthRevertUserImage?.setOnClickListener {
            DatabaseRecoveryFlows.runRevert(this, BackupType.USER_IMAGE) { refreshBackupStatus() }
        }

        /* ---- 2. Database Health ---- */
        btnCheckIntegrity?.setOnClickListener { onCheckIntegrity() }

        /* ---- 3. Recovery Backup (manual) ---- */
        // The installation-bound v1 controls (btn_change_manual_location,
        // btn_create_backup) are hidden AND unwired: the old writer must not be
        // reachable from this screen (owner correction, July 22 2026). The v1
        // backend classes stay for the future automatic implementation.
        initRecoveryTypeSection()
        btnCreateRecovery?.setOnClickListener {
            startActivity(
                Intent(this, RecoveryBackupActivity::class.java)
                    .putExtra(RecoveryBackupActivity.EXTRA_RECOVERY_PROTECTED, recoveryTypeProtected)
            )
        }

        /* ---- 4. Human-Readable Chat Backup ---- */
        initReadableSection()

        /* ---- 5. Portable Data Copy ---- */
        btnPortableImport?.setOnClickListener {
            importSeedLauncher.launch(arrayOf("application/json", "text/*"))
        }
        btnPortableExport?.setOnClickListener {
            if (!MemoryStore.isProvisioned(this)) {
                showNoticeDialog(getString(R.string.memory_not_provisioned_toast))
                return@setOnClickListener
            }
            val stamp = MemoryStore.nowIso().substring(0, 10)
            exportLauncher.launch("memory-export-$stamp.json")
        }

        /* ---- 6. Automatic Backups: enabled flag + frequency + destination
             drive the WorkManager job and the app-open catch-up check. A
             valid, writable destination is REQUIRED before enabling — flipping
             the toggle on with no folder opens the picker and completes the
             enable only once a folder is chosen. ---- */
        suppressAutoToggle = true
        switchAutoBackup?.isChecked = RecoveryBackupState.isEnabled(this)
        suppressAutoToggle = false
        switchAutoBackup?.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAutoToggle) return@setOnCheckedChangeListener
            onAutoToggle(isChecked)
        }
        initAutoFrequencySection()
        // Changing the folder here is a plain "change location" (never a
        // pending-enable), so make sure the flag is clear before launching.
        btnChangeAutoLocation?.setOnClickListener {
            pendingEnableAfterPick = false
            autoFolderPicker.launch(null)
        }

        /* ---- 7. Reset (bottom) ---- */
        btnReset?.setOnClickListener { showResetDialog() }

        refreshLocations()
        refreshBackupStatus()
    }

    /** A persistent notice the user dismisses — never a toast (owner rule). */
    private fun showNoticeDialog(message: String, title: String? = null) {
        if (isFinishing) return
        val b = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(message)
            .setPositiveButton(R.string.btn_ok) { _, _ -> }
        if (title != null) b.setTitle(title)
        b.show()
    }

    override fun onDestroy() {
        cleanupStagedReadable()
        super.onDestroy()
    }

    /* ------------------------------ Widget.App.Dropdown.* shared helper ------------------------------ */

    /** Anchored dropdown list (Widget.App.Dropdown.* contract) - a real
     *  dropdown attached to the tapped value, matching the Summoning Circle
     *  tiles' `showTileDropdown`, minus an edit button - never the centered
     *  picker dialog. Shared by every Widget.App.Dropdown.* field on this
     *  screen (Recovery Type, Backup Style, Format). */
    private fun showFieldDropdown(anchor: View, labels: List<String>, onPick: (Int) -> Unit) {
        if (isFinishing || labels.isEmpty()) return
        val popup = ListPopupWindow(this)
        popup.anchorView = anchor
        popup.isModal = true
        popup.width = anchor.width
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onPick(position)
        }
        popup.show()
    }

    /* ------------------------------ 3. recovery backup (manual) ------------------------------ */

    private fun initRecoveryTypeSection() {
        // Same "Title: Description" wording as the Recovery Backup screen's
        // choice panel, reused verbatim via the shared format string so the
        // two can never drift apart (owner ruling, July 22 2026).
        textRecoveryTypeProtectedSummary?.text = getString(
            R.string.recovery_type_summary_format,
            getString(R.string.recovery_choice_protected_title),
            getString(R.string.recovery_choice_protected_desc)
        )
        textRecoveryTypeUnencryptedSummary?.text = getString(
            R.string.recovery_type_summary_format,
            getString(R.string.recovery_choice_unencrypted_title),
            getString(R.string.recovery_choice_unencrypted_desc)
        )
        // Remember the last-selected Recovery Type across visits, the same
        // way Backup Style and Format below do.
        recoveryTypeProtected = RecoveryBackupState.getLastRecoveryProtected(this)
        updateRecoveryTypeLabel()
        btnRecoveryType?.setOnClickListener { pickRecoveryType() }
    }

    private fun updateRecoveryTypeLabel() {
        btnRecoveryType?.text = getString(
            if (recoveryTypeProtected) R.string.recovery_choice_protected_title
            else R.string.recovery_choice_unencrypted_title
        )
    }

    private fun pickRecoveryType() {
        val anchor = btnRecoveryType ?: return
        val labels = listOf(
            getString(R.string.recovery_choice_protected_title),
            getString(R.string.recovery_choice_unencrypted_title)
        )
        showFieldDropdown(anchor, labels) { position ->
            recoveryTypeProtected = position == 0
            RecoveryBackupState.setLastRecoveryProtected(this, recoveryTypeProtected)
            updateRecoveryTypeLabel()
        }
    }

    /* ------------------------------ 4. human-readable chat backup ------------------------------ */

    private fun initReadableSection() {
        // Remember the last-selected option across visits (owner ruling,
        // July 22 2026) - never reset to the default just because the
        // screen was reopened.
        readableScopeAll = ReadableBackupState.getScopeAll(this)
        readableFormat = ReadableBackupState.getFormat(this)
        updateReadableSelectorLabels()
        btnReadableScope?.setOnClickListener { pickReadableScope() }
        btnReadableFormat?.setOnClickListener { pickReadableFormat() }
        btnReadableCreate?.setOnClickListener { onCreateReadableBackup() }
        showReadableLastSuccess()
    }

    private fun updateReadableSelectorLabels() {
        btnReadableScope?.text = getString(
            if (readableScopeAll) R.string.backup_readable_scope_all
            else R.string.backup_readable_scope_incremental
        )
        btnReadableFormat?.text = getString(
            when (readableFormat) {
                ReadableChatBackup.Format.TEXT -> R.string.backup_readable_format_text
                ReadableChatBackup.Format.JSON -> R.string.backup_readable_format_json
                ReadableChatBackup.Format.BOTH -> R.string.backup_readable_format_both
            }
        )
    }

    private fun pickReadableScope() {
        val anchor = btnReadableScope ?: return
        val labels = listOf(
            getString(R.string.backup_readable_scope_all),
            getString(R.string.backup_readable_scope_incremental)
        )
        showFieldDropdown(anchor, labels) { position ->
            readableScopeAll = position == 0
            ReadableBackupState.setScopeAll(this, readableScopeAll)
            updateReadableSelectorLabels()
        }
    }

    private fun pickReadableFormat() {
        val anchor = btnReadableFormat ?: return
        val labels = listOf(
            getString(R.string.backup_readable_format_text),
            getString(R.string.backup_readable_format_json),
            getString(R.string.backup_readable_format_both)
        )
        showFieldDropdown(anchor, labels) { position ->
            readableFormat = when (position) {
                1 -> ReadableChatBackup.Format.JSON
                2 -> ReadableChatBackup.Format.BOTH
                else -> ReadableChatBackup.Format.TEXT
            }
            ReadableBackupState.setFormat(this, readableFormat)
            updateReadableSelectorLabels()
        }
    }

    private fun setReadableStatus(text: String) {
        textReadableStatus?.text = text
        textReadableStatus?.visibility = View.VISIBLE
    }

    private fun showReadableLastSuccess() {
        val last = ReadableBackupState.getLastSuccess(this)
        if (last > 0L) {
            val sizeValue = ReadableBackupState.getLastSuccessSizeBytes(this)?.let { ByteSizeFormatter.format(it) }
                ?: getString(R.string.backup_size_unavailable)
            setReadableStatus(
                getString(R.string.backup_readable_last_success, BackupStatusFormatter.formatDateTime(last)) +
                    "\n" + getString(R.string.backup_readable_file_size, sizeValue)
            )
        }
    }

    /** Build + verify the ZIP in private staging FIRST; Save As launches only
     *  when the staged file is real and verified. */
    private fun onCreateReadableBackup() {
        btnReadableCreate?.isEnabled = false
        setReadableStatus(getString(R.string.backup_readable_preparing))
        cleanupStagedReadable()
        val allChats = readableScopeAll
        val format = readableFormat
        runOffThread {
            val staged = File(cacheDir, "readable_stage_${System.nanoTime()}.zip")
            when (val result = ReadableChatBackup.build(this, staged, allChats, format)) {
                is ReadableChatBackup.BuildResult.NothingNew -> runOnUiThread {
                    btnReadableCreate?.isEnabled = true
                    setReadableStatus(getString(R.string.backup_readable_none))
                }
                is ReadableChatBackup.BuildResult.NothingToBackUp -> runOnUiThread {
                    btnReadableCreate?.isEnabled = true
                    setReadableStatus(getString(R.string.recovery_fail_nothing))
                }
                is ReadableChatBackup.BuildResult.ChatsUnreadable -> runOnUiThread {
                    btnReadableCreate?.isEnabled = true
                    setReadableStatus(getString(R.string.backup_readable_fail_chats))
                    showNoticeDialog(getString(R.string.backup_readable_fail_chats))
                }
                is ReadableChatBackup.BuildResult.Failed -> runOnUiThread {
                    btnReadableCreate?.isEnabled = true
                    setReadableStatus(getString(R.string.backup_readable_fail_generic))
                    showNoticeDialog(getString(R.string.backup_readable_fail_generic))
                }
                is ReadableChatBackup.BuildResult.Ok -> {
                    val sha = sha256(staged)
                    runOnUiThread {
                        stagedReadable = staged
                        stagedReadableSha = sha
                        stagedReadableFingerprints = result.fingerprints
                        stagedReadableIncremental = !allChats
                        readableSaveLauncher.launch(
                            RecoveryFileNaming.readableChats(
                                BackupBrand.resolve(this), complete = allChats,
                                epochMillis = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    /** Save As returned. Null == cancelled: the staged ZIP is discarded and
     *  the baseline stays untouched. */
    private fun onReadableSaveAsResult(uri: Uri?) {
        if (uri == null) {
            cleanupStagedReadable()
            btnReadableCreate?.isEnabled = true
            setReadableStatus(getString(R.string.backup_readable_cancelled))
            return
        }
        val staged = stagedReadable
        val expectedSha = stagedReadableSha
        val fingerprints = stagedReadableFingerprints
        if (staged == null || expectedSha == null || fingerprints == null || !staged.exists()) {
            cleanupStagedReadable()
            btnReadableCreate?.isEnabled = true
            setReadableStatus(getString(R.string.backup_readable_fail_generic))
            return
        }
        val incremental = stagedReadableIncremental
        setReadableStatus(getString(R.string.backup_readable_saving))
        runOffThread {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    staged.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("could not open destination")

                val (actualSha, verifiedSize) = contentResolver.openInputStream(uri)?.use { sha256WithSize(it) }
                    ?: throw IllegalStateException("could not reopen destination")

                if (!MessageDigest.isEqual(expectedSha, actualSha)) {
                    discardReadableDestination(uri)
                    runOnUiThread {
                        btnReadableCreate?.isEnabled = true
                        setReadableStatus(getString(R.string.backup_readable_fail_verify))
                        showNoticeDialog(getString(R.string.backup_readable_fail_verify))
                    }
                    return@runOffThread
                }

                // Destination verified: NOW (and only now) the incremental
                // baseline may advance (owner directive) and the last-success
                // stamp may move. verifiedSize is the FINAL destination file's
                // exact size, measured during this same verification read —
                // never the staged file's size, never an estimate.
                if (incremental) ReadableBackupState.setBaseline(this, fingerprints)
                ReadableBackupState.setLastSuccess(this, System.currentTimeMillis())
                ReadableBackupState.setLastSuccessSizeBytes(this, verifiedSize)

                val where = BackupLocationDisplay.describeSaveAs(this, uri)
                runOnUiThread {
                    btnReadableCreate?.isEnabled = true
                    val destination = where.breadcrumb ?: where.providerLabel
                    val main = if (destination != null) {
                        getString(R.string.backup_readable_saved_to, destination)
                    } else {
                        getString(R.string.backup_readable_saved_generic)
                    }
                    val detail = where.fileName?.let { "\n" + getString(R.string.recovery_saved_file, it) } ?: ""
                    setReadableStatus(main + detail)
                }
            } catch (e: Exception) {
                discardReadableDestination(uri)
                try {
                    MemoryLog.log(this, "ReadableChatBackup", "error",
                        "Saving the readable chat backup failed (${e.javaClass.simpleName}).")
                } catch (_: Exception) { }
                runOnUiThread {
                    btnReadableCreate?.isEnabled = true
                    setReadableStatus(getString(R.string.backup_readable_fail_generic))
                    showNoticeDialog(getString(R.string.backup_readable_fail_generic))
                }
            } finally {
                cleanupStagedReadable()
            }
        }
    }

    /** Remove a written-but-unverified destination so no corrupt ZIP remains:
     *  delete the document, or truncate it when delete is not permitted. */
    private fun discardReadableDestination(uri: Uri) {
        try {
            if (DocumentsContract.deleteDocument(contentResolver, uri)) return
        } catch (_: Exception) { /* fall through to truncate */ }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { /* truncate */ }
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun cleanupStagedReadable() {
        stagedReadable?.let { f -> runCatching { if (f.exists()) f.delete() } }
        stagedReadable = null
        stagedReadableSha = null
        stagedReadableFingerprints = null
        stagedReadableIncremental = false
    }

    private fun sha256(file: File): ByteArray = file.inputStream().use { sha256(it) }

    private fun sha256(stream: InputStream): ByteArray = sha256WithSize(stream).first

    /** Streaming SHA-256 that also counts the exact bytes read in the same
     *  pass — the FINAL verified destination file's real size, never an
     *  estimate and never an extra IPC/metadata round trip (see
     *  RecoveryBackupManager.sha256WithSize for the identical rationale on
     *  the automatic-backup path). */
    private fun sha256WithSize(stream: InputStream): Pair<ByteArray, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
        }
        return md.digest() to total
    }

    /* ------------------------------ 1. database health ------------------------------ */

    private fun onCheckIntegrity() {
        btnCheckIntegrity?.isEnabled = false
        // §15.9 verbatim in-progress line while the check runs; the result
        // lines replace it when it finishes.
        textCheckProgress?.visibility = View.VISIBLE
        textResultMemory?.visibility = View.GONE
        textResultLorebook?.visibility = View.GONE
        textResultUserImage?.visibility = View.GONE
        runOffThread {
            val results = DatabaseHealthChecker.checkAll(this)
            // B8 escalation: a DAMAGED manual-check result IS confirmed damage
            // — set the degraded flag (off the main thread; transition-logged
            // once) before the report dialog offers the repair flow.
            for (r in results) {
                if (r.status == DatabaseHealthChecker.Status.DAMAGED) {
                    DatabaseHealthState.markDegraded(this, r.type, r.detail ?: "integrity check failed")
                }
            }
            runOnUiThread {
                btnCheckIntegrity?.isEnabled = true
                textCheckProgress?.visibility = View.GONE
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
                showCheckReportDialog(results)
            }
        }
    }

    /**
     * The B8 per-database report dialog (owner-approved wording): `Database
     * Check Complete` when every database produced a real result, `Database
     * Check Incomplete` when one could not be checked. A damaged database
     * routes straight into the A1 repair flow (result buttons are the build
     * plan's suggested labels, pending owner confirmation).
     */
    private fun showCheckReportDialog(results: List<DatabaseHealthChecker.Result>) {
        if (isFinishing) return
        fun reportName(type: BackupType): String = when (type) {
            BackupType.MEMORY -> getString(R.string.health_db_memory)
            BackupType.LOREBOOK -> getString(R.string.health_db_lorebook)
            else -> getString(R.string.health_db_user_image)
        }
        val incomplete = results.any { it.status == DatabaseHealthChecker.Status.UNAVAILABLE }
        val damaged = results.filter { it.status == DatabaseHealthChecker.Status.DAMAGED }
        val lines = results.joinToString("\n") { r ->
            when (r.status) {
                DatabaseHealthChecker.Status.OK -> getString(R.string.health_check_line_ok, reportName(r.type))
                DatabaseHealthChecker.Status.DAMAGED -> getString(R.string.health_check_line_damaged, reportName(r.type))
                DatabaseHealthChecker.Status.UNAVAILABLE -> getString(R.string.health_check_line_unchecked, reportName(r.type))
            }
        }
        val footer = when {
            damaged.isNotEmpty() ->
                "\n\n" + getString(R.string.health_check_footer_damaged,
                    DatabaseHealthState.displayNoun(damaged.first().type))
            incomplete -> "\n\n" + getString(R.string.health_check_footer_incomplete)
            else -> ""
        }
        val b = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(if (incomplete) R.string.health_check_incomplete_title else R.string.health_check_complete_title)
            .setMessage(lines + footer)
        when {
            damaged.isNotEmpty() -> {
                // Route the FIRST damaged database into the repair flow; a
                // re-run after fixing it reports (and routes) the next one.
                val target = damaged.first().type
                b.setPositiveButton(R.string.health_btn_repair) { _, _ ->
                    DatabaseRecoveryFlows.runRepair(this, target) { refreshBackupStatus() }
                }
                b.setNeutralButton(R.string.health_btn_revert) { _, _ ->
                    DatabaseRecoveryFlows.runRevert(this, target) { refreshBackupStatus() }
                }
                b.setNegativeButton(R.string.btn_cancel) { _, _ -> }
            }
            incomplete -> {
                b.setPositiveButton(R.string.health_btn_try_again) { _, _ -> onCheckIntegrity() }
                b.setNeutralButton(R.string.health_btn_view_log) { _, _ ->
                    val intent = Intent(this, LogsActivity::class.java)
                    intent.putExtra("type", "crash")
                    startActivity(intent)
                }
                b.setNegativeButton(R.string.btn_cancel) { _, _ -> }
            }
            else -> b.setPositiveButton(R.string.btn_ok) { _, _ -> }
        }
        b.show()
    }

    /**
     * Build Phase 3 item 8 — the 3-consecutive-failures response, split by
     * category: destination/verify failures get the storage dialog (`Change
     * Backup Folder | Retry | Cancel`, folder shown as text, `Open Backup
     * Folder` as a secondary action); a SOURCE failure runs the store's own
     * check and routes into the repair flow ONLY if damage is confirmed.
     * At most one dialog per screen visit.
     */
    private fun sweepRepeatedBackupFailures() {
        if (backupFailureDialogShown || isFinishing) return
        for (type in BackupType.displayOrder) {
            val response = BackupFailurePolicy.respond(
                RecoveryBackupState.getConsecutiveFailures(this, type),
                RecoveryBackupState.getLastFailureCategory(this, type)
            )
            when (response) {
                BackupFailurePolicy.Response.STORAGE_DIALOG -> {
                    backupFailureDialogShown = true
                    showBackupFailureStorageDialog(type)
                    return
                }
                BackupFailurePolicy.Response.SOURCE_CHECK -> {
                    backupFailureDialogShown = true
                    runOffThread {
                        // Chat source failures route to the Round-4 chat
                        // machinery, not the database flow (Build Phase 2
                        // item 7); confirmDamage handles the three databases.
                        if (type != BackupType.CHATS && DatabaseRepairManager.confirmDamage(this, type)) {
                            runOnUiThread {
                                if (!isFinishing) {
                                    DatabaseRecoveryFlows.showProblemDialog(this, type) { refreshBackupStatus() }
                                }
                            }
                        }
                    }
                    return
                }
                BackupFailurePolicy.Response.NONE -> { /* status row only */ }
            }
        }
    }

    private fun showBackupFailureStorageDialog(type: BackupType) {
        if (isFinishing) return
        val streak = RecoveryBackupState.getConsecutiveFailures(this, type)
        val folderLabel = RecoveryBackupState.getAutoFolderLabel(this)
            ?: getString(R.string.backup_location_selected_generic)
        // The folder is shown as text in the body (approved structure). A
        // stock Material dialog has exactly three button slots, all spoken
        // for (Change Backup Folder | Retry | Cancel), so the `Open Backup
        // Folder` secondary convenience lives on the screen's Automatic
        // Backups section rather than in this dialog — never as a fourth
        // primary button (owner ruling).
        val body = getString(R.string.health_backup_failed_body, streak, typeLabel(type)) +
            "\n\n" + getString(R.string.backup_current_location, folderLabel)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.health_backup_failed_title)
            .setMessage(body)
            .setPositiveButton(R.string.health_btn_change_folder) { _, _ ->
                autoFolderPicker.launch(null)
            }
            .setNeutralButton(R.string.health_btn_retry) { _, _ -> retryAutomaticBackup() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /** Retry from the 3-strikes dialog: run one recovery-backup pass into the
     *  automatic folder now (through the controller, so rotation stays OFF —
     *  automatic backups never delete an older copy), then refresh the status
     *  rows. [AutoBackupController.runNow] ignores the due window but honours
     *  every other gate (destination present + still writable). */
    private fun retryAutomaticBackup() {
        if (RecoveryBackupState.getAutoFolderUri(this) == null) {
            pendingEnableAfterPick = false
            autoFolderPicker.launch(null)
            return
        }
        manualAutoBackupRunning = true
        refreshAutoStatus()
        runOffThread {
            // A local try/finally, not just runOffThread's own catch: the
            // spinner must never get stuck showing "Creating…" forever, even
            // on an exception runOffThread's handler would otherwise catch
            // AFTER this block (AutoBackupController.runNow is documented to
            // never throw, but this guarantees it regardless).
            try {
                AutoBackupController.runNow(this)
            } finally {
                runOnUiThread {
                    manualAutoBackupRunning = false
                    refreshBackupStatus()
                    refreshAutoStatus()
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
        failedNoLastGood = getString(R.string.backup_state_failed_none),
        nothingToBackUp = getString(R.string.backup_state_nothing)
    )

    private fun refreshBackupStatus() {
        runOffThread {
            val templates = statusTemplates()
            val lines = LinkedHashMap<BackupType, String>()
            for (type in BackupType.displayOrder) {
                val lastSuccess = RecoveryBackupState.getLastSuccess(this, type)
                val failed = RecoveryBackupState.getConsecutiveFailures(this, type) > 0
                val nothing = RecoveryBackupState.isNothingToBackUp(this, type)
                lines[type] = BackupStatusFormatter.statusLine(
                    typeLabel(type), inProgress = false, nothingToBackUp = nothing,
                    lastSuccessMillis = lastSuccess, lastFailed = failed, templates = templates
                )
            }
            // Inline recovery actions are gated STRICTLY on confirmed
            // corruption (isDegraded), never on a backup-failure status — a
            // space/permission failure must never surface repair language.
            val memoryDegraded = DatabaseHealthState.isDegraded(this, BackupType.MEMORY)
            val lorebookDegraded = DatabaseHealthState.isDegraded(this, BackupType.LOREBOOK)
            val userImageDegraded = DatabaseHealthState.isDegraded(this, BackupType.USER_IMAGE)
            runOnUiThread {
                textStatusMemory?.text = lines[BackupType.MEMORY]
                textStatusLorebooks?.text = lines[BackupType.LOREBOOK]
                textStatusChats?.text = lines[BackupType.CHATS]
                textStatusUserImage?.text = lines[BackupType.USER_IMAGE]
                healthActionsMemory?.visibility = if (memoryDegraded) View.VISIBLE else View.GONE
                healthActionsLorebook?.visibility = if (lorebookDegraded) View.VISIBLE else View.GONE
                healthActionsUserImage?.visibility = if (userImageDegraded) View.VISIBLE else View.GONE
            }
        }
    }

    /* ------------------------------ 6. automatic backups (frequency) ------------------------------ */

    private fun initAutoFrequencySection() {
        // Remember the last-selected frequency across visits, the same way
        // every other Widget.App.Dropdown.* field on this screen does.
        autoFrequency = RecoveryBackupState.getAutoFrequency(this)
        updateAutoFrequencyLabel()
        btnAutoFrequency?.setOnClickListener { pickAutoFrequency() }
    }

    private fun autoFrequencyLabelRes(frequency: BackupFrequency): Int = when (frequency) {
        BackupFrequency.DAILY -> R.string.backup_auto_frequency_daily
        BackupFrequency.WEEKLY -> R.string.backup_auto_frequency_weekly
        BackupFrequency.BIWEEKLY -> R.string.backup_auto_frequency_biweekly
        BackupFrequency.MONTHLY -> R.string.backup_auto_frequency_monthly
    }

    private fun updateAutoFrequencyLabel() {
        btnAutoFrequency?.text = getString(autoFrequencyLabelRes(autoFrequency))
    }

    private fun pickAutoFrequency() {
        val anchor = btnAutoFrequency ?: return
        val options = BackupFrequency.displayOrder
        val labels = options.map { getString(autoFrequencyLabelRes(it)) }
        showFieldDropdown(anchor, labels) { position ->
            autoFrequency = options[position]
            RecoveryBackupState.setAutoFrequency(this, autoFrequency)
            updateAutoFrequencyLabel()
            // Re-period the unique job so a frequency change takes effect
            // (no-op when automatic backups are off or no folder is set).
            AutoBackupScheduling.sync(this)
            refreshAutoStatus()
        }
    }

    /** The value shown for a file-size label when the size could not be
     *  reliably determined — never an estimate, never a stale number. */
    private fun fileSizeValue(bytes: Long?): String =
        if (bytes != null) ByteSizeFormatter.format(bytes) else getString(R.string.backup_size_unavailable)

    /** The owner-approved message for a display failure reason. */
    private fun autoFailureMessage(reason: AutoBackupFailureReason?): String = getString(
        when (reason) {
            AutoBackupFailureReason.DESTINATION_PERMISSION -> R.string.backup_auto_status_paused
            AutoBackupFailureReason.DESTINATION_WRITE -> R.string.backup_auto_fail_write
            AutoBackupFailureReason.DESTINATION_FULL -> R.string.backup_auto_fail_storage
            AutoBackupFailureReason.SOURCE -> R.string.backup_auto_fail_source
            AutoBackupFailureReason.VERIFY -> R.string.backup_auto_fail_verify
            AutoBackupFailureReason.UNEXPECTED, null -> R.string.backup_auto_fail_unexpected
        }
    )

    /** The "Last (Successful) Backup: <date>" + "File Size: <size>" pair, with
     *  the label chosen by context (clean success vs after-a-failure). */
    private fun lastSuccessLines(labelRes: Int): List<String> {
        val date = BackupStatusFormatter.formatDateTime(RecoveryBackupState.getAutoLastSuccess(this))
        val size = fileSizeValue(RecoveryBackupState.getAutoLastSuccessSizeBytes(this))
        return listOf(
            getString(labelRes, date),
            getString(R.string.backup_auto_status_file_size, size)
        )
    }

    /**
     * Show the automatic set's own status, driven by the pure
     * [AutoBackupStatusPresenter] (every state is unit-tested there). While a
     * pass is actively running this shows the real indeterminate Material
     * spinner + "Creating Automatic Backup…" and NOTHING else (no text glyph).
     * Otherwise it renders, per the presenter's decision: the last successful
     * backup + size when disabled; the never-run line; the paused message
     * (destination lost, no retry) with the previous good backup kept visible;
     * a category-specific failure message + previous good backup + "Retrying
     * Automatically"; or, on a clean pass, last backup + size + next due.
     * Uses the recorded state — never a raw URI, never a raw exception.
     */
    private fun refreshAutoStatus() {
        val tv = textAutoStatus ?: return
        val spinner = spinnerAutoStatus

        val uriStr = RecoveryBackupState.getAutoFolderUri(this)
        val hasDestination = uriStr != null
        val view = AutoBackupStatusPresenter.present(
            enabled = RecoveryBackupState.isEnabled(this),
            hasDestination = hasDestination,
            destinationAccessible = hasDestination && AutoBackupController.folderAccessible(this, uriStr!!),
            running = manualAutoBackupRunning || AutoBackupController.isRunning(),
            lastSuccessMillis = RecoveryBackupState.getAutoLastSuccess(this),
            lastAttemptMillis = RecoveryBackupState.getAutoLastAttempt(this),
            failureReason = RecoveryBackupState.getAutoLastFailureReason(this)
        )

        if (view.kind == AutoBackupStatusPresenter.Kind.CREATING) {
            spinner?.visibility = View.VISIBLE
            tv.text = getString(R.string.backup_auto_status_creating)
            tv.visibility = View.VISIBLE
            return
        }
        spinner?.visibility = View.GONE

        val lines = ArrayList<String>()
        when (view.kind) {
            AutoBackupStatusPresenter.Kind.HIDDEN, AutoBackupStatusPresenter.Kind.CREATING -> { /* nothing */ }
            AutoBackupStatusPresenter.Kind.NEVER ->
                lines += getString(R.string.backup_auto_status_never)
            AutoBackupStatusPresenter.Kind.DISABLED ->
                if (view.showLastSuccess) lines += lastSuccessLines(R.string.backup_auto_status_last)
            AutoBackupStatusPresenter.Kind.SUCCESS -> {
                lines += lastSuccessLines(R.string.backup_auto_status_last)
                val nextDue = AutoBackupScheduler.nextDueMillis(
                    RecoveryBackupState.getAutoLastSuccess(this), autoFrequency
                )
                lines += getString(R.string.backup_auto_status_next_due, BackupStatusFormatter.formatDateTime(nextDue))
            }
            AutoBackupStatusPresenter.Kind.PAUSED -> {
                lines += autoFailureMessage(view.failureReason)
                if (view.showLastSuccess) lines += lastSuccessLines(R.string.backup_auto_status_last_successful)
            }
            AutoBackupStatusPresenter.Kind.FAILED -> {
                lines += autoFailureMessage(view.failureReason)
                if (view.showLastSuccess) lines += lastSuccessLines(R.string.backup_auto_status_last_successful)
                if (view.showRetrying) lines += getString(R.string.backup_auto_status_retrying)
            }
        }
        val text = lines.joinToString("\n")
        tv.text = text
        tv.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /* ------------------------------ backup locations ------------------------------ */

    /**
     * A folder was picked for Automatic Backups: persist the URI for ACCESS
     * and, separately, a resolved human-readable label for DISPLAY (owner
     * correction, July 22 2026). The label query runs off the main thread;
     * until it lands the line shows the generic phrase — never the URI.
     */
    private fun onFolderChosen(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* best-effort; a lost grant surfaces later as "Folder unavailable" */ }
        // Adopt the new URI immediately and drop the OLD breadcrumb right
        // away (never leave the previous folder's label on screen while the
        // new one resolves) — see BackupLocationDisplay.applyFolderPick.
        val picked = BackupLocationDisplay.applyFolderPick(uri.toString())
        RecoveryBackupState.setAutoFolderUri(this, picked.uri)
        RecoveryBackupState.setAutoFolderLabel(this, picked.label)
        // Repairing/replacing the destination clears a stale "folder lost"
        // failure reason so the status doesn't keep showing the old paused
        // message over the freshly-granted folder until the next run.
        RecoveryBackupState.clearAutoFailureReason(this)

        // If this pick was the destination the pending toggle-enable needed,
        // enabling now completes: the required valid destination exists.
        if (pendingEnableAfterPick) {
            pendingEnableAfterPick = false
            RecoveryBackupState.setEnabled(this, true)
            setAutoSwitchChecked(true)
        }
        // Reconcile the WorkManager job with the new state (enqueues when
        // enabled + a folder is set, cancels otherwise). Idempotent.
        AutoBackupScheduling.sync(this)
        refreshLocations()
        refreshAutoStatus()
        runOffThread {
            val label = BackupLocationDisplay.treeFolderLabel(this, uri)
            if (label != null) RecoveryBackupState.setAutoFolderLabel(this, label)
            runOnUiThread { refreshLocations() }
        }
    }

    /** The folder picker was cancelled. If it was opened to satisfy a
     *  pending toggle-enable, revert the switch to off — enabling requires a
     *  valid destination, and none was chosen — and make sure nothing was
     *  left scheduled (defensive: `enabled` was never actually set true on
     *  this path, so this is normally a no-op cancel, but automatic backups
     *  must provably stay unscheduled after a cancelled first-time enable). */
    private fun onFolderPickCancelled() {
        if (pendingEnableAfterPick) {
            pendingEnableAfterPick = false
            RecoveryBackupState.setEnabled(this, false)
            setAutoSwitchChecked(false)
            AutoBackupScheduling.sync(this)
            refreshAutoStatus()
        }
    }

    /** Set the auto-backup switch's checked state without firing the listener. */
    private fun setAutoSwitchChecked(checked: Boolean) {
        suppressAutoToggle = true
        switchAutoBackup?.isChecked = checked
        suppressAutoToggle = false
    }

    /**
     * The toggle changed. Turning ON requires a valid destination: if none is
     * chosen yet, open the picker and defer the enable until a folder lands
     * (owner ruling). Turning OFF disables and cancels the scheduled job.
     */
    private fun onAutoToggle(isChecked: Boolean) {
        if (isChecked) {
            if (RecoveryBackupState.getAutoFolderUri(this) == null) {
                pendingEnableAfterPick = true
                autoFolderPicker.launch(null)
                return
            }
            RecoveryBackupState.setEnabled(this, true)
        } else {
            RecoveryBackupState.setEnabled(this, false)
        }
        AutoBackupScheduling.sync(this)
        refreshAutoStatus()
    }

    /** True while the persisted grant for [uriStr] is still held — when it is
     *  gone the folder moved/was deleted/was revoked and the line must say so
     *  instead of showing a stale name. */
    private fun folderAccessible(uriStr: String): Boolean = try {
        val uri = Uri.parse(uriStr)
        contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Location lines show, in order of preference: the persisted breadcrumb
     * label ("Provider > Parent > Selected Folder", from
     * [BackupLocationDisplay]), the generic "Selected folder location"
     * phrase, or — when access was lost — "Folder unavailable. Choose a new
     * location." NEVER a raw URI, tree document id, or provider authority
     * (owner correction, July 22 2026).
     */
    private fun refreshLocations() {
        val manual = RecoveryBackupState.getManualFolderUri(this)
        val auto = RecoveryBackupState.getAutoFolderUri(this)
        textManualLocation?.text = locationText(
            manual, RecoveryBackupState.getManualFolderLabel(this), R.string.backup_current_location
        )
        textAutoLocation?.text = locationText(
            auto, RecoveryBackupState.getAutoFolderLabel(this), R.string.backup_auto_location
        )
    }

    /** One shared rendering of a destination line, driven by the pure
     *  [BackupLocationDisplay.classifyDestination] ladder: no folder chosen,
     *  access lost ("Folder unavailable. Choose a new location."), or the
     *  resolved breadcrumb — falling back to the generic phrase only when
     *  nothing could ever be resolved for an otherwise-accessible folder. */
    private fun locationText(uriStr: String?, label: String?, templateRes: Int): String =
        when (BackupLocationDisplay.classifyDestination(uriStr != null, uriStr != null && folderAccessible(uriStr))) {
            BackupLocationDisplay.DestinationDisplayKind.NONE -> getString(R.string.backup_location_none)
            BackupLocationDisplay.DestinationDisplayKind.UNAVAILABLE -> getString(R.string.backup_location_unavailable)
            BackupLocationDisplay.DestinationDisplayKind.LABELED -> getString(
                templateRes, label ?: getString(R.string.backup_location_selected_generic)
            )
        }

    /* ------------------------------ 5. portable data copy ------------------------------ */

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
            // The import report stays on screen until dismissed (never a toast).
            showNoticeDialog(report.summary(), getString(R.string.backup_portable_import_title))
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
            val where = BackupLocationDisplay.describeSaveAs(this, uri)
            val destination = where.breadcrumb ?: where.providerLabel
            runOnUiThread {
                // Persistent inline result under the buttons (never a toast).
                textPortableStatus?.text = if (destination != null) {
                    getString(R.string.backup_portable_saved_to, destination)
                } else {
                    getString(R.string.backup_portable_export_done)
                }
                textPortableStatus?.visibility = View.VISIBLE
                refreshBackupStatus()
            }
        }
    }

    /* ------------------------------ 6. reset (destructive) ------------------------------ */

    private fun showResetDialog() {
        if (!MemoryStore.isProvisioned(this)) {
            showNoticeDialog(getString(R.string.memory_not_provisioned_toast))
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

    /**
     * All store work off the main thread; a failure becomes a persistent
     * dialog with GENERIC wording — never a toast, and never raw exception
     * text on screen (owner corrections, July 22 2026). The class name only
     * (no message — messages can embed paths/URIs) goes to the local Memory
     * log for diagnosis.
     */
    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                try {
                    MemoryLog.log(this, "BackupScreen", "error",
                        "Backup screen operation failed (${e.javaClass.simpleName}).")
                } catch (_: Exception) { /* logging is best-effort */ }
                runOnUiThread {
                    showNoticeDialog(
                        getString(R.string.backup_operation_failed_body),
                        getString(R.string.backup_operation_failed_title)
                    )
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
