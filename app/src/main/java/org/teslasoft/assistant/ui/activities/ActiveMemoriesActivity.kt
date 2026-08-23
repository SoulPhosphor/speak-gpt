/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.activities

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.elevation.SurfaceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.ActiveMemoryAttribution
import org.teslasoft.assistant.preferences.memory.ActiveMemoryReference
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.theme.ThemeManager

/** Read-only, per-response viewer for the exact memory context sent to the AI. */
class ActiveMemoriesActivity : FragmentActivity() {

    companion object {
        const val EXTRA_ATTRIBUTION = "active_memory_attribution"
    }

    private lateinit var actionBar: ConstraintLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private sealed interface DisplayEntry {
        data class Available(val label: String, val content: String) : DisplayEntry
        data object Deleted : DisplayEntry
        data object ReadError : DisplayEntry
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_active_memories)

        actionBar = findViewById(R.id.action_bar)
        scroll = findViewById(R.id.scroll)
        content = findViewById(R.id.active_memories_content)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        applyAppearanceScreenTheme()

        val references = ActiveMemoryAttribution.decode(
            intent.getStringExtra(EXTRA_ATTRIBUTION)
        )
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) { resolve(references) }
            render(references, resolved)
        }
    }

    private fun applyAppearanceScreenTheme() {
        window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
        if (Build.VERSION.SDK_INT <= 34) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = SurfaceColors.SURFACE_0.getColor(this)
            @Suppress("DEPRECATION")
            window.statusBarColor = SurfaceColors.SURFACE_4.getColor(this)
        }
        actionBar.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
        findViewById<ImageButton>(R.id.btn_back).backgroundTintList =
            ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
    }

    private fun resolve(
        references: List<ActiveMemoryReference>
    ): Map<ActiveMemoryReference, DisplayEntry> {
        val result = LinkedHashMap<ActiveMemoryReference, DisplayEntry>()
        val memoryStore = runCatching { MemoryStore.getInstance(this) }
        val memoryTypes = memoryStore.mapCatching { store ->
            store.getMemoryTypes().associate { it.typeId to it.name }
        }
        val lorebookStore = runCatching { LoreBookStore.getInstance(this) }
        for (reference in references) {
            result[reference] = when (reference.source) {
                ActiveMemoryReference.SOURCE_MEMORY ->
                    resolveMemory(reference, memoryStore, memoryTypes)
                ActiveMemoryReference.SOURCE_LOREBOOK ->
                    resolveLorebook(reference, lorebookStore)
                else -> DisplayEntry.ReadError
            }
        }
        return result
    }

    private fun resolveMemory(
        reference: ActiveMemoryReference,
        storeResult: Result<MemoryStore>,
        typeResult: Result<Map<String, String>>
    ): DisplayEntry {
        val store = storeResult.getOrElse { error ->
            logReadError("Memory", reference.id, error)
            return DisplayEntry.ReadError
        }
        return try {
            val memory = store.getMemory(reference.id) ?: return DisplayEntry.Deleted
            val label = if (memory.typeId == null) {
                getString(R.string.mem_type_none)
            } else {
                val types = typeResult.getOrElse { error ->
                    logReadError("Memory Type", reference.id, error)
                    return DisplayEntry.ReadError
                }
                types[memory.typeId].orEmpty().ifBlank { getString(R.string.mem_type_none) }
            }
            DisplayEntry.Available(label, memory.content)
        } catch (error: Exception) {
            logReadError("Memory", reference.id, error)
            DisplayEntry.ReadError
        }
    }

    private fun resolveLorebook(
        reference: ActiveMemoryReference,
        storeResult: Result<LoreBookStore>
    ): DisplayEntry {
        val store = storeResult.getOrElse { error ->
            logReadError("Lorebook Entry", reference.id, error)
            return DisplayEntry.ReadError
        }
        return try {
            val entry = store.getEntry(reference.id) ?: return DisplayEntry.Deleted
            DisplayEntry.Available(
                entry.label.ifBlank { getString(R.string.active_memories_unnamed_lorebook_entry) },
                entry.content
            )
        } catch (error: Exception) {
            logReadError("Lorebook Entry", reference.id, error)
            DisplayEntry.ReadError
        }
    }

    private fun logReadError(kind: String, id: String, error: Throwable) {
        MemoryLog.log(
            this,
            "ActiveMemories",
            "error",
            "$kind $id could not be read: ${error.message ?: error.javaClass.simpleName}"
        )
    }

    private fun render(
        references: List<ActiveMemoryReference>,
        resolved: Map<ActiveMemoryReference, DisplayEntry>
    ) {
        content.removeAllViews()
        val memories = references.filter { it.source == ActiveMemoryReference.SOURCE_MEMORY }
        val lorebook = references.filter { it.source == ActiveMemoryReference.SOURCE_LOREBOOK }
        if (memories.isNotEmpty()) addSection(R.string.active_memories_section_memories, memories, resolved)
        if (lorebook.isNotEmpty()) addSection(R.string.active_memories_section_lorebook, lorebook, resolved)
    }

    private fun addSection(
        titleRes: Int,
        references: List<ActiveMemoryReference>,
        resolved: Map<ActiveMemoryReference, DisplayEntry>
    ) {
        content.addView(TextView(this, null, 0, R.style.Widget_App_Section_Title).apply {
            setText(titleRes)
        })
        references.forEachIndexed { index, reference ->
            content.addView(TextView(this).apply {
                textSize = 16f
                setTextColor(getColorFromAttr(com.google.android.material.R.attr.colorOnSurface))
                text = entryText(reference, resolved[reference])
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = if (index < references.lastIndex) dp(16) else 0
            })
        }
    }

    private fun entryText(
        reference: ActiveMemoryReference,
        entry: DisplayEntry?
    ): CharSequence = when (entry) {
        is DisplayEntry.Available -> labeledText(entry.label, entry.content)
        DisplayEntry.Deleted -> if (reference.source == ActiveMemoryReference.SOURCE_MEMORY) {
            statusText(
                R.string.active_memories_memory_deleted,
                R.string.active_memories_memory_deleted_detail
            )
        } else {
            statusText(
                R.string.active_memories_lorebook_entry_deleted,
                R.string.active_memories_lorebook_entry_deleted_detail
            )
        }
        DisplayEntry.ReadError, null ->
            if (reference.source == ActiveMemoryReference.SOURCE_MEMORY) {
                statusText(
                    R.string.active_memories_memory_read_error,
                    R.string.active_memories_memory_read_error_detail
                )
            } else {
                statusText(
                    R.string.active_memories_lorebook_read_error,
                    R.string.active_memories_lorebook_read_error_detail
                )
            }
    }

    private fun labeledText(label: String, body: String): CharSequence {
        val prefix = "$label:"
        return SpannableString("$prefix $body").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun statusText(titleRes: Int, detailRes: Int): CharSequence =
        labeledText(getString(titleRes), getString(detailRes))

    private fun getColorFromAttr(attr: Int): Int {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(attr, typed, true)
        return typed.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val insets = window.decorView.rootWindowInsets
            actionBar.setPadding(0, insets.getInsets(WindowInsets.Type.statusBars()).top, 0, 0)
            scroll.setPadding(
                0,
                0,
                0,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom + dp(24)
            )
        } catch (_: Exception) { /* Window insets are not available yet. */ }
    }
}
