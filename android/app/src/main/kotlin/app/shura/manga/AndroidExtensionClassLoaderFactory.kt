package app.shura.manga

import android.content.Context
import app.shura.source.host.ExtensionClassLoaderFactory
import java.io.File

/**
 * The classloader strategy a real Android device needs.
 *
 * A hosted extension arrives as an APK, so its code is `classes.dex`, which
 * [app.shura.source.host.ExtensionClassLoader] (a `URLClassLoader`) cannot read. This factory
 * returns the Android counterpart, [AndroidAbiClassLoader], which is a `DexClassLoader` with the
 * same child-first delegation for the extension owned packages.
 *
 * The ABI jars staged as assets were dexed at build time specifically so they can be passed here.
 * If this wiring is missing the host falls back to its JVM default and every installed extension
 * fails to load on device, which is exactly the bug this class exists to prevent.
 */
class AndroidExtensionClassLoaderFactory(
    context: Context,
    private val optimizedDirectory: File = File(context.codeCacheDir, "shura-dex"),
) : ExtensionClassLoaderFactory {

    override fun create(classpath: List<File>, parent: ClassLoader): ClassLoader {
        // A DexClassLoader needs its optimized dex output directory to exist. Sharing one
        // directory is safe: ART keys the output on each source file's path, and the ABI jar is
        // deliberately the same file for every extension at a given level.
        optimizedDirectory.mkdirs()
        return AndroidAbiClassLoader(
            dexJars = classpath,
            optimizedDirectory = optimizedDirectory,
            parent = parent,
        )
    }
}
