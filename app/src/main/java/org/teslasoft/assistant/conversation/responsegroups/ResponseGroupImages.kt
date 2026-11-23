package org.teslasoft.assistant.conversation.responsegroups

import org.teslasoft.assistant.imagegen.GeneratedImageMetadata

/** Image receipts remain immutable; an edited reminder belongs to that exact response version. */
object ResponseGroupImages {
    fun image(version: ResponseVersion): String? {
        val original = version.attempts.asReversed().firstNotNullOfOrNull { it.imageResult } ?: return null
        if (version.imageSummaryEdited == null && version.imageSummary == null) return original
        val meta = GeneratedImageMetadata.fromJson(original) ?: return original
        return meta.withImageSummary(version.imageSummary ?: meta.imageSummary)
            .withSummaryEdited(version.imageSummaryEdited ?: meta.summaryEdited).toJson()
    }

    /** All versions qualify independently, including images behind an unselected tab. */
    fun pendingSummaries(message: Map<String, Any?>): List<GeneratedImageMetadata> {
        val group = (ResponseGroupCodec.read(message) as? ResponseGroupRead.Valid)?.group ?: return emptyList()
        return group.slots.flatMap { it.versions }.mapNotNull { version ->
            GeneratedImageMetadata.fromJson(image(version))?.takeIf {
                it.status == GeneratedImageMetadata.STATUS_COMPLETE && it.prompt.isNotBlank() && it.effectiveSummary() == null
            }
        }.distinctBy { it.imageId }
    }

    /** Recheck the latest version after the model wait; never overwrite an edit or a newer summary. */
    fun summarize(group: ResponseGroup, imageId: String, prompt: String, summary: String): ResponseGroup =
        group.copy(slots = group.slots.map { slot -> slot.copy(versions = slot.versions.map { version ->
            val meta = GeneratedImageMetadata.fromJson(image(version))
            if (summary.isBlank() || meta == null || meta.imageId != imageId || meta.prompt != prompt ||
                meta.status != GeneratedImageMetadata.STATUS_COMPLETE || meta.effectiveSummary() != null) version
            else version.copy(imageSummary = summary)
        }) })

    fun metadata(message: Map<String, Any?>): List<GeneratedImageMetadata> {
        val group = (ResponseGroupCodec.read(message) as? ResponseGroupRead.Valid)?.group ?: return emptyList()
        return group.slots.flatMap { it.versions }.flatMap { version -> version.attempts.mapNotNull {
            GeneratedImageMetadata.fromJson(it.imageResult)
        } }.distinctBy { it.imageId }
    }
}
