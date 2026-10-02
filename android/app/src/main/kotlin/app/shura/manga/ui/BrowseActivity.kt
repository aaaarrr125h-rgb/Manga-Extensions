package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.shura.manga.ShuraRepository
import app.shura.source.api.Manga
import app.shura.source.api.MangaListPage
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceProvider
import app.shura.manga.R

/**
 * Popular titles and search, for one source.
 *
 * The source name arrives after the extension is loaded, so the title in the bar is filled in once
 * that succeeds rather than guessed from the intent.
 */
class BrowseActivity : AsyncScreenActivity() {

    private var provider: SourceProvider? = null
    private var packageName: String? = null
    private var sourceId = -1L
    private lateinit var title: TextView
    private lateinit var query: EditText
    private lateinit var content: LinearLayout
    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = intent.getStringExtra(SourceExtras.PACKAGE)
        sourceId = intent.getLongExtra(SourceExtras.SOURCE_ID, -1L)
        if (packageName == null || sourceId < 0) {
            finish()
            return
        }

        val screen = buildScreen()
        content = screen.content

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(4), 0, dp(16), 0)
        }
        bar.addView(iconButton(R.drawable.ic_back) { finish() })
        title = text("", 19f, ShuraColors.onBackground, bold = true, maxLines = 1)
        bar.addView(title)
        screen.root.addView(
            bar,
            0,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
        )

        addStatusViews(content)

        query = EditText(this).apply {
            hint = str(R.string.browse_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setTextColor(ShuraColors.onBackground)
            setHintTextColor(ShuraColors.textTertiary)
            background = rounded(ShuraColors.surfaceVariant, 12, ShuraColors.outline, 1)
            setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
        }
        content.addView(
            query,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            primaryButton(str(R.string.browse_search)) { load(query.text.toString().trim().ifBlank { null }) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        actions.addView(
            secondaryButton(str(R.string.browse_popular)) { load(null) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        content.addView(spacer(8))
        content.addView(actions)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)

        resolveSource()
    }

    private fun resolveSource() {
        val packageName = packageName ?: return
        runLoad(
            loading = str(R.string.loading),
            retry = { resolveSource() },
            block = { ShuraRepository.create(this).findSource(packageName, sourceId) },
            onLoaded = { source ->
                if (source == null) {
                    status.text = str(R.string.sources_empty)
                } else {
                    provider = source.provider
                    title.text = source.descriptor.name
                    load(null)
                }
            },
        )
    }

    private fun load(search: String?) {
        val source = provider ?: return
        runLoad(
            loading = str(R.string.loading),
            retry = { load(search) },
            block = {
                when {
                    search == null && source.descriptor.supports(SourceCapability.BROWSE_POPULAR) ->
                        source.popularManga(1)

                    search != null && source.descriptor.supports(SourceCapability.SEARCH) ->
                        source.searchManga(1, search)

                    else -> MangaListPage.EMPTY
                }
            },
            onLoaded = { page -> show(page) },
        )
    }

    private fun show(page: MangaListPage) {
        results.removeAllViews()
        status.text = if (page.mangas.isEmpty()) str(R.string.browse_no_results) else ""
        if (page.mangas.isEmpty()) return

        val density = resources.displayMetrics.density
        val widthDp = (resources.displayMetrics.widthPixels / density).toInt()
        val cellWidth = (widthDp - 16 * 2 - 8 * 2) / 3
        var row: LinearLayout? = null
        page.mangas.forEachIndexed { index, manga ->
            if (index % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                if (index > 0) results.addView(spacer(16))
                results.addView(row)
            }
            val cell = coverCell(cellWidth, manga.title) { open(manga) }
            if (index % 3 != 0) {
                (cell.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
            }
            row?.addView(cell)
        }
    }

    private fun open(manga: Manga) {
        val source = provider ?: return
        startActivity(
            Intent(this, MangaDetailsActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, source.descriptor.packageName)
                .putExtra(SourceExtras.SOURCE_ID, source.descriptor.sourceId)
                .putExtra(SourceExtras.MANGA_REF, manga.ref.value)
                .putExtra(SourceExtras.MANGA_TITLE, manga.title),
        )
    }
}
