package org.teslasoft.assistant.preferences.includes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.teslasoft.assistant.preferences.backup.portable.ChatRestoreParticipant

class PdfRestoreStagingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `repeated includes of the same PDF in one chat stage one file`() {
        val hash = "a".repeat(64)
        val source = temp.newFile("source.pdf").apply { writeText("%PDF-same") }
        val root = File(temp.root, "staged")

        val ok = ChatRestoreParticipant.stagePdfFiles(
            root,
            listOf("chat-1" to hash, "chat-1" to hash, "chat-2" to hash)
        ) { if (it == hash) source else null }

        assertTrue(ok)
        assertEquals("%PDF-same", File(root, "chat-1/$hash.pdf").readText())
        assertEquals("%PDF-same", File(root, "chat-2/$hash.pdf").readText())
    }

    @Test fun `unreadable staged chat listing fails before the live tree is touched`() {
        val live = File(temp.root, "live/chat-1").apply { mkdirs() }
        val liveFile = File(live, "kept.pdf").apply { writeText("live") }
        val staging = File(temp.root, "staging")
        val unreadable = File(staging, "chat-1").apply { mkdirs() }
        File(unreadable, "new.pdf").writeText("new")

        val ok = PdfAttachmentStore.replaceTreeFromStaging(
            File(temp.root, "live"), staging
        ) { dir -> if (dir == unreadable) null else dir.listFiles() }

        assertFalse(ok)
        assertEquals("live", liveFile.readText())
    }

    @Test fun `unreadable staging root fails before the live tree is touched`() {
        val liveFile = File(temp.root, "live/chat-1/kept.pdf").apply {
            parentFile?.mkdirs()
            writeText("live")
        }
        val staging = temp.newFolder("staging")

        val ok = PdfAttachmentStore.replaceTreeFromStaging(
            File(temp.root, "live"), staging
        ) { null }

        assertFalse(ok)
        assertTrue(liveFile.isFile)
    }

    @Test fun `readable staged tree replaces the live tree`() {
        val liveRoot = File(temp.root, "live")
        val old = File(liveRoot, "chat-1/old.pdf").apply {
            parentFile?.mkdirs()
            writeText("old")
        }
        val staging = temp.newFolder("staging")
        File(staging, "chat-1").mkdirs()
        File(staging, "chat-1/new.pdf").writeText("new")

        assertTrue(PdfAttachmentStore.replaceTreeFromStaging(liveRoot, staging))
        assertFalse(old.exists())
        assertEquals("new", File(liveRoot, "chat-1/new.pdf").readText())
    }
}
