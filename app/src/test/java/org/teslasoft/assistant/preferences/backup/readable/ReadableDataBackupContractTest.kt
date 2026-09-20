package org.teslasoft.assistant.preferences.backup.readable

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableDataBackupContractTest {
    @Test
    fun everySupportedReadableGroupIsSelectable() {
        assertEquals(
            setOf(
                "CHATS", "GENERATED_IMAGES", "IDENTITIES", "PROFILE_IMAGES",
                "MODEL_SETTINGS", "MEMORIES", "MODEL_RULES", "LOREBOOKS"
            ),
            ReadableDataBackup.Category.entries.mapTo(HashSet()) { it.name }
        )
    }

    @Test
    fun readableWriterDoesNotReachSecretsOrDatabaseKeys() {
        val code = codeOnly(source("preferences/backup/readable/ReadableDataBackup.kt"))
        for (forbidden in listOf(
            "DatabaseKeys", "RecoveryKeyStore", "RecoveryCode", "EncryptedPreferences",
            "getApiKey", "setApiKey"
        )) assertFalse("Readable export must not reach $forbidden", code.contains(forbidden))
        assertTrue(code.contains("ModelEndpointPortableBackup.write"))
    }

    @Test
    fun backupScreenExposesTheContentSelector() {
        val activity = source("ui/activities/MemoryBackupRestoreActivity.kt")
        assertTrue(activity.contains("pickReadableContent"))
        assertTrue(activity.contains("ReadableDataBackup.build"))
    }

    private fun codeOnly(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")

    private fun source(relative: String): String = mainRoot().resolve(relative).readText()

    private fun mainRoot(): File = listOf(
        File("src/main/java/org/teslasoft/assistant"),
        File("app/src/main/java/org/teslasoft/assistant")
    ).firstOrNull(File::isDirectory) ?: error("main source root unavailable")
}
