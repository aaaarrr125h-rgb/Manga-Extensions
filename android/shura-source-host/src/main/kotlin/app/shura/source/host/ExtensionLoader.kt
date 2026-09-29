package app.shura.source.host

import app.shura.source.api.ExtensionMetadata
import app.shura.source.api.LoadedExtension
import app.shura.source.api.SourceDescriptor
import app.shura.source.api.SourceProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.Closeable
import java.io.File
import java.util.Properties
import java.util.jar.JarFile

/**
 * Turns a file on disk into a set of live [SourceProvider]s.
 *
 * One loader call produces one classloader. The classloader is owned by the returned
 * [LoadedExtension] and closed with it, because an extension that has been upgraded or removed
 * must not keep serving classes out of the jar it was replaced from.
 */
class ExtensionLoader(
    private val abiRegistry: AbiRegistry,
    private val parent: ClassLoader = ExtensionClassLoader::class.java.classLoader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {

    private val openLoaders = mutableListOf<ExtensionClassLoader>()

    /**
     * Loads the extension packaged in [extensionJar].
     *
     * [manifest] is passed in rather than read here because the two supported containers read
     * it differently: an APK through `PackageManager`, a plain jar through its properties file.
     * Both end up at [ExtensionManifestParser].
     */
    fun load(extensionJar: File, manifest: ExtensionManifest): LoadedExtension {
        require(extensionJar.isFile) { "Extension file does not exist: $extensionJar" }

        val artifact = abiRegistry.require(manifest.abi)
        val loader = ExtensionClassLoader(
            urls = arrayOf(extensionJar.toURI().toURL(), artifact.apiJar.toURI().toURL()),
            parent = parent,
        )
        synchronized(openLoaders) { openLoaders += loader }

        try {
            val providers = instantiateProviders(loader, artifact, manifest)
            return LoadedExtension(
                metadata = ExtensionMetadata(
                    packageName = manifest.packageName,
                    versionName = manifest.versionName,
                    versionCode = manifest.versionCode,
                    contentWarning = manifest.contentWarning,
                ),
                abi = manifest.abi,
                entryClass = manifest.entryClass,
                providers = providers,
                classLoader = loader,
            )
        } catch (failure: Throwable) {
            synchronized(openLoaders) { openLoaders -= loader }
            loader.close()
            throw failure
        }
    }

    /** Reads the descriptor a plain jar carries and then loads it. */
    fun loadFromJar(extensionJar: File): LoadedExtension {
        // Checked before the jar is opened, so a missing file is reported as a missing file and
        // not as a missing descriptor inside it.
        require(extensionJar.isFile) { "Extension file does not exist: $extensionJar" }
        return load(extensionJar, readManifest(extensionJar))
    }

    private fun instantiateProviders(
        loader: ExtensionClassLoader,
        artifact: AbiArtifact,
        manifest: ExtensionManifest,
    ): List<SourceProvider> {
        val sourceInterface = loader.loadClass(SOURCE_INTERFACE)
        val factoryInterface = loader.loadClass(SOURCE_FACTORY_INTERFACE)
        val catalogueInterface = loader.loadClass(CATALOGUE_SOURCE_INTERFACE)
        val bridgeType = loader.loadClass(artifact.bridgeClass)

        val entryClass = runCatching { loader.loadClass(manifest.entryClass) }.getOrElse {
            throw ExtensionLoadException(
                "Extension ${manifest.packageName} declares entry class '${manifest.entryClass}', " +
                    "which is not in the extension's classpath",
                it,
            )
        }

        val instances: List<Any> = when {
            factoryInterface.isAssignableFrom(entryClass) -> {
                val factory = entryClass.getDeclaredConstructor().newInstance()
                val created = factoryInterface.getMethod("createSources").invoke(factory)
                // A source factory may legitimately hand back an empty list, and a broken one may
                // hand back null. Neither is a reason to lose the whole extension.
                (created as? List<*>).orEmpty().filterNotNull()
            }

            sourceInterface.isAssignableFrom(entryClass) ->
                listOf(entryClass.getDeclaredConstructor().newInstance())

            else -> throw ExtensionLoadException(
                "Entry class '${manifest.entryClass}' of ${manifest.packageName} implements " +
                    "neither eu.kanade.tachiyomi.source.SourceFactory nor " +
                    "eu.kanade.tachiyomi.source.Source",
            )
        }

        if (instances.isEmpty()) {
            throw ExtensionLoadException("Extension ${manifest.packageName} produced no sources")
        }

        val metadata = ExtensionMetadata(
            packageName = manifest.packageName,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            contentWarning = manifest.contentWarning,
        )
        val bridgeConstructor = bridgeConstructorFor(bridgeType, catalogueInterface)

        return instances.map { instance ->
            if (!catalogueInterface.isInstance(instance)) {
                throw ExtensionLoadException(
                    "A source of ${manifest.packageName} implements Source but not CatalogueSource; " +
                        "Shura hosts catalogue sources only",
                )
            }
            val arguments = if (bridgeConstructor.parameterCount == BRIDGE_WITH_DISPATCHER_ARITY) {
                arrayOf(instance, metadata, ioDispatcher)
            } else {
                arrayOf(instance, metadata)
            }
            bridgeConstructor.newInstance(*arguments) as SourceProvider
        }
    }

    /**
     * Finds the bridge constructor to use, by shape rather than by a hard coded arity.
     *
     * Both bridges take the extension facing source and the host facing metadata. Only the 1.4
     * bridge also takes a dispatcher, because only 1.4 has to block an RxJava call off the
     * caller's thread; the 1.6 surface is `suspend` throughout and has nothing to hand over.
     * The signature is resolved reflectively, so neither surface is a compile time dependency
     * of the host.
     */
    private fun bridgeConstructorFor(
        bridgeType: Class<*>,
        catalogueInterface: Class<*>,
    ): java.lang.reflect.Constructor<*> {
        val candidates = bridgeType.declaredConstructors.filter { constructor ->
            val parameters = constructor.parameterTypes
            parameters.firstOrNull() == catalogueInterface &&
                ExtensionMetadata::class.java in parameters &&
                parameters.all { it == catalogueInterface || it == ExtensionMetadata::class.java || it == CoroutineDispatcher::class.java }
        }
        return candidates.maxByOrNull { it.parameterCount }
            ?: throw ExtensionLoadException(
                "Bridge ${bridgeType.name} has no constructor taking " +
                    "(CatalogueSource, ExtensionMetadata[, CoroutineDispatcher])",
            )
    }

    private fun readManifest(extensionJar: File): ExtensionManifest = JarFile(extensionJar).use { jar ->
        val properties = jar.getJarEntry(JAR_DESCRIPTOR_PATH)?.let { entry ->
            jar.getInputStream(entry).use { stream -> Properties().apply { load(stream) } }
        } ?: throw ExtensionLoadException("$extensionJar has no $JAR_DESCRIPTOR_PATH")

        ExtensionManifestParser.fromProperties(
            packageName = properties.required(JAR_PROPERTY_PACKAGE, extensionJar),
            versionCode = properties.getProperty(JAR_PROPERTY_VERSION_CODE)?.toLongOrNull()
                ?: throw ExtensionLoadException("$extensionJar has no usable $JAR_PROPERTY_VERSION_CODE"),
            versionName = properties.getProperty(JAR_PROPERTY_VERSION_NAME).orEmpty(),
            properties = properties,
        )
    }

    private fun Properties.required(name: String, jar: File): String =
        getProperty(name)?.takeIf { it.isNotBlank() }
            ?: throw ExtensionLoadException("$jar has no '$name' in $JAR_DESCRIPTOR_PATH")

    override fun close() {
        val loaders = synchronized(openLoaders) {
            openLoaders.toList().also { openLoaders.clear() }
        }
        loaders.forEach { runCatching { it.close() } }
    }

    companion object {
        const val JAR_DESCRIPTOR_PATH = "META-INF/tachiyomi/extension.properties"

        const val JAR_PROPERTY_PACKAGE = "packageName"
        const val JAR_PROPERTY_VERSION_CODE = "versionCode"
        const val JAR_PROPERTY_VERSION_NAME = "versionName"

        /** The widest bridge constructor: source, metadata, dispatcher. */
        private const val BRIDGE_WITH_DISPATCHER_ARITY = 3

        private const val SOURCE_INTERFACE = "eu.kanade.tachiyomi.source.Source"
        private const val SOURCE_FACTORY_INTERFACE = "eu.kanade.tachiyomi.source.SourceFactory"
        private const val CATALOGUE_SOURCE_INTERFACE = "eu.kanade.tachiyomi.source.CatalogueSource"
    }
}

class ExtensionLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
