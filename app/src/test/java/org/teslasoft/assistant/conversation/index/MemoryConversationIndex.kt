package org.teslasoft.assistant.conversation.index

/**
 * The conversation index in memory, keeping the encrypted store's
 * transaction contract: every write checks its conditions inside the write
 * and rolls back completely when the chat or message changed before commit.
 */
internal class MemoryIndex : ConversationIndexStorage, PassageVectorStorage {
    data class Row(val chatId: String, val messageId: String, val order: Int, val stamp: MessageStamp, val key: VectorKey)
    data class Vector(val state: String, val blob: ByteArray?)

    var rows = ArrayList<Row>()
    var vectors = LinkedHashMap<VectorKey, Vector>()
    var states = HashMap<String, ChatIndexState>()
    var duringVectorWrite: (() -> Unit)? = null

    private fun snapshot() = Triple(ArrayList(rows), LinkedHashMap(vectors), HashMap(states))
    private fun restore(saved: Triple<ArrayList<Row>, LinkedHashMap<VectorKey, Vector>, HashMap<String, ChatIndexState>>) {
        rows = saved.first; vectors = saved.second; states = saved.third
    }

    override fun indexedMessages(chatId: String): Map<String, MessageStamp> =
        rows.filter { it.chatId == chatId && it.order == 0 }.associate { it.messageId to it.stamp }

    override fun indexState(chatId: String) = states[chatId]

    override fun beginUpdate(chatId: String) {
        val old = states[chatId]
        states[chatId] = ChatIndexState(old?.describedVersion, true, old?.waitingVectors ?: 0, false)
    }

    override fun writeMessage(
        chatId: String, messageId: String, expected: MessageStamp?, replacement: MessageEntries?,
        chatUnchanged: () -> Boolean
    ): IndexWrite {
        if (!chatUnchanged() || indexedMessages(chatId)[messageId] != expected) return IndexWrite.STALE
        val saved = snapshot()
        val old = rows.filter { it.chatId == chatId && it.messageId == messageId }
        rows.removeAll(old.toSet())
        replacement?.passages?.forEach { passage ->
            val key = VectorKey(passage.searchFingerprint, replacement.stamp.vectorModel, replacement.stamp.vectorDimensions)
            rows.add(Row(chatId, messageId, passage.order, replacement.stamp, key))
            vectors.putIfAbsent(key, Vector(ConversationIndexStore.STATE_WAITING, null))
        }
        for (key in old.map { it.key }.toSet()) {
            if (rows.any { it.key == key }) continue
            val otherModel = replacement != null && key.vectorModel != replacement.stamp.vectorModel
            if (otherModel && vectors[key]?.state == ConversationIndexStore.STATE_MADE) continue
            vectors.remove(key)
        }
        if (!chatUnchanged()) { restore(saved); return IndexWrite.STALE }
        return IndexWrite.WRITTEN
    }

    override fun restampMessage(
        chatId: String, messageId: String, expected: MessageStamp, stamp: MessageStamp, chatUnchanged: () -> Boolean
    ): IndexWrite {
        if (!chatUnchanged() || indexedMessages(chatId)[messageId] != expected) return IndexWrite.STALE
        rows = ArrayList(rows.map { if (it.chatId == chatId && it.messageId == messageId) it.copy(stamp = stamp) else it })
        return IndexWrite.WRITTEN
    }

    override fun finishUpdate(
        chatId: String, chatVersion: String, expected: Map<String, MessageStamp>, chatUnchanged: () -> Boolean
    ): IndexWrite {
        if (!chatUnchanged() || indexedMessages(chatId) != expected) return IndexWrite.STALE
        val waiting = waitingIn(chatId)
        states[chatId] = ChatIndexState(chatVersion, false, waiting, waiting == 0)
        return IndexWrite.WRITTEN
    }

    private fun waitingIn(chatId: String) =
        rows.count { it.chatId == chatId && vectors[it.key]?.state == ConversationIndexStore.STATE_WAITING }

    override fun waitingVectors(
        vectorModel: String, vectorDimensions: Int, afterFingerprint: String?, limit: Int
    ): List<WaitingVector> =
        vectors.filter { (key, vector) ->
            key.vectorModel == vectorModel && key.vectorDimensions == vectorDimensions &&
                vector.state == ConversationIndexStore.STATE_WAITING &&
                key.searchFingerprint > (afterFingerprint ?: "") && rows.any { it.key == key }
        }.keys.sortedBy { it.searchFingerprint }.take(limit).map { key ->
            WaitingVector(key, rows.filter { it.key == key }
                .sortedWith(compareBy({ it.chatId }, { it.messageId }, { it.order }))
                .map {
                    VectorSourcePassage(
                        it.chatId, it.messageId, it.order, it.stamp.textFingerprint, it.stamp.speakerTitle,
                        it.stamp.cutterVersion, it.stamp.searchSettings
                    )
                })
        }

    private fun vectorWrite(
        key: VectorKey, source: VectorSourcePassage, sourceUnchanged: () -> Boolean, state: String, blob: ByteArray?
    ): VectorWrite {
        val used = rows.any {
            it.key == key && it.chatId == source.chatId && it.messageId == source.messageId &&
                it.order == source.order && it.stamp.textFingerprint == source.textFingerprint
        }
        if (vectors[key]?.state != ConversationIndexStore.STATE_WAITING || !used) return VectorWrite.GONE
        val saved = snapshot()
        vectors[key] = Vector(state, blob)
        for (chat in rows.filter { it.key == key }.map { it.chatId }.toSet()) {
            val old = states[chat] ?: continue
            val waiting = waitingIn(chat)
            states[chat] = old.copy(
                waitingVectors = waiting,
                fullyIndexed = !old.needsRefresh && old.describedVersion != null && waiting == 0
            )
        }
        duringVectorWrite?.invoke()
        if (!sourceUnchanged()) { restore(saved); return VectorWrite.STALE }
        return VectorWrite.WRITTEN
    }

    override fun publishVector(key: VectorKey, source: VectorSourcePassage, vector: ByteArray, sourceUnchanged: () -> Boolean) =
        vectorWrite(key, source, sourceUnchanged, ConversationIndexStore.STATE_MADE, vector)

    override fun markVectorFailed(key: VectorKey, source: VectorSourcePassage, sourceUnchanged: () -> Boolean) =
        vectorWrite(key, source, sourceUnchanged, ConversationIndexStore.STATE_FAILED, null)

    override fun markNeedsRefresh(chatId: String) = beginUpdate(chatId)

    override fun chatProgress(chatId: String): ChatIndexProgress {
        val mine = rows.filter { it.chatId == chatId }
        fun count(state: String) = mine.count { vectors[it.key]?.state == state }
        val state = states[chatId]
        return ChatIndexProgress(
            mine.size, count(ConversationIndexStore.STATE_MADE), count(ConversationIndexStore.STATE_WAITING),
            count(ConversationIndexStore.STATE_FAILED), state?.needsRefresh ?: false, state?.fullyIndexed ?: false
        )
    }

    override fun removeObsoleteVectors(currentModel: String, currentDimensions: Int): Int {
        fun current(key: VectorKey) = key.vectorModel == currentModel && key.vectorDimensions == currentDimensions
        if (rows.any { !current(it.key) } || states.values.any { it.needsRefresh } ||
            rows.any { vectors[it.key]?.state == ConversationIndexStore.STATE_WAITING }
        ) return 0
        val obsolete = vectors.keys.filter { !current(it) }
        obsolete.forEach { vectors.remove(it) }
        return obsolete.size
    }

    fun state(key: VectorKey) = vectors[key]?.state
}

/** The chat store, in memory: what each message is now. */
internal class MemoryChats : IndexMessageSource {
    val messages = HashMap<Pair<String, String>, IndexSourceMessage>()
    val versions = HashMap<String, Int>()
    override fun currentMessage(chatId: String, messageId: String) = messages[chatId to messageId]
    fun put(chatId: String, list: List<IndexSourceMessage>) {
        messages.keys.removeAll { it.first == chatId }
        list.forEach { messages[chatId to it.messageId] = it }
        versions[chatId] = (versions[chatId] ?: 0) + 1
    }
    fun version(chatId: String) = versions[chatId].toString()
}
