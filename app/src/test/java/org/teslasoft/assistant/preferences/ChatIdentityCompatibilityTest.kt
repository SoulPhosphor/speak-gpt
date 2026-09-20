package org.teslasoft.assistant.preferences

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.teslasoft.assistant.util.Hash

class ChatIdentityCompatibilityTest {

    @Test
    fun missingIdRowCanBeFoundAndRenamedWithoutBeingRewritten() {
        val legacy = hashMapOf("name" to "Legacy")
        val stableId = ChatPreferences.storedChatId(legacy)

        assertEquals("Legacy", ChatPreferences.chatNameForId(listOf(legacy), stableId))
        legacy["name"] = "Renamed"
        assertFalse(legacy.containsKey("id"))
        assertEquals(Hash.hash("Renamed"), ChatPreferences.storedChatId(legacy))
        assertEquals("Renamed", ChatPreferences.chatNameForId(listOf(legacy), Hash.hash("Renamed")))
    }

    @Test
    fun immutableHistoricalIdDoesNotReserveItsFormerTitle() {
        val oldId = Hash.hash("Old")
        val renamed = mapOf("id" to oldId, "name" to "New")

        assertFalse(ChatPreferences.hasChatTitle(listOf(renamed), "Old"))
        assertTrue(ChatPreferences.hasChatTitle(listOf(renamed), "New"))
        assertFalse(ChatPreferences.hasChatTitle(listOf(renamed), "New", oldId))
    }

    @Test
    fun autonameAvailabilityUsesTitlesNotImmutableIds() {
        val formerAutoname = mapOf("id" to Hash.hash("_autoname_1"), "name" to "Renamed")
        assertEquals("1", ChatPreferences.nextAutonameNumber(listOf(formerAutoname)))
        assertEquals(
            "3",
            ChatPreferences.nextAutonameNumber(
                listOf(
                    formerAutoname,
                    mapOf("id" to UUID.randomUUID().toString(), "name" to "_autoname_1"),
                    mapOf("id" to UUID.randomUUID().toString(), "name" to "_autoname_2")
                )
            )
        )
    }

    @Test
    fun newUuidsStayDistinctAcrossRenames() {
        val first = hashMapOf("id" to UUID.randomUUID().toString(), "name" to "One")
        val second = hashMapOf("id" to UUID.randomUUID().toString(), "name" to "Two")
        val firstId = ChatPreferences.storedChatId(first)
        val secondId = ChatPreferences.storedChatId(second)

        assertNotEquals(firstId, secondId)
        first["name"] = "Renamed One"
        second["name"] = "Renamed Two"
        assertEquals(firstId, ChatPreferences.storedChatId(first))
        assertEquals(secondId, ChatPreferences.storedChatId(second))
    }

    @Test
    fun identityReadersAndCreationPathRemainCentralized() {
        val root = sourceRoot()
        val chatPreferences = File(root, "preferences/ChatPreferences.kt").readText()
        val generatedFiles = File(root, "imagegen/GeneratedImageFiles.kt").readText()
        val renameJournal = File(root, "preferences/RenameJournal.kt").readText()

        assertFalse(chatPreferences.contains("fun addChat("))
        assertFalse(productionSources(root).any { it.readText().contains(".addChat(") })
        assertTrue(
            chatPreferences.substringAfter("fun getChatName(")
                .substringBefore("fun editChat(")
                .contains("chatNameForId")
        )
        val rename = chatPreferences.substringAfter("fun editChat(")
            .substringBefore("private fun securePrefsFileAccess")
        assertTrue(rename.contains("if (!entry.containsKey(\"id\")) newId = Hash.hash(chatName)"))
        assertFalse(rename.contains("entry[\"id\"] ="))
        assertFalse(generatedFiles.contains("chat[\"id\"]"))
        assertTrue(generatedFiles.contains("ChatPreferences.storedChatId(chat)"))
        assertFalse(renameJournal.contains("mapNotNull { it[\"id\"] }"))
        assertTrue(renameJournal.contains("ChatPreferences.storedChatId(it)"))
    }

    @Test
    fun everyChatListConsumerRoutesIdentityThroughCompatibilityHelper() {
        val root = sourceRoot()
        val directId = Regex("\\[\\s*\"id\"\\s*]")
        val helper = Regex(
            "fun storedChatId\\(chat: Map<String, String>\\): String =\\s*" +
                "chat\\[\"id\"\\] \\?: Hash\\.hash\\(chat\\[\"name\"\\]\\.toString\\(\\)\\)"
        )
        val offenders = productionSources(root)
            .filter { file ->
                val source = file.readText()
                (source.contains("getChatList(") || source.contains("getChatListResult(")) &&
                    directId.containsMatchIn(source.replace(helper, ""))
            }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("Direct chat-list ID readers: $offenders", offenders.isEmpty())
    }

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/org/teslasoft/assistant"),
            File("app/src/main/java/org/teslasoft/assistant"),
            File(System.getProperty("user.dir"), "src/main/java/org/teslasoft/assistant"),
            File(System.getProperty("user.dir"), "app/src/main/java/org/teslasoft/assistant")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate production sources")
    }

    private fun productionSources(root: File): Sequence<File> =
        root.walkTopDown().asSequence().filter { it.isFile && it.extension == "kt" }
}
