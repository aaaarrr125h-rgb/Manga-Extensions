package app.shura.abi.v16

/**
 * Remembers the `memo` a source wrote, so the next call for the same entry gets it back.
 *
 * This is not an optimisation. At ABI 1.6 a source stores per entry state in `SManga.memo` and
 * expects to read it on the next call — a site specific id, a token, a resolved variant. The host
 * builds a fresh stub object for every call, so without this the source would be handed an empty
 * memo every time and would quietly re-scrape, or fail outright.
 *
 * Two honest limits:
 *
 * - it is per bridge, so it lives exactly as long as the loaded extension. Across an app restart
 *   the memo is gone until the entry is fetched again. Persisting it belongs with the database
 *   layer, which is past the Foundation.
 * - it is bounded. A source browsed for hours would otherwise grow this without limit; the oldest
 *   entries are dropped first, and a dropped memo only costs the source one extra lookup.
 */
internal class MemoCache(private val capacity: Int = DEFAULT_CAPACITY) {

    private val byManga = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > capacity
    }

    private val byChapter = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > capacity
    }

    fun mangaMemo(url: String): String? = synchronized(byManga) { byManga[url] }

    fun rememberManga(url: String, memo: String?) = synchronized(byManga) {
        if (memo.isNullOrEmpty()) byManga.remove(url) else byManga[url] = memo
    }

    fun chapterMemo(url: String): String? = synchronized(byChapter) { byChapter[url] }

    fun rememberChapter(url: String, memo: String?) = synchronized(byChapter) {
        if (memo.isNullOrEmpty()) byChapter.remove(url) else byChapter[url] = memo
    }

    private companion object {
        const val DEFAULT_CAPACITY = 512
    }
}
