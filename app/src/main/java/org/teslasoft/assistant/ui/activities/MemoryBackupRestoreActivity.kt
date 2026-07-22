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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import android.provider.DocumentsContract
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.backup.AutoBackupFrequency
import org.teslasoft.assistant.preferences.backup.BackupBrand
import org.teslasoft.assistant.preferences.backup.BackupLocationDisplay
import org.teslasoft.assistant.preferences.backup.BackupStatusFormatter
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.DatabaseHealthChecker
import org.teslasoft.assistant.preferences.backup.RecoveryBackupState
import org.teslasoft.assistant.preferences.backup.RecoveryFileNaming
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
 * order is owner-directed and EXACT (July 22 2026): 1. Database Health,
 * 2. Backup Status, 3. Recovery Backup, 4. Human-Readable Chat Backup,
 * 5. Portable Data Copy, 6. Automatic Backups, 7. Reset. Do not reorder. The
 * two backup LOCATIONS (manual vs automatic) are kept separate.
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

    // 3. Recovery Backup (manual)
    private var btnCreateRecovery: MaterialButton? = null
    private var textManualLocation: TextView? = null
    private var btnChangeManualLocation: MaterialButton? = null
    private var btnCreateBackup: MaterialButton? = null

    // 4. Human-Readable Chat Backup
    private var btnReadableScope: MaterialButton? = null
    private var btnReadableFormat: MaterialButton? = null
    private var btnReadableCreate: MaterialButton? = null
    private var textReadableStatus: TextView? = null

    // 5. Portable Data Copy
    private var btnPortableExport: MaterialButton? = null
    private var btnPortableImport: MaterialButton? = null
    private var textPortableStatus: TextView? = null

    // 6. Automatic Backups (the portable automatic system, July 22 2026).
    // The switch is real and GATED: it cannot turn on without a chosen,
    // accessible folder and a CONFIRMED Recovery Code (automatic packages
    // are always protected). Refusals are explained inline, never a toast.
    private var textAutoLocation: TextView? = null
    private var btnChangeAutoLocation: MaterialButton? = null
    private var btnAutoFrequency: MaterialButton? = null
    private var switchAutoBackup: MaterialSwitch? = null
    private var textAutoPrereq: TextView? = null
    private var textAutoResult: TextView? = null

    /** Guards the switch listener while code (not the user) sets its state. */
    private var suppressAutoSwitchCallback = false

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
        if (uri != null) onFolderChosen(uri)
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
        updateAutoUi()
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

        btnCreateRecovery = findViewById(R.id.btn_create_recovery)
        textManualLocation = findViewById(R.id.text_manual_location)
        btnChangeManualLocation = findViewById(R.id.btn_change_manual_location)
        btnCreateBackup = findViewById(R.id.btn_create_backup)

        btnReadableScope = findViewById(R.id.btn_readable_scope)
        btnReadableFormat = findViewById(R.id.btn_readable_format)
        btnReadableCreate = findViewById(R.id.btn_readable_create)
        textReadableStatus = findViewById(R.id.text_readable_status)

        btnPortableExport = findViewById(R.id.btn_portable_export)
        btnPortableImport = findViewById(R.id.btn_portable_import)
        textPortableStatus = findViewById(R.id.text_portable_status)

        textAutoLocation = findViewById(R.id.text_auto_location)
        btnChangeAutoLocation = findViewById(R.id.btn_change_auto_location)
        btnAutoFrequency = findViewById(R.id.btn_auto_frequency)
        switchAutoBackup = findViewById(R.id.switch_auto_backup)
        textAutoPrereq = findViewById(R.id.text_auto_prereq)
        textAutoResult = findViewById(R.id.text_auto_result)

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

        /* ---- 3. Recovery Backup (manual) ---- */
        // The installation-bound v1 controls (btn_change_manual_location,
        // btn_create_backup) are hidden AND unwired: the old writer must not be
        // reachable from this screen (owner correction, July 22 2026). The v1
        // backend classes stay for the future automatic implementation.
        btnCreateRecovery?.setOnClickListener {
            startActivity(Intent(this, RecoveryBackupActivity::class.java))
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

        /* ---- 6. Automatic Backups ---- */
        btnChangeAutoLocation?.setOnClickListener { autoFolderPicker.launch(null) }
        initAutoSection()

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

    /* ------------------------------ 6. automatic backups ------------------------------ */

    private fun initAutoSection() {
        setAutoSwitchChecked(RecoveryBackupState.isEnabled(this))
        switchAutoBackup?.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoSwitchCallback) return@setOnCheckedChangeListener
            onAutoSwitchToggled(checked)
        }
        btnAutoFrequency?.setOnClickListener { pickAutoFrequency() }
        updateAutoUi()
    }

    private fun setAutoSwitchChecked(checked: Boolean) {
        suppressAutoSwitchCallback = true
        switchAutoBackup?.isChecked = checked
        suppressAutoSwitchCallback = false
    }

    /**
     * Turning the switch ON is GATED (owner directive): a chosen, accessible
     * folder and a CONFIRMED Recovery Code are required — automatic packages
     * are always protected and there is no user present at run time to
     * choose. The Keystore read runs off the main thread; until it answers
     * the switch stays visually on, and a refusal snaps it back with a
     * persistent inline explanation (never a toast).
     */
    private fun onAutoSwitchToggled(checked: Boolean) {
        if (!checked) {
            RecoveryBackupState.setEnabled(this, false)
            textAutoPrereq?.visibility = View.GONE
            updateAutoUi()
            return
        }
        val folderUri = RecoveryBackupState.getAutoFolderUri(this)
        if (folderUri == null || !folderAccessible(folderUri)) {
            setAutoSwitchChecked(false)
            textAutoPrereq?.setText(R.string.backup_auto_need_folder)
            textAutoPrereq?.visibility = View.VISIBLE
            return
        }
        switchAutoBackup?.isEnabled = false
        runOffThread {
            val confirmed = try {
                org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyStore
                    .getSetupState(this) ==
                    org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyStore.SetupState.CONFIRMED
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                switchAutoBackup?.isEnabled = true
                if (confirmed) {
                    RecoveryBackupState.setEnabled(this, true)
                    textAutoPrereq?.visibility = View.GONE
                } else {
                    setAutoSwitchChecked(false)
                    textAutoPrereq?.setText(R.string.backup_auto_need_setup)
                    textAutoPrereq?.visibility = View.VISIBLE
                }
                updateAutoUi()
            }
        }
    }

    private fun frequencyLabel(frequency: AutoBackupFrequency): String = getString(
        when (frequency) {
            AutoBackupFrequency.DAILY -> R.string.backup_auto_freq_daily
            AutoBackupFrequency.WEEKLY -> R.string.backup_auto_freq_weekly
            AutoBackupFrequency.BIWEEKLY -> R.string.backup_auto_freq_biweekly
            AutoBackupFrequency.MONTHLY -> R.string.backup_auto_freq_monthly
        }
    )

    private fun pickAutoFrequency() {
        if (isFinishing) return
        val values = arrayOf(
            AutoBackupFrequency.DAILY, AutoBackupFrequency.WEEKLY,
            AutoBackupFrequency.BIWEEKLY, AutoBackupFrequency.MONTHLY
        )
        val labels = values.map { frequencyLabel(it) }.toTypedArray()
        val current = values.indexOf(RecoveryBackupState.getAutoFrequency(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.backup_auto_frequency_label)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                RecoveryBackupState.setAutoFrequency(this, values[which])
                updateAutoUi()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    /** The frequency button label and the persistent last-result line — read
     *  from the recorded state, never invented. */
    private fun updateAutoUi() {
        btnAutoFrequency?.text = "${getString(R.string.backup_auto_frequency_label)}: " +
            frequencyLabel(RecoveryBackupState.getAutoFrequency(this))
        val lastSuccess = RecoveryBackupState.getAutoLastSuccess(this)
        val failedCategory = RecoveryBackupState.getAutoLastFailureCategory(this)
        textAutoResult?.text = when {
            failedCategory != null && lastSuccess > 0L -> getString(
                R.string.backup_auto_last_failed, BackupStatusFormatter.formatDateTime(lastSuccess)
            )
            failedCategory != null -> getString(R.string.backup_auto_last_failed_none)
            lastSuccess > 0L -> getString(
                R.string.backup_auto_last_success, BackupStatusFormatter.formatDateTime(lastSuccess)
            )
            else -> getString(R.string.backup_auto_last_never)
        }
    }

    /* ------------------------------ 4. human-readable chat backup ------------------------------ */

    private fun initReadableSection() {
        updateReadableSelectorLabels()
        btnReadableScope?.setOnClickListener { pickReadableScope() }
        btnReadableFormat?.setOnClickListener { pickReadableFormat() }
        btnReadableCreate?.setOnClickListener { onCreateReadableBackup() }
        showReadableLastSuccess()
    }

    private fun updateReadableSelectorLabels() {
        val scope = getString(
            if (readableScopeAll) R.string.backup_readable_scope_all
            else R.string.backup_readable_scope_incremental
        )
        btnReadableScope?.text = "${getString(R.string.backup_readable_scope_label)}: $scope"
        val format = getString(
            when (readableFormat) {
                ReadableChatBackup.Format.TEXT -> R.string.backup_readable_format_text
                ReadableChatBackup.Format.JSON -> R.string.backup_readable_format_json
                ReadableChatBackup.Format.BOTH -> R.string.backup_readable_format_both
            }
        )
        btnReadableFormat?.text = "${getString(R.string.backup_readable_format_label)}: $format"
    }

    private fun pickReadableScope() {
        if (isFinishing) return
        val options = arrayOf(
            getString(R.string.backup_readable_scope_all),
            getString(R.string.backup_readable_scope_incremental)
        )
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.backup_readable_scope_label)
            .setSingleChoiceItems(options, if (readableScopeAll) 0 else 1) { dialog, which ->
                readableScopeAll = which == 0
                updateReadableSelectorLabels()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun pickReadableFormat() {
        if (isFinishing) return
        val options = arrayOf(
            getString(R.string.backup_readable_format_text),
            getString(R.string.backup_readable_format_json),
            getString(R.string.backup_readable_format_both)
        )
        val checked = when (readableFormat) {
            ReadableChatBackup.Format.TEXT -> 0
            ReadableChatBackup.Format.JSON -> 1
            ReadableChatBackup.Format.BOTH -> 2
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.backup_readable_format_label)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                readableFormat = when (which) {
                    1 -> ReadableChatBackup.Format.JSON
                    2 -> ReadableChatBackup.Format.BOTH
                    else -> ReadableChatBackup.Format.TEXT
                }
                updateReadableSelectorLabels()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    private fun setReadableStatus(text: String) {
        textReadableStatus?.text = text
        textReadableStatus?.visibility = View.VISIBLE
    }

    private fun showReadableLastSuccess() {
        val last = ReadableBackupState.getLastSuccess(this)
        if (last > 0L) {
            setReadableStatus(
                getString(R.string.backup_readable_last_success, BackupStatusFormatter.formatDateTime(last))
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

                val actualSha = contentResolver.openInputStream(uri)?.use { sha256(it) }
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
                // stamp may move.
                if (incremental) ReadableBackupState.setBaseline(this, fingerprints)
                ReadableBackupState.setLastSuccess(this, System.currentTimeMillis())

                val where = BackupLocationDisplay.describeSaveAs(this, uri)
                runOnUiThread {
                    btnReadableCreate?.isEnabled = true
                    val main = if (where.providerLabel != null) {
                        getString(R.string.backup_readable_saved_to, where.providerLabel)
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

    private fun sha256(stream: InputStream): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest()
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
            runOnUiThread {
                textStatusMemory?.text = lines[BackupType.MEMORY]
                textStatusLorebooks?.text = lines[BackupType.LOREBOOK]
                textStatusChats?.text = lines[BackupType.CHATS]
                textStatusUserImage?.text = lines[BackupType.USER_IMAGE]
            }
        }
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
        RecoveryBackupState.setAutoFolderUri(this, uri.toString())
        RecoveryBackupState.setAutoFolderLabel(this, null)
        refreshLocations()
        runOffThread {
            val label = BackupLocationDisplay.treeFolderLabel(this, uri)
            if (label != null) RecoveryBackupState.setAutoFolderLabel(this, label)
            runOnUiThread { refreshLocations() }
        }
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
     * Location lines show, in order of preference: the persisted friendly
     * label, the generic "Selected folder" phrase, or — when access was lost —
     * "Folder unavailable. Choose a new location." NEVER a raw URI, tree
     * document id, or provider authority (owner correction, July 22 2026).
     */
    private fun refreshLocations() {
        val manual = RecoveryBackupState.getManualFolderUri(this)
        val auto = RecoveryBackupState.getAutoFolderUri(this)
        textManualLocation?.text = when {
            manual == null -> getString(R.string.backup_location_none)
            !folderAccessible(manual) -> getString(R.string.backup_location_unavailable)
            else -> getString(
                R.string.backup_current_location,
                RecoveryBackupState.getManualFolderLabel(this)
                    ?: getString(R.string.backup_location_selected_generic)
            )
        }
        textAutoLocation?.text = when {
            auto == null -> getString(R.string.backup_location_none)
            !folderAccessible(auto) -> getString(R.string.backup_location_unavailable)
            else -> getString(
                R.string.backup_auto_location,
                RecoveryBackupState.getAutoFolderLabel(this)
                    ?: getString(R.string.backup_location_selected_generic)
            )
        }
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
            runOnUiThread {
                // Persistent inline result under the buttons (never a toast).
                textPortableStatus?.text = getString(R.string.backup_portable_export_done)
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
