/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.BackupBrand
import org.teslasoft.assistant.preferences.backup.RecoveryFileNaming
import org.teslasoft.assistant.preferences.backup.portable.ChatLogicalImportPlan
import org.teslasoft.assistant.preferences.backup.portable.LegacyChatConversion
import org.teslasoft.assistant.theme.ThemeManager

/** Temporary, removable bridge from the old logical export to a real chat-recovery file. */
class LegacyChatConverterActivity : FragmentActivity() {

    private var actionBar: ConstraintLayout? = null
    private var chooseButton: MaterialButton? = null
    private var progress: CircularProgressIndicator? = null
    private var status: TextView? = null
    private var stagedRecovery: File? = null
    private var stagedRecoverySha: ByteArray? = null
    private var convertedSummary: String? = null

    private val sourcePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) convert(uri) }

    private val savePicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> saveConvertedRecovery(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_legacy_chat_converter)

        actionBar = findViewById(R.id.action_bar)
        chooseButton = findViewById(R.id.btn_choose_legacy_package)
        progress = findViewById(R.id.conversion_progress)
        status = findViewById(R.id.text_conversion_status)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        chooseButton?.setOnClickListener { confirmAndChooseSource() }
    }

    private fun confirmAndChooseSource() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.legacy_convert_confirm_title)
            .setMessage(R.string.legacy_convert_confirm_body)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .setPositiveButton(R.string.legacy_convert_choose_file) { _, _ ->
                sourcePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }
            .show()
    }

    private fun convert(uri: Uri) {
        cleanupStagedRecovery()
        setBusy(R.string.legacy_convert_working)
        Thread {
            val sourceCopy = copySource(uri)
            if (sourceCopy == null) {
                runOnUiThread { setIdle(getString(R.string.legacy_convert_err_unreadable)) }
                return@Thread
            }
            val recovery = File(cacheDir, "converted-chat-recovery-${System.nanoTime()}.zip")
            val outcome = try {
                LegacyChatConversion.convert(applicationContext, sourceCopy, recovery)
            } catch (_: Exception) {
                LegacyChatConversion.Outcome.StagingUnreadable
            } finally {
                runCatching { sourceCopy.delete() }
            }
            if (outcome !is LegacyChatConversion.Outcome.Ok) {
                runCatching { recovery.delete() }
                runOnUiThread { setIdle(conversionMessage(outcome)) }
                return@Thread
            }

            try {
                val expectedSha = sha256(recovery.inputStream())
                convertedSummary = getString(
                    R.string.legacy_convert_done,
                    outcome.report.chatsWritten,
                    outcome.report.messagesWritten,
                    outcome.report.settingsWritten
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        runCatching { recovery.delete() }
                        return@runOnUiThread
                    }
                    stagedRecovery = recovery
                    stagedRecoverySha = expectedSha
                    setStatus(getString(R.string.legacy_convert_choose_save_location))
                    savePicker.launch(convertedFileName())
                }
            } catch (_: Exception) {
                runCatching { recovery.delete() }
                runOnUiThread { setIdle(getString(R.string.legacy_convert_err_archive)) }
            }
        }.start()
    }

    /** The selected source is copied into private cache and is never written or deleted. */
    private fun copySource(uri: Uri): File? {
        return try {
            val target = File(cacheDir, "legacy-conversion-${System.nanoTime()}.tmp")
            val source = contentResolver.openInputStream(uri) ?: return null
            source.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        } catch (_: Exception) {
            null
        }
    }

    private fun saveConvertedRecovery(uri: Uri?) {
        if (uri == null) {
            cleanupStagedRecovery()
            setIdle(getString(R.string.legacy_convert_save_cancelled))
            return
        }
        val staged = stagedRecovery
        val expectedSha = stagedRecoverySha
        if (staged == null || expectedSha == null || !staged.exists()) {
            cleanupStagedRecovery()
            setIdle(getString(R.string.legacy_convert_err_archive))
            return
        }
        setBusy(R.string.legacy_convert_saving)
        Thread {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    staged.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("destination unavailable")
                val actualSha = contentResolver.openInputStream(uri)?.use(::sha256)
                    ?: throw IllegalStateException("destination unreadable")
                if (!MessageDigest.isEqual(expectedSha, actualSha)) {
                    discardDestination(uri)
                    throw IllegalStateException("destination mismatch")
                }
                val completedSummary = convertedSummary.orEmpty()
                runOnUiThread {
                    setIdle(completedSummary + "\n\n" + getString(R.string.legacy_convert_saved_next_step))
                }
            } catch (_: Exception) {
                discardDestination(uri)
                runOnUiThread { setIdle(getString(R.string.legacy_convert_err_save)) }
            } finally {
                cleanupStagedRecovery()
            }
        }.start()
    }

    private fun convertedFileName(): String =
        "${BackupBrand.resolve(this)}-Converted-Chats-Recovery-" +
            RecoveryFileNaming.stamp(System.currentTimeMillis()) + ".zip"

    private fun conversionMessage(outcome: LegacyChatConversion.Outcome): String = when (outcome) {
        is LegacyChatConversion.Outcome.Ok -> error("handled before message mapping")
        LegacyChatConversion.Outcome.EncryptedPackageUnsupported ->
            getString(R.string.legacy_convert_err_encrypted)
        is LegacyChatConversion.Outcome.PackageUnusable ->
            getString(R.string.legacy_convert_err_package)
        LegacyChatConversion.Outcome.NoChatsInPackage ->
            getString(R.string.legacy_convert_err_no_chats)
        LegacyChatConversion.Outcome.StagingUnreadable ->
            getString(R.string.legacy_convert_err_unreadable)
        is LegacyChatConversion.Outcome.ChatsRejected -> getString(
            R.string.legacy_convert_err_rejected,
            getString(rejectionReason(outcome.reason))
        )
        LegacyChatConversion.Outcome.ArchiveWriteFailed ->
            getString(R.string.legacy_convert_err_archive)
    }

    private fun rejectionReason(reason: ChatLogicalImportPlan.Reason): Int = when (reason) {
        ChatLogicalImportPlan.Reason.NOT_A_CHATS_ARTIFACT -> R.string.legacy_convert_reason_not_chats
        ChatLogicalImportPlan.Reason.UNSUPPORTED_FORMAT -> R.string.legacy_convert_reason_format
        ChatLogicalImportPlan.Reason.INCOMPLETE_ARTIFACT -> R.string.legacy_convert_reason_incomplete
        ChatLogicalImportPlan.Reason.MALFORMED -> R.string.legacy_convert_reason_malformed
        ChatLogicalImportPlan.Reason.IDENTITY_MISMATCH -> R.string.legacy_convert_reason_identity
        ChatLogicalImportPlan.Reason.DUPLICATE_CHAT_ID -> R.string.legacy_convert_reason_duplicate
        ChatLogicalImportPlan.Reason.UNSUPPORTED_SETTING_TYPE -> R.string.legacy_convert_reason_setting
    }

    private fun setBusy(message: Int) {
        chooseButton?.isEnabled = false
        progress?.visibility = View.VISIBLE
        setStatus(getString(message))
    }

    private fun setIdle(message: String) {
        chooseButton?.isEnabled = true
        progress?.visibility = View.GONE
        setStatus(message)
    }

    private fun setStatus(message: String) {
        status?.text = message
        status?.visibility = View.VISIBLE
    }

    private fun sha256(input: InputStream): ByteArray = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest()
    }

    private fun discardDestination(uri: Uri) {
        try {
            if (DocumentsContract.deleteDocument(contentResolver, uri)) return
        } catch (_: Exception) { }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { }
        } catch (_: Exception) { }
    }

    private fun cleanupStagedRecovery() {
        stagedRecovery?.let { runCatching { if (it.exists()) it.delete() } }
        stagedRecovery = null
        stagedRecoverySha = null
        convertedSummary = null
    }

    override fun onDestroy() {
        cleanupStagedRecovery()
        super.onDestroy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                actionBar?.setPadding(
                    0,
                    window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                    0,
                    0
                )
            } catch (_: Exception) { }
        }
    }
}
