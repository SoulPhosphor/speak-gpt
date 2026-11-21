package org.teslasoft.assistant.conversation.parallel

import android.content.Context
import kotlinx.coroutines.*
import org.teslasoft.assistant.imagegen.*

/** Reuses the existing catalog, image service, provider adapters, global parameters and billing path.
 * Owned images never call the ordinary screen's listener or append an unrelated assistant row.
 */
internal class ParallelRegisteredImages(context: Context, private val chat: ParallelChatScope) {
    private val app = context.applicationContext

    suspend fun generate(owner: ParallelRequest, imageId: String, request: ImageGenerationRequest,
        record: suspend (String, String) -> Unit): String {
        val key = "parallel:${chat.initialId}:${owner.target.groupId}:${owner.slotId}:${owner.versionId}:$imageId"
        val job = withContext(Dispatchers.Main.immediate) {
            ImageGenerationJobRegistry.start(app, chat.currentId, null, request, ImageGenerationJobRegistry.Origin.TOOL,
                ownerKey = key, imageId = imageId, ownedTerminal = { terminal ->
                    record(imageId, terminal.metadata.toJson())
                })
        }
        val terminal = try { job.await() }
        catch (e: CancellationException) {
            // Teardown detaches this waiter; the process-owned image still persists its result.
            // Explicit user Stop cancels the exact image, without touching a sibling's generation.
            if (e is ParallelUserStop) withContext(NonCancellable + Dispatchers.Main.immediate) {
                ImageGenerationJobRegistry.cancelOwned(key, imageId)
            }
            throw e
        }
        return when (terminal) {
            is ImageGenerationJobRegistry.Terminal.Complete -> CreateImageTool.successResult(imageId, request.description.orEmpty())
            is ImageGenerationJobRegistry.Terminal.Cancelled -> CreateImageTool.cancelledResult()
            is ImageGenerationJobRegistry.Terminal.Failed -> CreateImageTool.errorResult(terminal.cause.name.lowercase())
        }
    }
}

/** Distinguishes explicit Stop from parent/screen cancellation throughout tool/image awaiters. */
internal class ParallelUserStop : CancellationException()
