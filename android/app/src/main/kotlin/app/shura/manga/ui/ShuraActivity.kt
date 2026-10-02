package app.shura.manga.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import app.shura.manga.R

/** The five top-level destinations, in the order they appear in the bottom navigation. */
enum class Tab(val titleRes: Int, val iconRes: Int) {
    HOME(R.string.nav_home, R.drawable.ic_home),
    LIBRARY(R.string.nav_library, R.drawable.ic_library),
    SOURCES(R.string.nav_sources, R.drawable.ic_sources),
    HISTORY(R.string.nav_history, R.drawable.ic_history),
    SETTINGS(R.string.nav_settings, R.drawable.ic_settings),
}

/**
 * The base every screen extends.
 *
 * It applies the chosen locale in [attachBaseContext] so string resources and RTL mirroring follow
 * the user's language, paints the dark window, and builds the shared chrome: the fixed bottom
 * navigation and the top bar used by detail screens. Screens compose their body with [buildScreen]
 * and never hard-code padding or colours.
 */
abstract class ShuraActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Prefs.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ShuraColors.background
        window.navigationBarColor = ShuraColors.surface
    }

    protected fun str(resId: Int): String = getString(resId)

    protected fun str(resId: Int, vararg args: Any): String = getString(resId, *args)

    /** The result of [buildScreen]: the whole hierarchy and the column a screen fills. */
    protected class Screen(val root: LinearLayout, val content: LinearLayout)

    /**
     * Builds the standard frame and installs it.
     *
     * A non-null [titleRes] adds a fixed top bar with a back arrow when [showBack]; a non-null
     * [bottomTab] pins the navigation and marks that tab active. [content] is the scrolling column
     * a screen puts its own views into.
     */
    protected fun buildScreen(
        titleRes: Int? = null,
        showBack: Boolean = false,
        bottomTab: Tab? = null,
        horizontalPaddingDp: Int = 16,
    ): Screen {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ShuraColors.background)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        if (titleRes != null) {
            root.addView(
                topBar(titleRes, showBack),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
            )
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(horizontalPaddingDp), dp(8), dp(horizontalPaddingDp), dp(24))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(scroll)
        if (bottomTab != null) {
            root.addView(
                bottomNav(bottomTab),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)),
            )
        }
        setContentView(root)
        return Screen(root, content)
    }

    private fun topBar(titleRes: Int, showBack: Boolean): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(4), 0, dp(8), 0)
        }
        if (showBack) {
            bar.addView(iconButton(R.drawable.ic_back) { finish() })
        } else {
            bar.setPaddingRelative(dp(16), 0, dp(8), 0)
        }
        bar.addView(
            text(str(titleRes), 19f, ShuraColors.onBackground, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        return bar
    }

    private fun bottomNav(current: Tab): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ShuraColors.surface)
        }
        container.addView(divider())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        Tab.values().forEach { tab -> row.addView(navItem(tab, tab == current)) }
        container.addView(row)
        return container
    }

    private fun navItem(tab: Tab, selected: Boolean): LinearLayout {
        val color = if (selected) ShuraColors.accent else ShuraColors.textTertiary
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setPaddingRelative(0, dp(6), 0, dp(6))
            addView(
                ImageView(this@ShuraActivity).apply {
                    setImageResource(tab.iconRes)
                    imageTintList = ColorStateList.valueOf(color)
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                },
            )
            addView(text(str(tab.titleRes), 10.5f, color).apply { gravity = Gravity.CENTER })
            if (!selected) setOnClickListener { navigate(tab) }
        }
    }

    private fun navigate(tab: Tab) {
        val target = when (tab) {
            Tab.HOME -> HomeActivity::class.java
            Tab.LIBRARY -> LibraryActivity::class.java
            Tab.SOURCES -> SourcesActivity::class.java
            Tab.HISTORY -> HistoryActivity::class.java
            Tab.SETTINGS -> SettingsActivity::class.java
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}
