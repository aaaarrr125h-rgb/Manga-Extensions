package app.shura.source.host

import app.shura.source.api.SourceKey
import app.shura.source.api.SourceLanguage
import app.shura.source.host.json.asJsonArray
import app.shura.source.host.json.asJsonObject
import app.shura.source.host.json.longOrNull
import app.shura.source.host.json.objectOrNull
import app.shura.source.host.json.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.InputStream

/**
 * One source as the repository index publishes it.
 *
 * The index is a projection, not a model: everything here is a fact the repository already
 * published, so the app can build a catalogue before a single extension has been downloaded.
 */
data class RepositorySource(
    val key: SourceKey,
    val name: String,
    val language: String,
    val homeUrl: String?,
) {
    val sourceId: Long get() = key.sourceId
}

/** One extension as the repository index publishes it. */
data class RepositoryExtension(
    val packageName: String,
    val name: String,
    val extensionLib: String,
    val versionCode: Long,
    val versionName: String,
    val contentWarning: String,
    val apkUrl: String?,
    val iconUrl: String?,
    val jarUrl: String?,
    val sources: List<RepositorySource>,
) {
    /** The ABI level, or null when the index names a level this host does not implement. */
    val abi get() = app.shura.source.api.ExtensionAbi.parseOrNull(extensionLib)

    fun keyOf(source: RepositorySource): SourceKey = source.key
}

/** The repository index, parsed. */
data class RepositoryIndex(
    val name: String,
    val badgeLabel: String,
    val signingKey: String,
    val website: String?,
    val extensions: List<RepositoryExtension>,
) {
    fun byPackage(packageName: String): RepositoryExtension? =
        extensions.firstOrNull { it.packageName == packageName }

    val extensionLibHistogram: Map<String, Int> =
        extensions.groupingBy { it.extensionLib }.eachCount()
}

class RepositoryIndexException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Reads `repo/index.json`.
 *
 * Two details are deliberate and are covered by tests against the real published file:
 *
 * - `versionCode` and a source `id` are `int64` in the protobuf schema, but the JSON this
 *   repository publishes writes both as strings. Both spellings are accepted.
 * - a source `language` is the legacy `"all"` / `"other"` spelling, and is normalised once, here.
 */
object RepositoryIndexParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): RepositoryIndex = parse(text.byteInputStream())

    fun parse(stream: InputStream): RepositoryIndex = runCatching {
        val root = json.parseToJsonElement(stream.readBytes().decodeToString()).asJsonObject
            ?: throw RepositoryIndexException("Repository index is not a JSON object")
        RepositoryIndex(
            name = root.stringOrNull("name").orEmpty(),
            badgeLabel = root.stringOrNull("badgeLabel").orEmpty(),
            signingKey = root.stringOrNull("signingKey").orEmpty(),
            website = root.objectOrNull("contact")?.stringOrNull("website"),
            extensions = root.objectOrNull("extensionList")
                ?.get("extensions")
                ?.let { element ->
                    (element as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.asJsonObject }
                        ?.mapNotNull(::readExtension)
                        .orEmpty()
                }
                .orEmpty(),
        )
    }.getOrElse { failure ->
        if (failure is RepositoryIndexException) throw failure
        throw RepositoryIndexException("Repository index could not be parsed", failure)
    }

    fun parse(file: File): RepositoryIndex = parse(file.readText())

    private fun readExtension(node: JsonObject): RepositoryExtension? {
        val packageName = node.stringOrNull("packageName")?.takeIf { it.isNotBlank() } ?: return null
        val resources = node.objectOrNull("resources")
        return RepositoryExtension(
            packageName = packageName,
            name = node.stringOrNull("name").orEmpty(),
            extensionLib = node.stringOrNull("extensionLib").orEmpty(),
            versionCode = node.longOrNull("versionCode") ?: 0L,
            versionName = node.stringOrNull("versionName").orEmpty(),
            contentWarning = node.stringOrNull("contentWarning").orEmpty(),
            apkUrl = resources?.stringOrNull("apkUrl"),
            iconUrl = resources?.stringOrNull("iconUrl"),
            jarUrl = resources?.stringOrNull("jarUrl"),
            sources = node["sources"]
                ?.asJsonArray
                ?.mapNotNull { it.asJsonObject }
                ?.mapNotNull { readSource(packageName, it) }
                .orEmpty(),
        )
    }

    private fun readSource(packageName: String, node: JsonObject): RepositorySource? {
        val id = node.longOrNull("id") ?: return null
        return RepositorySource(
            key = SourceKey.of(packageName, id),
            name = node.stringOrNull("name").orEmpty(),
            language = SourceLanguage.normalize(node.stringOrNull("language")),
            homeUrl = node.stringOrNull("homeUrl"),
        )
    }
}
