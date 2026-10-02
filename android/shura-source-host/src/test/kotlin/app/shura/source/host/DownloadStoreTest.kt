package app.shura.source.host

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadStoreTest {

    private val root: File = Files.createTempDirectory("shura-downloads").toFile()
    private val store = DownloadStore(root) { 1_000L }

    private fun writePages(record: DownloadedChapter, count: Int): Long {
        val directory = store.pageDirectory(record)
        var bytes = 0L
        repeat(count) { index ->
            val payload = ByteArray(4) { index.toByte() }
            File(directory, store.pageFileName(index)).writeBytes(payload)
            bytes += payload.size
        }
        return bytes
    }

    @Test
    fun `a completed chapter is listed and found by its identity`() {
        store.recordComplete("pkg", 7L, "/m/1", "One Piece", "/c/1", "Chapter 1", 3, 12)
        val found = store.find("pkg", 7L, "/m/1", "/c/1")
        assertEquals(DownloadStatus.COMPLETE, found?.status)
        assertEquals(3, found?.pageCount)
        assertNull(found?.error)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `page files are written under a deterministic directory and read back in order`() {
        val directory = store.prepareDirectory("pkg", 7L, "/m/1", "/c/1")
        val same = store.prepareDirectory("pkg", 7L, "/m/1", "/c/1")
        assertEquals(directory, same)

        val record = store.recordComplete("pkg", 7L, "/m/1", "One Piece", "/c/1", "Chapter 1", 3, 0)
        writePages(record, 3)
        assertEquals(
            listOf("page-000.img", "page-001.img", "page-002.img"),
            store.pageFiles(record).map { it.name },
        )
    }

    @Test
    fun `a different chapter gets a different directory`() {
        val one = store.prepareDirectory("pkg", 7L, "/m/1", "/c/1")
        val two = store.prepareDirectory("pkg", 7L, "/m/1", "/c/2")
        assertFalse(one == two)
    }

    @Test
    fun `a failed download is recorded with its reason and a retry replaces it`() {
        store.recordFailed("pkg", 7L, "/m/1", "One Piece", "/c/1", "Chapter 1", 0, 0, "boom")
        val failed = store.find("pkg", 7L, "/m/1", "/c/1")
        assertEquals(DownloadStatus.FAILED, failed?.status)
        assertEquals("boom", failed?.error)

        store.recordComplete("pkg", 7L, "/m/1", "One Piece", "/c/1", "Chapter 1", 2, 8)
        assertEquals(DownloadStatus.COMPLETE, store.find("pkg", 7L, "/m/1", "/c/1")?.status)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `removing a chapter deletes its pages and reports whether anything was removed`() {
        store.prepareDirectory("pkg", 7L, "/m/1", "/c/1")
        val record = store.recordComplete("pkg", 7L, "/m/1", "One Piece", "/c/1", "Chapter 1", 1, 4)
        writePages(record, 1)
        val directory = store.pageDirectory(record)
        assertTrue(directory.isDirectory)

        assertTrue(store.remove("pkg", 7L, "/m/1", "/c/1"))
        assertFalse(directory.isDirectory)
        assertFalse(store.remove("pkg", 7L, "/m/1", "/c/1"))
        assertNull(store.find("pkg", 7L, "/m/1", "/c/1"))
    }

    @Test
    fun `a second store over the same root still sees the downloads`() {
        store.recordComplete("pkg", 7L, "/m/1", "One Piece", "/c/1", "Chapter 1", 1, 4)
        val reopened = DownloadStore(root)
        assertEquals(1, reopened.all().size)
        assertEquals("One Piece", reopened.all().single().mangaTitle)
    }

    @Test
    fun `a malformed index is reported rather than silently emptied`() {
        File(root, "downloads.json").writeText("[")
        assertFailsWith<ExtensionStorageException> { store.all() }
    }
}
