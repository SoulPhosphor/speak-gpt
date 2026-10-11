package org.teslasoft.assistant.ui.chat

/** Transient navigation only; closing it forgets the search and selection. */
class BookmarkNavigation {
    companion object {
        /** Mirrors the response pager without changing the canonical conversation. */
        fun displayedText(message: Map<String, Any>, variants: List<Map<String, String>>): String {
            val canonicalText = message["message"]?.toString().orEmpty()
            if (variants.size < 2) return canonicalText
            val canonical = (message["canonicalVariant"]?.toString()?.toIntOrNull()
                ?: variants.lastIndex).coerceIn(variants.indices)
            val display = (message["displayVariant"]?.toString()?.toIntOrNull()
                ?: canonical).coerceIn(variants.indices)
            return if (display == canonical) canonicalText else variants[display]["message"].orEmpty()
        }
    }

    data class Target(val id: String, val position: Int, val text: String)

    private var targets: List<Target> = emptyList()
    var query: String? = null
        private set
    var isOpen: Boolean = false
        private set
    private var selectedId: String? = null

    val matches: List<Target>
        get() = query?.let { term -> targets.filter { it.text.contains(term, ignoreCase = true) } }
            ?: targets
    val current: Target? get() = matches.firstOrNull { it.id == selectedId }
    val count: Int get() = matches.size
    val ordinal: Int get() = matches.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it + 1 }
    val canNavigate: Boolean get() = count > 1
    val hasBookmarks: Boolean get() = targets.isNotEmpty()

    fun refresh(updated: List<Target>) {
        targets = updated
        reconcileSelection()
    }

    fun open(): Target? {
        if (isOpen) return current
        close()
        selectedId = targets.firstOrNull()?.id
        isOpen = targets.size > 1
        return current
    }

    fun close() {
        isOpen = false
        query = null
        selectedId = null
    }

    fun search(text: String): Target? {
        query = text.trim().takeIf { it.isNotEmpty() }
        selectedId = matches.firstOrNull()?.id
        return current
    }

    fun clearSearch() {
        query = null
        reconcileSelection()
    }

    fun move(direction: Int): Target? {
        val list = matches
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        val next = Math.floorMod(index + direction, list.size)
        selectedId = list[next].id
        return list[next]
    }

    private fun reconcileSelection() {
        val list = matches
        if (list.none { it.id == selectedId }) selectedId = list.firstOrNull()?.id
    }
}
