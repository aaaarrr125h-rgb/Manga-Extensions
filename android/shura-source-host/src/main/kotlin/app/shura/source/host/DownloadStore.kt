package app.shura.source.host

import app.shura.source.host.json.asJsonArray
import app.shura.source.host.json.asJsonObject
import app.shura.source.host.json.longOrNull
import app.shura.source.host.json.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Whether a chapter's pages are all on disk. */
enum class DownloadStatus {
    COMPLETE,
    FAILED,
}

/** One chapter the app has downloaded, complete or not. */
data class DownloadedChapter(
    val packageName: String,
    val sourceId: Long,
    val mangaRef: String,
    val mangaTitle: String,
    val chapterRef: String,
    val chapterName: String,
    val pageCount: Int,
    val status: DownloadStatus,
    val error: String?,
    val bytes: Long,
    val updatedAtMillis: Long,
    /** Directory name under the download root; also the record's identity. */
    val directory: String,
)

/**
 * The downloaded chapters, kept under the app's private files directory.
 *
 * This is not a cache. Nothing here is evictable: the whole point is that a chapter downloaded
 * once stays readable after the app is closed and reopened, and is only removed when the user asks.
 *
 * Layout under [root]:
 *
 * ```
 * <root>/downloads.json                 the index of every known chapter
 * <root>/<directory>/page-000.img       the page bytes, in reading order
 * ```
 *
 * The page directory name is a digest of the four identity fields rather than the refs themselves,
 * because a ref is usually a URL and would put slashes and query strings into a path. The index is
 * written atomically, so a kill during a download leaves the previous index; the incomplete page
 * directory is simply overwritten by the retry.
 */
class DownloadStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val file: File get() = File(root, FILE_NAME)

    fun all(): List<DownloadedChapter> = readAll().sortedByDescending { it.updatedAtMillis }

    fun find(packageName: String, sourceId: Long, mangaRef: String, chapterRef: String): DownloadedChapter? =
        readAll().firstOrNull { it.matches(packageName, sourceId, mangaRef, chapterRef) }

    fun forManga(packageName: String, sourceId: Long, mangaRef: String): List<DownloadedChapter> =
        readAll().filter { it.packageName == packageName && it.sourceId == sourceId && it.mangaRef == mangaRef }

    /** The directory a chapter's pages live in. Created on demand. */
    fun prepareDirectory(packageName: String, sourceId: Long, mangaRef: String, chapterRef: String): File {
        val directory = File(root, Storage.digestName(packageName, sourceId, mangaRef, chapterRef))
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw ExtensionStorageException(directory.path, "cannot create download directory")
        }
        return directory
    }

    fun pageDirectory(record: DownloadedChapter): File = File(root, record.directory)

    fun pageFileName(index: Int): String = "page-%03d.img".format(index)

    /** The page files on disk for [record], in reading order, ignoring anything unexpected. */
    fun pageFiles(record: DownloadedChapter): List<File> =
        pageDirectory(record).listFiles()
            ?.filter { it.isFile && it.name.startsWith(PAGE_PREFIX) && it.name.endsWith(PAGE_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

    fun recordComplete(
        packageName: String,
        sourceId: Long,
        mangaRef: String,
        mangaTitle: String,
        chapterRef: String,
        chapterName: String,
        pageCount: Int,
        bytes: Long,
    ): DownloadedChapter = upsert(
        DownloadedChapter(
            packageName = packageName,
            sourceId = sourceId,
            mangaRef = mangaRef,
            mangaTitle = mangaTitle,
            chapterRef = chapterRef,
            chapterName = chapterName,
            pageCount = pageCount,
            status = DownloadStatus.COMPLETE,
            error = null,
            bytes = bytes,
            updatedAtMillis = clock(),
            directory = Storage.digestName(packageName, sourceId, mangaRef, chapterRef),
        ),
    )

    fun recordFailed(
        packageName: String,
        sourceId: Long,
        mangaRef: String,
        mangaTitle: String,
        chapterRef: String,
        chapterName: String,
        pageCount: Int,
        bytes: Long,
        error: String,
    ): DownloadedChapter = upsert(
        DownloadedChapter(
            packageName = packageName,
            sourceId = sourceId,
            mangaRef = mangaRef,
            mangaTitle = mangaTitle,
            chapterRef = chapterRef,
            chapterName = chapterName,
            pageCount = pageCount,
            status = DownloadStatus.FAILED,
            error = error,
            bytes = bytes,
            updatedAtMillis = clock(),
            directory = Storage.digestName(packageName, sourceId, mangaRef, chapterRef),
        ),
    )

    private fun upsert(record: DownloadedChapter): DownloadedChapter {
        val records = readAll().filterNot {
            it.matches(record.packageName, record.sourceId, record.mangaRef, record.chapterRef)
        } + record
        writeAll(records)
        return record
    }

    /** @return true when a record existed and was removed. */
    fun remove(packageName: String, sourceId: Long, mangaRef: String, chapterRef: String): Boolean {
        val current = readAll()
        val record = current.firstOrNull { it.matches(packageName, sourceId, mangaRef, chapterRef) }
            ?: return false
        writeAll(current.filterNot { it.matches(packageName, sourceId, mangaRef, chapterRef) })
        pageDirectory(record).deleteRecursively()
        return true
    }

    private fun readAll(): List<DownloadedChapter> {
        val text = Storage.readOrNull(file) ?: return emptyList()
        val rootNode = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ExtensionStorageException(file.path, "downloads index is not a JSON object")
        return (rootNode[DOWNLOADS_FIELD].asJsonArray ?: JsonArray(emptyList()))
            .mapNotNull { element -> element.asJsonObject?.toDownloadedChapter() }
    }

    private fun writeAll(records: List<DownloadedChapter>) {
        val payload = JsonObject(
            mapOf(
                "formatVersion" to JsonPrimitive(FORMAT),
                DOWNLOADS_FIELD to JsonArray(records.map(::downloadedChapterJson)),
            ),
        )
        Storage.writeAtomically(file, payload.toString())
    }

    private fun downloadedChapterJson(record: DownloadedChapter): JsonObject = JsonObject(
        buildMap {
            put("packageName", JsonPrimitive(record.packageName))
            put("sourceId", JsonPrimitive(record.sourceId))
            put("mangaRef", JsonPrimitive(record.mangaRef))
            put("mangaTitle", JsonPrimitive(record.mangaTitle))
            put("chapterRef", JsonPrimitive(record.chapterRef))
            put("chapterName", JsonPrimitive(record.chapterName))
            put("pageCount", JsonPrimitive(record.pageCount))
            put("status", JsonPrimitive(record.status.name))
            record.error?.let { put("error", JsonPrimitive(it)) }
            put("bytes", JsonPrimitive(record.bytes))
            put("updatedAt", JsonPrimitive(record.updatedAtMillis))
            put("directory", JsonPrimitive(record.directory))
        },
    )

    private fun JsonObject.toDownloadedChapter(): DownloadedChapter? {
        val packageName = stringOrNull("packageName")?.takeIf { it.isNotBlank() } ?: return null
        val mangaRef = stringOrNull("mangaRef")?.takeIf { it.isNotBlank() } ?: return null
        val chapterRef = stringOrNull("chapterRef")?.takeIf { it.isNotBlank() } ?: return null
        val directory = stringOrNull("directory")?.takeIf { it.isNotBlank() }
            ?: return null
        return DownloadedChapter(
            packageName = packageName,
            sourceId = longOrNull("sourceId") ?: return null,
            mangaRef = mangaRef,
            mangaTitle = stringOrNull("mangaTitle").orEmpty().ifBlank { mangaRef },
            chapterRef = chapterRef,
            chapterName = stringOrNull("chapterName").orEmpty().ifBlank { chapterRef },
            pageCount = (longOrNull("pageCount") ?: 0L).toInt(),
            status = runCatching { DownloadStatus.valueOf(stringOrNull("status").orEmpty()) }
                .getOrDefault(DownloadStatus.FAILED),
            error = stringOrNull("error")?.takeIf { it.isNotBlank() },
            bytes = longOrNull("bytes") ?: 0L,
            updatedAtMillis = longOrNull("updatedAt") ?: 0L,
            directory = directory,
        )
    }

    private fun DownloadedChapter.matches(
        packageName: String,
        sourceId: Long,
        mangaRef: String,
        chapterRef: String,
    ): Boolean = this.packageName == packageName &&
        this.sourceId == sourceId &&
        this.mangaRef == mangaRef &&
        this.chapterRef == chapterRef

    private companion object {
        const val FILE_NAME = "downloads.json"
        const val FORMAT = 1
        const val DOWNLOADS_FIELD = "downloads"
        const val PAGE_PREFIX = "page-"
        const val PAGE_SUFFIX = ".img"
    }
}
