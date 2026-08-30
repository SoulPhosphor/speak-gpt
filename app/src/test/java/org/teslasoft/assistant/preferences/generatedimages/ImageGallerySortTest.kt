package org.teslasoft.assistant.preferences.generatedimages

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGallerySortTest {
    @Test fun newestAndOldestUseCreatedAtWithDeterministicIdentityTieBreak() {
        val records = listOf(record("b", 10), record("a", 10), record("c", 5))
        assertEquals(
            listOf("b", "a", "c"),
            ImageGallerySorter.sort(records, ImageGallerySortOrder.NEWEST_TO_OLDEST).map { it.imageId }
        )
        assertEquals(
            listOf("c", "a", "b"),
            ImageGallerySorter.sort(records, ImageGallerySortOrder.OLDEST_TO_NEWEST).map { it.imageId }
        )
    }

    private fun record(id: String, createdAt: Long) = GeneratedImageCatalogRecord(
        id, "hash-$id", "$id.png", "image/png", 1, 1, createdAt, null, null, null
    )
}
