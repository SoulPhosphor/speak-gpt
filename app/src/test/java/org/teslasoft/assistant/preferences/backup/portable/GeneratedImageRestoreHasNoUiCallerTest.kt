package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the Phase 10 replacement engine unreachable until Phase 11.1 has an
 * owner-approved category transaction and screen. Startup recovery remains
 * reachable because it only resolves a journal an approved future caller may
 * create. */
class GeneratedImageRestoreHasNoUiCallerTest {

    @Test
    fun declarationExistsAndNoProductionSourceCallsRestore() {
        val sources = mainSourceFiles()
        val manager = sources.single { it.name == "GeneratedImagePortableRestoreManager.kt" }
        assertTrue(Regex("fun\\s+restore\\s*\\(").containsMatchIn(codeOnly(manager.readText())))

        val offenders = sources
            .filter { it.name != manager.name }
            .filter {
                Regex(
                    "GeneratedImagePortableRestoreManager\\s*\\.\\s*restore\\s*\\("
                ).containsMatchIn(codeOnly(it.readText()))
            }
            .map { it.path }

        assertEquals(emptyList<String>(), offenders)
    }

    private fun codeOnly(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")

    private fun mainSourceFiles(): List<File> = mainJavaRoot().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun mainJavaRoot(): File {
        val userDir = System.getProperty("user.dir")
        return listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(userDir, "src/main/java"),
            File(userDir, "app/src/main/java")
        ).firstOrNull { it.isDirectory } ?: error("main source root unavailable")
    }
}
