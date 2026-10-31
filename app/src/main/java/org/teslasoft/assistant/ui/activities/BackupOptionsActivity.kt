package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.AutoBackupController
import org.teslasoft.assistant.preferences.backup.AutoBackupFailureReason
import org.teslasoft.assistant.preferences.backup.AutoBackupScheduler
import org.teslasoft.assistant.preferences.backup.AutoBackupScheduling
import org.teslasoft.assistant.preferences.backup.AutoBackupStatusPresenter
import org.teslasoft.assistant.preferences.backup.BackupFrequency
import org.teslasoft.assistant.preferences.backup.BackupLocationDisplay
import org.teslasoft.assistant.preferences.backup.BackupStatusFormatter
import org.teslasoft.assistant.preferences.backup.ByteSizeFormatter
import org.teslasoft.assistant.preferences.backup.RecoveryBackupState
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.ui.widgets.AppDropdown

/** Automatic backup preferences and the existing manual recovery entry. */
class BackupOptionsActivity : MemoryBackupRestoreActivity() {
    override val isBackupOptionsPage: Boolean = true
    private var switchAutoBackup: MaterialSwitch? = null
    private var btnAutoFrequency: TextView? = null
    private var autoFrequency = BackupFrequency.DAILY
    private var textAutoLocation: TextView? = null
    private var textAutoStatus: TextView? = null
    private var spinnerAutoStatus: CircularProgressIndicator? = null
    private var btnChangeAutoLocation: MaterialButton? = null

    // Guards the auto-backup switch's listener from firing while we set its
    // checked state programmatically (revert-on-cancel, initial load).
    private var suppressAutoToggle = false

    // The user flipped the toggle ON with no destination yet: the folder picker
    // is open, and enabling completes only once a folder is actually chosen. If
    // they cancel the picker, the toggle reverts to off.
    private var pendingEnableAfterPick = false
    private var textRecoveryTypeProtectedSummary: TextView? = null
    private var textRecoveryTypeUnencryptedSummary: TextView? = null
    private var btnRecoveryType: TextView? = null
    private var recoveryTypeProtected = true

    private val autoFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderChosen(uri) else onFolderPickCancelled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val optionsBack = findViewById<View>(R.id.btn_back)
        ScreenChrome.apply(this, findViewById(R.id.action_bar), optionsBack)
        optionsBack?.setOnClickListener { finish() }
        fitHeaderToTopEdge()
        pendingEnableAfterPick = savedInstanceState?.getBoolean("pendingEnableAfterPick") ?: false
        switchAutoBackup = findViewById(R.id.switch_auto_backup)
        btnAutoFrequency = findViewById(R.id.btn_auto_frequency)
        textAutoLocation = findViewById(R.id.text_auto_location)
        textAutoStatus = findViewById(R.id.text_auto_status)
        spinnerAutoStatus = findViewById(R.id.spinner_auto_status)
        btnChangeAutoLocation = findViewById(R.id.btn_change_auto_location)
        textRecoveryTypeProtectedSummary = findViewById(R.id.text_recovery_type_protected_summary)
        textRecoveryTypeUnencryptedSummary = findViewById(R.id.text_recovery_type_unencrypted_summary)
        btnRecoveryType = findViewById(R.id.btn_recovery_type)
        /* ---- Backup: Automatic Backups. Enabled flag + frequency + destination
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

        initRecoveryTypeSection()
        findViewById<MaterialButton>(R.id.btn_create_recovery).setOnClickListener {
            startActivity(Intent(this, RecoveryBackupActivity::class.java)
                .putExtra(RecoveryBackupActivity.EXTRA_RECOVERY_PROTECTED, recoveryTypeProtected))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocations()
        refreshAutoStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingEnableAfterPick", pendingEnableAfterPick)
        super.onSaveInstanceState(outState)
    }

    private fun showFieldDropdown(anchor: View, labels: List<String>, onPick: (Int) -> Unit) {
        if (isFinishing) return
        val dropdown = anchor as? TextView ?: return
        AppDropdown.show(dropdown, labels, onPick = onPick)
    }

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
            running = AutoBackupController.isRunning(),
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
        lifecycleScope.launch {
            val label = withContext(Dispatchers.IO) { BackupLocationDisplay.treeFolderLabel(this@BackupOptionsActivity, uri) }
            if (label != null) RecoveryBackupState.setAutoFolderLabel(this@BackupOptionsActivity, label)
            refreshLocations()
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
        val auto = RecoveryBackupState.getAutoFolderUri(this)
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

    /**
     * Under edge-to-edge layout (Android 15 and later) the header's own
     * background runs up behind the status bar, as on Backup & Restore, with
     * its contents padded below it; the scroll area stops above the
     * navigation bar and the screen keeps clear of side insets.
     */
    private fun fitHeaderToTopEdge() {
        if (Build.VERSION.SDK_INT < 35) return
        val root = findViewById<View>(R.id.root) ?: return
        val header = findViewById<View>(R.id.action_bar) ?: return
        val scroll = findViewById<View>(R.id.scroll) ?: return
        val headerTop = header.paddingTop
        val scrollBottom = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, right = bars.right)
            header.updatePadding(top = headerTop + bars.top)
            scroll.updatePadding(bottom = scrollBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
