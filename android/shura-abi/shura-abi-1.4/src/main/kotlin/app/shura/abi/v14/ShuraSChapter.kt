package app.shura.abi.v14

import eu.kanade.tachiyomi.source.model.SChapter

/**
 * The host's implementation of the ABI 1.4 `SChapter` stub.
 *
 * `SChapter.create()` is a stub that throws, so the host has to supply a concrete class. The
 * source fills in whatever it recognises and leaves the rest at these defaults; the host reads
 * the object back after the call.
 */
internal class ShuraSChapter(url: String) : SChapter {

    override var url: String = url
    override var name: String = ""
    override var date_upload: Long = 0L
    override var chapter_number: Float = Float.NaN
    override var scanlator: String? = null
}
