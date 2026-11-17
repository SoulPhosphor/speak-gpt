package org.teslasoft.assistant.preferences.memory

/** Existing storage rule: editing source text invalidates the derived search hint. */
object MemorySourceText {
    fun changed(prior: MemoryRecord?, content: String, tagsJson: String): Boolean =
        prior == null || prior.content != content || prior.tagsJson != tagsJson
}
