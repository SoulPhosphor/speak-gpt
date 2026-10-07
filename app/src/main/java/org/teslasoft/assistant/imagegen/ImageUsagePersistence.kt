package org.teslasoft.assistant.imagegen

import kotlinx.coroutines.delay

internal enum class ImageUsageSaveResult { SAVED, RETRY, REMOVED }

/** Keep the received evidence in the caller until disk confirms it or its destination is deleted. */
internal object ImageUsagePersistence {
    suspend fun commit(
        save: () -> ImageUsageSaveResult,
        pause: suspend (Long) -> Unit = { delay(it) }
    ): Boolean {
        var waitMs = 1_000L
        while (true) {
            when (save()) {
                ImageUsageSaveResult.SAVED -> return true
                ImageUsageSaveResult.REMOVED -> return false
                ImageUsageSaveResult.RETRY -> {
                    pause(waitMs)
                    waitMs = (waitMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }
}
