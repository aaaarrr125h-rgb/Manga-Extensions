package app.shura.source.host

import java.io.File

/**
 * Locates the artefacts the host tests load.
 *
 * The ABI jars and the fixture extensions are staged by the `stageExtensionArtifacts` task into
 * a directory that is deliberately *not* on the test classpath. If they were, the classloader
 * tests would pass for the wrong reason: the test would find the classes through the parent
 * instead of proving the child-first delegation.
 */
object TestArtifacts {

    val extensionArtifacts: File = File(
        requireNotNull(System.getProperty("shura.extensionArtifacts")) {
            "System property shura.extensionArtifacts is not set; the test task must set it"
        },
    ).also {
        require(it.isDirectory) { "Staged extension artifacts directory does not exist: $it" }
    }

    fun abiJar(version: String): File = extensionArtifacts.resolve("shura-abi-$version.jar")

    fun extension(name: String): File = extensionArtifacts.resolve("$name.jar").also {
        require(it.isFile) { "Staged extension is missing: $it" }
    }

    /** The real, published `repo/index.json`, not a fixture copy of it. */
    val repoIndex: File = File(
        requireNotNull(System.getProperty("shura.repoIndex")) {
            "System property shura.repoIndex is not set; the test task must set it"
        },
    )
}
