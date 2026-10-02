package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.shura.manga.ShuraRepository
import app.shura.source.api.Chapter
import app.shura.source.api.ChapterRef
import app.shura.source.api.Manga
import app.shura.source.api.MangaRef
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MangaDetailsActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default)

    private var provider: SourceProvider? = null
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var chapters: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(SourceExtras.PACKAGE)
        val sourceId = intent.getLongExtra(SourceExtras.SOURCE_ID, -1L)
        val mangaRef = intent.getStringExtra(SourceExtras.MANGA_REF)
        val title = intent.getStringExtra(SourceExtras.MANGA_TITLE).orEmpty()
        if (packageName == null || sourceId < 0 || mangaRef == null) {
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

        root.addView(TextView(this).apply { text = title; textSize = 20f })
        status = TextView(this).apply { text = "Loading..."; textSize = 11f; setPadding(0, 8, 0, 8) }
        root.addView(status)
        details = TextView(this).apply { textSize = 12f; setPadding(0, 0, 0, 16) }
        root.addView(details)
        root.addView(TextView(this).apply { text = "Chapters"; textSize = 16f; setPadding(0, 8, 0, 8) })
        chapters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(chapters)

        scope.launch {
            val source = try {
                ShuraRepository.create(this@MangaDetailsActivity).findSource(packageName, sourceId)
            } catch (e: Exception) {
                null
            }
            if (source == null) {
                runOnUiThread { status.text = "Source is no longer installed" }
                return@launch
            }
            provider = source.provider
            val ref = MangaRef(mangaRef)
            val manga = try {
                if (source.provider.descriptor.supports(SourceCapability.FETCH_DETAILS)) {
                    source.provider.mangaDetails(ref)
                } else {
                    null
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Details error: ${e.message}" }
                null
            }
            val chapterList = try {
                if (source.provider.descriptor.supports(SourceCapability.FETCH_CHAPTERS)) {
                    source.provider.chapterList(ref)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Chapter error: ${e.message}" }
                emptyList()
            }
            runOnUiThread { show(manga, chapterList) }
        }
    }

    private fun show(manga: Manga?, chapterList: List<Chapter>) {
        status.text = if (manga == null) "No details" else manga.status.name
        details.text = manga?.let {
            buildString {
                it.author?.let { author -> appendLine("Author: $author") }
                it.artist?.let { artist -> appendLine("Artist: $artist") }
                it.genres.takeIf { genres -> genres.isNotEmpty() }
                    ?.let { genres -> appendLine("Genres: ${genres.joinToString()}") }
                it.description?.let { description -> appendLine(description) }
            }.trim()
        }.orEmpty()

        chapters.removeAllViews()
        if (chapterList.isEmpty()) {
            chapters.addView(TextView(this).apply { text = "No chapters"; textSize = 11f })
        }
        chapterList.forEach { chapter ->
            chapters.addView(Button(this).apply {
                text = chapter.name
                setOnClickListener { open(chapter) }
            })
        }
    }

    private fun open(chapter: Chapter) {
        val source = provider ?: return
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, source.descriptor.packageName)
                .putExtra(SourceExtras.SOURCE_ID, source.descriptor.sourceId)
                .putExtra(SourceExtras.CHAPTER_REF, chapter.ref.value)
                .putExtra(SourceExtras.CHAPTER_NAME, chapter.name),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
