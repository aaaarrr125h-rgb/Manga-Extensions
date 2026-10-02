package app.shura.source.host

import app.shura.source.api.ExtensionAbi

/**
 * One extension, as this host decides to act on it.
 *
 * [RepositoryExtension] is what the repository said. This is that entry after it has been checked
 * against what this host can actually do, so the fields are non-nullable and the reason for a
 * listed-but-unusable extension lives in [RepositorySnapshot.unusable] rather than in a null.
 */
data class AvailableExtension(
    val published: RepositoryExtension,
    val abi: ExtensionAbi,
    /** The artifact that will be downloaded and verified. See [ExtensionArtifact]. */
    val apkUrl: String,
) {
    val packageName: String get() = published.packageName
    val name: String get() = published.name
    val versionCode: Long get() = published.versionCode
    val versionName: String get() = published.versionName
    val contentWarning: String get() = published.contentWarning
    val sources: List<RepositorySource> get() = published.sources
}

/** A listed extension this host cannot install, and the one reason why. */
data class UnusableExtension(
    val packageName: String,
    val name: String,
    val reason: String,
)

/**
 * Where an extension actually comes from, and why only one of the two URLs is used.
 *
 * The index publishes both an `apkUrl` and a `jarUrl` for every extension. They are not
 * interchangeable, and the difference decides whether an install can be verified at all:
 *
 * - the `signingKey` in the index is the SHA-256 of the **APK's signing certificate**;
 * - therefore the only artifact whose authenticity this repository format lets a client check is the
 *   **APK**;
 * - a `jarUrl` sits on a different host and is covered by no signature this host can check, so
 *   fetching it and calling the result verified would be a lie.
 *
 * So an APK is what gets downloaded and what gets verified, and because a verified APK contains the
 * `classes.dex` that the extension actually runs, that same file is what gets loaded. `jarUrl` is
 * still carried on [RepositoryExtension] because the catalogue shows it, but it is never an install
 * source. An index entry that publishes only a `jarUrl` is reported as unusable rather than
 * installed unverified.
 */
enum class ArtifactUrlPolicy {

    /** The published repository's rule: artifacts are only fetched over TLS. */
    REQUIRE_HTTPS,

    /**
     * Additionally allow artifacts served from the loopback interface in plaintext.
     *
     * Exists so the loop can be exercised over a real socket against a real HTTP server without a
     * self-signed certificate standing in for TLS. It is not a general relaxation: `127.0.0.1` and
     * `[::1]` are the only hosts it permits, and a host that selects it should say so in its UI,
     * which is why [RepositorySnapshot.artifactUrlPolicy] is carried along.
     */
    ALLOW_LOOPBACK,
    ;

    internal fun accepts(url: String): Boolean {
        if (url.startsWith("https://")) return true
        if (this != ALLOW_LOOPBACK) return false
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return host == "127.0.0.1" || host == "[::1]" || host == "localhost"
    }
}

object ExtensionArtifact {

    const val APK_FIELD = "apkUrl"
    const val JAR_FIELD = "jarUrl"

    /** The URL of the signed APK, or a reason why there isn't one. */
    fun resolve(extension: RepositoryExtension, policy: ArtifactUrlPolicy = ArtifactUrlPolicy.REQUIRE_HTTPS): Result {
        val apk = extension.apkUrl?.trim().orEmpty()
        if (apk.isNotEmpty()) {
            if (!policy.accepts(apk)) {
                return Result.Unusable(
                    if (policy == ArtifactUrlPolicy.REQUIRE_HTTPS) {
                        "$APK_FIELD is not an https URL"
                    } else {
                        "$APK_FIELD is not https and is not served from loopback"
                    }
                )
            }
            return Result.Apk(apk)
        }
        val jar = extension.jarUrl?.trim().orEmpty()
        return if (jar.isEmpty()) {
            Result.Unusable("index publishes no $APK_FIELD")
        } else {
            Result.Unusable(
                "index publishes only $JAR_FIELD, which carries no signing certificate to verify against $KEY",
            )
        }
    }

    sealed class Result {
        data class Apk(val url: String) : Result()
        data class Unusable(val reason: String) : Result()
    }

    private const val KEY = "signingKey"
}

/**
 * A fetched, validated, self consistent view of a repository.
 *
 * Holding the parsed [RepositoryIndex] alongside the filtered [extensions] means a caller can
 * report on the repository as published *and* on what is installable here, without re-fetching.
 */
data class RepositorySnapshot(
    val url: String,
    val index: RepositoryIndex,
    /** [RepositoryIndex.signingKey], normalised to lower case. The trust anchor for every install. */
    val signingFingerprint: String,
    /** Entries this host can install, one per package, highest version first in the index's order. */
    val extensions: List<AvailableExtension>,
    /** Entries this host read but will not install, with the reason. */
    val unusable: List<UnusableExtension>,
    /** How many entries named an `extensionLib` level this host does not implement. */
    val unsupportedAbis: Map<String, Int>,
    /** The rule this host applied to artifact URLs, so a caller can report it. */
    val artifactUrlPolicy: ArtifactUrlPolicy,
) {
    val size: Int get() = extensions.size

    fun byPackage(packageName: String): AvailableExtension? =
        extensions.firstOrNull { it.packageName == packageName }

    /** The extension with the highest published version for [packageName], if this host can run it. */
    fun latestFor(packageName: String): AvailableExtension? = byPackage(packageName)

    fun abiHistogram(): Map<ExtensionAbi, Int> = extensions.groupingBy { it.abi }.eachCount()
}

/**
 * Fetches and validates a repository index over HTTP.
 *
 * Everything a caller needs is decided here, before any bytes of an extension are trusted:
 *
 * 1. the index is fetched through the injected [HttpTransport];
 * 2. it must parse as this repository's index shape -- malformed bytes are a response problem;
 * 3. `signingKey` must be a SHA-256 digest -- an index that cannot describe its own verification is
 *    refused outright, because every install made against it would fail closed;
 * 4. each entry is filtered to those with a supported ABI level and a verifiable signed APK.
 *
 * Steps 3 and 4 are the reason [RepositorySnapshot] exists: "the repository listed 579 extensions"
 * and "this host can install 4 of them" are different questions and both answers are needed.
 */
class RepositoryClient(
    val transport: HttpTransport,
    val indexUrl: String,
    private val supportedAbis: Set<ExtensionAbi> = setOf(ExtensionAbi.V1_4, ExtensionAbi.V1_6),
    private val artifactUrlPolicy: ArtifactUrlPolicy = ArtifactUrlPolicy.REQUIRE_HTTPS,
) {

    /**
     * Fetches the index and returns it without filtering.
     *
     * @throws RepositoryUnreachableException if the repository could not be reached.
     * @throws RepositoryResponseException if the response cannot be used.
     * @throws RepositoryIntegrityException if `signingKey` is not a SHA-256 digest.
     */
    fun fetchIndex(): RepositoryIndex {
        val response = transport.get(indexUrl)
        if (response.body.isEmpty()) {
            throw RepositoryResponseException(indexUrl, "index is empty")
        }
        val index = try {
            RepositoryIndexParser.parse(response.bodyAsText())
        } catch (e: RepositoryIndexException) {
            throw RepositoryResponseException(
                indexUrl,
                "index is not readable: ${e.message ?: "unparsable"}",
                e,
            )
        }
        if (index.extensions.isEmpty()) {
            throw RepositoryResponseException(indexUrl, "index lists no extensions")
        }
        return index
    }

    /** Fetches the index and filters it to what this host can install. */
    fun discover(): RepositorySnapshot {
        val index = fetchIndex()
        val fingerprint = SigningFingerprint.require(index.signingKey, indexUrl)

        val unusable = mutableListOf<UnusableExtension>()
        val unsupported = mutableMapOf<String, Int>()
        val installable = mutableListOf<AvailableExtension>()
        val seen = mutableSetOf<String>()

        for (extension in index.extensions) {
            val abi = extension.abi
            if (abi == null || abi !in supportedAbis) {
                unsupported[extension.extensionLib] = unsupported.getOrDefault(extension.extensionLib, 0) + 1
                unusable += UnusableExtension(
                    extension.packageName,
                    extension.name,
                    "extensionLib '${extension.extensionLib}' is not supported by this host",
                )
                continue
            }
            if (!seen.add(extension.packageName)) {
                unusable += UnusableExtension(
                    extension.packageName,
                    extension.name,
                    "index lists this package more than once",
                )
                continue
            }
            when (val artifact = ExtensionArtifact.resolve(extension, artifactUrlPolicy)) {
                is ExtensionArtifact.Result.Apk ->
                    installable += AvailableExtension(published = extension, abi = abi, apkUrl = artifact.url)

                is ExtensionArtifact.Result.Unusable ->
                    unusable += UnusableExtension(extension.packageName, extension.name, artifact.reason)
            }
        }
        return RepositorySnapshot(
            url = indexUrl,
            index = index,
            signingFingerprint = fingerprint,
            extensions = installable,
            unusable = unusable,
            unsupportedAbis = unsupported,
            artifactUrlPolicy = artifactUrlPolicy,
        )
    }
}