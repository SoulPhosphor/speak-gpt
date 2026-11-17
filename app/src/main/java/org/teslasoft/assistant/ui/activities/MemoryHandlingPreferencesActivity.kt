/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.os.Bundle
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryHandlingPreferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.ui.util.ScreenChrome
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Memory Handling Preferences (memory plan Phase 2, D.12), opened from the
 * Memory Manager's Settings section. It holds Delete Moves to Archived (D44,
 * on by default); later phases add their preferences here.
 *
 * The preference lives in the memory database, so it travels with every
 * memory backup. Reads and writes run off the main thread. The switch stays
 * disabled until the stored value is shown, so a tap can never act on a
 * value the user has not seen. Without a memory store there is nothing to
 * save to: the default is shown and a change is refused with the existing
 * not-set-up notice, never silently dropped.
 */
class MemoryHandlingPreferencesActivity : SettingsPageActivity() {

    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var deleteMovesSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_handling_preferences)
        ScreenChrome.apply(this, findViewById(R.id.action_bar), findViewById(R.id.btn_back))
        ScreenChrome.fitSystemBars(findViewById(R.id.root), findViewById(R.id.action_bar), findViewById(R.id.scroll))
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }
        deleteMovesSwitch = findViewById(R.id.switch_delete_moves_to_archived)
        loadPreferences()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun loadPreferences() {
        io.execute {
            val loaded = runCatching {
                if (MemoryStore.isProvisioned(this)) {
                    MemoryStore.getInstance(this).getMemoryHandlingPreferences()
                } else MemoryHandlingPreferences.DEFAULT
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loaded.onSuccess { show(it) }.onFailure { showFailure(it) }
            }
        }
    }

    private fun show(preferences: MemoryHandlingPreferences) {
        deleteMovesSwitch.setOnCheckedChangeListener(null)
        deleteMovesSwitch.isChecked = preferences.deleteMovesToArchived
        deleteMovesSwitch.isEnabled = true
        deleteMovesSwitch.setOnCheckedChangeListener { _, checked -> save(checked) }
    }

    private fun save(deleteMovesToArchived: Boolean) {
        deleteMovesSwitch.isEnabled = false
        io.execute {
            val saved = runCatching {
                check(MemoryStore.isProvisioned(this)) { NOT_PROVISIONED }
                val store = MemoryStore.getInstance(this)
                store.setMemoryHandlingPreferences(
                    store.getMemoryHandlingPreferences().copy(deleteMovesToArchived = deleteMovesToArchived)
                )
                store.getMemoryHandlingPreferences()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                saved.onSuccess { show(it) }.onFailure {
                    showFailure(it)
                    // Show what is actually stored, not the refused change.
                    loadPreferences()
                }
            }
        }
    }

    private fun showFailure(error: Throwable) {
        val message = if (error.message == NOT_PROVISIONED) {
            getString(R.string.memory_not_provisioned_toast)
        } else {
            getString(R.string.memory_operation_failed, error.message ?: error.javaClass.simpleName)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val NOT_PROVISIONED = "memory store not provisioned"
    }
}
