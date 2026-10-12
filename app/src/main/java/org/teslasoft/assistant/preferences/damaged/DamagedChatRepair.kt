/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.damaged

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.SecurePrefs
import org.teslasoft.assistant.preferences.SnapshotRegistry
import org.teslasoft.assistant.preferences.backup.portable.PortableRestoreProcessGate
import org.teslasoft.assistant.preferences.username.DefaultUsernameFill
import java.io.File
import java.security.MessageDigest

/**
 * Repair of the chats in the Damaged Chat Files log (owner rulings, Oct 12
 * 2026). Try Repair works on every damaged chat at once, with no backup
 * step asked of the user, and a chat
 * replacement from a backup uses the same per-chat transaction.
 *
 * Every attempt first copies the chat's file into the preserved-copies
 * folder and verifies the copy byte for byte; nothing is changed when that
 * copy cannot be made. The original is never the only copy at risk, and no
 * readable message is ever given up: recovered or backup messages are merged
 * into the chat's current readable messages, never put in their place.
 *
 * Direct repair methods:
 *  - reading the chat again: a chat whose saved contents could not be
 *    decrypted is repaired when they read correctly now (a passing glitch);
 *  - recovering messages: a chat whose contents could not be parsed was
 *    already saved aside and reset; every complete, valid message in that
 *    saved copy is merged back into the chat.
 * A chat is irreparable only when these were tried and failed. Open chats,
 * locked storage, a restore in progress or a failed save leave the chat
 * untouched and in the log, to be tried again.
 */
object DamagedChatRepair {

    data class Result(val repaired: Set<String>, val remaining: Set<String>)

    private enum class Outcome { REPAIRED, FAILED, NOT_ATTEMPTED }

    private val messagesType = object : TypeToken<ArrayList<HashMap<String, Any>>>() {}.type

    /** Try Repair: every damaged chat in the log. */
    fun repairAll(context: Context): Result {
        val targets = DamagedChatLog.detections(context).flatMap { d -> d.chats.map { it.chatId } }.distinct()
        return finish(context, targets.associateWith { repairOne(context, it) })
    }

    /** A replacement from a backup for [replacements] (chat id to the backup
     *  copy's messages); other damaged chats are not touched. */
    fun replaceFromBackup(context: Context, replacements: Map<String, List<HashMap<String, Any>>>): Result =
        finish(context, replacements.mapValues { (id, messages) -> replaceOne(context, id, messages) })

    private fun finish(context: Context, outcomes: Map<String, Outcome>): Result {
        val repaired = outcomes.filterValues { it == Outcome.REPAIRED }.keys
        DamagedChatLog.markIrreparable(context, outcomes.filterValues { it == Outcome.FAILED }.keys)
        repaired.forEach { DamagedChatLog.remove(context, it) }
        return Result(repaired, outcomes.keys - repaired)
    }

    private fun repairOne(context: Context, chatId: String): Outcome {
        return synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            if (!safeToChange(context, chatId)) return Outcome.NOT_ATTEMPTED
            val chats = ChatPreferences.getChatPreferences()
            val operation = DamagedChatLog.operationFor(context, chatId) ?: DamagedChatOperation.OPENING_A_CHAT
            val current = chats.getChatByIdResult(context, chatId, operation)
            when (current.state) {
                ChatStorageHealth.ReadState.LOCKED -> return Outcome.NOT_ATTEMPTED
                ChatStorageHealth.ReadState.CORRUPT -> return Outcome.FAILED
                else -> if (!ChatStorageHealth.isAuthoritative(current.state)) return Outcome.FAILED
            }
            // Reads correctly now. A chat reset after a parse failure still
            // has its saved-aside contents to recover.
            val savedCopies = SnapshotRegistry.entries(context)
                .filter { it.chatId == chatId && it.origin == SnapshotRegistry.ORIGIN_CORRUPT_JSON }
                .sortedBy { it.createdAt }
            if (savedCopies.isEmpty()) return Outcome.REPAIRED
            var merged: List<HashMap<String, Any>> = current.messages
            var recoveredAny = false
            for (copy in savedCopies) {
                val prefs = SecurePrefs.get(context, copy.snapshotName)
                if (SecurePrefs.isLockedName(copy.snapshotName)) return Outcome.NOT_ATTEMPTED
                val recovered = DamagedChatSalvage.salvage(
                    try { prefs.getString("chat", null) } catch (_: Exception) { null }
                )
                if (recovered.isNotEmpty()) recoveredAny = true
                merged = DamagedChatSalvage.merge(recovered, merged)
            }
            if (!recoveredAny) return Outcome.FAILED
            if (merged.size == current.messages.size) return Outcome.REPAIRED // already merged earlier
            writeVerified(context, chatId, operation, ArrayList(merged), rollbackTo = current.messages)
        }
    }

    private fun replaceOne(context: Context, chatId: String, backupMessages: List<HashMap<String, Any>>): Outcome {
        return synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            if (!safeToChange(context, chatId)) return Outcome.NOT_ATTEMPTED
            val chats = ChatPreferences.getChatPreferences()
            val operation = DamagedChatLog.operationFor(context, chatId) ?: DamagedChatOperation.OPENING_A_CHAT
            val current = chats.getChatByIdResult(context, chatId, operation)
            val readable = ChatStorageHealth.isAuthoritative(current.state)
            if (current.state == ChatStorageHealth.ReadState.LOCKED) return Outcome.NOT_ATTEMPTED
            val merged = DamagedChatSalvage.merge(backupMessages, if (readable) current.messages else emptyList())
            if (!readable) {
                // The unreadable contents are preserved (verified copy above);
                // this explicit replacement is what lifts the write block.
                ChatStorageHealth.clearReadFailure(context, "chat_$chatId")
            }
            val outcome = writeVerified(context, chatId, operation, merged, rollbackTo = if (readable) current.messages else null)
            if (outcome != Outcome.REPAIRED && !readable) {
                ChatStorageHealth.recordReadFailure(context, "chat_$chatId", null)
            }
            outcome
        }
    }

    /** Saves [messages] and reads them back; on any mismatch restores the
     *  readable [rollbackTo] contents. A failed save is not proof the chat
     *  cannot be repaired, so it is left to be tried again. */
    private fun writeVerified(
        context: Context,
        chatId: String,
        operation: DamagedChatOperation,
        messages: ArrayList<HashMap<String, Any>>,
        rollbackTo: ArrayList<HashMap<String, Any>>?
    ): Outcome {
        val chats = ChatPreferences.getChatPreferences()
        val saved = chats.saveChatHistory(context, chatId, messages, synchronous = true)
        val verified = saved == ChatStorageHealth.WriteOutcome.OK && run {
            val back = chats.getChatByIdResult(context, chatId, operation)
            ChatStorageHealth.isAuthoritative(back.state) &&
                Gson().toJson(back.messages) == Gson().toJson(messages)
        }
        if (verified) return Outcome.REPAIRED
        if (rollbackTo != null) chats.saveChatHistory(context, chatId, rollbackTo, synchronous = true)
        return Outcome.NOT_ATTEMPTED
    }

    /** The chat may be changed: it still exists, is not open on screen, no
     *  restore is running, and its file was copied aside and verified. */
    private fun safeToChange(context: Context, chatId: String): Boolean {
        if (PortableRestoreProcessGate.blocksCurrentProcess(context)) return false
        if (DefaultUsernameFill.isOpen(chatId)) return false
        val listed = ChatPreferences.getChatPreferences().getChatListResult(context, includeFirstMessage = false)
        if (!ChatStorageHealth.isAuthoritative(listed.state)) return false
        if (listed.chats.none { ChatPreferences.storedChatId(it) == chatId }) return false
        return preserveVerified(context, chatId)
    }

    /** Copies the chat's file into the preserved-copies folder and checks the
     *  copy matches byte for byte. True only for a verified copy (or when no
     *  file exists, so there is nothing to lose). */
    internal fun preserveVerified(context: Context, chatId: String): Boolean = try {
        val name = "chat_$chatId"
        val source = SecurePrefs.encryptedFile(context, name)
        if (!source.exists()) {
            true
        } else {
            val dir = SecurePrefs.recoveryDir(context)
            if (!dir.exists() && !dir.mkdirs()) {
                false
            } else {
                val copy = File(dir, "${source.nameWithoutExtension}.${SnapshotRegistry.uniqueSuffix()}.xml")
                source.copyTo(copy, overwrite = false)
                val matches = sha256(source).contentEquals(sha256(copy))
                if (matches) {
                    SnapshotRegistry.record(context, copy.name, name, SnapshotRegistry.ORIGIN_PRE_REPAIR, "before_repair")
                } else {
                    copy.delete()
                }
                matches
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    /** The messages of a backup chat (its stored JSON list). */
    fun parseMessages(json: String): List<HashMap<String, Any>>? = try {
        Gson().fromJson<ArrayList<HashMap<String, Any>>>(json, messagesType)
    } catch (_: Exception) {
        null
    }
}
