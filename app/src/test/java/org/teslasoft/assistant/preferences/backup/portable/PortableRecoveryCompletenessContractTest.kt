/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableRecoveryCompletenessContractTest {
    @Test
    fun normalWriterIncludesCompanionRoleplayAndModelSettings() {
        val writer = source("PortableRecoveryWriter.kt")
        assertTrue(writer.contains("CompanionBackupExporter.buildBackupZip"))
        assertTrue(writer.contains("TYPE_COMPANION_ROLEPLAY_ARCHIVE"))
        assertTrue(writer.contains("ModelEndpointPortableBackup.write"))
        assertTrue(writer.contains("TYPE_MODEL_ENDPOINT_SETTINGS"))
    }

    @Test
    fun modelSettingsExporterNeverReadsTheEndpointApiKey() {
        val exporter = source("ModelEndpointPortableBackup.kt")
        assertFalse(exporter.contains("endpoint.apiKey"))
        assertFalse(exporter.contains("_api_key"))
        assertFalse(exporter.contains("EncryptedPreferences"))
    }

    @Test
    fun modelSettingsRestoreUsesOnlyTheNonSecretEndpointSeam() {
        val restore = source("ModelEndpointPortableRestore.kt")
        assertTrue(restore.contains("setApiEndpointDefinition"))
        assertTrue(restore.contains("deleteApiEndpointDefinition"))
        assertFalse(restore.contains("setApiEndpoint("))
        assertFalse(restore.contains("deleteApiEndpoint("))
        assertFalse(restore.contains("EncryptedPreferences"))
    }

    private fun source(name: String): String = mainRoot().resolve(name).readText()

    private fun mainRoot(): File = listOf(
        File("src/main/java/org/teslasoft/assistant/preferences/backup/portable"),
        File("app/src/main/java/org/teslasoft/assistant/preferences/backup/portable")
    ).firstOrNull(File::isDirectory) ?: error("portable source root unavailable")
}
