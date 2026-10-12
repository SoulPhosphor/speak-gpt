/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.portable.PackageCrypto
import org.teslasoft.assistant.preferences.backup.portable.PortablePackage
import org.teslasoft.assistant.preferences.backup.portable.PortablePackageFormat
import org.teslasoft.assistant.preferences.backup.portable.PortableStaging
import org.teslasoft.assistant.preferences.backup.portable.RecoveryCode
import org.teslasoft.assistant.preferences.backup.portable.RecoveryKeyFile
import org.teslasoft.assistant.preferences.damaged.DamagedChatBackupReader
import org.teslasoft.assistant.preferences.damaged.DamagedChatLog
import org.teslasoft.assistant.preferences.damaged.DamagedChatRepair
import org.teslasoft.assistant.preferences.username.DefaultUsernameFill
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Damaged Chat Files (owner rulings, Oct 12 2026), opened from the logs
 *  list: the damaged chats, Try Repair, and the Irreparable Chat Files. */
class DamagedChatLogActivity : SettingsPageActivity() {

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_damaged_chat_log)
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        ScreenChrome.apply(this, actionBar, btnBack)
        btnBack?.setOnClickListener { finish() }
    }

    private var repairRunning = false

    // Attempt Chat Replacement from Backup: the backup copied into staging,
    // cleaned up when the flow ends.
    private var replaceRoot: File? = null
    private var replacePackage: File? = null
    private var replaceInspection: PortablePackage.Inspection? = null

    private val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectBackup(uri) else endReplacement()
    }

    private val keyFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) unlockWithKeyFile(uri) else showUnlockChoices()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val app = applicationContext
        Thread {
            val detections = DamagedChatLog.detections(app)
            runOnUiThread { if (!isFinishing && !isDestroyed) render(detections) }
        }.start()
    }

    private fun render(detections: List<DamagedChatLog.Detection>) {
        val damaged = detections.mapNotNull { d ->
            d.chats.filter { !it.irreparable }.takeIf { it.isNotEmpty() }?.let { d.copy(chats = it) }
        }
        val irreparable = detections.mapNotNull { d ->
            d.chats.filter { it.irreparable }.takeIf { it.isNotEmpty() }?.let { d.copy(chats = it) }
        }
        renderInto(findViewById(R.id.damaged_chat_detections), damaged)
        renderInto(findViewById(R.id.damaged_irreparable_detections), irreparable)
        findViewById<View>(R.id.damaged_irreparable_section)?.visibility =
            if (irreparable.isEmpty()) View.GONE else View.VISIBLE
        findViewById<MaterialButton>(R.id.btn_damaged_try_repair)?.apply {
            visibility = if (detections.isEmpty()) View.GONE else View.VISIBLE
            isEnabled = !repairRunning
            setOnClickListener { runRepair() }
        }
        findViewById<MaterialButton>(R.id.btn_damaged_replace_from_backup)?.apply {
            isEnabled = !repairRunning
            setOnClickListener { startReplacement() }
        }
    }

    private fun renderInto(container: LinearLayout?, detections: List<DamagedChatLog.Detection>) {
        container ?: return
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val unavailable = getString(R.string.damaged_value_unavailable)
        val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
        for (detection in detections) {
            val block = inflater.inflate(R.layout.view_damaged_chat_detection, container, false)
            block.findViewById<TextView>(R.id.damaged_detected_during).text =
                getString(R.string.damaged_detected_during, getString(detection.operation.label))
            block.findViewById<TextView>(R.id.damaged_detected_date).text =
                getString(R.string.damaged_detected_date, dateTime.format(Date(detection.detectedAt)))
            val chats = block.findViewById<LinearLayout>(R.id.damaged_detection_chats)
            for (chat in detection.chats) {
                val entry = inflater.inflate(R.layout.view_damaged_chat_entry, chats, false)
                entry.findViewById<TextView>(R.id.damaged_chat_title).text =
                    getString(R.string.damaged_original_chat_title, chat.title ?: unavailable)
                entry.findViewById<TextView>(R.id.damaged_chat_started).text =
                    getString(R.string.damaged_original_start_date, chat.startedAt?.let { date.format(Date(it)) } ?: unavailable)
                entry.findViewById<TextView>(R.id.damaged_chat_first_username).text =
                    getString(R.string.damaged_first_username, chat.firstUsername ?: unavailable)
                entry.findViewById<TextView>(R.id.damaged_chat_first_companion).text =
                    getString(R.string.damaged_first_companion_name, chat.firstCompanionName ?: unavailable)
                entry.findViewById<TextView>(R.id.damaged_chat_model).text =
                    getString(R.string.damaged_most_used_model, chat.mostUsedModel ?: unavailable)
                chats.addView(entry)
            }
            container.addView(block)
        }
    }

    /** Try Repair runs straight away (owner ruling, Oct 12 2026): each chat's
     *  file is copied aside and verified automatically before any change, so
     *  no backup step is asked of the user. */
    private fun runRepair() {
        if (repairRunning) return
        repairRunning = true
        findViewById<MaterialButton>(R.id.btn_damaged_try_repair)?.isEnabled = false
        val app = applicationContext
        Thread {
            val result = DamagedChatRepair.repairAll(app)
            runOnUiThread {
                repairRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                refresh()
                showRepairResult(result)
            }
        }.start()
    }

    // ---- Attempt Chat Replacement from Backup ----
    // Opens a recovery backup with the existing package checks and unlock
    // choices (the same wording as Restore), finds the damaged chats it holds
    // by chat ID, and after confirmation replaces only those chats.

    private fun startReplacement() {
        if (repairRunning) return
        repairRunning = true
        setButtonsEnabled(false)
        backupPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun inspectBackup(uri: Uri) {
        Thread {
            val root = try { PortableStaging.newRunDir(applicationContext) } catch (_: Exception) { null }
            val packageFile = root?.let { File(it, "recovery-backup.sgbak") }
            val copied = packageFile != null && copyBackup(uri, packageFile)
            val inspected = if (copied) PortablePackage.inspect(packageFile!!) else null
            runOnUiThread {
                replaceRoot = root
                if (isFinishing || isDestroyed) return@runOnUiThread endReplacement()
                if (packageFile == null || inspected !is PortablePackage.InspectResult.Ok) {
                    showBackupFailure(
                        if (inspected is PortablePackage.InspectResult.Invalid) backupErrorMessage(inspected.error)
                        else getString(R.string.backup_err_damaged)
                    )
                    return@runOnUiThread
                }
                replacePackage = packageFile
                replaceInspection = inspected.inspection
                if (inspected.inspection.protection == PortablePackageFormat.PROTECTION_NONE) {
                    readBackup(ByteArray(0), null)
                } else {
                    showUnlockChoices()
                }
            }
        }.start()
    }

    private fun copyBackup(uri: Uri, destination: File): Boolean = try {
        var total = 0L
        contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > PackageCrypto.MAX_PACKAGE_BYTES) return false
                    output.write(buffer, 0, count)
                }
            }
        } ?: return false
        PackageCrypto.withinSizeCap(destination)
    } catch (_: Exception) {
        false
    }

    private fun showUnlockChoices() {
        val inspection = replaceInspection ?: return endReplacement()
        if (isFinishing) return endReplacement()
        val choices = ArrayList<Pair<String, () -> Unit>>()
        choices.add(getString(R.string.portable_unlock_code) to ::showRecoveryCodeEntry)
        choices.add(getString(R.string.portable_unlock_key_file) to {
            keyFilePicker.launch(arrayOf("application/json", "text/*", "*/*"))
        })
        if (inspection.hasPasswordSlot) {
            choices.add(getString(R.string.portable_unlock_password) to ::showPasswordEntry)
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.portable_unlock_title)
            .setMessage(R.string.portable_unlock_message)
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> endReplacement() }
            .setOnCancelListener { endReplacement() }
            .show()
    }

    private fun unlockField(hint: Int, inputType: Int): Pair<View, TextInputEditText> {
        val body = LayoutInflater.from(this).inflate(R.layout.dialog_backup_unlock_field, null)
        val field = body.findViewById<TextInputEditText>(R.id.backup_unlock_field)
        field.setHint(hint)
        field.inputType = inputType
        return body to field
    }

    private fun showRecoveryCodeEntry() {
        val (body, field) = unlockField(
            R.string.restore_recovery_code_hint,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        )
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.restore_recovery_code_title)
            .setView(body)
            .setPositiveButton(R.string.portable_unlock_action) { _, _ ->
                when (val decoded = RecoveryCode.decode(field.text?.toString().orEmpty())) {
                    is RecoveryCode.DecodeResult.Ok -> readBackup(decoded.secret, null)
                    RecoveryCode.DecodeResult.Mistyped ->
                        showUnlockError(getString(R.string.backup_err_mistyped_code))
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> showUnlockChoices() }
            .setOnCancelListener { showUnlockChoices() }
            .show()
    }

    private fun showPasswordEntry() {
        val (body, field) = unlockField(
            R.string.portable_password_hint,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.portable_password_title)
            .setView(body)
            .setPositiveButton(R.string.portable_unlock_action) { _, _ ->
                readBackup(null, field.text?.toString().orEmpty().toCharArray())
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> showUnlockChoices() }
            .setOnCancelListener { showUnlockChoices() }
            .show()
    }

    private fun unlockWithKeyFile(uri: Uri) {
        Thread {
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) { null }
            val parsed = text?.let(RecoveryKeyFile::parse)
            runOnUiThread {
                if (parsed is RecoveryKeyFile.ParseResult.Ok) readBackup(parsed.secret, null)
                else showUnlockError(getString(R.string.portable_key_file_invalid))
            }
        }.start()
    }

    private fun showUnlockError(message: String) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(message)
            .setPositiveButton(R.string.btn_ok) { _, _ -> showUnlockChoices() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> endReplacement() }
            .setOnCancelListener { endReplacement() }
            .show()
    }

    private fun readBackup(secret: ByteArray?, password: CharArray?) {
        val packageFile = replacePackage
        val root = replaceRoot
        if (packageFile == null || root == null) {
            secret?.fill(0)
            password?.fill('\u0000')
            return endReplacement()
        }
        val app = applicationContext
        Thread {
            val read = try {
                DamagedChatBackupReader.read(packageFile, File(root, "decoded").apply { mkdirs() }, secret, password)
            } finally {
                secret?.fill(0)
                password?.fill('\u0000')
            }
            val matches = (read as? DamagedChatBackupReader.Read.Ok)?.let { DamagedChatBackupReader.match(app, it.chats) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread endReplacement()
                when (read) {
                    is DamagedChatBackupReader.Read.Failed -> {
                        val protected = replaceInspection?.protection != PortablePackageFormat.PROTECTION_NONE
                        if (protected && read.error == PortablePackageFormat.RestoreError.WRONG_KEY_OR_HEADER) {
                            showUnlockError(backupErrorMessage(read.error))
                        } else {
                            showBackupFailure(backupErrorMessage(read.error))
                        }
                    }
                    is DamagedChatBackupReader.Read.Ok -> confirmReplacement(read.chats, matches!!)
                }
            }
        }.start()
    }

    /** The approved confirmation: which damaged chats the backup holds, which
     *  it does not, and a warning where the backup copy is older. */
    private fun confirmReplacement(
        backup: Map<String, DamagedChatBackupReader.BackupChat>,
        matches: DamagedChatBackupReader.Matches
    ) {
        if (matches.found.isEmpty()) {
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setMessage(R.string.damaged_replace_none_found)
                .setPositiveButton(R.string.btn_ok, null)
                .setOnDismissListener { endReplacement() }
                .show()
            return
        }
        val unavailable = getString(R.string.damaged_value_unavailable)
        val message = buildString {
            append(getString(R.string.damaged_replace_found))
            for (match in matches.found) {
                append('\n')
                val title = match.title ?: unavailable
                append(
                    if (match.backupOlder) getString(R.string.damaged_replace_item_older, title)
                    else getString(R.string.damaged_replace_item, title)
                )
            }
            if (matches.notFoundTitles.isNotEmpty()) {
                append("\n\n").append(getString(R.string.damaged_replace_not_found))
                for (title in matches.notFoundTitles) {
                    append('\n').append(getString(R.string.damaged_replace_item, title ?: unavailable))
                }
            }
            append("\n\n").append(getString(R.string.damaged_replace_explanation))
        }
        val actions = LayoutInflater.from(this).inflate(R.layout.dialog_two_actions_cancel_first, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
        val replace = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
        cancel.setText(R.string.btn_cancel)
        replace.setText(R.string.damaged_replace_action)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.damaged_replace_title)
            .setMessage(message)
            .setView(actions)
            .setCancelable(false)
            .create()
        cancel.setOnClickListener {
            dialog.dismiss()
            endReplacement()
        }
        replace.setOnClickListener {
            dialog.dismiss()
            val replacements = matches.found.mapNotNull { m -> backup[m.chatId]?.let { m.chatId to it.messages } }.toMap()
            val app = applicationContext
            Thread {
                val result = DamagedChatRepair.replaceFromBackup(app, replacements)
                runOnUiThread {
                    endReplacement()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    showRepairResult(result)
                }
            }.start()
        }
        dialog.show()
    }

    private fun backupErrorMessage(error: PortablePackageFormat.RestoreError): String = getString(when (error) {
        PortablePackageFormat.RestoreError.MISTYPED_CODE -> R.string.backup_err_mistyped_code
        PortablePackageFormat.RestoreError.WRONG_KEY_OR_HEADER -> R.string.backup_err_wrong_key_or_header
        PortablePackageFormat.RestoreError.UNSUPPORTED_PROTECTION -> R.string.backup_err_unsupported_protection
        PortablePackageFormat.RestoreError.TOO_LARGE -> R.string.portable_restore_too_large
        PortablePackageFormat.RestoreError.NOT_A_V2_PACKAGE -> R.string.backup_err_not_recovery_backup
        PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED -> R.string.backup_err_damaged
    })

    private fun showBackupFailure(message: String) {
        endReplacement()
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.portable_failure_title)
            .setMessage(getString(R.string.portable_failure_message) + "\n\n" + message)
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }

    /** Ends the replacement flow: deletes the staged backup and refreshes. */
    private fun endReplacement() {
        val root = replaceRoot
        replaceRoot = null
        replacePackage = null
        replaceInspection = null
        if (root != null) Thread { PortableStaging.delete(root) }.start()
        repairRunning = false
        setButtonsEnabled(true)
        refresh()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        findViewById<MaterialButton>(R.id.btn_damaged_try_repair)?.isEnabled = enabled
        findViewById<MaterialButton>(R.id.btn_damaged_replace_from_backup)?.isEnabled = enabled
    }

    /** The approved result pop-ups. */
    private fun showRepairResult(result: DamagedChatRepair.Result) {
        if (result.repaired.isNotEmpty() && result.remaining.isEmpty()) {
            val actions = LayoutInflater.from(this).inflate(R.layout.dialog_two_actions_cancel_first, null)
            val notNow = actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action)
            val retry = actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action)
            notNow.setText(R.string.damaged_not_now)
            retry.setText(R.string.damaged_retry_renaming)
            val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.damaged_repair_fully_successful)
                .setMessage(R.string.damaged_retry_renaming_question)
                .setView(actions)
                .create()
            notNow.setOnClickListener { dialog.dismiss() }
            retry.setOnClickListener {
                dialog.dismiss()
                // Only messages still without a name are filled, so this
                // finishes just the renaming that was left undone.
                if (DefaultUsernameFill.activeName(this) != null) DefaultUsernameFill.schedule(this)
            }
            dialog.show()
            return
        }
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(
                if (result.repaired.isNotEmpty()) R.string.damaged_repair_partly_successful
                else R.string.damaged_repair_unsuccessful
            )
            .setMessage(R.string.damaged_repair_from_backup_hint)
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val insets = window.decorView.rootWindowInsets
            actionBar?.setPadding(0, insets.getInsets(WindowInsets.Type.statusBars()).top, 0, 0)
            val density = resources.displayMetrics.density
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0,
                0,
                0,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom + (24 * density).toInt()
            )
        } catch (_: Exception) { /* Window insets are not available yet. */ }
    }
}
