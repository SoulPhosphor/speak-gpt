package org.teslasoft.assistant.imagegen

import org.teslasoft.assistant.providers.ModelBrowserEntry

/** The image picker never falls back to an unverified text-model catalog. */
internal object ImageModelPickerCatalog {
    fun entries(models: List<ImageModelMetadata>): List<ModelBrowserEntry> = models
        .filter { it.knownImageOutput && it.hasDisplayableOutput() }
        .distinctBy { it.id }
        .map { model ->
            ModelBrowserEntry(
                id = model.id, name = model.publishedName.orEmpty(), created = null,
                isFree = false, providers = emptyList(), advertisesReasoning = false
            )
        }
}
