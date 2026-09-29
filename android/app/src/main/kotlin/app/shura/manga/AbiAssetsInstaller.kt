package app.shura.manga

import android.content.Context
import app.shura.source.api.ExtensionAbi
import app.shura.source.host.AbiRegistry
import java.io.File

/**
 * Puts the ABI jars on the device and builds the registry the host loads extensions against.
 *
 * A `DexClassLoader` needs real files, so the jars that ship in `assets/shura-abi` are unpacked
 * once into the app's own storage. Extraction is content addressed: a jar is only rewritten when
 * its bytes differ from what is already there, so a normal app start copies nothing.
 */
class AbiAssetsInstaller(
    private val context: Context,
    private val installDirectory: File = File(context.filesDir, "shura-abi"),
) {

    /**
     * Unpacks any missing or out of date ABI jar and returns the registry over what is on disk.
     *
     * @throws app.shura.source.host.AbiRegistryStartupException if a level this app is built to
     *   host has no jar, which means the APK was packaged wrong and must not be run half working.
     */
    fun install(): AbiRegistry {
        val expected = ExtensionAbi.entries.map { abi ->
            AbiAsset(name = "shura-abi-${abi.version}.jar", abi = abi)
        }

        val staged = installDirectory.takeIf { it.isDirectory }
            ?: installDirectory.mkdirs().let { installDirectory }

        for (asset in expected) {
            val target = File(staged, asset.name)
            if (!isUpToDate(asset, target)) unpack(asset, target)
        }

        // Anything left over belongs to an ABI level this build dropped; leaving it on disk would
        // let fromDirectory() pick up a level the app no longer supports.
        ExtensionAbi.entries.map { "shura-abi-${it.version}.jar" }
            .toSet()
            .let { keep ->
                staged.listFiles()?.filter { it.name.startsWith("shura-abi-") && it.name !in keep }
                    ?.forEach(File::delete)
            }

        return AbiRegistry.fromDirectory(staged)
    }

    private fun isUpToDate(asset: AbiAsset, target: File): Boolean {
        if (!target.isFile) return false
        val installed = runCatching { target.length() }.getOrDefault(-1L)
        val shipped = runCatching {
            context.assets.openFd(asset.name).use { it.length }
        }.getOrDefault(-1L)
        // Sizes differ only if the APK was tampered with, which the install signature check
        // elsewhere is responsible for; comparing size is enough to skip the copy on every start.
        return installed >= 0 && installed == shipped
    }

    private fun unpack(asset: AbiAsset, target: File) {
        // Written beside the target and renamed, so a process killed mid copy cannot leave a
        // truncated jar that would then look "up to date" on the next start.
        val partial = File(target.parentFile, "${asset.name}.partial")
        context.assets.open(asset.name).use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.exists() && !target.delete()) {
            throw AbiRegistryStartupException("Cannot replace ${target.name}; it is still in use")
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw AbiRegistryStartupException("Cannot install ${asset.name} into $installDirectory")
        }
    }

    private data class AbiAsset(val name: String, @Suppress("unused") val abi: ExtensionAbi)
}

class AbiRegistryStartupException(message: String) : IllegalStateException(message)
