package org.teslasoft.assistant.tts.api

import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.tts.SavedTtsSource
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings

/** Complete, non-secret identity; callers must not substitute their active chat profile. */
data class TtsTarget(
    val endpointId: String,
    val modelId: String = "",
    val routing: TtsRoutingSettings = TtsRoutingSettings(),
    val sourceId: String? = null,
    val voiceId: String? = null
)

/** An immutable snapshot for one attempt. Never put its credentials in extras or caches. */
class TtsEndpoint private constructor(
    val id: String, val label: String, val baseUrl: String, internal val apiKey: String,
    val authType: String, val speechPath: String, val discoveryPath: String,
    val connectSeconds: Int, val responseSeconds: Int, val openRouter: Boolean
) {
    companion object {
        fun from(profile: ApiEndpointObject) = TtsEndpoint(
            profile.id, profile.label, profile.host, profile.apiKey, profile.authType,
            ApiEndpointObject.normalizedSpeechEndpoint(profile.speechEndpoint),
            profile.providerDiscoveryPath.ifBlank { ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH },
            profile.connectTimeoutSeconds, profile.responseTimeoutSeconds,
            profile.hasOpenRouterCatalogAuthority()
        )
    }
}

class ResolvedTtsSource(val target: TtsTarget, val endpoint: TtsEndpoint)

class TtsSourceResolver(
    private val sources: () -> Result<List<SavedTtsSource>>,
    private val endpoints: () -> List<ApiEndpointObject>
) {
    fun saved(sourceId: String, voiceId: String): Result<ResolvedTtsSource> = runCatching {
        val rows = sources().getOrElse {
            throw TtsException(TtsFailure(TtsOperation.SPEECH, TtsTarget("", sourceId = sourceId),
                "", TtsFailureKind.STORAGE))
        }
        val source = rows.singleOrNull { it.sourceId == sourceId }
            ?: throw TtsException(TtsFailure(TtsOperation.SPEECH,
                TtsTarget("", sourceId = sourceId), "", TtsFailureKind.SOURCE_MISSING))
        resolve(TtsTarget(source.endpointId, source.modelId, source.routing, source.sourceId, voiceId))
    }

    fun resolve(target: TtsTarget): ResolvedTtsSource {
        // getApiEndpoint() can supply a default for a missing ID. Never use that fallback here.
        val profile = endpoints().singleOrNull { it.id == target.endpointId }
            ?: throw TtsException(TtsFailure(TtsOperation.SPEECH, target, "", TtsFailureKind.PROFILE_MISSING))
        return ResolvedTtsSource(target.copy(routing = target.routing.copy(
            providerOrder = target.routing.providerOrder.toList())), TtsEndpoint.from(profile))
    }

}
