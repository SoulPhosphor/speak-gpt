package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableBackupExclusionContractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun derivedSearchFilesKeysSidecarsAndJournalsCannotEnterPackage() {
        val payload = tmp.newFile("payload").apply { writeText("derived") }
        val forbidden = listOf(
            "chat_search.db",
            "chat_search.db-wal",
            "chat_search.db-shm",
            "chat_search.key",
            "chat_search_journal"
        )

        for (name in forbidden) {
            val out = File(tmp.root, "${name.replace('.', '_')}.zip")
            val failure = runCatching {
                PortablePackage.buildInnerZip(
                    listOf(
                        PortablePackage.Artifact(
                            entryName = name,
                            type = PortablePackage.TYPE_SQLCIPHER_DB,
                            file = payload,
                            databaseKeyHex = "00",
                            keySemantics = PortablePackage.KEY_SEMANTICS_PASSPHRASE,
                            schemaVersion = null
                        )
                    ),
                    "2026-09-08T00:00:00Z",
                    out
                )
            }.exceptionOrNull()

            assertTrue("$name must be excluded", failure is IllegalArgumentException)
        }
    }
}
