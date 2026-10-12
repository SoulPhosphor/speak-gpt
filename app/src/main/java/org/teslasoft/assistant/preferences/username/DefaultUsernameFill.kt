/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.username

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.teslasoft.assistant.conversation.NewConversationCoordinator
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.StartupHealth
import org.teslasoft.assistant.preferences.backup.ChatRestoreManager
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreProcessGate
import org.teslasoft.assistant.preferences.damaged.DamagedChatLog
import org.teslasoft.assistant.preferences.damaged.DamagedChatOperation
import org.teslasoft.assistant.ui.chat.ChatSpeakerNames

/**
 * Apply to Previous Chats with No Name (owner ruling, Oct 12 2026). While the
 * setting is on, every user message saved without a name (it displays the
 * built-in "User") gets the Default Displayed Username written into the
 * saved chat. Messages that already carry any name are never touched, so a
 * later change of the default name only reaches messages that are still
 * nameless.
 *
 * Runs as a WorkManager job so it finishes in the background even if the
 * app is closed, and is safe to repeat (only nameless messages change). A
 * chat that is open on screen is skipped here — the open chat fills its own
 * in-memory messages and saves them through its normal path, so the two can
 * never overwrite each other.
 *
 * Unfinished work (owner rulings, Oct 12 2026): during a restore nothing is
 * written and the run resumes once the restore has finished; locked chats
 * are never touched and insufficient storage leaves the chat as it was —
 * both retry on the next app start; damaged chats are skipped, recorded in
 * the Damaged Chat Files log, and never retried. Each case queues its notice
 * (UsernameFillState) for the next time a screen is shown.
 */
object DefaultUsernameFill {

    private const val WORK_NAME = "default_username_fill"

    // Chats currently open in ChatActivity (open count per id). Guarded by
    // ChatPreferences.CHAT_LIST_LOCK so opening a chat waits for an
    // in-progress fill of that chat to finish before reading it.
    private val openChats = HashMap<String, Int>()

    /** Whether [chatId] is open on screen. Call while holding
     *  ChatPreferences.CHAT_LIST_LOCK. */
    internal fun isOpen(chatId: String): Boolean = openChats.containsKey(chatId)

    /** Call on a background thread BEFORE reading the chat's history. */
    fun markOpen(chatId: String) {
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            openChats[chatId] = (openChats[chatId] ?: 0) + 1
        }
    }

    fun markClosed(chatId: String) {
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val count = (openChats[chatId] ?: 0) - 1
            if (count > 0) openChats[chatId] = count else openChats.remove(chatId)
        }
    }

    /** The name to fill with, or null when the setting is off or no default
     *  name is set. */
    fun activeName(context: Context): String? {
        val preferences = Preferences.getPreferences(context, "")
        if (!preferences.getApplyDefaultUsernameToPreviousChats()) return null
        return preferences.getDefaultDisplayedUsername().trim().takeIf { it.isNotEmpty() }
    }

    /** Queue the background fill (replacing any queued run). */
    fun schedule(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DefaultUsernameFillWorker>().build()
        )
    }

    /**
     * Writes [name] into every nameless user message of [messages]. Returns
     * true when anything changed. Pure; unit-tested.
     */
    fun fillNameless(messages: List<MutableMap<String, Any>>, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        var changed = false
        for (message in messages) {
            val isBot = message["isBot"] == true || message["isBot"]?.toString() == "true"
            if (isBot) continue
            if (message[ChatSpeakerNames.USER_NAME_KEY]?.toString()?.isNotBlank() == true) continue
            message[ChatSpeakerNames.USER_NAME_KEY] = trimmed
            changed = true
        }
        return changed
    }

    /** What one run could not finish, for the queued notices and retries. */
    internal data class RunOutcome(
        val restoreInProgress: Boolean = false,
        val locked: Boolean = false,
        val insufficientStorage: Boolean = false,
        val otherFailure: Boolean = false,
        val damagedChatIds: Set<String> = emptySet()
    ) {
        val needsRetry: Boolean
            get() = restoreInProgress || locked || insufficientStorage || otherFailure
    }

    /**
     * Fills every closed, healthy chat; records why anything was left
     * unfinished, queues the matching notices, and keeps a retry pending for
     * the next app start. Damaged chats are skipped and never written.
     */
    /** Runs the fill and records its result. Returns false only when that
     *  result could not be durably recorded (the caller then retries). */
    fun runAll(context: Context): Boolean {
        // Startup recovery (restores, the outage merge, rename and deletion
        // recovery, new chats' first saves) can still be adding or replacing
        // chats. A run queued before the app restarted waits for it; the
        // startup thread resumes the fill once everything has settled.
        synchronized(startupLock) {
            if (!startupSettled) {
                return if (PortableRestoreProcessGate.blocksCurrentProcess(context)) {
                    UsernameFillState.finish(context, RunOutcome(restoreInProgress = true), complete = false)
                } else {
                    UsernameFillState.deferToNextStart(context)
                }
            }
        }
        // A chat recovery still replacing histories: filling now would write
        // histories about to be replaced. Wait for the next start.
        if (ChatRestoreManager.hasPendingRecovery(context)) {
            return UsernameFillState.deferToNextStart(context)
        }
        val name = activeName(context)
            ?: return UsernameFillState.finish(context, RunOutcome(), complete = true)
        val before = listedChatIds(context)
        val outcome = fill(context, name)
        // The completion decision is made under the chat list lock, which a
        // new chat's first save and a chat-set replacement also hold, so the
        // chat set cannot change between this check and the recorded result.
        // The fill stays pending for a later start when:
        //  - a new chat's turns have not reached the list yet (first save
        //    pending, or an unlisted provisional chat that is unreadable,
        //    locked or holds turns);
        //  - a chat joined the list during the pass;
        //  - a restore began during the pass (it may have put back histories
        //    already filled);
        //  - storage-outage recovery has not fully settled (a deferred outage
        //    file can still bring chats back on a later start).
        return synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val unsettled = NewConversationCoordinator(context).hasUnsettledProvisionalTurns()
            val after = listedChatIds(context)
            val joinedDuringPass = before == null || after == null || !before.containsAll(after)
            val restoreStarted = PortableRestoreProcessGate.blocksCurrentProcess(context) ||
                ChatRestoreManager.hasPendingRecovery(context)
            val outageUnsettled = !StartupHealth.isRecoveryScanSettled(context)
            UsernameFillState.finish(
                context, outcome,
                complete = !outcome.needsRetry && !unsettled && !joinedDuringPass &&
                    !restoreStarted && !outageUnsettled
            )
        }
    }

    private val startupLock = Any()
    private var startupSettled = false

    /**
     * Called by the app's startup thread once every startup step that can
     * restore, replace or add chats has finished: lets the fill run in this
     * process and resumes one left pending (including a run that waited for
     * this point). Not called while a chat recovery is still unfinished.
     */
    fun onStartupSettled(context: Context) {
        synchronized(startupLock) { startupSettled = true }
        resumeIfPending(context)
    }

    /** The listed chat ids, or null when the list cannot be read. */
    private fun listedChatIds(context: Context): Set<String>? = try {
        val listed = ChatPreferences.getChatPreferences().getChatListResult(context, includeFirstMessage = false)
        if (ChatStorageHealth.isAuthoritative(listed.state)) {
            listed.chats.mapTo(HashSet()) { ChatPreferences.storedChatId(it) }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun fill(context: Context, name: String): RunOutcome {
        if (PortableRestoreProcessGate.blocksCurrentProcess(context)) {
            return RunOutcome(restoreInProgress = true)
        }
        val chatPreferences = ChatPreferences.getChatPreferences()
        val listed = chatPreferences.getChatListResult(context, includeFirstMessage = false)
        when (listed.state) {
            ChatStorageHealth.ReadState.LOCKED -> return RunOutcome(locked = true)
            else -> if (!ChatStorageHealth.isAuthoritative(listed.state)) return RunOutcome(otherFailure = true)
        }
        val alreadyDamaged = DamagedChatLog.chatIds(context)
        var locked = false
        var storage = false
        var other = false
        val damaged = LinkedHashSet<String>()
        for (chatId in listed.chats.map { ChatPreferences.storedChatId(it) }) {
            // Already recorded as damaged (possibly by another operation):
            // never written, but still reported as not renamed.
            if (chatId in alreadyDamaged) {
                damaged.add(chatId)
                continue
            }
            synchronized(ChatPreferences.CHAT_LIST_LOCK) {
                if (openChats.containsKey(chatId)) return@synchronized
                // Re-check under the lock: a chat deleted meanwhile must not
                // be recreated by this write.
                val current = chatPreferences.getChatListResult(context, includeFirstMessage = false)
                if (!ChatStorageHealth.isAuthoritative(current.state)) {
                    if (current.state == ChatStorageHealth.ReadState.LOCKED) locked = true else other = true
                    return@synchronized
                }
                if (current.chats.none { ChatPreferences.storedChatId(it) == chatId }) return@synchronized
                val history = chatPreferences.getChatByIdResult(
                    context, chatId, DamagedChatOperation.RENAMING_USERNAMES
                )
                when (history.state) {
                    ChatStorageHealth.ReadState.LOCKED -> { locked = true; return@synchronized }
                    ChatStorageHealth.ReadState.CORRUPT -> { damaged.add(chatId); return@synchronized }
                    else -> if (!ChatStorageHealth.isAuthoritative(history.state)) {
                        other = true
                        return@synchronized
                    }
                }
                if (!fillNameless(history.messages, name)) return@synchronized
                when (chatPreferences.saveChatHistory(context, chatId, history.messages, synchronous = true)) {
                    ChatStorageHealth.WriteOutcome.OK -> Unit
                    ChatStorageHealth.WriteOutcome.LOCKED -> locked = true
                    ChatStorageHealth.WriteOutcome.BLOCKED_CORRUPT -> damaged.add(chatId)
                    ChatStorageHealth.WriteOutcome.FAILED -> when {
                        PortableRestoreProcessGate.blocksCurrentProcess(context) ->
                            return RunOutcome(restoreInProgress = true, damagedChatIds = damaged)
                        lacksSpaceFor(context, history.messages) -> storage = true
                        else -> other = true
                    }
                }
            }
        }
        // A chat whose write was refused as damaged is recorded too (its read
        // may have succeeded in this run while an earlier failure stands).
        if (damaged.isNotEmpty()) {
            DamagedChatLog.recordAll(context, damaged, DamagedChatOperation.RENAMING_USERNAMES)
        }
        return RunOutcome(
            locked = locked,
            insufficientStorage = storage,
            otherFailure = other,
            damagedChatIds = damaged
        )
    }

    /** Evidence for "insufficient storage": the free space left on the app's
     *  data volume is smaller than a full rewrite of this chat needs. */
    private fun lacksSpaceFor(context: Context, messages: List<Map<String, Any>>): Boolean = try {
        val needed = com.google.gson.Gson().toJson(messages).toByteArray().size.toLong() * 2 + SPACE_MARGIN_BYTES
        context.dataDir.usableSpace < needed
    } catch (_: Exception) {
        false
    }

    private const val SPACE_MARGIN_BYTES = 1L shl 20

    /** Called on app start once any restore recovery has finished
     *  successfully: picks up a retry left by an earlier run. */
    fun resumeIfPending(context: Context) {
        if (!UsernameFillState.retryPending(context)) return
        if (activeName(context) == null) {
            UsernameFillState.finish(context, RunOutcome(), complete = true)
            return
        }
        schedule(context)
    }
}
