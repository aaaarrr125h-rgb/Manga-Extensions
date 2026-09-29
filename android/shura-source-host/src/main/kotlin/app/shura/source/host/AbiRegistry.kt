package app.shura.source.host

import app.shura.source.api.ExtensionAbi
import java.io.File
import java.io.InputStream

/**
 * One ABI level, and the two artefacts that make it usable at runtime.
 *
 * [apiJar] holds the `eu.kanade.tachiyomi.*` surface of that level plus the bridge that turns
 * it into a `SourceProvider`. [bridgeClass] is looked up inside the extension's classloader,
 * never on the host's own classpath, which is what keeps the host free of both surfaces.
 */
data class AbiArtifact(
    val abi: ExtensionAbi,
    val apiJar: File,
    val bridgeClass: String,
) {
    init {
        require(apiJar.isFile) { "ABI $abi jar does not exist: $apiJar" }
    }
}

class AbiRegistry(artifacts: List<AbiArtifact>) {

    private val byAbi: Map<ExtensionAbi, AbiArtifact> =
        artifacts.associateBy { it.abi }.also {
            require(it.size == artifacts.size) { "Duplicate ABI artifacts: ${artifacts.map(AbiArtifact::abi)}" }
        }

    val supported: Set<ExtensionAbi> get() = byAbi.keys

    operator fun get(abi: ExtensionAbi): AbiArtifact? = byAbi[abi]

    fun require(abi: ExtensionAbi): AbiArtifact = byAbi[abi]
        ?: throw UnsupportedAbiException(abi, byAbi.keys.sortedBy { it.encoded }.map { it.version })

    companion object {
        const val BRIDGE_1_4: String = "app.shura.abi.v14.ShuraAbi14Bridge"
        const val BRIDGE_1_6: String = "app.shura.abi.v16.ShuraAbi16Bridge"

        fun bridgeClassFor(abi: ExtensionAbi): String = when (abi) {
            ExtensionAbi.V1_4 -> BRIDGE_1_4
            ExtensionAbi.V1_6 -> BRIDGE_1_6
        }

        /**
         * Reads a directory holding one jar per ABI level.
         *
         * The jars ship with the app; on a device they come out of `assets`, on a workstation
         * out of the build directory. A missing level is a startup failure, not something to
         * discover when the first 1.4 extension fails to load.
         */
        fun fromDirectory(directory: File): AbiRegistry {
            require(directory.isDirectory) { "ABI artifact directory does not exist: $directory" }
            val artifacts = ExtensionAbi.entries.mapNotNull { abi ->
                val jar = directory.resolve("shura-abi-${abi.version}.jar")
                if (jar.isFile) AbiArtifact(abi, jar, bridgeClassFor(abi)) else null
            }
            require(artifacts.isNotEmpty()) { "No shura-abi-*.jar found in $directory" }
            return AbiRegistry(artifacts)
        }
    }
}

class UnsupportedAbiException(
    val abi: ExtensionAbi,
    val supported: List<String>,
) : IllegalStateException(
    "No ABI artifact is available for extension API level ${abi.version}. Available: $supported",
)

internal fun InputStream.readExtensionProperties(): java.util.Properties =
    java.util.Properties().also { it.load(this) }
