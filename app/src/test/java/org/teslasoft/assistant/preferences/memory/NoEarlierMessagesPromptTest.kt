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

package org.teslasoft.assistant.preferences.memory

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner ruling (Feature 1A): re-enabling "Archive this chat" silently
 * re-queues the paused backlog — the app never shows an "Include Earlier
 * Messages?" prompt or any equivalent choice. This scan pins that no such
 * wording exists in any string resource or source file, so the prompt
 * cannot quietly return.
 */
class NoEarlierMessagesPromptTest {

    @Test
    fun noIncludeEarlierMessagesWordingAnywhere() {
        // Gradle runs unit tests from the module directory; fall back to the
        // repository root for IDE runs.
        val srcMain = listOf(File("src/main"), File("app/src/main"))
            .firstOrNull { it.isDirectory }
            ?: error("src/main not found from working directory " + File(".").absolutePath)

        val offenders = srcMain.walkTopDown()
            .filter { it.isFile && (it.extension == "xml" || it.extension == "kt" || it.extension == "java") }
            .filter { file ->
                val text = file.readText()
                text.contains("include earlier", ignoreCase = true) ||
                    text.contains("earlier messages", ignoreCase = true)
            }
            .map { it.path }
            .toList()

        assertTrue(
            "Earlier-messages prompt wording found in: $offenders",
            offenders.isEmpty()
        )
    }
}
