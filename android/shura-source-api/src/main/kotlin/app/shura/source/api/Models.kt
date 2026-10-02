package app.shura.source.api

/**
 * A handle to one entry inside a source.
 *
 * [value] is opaque to the host: it is whatever the source wants to be handed back, in most
 * cases the site's relative URL. It is never interpreted here.
 */
@JvmInline
value class MangaRef(val value: String) {
    init {
        require(value.isNotBlank()) { "MangaRef must not be blank" }
    }

    override fun toString(): String = value
}

/** A handle to one chapter. Opaque to the host, same rules as [MangaRef]. */
@JvmInline
value class ChapterRef(val value: String) {
    init {
        require(value.isNotBlank()) { "ChapterRef must not be blank" }
    }

    override fun toString(): String = value
}

/** One page of a chapter. */
data class PageRef(
    val index: Int,
    val url: String,
    val imageUrl: String? = null,
) {
    init {
        require(index >= 0) { "PageRef index must not be negative, got $index" }
    }
}

/**
 * Publication status.
 *
 * The codes are the ones the extensions use, so the bridge is a straight assignment and a
 * value can round trip through the repository without a lookup table.
 */
enum class MangaStatus(val code: Int) {
    UNKNOWN(0),
    ONGOING(1),
    COMPLETED(2),
    LICENSED(3),
    PUBLISHING_FINISHED(4),
    CANCELLED(5),
    ON_HIATUS(6),
    ;

    companion object {
        fun fromCode(code: Int): MangaStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Age rating of a single entry. */
enum class ContentRating {
    SAFE,
    SUGGESTIVE,
    ADULT,
}

/** How often the host should re-fetch a single entry. */
enum class UpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}

/** One manga, as the source knows it. */
data class Manga(
    val ref: MangaRef,
    val url: String,
    val title: String,
    val altTitles: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val bannerUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val contentRating: ContentRating = ContentRating.SAFE,
    val score: Int? = null,
    val language: String? = null,
    val updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    val initialized: Boolean = false,
    /**
     * The source's own per entry state, as JSON text, or null when it has none.
     *
     * Only ABI 1.6 has a `memo` field. Shura never interprets the contents: a source uses it to
     * carry whatever it needs between calls, so it has to survive verbatim or the source stops
     * working. It is kept as text rather than as parsed JSON so the host does not have to be
     * built against kotlinx-serialization's model of what a memo may contain.
     */
    val memo: String? = null,
)

/** One page of a browse or search result. */
data class MangaListPage(
    val mangas: List<Manga>,
    val hasNextPage: Boolean,
) {
    companion object {
        val EMPTY = MangaListPage(emptyList(), hasNextPage = false)
    }
}

/** One chapter, as the source knows it. */
data class Chapter(
    val ref: ChapterRef,
    val url: String,
    val name: String,
    val volume: String? = null,
    val number: String? = null,
    val scanlators: List<String> = emptyList(),
    val uploadDateMillis: Long = 0L,
    val language: String? = null,
    val locked: Boolean = false,
    val note: String? = null,
    /** The source's own per chapter state, as JSON text. See [Manga.memo]. */
    val memo: String? = null,
)

/** Repository level content warning of an extension. */
enum class ExtensionContentWarning {
    SAFE,
    MIXED,
    NSFW,
    ;

    /**
     * The inverse of [fromIndexValue], and the only spelling written to storage.
     *
     * Persisting the enum's own name would round trip through `fromIndexValue` back to [SAFE],
     * because `fromIndexValue` only knows the `CONTENT_WARNING_*` spellings. A registry that stored
     * the short name would therefore quietly turn an NSFW extension into a safe one after a
     * restart, which is exactly the kind of mistake this value exists to prevent.
     */
    fun toIndexValue(): String = when (this) {
        SAFE -> "CONTENT_WARNING_SAFE"
        MIXED -> "CONTENT_WARNING_MIXED"
        NSFW -> "CONTENT_WARNING_NSFW"
    }

    companion object {
        fun fromIndexValue(raw: String?): ExtensionContentWarning = when (raw?.trim()?.uppercase()) {
            "CONTENT_WARNING_MIXED" -> MIXED
            "CONTENT_WARNING_NSFW" -> NSFW
            else -> SAFE
        }

        /**
         * The manifest meta-data `tachiyomix.contentWarning` uses a small integer, and the
         * legacy meta-data `tachiyomi.extension.nsfw` uses a 0/1 flag. Both are accepted.
         */
        fun fromManifestValue(raw: String?): ExtensionContentWarning = when (raw?.trim()) {
            "1" -> MIXED
            "2" -> NSFW
            else -> SAFE
        }
    }
}
