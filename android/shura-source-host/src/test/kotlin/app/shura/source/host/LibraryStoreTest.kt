package app.shura.source.host

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryStoreTest {

    private val root: File = Files.createTempDirectory("shura-library").toFile()
    private val store = LibraryStore(root) { 1_000L }

    @Test
    fun `a manga added is listed and reported present`() {
        assertTrue(store.add("pkg", 7L, "/m/1", "One Piece"))
        val entries = store.entries()
        assertEquals(1, entries.size)
        assertEquals("One Piece", entries.single().title)
        assertTrue(store.contains("pkg", 7L, "/m/1"))
    }

    @Test
    fun `adding the same manga twice replaces it and reports it was already there`() {
        store.add("pkg", 7L, "/m/1", "Old title")
        assertFalse(store.add("pkg", 7L, "/m/1", "New title"))
        assertEquals(listOf("New title"), store.entries().map { it.title })
    }

    @Test
    fun `removing a manga reports whether anything was removed`() {
        store.add("pkg", 7L, "/m/1", "One Piece")
        assertTrue(store.remove("pkg", 7L, "/m/1"))
        assertFalse(store.remove("pkg", 7L, "/m/1"))
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `reading state round trips and is replaced in place`() {
        store.saveReadingState("pkg", 7L, "/m/1", "/c/1", "Chapter 1", 3)
        val first = store.readingState("pkg", 7L, "/m/1")
        assertEquals(3, first?.page)
        assertEquals("Chapter 1", first?.chapterName)

        store.saveReadingState("pkg", 7L, "/m/1", "/c/2", "Chapter 2", 0)
        val second = store.readingState("pkg", 7L, "/m/1")
        assertEquals("/c/2", second?.chapterRef)
        assertEquals(1, store.readingStates().size)
    }

    @Test
    fun `a second store over the same root sees the same library`() {
        store.add("pkg", 7L, "/m/1", "One Piece")
        store.saveReadingState("pkg", 7L, "/m/1", "/c/1", "Chapter 1", 2)

        val reopened = LibraryStore(root)
        assertEquals(listOf("/m/1"), reopened.entries().map { it.mangaRef })
        assertEquals(2, reopened.readingState("pkg", 7L, "/m/1")?.page)
    }

    @Test
    fun `an absent file is an empty library rather than an error`() {
        assertTrue(LibraryStore(Files.createTempDirectory("shura-library-empty").toFile()).entries().isEmpty())
        assertNull(store.readingState("pkg", 7L, "/missing"))
    }

    @Test
    fun `a malformed library is reported rather than silently emptied`() {
        val file = File(root, "library.json")
        file.writeText("{ not json")
        assertFailsWith<ExtensionStorageException> { store.entries() }
    }
}
