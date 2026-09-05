package org.teslasoft.assistant.preferences.chatsearch

import android.content.Context
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationItem
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationResult

/** Owns all Search SQL, rebuild and source/index reconciliation. Activities never touch the DB. */
class ChatSearchIndexManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "chat-search-index") }
    private val rebuilding = AtomicBoolean(false)
    private val journal get() = ChatSearchIndexJournal.get(app)

    fun ensureReady(onChanged: (() -> Unit)? = null) {
        executor.execute {
            val needs = try {
                ChatSearchStore.get(app).requiresRebuild(Locale.getDefault().toLanguageTag())
            } catch (_: Exception) { true }
            if (needs) rebuildBlocking() else reconcileDirtyBlocking()
            onChanged?.invoke()
        }
    }

    fun rebuild(onChanged: (() -> Unit)? = null) {
        executor.execute {
            rebuildBlocking()
            onChanged?.invoke()
        }
    }

    fun scheduleChatRefresh(chatId: String, expectedRevision: String? = null) {
        if (chatId.isBlank()) return
        executor.execute { refreshChatBlocking(chatId, expectedRevision) }
    }

    fun scheduleTitleRefresh(chatId: String, expectedRevision: String) {
        if (chatId.isBlank()) return
        executor.execute { refreshTitleBlocking(chatId, expectedRevision) }
    }

    /**
     * The authoritative chat set was replaced by a restore (Phase 9.3). Search
     * is derived and disposable, so it is rebased rather than migrated: the
     * pre-restore dirty journal (tokens for a corpus that no longer exists) is
     * cleared and the index database is discarded. This runs synchronously and
     * touches no SQLCipher — the discard just closes the handle and deletes the
     * files — so it is safe inside the restore transaction, before the required
     * restart. The next [ensureReady] sees a missing index, treats it as a
     * first-use rebuild, and sources every row from the new chat set; queries
     * return nothing until that active generation exists, so no stale row —
     * including a legacy null-revision row — can appear after a restore.
     *
     * Returns true when both the journal clear and the discard succeeded.
     */
    fun onAuthoritativeChatSetReplaced(): Boolean {
        val journalCleared = try { journal.clearAll() } catch (_: Exception) { false }
        val discarded = try { ChatSearchStore.discard(app) } catch (_: Exception) { false }
        return journalCleared && discarded
    }

    fun scheduleChatsDeleted(chatIds: Set<String>) {
        if (chatIds.isEmpty()) return
        executor.execute {
            try {
                val store = ChatSearchStore.get(app)
                val generation = store.activeGeneration() ?: return@execute
                chatIds.forEach { store.deleteChat(generation, it) }
            } catch (_: Exception) { /* authoritative intersection remains fail-safe */ }
        }
    }

    suspend fun query(
        query: String,
        options: SearchOptions,
        candidateOffset: Int = 0,
        resultLimit: Int = RESULT_PAGE_SIZE
    ): SearchPage = withContext(Dispatchers.IO) {
        queryBlocking(query, options, candidateOffset, resultLimit)
    }

    fun health(): SearchHealth = try { ChatSearchStore.get(app).health() }
        catch (_: Exception) { SearchHealth(SearchCorpusState.UNAVAILABLE) }

    private fun queryBlocking(
        query: String,
        options: SearchOptions,
        candidateOffset: Int,
        resultLimit: Int
    ): SearchPage {
        val expression = SearchQueryCompiler.compile(query, options)
            ?: return SearchPage(emptyList(), false, health(), candidateOffset)
        val navigation = when (val result = ChatNavigationRepository.get(app).snapshot()) {
            is ChatNavigationResult.Success -> result.value
            is ChatNavigationResult.Failure -> return SearchPage(
                emptyList(), false, SearchHealth(SearchCorpusState.UNAVAILABLE), candidateOffset
            )
        }
        val byId = navigation.allChats.associateBy { it.id }
        val store = try { ChatSearchStore.get(app) } catch (_: Exception) {
            return SearchPage(emptyList(), false, SearchHealth(SearchCorpusState.UNAVAILABLE), candidateOffset)
        }
        val generation = store.activeGeneration()
            ?: return SearchPage(emptyList(), false, store.health(), candidateOffset)
        val locale = Locale.getDefault()
        val verified = ArrayList<SearchResult>()
        var offset = candidateOffset
        var exhausted = false
        while (verified.size < resultLimit && !exhausted) {
            val candidates = try { store.candidates(generation, expression, CANDIDATE_BATCH_SIZE, offset) }
                catch (_: Exception) {
                    return SearchPage(emptyList(), false, SearchHealth(SearchCorpusState.UNAVAILABLE), offset)
                }
            offset += candidates.size
            exhausted = candidates.size < CANDIDATE_BATCH_SIZE
            for (candidate in candidates) {
                val nav = byId[candidate.document.chatId] ?: continue
                val currentRevision = if (candidate.document.kind == SearchDocumentKind.TITLE) {
                    nav.titleRevision
                } else sourceRevision(nav.id)
                if (journal.revision(nav.id) != null) continue
                if (candidate.document.sourceRevision != currentRevision) continue
                val authoritativeText = if (candidate.document.kind == SearchDocumentKind.TITLE) nav.name
                    else candidate.document.rawText
                val match = SearchTextPolicy.match(query, authoritativeText, options, locale) ?: continue
                val snippet = if (candidate.document.kind == SearchDocumentKind.MESSAGE) {
                    SearchSnippetPolicy.create(authoritativeText, match.ranges)
                } else SearchSnippetPolicy.Snippet(authoritativeText, match.ranges)
                verified += SearchResult(
                    rowId = candidate.rowId,
                    chatId = nav.id,
                    chatName = nav.name,
                    chatTitle = displayTitle(nav),
                    chatPinned = nav.pinned,
                    kind = candidate.document.kind,
                    matchedText = snippet.text,
                    highlightRanges = snippet.ranges,
                    messageTimestamp = candidate.document.messageTimestamp,
                    messageId = candidate.document.messageId,
                    legacyOrdinal = candidate.document.legacyOrdinal,
                    legacyRole = candidate.document.legacyRole,
                    contentFingerprint = candidate.document.contentFingerprint,
                    rankClass = SearchRankingPolicy.rankClass(
                        candidate.document.kind, authoritativeText, query, options, match, locale
                    ),
                    relevance = candidate.relevance,
                    chatTimestamp = nav.timestamp
                )
            }
        }
        return SearchPage(
            verified.sortedWith(SearchRankingPolicy.comparator).take(resultLimit),
            !exhausted,
            store.health(),
            offset
        )
    }

    private fun rebuildBlocking() {
        if (!rebuilding.compareAndSet(false, true)) return
        var store: ChatSearchStore? = null
        var generation: Long? = null
        try {
            val navigation = when (val result = ChatNavigationRepository.get(app).snapshot()) {
                is ChatNavigationResult.Success -> result.value
                is ChatNavigationResult.Failure -> return
            }
            val activeStore = try {
                ChatSearchStore.get(app)
            } catch (_: Exception) {
                discardDerivedOnly()
                ChatSearchStore.get(app)
            }
            store = activeStore
            val newGeneration = System.currentTimeMillis()
                .coerceAtLeast((activeStore.activeGeneration() ?: 0L) + 1)
            generation = newGeneration
            val locale = Locale.getDefault()
            activeStore.beginGeneration(newGeneration, locale.toLanguageTag())
            var skipped = 0
            for (chat in navigation.allChats) {
                val history = ChatPreferences.getChatPreferences().getChatByIdResult(app, chat.id)
                if (!ChatStorageHealth.isAuthoritative(history.state)) {
                    skipped++
                    continue
                }
                activeStore.insertDocuments(
                    newGeneration,
                    documents(chat, history.messages, sourceRevision(chat.id), locale)
                )
            }
            // Replay changes that raced the corpus scan before publishing it.
            val currentNavigation = when (val result = ChatNavigationRepository.get(app).snapshot()) {
                is ChatNavigationResult.Success -> result.value
                is ChatNavigationResult.Failure -> throw IllegalStateException("Chat metadata unavailable")
            }
            journal.entries().forEach { (chatId, revision) ->
                val current = currentNavigation.allChats.firstOrNull { it.id == chatId }
                if (current != null) {
                    val history = ChatPreferences.getChatPreferences().getChatByIdResult(app, chatId)
                    if (ChatStorageHealth.isAuthoritative(history.state)) {
                        activeStore.replaceChat(
                            newGeneration,
                            chatId,
                            documents(current, history.messages, sourceRevision(chatId), locale)
                        )
                        journal.clearExact(chatId, revision)
                    }
                } else {
                    activeStore.deleteChat(newGeneration, chatId)
                    journal.clearExact(chatId, revision)
                }
            }
            activeStore.activateGeneration(newGeneration, skipped)
            // A mutation may have landed after the replay snapshot. Exact-token
            // clearing above preserves it, so reconcile once more against the
            // newly active generation before leaving the single-flight worker.
            reconcileDirtyBlocking()
        } catch (_: Exception) {
            // The previous complete generation, when present, remains active.
            if (store != null && generation != null) {
                try { store.abortGeneration(generation) } catch (_: Exception) { }
            }
        } finally { rebuilding.set(false) }
    }

    private fun reconcileDirtyBlocking() {
        journal.entries().forEach { (chatId, revision) ->
            refreshChatBlocking(chatId, revision)
        }
    }

    private fun refreshTitleBlocking(chatId: String, expectedRevision: String) {
        try {
            val store = ChatSearchStore.get(app)
            val generation = store.activeGeneration() ?: run { rebuildBlocking(); return }
            val navigation = when (val result = ChatNavigationRepository.get(app).snapshot()) {
                is ChatNavigationResult.Success -> result.value
                is ChatNavigationResult.Failure -> return
            }
            val chat = navigation.allChats.firstOrNull { it.id == chatId }
            if (chat == null) {
                store.deleteChat(generation, chatId)
                journal.clearExact(chatId, expectedRevision)
                return
            }
            if (chat.titleRevision != expectedRevision) return
            val title = documents(chat, emptyList(), null, Locale.getDefault()).single()
            store.replaceTitle(generation, chatId, title)
            journal.clearExact(chatId, expectedRevision)
        } catch (_: Exception) { /* dirty token keeps stale rows suppressed */ }
    }

    private fun refreshChatBlocking(chatId: String, expectedRevision: String?) {
        try {
            val store = ChatSearchStore.get(app)
            val generation = store.activeGeneration() ?: run { rebuildBlocking(); return }
            val navigation = when (val result = ChatNavigationRepository.get(app).snapshot()) {
                is ChatNavigationResult.Success -> result.value
                is ChatNavigationResult.Failure -> return
            }
            val chat = navigation.allChats.firstOrNull { it.id == chatId }
            if (chat == null) {
                store.deleteChat(generation, chatId)
                expectedRevision?.let { journal.clearExact(chatId, it) }
                return
            }
            val history = ChatPreferences.getChatPreferences().getChatByIdResult(app, chatId)
            if (!ChatStorageHealth.isAuthoritative(history.state)) return
            val currentRevision = sourceRevision(chatId)
            if (expectedRevision != null &&
                currentRevision != expectedRevision &&
                chat.titleRevision != expectedRevision
            ) return
            store.replaceChat(
                generation, chatId,
                documents(chat, history.messages, currentRevision, Locale.getDefault())
            )
            if (expectedRevision != null) journal.clearExact(chatId, expectedRevision)
            else currentRevision?.let { journal.clearExact(chatId, it) }
        } catch (_: Exception) { /* journal remains dirty; stale rows stay suppressed */ }
    }

    private fun documents(
        chat: ChatNavigationItem,
        messages: List<Map<String, Any>>,
        revision: String?,
        locale: Locale
    ): List<SearchDocument> = buildList {
        val title = chat.name
        add(
            SearchDocument(
                chatId = chat.id, documentKey = "title:${chat.id}", kind = SearchDocumentKind.TITLE,
                rawText = title, indexText = SearchTextPolicy.indexText(title, locale),
                contentFingerprint = SearchableMessageProjection.fingerprint(title), sourceRevision = chat.titleRevision,
                chatTimestamp = chat.timestamp, messageTimestamp = null, messageId = null,
                legacyOrdinal = null, legacyRole = null
            )
        )
        SearchableMessageProjection.project(messages, locale).forEach { message ->
            add(
                SearchDocument(
                    chatId = chat.id,
                    documentKey = SearchableMessageProjection.documentKey(chat.id, message),
                    kind = SearchDocumentKind.MESSAGE,
                    rawText = message.text,
                    indexText = SearchTextPolicy.indexText(message.text, locale),
                    contentFingerprint = message.fingerprint,
                    sourceRevision = revision,
                    chatTimestamp = chat.timestamp,
                    messageTimestamp = message.timestamp,
                    messageId = message.messageId,
                    legacyOrdinal = if (message.messageId == null) message.ordinal else null,
                    legacyRole = if (message.messageId == null) message.role else null
                )
            )
        }
    }

    private fun sourceRevision(chatId: String): String? = try {
        SecurePrefs.get(app, "chat_$chatId").getString(SEARCH_REVISION_KEY, null)
    } catch (_: Exception) { null }

    private fun displayTitle(item: ChatNavigationItem): String =
        if (item.name.trim().contains("_autoname_")) "Untitled chat" else item.name

    private fun discardDerivedOnly(): Boolean = ChatSearchStore.discard(app)

    companion object {
        const val SEARCH_REVISION_KEY = "search_revision"
        const val SEARCH_PROJECTION_FINGERPRINT_KEY = "search_projection_fingerprint"
        const val RESULT_PAGE_SIZE = 30
        const val QUERY_DEBOUNCE_MS = 180L
        private const val CANDIDATE_BATCH_SIZE = 64

        @Volatile private var instance: ChatSearchIndexManager? = null
        fun get(context: Context): ChatSearchIndexManager = instance ?: synchronized(this) {
            instance ?: ChatSearchIndexManager(context).also { instance = it }
        }

        /** Drop the process singleton so the next [get] rebinds to the current
         *  context. Test-only: this singleton captures its application context,
         *  and Robolectric hands each test a fresh application, so a leaked
         *  instance would operate on a stale (empty) sandbox. */
        @androidx.annotation.VisibleForTesting
        fun resetForTest() {
            synchronized(this) { instance = null }
        }

        fun newRevision(): String = UUID.randomUUID().toString()
    }
}
