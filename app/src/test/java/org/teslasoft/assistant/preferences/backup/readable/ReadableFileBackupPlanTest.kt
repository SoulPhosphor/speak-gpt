package org.teslasoft.assistant.preferences.backup.readable

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReadableFileBackupPlanTest {
    @Test fun categoriesStartOffAndOldCombinedSelectionDoesNotEnableNewCategories() {
        assertTrue(ReadableBackupCategory.fromStored(null).isEmpty())
        assertTrue(ReadableBackupCategory.fromStored(setOf("IDENTITIES")).isEmpty())
        assertEquals(setOf(ReadableBackupCategory.CHATS), ReadableBackupCategory.fromStored(setOf("CHATS", "unknown")))
    }

    @Test fun fullBackupIncludesEverySelectedFileEvenWhenAlreadySaved() = withFiles { root ->
        val sources = listOf(file(root, "picture.jpg", "image"), file(root, "settings.json", "settings"))
        val saved = plan(root, sources, emptyMap(), true)
        assertEquals(2, plan(root, sources, saved.fingerprints, true).files.size)
    }

    @Test fun incrementalFindsChangesInImagesAndDataAndSkipsUnchangedFiles() = withFiles { root ->
        val image = file(root, "picture.jpg", "image")
        val data = file(root, "settings.json", "settings")
        val sources = listOf(image, data)
        val saved = plan(root, sources, emptyMap(), true)
        assertTrue(plan(root, sources, saved.fingerprints, false).files.isEmpty())
        image.second.writeText("changed image")
        assertEquals(listOf("picture.jpg"), plan(root, sources, saved.fingerprints, false).files.map { it.first })
        data.second.writeText("changed settings")
        val added = file(root, "new.jpg", "new image")
        assertEquals(setOf("picture.jpg", "settings.json", "new.jpg"),
            plan(root, sources + added, saved.fingerprints, false).files.map { it.first }.toSet())
    }

    @Test fun aRenameCountsAsANewFileAndNamesAreNotStoredInBaseline() = withFiles { root ->
        val original = file(root, "private chat title.txt", "content")
        val saved = plan(root, listOf(original), emptyMap(), true)
        assertTrue(saved.fingerprints.keys.all { it.matches(Regex("[a-f0-9]{64}")) })
        val renamed = "new private title.txt" to original.second
        assertEquals(listOf(renamed.first), plan(root, listOf(renamed), saved.fingerprints, false).files.map { it.first })
    }

    @Test fun planningDoesNotAdvanceBaselineAndPreservesOtherSelections() = withFiles { root ->
        val prior = mapOf("previously-saved-unselected-file" to "old-hash")
        val copy = prior.toMap()
        val sources = listOf(file(root, "prompt.json", "prompt"))
        val candidate = plan(root, sources, prior, false)
        assertEquals(copy, prior)
        assertEquals("old-hash", candidate.fingerprints["previously-saved-unselected-file"])
        assertEquals(1, plan(root, sources, prior, false).files.size)
        assertTrue(plan(root, sources, candidate.fingerprints, false).files.isEmpty())
    }

    @Test fun selectedBytesRemainStableWhileSourceChanges() = withFiles { root ->
        val source = file(root, "picture.jpg", "saved bytes")
        val candidate = plan(root, listOf(source), emptyMap(), true)
        source.second.writeText("later bytes")
        assertEquals("saved bytes", candidate.files.single().second.readText())
    }

    @Test fun independentIdentityCategoriesCannotLeakOtherTables() {
        val rows = listOf("companions", "companion_name_history", "user_personas", "roleplay_characters", "worlds")
            .associateWith { listOf(mapOf("text" to "$it private content")) }
        assertEquals(setOf("companions", "companion_name_history"), ReadableIdentitySelection.tables(rows, ReadableBackupCategory.COMPANIONS).keys)
        assertEquals(setOf("user_personas"), ReadableIdentitySelection.tables(rows, ReadableBackupCategory.GLAMOURS).keys)
        assertEquals(setOf("roleplay_characters", "worlds"), ReadableIdentitySelection.tables(rows, ReadableBackupCategory.ROLEPLAY).keys)
        assertTrue(ReadableIdentitySelection.tables(rows, ReadableBackupCategory.ACTIVATION_PROMPTS).isEmpty())
    }

    private fun file(root: File, name: String, content: String): Pair<String, File> =
        name to File(root, "source-${root.listFiles()!!.size}").apply { writeText(content) }
    private fun plan(root: File, sources: List<Pair<String, File>>, baseline: Map<String, String>, all: Boolean): ReadableFileBackupPlan.Plan =
        ReadableFileBackupPlan.prepare(sources, Files.createTempDirectory(root.toPath(), "plan").toFile(), baseline, all)
    private fun withFiles(block: (File) -> Unit) {
        val root = Files.createTempDirectory("readable-plan-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
