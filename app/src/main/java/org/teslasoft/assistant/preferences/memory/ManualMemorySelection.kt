package org.teslasoft.assistant.preferences.memory

/** Ordered, bounded ids only. Filtering the browser never changes this selection. */
class ManualMemorySelection(initial: List<String> = emptyList()) {
    companion object { const val MAX_COUNT = 5 }
    private val selected = LinkedHashSet(initial.filter(String::isNotBlank).distinct().take(MAX_COUNT))
    fun ids(): List<String> = selected.toList()
    fun contains(id: String): Boolean = id in selected
    fun toggle(id: String): Boolean {
        if (selected.remove(id)) return true
        if (id.isBlank() || selected.size >= MAX_COUNT) return false
        selected.add(id)
        return true
    }
}
