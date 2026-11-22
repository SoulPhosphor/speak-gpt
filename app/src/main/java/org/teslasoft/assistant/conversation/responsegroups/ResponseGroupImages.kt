package org.teslasoft.assistant.conversation.responsegroups

import org.teslasoft.assistant.imagegen.GeneratedImageMetadata

/** Image receipts remain immutable; an edited reminder belongs to that exact response version. */
object ResponseGroupImages {
    fun image(version: ResponseVersion): String? {
        val original = version.attempts.asReversed().firstNotNullOfOrNull { it.imageResult } ?: return null
        return if (version.imageSummaryEdited == null) original else
            GeneratedImageMetadata.fromJson(original)?.withSummaryEdited(version.imageSummaryEdited)?.toJson() ?: original
    }

    fun metadata(message: Map<String, Any?>): List<GeneratedImageMetadata> {
        val group = (ResponseGroupCodec.read(message) as? ResponseGroupRead.Valid)?.group ?: return emptyList()
        return group.slots.flatMap { it.versions }.flatMap { version -> version.attempts.mapNotNull {
            GeneratedImageMetadata.fromJson(it.imageResult)
        } }.distinctBy { it.imageId }
    }
}
