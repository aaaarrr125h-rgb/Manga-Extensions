package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shura.manga.ShuraRepository
import app.shura.source.host.LibraryEntry
import app.shura.source.host.ReadingState
import kotlinx.coroutines.launch

class LibraryActivity : AsyncScreenActivity() {

    private var repository: ShuraRepository? = null
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "Library"; textSize = 20f })
        addStatusViews(root)
        root.addView(Button(this).apply {
            text = "Reload"
            setOnClickListener { load() }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        load()
    }

    private fun load() {
        runLoad(
            loading = "Loading library...",
            retry = { load() },
            block = {
                val created = ShuraRepository.create(this)
                repository = created
                created.library.entries() to created.library.readingStates()
            },
            onLoaded = { (entries, reading) -> show(entries, reading) },
        )
    }

    private fun show(entries: List<LibraryEntry>, reading: List<ReadingState>) {
        list.removeAllViews()
        if (entries.isEmpty()) {
            status.text = "Library is empty. Open a manga and tap Add to Library."
            return
        }
        status.text = "${entries.size} manga in the library"
        entries.forEach { entry ->
            val state = reading.firstOrNull {
                it.packageName == entry.packageName &&
                    it.sourceId == entry.sourceId &&
                    it.mangaRef == entry.mangaRef
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                text = buildString {
                    append(entry.title)
                    if (state != null) append("\nresume: ${state.chapterName} p${state.page + 1}")
                }
                setOnClickListener { open(entry) }
            })
            row.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener { remove(entry) }
            })
            list.addView(row)
        }
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

    private fun remove(entry: LibraryEntry) {
        val repository = repository ?: return
        scope.launch {
            runCatching { repository.library.remove(entry.packageName, entry.sourceId, entry.mangaRef) }
                .onFailure { Diagnostics.recordError(it.describe()) }
            runOnUiThread { load() }
        }
    }
}
