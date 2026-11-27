package org.teslasoft.assistant.ui.chat

import org.teslasoft.assistant.util.ProcessActivityTracker
import org.teslasoft.assistant.util.ProcessExitSnapshot.Activity
import java.util.concurrent.atomic.AtomicBoolean

/** One chat's diagnostic state, without a screen reference, chat ID, or content. */
internal class ChatExitActivity(
    private val report: (Any, Set<Activity>) -> Unit = ProcessActivityTracker::update
) {
    private var closed = false
    private var voice = false
    private var recording = false
    private var transcribing = false
    private var reading = false
    private var handsFreeReading = false
    private var preparing = false
    private var generations = 0

    @Synchronized fun open() = publish()
    @Synchronized fun voice(value: Boolean) { voice = value; publish() }
    @Synchronized fun recording(value: Boolean) { recording = value; publish() }
    @Synchronized fun transcribing(value: Boolean) { transcribing = value; publish() }
    @Synchronized fun reading(value: Boolean) { reading = value; publish() }
    @Synchronized fun handsFreeReading(value: Boolean) { handsFreeReading = value; publish() }
    @Synchronized fun preparing(value: Boolean) { preparing = value; publish() }
    @Synchronized fun beginGeneration(): AutoCloseable {
        generations++
        publish()
        val released = AtomicBoolean()
        return AutoCloseable { if (released.compareAndSet(false, true)) endGeneration() }
    }
    @Synchronized private fun endGeneration() { generations--; publish() }
    @Synchronized fun close() { closed = true; report(this, emptySet()) }

    private fun publish() {
        if (closed) return
        val work = linkedSetOf(Activity.CHAT)
        if (recording) work += if (voice) Activity.VOICE_LISTENING else Activity.LISTENING
        if (transcribing) work += if (voice) Activity.VOICE_TRANSCRIBING else Activity.TRANSCRIBING
        if (generations > 0 || preparing) work += if (voice) Activity.VOICE_GENERATING else Activity.GENERATING
        if (reading || handsFreeReading) work += if (voice) Activity.VOICE_READING else Activity.READING
        if (voice && work.size == 1) work += Activity.VOICE
        report(this, work)
    }
}
