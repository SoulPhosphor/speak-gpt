/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.os.Handler
import android.os.Looper
import android.transition.ChangeBounds
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import org.teslasoft.assistant.R

/**
 * The log viewer's search: the header's search icon slides [panel] down from
 * behind the header (and back up), and typing in [field] narrows the shown
 * entries to those containing the text, case-insensitive, after a short
 * pause. Closing the panel clears the search and shows every entry again.
 * Searches only the log on screen. [onQueryChanged] re-renders the log.
 */
class LogSearchBar(
    private val root: ViewGroup,
    private val panel: View,
    private val field: EditText,
    private val onQueryChanged: () -> Unit
) {
    /** The active search text; blank when the panel is closed. */
    var query: String = ""
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val apply = Runnable {
        val next = field.text?.toString().orEmpty().trim()
        if (next != query) {
            query = next
            onQueryChanged()
        }
    }

    init {
        field.doAfterTextChanged {
            handler.removeCallbacks(apply)
            handler.postDelayed(apply, DEBOUNCE_MS)
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacks(apply)
                apply.run()
                hideKeyboard()
                true
            } else false
        }
    }

    val isOpen: Boolean get() = panel.visibility == View.VISIBLE

    fun toggle() = if (isOpen) close() else open()

    fun open() {
        animate()
        panel.visibility = View.VISIBLE
        field.requestFocus()
        field.post {
            (field.context.getSystemService(InputMethodManager::class.java))
                ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun close() {
        hideKeyboard()
        handler.removeCallbacks(apply)
        animate()
        panel.visibility = View.GONE
        field.setText("")
        handler.removeCallbacks(apply)
        if (query.isNotEmpty()) {
            query = ""
            onQueryChanged()
        }
    }

    private fun animate() {
        val duration = root.resources.getInteger(R.integer.header_search_panel_slide_duration).toLong()
        TransitionManager.beginDelayedTransition(
            root,
            TransitionSet()
                .addTransition(Slide(Gravity.TOP).addTarget(panel))
                .addTransition(ChangeBounds())
                .setDuration(duration)
        )
    }

    private fun hideKeyboard() {
        field.context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    companion object {
        private const val DEBOUNCE_MS = 250L
    }
}

/** The log search's matching rule, kept free of Android types for testing. */
object LogSearch {
    /** Indices of [entries] containing [query] (case-insensitive); every
     *  index when [query] is blank. */
    fun matchingIndices(entries: List<String>, query: String): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return entries.indices.toList()
        return entries.indices.filter { entries[it].contains(q, ignoreCase = true) }
    }
}
