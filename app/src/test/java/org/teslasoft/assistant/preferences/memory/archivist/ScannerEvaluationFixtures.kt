package org.teslasoft.assistant.preferences.memory.archivist

import org.json.JSONObject
import org.teslasoft.assistant.preferences.memory.scan.ScanSourceRow

/** Synthetic inputs. Expected outcomes are never supplied to the extracting model. */
internal object ScannerEvaluationFixtures {
    data class Case(val id: String, val texts: List<String>,
        val existing: List<ArchivistExistingMemory> = emptyList(),
        val working: List<ArchivistEarlierCandidate> = emptyList(),
        val companion: String? = null, val targets: List<ArchivistTarget> = emptyList()) {
        val scene get() = ArchivistSceneContext(companion, null, null, null, null)
        val rows get() = texts.mapIndexed { index, text -> ScanSourceRow(
            id, "$id-${index + 1}", (index + 1).toLong(), if (index % 2 == 0) "user" else "assistant",
            0, text.length, text, "2026-01-10T12:00:00Z", "fixture-model", companionId = companion) }
        val protocol get() = ArchivistRuntimeProtocol.create(scene, existing, targets, working, texts.joinToString("\n"))
        fun request(): JSONObject = JSONObject().put("system", ArchivistPrompt.withRuntimeProtocol(
            ArchivistPrompt.SYSTEM, listOf("fact" to "Fact", "decision" to "Decision"), protocol
        )).put("user", ArchivistPrompt.userMessage(id, companion, rows).text)
    }
    private val telescope = "The user joined the Cedar Observatory volunteer team and chose Saturday mornings because weekday evenings conflict with choir."
    private fun saved(status: String = "pending") = listOf(ArchivistExistingMemory("observatory", telescope, "real_life", emptyList(), "Fact", status))
    private fun working() = listOf(ArchivistEarlierCandidate(telescope, "real_life", emptyList(), "Fact", emptyList(), draftId = 1))
    private fun longChat(correctionAt: Int) = (0 until 80).map { i -> when (i) {
        0 -> "I agreed to lead the Cedar Observatory volunteers on Saturday mornings because weekday evenings conflict with choir."
        correctionAt -> "Correction to the observatory arrangement: Saturday is no longer available. I have now confirmed Sunday mornings; choir is still the reason I cannot do weekday evenings. Please remember Sunday as the current schedule."
        10 -> "I finally ordered a brass focusing knob for the telescope restoration; it fits the 1978 instrument."
        24 -> "My cousin Ivo is coming on the spring train trip. We chose May rather than April so the coastal line will be running."
        50 -> "The club agreed to keep the telescope's original blue finish instead of repainting it black, to preserve its history."
        else -> if (i % 2 == 0) "For today's small talk, cloud pattern number $i was interesting; no new decision or lasting change." else "That makes sense; we can continue discussing the plans."
    } }
    private val targetB = listOf(ArchivistTarget("comp-b", "companion", "Companion B", "active"))
    val cases = listOf(
        Case("short", listOf("I joined the Cedar Observatory volunteers. I chose Saturday mornings because weekday evenings conflict with choir.", "Saturday mornings, with choir protected.")),
        Case("late-correction", longChat(78)), Case("middle-correction", longChat(40)),
        Case("working-correction", listOf("The observatory confirmed Sunday mornings instead of Saturday. Weekday evenings still conflict with choir.", "Sunday mornings is confirmed."), working = working()),
        Case("retelling", listOf("As I mentioned before, I volunteer at Cedar Observatory on Saturday mornings since choir occupies my weekday evenings.", "Yes, the same arrangement."), existing = saved()),
        Case("new-decision", listOf("The observatory rota has changed: I have confirmed Sunday mornings from February, rather than Saturday. Choir still rules out weekday evenings.", "Sunday mornings starting in February."), existing = saved()),
        Case("archived-retelling", listOf("I was remembering the old observatory volunteer arrangement, Saturdays because of choir. Nothing has changed; I am just recalling it.", "A recollection of that past arrangement."), existing = saved("archived")),
        Case("archived-detail", listOf("Something I never mentioned about that old observatory role: the team gave me the brass gate key engraved C7 when I joined. I kept it as a memento.", "That is a new detail about the old role."), existing = saved("archived")),
        Case("archived-return", listOf("I have rejoined the Cedar Observatory volunteer team and confirmed my old Saturday morning shift, beginning next month.", "You are returning to the old role."), existing = saved("archived")),
        Case("email-correction", listOf("Here is Ivo's email: 'I accepted the Thursday night station shift.' That is Ivo speaking, not me. My own shift is still Tuesday.", "Ivo Thursday, you Tuesday.", "Correction: Ivo just clarified his email had a typo. His accepted shift is Friday night, not Thursday. My shift is unchanged.")),
        Case("long-tail", listOf("I had planned Saturday mornings at Cedar Observatory.", "We can keep that in mind.",
            "For this temporary mechanical check the telescope dial moves back and forth without any new lasting event ".repeat(240) + " THE ACTUAL UPDATE: I confirmed Sunday mornings at Cedar Observatory instead of Saturday because choir still occupies weekday evenings. That is the final arrangement."), working = working(), companion = "comp-b", targets = targetB),
        Case("different-listener", listOf("I told another companion this already: I joined Cedar Observatory for Saturday mornings because choir fills my weekday evenings.", "The listener changed, not the arrangement."), existing = saved(), companion = "comp-b", targets = targetB),
        Case("companion-instruction", listOf("For you specifically, Companion B, when we plan our observatory outings together, remind me to bring the brass C7 key. This is our outing routine, not a rule for my other companions.", "I will use that reminder for our outings."), existing = saved(), companion = "comp-b", targets = targetB)
    )
}
