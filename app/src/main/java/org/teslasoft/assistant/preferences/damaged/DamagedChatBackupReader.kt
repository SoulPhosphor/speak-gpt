/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.damaged

import android.content.Context
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.backup.portable.PortableChatRestorePlan
import org.teslasoft.assistant.preferences.backup.portable.PortablePackage
import org.teslasoft.assistant.preferences.backup.portable.PortablePackageFormat
import org.teslasoft.assistant.preferences.backup.portable.PortableRecoveryLimits
import java.io.File

/**
 * Attempt Chat Replacement from Backup (owner rulings, Oct 12 2026): reads a
 * recovery backup with the existing package decoding and validation, and
 * finds the damaged chats it holds by their stable chat IDs — never by
 * title. Nothing here changes any chat.
 */
object DamagedChatBackupReader {

    data class BackupChat(
        val chatId: String,
        val messages: List<HashMap<String, Any>>,
        val lastActivity: Long?
    )

    sealed class Read {
        data class Ok(val chats: Map<String, BackupChat>) : Read()
        data class Failed(val error: PortablePackageFormat.RestoreError) : Read()
    }

    /** A damaged chat found in the backup. [backupOlder] is true when the
     *  backup copy's last activity is before the chat's last activity on this
     *  device, so newer messages may be missing from it. */
    data class Match(val chatId: String, val title: String?, val backupOlder: Boolean)

    data class Matches(val found: List<Match>, val notFoundTitles: List<String?>)

    /** Decodes [packageFile] (with [password], or the recovery [secret]; an
     *  unprotected backup takes an empty secret) into [stagingDir]. */
    fun read(packageFile: File, stagingDir: File, secret: ByteArray?, password: CharArray?): Read {
        val decoded = if (password != null) {
            PortablePackage.decodeWithPassword(packageFile, password, stagingDir)
        } else {
            PortablePackage.decodeWithSecret(packageFile, secret ?: ByteArray(0), stagingDir)
        }
        if (decoded !is PortablePackage.DecodeResult.Ok) {
            return Read.Failed((decoded as PortablePackage.DecodeResult.Failed).error)
        }
        val artifactRoot = File(stagingDir, "artifacts")
        if (!artifactRoot.mkdirs() && !artifactRoot.isDirectory) {
            return Read.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
        }
        val validated = PortablePackage.validateAndExtract(decoded.innerZip, artifactRoot)
        if (validated !is PortablePackage.ValidateResult.Ok) {
            return Read.Failed((validated as PortablePackage.ValidateResult.Failed).error)
        }
        val artifact = validated.artifacts.firstOrNull { it.type == PortablePackage.TYPE_CHATS_JSON }
            ?: return Read.Ok(emptyMap())
        // This reader holds the chats artifact whole (until plan stage 4).
        if (artifact.stagedFile.length() > PortableRecoveryLimits.WHOLE_READ_RESTORE_CHATS_JSON_BYTES) {
            return Read.Failed(PortablePackageFormat.RestoreError.TOO_LARGE)
        }
        val plan = try {
            PortableChatRestorePlan.parse(artifact.stagedFile.readText())
        } catch (_: Exception) {
            null
        }
        if (plan !is PortableChatRestorePlan.Result.Ok) {
            return Read.Failed(PortablePackageFormat.RestoreError.DAMAGED_OR_ALTERED)
        }
        val chats = LinkedHashMap<String, BackupChat>()
        for (chat in plan.plan.chats) {
            val messages = DamagedChatRepair.parseMessages(chat.messagesJson) ?: continue
            chats[chat.chatId] = BackupChat(chat.chatId, messages, chat.listRow["timestamp"]?.toLongOrNull())
        }
        return Read.Ok(chats)
    }

    /** The damaged chats in the log that [backup] holds, and those it does not. */
    fun match(context: Context, backup: Map<String, BackupChat>): Matches {
        val damaged = DamagedChatLog.detections(context).flatMap { it.chats }.distinctBy { it.chatId }
        val listed = ChatPreferences.getChatPreferences().getChatListResult(context, includeFirstMessage = false)
        val rows = if (ChatStorageHealth.isAuthoritative(listed.state)) listed.chats else emptyList()
        val found = ArrayList<Match>()
        val notFound = ArrayList<String?>()
        for (chat in damaged) {
            val copy = backup[chat.chatId]
            if (copy == null) {
                notFound.add(chat.title)
                continue
            }
            val currentActivity = rows.firstOrNull { ChatPreferences.storedChatId(it) == chat.chatId }
                ?.get("timestamp")?.toLongOrNull()
            val older = currentActivity != null && (copy.lastActivity == null || copy.lastActivity < currentActivity)
            found.add(Match(chat.chatId, chat.title, older))
        }
        return Matches(found, notFound)
    }
}
