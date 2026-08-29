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
            if (already.state != GeneratedImageCatalogStorageState.AVAILABLE) {
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
                    originMessageId = message["id"]?.toString()?.takeIf { it.isNotBlank() }
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
            val marked = GeneratedImageCatalogStore.setMeta(
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
            state = GeneratedImageCatalogStorageState.AVAILABLE
        )
    }
}

object GeneratedImageCatalogReconciler {
    private val canonicalPattern = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.[A-Za-z0-9]{2,8}$"
    )

    fun run(context: Context): GeneratedImageCatalogStorageState {
        val app = context.applicationContext
        val list = GeneratedImageCatalogStore.listActive(app)
        if (list.state != GeneratedImageCatalogStorageState.AVAILABLE) return list.state
        val imagesDir = app.getExternalFilesDir("images") ?: return GeneratedImageCatalogStorageState.UNAVAILABLE

        val activeNames = list.records.mapTo(HashSet()) { it.assetFileName }
        for (record in list.records) {
            val file = safeChild(imagesDir, record.assetFileName)
            if (file == null || !file.isFile) {
                val result = GeneratedImageCatalogStore.tombstoneMissing(
                    app,
                    record.imageId,
                    record.assetFileName
                )
                if (!result.success) return result.state
                activeNames.remove(record.assetFileName)
            }
        }

        // These suffixes are created only by AtomicFileWriter's catalog byte
        // path. A process death before rename leaves no durable asset.
        imagesDir.listFiles()?.filter { it.name.endsWith(".catalogtmp") }?.forEach {
            try { it.delete() } catch (_: Exception) { }
        }

        // A UUID-named file with no row can only be an interrupted new-image
        // registration. Legacy hash-named files are deliberately untouched.
        imagesDir.listFiles()?.filter {
            it.isFile && canonicalPattern.matches(it.name) && it.name !in activeNames
        }?.forEach {
            try { it.delete() } catch (_: Exception) { }
        }
        return GeneratedImageCatalogStorageState.AVAILABLE
    }

    private fun safeChild(parent: File, name: String): File? =
        if (name.isBlank() || name.contains('/') || name.contains('\\')) null else File(parent, name)
}

/** One background maintenance entrypoint used at process start. */
object GeneratedImageCatalogMaintenance {
    fun run(context: Context) {
        GeneratedImageCatalogReconciler.run(context)
        GeneratedImageCatalogBackfill.run(context)
    }
}
