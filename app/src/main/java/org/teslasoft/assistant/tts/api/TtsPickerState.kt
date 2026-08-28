package org.teslasoft.assistant.tts.api

import com.google.gson.Gson
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings

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
    private val gson = Gson()
    fun encode(target: TtsTarget): String = gson.toJson(target)
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
    fun encodeSort(sort: TtsProviderSort): String = gson.toJson(sort)
    fun decodeSort(value: String): TtsProviderSort = gson.fromJson(value, TtsProviderSort::class.java)
}

object TtsPickerPresentation {
    fun models(catalog: TtsModelCatalog, query: String): List<TtsModel> = catalog.models.filter {
        it.capabilityEvidence.isNotEmpty() &&
            (it.id.contains(query.trim(), true) || it.name.contains(query.trim(), true))
    }
    fun mark(value: Boolean?): String = when (value) { true -> "X"; false -> ""; null -> "?" }
}
