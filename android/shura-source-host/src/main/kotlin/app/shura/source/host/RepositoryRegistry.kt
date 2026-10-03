package app.shura.source.host

import app.shura.source.host.json.boolOrNull
import app.shura.source.host.json.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.URI

/** A repository URL that is empty, not https, or not a URL at all. */
class RepositoryUrlException(message: String) : RepositoryException(message)

/** A repository whose URL is already in the list. URLs, not names, are the identity. */
class DuplicateRepositoryException(url: String) : RepositoryException("$url is already configured")

/**
 * One repository the user has configured.
 *
 * [url] is the identity: two entries with the same URL are the same repository, whatever they are
 * named, which is what makes re-adding a repository a refusal rather than a silent duplicate.
 * [builtIn] marks the repository this build ships with; it is always present and cannot be removed.
 * [isDefault] marks the one repository whose copy of a duplicated extension wins, and whose
 * installed extensions seed the catalogue.
 */
data class RepositoryConfig(
    val name: String,
    val url: String,
    val isDefault: Boolean = false,
    val builtIn: Boolean = false,
) {
    val id: String get() = url
}

/** URL rules for a configured repository. The HTTPS rule mirrors the artifact policy. */
object RepositoryUrls {

    /**
     * Normalises and validates a repository URL.
     *
     * Only `https://` is accepted, which is the same rule [ArtifactUrlPolicy.REQUIRE_HTTPS] applies
     * to artifact downloads: a repository reached over plaintext could be rewritten in transit, and
     * the `signingKey` that authorises every install would be rewritten with it.
     *
     * @throws RepositoryUrlException when the value is empty, not https, or not a URL.
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw RepositoryUrlException("Repository URL is empty")
        }
        val uri = try {
            URI(trimmed)
        } catch (failure: Exception) {
            throw RepositoryUrlException("Repository URL is not a valid URL: ${failure.message}")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw RepositoryUrlException("Repository URL must start with https://")
        }
        if (uri.host.isNullOrBlank()) {
            throw RepositoryUrlException("Repository URL has no host")
        }
        return trimmed
    }

    /** A display name derived from the URL, for when the user leaves the name blank. */
    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Repository"
}

/**
 * The persistent list of configured repositories.
 *
 * Layout: one JSON file. The list survives restarts, seeded the first time from the repository this
 * build ships with, so an upgrade from the single-repository build keeps working and never loses the
 * official repository. Reads are lenient: a file that cannot be parsed is treated as an empty list
 * and re-seeded, because a corrupt settings file must not be able to stop the app from opening.
 * Writes are atomic, like [ExtensionStore]'s registry.
 */
class RepositoryRegistry(
    private val file: File,
    private val defaultName: String,
    private val defaultUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        val state = read()
        val migrated = migrate(state)
        if (migrated != state) write(migrated)
    }

    @Synchronized
    fun configs(): List<RepositoryConfig> = read().configs

    @Synchronized
    fun default(): RepositoryConfig? = read().configs.firstOrNull { it.isDefault }

    @Synchronized
    fun find(url: String): RepositoryConfig? {
        val normalized = RepositoryUrls.normalize(url)
        return read().configs.firstOrNull { it.url == normalized }
    }

    /**
     * Adds a repository.
     *
     * @throws RepositoryUrlException when [url] fails [RepositoryUrls.normalize].
     * @throws DuplicateRepositoryException when a repository with the same URL exists.
     */
    @Synchronized
    fun add(name: String, url: String): RepositoryConfig {
        val normalized = RepositoryUrls.normalize(url)
        val state = read()
        if (state.configs.any { it.url == normalized }) {
            throw DuplicateRepositoryException(normalized)
        }
        val config = RepositoryConfig(
            name = name.trim().ifBlank { RepositoryUrls.hostOf(normalized) },
            url = normalized,
            isDefault = state.configs.isEmpty(),
            builtIn = false,
        )
        write(RegistryState(state.configs + config, state.sync))
        return config
    }

    /**
     * Removes a user-added repository. The built-in repository cannot be removed and a missing URL
     * is not an error.
     *
     * @return true when an entry was removed.
     */
    @Synchronized
    fun remove(url: String): Boolean {
        val normalized = runCatching { RepositoryUrls.normalize(url) }.getOrNull() ?: return false
        val state = read()
        val target = state.configs.firstOrNull { it.url == normalized } ?: return false
        if (target.builtIn) return false
        val remaining = state.configs.filterNot { it.url == normalized }
        write(RegistryState(ensureDefault(remaining), state.sync - normalized))
        return true
    }

    /** Makes [url] the default repository, clearing the flag from the others. */
    @Synchronized
    fun setDefault(url: String): Boolean {
        val normalized = RepositoryUrls.normalize(url)
        val state = read()
        if (state.configs.none { it.url == normalized }) return false
        val updated = state.configs.map { it.copy(isDefault = it.url == normalized) }
        write(RegistryState(updated, state.sync))
        return true
    }

    /** Records that [url] was fetched successfully at time [at]. */
    @Synchronized
    fun markSynced(url: String, at: Long = clock()) {
        val normalized = runCatching { RepositoryUrls.normalize(url) }.getOrNull() ?: return
        val state = read()
        if (state.configs.none { it.url == normalized }) return
        write(RegistryState(state.configs, state.sync + (normalized to at)))
    }

    @Synchronized
    fun lastSynced(url: String): Long? = read().sync[url]

    // ------------------------------------------------------------------ persistence

    private data class RegistryState(
        val configs: List<RepositoryConfig>,
        val sync: Map<String, Long>,
    )

    private fun read(): RegistryState {
        if (!file.isFile) return RegistryState(emptyList(), emptyMap())
        val text = try {
            file.readText()
        } catch (failure: java.io.IOException) {
            return RegistryState(emptyList(), emptyMap())
        }
        if (text.isBlank()) return RegistryState(emptyList(), emptyMap())
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (failure: Exception) {
            null
        } ?: return RegistryState(emptyList(), emptyMap())
        val configs = (root["repositories"] as? JsonArray).orEmpty().mapNotNull(::readConfig)
        val sync = (root["sync"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.content?.toLongOrNull()?.let { key to it }
        }.toMap()
        return RegistryState(configs, sync)
    }

    private fun write(state: RegistryState) {
        val payload = JsonObject(
            buildMap {
                put("formatVersion", JsonPrimitive(FORMAT_VERSION))
                put("repositories", JsonArray(state.configs.map(::writeConfig)))
                put("sync", JsonObject(state.sync.mapValues { (_, at) -> JsonPrimitive(at) }))
            }
        )
        val parent = file.parentFile ?: File(".")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw ExtensionStorageException(parent.path, "cannot create repositories directory")
        }
        val temporary = File(parent, "${file.name}$STAGING_SUFFIX")
        try {
            temporary.writeText(payload.toString())
            if (!temporary.renameTo(file)) {
                file.writeText(payload.toString())
                temporary.delete()
            }
        } catch (failure: java.io.IOException) {
            temporary.delete()
            throw ExtensionStorageException(file.path, "cannot write repositories: ${failure.message}", failure)
        }
    }

    /**
     * The migration from the single-repository build.
     *
     * Whatever else is in the file, the repository this build ships with is present, is marked
     * built-in, and exactly one entry is the default. Installed extensions, the library and the
     * downloads live in other directories and are not touched here.
     */
    private fun migrate(state: RegistryState): RegistryState {
        val configs = state.configs.toMutableList()
        val builtInIndex = configs.indexOfFirst { it.url == defaultUrl }
        if (builtInIndex < 0) {
            configs += RepositoryConfig(
                name = defaultName,
                url = defaultUrl,
                isDefault = configs.none { it.isDefault },
                builtIn = true,
            )
        } else if (!configs[builtInIndex].builtIn) {
            configs[builtInIndex] = configs[builtInIndex].copy(builtIn = true)
        }
        return RegistryState(ensureDefault(configs), state.sync)
    }

    /** Leaves exactly one default, preferring the built-in repository, then the first entry. */
    private fun ensureDefault(configs: List<RepositoryConfig>): List<RepositoryConfig> {
        if (configs.isEmpty()) return configs
        if (configs.none { it.isDefault }) {
            val index = configs.indexOfFirst { it.url == defaultUrl }.takeIf { it >= 0 } ?: 0
            return configs.mapIndexed { position, config ->
                if (position == index) config.copy(isDefault = true) else config
            }
        }
        var seen = false
        return configs.map { config ->
            when {
                !config.isDefault -> config
                !seen -> {
                    seen = true
                    config
                }

                else -> config.copy(isDefault = false)
            }
        }
    }

    private fun writeConfig(config: RepositoryConfig): JsonObject = JsonObject(
        mapOf(
            "name" to JsonPrimitive(config.name),
            "url" to JsonPrimitive(config.url),
            "isDefault" to JsonPrimitive(config.isDefault),
            "builtIn" to JsonPrimitive(config.builtIn),
        )
    )

    private fun readConfig(element: JsonElement): RepositoryConfig? {
        val node = element as? JsonObject ?: return null
        val url = node.stringOrNull("url")?.takeIf { it.isNotBlank() } ?: return null
        return RepositoryConfig(
            name = node.stringOrNull("name").orEmpty().ifBlank { RepositoryUrls.hostOf(url) },
            url = url,
            isDefault = node.boolOrNull("isDefault") ?: false,
            builtIn = node.boolOrNull("builtIn") ?: false,
        )
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val STAGING_SUFFIX = ".partial"
    }
}
