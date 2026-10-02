package app.shura.manga.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import app.shura.manga.BuildConfig
import app.shura.manga.R

/**
 * The one screen that owns choices.
 *
 * Everything that used to be a top-level button (repository, extensions, downloads, diagnostics,
 * self test) now lives here under the heading it belongs to, so the five destinations in the
 * bottom bar stay about *using* Shura and this screen stays about configuring it.
 */
class SettingsActivity : AsyncScreenActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(titleRes = R.string.settings_title, bottomTab = Tab.SETTINGS).content
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun render() {
        content.removeAllViews()

        content.addView(sectionHeader(str(R.string.settings_general)))
        content.addView(
            settingRow(str(R.string.settings_language), languageLabel()) { chooseLanguage() },
        )
        content.addView(
            valueRow(str(R.string.settings_theme), str(R.string.settings_theme_dark)),
        )

        content.addView(spacer(12))
        content.addView(sectionHeader(str(R.string.settings_reading)))
        content.addView(
            switchRow(
                str(R.string.settings_save_position),
                checked = Prefs.savePosition(this),
            ) { Prefs.setSavePosition(this, it) },
        )
        content.addView(
            valueRow(str(R.string.settings_direction), str(R.string.settings_direction_vertical)),
        )
        content.addView(
            valueRow(str(R.string.settings_scroll), str(R.string.settings_scroll_continuous)),
        )

        content.addView(spacer(12))
        content.addView(sectionHeader(str(R.string.settings_downloads)))
        content.addView(
            switchRow(
                str(R.string.settings_wifi_only),
                checked = Prefs.wifiOnly(this),
            ) { Prefs.setWifiOnly(this, it) },
        )
        content.addView(
            settingRow(str(R.string.settings_manage_downloads)) {
                startActivity(Intent(this, DownloadsActivity::class.java))
            },
        )

        content.addView(spacer(12))
        content.addView(sectionHeader(str(R.string.settings_sources)))
        content.addView(
            settingRow(str(R.string.settings_repositories)) {
                startActivity(Intent(this, RepositoryActivity::class.java))
            },
        )
        content.addView(
            settingRow(str(R.string.settings_extensions)) {
                startActivity(Intent(this, ExtensionsActivity::class.java))
            },
        )

        content.addView(spacer(12))
        content.addView(sectionHeader(str(R.string.settings_advanced)))
        content.addView(
            settingRow(str(R.string.settings_diagnostics)) {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            },
        )
        content.addView(
            settingRow(str(R.string.settings_self_test)) {
                startActivity(Intent(this, app.shura.manga.MainActivity::class.java))
            },
        )
        content.addView(
            valueRow(
                str(R.string.settings_build_info),
                "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
            ),
        )
    }

    private fun languageLabel(): String = when (Prefs.language(this)) {
        Prefs.ENGLISH -> str(R.string.language_english)
        Prefs.ARABIC -> str(R.string.language_arabic)
        else -> str(R.string.language_system)
    }

    private fun chooseLanguage() {
        val values = arrayOf(Prefs.SYSTEM, Prefs.ENGLISH, Prefs.ARABIC)
        val labels = arrayOf(
            str(R.string.language_system),
            str(R.string.language_english),
            str(R.string.language_arabic),
        )
        val current = values.indexOf(Prefs.language(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setLanguage(this, values[which])
                dialog.dismiss()
                restartTask()
            }
            .show()
    }

    /** Rebuilds the task from Home so the new locale applies to every already-created screen. */
    private fun restartTask() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }
}
