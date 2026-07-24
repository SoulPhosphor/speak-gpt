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
import org.teslasoft.assistant.preferences.backup.portable.PackageCrypto
import org.teslasoft.assistant.preferences.backup.portable.RecoveryCode
import org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyFile
import org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyStore
import org.teslasoft.assistant.theme.ThemeManager
import java.security.MessageDigest

/**
 * The one-time Protected Recovery setup flow (portable format v2). Reveals
 * the generated Recovery Code, lets the user save/copy it and optionally add
 * a password, then requires re-entering the code to confirm it was actually
 * captured before the Recovery Secret may ever be used.
 *
 * SCOPE (owner directive, July 24 2026 — supersedes the earlier five-panel
 * design): this activity handles setup/confirmation ONLY. It is launched by
 * [MemoryBackupRestoreActivity] exactly when Protected Recovery is chosen and
 * has not yet been confirmed, and finishes with [android.app.Activity.RESULT_OK]
 * once confirmed so the caller can proceed straight into building and saving
 * the backup inline — there is no "choice" panel and no build/save/result UI
 * here any more; that all lives on the Memory Backup & Restore screen now
 * (the same one-tap Save-As + inline status pattern as the other backup
 * types there), so a Protected save is exactly as fast as an Unencrypted one
 * after the first-time setup below.
 *
 * SETUP STATE (owner correction 2, July 22 2026): storing the Recovery Secret
 * is NOT the same as configuring it. A freshly generated secret is UNCONFIRMED
 * and cannot produce a protected package until the user re-enters the complete
 * Recovery Code AND the CONFIRMED marker is durably written. This confirmation
 * step is the standard pattern for any once-shown, unrecoverable recovery key
 * (the same reason password managers and crypto wallets ask you to retype a
 * generated key before it becomes load-bearing) — it exists to prove the code
 * was actually captured, not to gate re-entering something the user chose.
 *
 * SECRET LIFECYCLE (owner correction 3/4): confirmation compares the COMPLETE
 * 16-byte secret in constant time and wipes the decoded copy; entered
 * password/code fields are cleared after use.
 */
class RecoveryBackupActivity : FragmentActivity() {

    private var preferences: Preferences? = null

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var panelSetup: LinearLayout? = null
    private var panelPassword: LinearLayout? = null
    private var panelConfirm: LinearLayout? = null
    private var panelFailed: LinearLayout? = null

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

    private var textFailed: TextView? = null
    private var btnFailedDone: MaterialButton? = null

    /** The secret being set up, held only during the setup + confirm panels
     *  for display and constant-time confirmation. */
    private var pendingSecret: ByteArray? = null

    private val saveKeyFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) writeKeyFile(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_recovery_backup)
        preferences = Preferences.getPreferences(this, "")
        bindViews()
        applyTheme()
        initLogic()
        startSetupFlow()
    }

    override fun onDestroy() {
        pendingSecret?.let { PackageCrypto.wipe(it) }
        pendingSecret = null
        super.onDestroy()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        panelSetup = findViewById(R.id.panel_setup)
        panelPassword = findViewById(R.id.panel_password)
        panelConfirm = findViewById(R.id.panel_confirm)
        panelFailed = findViewById(R.id.panel_failed)
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
        textFailed = findViewById(R.id.text_failed)
        btnFailedDone = findViewById(R.id.btn_failed_done)
    }

    private fun initLogic() {
        btnBack?.setOnClickListener { finish() }

        btnSaveKeyFile?.setOnClickListener {
            saveKeyFileLauncher.launch("${BackupBrand.resolve(this)}-Recovery-Key.json")
        }
        btnCopyCode?.setOnClickListener { copyCode() }
        btnAddPassword?.setOnClickListener { showPassword() }
        btnSetupContinue?.setOnClickListener { showConfirm() }

        btnPasswordSave?.setOnClickListener { onSavePassword() }
        btnPasswordCancel?.setOnClickListener { showSetup() }

        btnConfirm?.setOnClickListener { onConfirmCode() }

        btnFailedDone?.setOnClickListener { finish() }
    }

    /* ---------------------------- entry ---------------------------- */

    /** Jump straight to the right panel for the current setup state — there is
     *  no choice panel here any more; [MemoryBackupRestoreActivity] already
     *  decided Protected Recovery is what's being set up before launching
     *  this activity. */
    private fun startSetupFlow() {
        when (RecoveryKeyStore.getSetupState(this)) {
            RecoveryKeyStore.SetupState.UNAVAILABLE -> showKeystoreFailure()
            RecoveryKeyStore.SetupState.CONFIRMED -> {
                // Defensive only: the caller should not launch this activity
                // when already confirmed.
                setResult(RESULT_OK)
                finish()
            }
            RecoveryKeyStore.SetupState.UNCONFIRMED -> {
                // A secret exists but was never confirmed: re-show setup so
                // the user records and re-confirms it (never proceed
                // unconfirmed).
                when (val s = RecoveryKeyStore.getSecret(this)) {
                    is RecoveryKeyStore.SecretResult.Ok -> showSetupWith(s.secret)
                    is RecoveryKeyStore.SecretResult.Unavailable -> showKeystoreFailure()
                    is RecoveryKeyStore.SecretResult.NotSet -> generateAndSetup()
                }
            }
            RecoveryKeyStore.SetupState.NOT_SET -> generateAndSetup()
        }
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
        setResult(RESULT_OK)
        finish()
    }

    /* ---------------------------- failure ---------------------------- */

    private fun showKeystoreFailure() {
        showOnly(panelFailed)
        textFailed?.text = getString(R.string.recovery_fail_keystore)
    }

    /* ---------------------------- panel switching ---------------------------- */

    private fun showOnly(panel: LinearLayout?) {
        for (p in listOf(panelSetup, panelPassword, panelConfirm, panelFailed)) {
            p?.visibility = if (p === panel) View.VISIBLE else View.GONE
        }
    }

    private fun showSetup() = showOnly(panelSetup)
    private fun showPassword() = showOnly(panelPassword)
    private fun showConfirm() = showOnly(panelConfirm)

    /* ---------------------------- helpers ---------------------------- */

    private fun runOffThread(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (_: Exception) {
                runOnUiThread { showKeystoreFailure() }
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
