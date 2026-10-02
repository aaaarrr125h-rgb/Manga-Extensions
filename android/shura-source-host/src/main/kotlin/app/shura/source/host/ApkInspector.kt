package app.shura.source.host

import java.io.File
import java.util.Properties
import java.util.jar.JarFile

/**
 * The platform's view of a downloaded APK.
 *
 * Two facts about an APK cannot be obtained with plain file I/O: the certificate it was signed
 * with, and the manifest its publisher declared. Both live inside the APK's signing block and
 * `AndroidManifest.xml`, which only `PackageManager` can read on a device. Everything else in this
 * package -- digesting, comparing, refusing, ordering -- is ordinary code that must be testable
 * without one.
 *
 * So the platform dependency is exactly this interface, and it is one interface rather than two on
 * purpose: the certificate and the manifest have to come from the *same* file, and an installer that
 * could read the manifest of one APK and the certificate of another would be verifying the wrong
 * pair.
 *
 * On a device the implementation is
 * `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)` followed by
 * [ExtensionManifestParser.fromMetaData]. Off a device a test supplies what it wants to assert on.
 */
interface ApkInspector {

    /**
     * The DER encoded signing certificate of [apk].
     *
     * @throws ExtensionVerificationException if the APK is not signed, or not readable.
     */
    fun certificateOf(apk: File): ByteArray

    /**
     * The descriptor [apk]'s publisher declared.
     *
     * @throws ExtensionLoadException if there is no descriptor, or it is unusable.
     */
    fun manifestOf(apk: File): ExtensionManifest
}

/**
 * An [ApkInspector] whose two answers are supplied by the caller.
 *
 * Used by tests, and by any host that already has the platform's answers in hand. The certificate
 * bytes may be arbitrary: the point of the verifier is that it hashes whatever it is given and
 * compares the digest, so a test can present a real digest and a wrong one without constructing
 * certificates.
 */
class FixedApkInspector(
    private val certificate: ByteArray,
    private val manifests: Map<String, ExtensionManifest> = emptyMap(),
    private val defaultManifest: ExtensionManifest? = null,
    private val failure: (() -> Throwable)? = null,
) : ApkInspector {

    override fun certificateOf(apk: File): ByteArray {
        failure?.let { throw it() }
        if (certificate.isEmpty()) {
            throw ExtensionVerificationException(apk.name, "no signing certificate")
        }
        return certificate
    }

    override fun manifestOf(apk: File): ExtensionManifest = failure?.let { throw it() }
        ?: manifests[apk.name]
        ?: defaultManifest
        ?: throw ExtensionLoadException("${apk.name} has no manifest")
}

/**
 * Reads the manifest out of a jar-shaped APK.
 *
 * An APK is a zip, and a jar is a zip, so the `META-INF/tachiyomi/extension.properties` descriptor
 * that Shura's own extensions carry can be read from either with no platform at all. This exists so
 * that the loop -- fetch, verify, install, register, load, browse -- can be driven end to end on a
 * plain JVM against real jars.
 *
 * It is **not** a substitute for [ExtensionManifestParser.fromMetaData] on a device: a real published
 * extension declares itself in `AndroidManifest.xml` meta-data, and a host that installed real
 * extensions must read it there.
 */
object JarDescriptorInspector : ApkInspector {

    const val DESCRIPTOR_PATH = "META-INF/tachiyomi/extension.properties"

    override fun certificateOf(apk: File): ByteArray = throw ExtensionVerificationException(
        apk.name,
        "a jar carries no signing certificate; use PackageManager for a real APK",
    )

    override fun manifestOf(apk: File): ExtensionManifest = JarFile(apk).use { jar ->
        val properties = jar.getJarEntry(DESCRIPTOR_PATH)?.let { entry ->
            jar.getInputStream(entry).use { stream -> Properties().apply { load(stream) } }
        } ?: throw ExtensionLoadException("${apk.name} has no $DESCRIPTOR_PATH")

        ExtensionManifestParser.fromProperties(
            packageName = properties.getProperty(JAR_PROPERTY_PACKAGE)?.takeIf { it.isNotBlank() }
                ?: throw ExtensionLoadException("${apk.name} has no $JAR_PROPERTY_PACKAGE"),
            versionCode = properties.getProperty(JAR_PROPERTY_VERSION_CODE)?.toLongOrNull()
                ?: throw ExtensionLoadException("${apk.name} has no usable $JAR_PROPERTY_VERSION_CODE"),
            versionName = properties.getProperty(JAR_PROPERTY_VERSION_NAME).orEmpty(),
            properties = properties,
        )
    }

    private const val JAR_PROPERTY_PACKAGE = "packageName"
    private const val JAR_PROPERTY_VERSION_CODE = "versionCode"
    private const val JAR_PROPERTY_VERSION_NAME = "versionName"
}

/** Keeps the descriptor path in one place: the loader reads the same one. */
internal const val JAR_DESCRIPTOR_ENTRY = JarDescriptorInspector.DESCRIPTOR_PATH