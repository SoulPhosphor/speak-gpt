package org.teslasoft.assistant.conversation.parallel

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.teslasoft.assistant.conversation.responsegroups.ResponseGroupHistory
import org.teslasoft.assistant.conversation.responsegroups.ResponseModelAssignment
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.includes.ImageImporter
import org.teslasoft.assistant.preferences.includes.PdfAttachmentStore
import org.teslasoft.assistant.service.GenerationForegroundService
import org.teslasoft.assistant.usage.TokenPricingCatalogClient
import java.io.File

/** Production binding, still unexposed. Later setup supplies one already-prepared frozen turn, one
 * turn-owned context-commit callback, and request-owned confirmation presentation; it never invokes
 * the ordinary mutable ChatActivity generation funnel once per model.
 */
internal object ParallelRuntime {
    fun forChat(context: Context, chatId: String, owner: CoroutineScope,
        canonicalSourcesChanged: (ResponseGroupHistory.Change) -> Unit,
        confirmImage: suspend (ParallelRequest, String) -> Boolean,
        persistence: ParallelGroupPersistence? = null): ParallelRequestCoordinator {
        val app = context.applicationContext
        val chat = ParallelChatScope(chatId)
        val store = persistence ?: StoredParallelGroups(app, chat, canonicalSourcesChanged)
        val images = ParallelRegisteredImages(app, chat)
        fun endpoint(assignment: ResponseModelAssignment): ApiEndpointObject {
            val current = ApiEndpointPreferences.getApiEndpointPreferences(app).getApiEndpoint(app, assignment.endpointProfileId)
            if (current.id != assignment.endpointProfileId || current.host != assignment.endpoint || current.apiKey.isBlank() ||
                current.authType != assignment.capabilities["parallel_auth_type"])
                throw ParallelTransportFailure("endpoint_configuration_changed")
            // Only credentials are live. A later settings edit cannot change this request's path/timeouts/routing.
            return ApiEndpointObject(current.label, assignment.endpoint, current.apiKey,
                chatEndpoint = assignment.parameters.getValue("parallel_chat_path"), authType = current.authType,
                connectTimeoutSeconds = assignment.parameters.getValue("parallel_connect_seconds").toInt(),
                responseTimeoutSeconds = assignment.parameters.getValue("parallel_response_seconds").toInt(),
                id = current.id, identity = assignment.capabilities["parallel_endpoint_identity"] ?: current.identity)
        }
        val transport = ParallelChatTransport { assignment -> endpoint(assignment).apiKey }
        return ParallelRequestCoordinator(owner, store, { target, attachment ->
            val actual = chat.target(target)
            val dir = when (attachment.store) {
                "image" -> ImageImporter.chatImagesDir(app, actual.chatId)
                "pdf" -> PdfAttachmentStore.chatPdfsDir(app, actual.chatId)
                else -> error("snapshot_store")
            }
            File(dir, attachment.fileName).takeIf { it.isFile && it.length() == attachment.sizeBytes }?.readBytes()
        }, { transport }, { request -> TokenPricingCatalogClient.load(endpoint(request.assignment), request.assignment.modelId) },
            tools = ParallelImageTools({ ParallelImageRules.global(app) }, confirmImage, images::generate),
            keepAlive = {
                val acquired = GenerationForegroundService.begin(app, chat.currentId, null)
                AutoCloseable { if (acquired) GenerationForegroundService.end(app) }
            })
    }
}
