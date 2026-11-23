package org.teslasoft.assistant.conversation.responsegroups

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.imagegen.GeneratedImageMetadata
import org.teslasoft.assistant.ui.chat.parallel.ParallelResponseSelection
import java.util.UUID

class ResponseGroupImageSummariesTest {
    private fun fixture(): Pair<Map<String, Any>, ResponseGroup> {
        val rows: List<HashMap<String, Any>> = Gson().fromJson(
            javaClass.classLoader!!.getResource("response-groups/history.json")!!.readText(),
            object : TypeToken<List<HashMap<String, Any>>>() {}.type)
        val row = rows[2]
        val original = (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group
        val group = original.copy(slots = original.slots.mapIndexed { i, slot ->
            slot.copy(versions = slot.versions.mapIndexed { j, version ->
                val id = UUID.randomUUID().toString()
                val image = GeneratedImageMetadata(id, "a".repeat(64), "image/png", 32, 32,
                    "fixture", "fixture", "prompt-$i-$j", "description", 100L, "complete", null).toJson()
                version.copy(attempts = version.attempts.mapIndexed { k, attempt ->
                    if (k != version.attempts.lastIndex) attempt else attempt.copy(imageJobId = id, imageResult = image)
                })
            })
        })
        return ResponseGroupEdits.install(row, group) to group
    }
    private fun row(group: ResponseGroup) = ResponseGroupEdits.install(
        mapOf("message_id" to group.assistantMessageId, "isBot" to true), group)

    @Test fun everySlotAndPriorVersionGetsItsOwnSummaryWithoutChangingReceiptsOrSelections() {
        val (row, group) = fixture()
        val pending = ResponseGroupImages.pendingSummaries(row)
        assertEquals(8, pending.size)
        var changed = group
        pending.forEach { image -> changed = ResponseGroupImages.summarize(changed, image.imageId, image.prompt, "short ${image.prompt}") }
        ResponseGroupEdits.validateTransition(group, changed)
        val restored = (ResponseGroupCodec.read(row(changed)) as ResponseGroupRead.Valid).group
        assertEquals(changed, restored)
        assertEquals(group.input, changed.input)
        assertEquals(group.primarySlotId, changed.primarySlotId)
        assertEquals(group.foregroundSlotId, changed.foregroundSlotId)
        group.slots.zip(changed.slots).forEach { (before, after) ->
            assertEquals(before.assignment, after.assignment)
            assertEquals(before.canonicalVersionId, after.canonicalVersionId)
            assertEquals(before.foregroundVersionId, after.foregroundVersionId)
            before.versions.zip(after.versions).forEach { (old, new) ->
                assertEquals(old.fields, new.fields)
                assertEquals(old.attempts, new.attempts)
                val metadata = GeneratedImageMetadata.fromJson(ResponseGroupImages.image(new))!!
                assertEquals("short ${metadata.prompt}", metadata.effectiveSummary())
            }
        }
        assertTrue(ResponseGroupImages.pendingSummaries(row(changed)).isEmpty())
        assertEquals(ResponseGroupEdits.usage(group), ResponseGroupEdits.usage(changed))
        val canonical = GeneratedImageMetadata.fromJson(ParallelResponseSelection.canonicalImage(row(changed)))!!
        assertEquals("short prompt-0-0", canonical.effectiveSummary())
        val slot = changed.slots[3]
        val display = ParallelResponseSelection.display(row(ResponseGroupEdits.browse(changed, slot.slotId, slot.versions.last().versionId)))
        assertEquals("short prompt-3-1", GeneratedImageMetadata.fromJson(display[GeneratedImageMetadata.KEY] as String)!!.effectiveSummary())
    }

    @Test fun delayedSummaryRechecksEditsExistingSummaryPromptAndImageIdentity() {
        val (row, group) = fixture()
        val target = ResponseGroupImages.pendingSummaries(row).last()
        fun edited(text: String?) = group.copy(slots = group.slots.mapIndexed { i, slot ->
            if (i != 3) slot else slot.copy(versions = slot.versions.mapIndexed { j, version ->
                if (j != 1) version else version.copy(imageSummaryEdited = text)
            })
        })
        val userEdited = edited("user reminder")
        assertEquals(userEdited, ResponseGroupImages.summarize(userEdited, target.imageId, target.prompt, "late summary"))
        assertEquals(group, ResponseGroupImages.summarize(group, target.imageId, "changed prompt", "late summary"))
        assertEquals(group, ResponseGroupImages.summarize(group, UUID.randomUUID().toString(), target.prompt, "late summary"))
        assertEquals(group, ResponseGroupImages.summarize(group, target.imageId, target.prompt, " "))
        val first = ResponseGroupImages.summarize(group, target.imageId, target.prompt, "first summary")
        assertEquals(first, ResponseGroupImages.summarize(first, target.imageId, target.prompt, "second summary"))
        val editedAfter = first.copy(slots = first.slots.mapIndexed { i, slot ->
            if (i != 3) slot else slot.copy(versions = slot.versions.mapIndexed { j, v -> if (j != 1) v else v.copy(imageSummaryEdited = "my reminder") })
        })
        assertEquals("my reminder", GeneratedImageMetadata.fromJson(ResponseGroupImages.image(editedAfter.slots[3].versions[1]))!!.effectiveSummary())
    }

    @Test fun olderEnvelopesAndOrdinaryRowsStayReadableWithoutSummaryFields() {
        val (row, group) = fixture()
        assertFalse((row[ResponseGroupCodec.KEY] as String).contains("imageSummary"))
        assertEquals(group, (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group)
        assertTrue(ResponseGroupImages.pendingSummaries(mapOf("isBot" to true, "message" to "ordinary reply")).isEmpty())
    }
}
