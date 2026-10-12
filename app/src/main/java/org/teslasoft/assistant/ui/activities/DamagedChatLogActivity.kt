/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.backup.BackupType
import org.teslasoft.assistant.preferences.backup.RecoveryBackupState
import org.teslasoft.assistant.preferences.damaged.DamagedChatLog
import org.teslasoft.assistant.preferences.damaged.DamagedChatRepair
import org.teslasoft.assistant.preferences.username.DefaultUsernameFill
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome
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

    // Set when Back Up opened the Recovery Backup screen: on return, a backup
    // finished after this time lets the repair go ahead; otherwise the
    // backup choice is offered again.
    private var backupStartedAt = 0L
    private var repairRunning = false

    override fun onResume() {
        super.onResume()
        refresh()
        if (backupStartedAt > 0L) {
            val since = backupStartedAt
            backupStartedAt = 0L
            if (RecoveryBackupState.getLastSuccess(this, BackupType.CHATS) >= since) runRepair()
            else showBackupChoice()
        }
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
            setOnClickListener { showBackupChoice() }
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

    /** Pre-repair choice: Cancel, Proceed Without Backup, Back Up. Back Up
     *  opens the existing Recovery Backup screen, which explains when a
     *  damaged chat prevents a complete backup. */
    private fun showBackupChoice() {
        if (repairRunning || isFinishing) return
        val actions = LayoutInflater.from(this).inflate(R.layout.dialog_three_actions_cancel_first, null)
        val cancel = actions.findViewById<MaterialButton>(R.id.btn_dialog_cancel_action)
        val proceed = actions.findViewById<MaterialButton>(R.id.btn_dialog_middle_action)
        val backUp = actions.findViewById<MaterialButton>(R.id.btn_dialog_final_action)
        cancel.setText(R.string.btn_cancel)
        proceed.setText(R.string.damaged_proceed_without_backup)
        backUp.setText(R.string.damaged_back_up)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(R.string.damaged_backup_before_repair)
            .setView(actions)
            .create()
        cancel.setOnClickListener { dialog.dismiss() }
        proceed.setOnClickListener {
            dialog.dismiss()
            runRepair()
        }
        backUp.setOnClickListener {
            dialog.dismiss()
            backupStartedAt = System.currentTimeMillis()
            startActivity(
                Intent(this, RecoveryBackupActivity::class.java)
                    .putExtra(
                        RecoveryBackupActivity.EXTRA_RECOVERY_PROTECTED,
                        RecoveryBackupState.getLastRecoveryProtected(this)
                    )
            )
        }
        dialog.show()
    }

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
