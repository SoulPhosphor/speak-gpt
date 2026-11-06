package org.teslasoft.assistant.ui.util

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.materialswitch.MaterialSwitch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatsearch.SearchOptions
import org.teslasoft.assistant.preferences.chatsearch.SearchTextPolicy

/** Collapsible in-place search for prompt libraries. No prompt or selection is mutated. */
class PromptLibrarySearch(
    private val activity: FragmentActivity,
    savedState: Bundle?,
    private val onChanged: () -> Unit
) {
    private val controls = activity.findViewById<View>(R.id.search_controls)
    private val searchField = activity.findViewById<EditText>(R.id.field_search)
    private val wholeWords = activity.findViewById<MaterialSwitch>(R.id.check_whole_words)
    private val toggle = activity.findViewById<ImageButton>(R.id.btn_search_toggle)
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setOpen(false)
    }

    val query: String get() = searchField.text.toString()
    val options: SearchOptions get() = SearchOptions(wholeWords = wholeWords.isChecked)

    init {
        // Own restoration as a unit: a restored filter must always remain visible.
        searchField.isSaveEnabled = false
        wholeWords.isSaveEnabled = false
        searchField.setText(savedState?.getString(STATE_QUERY).orEmpty())
        wholeWords.isChecked = savedState?.getBoolean(STATE_WHOLE) ?: false
        setOpen(savedState?.getBoolean(STATE_OPEN) == true, focus = false)
        activity.onBackPressedDispatcher.addCallback(activity, back)
        toggle.setOnClickListener { setOpen(controls.visibility != View.VISIBLE) }
        searchField.doAfterTextChanged { onChanged() }
        wholeWords.setOnCheckedChangeListener { _, _ -> onChanged() }
        activity.findViewById<View>(R.id.btn_search).setOnClickListener {
            activity.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(searchField.windowToken, 0)
            onChanged()
        }
        searchField.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                activity.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(searchField.windowToken, 0)
                onChanged()
                true
            } else false
        }
    }

    fun matches(vararg fields: String): Boolean = matches(query, options, *fields)

    fun saveState(outState: Bundle) {
        outState.putBoolean(STATE_OPEN, controls.visibility == View.VISIBLE)
        outState.putString(STATE_QUERY, query)
        outState.putBoolean(STATE_WHOLE, wholeWords.isChecked)
    }

    private fun setOpen(open: Boolean, focus: Boolean = true) {
        controls.visibility = if (open) View.VISIBLE else View.GONE
        back.isEnabled = open
        toggle.isSelected = open
        if (open && focus) {
            searchField.requestFocus()
            searchField.post {
                if (controls.visibility == View.VISIBLE) {
                    activity.getSystemService<InputMethodManager>()?.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        } else if (!open) {
            searchField.text.clear()
            searchField.clearFocus()
            activity.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(searchField.windowToken, 0)
        }
    }

    companion object {
        private const val STATE_OPEN = "promptSearch.open"
        private const val STATE_QUERY = "promptSearch.query"
        private const val STATE_WHOLE = "promptSearch.wholeWords"

        /** Use the same Unicode token and partial/whole-word rules as app search. */
        fun matches(query: String, options: SearchOptions, vararg fields: String): Boolean =
            query.isBlank() || SearchTextPolicy.match(query, fields.joinToString("\n"), options) != null
    }
}
