package app.shura.source.host

import java.net.URL
import java.net.URLClassLoader

/**
 * The classloader an extension is loaded into.
 *
 * One of these exists per installed extension, never one per process. That is not tidiness, it
 * is the only way the host can hold two ABI levels at once: `eu.kanade.tachiyomi.source.Source`
 * exists in both the 1.4 and the 1.6 surface with incompatible members, and a single parent
 * could only ever hold one of them.
 *
 * The delegation is therefore:
 *
 * - child first for [EXTENSION_OWNED_PACKAGES], so the extension and its ABI jar supply those
 *   classes and a stray copy on the parent can never win;
 * - parent first for everything else, so [app.shura.source.api] stays one single class and the
 *   host can cast a bridge to `SourceProvider` across the boundary.
 */
class ExtensionClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    childFirstPackages: List<String> = EXTENSION_OWNED_PACKAGES,
) : URLClassLoader(urls, parent) {

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

    companion object {
        /**
         * Packages that an extension and its ABI jar own outright.
         *
         * `app.shura.abi` is the bridge itself: it implements the extension facing interface, so
         * it has to be the copy that sits next to the extension's own `eu.kanade` classes.
         */
        val EXTENSION_OWNED_PACKAGES: List<String> = listOf(
            "eu.kanade.tachiyomi",
            "keiyoushi.source",
            "app.shura.abi",
        )
    }
}
