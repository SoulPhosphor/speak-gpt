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
import org.teslasoft.assistant.preferences.backup.BackupLocationDisplay
import org.teslasoft.assistant.preferences.backup.RecoveryFileNaming
import org.teslasoft.assistant.preferences.backup.portable.PackageCrypto
import org.teslasoft.assistant.preferences.backup.portable.PortablePackageFormat
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoveryWriter
import org.teslasoft.assistant.preferences.backup.portable.RecoveryCode
import org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyFile
import org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyStore
import org.teslasoft.assistant.theme.ThemeManager
import java.io.File

/**
 * The manual Recovery Backup flow (portable format v2). One activity, five
 * panels driven by a small state machine — the owner-approved flow (July 22
 * 2026): one entry, a choice of Protected / Unencrypted, one-time Recovery
 * Code setup with a required re-entry confirmation before the code can gate
 * automatic backups later, and Save As package creation.
 *
 * SCOPE FENCE (owner): NO automatic scheduling, NO automatic writer, NO
 * rotation, NO deletion, NO live-data restore here. This exists for the
 * controlled on-device test gate.
 *
 * The Recovery Secret is held in a field only for the brief setup+create
 * window and wiped on teardown; the durable copy lives in RecoveryKeyStore.
 */
class RecoveryBackupActivity : FragmentActivity() {

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

    /** The secret being set up (protected flow), held only during the flow. */
    private var pendingSecret: ByteArray? = null

    /** True while the pending Save As is a protected package. */
    private var pendingProtected = false

    private val saveKeyFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeKeyFile(uri) }

    private val createPackageLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) createPackageInto(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_recovery_backup)
        preferences = Preferences.getPreferences(this, "")
        bindViews()
        applyTheme()
        initLogic()
        showChoice()
    }

    override fun onDestroy() {
        pendingSecret?.let { PackageCrypto.wipe(it) }
        pendingSecret = null
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
        when (val existing = RecoveryKeyStore.getSecret(this)) {
            is RecoveryKeyStore.SecretResult.Unavailable -> showKeystoreFailure()
            is RecoveryKeyStore.SecretResult.Ok -> {
                // Already configured: go straight to Save As (no repeat setup).
                pendingSecret = existing.secret
                launchCreate(protected = true)
            }
            is RecoveryKeyStore.SecretResult.NotSet -> {
                val secret = PackageCrypto.newRecoverySecret()
                if (!RecoveryKeyStore.setSecret(this, secret)) {
                    PackageCrypto.wipe(secret)
                    showKeystoreFailure()
                    return
                }
                pendingSecret = secret
                textRecoveryCode?.text = RecoveryCode.encode(secret)
                showSetup()
            }
        }
    }

    private fun onUnencryptedChosen() {
        // The warning is the choice-screen description; proceed to Save As.
        pendingProtected = false
        launchCreate(protected = false)
    }

    /* ---------------------------- setup ---------------------------- */

    private fun copyCode() {
        val code = textRecoveryCode?.text?.toString() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Recovery Code", code))
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
        if (pw.isEmpty()) return
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
                if (stored) showSetup() else showKeystoreFailure()
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
            PackageCrypto.fingerprintMatches(
                PackageCrypto.fingerprint(decoded.secret),
                PackageCrypto.fingerprint(secret)
            )
        if (!matches) {
            textConfirmError?.visibility = View.VISIBLE
            return
        }
        launchCreate(protected = true)
    }

    /* ---------------------------- create ---------------------------- */

    private fun launchCreate(protected: Boolean) {
        pendingProtected = protected
        val name = RecoveryFileNaming.manualRecoveryPackage(
            BackupBrand.resolve(this), protected, System.currentTimeMillis()
        )
        createPackageLauncher.launch(name)
    }

    private fun createPackageInto(uri: Uri) {
        showResult()
        textResult?.setText(R.string.recovery_creating)
        textResultDetail?.visibility = View.GONE
        btnResultDone?.visibility = View.GONE

        runOffThread {
            val cacheFile = File(cacheDir, "recovery_out_${System.nanoTime()}.tmp")
            try {
                val secret = if (pendingProtected) {
                    when (val s = RecoveryKeyStore.getSecret(this)) {
                        is RecoveryKeyStore.SecretResult.Ok -> s.secret
                        is RecoveryKeyStore.SecretResult.Unavailable -> {
                            runOnUiThread { showKeystoreFailure() }; return@runOffThread
                        }
                        is RecoveryKeyStore.SecretResult.NotSet -> {
                            runOnUiThread { showFailureText(getString(R.string.recovery_fail_generic), "") }; return@runOffThread
                        }
                    }
                } else null

                val passwordBlob: PortablePackageFormat.PasswordBlob? =
                    if (pendingProtected) RecoveryKeyStore.getPasswordBlob(this)?.toFormatBlob() else null

                val appVersion = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }

                val result = PortableRecoveryWriter.createPackage(this, cacheFile, secret, passwordBlob, appVersion)
                if (result is PortableRecoveryWriter.Result.Failed) {
                    runOnUiThread { showWriterFailure(result.reason) }
                    return@runOffThread
                }

                // Stream the verified package to the user-chosen destination.
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    cacheFile.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("could not open destination")

                val where = BackupLocationDisplay.describeSaveAs(this, uri)
                runOnUiThread { showSaved(where) }
            } catch (_: Exception) {
                runOnUiThread { showFailureText(getString(R.string.recovery_fail_generic), "") }
            } finally {
                try { if (cacheFile.exists()) cacheFile.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun showWriterFailure(reason: PortableRecoveryWriter.Reason) {
        when (reason) {
            PortableRecoveryWriter.Reason.KEY_MATERIAL_UNAVAILABLE -> showKeystoreFailure()
            else -> showFailureText(getString(R.string.recovery_fail_generic), reason.name)
        }
    }

    private fun showSaved(where: BackupLocationDisplay.SaveAsDescription) {
        showResult()
        textResult?.text = if (where.providerLabel != null) {
            getString(R.string.recovery_saved_to, where.providerLabel)
        } else {
            getString(R.string.recovery_saved_generic)
        }
        if (where.fileName != null) {
            textResultDetail?.text = getString(R.string.recovery_saved_file, where.fileName)
            textResultDetail?.visibility = View.VISIBLE
        }
        btnResultDone?.visibility = View.VISIBLE
    }

    private fun showFailureText(main: String, detail: String) {
        showResult()
        textResult?.text = main
        if (detail.isNotEmpty()) {
            textResultDetail?.text = detail
            textResultDetail?.visibility = View.VISIBLE
        } else {
            textResultDetail?.visibility = View.GONE
        }
        btnResultDone?.visibility = View.VISIBLE
    }

    private fun showKeystoreFailure() {
        showFailureText(getString(R.string.recovery_fail_keystore), "")
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

    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                runOnUiThread { showFailureText(getString(R.string.recovery_fail_generic), e.javaClass.simpleName) }
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
