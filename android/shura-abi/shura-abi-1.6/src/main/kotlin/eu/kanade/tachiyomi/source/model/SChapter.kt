package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

@Suppress("UNUSED", "PropertyName")
interface SChapter {

    var url: String

    var name: String

    var chapter_number: Float

    var scanlator: String?

    var date_upload: Long

    /**
     * Extra metadata associated with the chapter.
     *
     * @since tachiyomix 1.6
     */
    var memo: JsonObject

    companion object {
        fun create(): SChapter = throw Exception("Stub!")
    }
}
