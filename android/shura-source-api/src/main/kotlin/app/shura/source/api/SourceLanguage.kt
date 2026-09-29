package app.shura.source.api

/**
 * Normalisation for source languages.
 *
 * The repository index publishes the historical ISO 639-1 style codes (`"all"`, `"other"`), and
 * `tachiyomix` 1.6 documents that a host is expected to map those onto the BCP 47 tags
 * `"mul"` (multiple languages) and `"und"` (undetermined / language independent). Shura does
 * that once, here, so nothing downstream has to know the old codes exist.
 */
object SourceLanguage {

    const val MULTIPLE: String = "mul"
    const val UNDETERMINED: String = "und"

    private val LEGACY_ALIASES: Map<String, String> = mapOf(
        "all" to MULTIPLE,
        "multi" to MULTIPLE,
        "other" to UNDETERMINED,
        "" to UNDETERMINED,
    )

    /** Maps a raw index/extension language onto the tag Shura stores. */
    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return UNDETERMINED
        val lowered = trimmed.lowercase()
        return LEGACY_ALIASES[lowered] ?: trimmed
    }

    /** True when the value is already a tag this host will store unchanged. */
    fun isNormalized(value: String): Boolean = value == normalize(value)

    fun isMultiple(value: String?): Boolean = normalize(value) == MULTIPLE

    fun isUndetermined(value: String?): Boolean = normalize(value) == UNDETERMINED
}
