package androidx.preference

/** Compile time stand in for the platform class of the same name. */
open class Preference(val key: String? = null) {
    var title: CharSequence? = null
    var summary: CharSequence? = null
    var isEnabled: Boolean = true
    var isVisible: Boolean = true
}

/** Compile time stand in for the platform class of the same name. */
open class PreferenceScreen {
    private val children = mutableListOf<Preference>()

    fun addPreference(preference: Preference) {
        children += preference
    }

    fun removePreference(preference: Preference) {
        children -= preference
    }

    fun findPreference(key: String): Preference? = children.firstOrNull { it.key == key }
}
