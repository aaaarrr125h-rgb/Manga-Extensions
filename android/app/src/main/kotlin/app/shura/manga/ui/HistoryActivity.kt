package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import app.shura.manga.ShuraRepository
import app.shura.source.host.LibraryEntry
import app.shura.source.host.ReadingState
import app.shura.manga.R

/**
 * Every manga with a saved position, most recent first.
 *
 * This reads the same [app.shura.source.host.LibraryStore] positions the reader writes, so
 * "continue" here and "continue reading" on Home can never disagree about where the user stopped.
 */
class HistoryActivity : AsyncScreenActivity() {

    private lateinit var content: LinearLayout

    private data class HistoryData(
        val reading: List<ReadingState>,
        val library: List<LibraryEntry>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(titleRes = R.string.history_title, bottomTab = Tab.HISTORY).content
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
                HistoryData(
                    reading = runCatching { created.library.readingStates() }
                        .getOrDefault(emptyList())
                        .sortedByDescending { it.updatedAtMillis },
                    library = runCatching { created.library.entries() }.getOrDefault(emptyList()),
                )
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(data: HistoryData) {
        content.removeAllViews()
        addStatusViews(content)
        status.text = ""
        if (data.reading.isEmpty()) {
            content.addView(
                emptyState(
                    str(R.string.history_empty),
                    str(R.string.history_empty_hint),
                    str(R.string.home_browse),
                ) { startActivity(Intent(this, SourcesActivity::class.java)) },
            )
            return
        }
        val byKey = data.library.associateBy { Triple(it.packageName, it.sourceId, it.mangaRef) }
        data.reading.forEach { state ->
            content.addView(row(byKey[Triple(state.packageName, state.sourceId, state.mangaRef)], state))
            content.addView(divider())
        }
    }

    private fun row(entry: LibraryEntry?, state: ReadingState): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPaddingRelative(0, dp(10), 0, dp(10))
            setOnClickListener { open(entry, state) }
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
        column.addView(
            text(
                "${str(R.string.history_page, state.page + 1)}  ·  " +
                    DateUtils.getRelativeTimeSpanString(state.updatedAtMillis),
                11f,
                ShuraColors.textTertiary,
            ).apply { setPaddingRelative(0, dp(2), 0, 0) },
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

    private fun open(entry: LibraryEntry?, state: ReadingState) {
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
}
