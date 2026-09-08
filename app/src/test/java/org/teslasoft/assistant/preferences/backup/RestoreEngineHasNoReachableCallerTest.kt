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
 * Phase 9 single-approved-caller boundary, enforced mechanically.
 *
 * [ChatRestoreManager.restoreFromArchive] performs a wholesale, journaled
 * REPLACEMENT of encrypted chat storage, and the plan's P1 risk is that a
 * restore reached from a stray UI, debug action, or import path would run
 * before the user has any way to understand or confirm it. The owner has
 * approved the Phase 9 reachable restore — Restore From Backup (chats-only,
 * replace-only), driven by [org.teslasoft.assistant.service.RestoreForegroundService]
 * — and Phase 11's selected-category portable restore participant.
 *
 * This test makes that the build invariant: only the approved callers (the
 * engine itself and the restore service) may name `restoreFromArchive`; if any
 * OTHER production source file does, ordinary unit CI fails here — before a
 * second, unreviewed restore path can ship. The coordinator is reached only
 * from `restoreFromArchive`, so guarding the engine entry guards the whole path.
 *
 * `resumeIfPending` is deliberately NOT guarded: it is the startup finisher for
 * an already-journaled swap and does nothing unless a restore that was itself
 * started elsewhere left a journal. With no reachable `restoreFromArchive`, it
 * can never observe a pending swap, so it is inert without being unreachable.
 */
class RestoreEngineHasNoReachableCallerTest {

    private val declaringFile = "ChatRestoreManager.kt"
    private val restoreEntryPoint = "restoreFromArchive"

    /**
     * The engine declares it; [RestoreForegroundService] is the single
     * owner-approved callers. Phase 11 adds the transaction participant that
     * stages exact current/desired archives and reaches the same engine only
     * after the unified confirmation. Every OTHER production file stays away
     * from the engine so a stray edit cannot open an unreviewed restore path.
     */
    private val approvedCallers = setOf(
        "ChatRestoreManager.kt",
        "RestoreForegroundService.kt",
        "PortableChatRestoreCoordinator.kt",
        "ChatRestoreParticipant.kt"
    )

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
    fun onlyTheApprovedCallerReachesTheRestoreReplacementEngine() {
        val offenders = mainSourceFiles()
            .filter { it.name !in approvedCallers }
            .filter { codeOnly(it.readText()).contains(restoreEntryPoint) }
            .map { it.path }

        assertEquals(
            "Only the approved restore caller (${approvedCallers.joinToString()}) may reach " +
                "the whole-chat-set restore replacement engine. These other production files " +
                "reach `$restoreEntryPoint`:\n" + offenders.joinToString("\n"),
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
