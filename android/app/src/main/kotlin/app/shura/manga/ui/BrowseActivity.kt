package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.shura.manga.ShuraRepository
import app.shura.source.api.Manga
import app.shura.source.api.MangaListPage
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BrowseActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default)

    private var provider: SourceProvider? = null
    private lateinit var status: TextView
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(SourceExtras.PACKAGE)
        val sourceId = intent.getLongExtra(SourceExtras.SOURCE_ID, -1L)
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
        status = TextView(this).apply { text = "Loading..."; textSize = 11f; setPadding(0, 8, 0, 8) }
        root.addView(status)

        val query = EditText(this).apply {
            hint = "Search"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(query)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Search"
            setOnClickListener { load(search = query.text.toString()) }
        })
        row.addView(Button(this).apply {
            text = "Popular"
            setOnClickListener { load(search = null) }
        })
        root.addView(row)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        scope.launch {
            val source = try {
                ShuraRepository.create(this@BrowseActivity).findSource(packageName, sourceId)
            } catch (e: Exception) {
                null
            }
            if (source == null) {
                runOnUiThread { status.text = "Source is no longer installed" }
                return@launch
            }
            provider = source.provider
            runOnUiThread {
                title = source.descriptor.name
                load(search = null)
            }
        }
    }

    private fun load(search: String?) {
        val source = provider ?: return
        status.text = if (search == null) "Loading popular..." else "Searching '$search'..."
        listContainer.removeAllViews()
        scope.launch {
            val page = try {
                if (search == null) {
                    if (!source.descriptor.supports(SourceCapability.BROWSE_POPULAR)) {
                        MangaListPage.EMPTY
                    } else {
                        source.popularManga(1)
                    }
                } else {
                    if (!source.descriptor.supports(SourceCapability.SEARCH)) {
                        MangaListPage.EMPTY
                    } else {
                        source.searchManga(1, search)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error: ${e.message}" }
                return@launch
            }
            runOnUiThread { show(page) }
        }
    }

    private fun show(page: MangaListPage) {
        listContainer.removeAllViews()
        status.text = if (page.mangas.isEmpty()) {
            "No results"
        } else {
            "${page.mangas.size} result(s)"
        }
        page.mangas.forEach { manga ->
            listContainer.addView(Button(this).apply {
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
