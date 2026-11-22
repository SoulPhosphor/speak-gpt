package org.teslasoft.assistant.conversation.parallel

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.teslasoft.assistant.conversation.responsegroups.ResponseAttachment
import org.teslasoft.assistant.conversation.responsegroups.ResponseModelAssignment
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.Base64

/** Provider-ready messages from the existing eligibility/Include preparation, frozen before dispatch.
 * No model capability, prompt layer, summary or memory selection is recomputed here.
 */
internal object ParallelChatSnapshot {
    const val OPTIONS = "parallel_wire_options"
    const val ALLOW_IMAGES = "parallel_allow_images"
    const val SHOW_REASONING = "parallel_show_reasoning"
    private const val PREFIX = "parallel-snapshot:"
    private const val UPLOADED_SNAPSHOT = "parallel_uploaded_snapshot"

    /** Provider-side file IDs still retain and validate the original backed-up bytes. */
    fun uploadedFile(fileId: String, ref: ResponseAttachment) = JsonObject().apply {
        addProperty("file_id", fileId)
        addProperty(UPLOADED_SNAPSHOT, attachmentReference(ref))
    }

    fun text(role: String, text: String): ParallelInputPart.Text = message(JsonObject().apply {
        addProperty("role", role); addProperty("content", text)
    })

    fun message(value: JsonObject): ParallelInputPart.Text {
        require(value.get("role")?.asString in setOf("system", "developer", "user", "assistant", "tool"))
        require(value.has("content") || value.has("tool_calls"))
        return ParallelInputPart.Text(value.toString())
    }

    /** Put this reference at the exact URL/file_data position selected by existing model preparation. */
    fun attachmentReference(ref: ResponseAttachment) = PREFIX + ref.store + ":" + ref.fileName

    fun assignment(
        original: ResponseModelAssignment,
        endpoint: ApiEndpointObject,
        preparedBody: String,
        allowImages: Boolean = true,
        showReasoning: Boolean
    ): ResponseModelAssignment {
        require(original.endpointProfileId == endpoint.id && original.endpoint == endpoint.host)
        val options = JsonParser.parseString(preparedBody).asJsonObject.deepCopy()
        require(options.remove("model")?.asString == original.modelId)
        options.remove("messages")
        require(options.keySet().none { it.lowercase() in setOf("authorization", "headers", "api_key", "apikey") })
        if (!allowImages) options.getAsJsonArray("tools")?.let { tools ->
            val retained = JsonArray()
            tools.filterNot { it.asJsonObject.getAsJsonObject("function")?.get("name")?.asString == "create_image" }
                .forEach(retained::add)
            if (retained.isEmpty) { options.remove("tools"); options.remove("tool_choice") }
            else options.add("tools", retained)
        }
        return original.copy(parameters = original.parameters + mapOf(
            OPTIONS to options.toString(), ALLOW_IMAGES to allowImages.toString(), SHOW_REASONING to showReasoning.toString(),
            "parallel_chat_path" to endpoint.chatEndpoint,
            "parallel_connect_seconds" to endpoint.connectTimeoutSeconds.toString(),
            "parallel_response_seconds" to endpoint.responseTimeoutSeconds.toString()),
            capabilities = original.capabilities + mapOf("parallel_auth_type" to endpoint.authType, "parallel_endpoint_identity" to endpoint.identity))
    }

    fun body(request: ParallelRequest): String {
        val root = JsonParser.parseString(request.assignment.parameters.getValue(OPTIONS)).asJsonObject.deepCopy()
        require(!root.has("messages") && !root.has("model"))
        root.addProperty("model", request.assignment.modelId)
        val messages = JsonArray()
        val consumed = HashSet<String>()
        fun resolve(value: com.google.gson.JsonElement, field: String? = null): com.google.gson.JsonElement = when {
            value.isJsonObject -> value.asJsonObject.deepCopy().also { obj ->
                if (field == "file" && obj.has("file_id") && obj.has(UPLOADED_SNAPSHOT)) {
                    val reference = obj.remove(UPLOADED_SNAPSHOT).asString
                    val part = request.prompt.single { it.attachment?.let(::attachmentReference) == reference }
                    require(part.attachment!!.matches(request.attachmentBytes.getValue(part.partId))) { "snapshot_attachment_unavailable" }
                    consumed += part.partId
                }
                obj.entrySet().toList().forEach { (key, child) -> obj.add(key, resolve(child, key)) }
            }
            value.isJsonArray -> JsonArray().also { out -> value.asJsonArray.forEach { out.add(resolve(it, field)) } }
            field in setOf("url", "file_data") && value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.startsWith(PREFIX) -> {
                val part = request.prompt.single { it.attachment?.let(::attachmentReference) == value.asString }
                val ref = part.attachment!!
                val bytes = request.attachmentBytes.getValue(part.partId)
                require(ref.matches(bytes)) { "snapshot_attachment_unavailable" }
                consumed += part.partId
                com.google.gson.JsonPrimitive("data:${ref.mimeType};base64," + Base64.getEncoder().encodeToString(bytes))
            }
            else -> value.deepCopy()
        }
        request.prompt.forEach { part -> part.text?.let { text ->
            val frame = JsonParser.parseString(text).asJsonObject
            require(frame.get("role")?.asString in setOf("system", "developer", "user", "assistant", "tool"))
            messages.add(resolve(frame))
        } }
        require(consumed == request.attachmentBytes.keys) { "unencoded_attachment" }
        request.continuationMessages.forEach { messages.add(JsonParser.parseString(it).asJsonObject) }
        if (request.continuationMessages.isNotEmpty() || request.omitTools) { root.remove("tools"); root.remove("tool_choice") }
        root.add("messages", messages)
        return root.toString()
    }
}
