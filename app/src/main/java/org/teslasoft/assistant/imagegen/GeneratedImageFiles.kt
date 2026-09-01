/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.imagegen

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStorageState
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogStore
import java.io.File

/**
 * §12 private-file cleanup: deleting a generated-image message deletes its
 * stored image file — but only when no other stored chat message still
 * references the same file. New-chat copying duplicates message lists, so
 * two chats can legitimately share one file hash; the reference check
 * reads every chat's persisted history before removing anything.
 *
 * Best-effort by design: it runs after the message deletion has already
 * been persisted, on a background scope, and a failure leaves at worst an
 * orphaned file — never a missing one that a message still needs.
 */
object GeneratedImageFiles {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The hashes of the generated-image files referenced by [messages] —
     *  collected BEFORE the messages are removed. */
    fun referencedHashes(messages: List<Map<String, Any?>>): Set<String> =
        messages.mapNotNull { GeneratedImageMetadata.referencedFileHash(it) }.toSet()

    /** Deletes each candidate file unless any stored chat message still
     *  references its hash. Call after the deletion has been persisted. */
    fun deleteIfUnreferenced(context: Context, candidates: Collection<String>) {
        if (candidates.isEmpty()) return
        val app = context.applicationContext
        scope.launch {
            try {
                val remaining = candidates.toMutableSet()
                // A gallery row is an independent active reference even after
                // its chat message is gone. If catalog state cannot be read,
                // keep every candidate rather than treating that outage as an
                // empty gallery and destroying an asset.
                for (hash in candidates) {
                    val catalogReference =
                        GeneratedImageCatalogStore.hasActiveFileHash(app, hash)
                    if (catalogReference.state != GeneratedImageCatalogStorageState.AVAILABLE) {
                        return@launch
                    }
                    if (catalogReference.value) remaining.remove(hash)
                }
                if (remaining.isEmpty()) return@launch
                val chatPreferences = ChatPreferences.getChatPreferences()
                val chats = chatPreferences
                    .getChatListResult(app, includeFirstMessage = false).chats
                for (chat in chats) {
                    if (remaining.isEmpty()) break
                    val chatId = ChatPreferences.storedChatId(chat)
                    val history = chatPreferences.getChatByIdResult(app, chatId)
                    // A LOCKED/CORRUPT/FAILED history might still reference
                    // the file — abort and keep everything rather than guess.
                    if (!org.teslasoft.assistant.preferences.ChatStorageHealth
                            .isAuthoritative(history.state)
                    ) {
                        return@launch
                    }
                    for (message in history.messages) {
                        val hash = GeneratedImageMetadata.referencedFileHash(message)
                        if (hash != null) remaining.remove(hash)
                    }
                }
                if (remaining.isEmpty()) return@launch
                val imagesDir = app.getExternalFilesDir("images") ?: return@launch
                for (hash in remaining) {
                    for (format in ImageFormat.entries) {
                        val file = File(imagesDir, "$hash.${format.fileExtension}")
                        if (file.exists()) file.delete()
                    }
                }
            } catch (_: Exception) { /* an orphaned file is the safe failure */ }
        }
    }
}
