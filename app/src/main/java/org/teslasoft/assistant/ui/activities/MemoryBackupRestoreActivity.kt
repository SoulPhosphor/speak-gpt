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

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import org.teslasoft.assistant.preferences.memory.MemoryExporter
import org.teslasoft.assistant.preferences.memory.MemorySeedCodec
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager

/**
 * "Memory Backup & Restore" (owner request, July 18 2026): the Backups section
 * and the destructive Reset section, moved out of the Memory Controls screen
 * into their own row on the Memory Manager hub. Nothing about how they work
 * changed — the auto-backup toggle, import/export, last-backup status, and the
 * backup-gated Reset are the exact same code that lived in
 * `MemoryControlsActivity`, only relocated.
 */
class MemoryBackupRestoreActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var chatId = ""

    private var actionBar: ConstraintLayout? = null
    private var btnBack: ImageButton? = null

    private var switchAutoBackup: MaterialSwitch? = null
    private var btnImport: MaterialButton? = null
    private var btnExport: MaterialButton? = null
    private var textLastBackup: TextView? = null

    private var btnReset: MaterialButton? = null

    private val importSeedLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSeedFromUri(uri)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportToUri(uri)
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
        refreshBackupStatus()
    }

    private fun bindViews() {
        actionBar = findViewById(R.id.action_bar)
        btnBack = findViewById(R.id.btn_back)
        switchAutoBackup = findViewById(R.id.switch_auto_backup)
        btnImport = findViewById(R.id.btn_memory_import)
        btnExport = findViewById(R.id.btn_memory_export)
        textLastBackup = findViewById(R.id.text_last_backup)
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

        /* ---- Backups ---- */
        switchAutoBackup?.setOnCheckedChangeListener { _, checked ->
            if (!MemoryStore.isProvisioned(this)) return@setOnCheckedChangeListener
            runOffThread {
                MemoryStore.getInstance(this).setMeta(
                    MemoryStore.META_AUTO_EXPORT_ENABLED, if (checked) "1" else "0"
                )
            }
        }
        btnImport?.setOnClickListener {
            importSeedLauncher.launch(arrayOf("application/json", "text/*"))
        }
        btnExport?.setOnClickListener {
            if (!MemoryStore.isProvisioned(this)) {
                Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val stamp = MemoryStore.nowIso().substring(0, 10)
            exportLauncher.launch("memory-export-$stamp.json")
        }
        refreshBackupStatus()

        /* ---- Reset (bottom) ---- */
        btnReset?.setOnClickListener { showResetDialog() }
    }

    /* ------------------------------ backups ------------------------------ */

    private fun refreshBackupStatus() {
        runOffThread {
            var autoBackupOn = true
            var provisioned = false
            val lastBackup: String?
            if (!MemoryStore.isProvisioned(this)) {
                lastBackup = null
            } else {
                provisioned = true
                val store = MemoryStore.getInstance(this)
                autoBackupOn = store.getMeta(MemoryStore.META_AUTO_EXPORT_ENABLED) != "0"
                lastBackup = store.getMeta(MemoryStore.META_LAST_AUTO_EXPORT_AT)
            }
            runOnUiThread {
                switchAutoBackup?.isEnabled = provisioned
                switchAutoBackup?.isChecked = autoBackupOn
                textLastBackup?.text = getString(
                    R.string.memory_controls_last_backup,
                    lastBackup ?: getString(R.string.memory_controls_never)
                )
            }
        }
    }

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
            // A manual export counts as a backup, so "Chats since last back-up"
            // resets after it too (not just the automatic daily one).
            MemoryStore.getInstance(this).setMeta(MemoryStore.META_LAST_AUTO_EXPORT_AT, MemoryStore.nowIso())
            runOnUiThread {
                Toast.makeText(this, R.string.memory_export_done, Toast.LENGTH_SHORT).show()
                refreshBackupStatus()
            }
        }
    }

    /* ------------------------------ reset (destructive) ------------------------------ */

    private fun showResetDialog() {
        if (!MemoryStore.isProvisioned(this)) {
            Toast.makeText(this, R.string.memory_not_provisioned_toast, Toast.LENGTH_SHORT).show()
            return
        }
        // The user decides whether a backup is written first (starts checked); if
        // they decline, trust them (owner_approved_rules approved UI decisions).
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
                // The safety copy the user asked for cannot be complete while
                // chat storage is locked — abort the reset BEFORE anything is
                // deleted and say why with the owner-approved wording.
                runOnUiThread { showChatBackupUnavailableDialog() }
                return@runOffThread
            }
            if (backupFirst) {
                // The user asked for a safety copy, so the reset must not run
                // unless that copy is verifiably on disk. writeBackupNow
                // returns null on ANY failure (it writes atomically and reads
                // the file back), and a null aborts BEFORE anything is
                // deleted — it used to only change the confirmation wording
                // while the reset destroyed the store anyway.
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
                // Outcome as a dialog, not a toast (owner rule: persistent
                // messages); the wording itself is unchanged.
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
