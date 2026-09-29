package app.shura.abi.v16

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject

/**
 * The host's implementation of the ABI 1.6 `SManga` stub.
 *
 * `memo` is a real `JsonObject` because that is what a source writes into, and it arrives
 * pre-filled with whatever the source stored on the previous call. See [MemoCache] for why the
 * host has to remember it.
 */
internal class ShuraSManga(url: String, memo: JsonObject = JsonObject(LinkedHashMap())) : SManga {

    override var url: String = url
    override var title: String = ""
    override var thumbnail_url: String? = null
    override var artist: String? = null
    override var author: String? = null
    override var status: Int = SManga.UNKNOWN
    override var description: String? = null
    override var genre: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var memo: JsonObject = memo
    override var initialized: Boolean = false
}
