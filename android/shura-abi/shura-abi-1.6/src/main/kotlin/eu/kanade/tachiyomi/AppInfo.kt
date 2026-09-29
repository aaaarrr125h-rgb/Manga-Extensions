package eu.kanade.tachiyomi

/**
 * Host application information, as seen by an extension.
 *
 * The extensions are compiled against this stub; the host supplies the real implementation.
 */
@Suppress("Unused")
object AppInfo {
    /**
     * Version code of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionCode(): Int = throw Exception("Stub!")

    /**
     * Version name of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionName(): String = throw Exception("Stub!")
}
