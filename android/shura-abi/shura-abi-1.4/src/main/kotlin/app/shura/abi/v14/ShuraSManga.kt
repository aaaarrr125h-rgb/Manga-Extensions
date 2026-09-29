package app.shura.abi.v14

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy

/**
 * The host's implementation of the ABI 1.4 `SManga` stub.
 */
internal class ShuraSManga(url: String) : SManga {

    override var url: String = url
    override var title: String = ""
    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = SManga.UNKNOWN
    override var thumbnail_url: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var initialized: Boolean = false
}
