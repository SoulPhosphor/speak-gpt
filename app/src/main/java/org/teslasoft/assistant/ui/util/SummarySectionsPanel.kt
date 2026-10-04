/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.ui.util

import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.util.summarizer.SummarySectionTime
import org.teslasoft.assistant.util.summarizer.SummarySections

/**
 * The Summarizer's sections on the Conversation Summary screen, in
 * chronological order (owner-approved design, Oct 4 2026). Each shows its
 * protected generated date/time header (tapping it opens the conversation
 * preview) and its own editable text with a Revert that appears only while
 * that text differs from its last save. Edits are tracked per section by the
 * section's stable ID, never by its position on screen.
 */
class SummarySectionsPanel(
    private val activity: Activity,
    private val container: LinearLayout,
    private val onEdited: () -> Unit,
    private val onHeaderTapped: (sectionId: String) -> Unit
) {
    private class Row(
        val sectionId: String,
        val root: View,
        val flag: View,
        val field: TextInputEditText,
        val revert: View,
        var savedText: String
    )

    private val rows = ArrayList<Row>()
    private var locked = false

    /** Shows [sections]; [drafts] (by section ID) restore unsaved edits. */
    fun bind(
        sections: List<SummarySections.Section>,
        messages: List<SummarySections.SourceMessage>,
        drafts: Map<String, String> = emptyMap()
    ) {
        container.removeAllViews()
        rows.clear()
        for (section in sections) {
            val view = activity.layoutInflater.inflate(R.layout.view_summary_section, container, false)
            val header = view.findViewById<TextView>(R.id.summary_section_header)
            val field = view.findViewById<TextInputEditText>(R.id.summary_section_text)
            val row = Row(
                section.id,
                view,
                view.findViewById(R.id.summary_section_flag),
                field,
                view.findViewById(R.id.summary_section_revert),
                section.text
            )
            val label = SummarySections.timeSpan(section, messages)
                ?.let { (start, end) -> SummarySectionTime.format(start, end) }
                ?: activity.getString(R.string.summary_section_time_unknown)
            header.text = label
            header.contentDescription = activity.getString(R.string.summary_section_header_desc, label)
            header.setOnClickListener { onHeaderTapped(section.id) }
            field.setText(drafts[section.id] ?: section.text)
            field.addTextChangedListener {
                refreshRevert(row)
                onEdited()
            }
            row.revert.setOnClickListener { field.setText(row.savedText) }
            rows.add(row)
            container.addView(view)
            refreshRevert(row)
        }
        applyLock()
    }

    fun isDirty(): Boolean = rows.any { it.field.text?.toString().orEmpty() != it.savedText }

    /** Unsaved texts by section ID. */
    fun changedTexts(): Map<String, String> = rows
        .filter { it.field.text?.toString().orEmpty() != it.savedText }
        .associate { it.sectionId to it.field.text?.toString().orEmpty() }

    fun drafts(): Map<String, String> = rows.associate { it.sectionId to it.field.text?.toString().orEmpty() }

    fun markSaved() {
        rows.forEach {
            it.savedText = it.field.text?.toString().orEmpty()
            refreshRevert(it)
        }
    }

    fun setLocked(locked: Boolean) {
        this.locked = locked
        applyLock()
    }

    /** Scrolls to [sectionId], puts the bookmark flag beside its header (and
     *  only there), and briefly highlights it. */
    fun target(sectionId: String, scroll: ScrollView?) {
        val row = rows.firstOrNull { it.sectionId == sectionId } ?: return
        rows.forEach { it.flag.visibility = if (it === row) View.VISIBLE else View.GONE }
        container.post {
            val top = container.top + row.root.top
            scroll?.smoothScrollTo(0, (top - scroll.height / 4).coerceAtLeast(0))
            highlight(row.root)
        }
    }

    private fun highlight(view: View) {
        val drawable = ContextCompat.getDrawable(activity, R.drawable.bg_summary_section_highlight)?.mutate()
            ?: return
        view.background = drawable
        ValueAnimator.ofInt(255, 0).apply {
            startDelay = 900L
            duration = 1200L
            addUpdateListener { drawable.alpha = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.background = null
                }
            })
            start()
        }
    }

    private fun applyLock() {
        rows.forEach {
            it.field.isFocusable = !locked
            it.field.isFocusableInTouchMode = !locked
            if (locked) it.field.clearFocus()
            refreshRevert(it)
        }
    }

    private fun refreshRevert(row: Row) {
        val dirty = row.field.text?.toString().orEmpty() != row.savedText
        row.revert.visibility = if (dirty && !locked) View.VISIBLE else View.GONE
    }
}
