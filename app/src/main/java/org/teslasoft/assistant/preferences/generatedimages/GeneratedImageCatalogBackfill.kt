/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************/

package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.ChatStorageHealth
import org.teslasoft.assistant.preferences.chatsearch.SearchableMessageProjection
import java.io.File

/** Resumable history scan. Each authoritative chat is stamped only after its
 * complete scan commits; locked/corrupt chats remain unstamped for a later run. */
object GeneratedImageCatalogBackfill {

    data class Outcome(
        val completed: Boolean,
        val scannedChats: Int,
        val indexedImages: Int,
        val state: GeneratedImageCatalogStorageState
    )

    fun run(context: Context): Outcome {
        val app = context.applicationContext
        val chatPreferences = ChatPreferences.getChatPreferences()
        val listResult = chatPreferences.getChatListResult(app, includeFirstMessage = false)
        if (!ChatStorageHealth.isAuthoritative(listResult.state)) {
            return Outcome(false, 0, 0, GeneratedImageCatalogStorageState.UNAVAILABLE)
        }

        val names = listResult.chats.associate { ChatPreferences.storedChatId(it) to it["name"].orEmpty() }
        val sync = GeneratedImageCatalogStore.synchronizeOriginNames(app, names)
        if (!sync.success) return Outcome(false, 0, 0, sync.state)

        var allAuthoritative = true
        var scanned = 0
        var indexed = 0
        for (chat in listResult.chats) {
            val chatId = ChatPreferences.storedChatId(chat)
            val already = GeneratedImageCatalogStore.isBackfillChatComplete(app, chatId)
            if (already.state != GeneratedImageCatalogStorageState.AVAILABLE &&
                already.state != GeneratedImageCatalogStorageState.NEEDS_RECOVERY
            ) {
                return Outcome(false, scanned, indexed, already.state)
            }
            if (already.value) continue

            val history = chatPreferences.getChatByIdResult(app, chatId)
            if (!ChatStorageHealth.isAuthoritative(history.state)) {
                allAuthoritative = false
                continue
            }

            for (message in history.messages) {
                val metadata = GeneratedImageMetadata.fromJson(
                    message[GeneratedImageMetadata.KEY]?.toString()
                )
                if (metadata != null && metadata.status != GeneratedImageMetadata.STATUS_COMPLETE) continue
                val hash = GeneratedImageMetadata.referencedFileHash(message) ?: continue
                val resolved = GeneratedImageAssetResolver.resolve(app, metadata, hash)
                val available = resolved as? GeneratedImageAssetResolver.Result.Available ?: continue
                val file = available.file
                val imageId = LegacyGeneratedImageIdentity.resolve(
                    metadata?.imageId,
                    hash,
                    metadata?.createdAt ?: 0L,
                    file.name
                )
                val record = GeneratedImageCatalogRecord(
                    imageId = imageId,
                    fileHash = hash,
                    assetFileName = file.name,
                    mimeType = metadata?.mimeType ?: available.mimeType,
                    width = metadata?.width,
                    height = metadata?.height,
                    createdAt = metadata?.createdAt?.takeIf { it > 0L }
                        ?: file.lastModified().takeIf { it > 0L }
                        ?: 0L,
                    originChatId = chatId,
                    originChatName = chat["name"],
                    originMessageId = message[SearchableMessageProjection.MESSAGE_ID_KEY]
                        ?.toString()?.takeIf { it.isNotBlank() }
                        ?: metadata?.imageId?.takeIf { it.isNotBlank() },
                    locked = false,
                    source = GeneratedImageCatalogRecord.Source.BACKFILL
                )
                val result = GeneratedImageCatalogStore.upsertBackfill(app, record)
                if (!result.success) return Outcome(false, scanned, indexed, result.state)
                indexed++
            }

            val marked = GeneratedImageCatalogStore.markBackfillChatComplete(app, chatId)
            if (!marked.success) return Outcome(false, scanned, indexed, marked.state)
            scanned++
        }

        if (allAuthoritative) {
            val marked = GeneratedImageCatalogStore.setBackfillMeta(
                app,
                GeneratedImageCatalogStore.META_BACKFILL_VERSION,
                GeneratedImageCatalogStore.BACKFILL_VERSION
            )
            if (!marked.success) return Outcome(false, scanned, indexed, marked.state)
        }
        return Outcome(
            completed = allAuthoritative,
            scannedChats = scanned,
            indexedImages = indexed,
            state = if (GeneratedImageCatalogHealth.needsRecovery(app)) {
                GeneratedImageCatalogStorageState.NEEDS_RECOVERY
            } else {
                GeneratedImageCatalogStorageState.AVAILABLE
            }
        )
    }
}

object GeneratedImageCatalogReconciler {
    fun run(context: Context): GeneratedImageCatalogStorageState {
        val app = context.applicationContext
        val list = GeneratedImageCatalogStore.listActive(app)
        if (list.state != GeneratedImageCatalogStorageState.AVAILABLE) return list.state
        val imagesDir = app.getExternalFilesDir("images") ?: return GeneratedImageCatalogStorageState.UNAVAILABLE

        for (record in list.records) {
            val file = safeChild(imagesDir, record.assetFileName)
            if (file == null || !file.isFile) {
                val result = GeneratedImageCatalogStore.tombstoneMissing(
                    app,
                    record.imageId,
                    record.assetFileName
                )
                if (!result.success) return result.state
            }
        }
        return GeneratedImageRegistrationJournal.recover(app)
    }

    private fun safeChild(parent: File, name: String): File? =
        if (name.isBlank() || name.contains('/') || name.contains('\\')) null else File(parent, name)
}

/** One background maintenance entrypoint used at process start. */
object GeneratedImageCatalogMaintenance {
    fun run(context: Context) {
        GeneratedImageCatalogBackfill.run(context)
        GeneratedImageCatalogReconciler.run(context)
    }
}
