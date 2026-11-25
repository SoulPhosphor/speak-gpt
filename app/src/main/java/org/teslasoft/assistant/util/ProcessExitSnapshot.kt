package org.teslasoft.assistant.util

/** Content-free diagnostic state retained by Android with this process's exit record. */
internal data class ProcessExitSnapshot(val foreground: Boolean, val activities: Set<Activity>) {
    // Explicit bits form a persisted format. Keep existing assignments stable.
    enum class Activity(val bit: Int, val display: String) {
        CHAT(1, "Chat Open"),
        VOICE(2, "Voice Chat Active"),
        LISTENING(4, "Listening for Speech"),
        TRANSCRIBING(8, "Transcribing Voice Input"),
        GENERATING(16, "Generating Text Reply"),
        READING(32, "Reading Reply Aloud"),
        VOICE_LISTENING(64, "Voice Chat Active — Listening for Speech"),
        VOICE_TRANSCRIBING(128, "Voice Chat Active — Transcribing Voice Input"),
        VOICE_GENERATING(256, "Voice Chat Active — Generating Reply"),
        VOICE_READING(512, "Voice Chat Active — Reading Reply Aloud"),
        BACKUP(1024, "Creating Backup"),
        MEMORY_SCAN(2048, "Scanning Memories")
    }

    fun encode(): ByteArray = "PX1|${if (foreground) 'F' else 'B'}|${activities.fold(0) { mask, a -> mask or a.bit }}"
        .toByteArray(Charsets.US_ASCII)

    fun lines(): String {
        val work = activities.filter { it != Activity.CHAT }
        val shown = if (work.isNotEmpty()) work else activities.toList()
        val activity = shown.sortedBy { it.bit }.joinToString("; ") { it.display }
            .ifEmpty { if (foreground) "App Open" else "No Tracked Activity" }
        return "App Position: ${if (foreground) "Foreground" else "Background"}\nActivity: $activity"
    }

    companion object {
        fun decode(bytes: ByteArray?): ProcessExitSnapshot? {
            if (bytes == null || bytes.size > 128) return null
            val parts = bytes.toString(Charsets.US_ASCII).split('|')
            if (parts.size != 3 || parts[0] != "PX1" || parts[1] !in setOf("F", "B")) return null
            val mask = parts[2].toIntOrNull() ?: return null
            val known = Activity.entries.fold(0) { all, a -> all or a.bit }
            if (mask < 0 || mask and known.inv() != 0) return null
            return ProcessExitSnapshot(parts[1] == "F", Activity.entries.filter { mask and it.bit != 0 }.toSet())
        }
    }
}

/** Caller supplies synchronization; overlapping tasks never clear one another. */
internal class ProcessActivityLedger {
    private val owners = java.util.IdentityHashMap<Any, Set<ProcessExitSnapshot.Activity>>()
    var foreground = false
    fun update(owner: Any, activities: Set<ProcessExitSnapshot.Activity>) {
        if (activities.isEmpty()) owners.remove(owner) else owners[owner] = activities.toSet()
    }
    fun snapshot() = ProcessExitSnapshot(foreground, owners.values.flatten().toSet())
}
