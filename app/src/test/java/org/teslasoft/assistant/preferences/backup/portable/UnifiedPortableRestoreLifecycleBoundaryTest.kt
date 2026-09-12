/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedPortableRestoreLifecycleBoundaryTest {
    @Test
    fun activityOnlyObservesAndSubmitsDecisions() {
        val activity = source("ui/activities/MemoryBackupRestoreActivity.kt")
        assertTrue(activity.contains("UnifiedPortableRestoreCoordinator.Observer"))
        assertTrue(activity.contains("RestoreForegroundService.startUnified"))
        assertFalse(activity.contains("UnifiedPortableRestore.build("))
        assertFalse(activity.contains("UnifiedPortableRestore.execute("))
        assertFalse(activity.contains("PortablePackage.decodeWith"))
        assertFalse(activity.contains("PortablePackage.validateAndExtract"))
    }

    @Test
    fun foregroundServiceOwnsCoordinatorWorkAndMandatoryRestart() {
        val service = source("service/RestoreForegroundService.kt")
        assertTrue(service.contains("UnifiedPortableRestoreCoordinator.advance"))
        assertTrue(service.contains("Executors.newSingleThreadExecutor"))
        assertTrue(service.contains("Intent.FLAG_ACTIVITY_CLEAR_TASK"))
        assertTrue(service.contains("Runtime.getRuntime().exit(0)"))
    }

    @Test
    fun restoredStateCannotBeOpenedOrSavedByTheOldProcess() {
        val chat = source("ui/activities/ChatActivity.kt")
        val chatStore = source("preferences/ChatPreferences.kt")
        val application = source("app/MainApplication.kt")
        val launcher = source("ui/activities/MainActivity.kt")
        assertTrue(chat.contains("PortableRestoreProcessGate.blocksCurrentProcess"))
        assertTrue(chat.contains("PortableRestoreProcessGate.awaitStartupRecovery"))
        assertTrue(chatStore.contains("PortableRestoreProcessGate.blocksCurrentProcess"))
        assertTrue(application.contains("beginStartupRecoveryIfNeeded"))
        assertTrue(application.contains("finishStartupRecovery"))
        assertTrue(launcher.contains("awaitStartupRecovery"))
    }

    @Test
    fun terminalReportPrecedesCleanupAndRestart() {
        val coordinator = source("preferences/backup/portable/UnifiedPortableRestoreCoordinator.kt")
        val transaction = source("preferences/backup/portable/SelectedCategoryRestoreTransaction.kt")
        val apply = coordinator.substringAfter("private fun apply(context: Context)")
        assertTrue(apply.contains("beforeCleanup ="))
        assertTrue(apply.indexOf("PortableRestoreOutcomeStore.persist(context, success)") <
            apply.indexOf("cleanup(current, keepTransactionForRecovery = false)"))
        val terminalHook = transaction.indexOf("safeCall(beforeCleanup)")
        assertTrue(terminalHook >= 0)
        assertTrue(terminalHook < transaction.indexOf("cleanup(participants)", terminalHook))
    }

    @Test
    fun generationFenceRunsImmediatelyBeforeApply() {
        val coordinator = source("preferences/backup/portable/UnifiedPortableRestoreCoordinator.kt")
        val apply = coordinator.substringAfter("private fun apply(context: Context)")
            .substringBefore("private fun terminal(")
        val fence = apply.indexOf("UnifiedPortableRestore.sourceGenerationsMatch")
        val execute = apply.indexOf("UnifiedPortableRestore.execute(")
        assertTrue(fence >= 0)
        assertTrue(execute > fence)
        assertTrue(apply.substring(fence, execute).contains("current.next = Next.PREFLIGHT"))
    }

    @Test
    fun activityRecreationKeepsCoordinatorStagingAndTerminalOutcome() {
        val activity = source("ui/activities/MemoryBackupRestoreActivity.kt")
        val coordinator = source("preferences/backup/portable/UnifiedPortableRestoreCoordinator.kt")
        val outcome = source("ui/PortableRestoreOutcomeFlow.kt")
        val destroy = activity.substringAfter("override fun onDestroy()")
            .substringBefore("override fun onAttachedToWindow()")
        assertFalse(destroy.contains("UnifiedPortableRestoreCoordinator.cancel()"))
        assertTrue(coordinator.contains("val decodeRoot: File"))
        assertTrue(coordinator.contains("var transactionRoot: File?"))
        assertTrue(outcome.contains("onActivityDestroyed"))
        assertTrue(outcome.contains("must not acknowledge its outcome"))
    }

    @Test
    fun activityDoesNotPerformRecoveryOrFolderReadsOnMainThread() {
        val activity = source("ui/activities/MemoryBackupRestoreActivity.kt")
        val begin = activity.substringAfter("private fun beginPortableRestore()")
            .substringBefore("private fun inspectPortableRestore(")
        assertFalse(begin.contains("recoverPending("))
        val recoveryFlow = source("ui/PortableRestoreRecoveryFlow.kt")
        assertFalse(recoveryFlow.contains("Thread {"))
        assertTrue(recoveryFlow.contains("recoverPendingAsync"))
        val folderRead = activity.substringAfter("private fun currentFolderNameAlreadyUsed")
            .substringBefore("private fun showPortableConfirmation")
        assertTrue(activity.contains("Called only from [runOffThread]"))
        assertTrue(folderRead.contains("ChatNavigationRepository.get"))
        assertTrue(activity.contains("runOffThread {\n                            val duplicate = currentFolderNameAlreadyUsed(name)"))
    }

    private fun source(relative: String): String = mainRoot().resolve(relative).readText()

    private fun mainRoot(): File = listOf(
        File("src/main/java/org/teslasoft/assistant"),
        File("app/src/main/java/org/teslasoft/assistant")
    ).firstOrNull(File::isDirectory) ?: error("main source root unavailable")
}
