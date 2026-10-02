package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shura.manga.ShuraRepository
import app.shura.source.api.Chapter
import app.shura.source.api.Manga
import app.shura.source.api.MangaRef
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceProvider
import kotlinx.coroutines.launch

class MangaDetailsActivity : AsyncScreenActivity() {

    private var repository: ShuraRepository? = null
    private var provider: SourceProvider? = null
    private var packageName: String? = null
    private var sourceId = -1L
    private var mangaRef: String? = null
    private var mangaTitle = ""
    private var thumbnailUrl: String? = null
    private lateinit var libraryButton: Button
    private lateinit var details: TextView
    private lateinit var chapters: LinearLayout

    private data class Details(val manga: Manga?, val chapterList: List<Chapter>, val warning: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = intent.getStringExtra(SourceExtras.PACKAGE)
        sourceId = intent.getLongExtra(SourceExtras.SOURCE_ID, -1L)
        mangaRef = intent.getStringExtra(SourceExtras.MANGA_REF)
        mangaTitle = intent.getStringExtra(SourceExtras.MANGA_TITLE).orEmpty()
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

        root.addView(TextView(this).apply { text = mangaTitle; textSize = 20f })
        addStatusViews(root)
        libraryButton = Button(this).apply {
            text = "Add to Library"
            isEnabled = false
            setOnClickListener { toggleLibrary() }
        }
        root.addView(libraryButton)
        details = TextView(this).apply { textSize = 12f; setPadding(0, 12, 0, 16) }
        root.addView(details)
        root.addView(TextView(this).apply { text = "Chapters"; textSize = 16f; setPadding(0, 8, 0, 8) })
        chapters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(chapters)

        load()
    }

    private fun load() {
        val packageName = packageName ?: return
        val mangaRef = mangaRef ?: return
        runLoad(
            loading = "Loading details...",
            retry = { load() },
            block = {
                val created = ShuraRepository.create(this)
                repository = created
                val source = created.findSource(packageName, sourceId)
                    ?: error("Source is no longer installed")
                provider = source.provider
                val ref = MangaRef(mangaRef)

                val detailsResult = runCatching {
                    if (source.provider.descriptor.supports(SourceCapability.FETCH_DETAILS)) {
                        source.provider.mangaDetails(ref)
                    } else {
                        null
                    }
                }
                val chaptersResult = runCatching {
                    if (source.provider.descriptor.supports(SourceCapability.FETCH_CHAPTERS)) {
                        source.provider.chapterList(ref)
                    } else {
                        emptyList()
                    }
                }
                val warning = listOfNotNull(
                    detailsResult.exceptionOrNull()?.let { "Details: ${it.describe()}" },
                    chaptersResult.exceptionOrNull()?.let { "Chapters: ${it.describe()}" },
                ).takeIf { it.isNotEmpty() }?.joinToString()
                detailsResult.exceptionOrNull()?.let { Diagnostics.recordError(it.describe()) }
                chaptersResult.exceptionOrNull()?.let { Diagnostics.recordError(it.describe()) }

                Details(
                    manga = detailsResult.getOrNull(),
                    chapterList = chaptersResult.getOrElse { emptyList() },
                    warning = warning,
                )
            },
            onLoaded = { result -> show(result) },
        )
    }

    private fun show(result: Details) {
        thumbnailUrl = result.manga?.thumbnailUrl
        details.text = result.manga?.let { manga ->
            buildString {
                manga.author?.let { appendLine("Author: $it") }
                manga.artist?.let { appendLine("Artist: $it") }
                manga.genres.takeIf { it.isNotEmpty() }?.let { appendLine("Genres: ${it.joinToString()}") }
                manga.description?.let { appendLine(it) }
            }.trim()
        }.orEmpty()
        if (result.warning != null) {
            status.text = result.warning
        } else if (result.manga == null) {
            status.text = "No details"
        }

        updateLibraryButton()
        chapters.removeAllViews()
        if (result.chapterList.isEmpty()) {
            chapters.addView(TextView(this).apply { text = "No chapters"; textSize = 11f })
        }
        result.chapterList.forEach { chapter ->
            chapters.addView(Button(this).apply {
                text = chapter.name
                setOnClickListener { open(chapter) }
            })
        }
    }

    private fun updateLibraryButton() {
        val library = repository?.library ?: return
        val packageName = packageName ?: return
        val mangaRef = mangaRef ?: return
        val present = runCatching { library.contains(packageName, sourceId, mangaRef) }.getOrDefault(false)
        libraryButton.isEnabled = true
        libraryButton.text = if (present) "Remove from Library" else "Add to Library"
    }

    private fun toggleLibrary() {
        val target = repository ?: return
        val packageName = packageName ?: return
        val mangaRef = mangaRef ?: return
        val library = target.library
        scope.launch {
            val present = runCatching { library.contains(packageName, sourceId, mangaRef) }.getOrDefault(false)
            val result = runCatching {
                if (present) {
                    library.remove(packageName, sourceId, mangaRef)
                } else {
                    library.add(packageName, sourceId, mangaRef, mangaTitle, thumbnailUrl)
                }
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { updateLibraryButton() },
                    onFailure = { failure ->
                        Diagnostics.recordError(failure.describe())
                        status.text = "Library: ${failure.describe()}"
                    },
                )
            }
        }
    }

    private fun open(chapter: Chapter) {
        val source = provider ?: return
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, source.descriptor.packageName)
                .putExtra(SourceExtras.SOURCE_ID, source.descriptor.sourceId)
                .putExtra(SourceExtras.MANGA_REF, mangaRef)
                .putExtra(SourceExtras.MANGA_TITLE, mangaTitle)
                .putExtra(SourceExtras.CHAPTER_REF, chapter.ref.value)
                .putExtra(SourceExtras.CHAPTER_NAME, chapter.name),
        )
    }
}
