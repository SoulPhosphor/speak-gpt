package org.teslasoft.assistant.imagegen

import org.junit.Assert.*
import org.junit.Test

class ImageModelPickerCatalogTest {
    @Test fun onlyConfirmedDisplayableImageModelsAppear() {
        val entries = ImageModelPickerCatalog.entries(listOf(
            ImageModelMetadata("invented-image", publishedName = "Invented Image"),
            ImageModelMetadata("invented-unverified", knownImageOutput = false),
            ImageModelMetadata("invented-unsupported", parameters = listOf(
                ImageParameter("output_format", ImageParameterType.ENUM, values = listOf("unsupported-format"))
            )),
            ImageModelMetadata("invented-required-format", requiresExplicitOutputFormat = true),
            ImageModelMetadata("invented-image")
        ))
        assertEquals(listOf("invented-image"), entries.map { it.id })
        assertTrue(entries.single().matchesModel("Invented Image"))
        assertFalse(entries.single().isFree)
        assertFalse(entries.single().advertisesReasoning)
    }

    @Test fun emptyOrUnverifiedCatalogDoesNotSupplyTextModels() {
        assertTrue(ImageModelPickerCatalog.entries(emptyList()).isEmpty())
        assertTrue(ImageModelPickerCatalog.entries(listOf(
            ImageModelMetadata("invented-text", knownImageOutput = false)
        )).isEmpty())
    }
}
