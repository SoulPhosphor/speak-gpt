package org.teslasoft.assistant.conversation.parallel

import org.teslasoft.assistant.conversation.responsegroups.ResponseGroupTarget
import java.lang.ref.WeakReference

/** One live owner, independent from a restored copy retaining the same embedded IDs. */
internal class ParallelChatScope(val initialId: String) {
    @Volatile var currentId: String = initialId
        private set
    init { require(initialId.isNotBlank()); ParallelChatScopes.register(this) }
    fun target(value: ResponseGroupTarget): ResponseGroupTarget {
        require(value.chatId == initialId || value.chatId == currentId) { "conversation_scope" }
        return value.copy(chatId = currentId)
    }
    fun rename(old: String, new: String) { if (currentId == old) currentId = new }
}

/** Only an explicitly committed legacy cross-ID rename moves these live scopes. */
object ParallelChatScopes {
    private val scopes = ArrayList<WeakReference<ParallelChatScope>>()
    internal fun register(value: ParallelChatScope) = synchronized(scopes) {
        scopes.removeAll { it.get() == null }; scopes.add(WeakReference(value)); Unit
    }
    fun rename(old: String, new: String) {
        val live = synchronized(scopes) { scopes.mapNotNull { it.get() } }
        live.forEach { it.rename(old, new) }
    }
}
