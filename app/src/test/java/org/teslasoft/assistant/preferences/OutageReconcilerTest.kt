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

package org.teslasoft.assistant.preferences

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outage-recovery merge at every storage state and interruption
 * boundary (Round 4). The invariants under test: unreadable encrypted data
 * is never touched, outage data is never silently discarded, a conflict
 * always preserves BOTH sides, every path is restartable and idempotent
 * (a re-run after a death at any boundary converges without loss or
 * duplication), and the outage copy is deleted only after verification.
 */
class OutageReconcilerTest {

    /**
     * In-memory storage double. Encrypted files live in [encrypted]
     * (a name in [locked] simulates a file the Keystore cannot open);
     * outage files in [outage]; durable markers in [markers]; preserved
     * copies in [snapshots]. Failure flags simulate each fallible step,
     * and [ops] records the order of destructive operations so ordering
     * guarantees can be asserted.
     */
    private class FakeFiles : OutageReconciler.Files {
        val locked = mutableSetOf<String>()
        val encrypted = mutableMapOf<String, MutableMap<String, Any?>>()
        val outage = mutableMapOf<String, MutableMap<String, Any?>>()
        val markers = mutableMapOf<String, String>()
        val snapshots = mutableMapOf<String, Map<String, Any?>>()
        val logs = mutableListOf<String>()
        val ops = mutableListOf<String>()

        var failPut = false
        var failSnapshot = false
        var failDelete = false
        var dropWrites = false // putEncrypted lies: reports success, writes nothing
        private var snapCounter = 0

        override fun outageNames(): List<String> = outage.keys.toList()

        override fun encryptedOpens(name: String): Boolean = name !in locked

        override fun readEncrypted(name: String): Map<String, Any?>? =
            if (name in locked) null else (encrypted[name]?.toMap() ?: emptyMap())

        override fun readOutage(name: String): Map<String, Any?> =
            outage[name]?.toMap() ?: emptyMap()

        override fun putEncrypted(name: String, entries: Map<String, Any?>): Boolean {
            if (failPut) return false
            if (name in locked) return false
            ops.add("put:$name")
            if (!dropWrites) encrypted.getOrPut(name) { mutableMapOf() }.putAll(entries)
            return true
        }

        override fun snapshot(name: String, entries: Map<String, Any?>): String? {
            if (failSnapshot) return null
            val id = "snap_${name}_${snapCounter++}"
            snapshots[id] = entries.toMap()
            ops.add("snapshot:$name")
            return id
        }

        override fun deleteOutage(name: String): Boolean {
            if (failDelete) return false
            ops.add("deleteOutage:$name")
            outage.remove(name)
            return true
        }

        override fun snapshotMarker(name: String): String? = markers[name]

        override fun setSnapshotMarker(name: String, snapshotId: String?) {
            if (snapshotId == null) markers.remove(name) else markers[name] = snapshotId
        }

        override fun log(message: String) { logs.add(message) }
    }

    private fun chatListJson(vararg entries: Pair<String, String>): String {
        val arr = JSONArray()
        for ((id, name) in entries) {
            arr.put(JSONObject().put("id", id).put("name", name).put("pinned", "false"))
        }
        return arr.toString()
    }

    private fun idsIn(json: String): List<String> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    // ---- trivial states ----------------------------------------------------

    @Test fun noOutageFiles_doesNothing() {
        val f = FakeFiles()
        val r = OutageReconciler.reconcile(f)
        assertTrue(r.merged.isEmpty() && r.deferred.isEmpty() && r.conflictsPreserved.isEmpty())
        assertTrue(f.ops.isEmpty())
    }

    @Test fun emptyOutageFile_isDeletedWithoutWrites() {
        val f = FakeFiles()
        f.outage["chat_1"] = mutableMapOf("chat" to "[]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.merged)
        assertFalse(f.outage.containsKey("chat_1"))
        assertTrue(f.ops.none { it.startsWith("put") || it.startsWith("snapshot") })
    }

    // ---- still locked ------------------------------------------------------

    @Test fun stillLocked_deferredAndUntouched() {
        val f = FakeFiles()
        f.locked.add("chat_1")
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":1}]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.deferred)
        // The outage copy survives for a later start; nothing was written,
        // nothing snapshotted, nothing deleted.
        assertEquals("[{\"m\":1}]", f.outage["chat_1"]!!["chat"])
        assertTrue(f.ops.isEmpty())
    }

    @Test fun mixedLockState_recoversPerFile() {
        // Only SOME encrypted files fail: the readable one merges, the
        // locked one waits. One failure is never treated as proof that all
        // chats are lost.
        val f = FakeFiles()
        f.locked.add("chat_locked")
        f.outage["chat_locked"] = mutableMapOf("chat" to "[{\"m\":1}]")
        f.outage["chat_ok"] = mutableMapOf("chat" to "[{\"m\":2}]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_ok"), r.merged)
        assertEquals(listOf("chat_locked"), r.deferred)
        assertEquals("[{\"m\":2}]", f.encrypted["chat_ok"]!!["chat"])
        assertTrue(f.outage.containsKey("chat_locked"))
    }

    // ---- clean merges ------------------------------------------------------

    @Test fun outageOnlyData_movesIntoAbsentEncrypted() {
        val f = FakeFiles()
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":1}]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.merged)
        assertTrue(r.conflictsPreserved.isEmpty())
        assertEquals("[{\"m\":1}]", f.encrypted["chat_1"]!!["chat"])
        assertFalse(f.outage.containsKey("chat_1"))
        assertTrue(f.snapshots.isEmpty())
    }

    @Test fun encryptedEmptyJsonValue_isFillableNotAConflict() {
        // "[]" on the encrypted side is the "no messages yet" placeholder
        // addChat writes — real outage data must flow into it, not be
        // flagged as a conflict against it.
        val f = FakeFiles()
        f.encrypted["chat_1"] = mutableMapOf("chat" to "[]")
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":1}]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.merged)
        assertTrue(r.conflictsPreserved.isEmpty())
        assertEquals("[{\"m\":1}]", f.encrypted["chat_1"]!!["chat"])
    }

    @Test fun identicalBothSides_dropsOutageWithoutSnapshot() {
        val f = FakeFiles()
        f.encrypted["settings.1"] = mutableMapOf("model" to "glm-4", "temp" to 0.7f)
        f.outage["settings.1"] = mutableMapOf("model" to "glm-4", "temp" to 0.7f)
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("settings.1"), r.merged)
        assertTrue(f.snapshots.isEmpty())
        assertEquals("glm-4", f.encrypted["settings.1"]!!["model"])
        assertFalse(f.outage.containsKey("settings.1"))
    }

    @Test fun settingsMerge_copiesAbsentKeysOnly() {
        val f = FakeFiles()
        f.encrypted["settings.1"] = mutableMapOf("model" to "glm-4")
        f.outage["settings.1"] = mutableMapOf("model" to "glm-4", "voice" to "en-US")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("settings.1"), r.merged)
        assertEquals("en-US", f.encrypted["settings.1"]!!["voice"])
        assertTrue(f.snapshots.isEmpty())
    }

    // ---- conflicts: both sides preserved, never silently chosen ------------

    @Test fun conflictingHistories_preserveBothSides() {
        val f = FakeFiles()
        f.encrypted["chat_1"] = mutableMapOf("chat" to "[{\"m\":\"enc\"}]")
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":\"out\"}]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.merged)
        assertEquals(listOf("chat_1"), r.conflictsPreserved)
        // Encrypted side untouched — never overwritten by "newer" data.
        assertEquals("[{\"m\":\"enc\"}]", f.encrypted["chat_1"]!!["chat"])
        // Outage side preserved whole in a snapshot — never discarded.
        assertEquals(1, f.snapshots.size)
        assertEquals("[{\"m\":\"out\"}]", f.snapshots.values.first()["chat"])
        // Marker cleaned up, outage file gone.
        assertNull(f.markers["chat_1"])
        assertFalse(f.outage.containsKey("chat_1"))
    }

    @Test fun conflictingSettings_encryptedStaysPresented_outageSnapshotted() {
        val f = FakeFiles()
        f.encrypted["settings.1"] = mutableMapOf("model" to "glm-4")
        f.outage["settings.1"] = mutableMapOf("model" to "gpt-x", "voice" to "en-US")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("settings.1"), r.conflictsPreserved)
        assertEquals("glm-4", f.encrypted["settings.1"]!!["model"]) // kept
        assertEquals("en-US", f.encrypted["settings.1"]!!["voice"]) // absent key copied
        assertEquals("gpt-x", f.snapshots.values.first()["model"]) // preserved
    }

    // ---- failure at each step defers without loss ---------------------------

    @Test fun snapshotFailure_defersEverythingUntouched() {
        val f = FakeFiles()
        f.failSnapshot = true
        f.encrypted["chat_1"] = mutableMapOf("chat" to "[{\"m\":\"enc\"}]")
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":\"out\"}]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.deferred)
        assertTrue(f.outage.containsKey("chat_1"))
        assertEquals("[{\"m\":\"enc\"}]", f.encrypted["chat_1"]!!["chat"])
    }

    @Test fun putFailure_defersOutageIntact() {
        val f = FakeFiles()
        f.failPut = true
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":1}]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.deferred)
        assertTrue(f.outage.containsKey("chat_1"))
    }

    @Test fun silentlyDroppedWrite_failsVerificationAndDefers() {
        // The write "succeeds" but the data never lands (torn storage):
        // the verify step must catch it and keep the outage copy.
        val f = FakeFiles()
        f.dropWrites = true
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":1}]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.deferred)
        assertTrue(f.outage.containsKey("chat_1"))
    }

    @Test fun deleteFailure_defersButDataIsAlreadyMerged_rerunConverges() {
        val f = FakeFiles()
        f.failDelete = true
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":1}]")
        assertEquals(listOf("chat_1"), OutageReconciler.reconcile(f).deferred)
        assertEquals("[{\"m\":1}]", f.encrypted["chat_1"]!!["chat"])
        // Next start: delete works, nothing is duplicated or re-copied.
        f.failDelete = false
        val r2 = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r2.merged)
        assertFalse(f.outage.containsKey("chat_1"))
        assertEquals("[{\"m\":1}]", f.encrypted["chat_1"]!!["chat"])
    }

    // ---- interruption boundaries: re-runs are idempotent ---------------------

    @Test fun deathAfterSnapshotAndMarker_rerunDoesNotDuplicateSnapshot() {
        // Simulates a process death after the snapshot was written and the
        // marker recorded, before the outage file was removed: the re-run
        // must trust the durable marker instead of snapshotting again.
        val f = FakeFiles()
        f.encrypted["chat_1"] = mutableMapOf("chat" to "[{\"m\":\"enc\"}]")
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":\"out\"}]")
        f.markers["chat_1"] = "snap_prior"
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.merged)
        assertEquals(listOf("chat_1"), r.conflictsPreserved)
        assertTrue(f.snapshots.isEmpty()) // no second snapshot
        assertNull(f.markers["chat_1"])
        assertFalse(f.outage.containsKey("chat_1"))
    }

    @Test fun deathAfterPartialCopy_rerunConvergesWithoutClobber() {
        // The copy landed but the process died before verification/cleanup.
        // On re-run the key is present-and-equal, so nothing is rewritten
        // and the outage file is simply retired.
        val f = FakeFiles()
        f.encrypted["chat_1"] = mutableMapOf("chat" to "[{\"m\":1}]")
        f.outage["chat_1"] = mutableMapOf("chat" to "[{\"m\":1}]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_1"), r.merged)
        assertTrue(f.ops.none { it == "put:chat_1" })
        assertFalse(f.outage.containsKey("chat_1"))
    }

    @Test fun failedRunThenHealthyRun_endsWithExactlyOneSnapshot() {
        // First run: snapshot succeeds but the copy step fails (put failure
        // after a conflict was preserved). Second run must reuse the marker.
        val f = FakeFiles()
        f.encrypted["settings.1"] = mutableMapOf("model" to "a")
        f.outage["settings.1"] = mutableMapOf("model" to "b", "voice" to "v")
        f.failPut = true
        assertEquals(listOf("settings.1"), OutageReconciler.reconcile(f).deferred)
        assertEquals(1, f.snapshots.size)
        f.failPut = false
        val r2 = OutageReconciler.reconcile(f)
        assertEquals(listOf("settings.1"), r2.merged)
        assertEquals(1, f.snapshots.size) // still exactly one
        assertEquals("a", f.encrypted["settings.1"]!!["model"])
        assertEquals("v", f.encrypted["settings.1"]!!["voice"])
    }

    // ---- chat list ------------------------------------------------------------

    @Test fun chatList_outageOnlyChatsAppended() {
        val f = FakeFiles()
        f.encrypted["chat_list"] = mutableMapOf("data" to chatListJson("idA" to "Chat A"))
        f.encrypted["chat_new"] = mutableMapOf("chat" to "[{\"m\":1}]")
        f.outage["chat_list"] = mutableMapOf("data" to chatListJson("idNew" to "Outage chat"))
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_list"), r.merged)
        assertEquals(listOf("idA", "idNew"), idsIn(f.encrypted["chat_list"]!!["data"] as String))
        assertFalse(f.outage.containsKey("chat_list"))
        assertTrue(f.snapshots.isEmpty())
    }

    @Test fun chatList_sameIdBothSides_keepsEncryptedAndPreservesOutageList() {
        val f = FakeFiles()
        f.encrypted["chat_list"] = mutableMapOf("data" to chatListJson("idA" to "Chat A"))
        f.outage["chat_list"] = mutableMapOf(
            "data" to chatListJson("idA" to "Chat A", "idB" to "Outage chat")
        )
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_list"), r.merged)
        assertEquals(listOf("chat_list"), r.conflictsPreserved)
        // idA is present once (the encrypted entry), idB was appended.
        assertEquals(listOf("idA", "idB"), idsIn(f.encrypted["chat_list"]!!["data"] as String))
        // The whole outage list is preserved for the owner to inspect.
        assertEquals(1, f.snapshots.size)
    }

    @Test fun chatList_entryWithoutId_isPreservedNotMerged() {
        val f = FakeFiles()
        f.encrypted["chat_list"] = mutableMapOf("data" to "[]")
        val arr = JSONArray().put(JSONObject().put("name", "No id"))
        f.outage["chat_list"] = mutableMapOf("data" to arr.toString())
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_list"), r.conflictsPreserved)
        assertEquals(1, f.snapshots.size)
        assertEquals("[]", f.encrypted["chat_list"]!!["data"]) // untouched
    }

    @Test fun chatList_corruptOutageList_snapshottedNeverMerged() {
        val f = FakeFiles()
        f.encrypted["chat_list"] = mutableMapOf("data" to chatListJson("idA" to "Chat A"))
        f.outage["chat_list"] = mutableMapOf("data" to "{not json]")
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_list"), r.merged)
        assertEquals(listOf("chat_list"), r.conflictsPreserved)
        assertEquals(1, f.snapshots.size)
        assertEquals("{not json]", f.snapshots.values.first()["data"])
        // Encrypted list untouched by the corrupt outage copy.
        assertEquals(listOf("idA"), idsIn(f.encrypted["chat_list"]!!["data"] as String))
    }

    @Test fun chatList_corruptEncryptedList_deferredForRepairPath() {
        // A corrupt ENCRYPTED list belongs to preserveCorruptData (the read
        // path); merging outage chats into garbage would entangle two
        // recovery mechanisms. Defer, keep the outage file.
        val f = FakeFiles()
        f.encrypted["chat_list"] = mutableMapOf("data" to "{not json]")
        f.outage["chat_list"] = mutableMapOf("data" to chatListJson("idB" to "Outage chat"))
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("chat_list"), r.deferred)
        assertTrue(f.outage.containsKey("chat_list"))
        assertEquals("{not json]", f.encrypted["chat_list"]!!["data"])
    }

    @Test fun chatList_isProcessedAfterChatFiles() {
        // Histories land before their list entries become visible, so a
        // death mid-run can leave an invisible orphan history (merged next
        // start) but never a listed chat with missing content.
        val f = FakeFiles()
        f.outage["chat_list"] = mutableMapOf("data" to chatListJson("id1" to "C1"))
        f.outage["chat_id1"] = mutableMapOf("chat" to "[{\"m\":1}]")
        OutageReconciler.reconcile(f)
        val listDelete = f.ops.indexOf("deleteOutage:chat_list")
        val chatDelete = f.ops.indexOf("deleteOutage:chat_id1")
        assertTrue(chatDelete in 0 until listDelete)
    }

    // ---- rename journal ---------------------------------------------------------

    @Test fun renameJournal_unionMerge_neverDropsAnEntry() {
        val f = FakeFiles()
        val encPending = JSONArray().put(JSONObject().put("old", "x").put("new", "y")).toString()
        val outPending = JSONArray()
            .put(JSONObject().put("old", "a").put("new", "b"))
            .put(JSONObject().put("old", "x").put("new", "y")) // duplicate across sides
            .toString()
        f.encrypted["rename_journal"] = mutableMapOf("pending" to encPending)
        f.outage["rename_journal"] = mutableMapOf("pending" to outPending)
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("rename_journal"), r.merged)
        val merged = JSONArray(f.encrypted["rename_journal"]!!["pending"] as String)
        assertEquals(2, merged.length()) // union, deduplicated
        assertFalse(f.outage.containsKey("rename_journal"))
    }

    @Test fun renameJournal_outageEntriesOnly_land() {
        val f = FakeFiles()
        val outPending = JSONArray().put(JSONObject().put("old", "a").put("new", "b")).toString()
        f.outage["rename_journal"] = mutableMapOf("pending" to outPending)
        val r = OutageReconciler.reconcile(f)
        assertEquals(listOf("rename_journal"), r.merged)
        val merged = JSONArray(f.encrypted["rename_journal"]!!["pending"] as String)
        assertEquals(1, merged.length())
        assertEquals("a", merged.getJSONObject(0).getString("old"))
    }
}
