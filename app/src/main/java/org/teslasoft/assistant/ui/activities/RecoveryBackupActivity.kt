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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
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
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.backup.BackupBrand
import org.teslasoft.assistant.preferences.backup.BackupFailureCategory
import org.teslasoft.assistant.preferences.backup.BackupFailureClassifier
import org.teslasoft.assistant.preferences.backup.BackupLocationDisplay
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.RecoveryBackupState
import org.teslasoft.assistant.preferences.backup.RecoveryFileNaming
import org.teslasoft.assistant.preferences.backup.portable.ChatLogicalSerializer
import org.teslasoft.assistant.preferences.backup.portable.PackageCrypto
import org.teslasoft.assistant.preferences.backup.portable.PortablePackageFormat
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoveryWriter
import org.teslasoft.assistant.preferences.backup.portable.RecoveryCode
import org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyFile
import org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyStore
import org.teslasoft.assistant.theme.ThemeManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The manual Recovery Backup flow (portable format v2). One activity, five
 * panels driven by a small state machine — the owner-approved flow (July 22
 * 2026): one entry, a choice of Protected / Unencrypted, one-time Recovery
 * Code setup with a required re-entry confirmation before protected backups can
 * be created, and Save As package creation.
 *
 * SCOPE FENCE (owner): NO automatic scheduling, NO automatic writer, NO
 * rotation, NO deletion, NO live-data restore here. This exists for the
 * controlled on-device test gate.
 *
 * SETUP STATE (owner correction 2, July 22 2026): storing the Recovery Secret
 * is NOT the same as configuring it. The protected path branches on
 * [RecoveryKeyStore.getSetupState] — a freshly generated secret is UNCONFIRMED
 * and cannot produce a protected package until the user re-enters the complete
 * Recovery Code AND the CONFIRMED marker is durably written.
 *
 * BUILD BEFORE SAVE AS (owner correction 6): the package is built and verified
 * into per-run cache staging FIRST; only then is Save As launched. After the
 * copy to the chosen destination, the destination is reopened and its SHA-256
 * compared to the staged file — a mismatch discards the destination and reports
 * a visible failure. Staging is cleaned on every path.
 *
 * SECRET LIFECYCLE (owner correction 3/4): confirmation compares the COMPLETE
 * 16-byte secret in constant time and wipes the decoded copy; workers re-read
 * the stored secret rather than sharing the activity field, and wipe it in a
 * finally; entered password/code fields are cleared after use; the copied
 * Recovery Code is marked sensitive on the clipboard.
 */
class RecoveryBackupActivity : FragmentActivity() {

    companion object {
        /** Intent extra: the Recovery Type chosen ahead of time on the
         *  Memory Backup & Restore screen's dropdown (owner ruling, July 22
         *  2026) - true = Protected, false = Unencrypted. When present, the
         *  choice panel is skipped entirely and the flow goes straight to
         *  that type's next step, the same as tapping the corresponding
         *  choice card used to do. Absent when this activity is launched
         *  without a pre-made choice, which still shows the choice panel. */
        const val EXTRA_RECOVERY_PROTECTED = "recoveryProtected"
    }

    private var preferences: Preferences? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var panelChoice: LinearLayout? = null
    private var panelSetup: LinearLayout? = null
    private var panelPassword: LinearLayout? = null
    private var panelConfirm: LinearLayout? = null
    private var panelResult: LinearLayout? = null

    private var btnChoiceProtected: LinearLayout? = null
    private var btnChoiceUnencrypted: LinearLayout? = null

    private var textRecoveryCode: TextView? = null
    private var btnSaveKeyFile: MaterialButton? = null
    private var btnCopyCode: MaterialButton? = null
    private var btnAddPassword: MaterialButton? = null
    private var btnSetupContinue: MaterialButton? = null

    private var editPassword: EditText? = null
    private var editPasswordConfirm: EditText? = null
    private var textPasswordError: TextView? = null
    private var btnPasswordSave: MaterialButton? = null
    private var btnPasswordCancel: MaterialButton? = null

    private var editConfirmCode: EditText? = null
    private var textConfirmError: TextView? = null
    private var btnConfirm: MaterialButton? = null

    private var textResult: TextView? = null
    private var textResultDetail: TextView? = null
    private var btnResultDone: MaterialButton? = null

    /** The secret being set up (protected flow), held only during the setup +
     *  confirm panels for display and constant-time confirmation. Never used to
     *  build the package — the build worker re-reads the stored secret. */
    private var pendingSecret: ByteArray? = null

    /** True while the pending Save As is a protected package. */
    private var pendingProtected = false

    /** The built, verified package awaiting the user's Save As destination
     *  (owner correction 6: build before Save As). Deleted on cancel, after a
     *  successful copy, and in onDestroy. */
    private var stagedPackage: File? = null

    /** SHA-256 of [stagedPackage], computed right after the build, used to
     *  verify the copy landed in the destination byte-for-byte. */
    private var stagedSha: ByteArray? = null

    /** The backup types the staged package actually contains. Backup Status is
     *  recorded from this ONLY after the destination is also verified (owner
     *  rule: never claim a failed or unfinished attempt succeeded). */
    private var stagedIncludedTypes: Set<BackupType> = emptySet()

    private val saveKeyFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeKeyFile(uri) }

    private val createPackageLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> onSaveAsResult(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_recovery_backup)
        preferences = Preferences.getPreferences(this, "")
        bindViews()
        applyTheme()
        initLogic()
        // A Recovery Type chosen ahead of time (the Memory Backup & Restore
        // dropdown) skips the choice panel entirely - go straight to that
        // type's next step, same as tapping the choice card would have.
        if (intent.hasExtra(EXTRA_RECOVERY_PROTECTED)) {
            if (intent.getBooleanExtra(EXTRA_RECOVERY_PROTECTED, true)) onProtectedChosen() else onUnencryptedChosen()
        } else {
            showChoice()
        }
    }

    override fun onDestroy() {
        pendingSecret?.let { PackageCrypto.wipe(it) }
        pendingSecret = null
        cleanupStaged()
        super.onDestroy()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        panelChoice = findViewById(R.id.panel_choice)
        panelSetup = findViewById(R.id.panel_setup)
        panelPassword = findViewById(R.id.panel_password)
        panelConfirm = findViewById(R.id.panel_confirm)
        panelResult = findViewById(R.id.panel_result)
        btnChoiceProtected = findViewById(R.id.btn_choice_protected)
        btnChoiceUnencrypted = findViewById(R.id.btn_choice_unencrypted)
        textRecoveryCode = findViewById(R.id.text_recovery_code)
        btnSaveKeyFile = findViewById(R.id.btn_save_key_file)
        btnCopyCode = findViewById(R.id.btn_copy_code)
        btnAddPassword = findViewById(R.id.btn_add_password)
        btnSetupContinue = findViewById(R.id.btn_setup_continue)
        editPassword = findViewById(R.id.edit_password)
        editPasswordConfirm = findViewById(R.id.edit_password_confirm)
        textPasswordError = findViewById(R.id.text_password_error)
        btnPasswordSave = findViewById(R.id.btn_password_save)
        btnPasswordCancel = findViewById(R.id.btn_password_cancel)
        editConfirmCode = findViewById(R.id.edit_confirm_code)
        textConfirmError = findViewById(R.id.text_confirm_error)
        btnConfirm = findViewById(R.id.btn_confirm)
        textResult = findViewById(R.id.text_result)
        textResultDetail = findViewById(R.id.text_result_detail)
        btnResultDone = findViewById(R.id.btn_result_done)
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        btnChoiceProtected?.setOnClickListener { onProtectedChosen() }
        btnChoiceUnencrypted?.setOnClickListener { onUnencryptedChosen() }

        btnSaveKeyFile?.setOnClickListener {
            saveKeyFileLauncher.launch("${BackupBrand.resolve(this)}-Recovery-Key.json")
        }
        btnCopyCode?.setOnClickListener { copyCode() }
        btnAddPassword?.setOnClickListener { showPassword() }
        btnSetupContinue?.setOnClickListener { showConfirm() }

        btnPasswordSave?.setOnClickListener { onSavePassword() }
        btnPasswordCancel?.setOnClickListener { showSetup() }

        btnConfirm?.setOnClickListener { onConfirmCode() }

        btnResultDone?.setOnClickListener { finish() }
    }

    /* ---------------------------- choice ---------------------------- */

    private fun onProtectedChosen() {
        pendingProtected = true
        when (RecoveryKeyStore.getSetupState(this)) {
            RecoveryKeyStore.SetupState.UNAVAILABLE -> showKeystoreFailure()
            RecoveryKeyStore.SetupState.CONFIRMED -> buildThenSaveAs(protected = true)
            RecoveryKeyStore.SetupState.UNCONFIRMED -> {
                // A secret exists but was never confirmed: re-show setup so the
                // user records and re-confirms it (never proceed unconfirmed).
                when (val s = RecoveryKeyStore.getSecret(this)) {
                    is RecoveryKeyStore.SecretResult.Ok -> showSetupWith(s.secret)
                    is RecoveryKeyStore.SecretResult.Unavailable -> showKeystoreFailure()
                    is RecoveryKeyStore.SecretResult.NotSet -> generateAndSetup()
                }
            }
            RecoveryKeyStore.SetupState.NOT_SET -> generateAndSetup()
        }
    }

    private fun onUnencryptedChosen() {
        // The warning is the choice-screen description; proceed to build + Save As.
        pendingProtected = false
        buildThenSaveAs(protected = false)
    }

    private fun generateAndSetup() {
        val secret = PackageCrypto.newRecoverySecret()
        if (!RecoveryKeyStore.setSecret(this, secret)) {
            PackageCrypto.wipe(secret)
            showKeystoreFailure()
            return
        }
        showSetupWith(secret)
    }

    /** Take ownership of [secret] as the pending setup secret and show setup. */
    private fun showSetupWith(secret: ByteArray) {
        setPendingSecret(secret)
        textRecoveryCode?.text = RecoveryCode.encode(secret)
        showSetup()
    }

    private fun setPendingSecret(secret: ByteArray) {
        pendingSecret?.let { PackageCrypto.wipe(it) }
        pendingSecret = secret
    }

    /* ---------------------------- setup ---------------------------- */

    private fun copyCode() {
        val code = textRecoveryCode?.text?.toString() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("Recovery Code", code)
        // Mark the clip sensitive so the system (and keyboards/launchers that
        // honour it) do not surface a clipboard preview of the Recovery Code.
        // ClipDescription.EXTRA_IS_SENSITIVE is API 33+; the literal key is
        // read by supporting platforms from API 24 up. We do NOT auto-clear the
        // clipboard (owner: the user needs it to paste into a password manager).
        try {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        } catch (_: Exception) { /* best-effort; copy still proceeds */ }
        clipboard.setPrimaryClip(clip)
        // Brief inline acknowledgement without a toast (owner no-toast rule):
        btnCopyCode?.text = getString(R.string.recovery_code_copied)
    }

    private fun writeKeyFile(uri: Uri) {
        val secret = pendingSecret ?: return
        runOffThread {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                it.write(RecoveryKeyFile.serialize(secret))
            } ?: throw IllegalStateException("could not open destination")
            runOnUiThread { btnSaveKeyFile?.text = getString(R.string.recovery_key_saved) }
        }
    }

    /* ---------------------------- password ---------------------------- */

    private fun onSavePassword() {
        val pw = editPassword?.text?.toString().orEmpty()
        val confirm = editPasswordConfirm?.text?.toString().orEmpty()
        textPasswordError?.visibility = View.GONE
        if (pw.isEmpty()) {
            textPasswordError?.setText(R.string.recovery_password_empty)
            textPasswordError?.visibility = View.VISIBLE
            return
        }
        if (pw != confirm) {
            textPasswordError?.setText(R.string.recovery_password_mismatch)
            textPasswordError?.visibility = View.VISIBLE
            return
        }
        val secret = pendingSecret ?: return
        btnPasswordSave?.isEnabled = false
        runOffThread {
            // KDF runs here only (password set), off the main thread.
            val salt = PackageCrypto.newKdfSalt()
            val iterations = PackageCrypto.calibratedIterations()
            val kek = PackageCrypto.derivePasswordKey(pw.toCharArray(), salt, iterations)
            val wrappedRs = PackageCrypto.wrapRecoverySecret(kek, secret)
            PackageCrypto.wipe(kek)
            val stored = RecoveryKeyStore.setPasswordBlob(
                this, RecoveryKeyStore.StoredPasswordBlob(salt, iterations, wrappedRs)
            )
            runOnUiThread {
                btnPasswordSave?.isEnabled = true
                if (stored) {
                    // Clear the entered password from the fields once stored.
                    editPassword?.text = null
                    editPasswordConfirm?.text = null
                    showSetup()
                } else {
                    showKeystoreFailure()
                }
            }
        }
    }

    /* ---------------------------- confirm ---------------------------- */

    private fun onConfirmCode() {
        textConfirmError?.visibility = View.GONE
        val entered = editConfirmCode?.text?.toString().orEmpty()
        val secret = pendingSecret ?: return
        val decoded = RecoveryCode.decode(entered)
        val matches = decoded is RecoveryCode.DecodeResult.Ok &&
            MessageDigest.isEqual(decoded.secret, secret)   // full 16-byte constant-time compare
        if (decoded is RecoveryCode.DecodeResult.Ok) {
            PackageCrypto.wipe(decoded.secret)              // wipe the decoded secret regardless of match
        }
        if (!matches) {
            textConfirmError?.visibility = View.VISIBLE
            return
        }
        // Durably record confirmation; proceed ONLY if the write succeeds.
        if (!RecoveryKeyStore.markConfirmed(this)) {
            showKeystoreFailure()
            return
        }
        editConfirmCode?.text = null                        // clear the typed code
        buildThenSaveAs(protected = true)
    }

    /* ---------------------------- create ---------------------------- */

    /**
     * Build and verify the package into cache staging FIRST, then launch Save
     * As (owner correction 6). The build worker re-reads the stored secret and
     * password blob rather than sharing the activity field, and wipes the
     * secret in a finally.
     */
    private fun buildThenSaveAs(protected: Boolean) {
        pendingProtected = protected
        cleanupStaged()
        showResult()
        // Owner correction (July 22 2026): say plainly that Save As comes
        // AFTER the package is ready, so the quiet build phase is not
        // mistaken for a hang or a missing picker.
        textResult?.setText(R.string.recovery_preparing)
        textResultDetail?.visibility = View.GONE
        btnResultDone?.visibility = View.GONE

        runOffThread {
            val staged = File(cacheDir, "recovery_stage_${System.nanoTime()}.tmp")
            var secret: ByteArray? = null
            try {
                if (protected) {
                    secret = when (val s = RecoveryKeyStore.getSecret(this)) {
                        is RecoveryKeyStore.SecretResult.Ok -> s.secret
                        is RecoveryKeyStore.SecretResult.Unavailable -> {
                            runOnUiThread { showKeystoreFailure() }; return@runOffThread
                        }
                        is RecoveryKeyStore.SecretResult.NotSet -> {
                            runOnUiThread { showFailureText(getString(R.string.recovery_fail_generic)) }; return@runOffThread
                        }
                    }
                }

                val passwordBlob: PortablePackageFormat.PasswordBlob? = if (protected) {
                    when (val b = RecoveryKeyStore.getPasswordBlob(this)) {
                        is RecoveryKeyStore.PasswordBlobResult.NotSet -> null
                        is RecoveryKeyStore.PasswordBlobResult.Ok -> b.blob.toFormatBlob()
                        is RecoveryKeyStore.PasswordBlobResult.Unavailable -> {
                            // A password WAS configured but its blob is
                            // unavailable/malformed: fail visibly, never silently
                            // drop the password restore route (owner correction 5).
                            runOnUiThread { showKeystoreFailure() }; return@runOffThread
                        }
                    }
                } else null

                val appVersion = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }

                when (val result = PortableRecoveryWriter.createPackage(this, staged, secret, passwordBlob, appVersion)) {
                    is PortableRecoveryWriter.Result.Failed -> {
                        runCatching { if (staged.exists()) staged.delete() }
                        recordFailureStatus(result.reason)
                        runOnUiThread { showWriterFailure(result.reason, result.chatFailure) }
                    }
                    is PortableRecoveryWriter.Result.Ok -> {
                        val sha = sha256(staged)
                        runOnUiThread {
                            stagedPackage = staged
                            stagedSha = sha
                            stagedIncludedTypes = result.includedTypes
                            val name = RecoveryFileNaming.manualRecoveryPackage(
                                BackupBrand.resolve(this), protected, System.currentTimeMillis()
                            )
                            createPackageLauncher.launch(name)
                        }
                    }
                }
            } catch (_: Exception) {
                runCatching { if (staged.exists()) staged.delete() }
                recordFailureStatusAll(BackupFailureCategory.SOURCE)
                runOnUiThread { showFailureText(getString(R.string.recovery_fail_generic)) }
            } finally {
                secret?.let { PackageCrypto.wipe(it) }
            }
        }
    }

    /** Save As returned. Null == the user cancelled the picker. */
    private fun onSaveAsResult(uri: Uri?) {
        if (uri == null) {
            // Cancelled: discard the staged package and return to the choice.
            cleanupStaged()
            showChoice()
            return
        }
        copyStagedToDestination(uri)
    }

    /** Copy the verified staged package to the chosen destination, then reopen
     *  the destination and verify its SHA-256 matches the staged file. On any
     *  mismatch/failure the destination is discarded and a visible failure is
     *  shown. Staging is cleaned on every path. */
    private fun copyStagedToDestination(uri: Uri) {
        val staged = stagedPackage
        val expectedSha = stagedSha
        if (staged == null || expectedSha == null || !staged.exists()) {
            cleanupStaged()
            showFailureText(getString(R.string.recovery_fail_generic))
            return
        }
        showResult()
        textResult?.setText(R.string.recovery_saving)
        textResultDetail?.visibility = View.GONE
        btnResultDone?.visibility = View.GONE

        val includedTypes = stagedIncludedTypes
        runOffThread {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    staged.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("could not open destination")

                val actualSha = contentResolver.openInputStream(uri)?.use { sha256(it) }
                    ?: throw IllegalStateException("could not reopen destination")

                if (!MessageDigest.isEqual(expectedSha, actualSha)) {
                    discardDestination(uri)
                    recordFailureStatusAll(BackupFailureCategory.VERIFY)
                    runOnUiThread { showFailureText(getString(R.string.recovery_fail_verify)) }
                    return@runOffThread
                }

                // BOTH verifications passed (staged package + destination):
                // only now may Backup Status claim success (owner rule). Types
                // with no artifact in this package record the neutral
                // "Nothing to Back Up" state for this run.
                val now = System.currentTimeMillis()
                for (type in BackupType.displayOrder) {
                    if (type in includedTypes) RecoveryBackupState.recordSuccess(this, type, now)
                    else RecoveryBackupState.recordNothingToBackUp(this, type, now)
                }

                val where = BackupLocationDisplay.describeSaveAs(this, uri)
                runOnUiThread { showSaved(where) }
            } catch (e: Exception) {
                discardDestination(uri)
                recordFailureStatusAll(
                    if (BackupFailureClassifier.isPermissionRelated(e)) BackupFailureCategory.DESTINATION_PERMISSION
                    else BackupFailureCategory.DESTINATION_WRITE
                )
                runOnUiThread { showFailureText(getString(R.string.recovery_fail_generic)) }
            } finally {
                cleanupStaged()
            }
        }
    }

    /**
     * Record a failed manual attempt in Backup Status. recordFailure keeps
     * the last-success stamp untouched and marks the latest attempt failed —
     * exactly the owner-required display ("Backup Failed. Last Good Backup:
     * …"). NOTHING_TO_BACK_UP is neutral, not a failure.
     */
    private fun recordFailureStatus(reason: PortableRecoveryWriter.Reason) {
        val now = System.currentTimeMillis()
        if (reason == PortableRecoveryWriter.Reason.NOTHING_TO_BACK_UP) {
            for (type in BackupType.displayOrder) RecoveryBackupState.recordNothingToBackUp(this, type, now)
            return
        }
        if (reason == PortableRecoveryWriter.Reason.STORE_DEGRADED) {
            // A degraded-database refusal is a PAUSE, not a backup failure:
            // recording it would poison every type's failure streak (and the
            // 3-strikes source routing) for a condition the repair flow owns.
            // The status rows keep their last real result.
            return
        }
        val category = when (reason) {
            PortableRecoveryWriter.Reason.PACKAGE_VERIFY_FAILED -> BackupFailureCategory.VERIFY
            else -> BackupFailureCategory.SOURCE
        }
        for (type in BackupType.displayOrder) RecoveryBackupState.recordFailure(this, type, now, category)
    }

    private fun recordFailureStatusAll(category: BackupFailureCategory) {
        val now = System.currentTimeMillis()
        for (type in BackupType.displayOrder) RecoveryBackupState.recordFailure(this, type, now, category)
    }

    /** Remove a written-but-unverified destination so no corrupt package
     *  remains: delete the document, or truncate it to empty if delete is not
     *  permitted by the provider. */
    private fun discardDestination(uri: Uri) {
        try {
            if (DocumentsContract.deleteDocument(contentResolver, uri)) return
        } catch (_: Exception) { /* fall through to truncate */ }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { /* write nothing = truncate */ }
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun cleanupStaged() {
        stagedPackage?.let { f -> runCatching { if (f.exists()) f.delete() } }
        stagedPackage = null
        stagedSha = null
        stagedIncludedTypes = emptySet()
    }

    private fun showWriterFailure(
        reason: PortableRecoveryWriter.Reason,
        chatFailure: ChatLogicalSerializer.FailureCategory?
    ) {
        when (reason) {
            PortableRecoveryWriter.Reason.KEY_MATERIAL_UNAVAILABLE -> showKeystoreFailure()
            PortableRecoveryWriter.Reason.CHATS_UNAVAILABLE -> {
                // Main line + a plain-words detail naming WHICH part of chat
                // storage failed — diagnosable, never an enum or exception.
                showFailureText(getString(R.string.recovery_fail_chats_unavailable))
                val detail = when (chatFailure) {
                    ChatLogicalSerializer.FailureCategory.LIST -> R.string.recovery_fail_chats_detail_list
                    ChatLogicalSerializer.FailureCategory.HISTORY -> R.string.recovery_fail_chats_detail_history
                    ChatLogicalSerializer.FailureCategory.SETTINGS -> R.string.recovery_fail_chats_detail_settings
                    null -> null
                }
                if (detail != null) {
                    textResultDetail?.setText(detail)
                    textResultDetail?.visibility = View.VISIBLE
                }
            }
            PortableRecoveryWriter.Reason.SNAPSHOT_FAILED ->
                showFailureText(getString(R.string.recovery_fail_snapshot))
            PortableRecoveryWriter.Reason.PACKAGE_VERIFY_FAILED ->
                showFailureText(getString(R.string.recovery_fail_verify))
            PortableRecoveryWriter.Reason.NOTHING_TO_BACK_UP ->
                showFailureText(getString(R.string.recovery_fail_nothing))
            PortableRecoveryWriter.Reason.STORE_DEGRADED ->
                showFailureText(getString(R.string.recovery_fail_degraded))
        }
    }

    private fun showSaved(where: BackupLocationDisplay.SaveAsDescription) {
        showResult()
        val destination = where.breadcrumb ?: where.providerLabel
        textResult?.text = if (destination != null) {
            getString(R.string.recovery_saved_to, destination)
        } else {
            getString(R.string.recovery_saved_generic)
        }
        if (where.fileName != null) {
            textResultDetail?.text = getString(R.string.recovery_saved_file, where.fileName)
            textResultDetail?.visibility = View.VISIBLE
        } else {
            textResultDetail?.visibility = View.GONE
        }
        btnResultDone?.visibility = View.VISIBLE
    }

    /** Show a failure on the result panel. Only human-readable, owner-approved
     *  wording reaches the screen — never an enum or exception name (owner
     *  correction 7). */
    private fun showFailureText(main: String) {
        showResult()
        textResult?.text = main
        textResultDetail?.visibility = View.GONE
        btnResultDone?.visibility = View.VISIBLE
    }

    private fun showKeystoreFailure() {
        showFailureText(getString(R.string.recovery_fail_keystore))
    }

    /* ---------------------------- panel switching ---------------------------- */

    private fun showOnly(panel: LinearLayout?) {
        for (p in listOf(panelChoice, panelSetup, panelPassword, panelConfirm, panelResult)) {
            p?.visibility = if (p === panel) View.VISIBLE else View.GONE
        }
    }

    private fun showChoice() = showOnly(panelChoice)
    private fun showSetup() = showOnly(panelSetup)
    private fun showPassword() = showOnly(panelPassword)
    private fun showConfirm() = showOnly(panelConfirm)
    private fun showResult() = showOnly(panelResult)

    /* ---------------------------- helpers ---------------------------- */

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

    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (_: Exception) {
                runOnUiThread { showFailureText(getString(R.string.recovery_fail_generic)) }
            }
        }.start()
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 35) return
        try {
            actionBar?.setPadding(
                0, window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top, 0, 0
            )
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0, 0, 0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom +
                    (24 * resources.displayMetrics.density).toInt()
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun isDarkThemeEnabled(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
