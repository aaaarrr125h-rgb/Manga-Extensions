package app.shura.source.host

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How one configured repository fared on the last refresh.
 *
 * Reachable and unreachable are both first-class: a repository that is down is reported with its
 * error and the time it last answered, and every other repository keeps working. An exception here
 * would make one bad URL look like a broken app.
 */
data class RepositoryStatus(
    val config: RepositoryConfig,
    val snapshot: RepositorySnapshot?,
    val error: String?,
    val lastSyncedAtMillis: Long?,
) {
    val isReachable: Boolean get() = snapshot != null

    /** Installable extensions this repository currently publishes, or 0 when it is unreachable. */
    val extensionCount: Int get() = snapshot?.extensions?.size ?: 0

    val unusableCount: Int get() = snapshot?.unusable?.size ?: 0
}

/** One installable extension, together with the repository it came from. */
data class CataloguedExtension(
    val config: RepositoryConfig,
    val available: AvailableExtension,
) {
    val packageName: String get() = available.packageName
    val name: String get() = available.name
    val versionCode: Long get() = available.versionCode
    val versionName: String get() = available.versionName
    val abi get() = available.abi
    val contentWarning: String get() = available.contentWarning
    val sources: List<RepositorySource> get() = available.sources
    val iconUrl: String? get() = available.published.iconUrl
}

/** A package several repositories publish, and which repository's copy was kept and why-not. */
data class SkippedDuplicate(
    val packageName: String,
    val kept: RepositoryConfig,
    val ignored: RepositoryConfig,
)

/** A refresh across every configured repository. */
data class RepositoryCatalogue(
    val repositories: List<RepositoryStatus>,
    /** Deduplicated, best-source-first, sorted by display name. */
    val extensions: List<CataloguedExtension>,
    val duplicates: List<SkippedDuplicate>,
) {
    val reachableCount: Int get() = repositories.count { it.isReachable }
    val totalPublished: Int get() = repositories.sumOf { it.extensionCount }

    fun statusFor(url: String): RepositoryStatus? = repositories.firstOrNull { it.config.url == url }
}

/** Builds the [ExtensionRepository] for one configured URL. The wiring decides transport and store. */
fun interface RepositoryFactory {
    fun create(config: RepositoryConfig): ExtensionRepository
}

/**
 * The repository list, and the one repository loop per URL behind it.
 *
 * The backend is intentionally untouched: each configured repository is an ordinary
 * [ExtensionRepository], sharing one [ExtensionStore] so that installs from any repository land in
 * the same registry and the installed set is the union. This class adds only what multiple URLs
 * need, and nothing else:
 *
 * - **aggregation.** [refresh] fetches every repository and merges the results, so the extensions
 *   screen can show one list without knowing there is more than one repository;
 * - **deduplication.** a package published by several repositories is shown once. The default
 *   repository's copy wins; otherwise the higher version does, and a stable URL tie-break keeps the
 *   choice from flickering between refreshes;
 * - **routing.** [install] sends the download to the repository the chosen entry came from, so the
 *   right `signingKey` verifies it.
 *
 * Failures are contained: [refresh] turns a repository that cannot be reached into a
 * [RepositoryStatus] with an error rather than throwing, so the rest of the list still loads.
 */
class RepositoryManager(
    private val registry: RepositoryRegistry,
    private val factory: RepositoryFactory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val repositories = mutableMapOf<String, ExtensionRepository>()

    fun configs(): List<RepositoryConfig> = registry.configs()

    fun defaultConfig(): RepositoryConfig? = registry.default()

    fun isDefault(url: String): Boolean = registry.default()?.url == url

    /** Adds a repository. Validation and duplicate refusal live in [RepositoryRegistry]. */
    fun add(name: String, url: String): RepositoryConfig = registry.add(name, url)

    /** Removes a user-added repository. The built-in one cannot be removed. */
    fun remove(url: String): Boolean {
        repositories.remove(url)
        return registry.remove(url)
    }

    fun setDefault(url: String): Boolean = registry.setDefault(url)

    fun lastSynced(url: String): Long? = registry.lastSynced(url)

    /**
     * Fetches every configured repository.
     *
     * @return the merged catalogue, including statuses for repositories that failed.
     */
    suspend fun refresh(): RepositoryCatalogue = withContext(ioDispatcher) {
        val statuses = registry.configs().map { config -> refreshOne(config) }
        val extensions = aggregate(statuses)
        RepositoryCatalogue(
            repositories = statuses,
            extensions = extensions,
            duplicates = duplicates(statuses, extensions),
        )
    }

    /** A repository's loop, created once per URL. */
    fun repositoryFor(config: RepositoryConfig): ExtensionRepository =
        repositories.getOrPut(config.url) { factory.create(config) }

    /**
     * Fetches a repository without configuring it, for the Add flow.
     *
     * @throws RepositoryException when the URL does not answer with a usable index.
     */
    suspend fun validate(config: RepositoryConfig): RepositorySnapshot =
        withContext(ioDispatcher) { factory.create(config).discover() }

    /** Installs an extension from the repository it came from. */
    suspend fun install(extension: CataloguedExtension): InstallOutcome =
        repositoryFor(extension.config).install(extension.available)

    fun installed(): List<InstalledExtension> = primary().installed()

    suspend fun isLoadable(packageName: String): Boolean = primary().isLoadable(packageName)

    suspend fun catalogue(): List<InstalledSource> = primary().catalogue()

    suspend fun openAll() = primary().openAll()

    private fun primary(): ExtensionRepository {
        val config = registry.default() ?: registry.configs().first()
        return repositoryFor(config)
    }

    private suspend fun refreshOne(config: RepositoryConfig): RepositoryStatus {
        val repository = repositoryFor(config)
        return try {
            val snapshot = repository.discover()
            registry.markSynced(config.url)
            RepositoryStatus(config, snapshot, null, registry.lastSynced(config.url))
        } catch (failure: Throwable) {
            RepositoryStatus(config, null, failure.describeRepository(), registry.lastSynced(config.url))
        }
    }

    /** Merges the reachable repositories, keeping one entry per package. */
    private fun aggregate(statuses: List<RepositoryStatus>): List<CataloguedExtension> {
        val defaultUrl = registry.default()?.url
        val best = LinkedHashMap<String, CataloguedExtension>()
        statuses.filter { it.snapshot != null }.forEach { status ->
            status.snapshot!!.extensions.forEach { available ->
                val candidate = CataloguedExtension(status.config, available)
                val current = best[available.packageName]
                if (current == null || better(candidate, current, defaultUrl)) {
                    best[available.packageName] = candidate
                }
            }
        }
        return best.values.sortedBy { it.name.lowercase() }
    }

    private fun better(
        candidate: CataloguedExtension,
        current: CataloguedExtension,
        defaultUrl: String?,
    ): Boolean {
        val candidateDefault = candidate.config.url == defaultUrl
        val currentDefault = current.config.url == defaultUrl
        if (candidateDefault != currentDefault) return candidateDefault
        if (candidate.versionCode != current.versionCode) return candidate.versionCode > current.versionCode
        return candidate.config.url < current.config.url
    }

    private fun duplicates(
        statuses: List<RepositoryStatus>,
        extensions: List<CataloguedExtension>,
    ): List<SkippedDuplicate> {
        val winners = extensions.associateBy { it.packageName }
        val result = mutableListOf<SkippedDuplicate>()
        statuses.filter { it.snapshot != null }.forEach { status ->
            status.snapshot!!.extensions.forEach { available ->
                val winner = winners[available.packageName]
                if (winner != null && winner.config.url != status.config.url) {
                    result += SkippedDuplicate(available.packageName, winner.config, status.config)
                }
            }
        }
        return result
    }

    private fun Throwable.describeRepository(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
