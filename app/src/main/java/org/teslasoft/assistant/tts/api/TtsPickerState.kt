package org.teslasoft.assistant.tts.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings
import org.teslasoft.assistant.providers.SortDirection

/** Navigation payload: identity and choices only, never endpoint credentials. */
data class TtsPickerRequest(val target: TtsTarget) {
    fun acceptsProviderResult(result: TtsTarget): Boolean =
        result.endpointId == target.endpointId && result.modelId == target.modelId &&
            result.sourceId == target.sourceId && result.voiceId == target.voiceId

    fun acceptsModelResult(result: TtsTarget): Boolean = target.sourceId == null &&
        result.endpointId == target.endpointId && result.sourceId == null && result.modelId.isNotBlank()
}

/** Only the owning picker mutates this copy. Sorting cannot rewrite routing priority. */
class TtsProviderPickerState(val request: TtsPickerRequest) {
    var routing: TtsRoutingSettings = request.target.routing.copy(
        providerOrder = request.target.routing.providerOrder.toList())
        private set
    var sort = TtsProviderSort()

    init { promotePreferredSelection() }
    private fun promotePreferredSelection() {
        if (routing.mode == TtsRoutingMode.PREFERRED && routing.providerOrder.isEmpty() && routing.selectedProvider.isNotBlank())
            routing = routing.copy(providerOrder = listOf(routing.selectedProvider))
    }
    fun mode(mode: TtsRoutingMode) { routing = routing.copy(mode = mode); promotePreferredSelection() }
    fun fallbacks(enabled: Boolean) { routing = routing.copy(allowFallbacks = enabled) }
    fun select(id: String) {
        require(id.isNotBlank())
        routing = when (routing.mode) {
            TtsRoutingMode.AUTOMATIC -> routing
            TtsRoutingMode.ONLY -> routing.copy(selectedProvider = id)
            TtsRoutingMode.PREFERRED -> {
                val order = if (id in routing.providerOrder) routing.providerOrder - id else routing.providerOrder + id
                routing.copy(providerOrder = order, selectedProvider = if (routing.selectedProvider == id && id !in order)
                    "" else routing.selectedProvider)
            }
        }
    }
    fun remove(id: String) { routing = routing.copy(providerOrder = routing.providerOrder - id,
        selectedProvider = if (routing.selectedProvider == id) "" else routing.selectedProvider) }
    fun move(index: Int, destination: Int) {
        if (index !in routing.providerOrder.indices || destination !in routing.providerOrder.indices) return
        routing = routing.copy(providerOrder = routing.providerOrder.toMutableList().apply {
            add(destination, removeAt(index))
        })
    }

    fun result(): TtsTarget {
        val target = request.target.copy(routing = routing.copy(providerOrder = routing.providerOrder.toList()))
        val kind = when {
            target.endpointId.isBlank() -> TtsFailureKind.ENDPOINT_REQUIRED
            target.modelId.isBlank() -> TtsFailureKind.MODEL_REQUIRED
            routing.mode == TtsRoutingMode.ONLY && routing.selectedProvider.isBlank() -> TtsFailureKind.PROVIDER_REQUIRED
            routing.mode == TtsRoutingMode.PREFERRED && !routing.allowFallbacks &&
                routing.providerOrder.isEmpty() && routing.selectedProvider.isBlank() -> TtsFailureKind.PROVIDER_REQUIRED
            else -> null
        }
        if (kind != null) throw TtsException(TtsFailure(TtsOperation.PROVIDERS, target, "", kind))
        return target
    }
}

/** Same codec for activity results and recreation. Incomplete Only drafts are deliberately valid. */
object TtsPickerCodec {
    fun encode(target: TtsTarget): String = JsonObject().apply {
        addProperty("endpointId", target.endpointId)
        addProperty("modelId", target.modelId)
        addProperty("sourceId", target.sourceId)
        addProperty("voiceId", target.voiceId)
        add("routing", JsonObject().apply {
            addProperty("mode", target.routing.mode.name)
            addProperty("selectedProvider", target.routing.selectedProvider)
            addProperty("allowFallbacks", target.routing.allowFallbacks)
            add("providerOrder", JsonArray().apply { target.routing.providerOrder.forEach { add(it) } })
        })
    }.toString()
    fun decode(value: String): TtsTarget {
        val root = objectBody(value)
        val routing = root.get("routing").objectOrNull() ?: error("Missing routing")
        val mode = TtsRoutingMode.entries.single { it.name == routing.text("mode") }
        val order = routing.getAsJsonArrayOrNull("providerOrder") ?: error("Missing provider order")
        val ids = order.map { it.asString }.also { require(it.all(String::isNotBlank) && it.distinct() == it) }
        return TtsTarget(
            endpointId = root.get("endpointId").asString,
            modelId = root.get("modelId").asString,
            routing = TtsRoutingSettings(mode, routing.get("selectedProvider").asString, ids,
                routing.bool("allowFallbacks") ?: error("Missing fallback choice")),
            sourceId = root.text("sourceId"), voiceId = root.text("voiceId")
        )
    }
    fun encodeSort(sort: TtsProviderSort): String = JsonObject().apply {
        addProperty("alphaAToZ", sort.alphaAToZ)
        addProperty("price", sort.price.name)
        addProperty("latency", sort.latency.name)
        addProperty("uptime", sort.uptime.name)
    }.toString()
    fun decodeSort(value: String): TtsProviderSort {
        val obj = objectBody(value)
        return TtsProviderSort(obj.bool("alphaAToZ") ?: error("Missing alphabetical order"),
            SortDirection.valueOf(obj.get("price").asString), SortDirection.valueOf(obj.get("latency").asString),
            SortDirection.valueOf(obj.get("uptime").asString))
    }
}

object TtsPickerPresentation {
    fun models(catalog: TtsModelCatalog, query: String): List<TtsModel> = catalog.models.filter {
        it.capabilityEvidence.isNotEmpty() &&
            (it.id.contains(query.trim(), true) || it.name.contains(query.trim(), true))
    }
    fun mark(value: Boolean?): String = when (value) { true -> "X"; false -> ""; null -> "?" }
}
