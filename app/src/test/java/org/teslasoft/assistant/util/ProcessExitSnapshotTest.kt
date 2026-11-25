package org.teslasoft.assistant.util

import org.junit.Assert.*
import org.junit.Test
import android.app.ActivityManager.RunningAppProcessInfo
import org.teslasoft.assistant.util.ProcessExitSnapshot.Activity

class ProcessExitSnapshotTest {
    @Test fun historicalForegroundServicesDoNotPretendAScreenWasOpen() {
        assertEquals("App Position: Unknown\nActivity: Unknown",
            ProcessActivityTracker.exitLines(null, RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertEquals("App Position: Foreground\nActivity: Unknown",
            ProcessActivityTracker.exitLines(null, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals("App Position: Background\nActivity: Unknown",
            ProcessActivityTracker.exitLines(null, RunningAppProcessInfo.IMPORTANCE_CACHED))
        val prior = ProcessExitSnapshot(false, setOf(Activity.VOICE_READING))
        assertEquals(prior.lines(), ProcessActivityTracker.exitLines(prior.encode(),
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertEquals(prior.copy(foreground = true).lines(), ProcessActivityTracker.exitLines(prior.encode(),
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertEquals(prior.lines(), ProcessActivityTracker.exitLines(prior.copy(foreground = true).encode(),
            RunningAppProcessInfo.IMPORTANCE_CACHED))
    }
    @Test fun foregroundAndBackgroundWorkAreIndependentAndSurviveEncoding() {
        for (foreground in listOf(true, false)) {
            val state = ProcessExitSnapshot(foreground, setOf(Activity.VOICE_LISTENING, Activity.BACKUP, Activity.MEMORY_SCAN))
            assertEquals(state, ProcessExitSnapshot.decode(state.encode()))
            assertTrue(state.encode().size <= 128)
            assertEquals("App Position: ${if (foreground) "Foreground" else "Background"}\n" +
                "Activity: Voice Chat Active — Listening for Speech; Creating Backup; Scanning Memories", state.lines())
        }
    }

    @Test fun olderOrUnrecognizedDataDoesNotInventActivity() {
        listOf(null, byteArrayOf(), "PX2|F|1".toByteArray(), "PX1|X|1".toByteArray(),
            "PX1|F|-1".toByteArray(), "PX1|F|4096".toByteArray(), ByteArray(129)).forEach {
            assertNull(ProcessExitSnapshot.decode(it))
        }
    }

    @Test fun overlappingTasksDoNotClearEachOtherAndANewProcessStartsEmpty() {
        val ledger = ProcessActivityLedger()
        val first = Any(); val second = Any()
        ledger.update(first, setOf(Activity.BACKUP))
        ledger.update(second, setOf(Activity.BACKUP, Activity.MEMORY_SCAN))
        ledger.update(first, emptySet())
        assertEquals(setOf(Activity.BACKUP, Activity.MEMORY_SCAN), ledger.snapshot().activities)
        ledger.update(second, emptySet())
        assertTrue(ledger.snapshot().activities.isEmpty())
        assertEquals("App Position: Background\nActivity: No Tracked Activity", ProcessActivityLedger().snapshot().lines())
    }

    @Test fun chatOpenDoesNotHideConcurrentWork() {
        assertEquals("App Position: Foreground\nActivity: Creating Backup",
            ProcessExitSnapshot(true, setOf(Activity.CHAT, Activity.BACKUP)).lines())
        assertEquals("App Position: Foreground\nActivity: Chat Open",
            ProcessExitSnapshot(true, setOf(Activity.CHAT)).lines())
    }
}
