package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import app.shura.manga.ShuraRepository
import app.shura.source.host.LibraryEntry
import app.shura.source.host.ReadingState
import app.shura.manga.R

/**
 * The user's own shelves, as a grid of covers or a compact list.
 *
 * The layout is a preference rather than a mode the screen invents, and both spellings read the
 * same [app.shura.source.host.LibraryStore], so switching between them never changes what is shown.
 */
class LibraryActivity : AsyncScreenActivity() {

    private lateinit var content: LinearLayout
    private lateinit var headerHost: LinearLayout

    private data class LibraryData(
        val entries: List<LibraryEntry>,
        val reading: List<ReadingState>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(bottomTab = Tab.LIBRARY).content
        headerHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(headerHost)
        addStatusViews(content)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        runLoad(
            loading = str(R.string.loading),
            retry = { load() },
            block = {
                val created = ShuraRepository.create(this)
                LibraryData(
                    entries = runCatching { created.library.entries() }.getOrDefault(emptyList()),
                    reading = runCatching { created.library.readingStates() }.getOrDefault(emptyList()),
                )
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(data: LibraryData) {
        content.removeAllViews()
        headerHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(headerHost)
        addStatusViews(content)
        status.text = ""

        val grid = Prefs.isGrid(this)
        headerHost.addView(
            sectionHeader(
                str(R.string.library_title),
                (if (grid) str(R.string.library_list) else str(R.string.library_grid)) to {
                    Prefs.setGrid(this, !grid)
                    show(data)
                },
            ),
        )

        if (data.entries.isEmpty()) {
            content.addView(
                emptyState(
                    str(R.string.library_empty),
                    str(R.string.library_empty_hint),
                    str(R.string.home_browse),
                ) { startActivity(Intent(this, SourcesActivity::class.java)) },
            )
            return
        }

        val readingByKey = data.reading.associateBy { Triple(it.packageName, it.sourceId, it.mangaRef) }
        val subtitle: (LibraryEntry) -> CharSequence? = { entry ->
            readingByKey[Triple(entry.packageName, entry.sourceId, entry.mangaRef)]?.chapterName
        }

        content.addView(spacer(4))
        if (grid) {
            content.addView(gridView(data.entries, subtitle))
        } else {
            data.entries.forEach { entry ->
                content.addView(listRow(entry, subtitle(entry)))
                content.addView(divider())
            }
        }
    }

    private fun gridView(
        entries: List<LibraryEntry>,
        subtitle: (LibraryEntry) -> CharSequence?,
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val widthDp = (resources.displayMetrics.widthPixels / density).toInt()
        val cellWidth = (widthDp - 16 * 2 - 8 * 2) / 3
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        entries.forEachIndexed { index, entry ->
            if (index % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                if (index > 0) grid.addView(spacer(16))
                grid.addView(row)
            }
            val cell = coverCell(cellWidth, entry.title, subtitle(entry)) { open(entry) }
            if (index % 3 != 0) {
                (cell.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
            }
            row?.addView(cell)
        }
        return grid
    }

    private fun listRow(entry: LibraryEntry, subtitle: CharSequence?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPaddingRelative(0, dp(10), 0, dp(10))
            setOnClickListener { open(entry) }
        }
        val image = cover(56, 78)
        CoverLoader.load(image, entry.thumbnailUrl, dp(56), dp(78))
        row.addView(image)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(12), 0, dp(12), 0)
        }
        column.addView(text(entry.title, 15f, ShuraColors.onBackground, maxLines = 2))
        column.addView(
            text(subtitle ?: "", 12f, ShuraColors.textSecondary, maxLines = 1).apply {
                setPaddingRelative(0, dp(2), 0, 0)
            },
        )
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_forward)
                imageTintList = android.content.res.ColorStateList.valueOf(ShuraColors.textTertiary)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            },
        )
        return row
    }

    private fun open(entry: LibraryEntry) {
        startActivity(
            Intent(this, MangaDetailsActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, entry.packageName)
                .putExtra(SourceExtras.SOURCE_ID, entry.sourceId)
                .putExtra(SourceExtras.MANGA_REF, entry.mangaRef)
                .putExtra(SourceExtras.MANGA_TITLE, entry.title),
        )
    }
}
