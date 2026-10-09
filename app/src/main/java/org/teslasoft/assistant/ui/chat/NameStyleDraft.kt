package org.teslasoft.assistant.ui.chat

/** Pure draft state; restoring preserves missing overrides as true inheritance. */
internal data class NameStyleDraft(
    var saved: ChatNameStyle.Override,
    var pending: ChatNameStyle.Override = saved
) {
    val dirty: Boolean get() = pending != saved
    fun restoreOriginal() { pending = saved }
    fun markSaved(value: ChatNameStyle.Override) {
        saved = value
        pending = value
    }
}
