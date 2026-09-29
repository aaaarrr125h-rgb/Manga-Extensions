package app.shura.abi.v16

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.json.JsonObject

/** The host's implementation of the ABI 1.6 `SChapter` stub. See [ShuraSManga] for `memo`. */
internal class ShuraSChapter(url: String, memo: JsonObject = JsonObject(LinkedHashMap())) : SChapter {

    override var url: String = url
    override var name: String = ""
    override var chapter_number: Float = Float.NaN
    override var scanlator: String? = null
    override var date_upload: Long = 0L
    override var memo: JsonObject = memo
}
