/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException
import org.teslasoft.assistant.preferences.memory.librarian.Librarian
import org.teslasoft.assistant.preferences.memory.librarian.VectorMath

/**
 * Where the worker reads a message as it is stored now (supplied by the
 * caller; the saved-chat reader is Step 10). Called off the main thread.
 */
fun interface IndexMessageSource {
    /** The message as stored now, or null when it is gone or cannot be read. */
    fun currentMessage(chatId: String, messageId: String): IndexSourceMessage?
}

/** Whether background embedding is paused. Kept across the app being closed and reopened. */
interface EmbeddingPauseState {
    var paused: Boolean
}

/** The pause, saved on the phone so it survives the app being closed. Holds no message content. */
class SavedEmbeddingPauseState(context: Context) : EmbeddingPauseState {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var paused: Boolean
        get() = preferences.getBoolean(KEY_PAUSED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_PAUSED, value).commit()
        }

    private companion object {
        const val FILE = "conversation_index_worker"
        const val KEY_PAUSED = "embedding_paused"
    }
}

/**
 * Makes the vectors of waiting passages in the background (memory development
 * plan Step 9; G.5a rules 2, 3, 4, 6; "The indexing steps", steps 5–7).
 *
 * One waiting vector at a time:
 *  1. its passage's search text is rebuilt from the message as stored now,
 *     using the permanent ID, the original-text fingerprint, the recorded
 *     title and cutting settings, and must give back the stored search-text
 *     fingerprint (the exact model input, title included);
 *  2. the complete model input is counted with the model's own tokenizer,
 *     prefix and special tokens included; one that does not fit is recorded
 *     as failed and is never read cut off or tried again for the same text;
 *  3. the vector is read through the one embedding queue at background
 *     priority, so a live request waits for at most this one read;
 *  4. it is stored in one transaction that checks again that the row still
 *     waits, the passage still uses it, and the message still matches.
 *     A result for text that changed or went away is thrown away.
 *
 * A vector is keyed by its exact model input, model and size, so identical
 * input is read once and shared. A temporary failure (the model failing to
 * read) stops the pass and leaves the work waiting for the next one. The
 * pause is saved, and every finished vector is saved as it is made, so
 * closing and reopening the app continues where it stopped without reading
 * anything twice. Running while the app is closed is Step 13.
 *
 * Nothing in the app starts this yet (Step 10).
 */
class PassageEmbeddingWorker(
    private val storage: PassageVectorStorage,
    private val source: IndexMessageSource,
    private val searchReader: SearchTextReader,
    private val vectorReader: PassageVectorReader,
    private val pauseState: EmbeddingPauseState,
    private val pageSize: Int = PAGE_SIZE
) {
    init {
        require(searchReader.vectorModel == vectorReader.vectorModel) { "search text and vectors must use one model" }
        require(searchReader.vectorDimensions == vectorReader.vectorDimensions) { "vector sizes must match" }
        require(pageSize >= 1)
    }

    data class Counts(val made: Int, val failed: Int, val skipped: Int)

    sealed interface Outcome {
        /** Nothing more is waiting that could be made now. */
        data class Finished(val counts: Counts, val obsoleteRemoved: Int) : Outcome

        data class Paused(val counts: Counts) : Outcome

        data class Cancelled(val counts: Counts) : Outcome

        /** A temporary failure stopped the pass; the rest stays waiting. */
        data class Interrupted(val counts: Counts, val cause: Exception) : Outcome

        /** Another pass is already running. */
        data object AlreadyRunning : Outcome
    }

    val isPaused: Boolean get() = pauseState.paused

    /** Pauses after the read that is running, if any; saved across app restarts. */
    fun pause() {
        pauseState.paused = true
    }

    fun resume() {
        pauseState.paused = false
    }

    private enum class Result { MADE, FAILED, SKIPPED }

    private var made = 0
    private var failed = 0
    private var skipped = 0
    private fun counts() = Counts(made, failed, skipped)

    /** Rebuilt passages of the last few messages, held in working memory for this pass only. */
    private val rebuilt = object : LinkedHashMap<String, SearchPassages>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchPassages>?): Boolean =
            size > REBUILT_MESSAGES
    }

    /**
     * Runs one pass on the calling thread, which must not be the main thread
     * ([requestPass] runs it on the worker's own thread). [isCancelled] is
     * checked between vectors.
     */
    fun runPass(isCancelled: () -> Boolean = { false }): Outcome {
        if (!passRunning.compareAndSet(false, true)) return Outcome.AlreadyRunning
        made = 0
        failed = 0
        skipped = 0
        return try {
            var after: String? = null
            while (true) {
                if (pauseState.paused) return Outcome.Paused(counts())
                if (isCancelled()) return Outcome.Cancelled(counts())
                val page = storage.waitingVectors(vectorReader.vectorModel, vectorReader.vectorDimensions, after, pageSize)
                if (page.isEmpty()) break
                for (waiting in page) {
                    if (pauseState.paused) return Outcome.Paused(counts())
                    if (isCancelled()) return Outcome.Cancelled(counts())
                    after = waiting.key.searchFingerprint
                    when (makeVector(waiting)) {
                        Result.MADE -> made++
                        Result.FAILED -> failed++
                        Result.SKIPPED -> skipped++
                    }
                }
            }
            val removed = storage.removeObsoleteVectors(vectorReader.vectorModel, vectorReader.vectorDimensions)
            Outcome.Finished(counts(), removed)
        } catch (e: Exception) {
            Outcome.Interrupted(counts(), e)
        } finally {
            rebuilt.clear()
            passRunning.set(false)
        }
    }

    /**
     * Starts a pass on the worker's own background thread. Returns false
     * when a pass is already running or could not be started.
     */
    fun requestPass(onDone: (Outcome) -> Unit = {}): Boolean {
        if (passRunning.get()) return false
        return try {
            executor.execute { onDone(runPass()) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** One waiting vector: tries each passage that uses it until one matches its message. */
    private fun makeVector(waiting: WaitingVector): Result {
        var vector: FloatArray? = null
        for (passage in waiting.sources) {
            val input = currentModelInput(passage)
            if (input == null || IndexFingerprint.of(input) != waiting.key.searchFingerprint) {
                storage.markNeedsRefresh(passage.chatId)
                continue
            }
            val unchanged = { stillMatches(passage) }
            val fits = vector != null || vectorReader.countTokens(input) <= vectorReader.tokenLimit
            val read = if (!fits) null else vector ?: try {
                vectorReader.read(input).also {
                    check(it.size == vectorReader.vectorDimensions) { "vector has ${it.size} values" }
                }
            } catch (_: InputTooLongException) {
                null
            }
            vector = read
            val write = if (read == null) {
                storage.markVectorFailed(waiting.key, passage, unchanged)
            } else {
                storage.publishVector(waiting.key, passage, VectorMath.toBlob(read), unchanged)
            }
            when (write) {
                VectorWrite.WRITTEN -> return if (read == null) Result.FAILED else Result.MADE
                // This passage no longer uses the row; another one may.
                VectorWrite.GONE -> continue
                VectorWrite.STALE -> storage.markNeedsRefresh(passage.chatId)
            }
        }
        return Result.SKIPPED
    }

    /** The exact model input of [passage], rebuilt from its message as stored now, or null when it no longer matches. */
    private fun currentModelInput(passage: VectorSourcePassage): String? {
        val settings = SearchSettings.decode(passage.searchSettings) ?: return null
        if (settings.readerKey != searchReader.key || passage.cutterVersion != PassageCutter.CUTTER_VERSION) return null
        val message = source.currentMessage(passage.chatId, passage.messageId) ?: return null
        if (!matches(message, passage)) return null
        val cacheKey = listOf(
            passage.chatId, passage.messageId, passage.textFingerprint, passage.speakerTitle, passage.searchSettings
        ).joinToString("\u0000")
        val passages = rebuilt[cacheKey] ?: try {
            PassageSearchText.build(
                message.modelText, passage.speakerTitle, searchReader, settings.targetTokens, settings.overlapWords
            )
        } catch (_: IllegalArgumentException) {
            return null
        }.also { rebuilt[cacheKey] = it }
        if (passages.cutterVersion != passage.cutterVersion) return null
        return passages.passages.getOrNull(passage.order)?.modelInput
    }

    private fun stillMatches(passage: VectorSourcePassage): Boolean =
        source.currentMessage(passage.chatId, passage.messageId)?.let { matches(it, passage) } ?: false

    private fun matches(message: IndexSourceMessage, passage: VectorSourcePassage): Boolean =
        message.messageId == passage.messageId &&
            IndexEligibility.isEligible(message) &&
            IndexFingerprint.of(message.modelText) == passage.textFingerprint

    companion object {
        private const val PAGE_SIZE = 32
        private const val REBUILT_MESSAGES = 4

        /** One pass at a time in the app, whichever worker starts it. */
        private val passRunning = AtomicBoolean(false)

        private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "conversation-index-embedding").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }

        /**
         * The worker for the installed embedding model, the app's index and
         * its saved pause, reading messages from [source]. Null when no model
         * is usable. Off the main thread.
         */
        fun forInstalledModel(context: Context, source: IndexMessageSource): PassageEmbeddingWorker? {
            val searchReader = SearchTextReader.forInstalledModel(context) ?: return null
            val vectorReader = Librarian.getInstance(context).passageVectorReader() ?: return null
            if (searchReader.vectorModel != vectorReader.vectorModel ||
                searchReader.vectorDimensions != vectorReader.vectorDimensions
            ) return null
            return PassageEmbeddingWorker(
                ConversationIndexStore.get(context), source, searchReader, vectorReader, SavedEmbeddingPauseState(context)
            )
        }
    }
}
