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

package org.teslasoft.assistant.preferences.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9 engine-only boundary, enforced mechanically.
 *
 * The safe whole-chat-set replacement engine and its coordinator
 * ([ChatSetReplacementCoordinator]) are now built, but they must stay
 * UNREACHABLE until the approved restore UI (and its owner-approved recovery
 * wording) ship. [ChatRestoreManager.restoreFromArchive] performs a wholesale,
 * journaled REPLACEMENT of encrypted chat storage, and the plan's P1 risk is
 * that a restore reached from UI, a debug action, or an import path would run
 * before the user has any way to understand or confirm it.
 *
 * This test makes the absence of a caller a build invariant: if any production
 * source file other than [ChatRestoreManager] itself names `restoreFromArchive`,
 * ordinary unit CI fails here — before a reachable restore can ship. The
 * coordinator is reached only from `restoreFromArchive`, so guarding the engine
 * entry keeps the whole path unreachable. The plan's Phase 9 exit gate requires
 * exactly this proof while no restore UI exists.
 *
 * `resumeIfPending` is deliberately NOT guarded: it is the startup finisher for
 * an already-journaled swap and does nothing unless a restore that was itself
 * started elsewhere left a journal. With no reachable `restoreFromArchive`, it
 * can never observe a pending swap, so it is inert without being unreachable.
 */
class RestoreEngineHasNoReachableCallerTest {

    private val declaringFile = "ChatRestoreManager.kt"
    private val restoreEntryPoint = "restoreFromArchive"

    @Test
    fun theDeclarationStillExistsSoThisGuardCannotRotSilently() {
        val manager = mainSourceFiles().single { it.name == declaringFile }
        val code = codeOnly(manager.readText())
        assertTrue(
            "$declaringFile no longer declares `fun $restoreEntryPoint`; if the restore " +
                "entry point was renamed, update this guard to the new name — do not delete it.",
            Regex("fun\\s+$restoreEntryPoint\\s*\\(").containsMatchIn(code)
        )
    }

    @Test
    fun noProductionSourceReachesTheRestoreReplacementEngine() {
        val offenders = mainSourceFiles()
            .filter { it.name != declaringFile }
            .filter { codeOnly(it.readText()).contains(restoreEntryPoint) }
            .map { it.path }

        assertEquals(
            "No restore UI has shipped, so nothing may call the whole-chat-set restore " +
                "replacement engine. These production files reach `$restoreEntryPoint`:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    /** Strips block and line comments so a "must not appear" check cannot be
     *  tripped by documentation that names the guarded symbol — the same
     *  convention the converter-boundary guard uses. A `//` inside a `://`
     *  (a URL) is left alone. */
    private fun codeOnly(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")

    private fun mainSourceFiles(): List<File> {
        val root = mainJavaRoot()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun mainJavaRoot(): File {
        val userDir = System.getProperty("user.dir")
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(userDir, "src/main/java"),
            File(userDir, "app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Could not locate the main source root from $userDir; " +
                    "checked: ${candidates.joinToString { it.path }}"
            )
    }
}
