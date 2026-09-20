package org.teslasoft.assistant.preferences.chatdeletion

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract guard for the final SQLCipher transaction, not only the UI policy. */
class ImageLockDeletionVetoTest {
    @Test fun explicitDeletionReReadsOwnershipAndLockInsideTheCatalogTransaction() {
        val source = source(
            "src/main/java/org/teslasoft/assistant/preferences/generatedimages/" +
                "GeneratedImageCatalogStore.kt"
        )
        val operation = source.substringAfter("fun tombstoneUnlockedOwned(")
            .substringBefore("fun deleteAssetIfUnreferenced(")

        assertOrdered(
            operation,
            "db.beginTransaction()",
            "findActiveTx(db, imageId)",
            "current.originChatId !in chatIds",
            "current.locked",
            "TABLE_TOMBSTONES",
            "db.delete(TABLE_IMAGES"
        )
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("$relative not found from ${File(".").absolutePath}")
    }

    private fun assertOrdered(source: String, vararg markers: String) {
        val positions = markers.map(source::indexOf)
        assertTrue("Missing marker in ${markers.toList()}", positions.all { it >= 0 })
        assertTrue(positions.zipWithNext().all { it.first < it.second })
    }
}
