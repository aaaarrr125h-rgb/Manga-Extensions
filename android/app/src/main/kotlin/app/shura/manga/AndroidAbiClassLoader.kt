package app.shura.manga

import app.shura.source.host.ExtensionClassLoader
import dalvik.system.DexClassLoader
import java.io.File

/**
 * The Android counterpart of [ExtensionClassLoader].
 *
 * `ExtensionClassLoader` extends `URLClassLoader`, which reads `.class` bytecode off a jar. That
 * is a JVM capability Android's runtime does not have: a `DexClassLoader` can only read
 * `classes.dex`. The delegation policy is therefore identical and the isolated package list is
 * taken from the host rather than restated here, so the two cannot drift apart.
 *
 * The jars handed in must already be dexed. That is what the `shuraAbiAssets` staging task does
 * at build time; nothing here shells out to a tool on the device.
 */
class AndroidAbiClassLoader(
    dexJars: List<File>,
    optimizedDirectory: File,
    parent: ClassLoader,
    childFirstPackages: List<String> = ExtensionClassLoader.EXTENSION_OWNED_PACKAGES,
) : DexClassLoader(
    // The dex path is colon separated, and the order is significant: the extension jar first so
    // its own classes are found without a second pass over the ABI jar.
    dexJars.joinToString(File.pathSeparator) { it.absolutePath },
    optimizedDirectory.absolutePath,
    null,
    parent,
) {

    private val childFirst: List<String> = childFirstPackages

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { alreadyLoaded ->
                if (resolve) resolveClass(alreadyLoaded)
                return alreadyLoaded
            }

            if (isChildFirst(name)) {
                try {
                    val defined = findClass(name)
                    if (resolve) resolveClass(defined)
                    return defined
                } catch (_: ClassNotFoundException) {
                    // Fall through to the parent: an extension may legitimately rely on a class
                    // the ABI jar does not carry.
                }
            }

            return super.loadClass(name, resolve)
        }
    }

    private fun isChildFirst(name: String): Boolean =
        childFirst.any { name == it || name.startsWith("$it.") }
}
