package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shura.manga.ShuraRepository
import app.shura.source.api.Manga
import app.shura.source.api.MangaListPage
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceProvider

class BrowseActivity : AsyncScreenActivity() {

    private var provider: SourceProvider? = null
    private var packageName: String? = null
    private var sourceId = -1L
    private lateinit var query: EditText
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = intent.getStringExtra(SourceExtras.PACKAGE)
        sourceId = intent.getLongExtra(SourceExtras.SOURCE_ID, -1L)
        if (packageName == null || sourceId < 0) {
            finish()
            return
        }

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "Browse"; textSize = 20f })
        addStatusViews(root)

        query = EditText(this).apply {
            hint = "Search"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(query)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { load(query.text.toString()) }
        })
        row.addView(Button(this).apply {
            text = "Popular"
            setOnClickListener { load(null) }
        })
        root.addView(row)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        resolveSource()
    }

    private fun resolveSource() {
        val packageName = packageName ?: return
        runLoad(
            loading = "Opening source...",
            retry = { resolveSource() },
            block = { ShuraRepository.create(this).findSource(packageName, sourceId) },
            onLoaded = { source ->
                if (source == null) {
                    status.text = "Source is no longer installed"
                } else {
                    provider = source.provider
                    load(null)
                }
            },
        )
    }

    private fun load(search: String?) {
        val source = provider ?: return
        runLoad(
            loading = if (search == null) "Loading popular..." else "Searching '$search'...",
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
        list.removeAllViews()
        status.text = if (page.mangas.isEmpty()) "No results" else "${page.mangas.size} result(s)"
        page.mangas.forEach { manga ->
            list.addView(Button(this).apply {
                text = manga.title
                setOnClickListener { open(manga) }
            })
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
