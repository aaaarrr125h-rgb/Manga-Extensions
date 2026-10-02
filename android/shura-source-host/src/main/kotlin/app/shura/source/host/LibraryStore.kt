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

/** One manga the user keeps in the library. */
data class LibraryEntry(
    val packageName: String,
    val sourceId: Long,
    val mangaRef: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val addedAtMillis: Long = 0L,
)

/** The chapter and page the user last read, for one manga. */
data class ReadingState(
    val packageName: String,
    val sourceId: Long,
    val mangaRef: String,
    val chapterRef: String,
    val chapterName: String,
    val page: Int,
    val updatedAtMillis: Long,
)

/**
 * The user's library and reading positions, in one JSON document.
 *
 * Keeping both in one file means one atomic write covers "add to library" and "remember where I
 * stopped", and a reader never sees a manga with a reading position pointing at a different
 * library state. The document is written the same way the extension registry is: to a temporary
 * sibling and renamed, so a kill during a write leaves the previous document intact.
 *
 * There is deliberately no cache layer in front of this. The file is small, it is read when a
 * screen opens, and a stale in-memory copy would be a worse bug than a disk read.
 */
class LibraryStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val file: File get() = File(root, FILE_NAME)

    fun entries(): List<LibraryEntry> = snapshot().manga.sortedBy { it.title.lowercase() }

    fun contains(packageName: String, sourceId: Long, mangaRef: String): Boolean =
        snapshot().manga.any { it.matches(packageName, sourceId, mangaRef) }

    /**
     * Adds or replaces a library entry.
     *
     * @return true when the entry was not in the library before.
     */
    fun add(
        packageName: String,
        sourceId: Long,
        mangaRef: String,
        title: String,
        thumbnailUrl: String? = null,
    ): Boolean {
        val current = snapshot()
        val existed = current.manga.any { it.matches(packageName, sourceId, mangaRef) }
        val entry = LibraryEntry(
            packageName = packageName,
            sourceId = sourceId,
            mangaRef = mangaRef,
            title = title,
            thumbnailUrl = thumbnailUrl,
            addedAtMillis = clock(),
        )
        write(
            current.copy(
                manga = current.manga.filterNot { it.matches(packageName, sourceId, mangaRef) } + entry,
            ),
        )
        return !existed
    }

    /** @return true when an entry was removed. */
    fun remove(packageName: String, sourceId: Long, mangaRef: String): Boolean {
        val current = snapshot()
        val remaining = current.manga.filterNot { it.matches(packageName, sourceId, mangaRef) }
        if (remaining.size == current.manga.size) return false
        write(current.copy(manga = remaining))
        return true
    }

    fun readingState(packageName: String, sourceId: Long, mangaRef: String): ReadingState? =
        snapshot().reading.firstOrNull { it.matches(packageName, sourceId, mangaRef) }

    fun readingStates(): List<ReadingState> = snapshot().reading

    /** Records where the reader stopped. Replaces any previous position for the same manga. */
    fun saveReadingState(
        packageName: String,
        sourceId: Long,
        mangaRef: String,
        chapterRef: String,
        chapterName: String,
        page: Int,
    ) {
        val current = snapshot()
        val state = ReadingState(
            packageName = packageName,
            sourceId = sourceId,
            mangaRef = mangaRef,
            chapterRef = chapterRef,
            chapterName = chapterName,
            page = page.coerceAtLeast(0),
            updatedAtMillis = clock(),
        )
        write(
            current.copy(
                reading = current.reading.filterNot { it.matches(packageName, sourceId, mangaRef) } + state,
            ),
        )
    }

    private data class Snapshot(val manga: List<LibraryEntry>, val reading: List<ReadingState>)

    private fun snapshot(): Snapshot {
        val text = Storage.readOrNull(file) ?: return Snapshot(emptyList(), emptyList())
        val rootNode = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ExtensionStorageException(file.path, "library is not a JSON object")
        val manga = (rootNode[MANGA_FIELD].asJsonArray ?: JsonArray(emptyList()))
            .mapNotNull { element -> element.asJsonObject?.toLibraryEntry() }
        val reading = (rootNode[READING_FIELD].asJsonArray ?: JsonArray(emptyList()))
            .mapNotNull { element -> element.asJsonObject?.toReadingState() }
        return Snapshot(manga, reading)
    }

    private fun write(snapshot: Snapshot) {
        val payload = JsonObject(
            mapOf(
                "formatVersion" to JsonPrimitive(FORMAT),
                MANGA_FIELD to JsonArray(snapshot.manga.map(::libraryEntryJson)),
                READING_FIELD to JsonArray(snapshot.reading.map(::readingStateJson)),
            ),
        )
        Storage.writeAtomically(file, payload.toString())
    }

    private fun libraryEntryJson(entry: LibraryEntry): JsonObject = JsonObject(
        buildMap {
            put("packageName", JsonPrimitive(entry.packageName))
            put("sourceId", JsonPrimitive(entry.sourceId))
            put("mangaRef", JsonPrimitive(entry.mangaRef))
            put("title", JsonPrimitive(entry.title))
            entry.thumbnailUrl?.let { put("thumbnailUrl", JsonPrimitive(it)) }
            put("addedAt", JsonPrimitive(entry.addedAtMillis))
        },
    )

    private fun readingStateJson(state: ReadingState): JsonObject = JsonObject(
        mapOf(
            "packageName" to JsonPrimitive(state.packageName),
            "sourceId" to JsonPrimitive(state.sourceId),
            "mangaRef" to JsonPrimitive(state.mangaRef),
            "chapterRef" to JsonPrimitive(state.chapterRef),
            "chapterName" to JsonPrimitive(state.chapterName),
            "page" to JsonPrimitive(state.page),
            "updatedAt" to JsonPrimitive(state.updatedAtMillis),
        ),
    )

    private fun JsonObject.toLibraryEntry(): LibraryEntry? {
        val packageName = stringOrNull("packageName")?.takeIf { it.isNotBlank() } ?: return null
        val mangaRef = stringOrNull("mangaRef")?.takeIf { it.isNotBlank() } ?: return null
        return LibraryEntry(
            packageName = packageName,
            sourceId = longOrNull("sourceId") ?: return null,
            mangaRef = mangaRef,
            title = stringOrNull("title").orEmpty().ifBlank { mangaRef },
            thumbnailUrl = stringOrNull("thumbnailUrl")?.takeIf { it.isNotBlank() },
            addedAtMillis = longOrNull("addedAt") ?: 0L,
        )
    }

    private fun JsonObject.toReadingState(): ReadingState? {
        val packageName = stringOrNull("packageName")?.takeIf { it.isNotBlank() } ?: return null
        val mangaRef = stringOrNull("mangaRef")?.takeIf { it.isNotBlank() } ?: return null
        val chapterRef = stringOrNull("chapterRef")?.takeIf { it.isNotBlank() } ?: return null
        return ReadingState(
            packageName = packageName,
            sourceId = longOrNull("sourceId") ?: return null,
            mangaRef = mangaRef,
            chapterRef = chapterRef,
            chapterName = stringOrNull("chapterName").orEmpty().ifBlank { chapterRef },
            page = (longOrNull("page") ?: 0L).toInt(),
            updatedAtMillis = longOrNull("updatedAt") ?: 0L,
        )
    }

    private fun LibraryEntry.matches(packageName: String, sourceId: Long, mangaRef: String): Boolean =
        this.packageName == packageName && this.sourceId == sourceId && this.mangaRef == mangaRef

    private fun ReadingState.matches(packageName: String, sourceId: Long, mangaRef: String): Boolean =
        this.packageName == packageName && this.sourceId == sourceId && this.mangaRef == mangaRef

    private companion object {
        const val FILE_NAME = "library.json"
        const val FORMAT = 1
        const val MANGA_FIELD = "manga"
        const val READING_FIELD = "reading"
    }
}
