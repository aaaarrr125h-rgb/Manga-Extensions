package app.shura.source.host

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Small file helpers shared by the library and download stores.
 *
 * Both stores keep one JSON document that must never be observed half written: a process kill in
 * the middle of an update would otherwise leave a registry that no longer parses and every entry
 * in it would look lost. The document is therefore written to a sibling temporary file and moved
 * into place, which is atomic on the filesystems the app uses.
 */
internal object Storage {

    /** Writes [text] to [file] atomically, creating the parent directory if needed. */
    fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.partial")
        try {
            temporary.writeText(text)
            if (!temporary.renameTo(file)) {
                file.writeText(text)
                temporary.delete()
            }
        } catch (e: IOException) {
            temporary.delete()
            throw ExtensionStorageException(file.path, "cannot write ${file.name}: ${e.message}", e)
        }
    }

    /** Reads [file] as text, or null when it is absent or blank. */
    fun readOrNull(file: File): String? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            throw ExtensionStorageException(file.path, "cannot read ${file.name}: ${e.message}", e)
        }
        return text.takeIf { it.isNotBlank() }
    }

    /** A stable, filesystem safe directory name for an arbitrary key. */
    fun digestName(vararg parts: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString(separator = "\u0000") { it?.toString().orEmpty() }.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(32)
    }
}
