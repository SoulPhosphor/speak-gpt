package org.teslasoft.assistant.preferences.backup.portable

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.profileimages.ProfileImageRecord

/**
 * Pure-JVM coverage for the Avatar/Profile Images restore participant, which
 * the direct portable "Profile Image Database" restore drives in Replace mode
 * (Phase 12.4, item 5) and which enforces post-apply reference closure
 * (item 7). The precomputed-plan constructor lets the participant run without
 * parsing a real catalog database, so no Android runtime is needed.
 */
class ProfileImageRestoreParticipantTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun stagesThenAppliesAndRollsBackFromStagedCurrentSnapshot() {
        val oldBytes = jpeg(1)
        val newBytes = jpeg(2)
        val oldRecord = record(oldBytes)
        val newRecord = record(newBytes)
        val current = snapshot(mapOf(oldRecord to oldBytes))
        val incoming = prepared(mapOf(newRecord to newBytes))
        val planned = ProfileImageCategoryPlanner.plan(
            current.records, incoming.records, PortableRestoreMode.MERGE
        ) as ProfileImageCategoryPlanner.Result.Ready
        val backend = FakeBackend(current)
        val participant = ProfileImageRestoreParticipant(
            emptyList(),
            PortableRestoreMode.MERGE,
            emptySet(),
            tmp.newFolder("profile_staging"),
            backend,
            precomputed = ProfileImageRestoreParticipant.PreparedPlan(
                current, incoming, planned.records, planned.report
            )
        )

        assertTrue(participant.validate())
        assertTrue(participant.stage())
        assertTrue(participant.apply())
        assertEquals(
            setOf(oldRecord.hash, newRecord.hash),
            backend.value.records.map { it.hash }.toSet()
        )
        assertTrue(participant.rollback())
        assertEquals(listOf(oldRecord.hash), backend.value.records.map { it.hash })
    }

    @Test
    fun applyFailsWhenPostApplyClosureIsNotSatisfied() {
        val oldBytes = jpeg(3)
        val newBytes = jpeg(4)
        val oldRecord = record(oldBytes)
        val newRecord = record(newBytes)
        val current = snapshot(mapOf(oldRecord to oldBytes))
        val incoming = prepared(mapOf(newRecord to newBytes))
        val planned = ProfileImageCategoryPlanner.plan(
            current.records, incoming.records, PortableRestoreMode.REPLACE
        ) as ProfileImageCategoryPlanner.Result.Ready
        val backend = FakeBackend(current).apply { closureResult = false }
        val participant = ProfileImageRestoreParticipant(
            emptyList(),
            PortableRestoreMode.REPLACE,
            emptySet(),
            tmp.newFolder("profile_closure_staging"),
            backend,
            precomputed = ProfileImageRestoreParticipant.PreparedPlan(
                current, incoming, planned.records, planned.report
            )
        )

        assertTrue(participant.validate())
        assertTrue(participant.stage())
        assertFalse(participant.apply())
    }

    @Test
    fun closurePredicateRequiresEqualHashesAndValidAssets() {
        val a = record(jpeg(5))
        val b = record(jpeg(6))
        val fileA = tmp.newFile("a.jpg")
        val fileB = tmp.newFile("b.jpg")
        val live = ProfileImageRestoreParticipant.Snapshot(
            listOf(a, b), mapOf(a.hash to fileA, b.hash to fileB)
        )
        assertTrue(
            ProfileImageRestoreParticipant.closureHolds(live, listOf(a, b)) { _, _ -> true }
        )
        // The live catalog does not match the desired hash set.
        assertFalse(
            ProfileImageRestoreParticipant.closureHolds(live, listOf(a)) { _, _ -> true }
        )
        // A row whose file does not validate breaks closure.
        assertFalse(
            ProfileImageRestoreParticipant.closureHolds(live, listOf(a, b)) { hash, _ -> hash == a.hash }
        )
    }

    private fun snapshot(entries: Map<ProfileImageRecord, ByteArray>): ProfileImageRestoreParticipant.Snapshot {
        val assets = LinkedHashMap<String, File>()
        entries.forEach { (record, bytes) ->
            assets[record.hash] = tmp.newFile("current-${record.hash.take(8)}.jpg").apply { writeBytes(bytes) }
        }
        return ProfileImageRestoreParticipant.Snapshot(entries.keys.toList(), assets)
    }

    private fun prepared(entries: Map<ProfileImageRecord, ByteArray>): ProfileImagePortableRestoreManager.Prepared {
        val assets = LinkedHashMap<String, File>()
        entries.forEach { (record, bytes) ->
            assets[record.hash] = tmp.newFile("incoming-${record.hash.take(8)}.jpg").apply { writeBytes(bytes) }
        }
        return ProfileImagePortableRestoreManager.Prepared(entries.keys.toList(), assets)
    }

    private fun record(bytes: ByteArray) = ProfileImageRecord(sha256(bytes), 1L)

    private fun jpeg(marker: Int) = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(),
        marker.toByte(), 0x01, 0x02,
        0xff.toByte(), 0xd9.toByte()
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private class FakeBackend(
        var value: ProfileImageRestoreParticipant.Snapshot,
        private val wasProvisioned: Boolean = true
    ) : ProfileImageRestoreParticipant.Backend {
        var closureResult = true

        override fun snapshot(): ProfileImageRestoreParticipant.Snapshot = value

        override fun replace(records: List<ProfileImageRecord>, assets: Map<String, File>): Boolean {
            value = ProfileImageRestoreParticipant.Snapshot(records, assets)
            return true
        }

        override fun restoreOriginal(records: List<ProfileImageRecord>, assets: Map<String, File>): Boolean =
            replace(records, assets)

        override fun wasProvisionedBeforeStage(): Boolean = wasProvisioned

        override fun verifyClosure(desired: List<ProfileImageRecord>): Boolean = closureResult

        override fun removeProvisionedStore(): Boolean = true
    }
}
