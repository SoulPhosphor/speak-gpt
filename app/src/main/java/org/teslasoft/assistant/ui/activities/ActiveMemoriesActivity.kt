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

    private data class DisplayEntry(val label: String?, val content: String?)

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
        val memoryTypes = try {
            MemoryStore.getInstance(this).getMemoryTypes().associate { it.typeId to it.name }
        } catch (_: Exception) {
            emptyMap()
        }
        for (reference in references) {
            result[reference] = when (reference.source) {
                ActiveMemoryReference.SOURCE_MEMORY -> try {
                    MemoryStore.getInstance(this).getMemory(reference.id)?.let { memory ->
                        DisplayEntry(
                            memory.typeId?.let(memoryTypes::get)
                                ?: getString(R.string.mem_type_none),
                            memory.content
                        )
                    } ?: DisplayEntry(null, null)
                } catch (_: Exception) {
                    DisplayEntry(null, null)
                }
                ActiveMemoryReference.SOURCE_LOREBOOK -> try {
                    LoreBookStore.getInstance(this).getEntry(reference.id)?.let { entry ->
                        DisplayEntry(entry.label, entry.content)
                    } ?: DisplayEntry(null, null)
                } catch (_: Exception) {
                    DisplayEntry(null, null)
                }
                else -> DisplayEntry(null, null)
            }
        }
        return result
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
                text = entryText(resolved[reference])
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = if (index < references.lastIndex) dp(16) else 0
            })
        }
    }

    private fun entryText(entry: DisplayEntry?): CharSequence {
        val label = entry?.label?.takeIf { it.isNotBlank() }
        val body = entry?.content
        if (label == null || body == null) {
            val unavailable = getString(R.string.active_memories_not_available)
            return SpannableString(unavailable).apply {
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    unavailable.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        val prefix = "$label:"
        return SpannableString("$prefix $body").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

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
