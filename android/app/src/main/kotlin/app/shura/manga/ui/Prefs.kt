package app.shura.manga.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

/**
 * The small set of user choices, kept in one private preferences file.
 *
 * Deliberately tiny: reading positions and the library are real data and live in their own stores;
 * this is only presentation state (language, library layout, reader toggles).
 */
object Prefs {
    private const val FILE = "shura_prefs"

    const val LANGUAGE = "language"
    const val LIBRARY_GRID = "library_grid"
    const val SAVE_POSITION = "save_position"
    const val WIFI_ONLY = "wifi_only"

    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val ARABIC = "ar"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun language(context: Context): String =
        get(context).getString(LANGUAGE, SYSTEM) ?: SYSTEM

    fun setLanguage(context: Context, language: String) {
        get(context).edit().putString(LANGUAGE, language).apply()
    }

    fun isGrid(context: Context): Boolean = get(context).getBoolean(LIBRARY_GRID, true)
    fun setGrid(context: Context, grid: Boolean) {
        get(context).edit().putBoolean(LIBRARY_GRID, grid).apply()
    }

    fun savePosition(context: Context): Boolean = get(context).getBoolean(SAVE_POSITION, true)
    fun setSavePosition(context: Context, value: Boolean) {
        get(context).edit().putBoolean(SAVE_POSITION, value).apply()
    }

    fun wifiOnly(context: Context): Boolean = get(context).getBoolean(WIFI_ONLY, false)
    fun setWifiOnly(context: Context, value: Boolean) {
        get(context).edit().putBoolean(WIFI_ONLY, value).apply()
    }

    /**
     * Rewrites a base context's configuration with the chosen language.
     *
     * Applied in `attachBaseContext`, so every activity in the app reads its strings, and mirrors
     * its layouts, from the same locale the user picked. "system" leaves the device locale alone.
     */
    fun localized(context: Context): Context {
        val language = language(context)
        if (language == SYSTEM) return context
        val locale = when (language) {
            ENGLISH -> Locale.ENGLISH
            ARABIC -> Locale("ar")
            else -> return context
        }
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
