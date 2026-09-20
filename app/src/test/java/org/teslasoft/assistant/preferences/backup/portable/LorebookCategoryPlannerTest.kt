package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.LoreBook
import org.teslasoft.assistant.preferences.dto.LoreBookEntry

class LorebookCategoryPlannerTest {
    @Test
    fun `merge keeps current collision and adds new stable ids`() {
        val current = data(
            books = listOf(book("book", "Current")),
            entries = listOf(entry("entry", "book", "Current entry"))
        )
        val backup = data(
            books = listOf(book("book", "Backup"), book("new-book", "New")),
            entries = listOf(entry("entry", "book", "Backup entry"))
        )
        val ready = LorebookCategoryPlanner.plan(current, backup, PortableRestoreMode.MERGE) as
            LorebookCategoryPlanner.Result.Ready

        assertEquals(listOf("book", "new-book"), ready.data.books.map { it.id })
        assertEquals("Current", ready.data.books.first().name)
        assertEquals("Current entry", ready.data.entries.single().label)
        assertEquals(2, ready.report.conflicts.size)
    }

    @Test
    fun `replace removes entries whose book does not exist`() {
        val ready = LorebookCategoryPlanner.plan(
            data(),
            data(entries = listOf(entry("entry", "missing", "Dangling"))),
            PortableRestoreMode.REPLACE
        ) as LorebookCategoryPlanner.Result.Ready
        assertEquals(emptyList<LoreBookEntry>(), ready.data.entries)
        assertEquals(1, ready.report.removedDanglingEntries)
    }

    @Test
    fun `codec round trips trigger order and tombstones`() {
        val original = data(
            books = listOf(book("book", "Book")),
            entries = listOf(entry("entry", "book", "Entry")),
            deleted = listOf(DeletedLorebookEntry("deleted", 20L, 10L))
        )
        assertEquals(original, LorebookPortableCodec.parse(LorebookPortableCodec.toJson(original)))
    }

    private fun data(
        books: List<LoreBook> = emptyList(),
        entries: List<LoreBookEntry> = emptyList(),
        deleted: List<DeletedLorebookEntry> = emptyList()
    ) = LorebookPortableData(books, entries, deleted)

    private fun book(id: String, name: String) = LoreBook(id, name, "Description", "Tag", 1L, 2L)

    private fun entry(id: String, bookId: String, label: String) = LoreBookEntry(
        id, bookId, label, "Content", "Source", arrayListOf("one", "two"), true, 3L, 4L
    )
}
