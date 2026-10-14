package org.teslasoft.assistant.ui.util

import android.text.SpannableString
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.widget.TextView
import org.teslasoft.assistant.R

/** Applies the theme-owned failure appearance without changing the rest of the row. */
object BackupStatusText {
    fun set(view: TextView?, text: String) {
        if (view == null) return
        val formatted = SpannableString(text)
        Regex("\\bfailed\\b", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            formatted.setSpan(TextAppearanceSpan(view.context, R.style.TextAppearance_App_BackupStatus_Failed),
                match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Each update creates new text; a later pass carries no leftover failure span.
        view.text = formatted
    }
}
