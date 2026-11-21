package org.teslasoft.assistant.conversation.responsegroups

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import org.teslasoft.assistant.preferences.MessageCompletionState
import org.teslasoft.assistant.preferences.MessageIdentity
import org.teslasoft.assistant.ui.adapters.chat.ChatAdapter
import org.teslasoft.assistant.usage.TokenUsageAccounting
import java.net.URI
import java.util.UUID

/** An unreadable envelope is data to preserve, never an empty group or a reason to reset a chat. */
sealed class ResponseGroupRead {
    data object Absent : ResponseGroupRead()
    data class Valid(val group: ResponseGroup) : ResponseGroupRead()
    data class Unsupported(val raw: String, val schemaVersion: Int) : ResponseGroupRead()
    data class Invalid(val raw: Any?, val reason: String) : ResponseGroupRead()
}

object ResponseGroupCodec {
    const val KEY = "responseGroup"
    private val gson = Gson()
    internal val states = setOf("streaming", "done", "failed", "stopped", "interrupted")

    fun read(message: Map<String, Any?>): ResponseGroupRead {
        if (!message.containsKey(KEY)) return ResponseGroupRead.Absent
        val raw = message[KEY]
        if (raw !is String) return ResponseGroupRead.Invalid(raw, "envelope_type")
        return try {
            val tree = JsonReader(StringReader(raw)).use { reader ->
                reader.strictness = Strictness.STRICT
                strictElement(reader, 0).also { require(reader.peek() == JsonToken.END_DOCUMENT) { "envelope_shape" } }
            }
            require(tree.isJsonObject) { "envelope_type" }
            val schema = tree.asJsonObject.get("schemaVersion")
            require(schema != null && schema.isJsonPrimitive && schema.asJsonPrimitive.isNumber &&
                schema.asString.matches(Regex("[0-9]+"))) { "schema_version" }
            val version = schema.asInt
            if (version != 1) return ResponseGroupRead.Unsupported(raw, version)
            val group = gson.fromJson(tree, ResponseGroup::class.java)
            // Reject unknown fields/type coercions rather than dropping data on the next mutation.
            require(tree == gson.toJsonTree(group)) { "envelope_shape" }
            validate(group)
            require(message["isBot"] == true || message["isBot"]?.toString() == "true") { "assistant_role" }
            require(message[MessageIdentity.KEY]?.toString() == group.assistantMessageId) { "assistant_identity" }
            val mirror = ChatAdapter.VARIANT_FIELDS.mapNotNull { field ->
                message[field]?.toString()?.let { field to it }
            }.toMap()
            require(mirror == ResponseGroupEdits.canonicalFields(group)) { "canonical_mirror" }
            ResponseGroupRead.Valid(group)
        } catch (e: Exception) {
            // Reasons are internal stable categories, not exception text containing entrusted content.
            ResponseGroupRead.Invalid(raw, (e as? IllegalArgumentException)?.message
                ?.takeIf { it in reasons } ?: "malformed_envelope")
        }
    }

    fun encode(group: ResponseGroup): String {
        validate(group)
        return gson.toJson(group)
    }

    internal fun validate(group: ResponseGroup) {
        require(group.schemaVersion == 1) { "schema_version" }
        val ids = HashSet<String>()
        fun identity(id: String) {
            require(isUuid(id) && ids.add(id)) { "duplicate_or_invalid_identity" }
        }
        identity(group.groupId)
        identity(group.userMessageId)
        identity(group.assistantMessageId)
        require(group.input.contextMode in setOf("full_history", "last_message_only")) { "context_mode" }
        val parts = group.input.parts
        parts.forEach { part ->
            identity(part.partId)
            require((part.text != null) xor (part.attachment != null)) { "snapshot_part" }
            part.attachment?.let { attachment ->
                require(attachment.store in setOf("image", "pdf") &&
                    (attachment.fileName == attachment.sha256 + when (attachment.store) {
                        "pdf" -> ".pdf"
                        else -> when (attachment.mimeType) { "image/png" -> ".png"; "image/jpeg" -> ".jpg"; else -> "invalid" }
                    }) &&
                    (attachment.store != "pdf" || attachment.mimeType == "application/pdf") && attachment.sizeBytes > 0 &&
                    attachment.sha256.matches(Regex("[0-9a-f]{64}"))) { "attachment_identity" }
            }
        }
        val partIds = parts.map { it.partId }.toSet()
        fun refs(refs: List<String>) { require(refs.all { it in partIds }) { "snapshot_reference" } }
        refs(group.input.contextPartIds)
        refs(group.input.currentTurnPartIds)
        require(group.input.currentTurnPartIds.isNotEmpty()) { "snapshot_reference" }
        require(group.slots.isNotEmpty()) { "slots" }
        require(group.slots.any { it.slotId == group.primarySlotId } &&
            group.slots.any { it.slotId == group.foregroundSlotId }) { "slot_selection" }
        group.slots.forEach { slot ->
            identity(slot.slotId)
            val a = slot.assignment
            require(a.modelId.isNotBlank() && a.label.isNotBlank() && a.endpointProfileId.isNotBlank() &&
                a.protocol.isNotBlank()) { "assignment" }
            val endpoint = URI(a.endpoint)
            require(endpoint.scheme in setOf("https", "http") && !endpoint.host.isNullOrBlank() &&
                endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) { "secret_configuration" }
            listOf(a.routing, a.parameters, a.modelRules, a.capabilities).forEach { map ->
                require(map.keys.none { secretKey(it) }) { "secret_configuration" }
            }
            refs(slot.promptPartIds)
            require(slot.promptPartIds.isNotEmpty()) { "snapshot_reference" }
            require(slot.versions.isNotEmpty() && slot.versions.any { it.versionId == slot.canonicalVersionId } &&
                slot.versions.any { it.versionId == slot.foregroundVersionId }) { "version_selection" }
            slot.versions.forEach { version ->
                identity(version.versionId)
                fields(version.fields)
                require(version.attempts.isNotEmpty()) { "attempts" }
                require(version.fields["state"] == version.attempts.last().state &&
                    version.attempts.dropLast(1).none { it.state == MessageCompletionState.STREAMING }) { "attempt_state" }
                version.attempts.forEachIndexed { attemptIndex, attempt ->
                    identity(attempt.requestId)
                    attempt.parentRequestId?.let { parent ->
                        require(attemptIndex > 0 && version.attempts[attemptIndex - 1].requestId == parent) { "attempt_state" }
                    }
                    attempt.continuationMessages?.let { extra ->
                        require(attempt.parentRequestId != null && extra.isNotEmpty()) { "attempt_state" }
                        extra.forEach { raw ->
                            val frame = com.google.gson.JsonParser.parseString(raw).asJsonObject
                            require(frame.get("role")?.asString in setOf("assistant", "tool")) { "attempt_state" }
                        }
                    }
                    attempt.toolAssistantMessage?.let { raw ->
                        val frame = com.google.gson.JsonParser.parseString(raw).asJsonObject
                        require(frame.get("role")?.asString == "assistant" && (frame.getAsJsonArray("tool_calls")?.size() ?: 0) > 0) { "attempt_state" }
                    }
                    attempt.imageJobId?.let { identity(it) }
                    attempt.imageResult?.let { raw ->
                        require(attempt.imageJobId != null && com.google.gson.JsonParser.parseString(raw).asJsonObject
                            .get("imageId")?.asString == attempt.imageJobId) { "attempt_state" }
                    }
                    require(attempt.startedAtMs > 0 && attempt.state in states &&
                        (attempt.endedAtMs == null || attempt.endedAtMs >= attempt.startedAtMs) &&
                        (attempt.state != MessageCompletionState.STREAMING || attempt.endedAtMs == null)) { "attempt_state" }
                    if (attempt.partialFields.isNotEmpty()) fields(attempt.partialFields)
                    attempt.usageRecord?.let { raw ->
                        val records = TokenUsageAccounting.decodeRecords(raw)
                        require(records.size == 1 && records.single().requestId == attempt.requestId) { "usage_identity" }
                    }
                }
            }
        }
    }

    private fun fields(fields: Map<String, String>) {
        require(fields.keys.all { it in ChatAdapter.VARIANT_FIELDS } && "message" in fields &&
            fields[MessageCompletionState.KEY_STATE] in states) { "version_fields" }
    }

    private fun strictElement(reader: JsonReader, depth: Int): JsonElement {
        require(depth < 128) { "envelope_shape" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { out ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!out.has(name)) { "envelope_shape" }
                    out.add(name, strictElement(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().also { out ->
                reader.beginArray()
                while (reader.hasNext()) out.add(strictElement(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> error("malformed_envelope")
        }
    }

    internal fun isUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: Exception) { false }

    private fun secretKey(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return normalized.endsWith("apikey") || normalized.endsWith("headers") ||
            normalized.endsWith("password") || normalized.contains("authorization") || normalized in setOf("apikey", "authorization", "headers", "authheaders", "password", "credential", "credentials", "secret", "accesstoken", "bearertoken")
    }

    private val reasons = setOf("envelope_type", "schema_version", "envelope_shape", "assistant_role",
        "assistant_identity", "canonical_mirror", "duplicate_or_invalid_identity", "context_mode", "snapshot_part", "attachment_identity",
        "snapshot_reference", "slots", "slot_selection", "assignment", "secret_configuration", "version_selection",
        "attempts", "attempt_state", "usage_identity", "version_fields")
}
