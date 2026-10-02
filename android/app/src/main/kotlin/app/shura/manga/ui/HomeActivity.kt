package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import app.shura.manga.ShuraRepository
import app.shura.source.host.InstalledSource
import app.shura.source.host.LibraryEntry
import app.shura.source.host.ReadingState
import app.shura.manga.R

/**
 * The landing screen: what to keep reading, what was just added, and how to find something new.
 *
 * It is assembled entirely from the stores the app already owns — the library, the reading
 * positions, the installed sources — so it is useful before any network call. It reloads on every
 * resume, because a screen that still shows yesterday's "continue reading" after a chapter was read
 * is worse than one that briefly shows a spinner.
 */
class HomeActivity : AsyncScreenActivity() {

    private lateinit var content: LinearLayout

    private data class HomeData(
        val library: List<LibraryEntry>,
        val reading: List<ReadingState>,
        val sources: List<InstalledSource>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(bottomTab = Tab.HOME).content
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
                HomeData(
                    library = runCatching { created.library.entries() }.getOrDefault(emptyList()),
                    reading = runCatching { created.library.readingStates() }.getOrDefault(emptyList()),
                    sources = runCatching { created.repository.catalogue() }.getOrDefault(emptyList()),
                )
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(data: HomeData) {
        content.removeAllViews()
        addStatusViews(content)
        status.text = ""

        if (data.library.isEmpty() && data.reading.isEmpty() && data.sources.isEmpty()) {
            content.addView(
                emptyState(
                    str(R.string.welcome_title),
                    str(R.string.welcome_subtitle),
                    str(R.string.welcome_start),
                ) { startActivity(Intent(this, SettingsActivity::class.java)) },
            )
            return
        }

        val byKey = data.library.associateBy { Triple(it.packageName, it.sourceId, it.mangaRef) }
        val reading = data.reading.sortedByDescending { it.updatedAtMillis }

        if (reading.isNotEmpty()) {
            content.addView(spacer(16))
            content.addView(sectionHeader(str(R.string.home_continue)))
            reading.take(10).forEach { state ->
                content.addView(continueRow(byKey[state.key()], state))
            }
        }

        val recent = data.library.sortedByDescending { it.addedAtMillis }
        if (recent.isNotEmpty()) {
            content.addView(spacer(20))
            content.addView(sectionHeader(str(R.string.home_recent)))
            content.addView(shelf(recent))
        }

        if (reading.isNotEmpty()) {
            content.addView(spacer(20))
            content.addView(sectionHeader(str(R.string.home_latest)))
            reading.take(10).forEach { state ->
                content.addView(latestRow(byKey[state.key()], state))
            }
        }

        content.addView(spacer(20))
        content.addView(sectionHeader(str(R.string.home_discover)))
        if (data.sources.isEmpty()) {
            content.addView(
                emptyState(
                    str(R.string.sources_empty),
                    str(R.string.sources_empty_hint),
                    str(R.string.sources_add_repository),
                ) { startActivity(Intent(this, RepositoryActivity::class.java)) },
            )
        } else {
            data.sources.sortedBy { it.descriptor.name }.forEach { source ->
                content.addView(
                    settingRow(source.descriptor.name, source.descriptor.language) { openBrowse(source) },
                )
            }
        }
    }

    private fun ReadingState.key(): Triple<String, Long, String> =
        Triple(packageName, sourceId, mangaRef)

    private fun continueRow(entry: LibraryEntry?, state: ReadingState): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPaddingRelative(0, dp(10), 0, dp(10))
            setOnClickListener { openReader(entry, state) }
        }
        val image = cover(56, 78)
        CoverLoader.load(image, entry?.thumbnailUrl, dp(56), dp(78))
        row.addView(image)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(12), 0, dp(12), 0)
        }
        column.addView(text(entry?.title ?: state.mangaRef, 15f, ShuraColors.onBackground, maxLines = 1))
        column.addView(
            text(state.chapterName, 13f, ShuraColors.textSecondary, maxLines = 1).apply {
                setPaddingRelative(0, dp(2), 0, 0)
            },
        )
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip(str(R.string.history_page, state.page + 1)))
        return row
    }

    private fun latestRow(entry: LibraryEntry?, state: ReadingState): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPaddingRelative(dp(4), dp(12), dp(4), dp(12))
            setOnClickListener { openReader(entry, state) }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(entry?.title ?: state.mangaRef, 15f, ShuraColors.onBackground, maxLines = 1))
        column.addView(
            text(state.chapterName, 12f, ShuraColors.textSecondary, maxLines = 1).apply {
                setPaddingRelative(0, dp(2), 0, 0)
            },
        )
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            text(
                DateUtils.getRelativeTimeSpanString(state.updatedAtMillis).toString(),
                11f,
                ShuraColors.textTertiary,
            ),
        )
        return row
    }

    private fun shelf(entries: List<LibraryEntry>): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        entries.take(20).forEachIndexed { index, entry ->
            val cell = coverCell(120, entry.title) { openDetails(entry) }
            if (index > 0) {
                (cell.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(12)
            }
            row.addView(cell)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        }
    }

    private fun openReader(entry: LibraryEntry?, state: ReadingState) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, state.packageName)
                .putExtra(SourceExtras.SOURCE_ID, state.sourceId)
                .putExtra(SourceExtras.MANGA_REF, state.mangaRef)
                .putExtra(SourceExtras.MANGA_TITLE, entry?.title ?: state.mangaRef)
                .putExtra(SourceExtras.CHAPTER_REF, state.chapterRef)
                .putExtra(SourceExtras.CHAPTER_NAME, state.chapterName),
        )
    }

    private fun openDetails(entry: LibraryEntry) {
        startActivity(
            Intent(this, MangaDetailsActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, entry.packageName)
                .putExtra(SourceExtras.SOURCE_ID, entry.sourceId)
                .putExtra(SourceExtras.MANGA_REF, entry.mangaRef)
                .putExtra(SourceExtras.MANGA_TITLE, entry.title),
        )
    }

    private fun openBrowse(source: InstalledSource) {
        startActivity(
            Intent(this, BrowseActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, source.packageName)
                .putExtra(SourceExtras.SOURCE_ID, source.descriptor.sourceId),
        )
    }
}
