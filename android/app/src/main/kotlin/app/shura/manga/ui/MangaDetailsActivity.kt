package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import app.shura.manga.ShuraRepository
import app.shura.source.api.Chapter
import app.shura.source.api.Manga
import app.shura.source.api.MangaRef
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceProvider
import app.shura.source.host.DownloadedChapter
import app.shura.source.host.ReadingState
import kotlinx.coroutines.launch
import app.shura.manga.R

/**
 * One manga: its cover, what is known about it, and its chapters.
 *
 * Details and chapters are fetched independently and each failure is reported separately, because
 * a source that can list chapters but not describe a title is still readable. The header is built
 * from the intent's title and cover before the network returns, so the screen is never blank.
 */
class MangaDetailsActivity : AsyncScreenActivity() {

    private var repository: ShuraRepository? = null
    private var provider: SourceProvider? = null
    private var packageName: String? = null
    private var sourceId = -1L
    private var mangaRef: String? = null
    private var mangaTitle = ""
    private var present = false

    private lateinit var content: LinearLayout
    private lateinit var titleText: android.widget.TextView
    private lateinit var coverImage: ImageView
    private lateinit var libraryButton: android.widget.Button
    private lateinit var chaptersHost: LinearLayout
    private var chapters: List<Chapter> = emptyList()
    private var readingChapterRef: String? = null

    private data class Details(
        val manga: Manga?,
        val chapterList: List<Chapter>,
        val downloaded: List<DownloadedChapter>,
        val reading: ReadingState?,
        val present: Boolean,
        val warning: String?,
    )

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

        content = buildScreen(showBack = true).content
        addStatusViews(content)
        buildHeader()
        load()
    }

    private fun buildHeader() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(0, dp(8), 0, dp(8))
        }
        coverImage = cover(120, 170)
        header.addView(coverImage)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(14), 0, 0, 0)
        }
        titleText = text(mangaTitle, 20f, ShuraColors.onBackground, bold = true, maxLines = 3)
        info.addView(titleText)
        header.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            primaryButton(str(R.string.details_read)) { read() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            },
        )
        libraryButton = secondaryButton(str(R.string.details_add_library)) { toggleLibrary() }
        buttons.addView(
            libraryButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        content.addView(spacer(8))
        content.addView(buttons)
        content.addView(spacer(12))
    }

    private fun load() {
        val packageName = packageName ?: return
        val mangaRef = mangaRef ?: return
        runLoad(
            loading = str(R.string.loading),
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
                    downloaded = runCatching {
                        created.downloads.forManga(packageName, sourceId, mangaRef)
                    }.getOrDefault(emptyList()),
                    reading = runCatching {
                        created.library.readingState(packageName, sourceId, mangaRef)
                    }.getOrNull(),
                    present = runCatching {
                        created.library.contains(packageName, sourceId, mangaRef)
                    }.getOrDefault(false),
                    warning = warning,
                )
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(result: Details) {
        val manga = result.manga
        if (manga != null) {
            titleText.text = manga.title
        }
        CoverLoader.load(coverImage, manga?.thumbnailUrl, dp(120), dp(170))

        present = result.present
        libraryButton.text = str(
            if (present) R.string.details_remove_library else R.string.details_add_library,
        )
        if (result.warning != null) status.text = result.warning

        val description = manga?.description
        chaptersHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        renderDetails(manga, description)

        readingChapterRef = result.reading?.chapterRef
        chapters = result.chapterList
        val downloadedRefs = result.downloaded
            .filter { it.status == app.shura.source.host.DownloadStatus.COMPLETE }
            .map { it.chapterRef }
            .toSet()

        content.addView(spacer(8))
        content.addView(sectionHeader(str(R.string.details_chapters)))
        content.addView(chaptersHost)
        if (chapters.isEmpty()) {
            chaptersHost.addView(
                text(str(R.string.details_no_chapters), 13f, ShuraColors.textSecondary).apply {
                    setPaddingRelative(0, dp(8), 0, dp(8))
                },
            )
        }
        chapters.forEach { chapter ->
            chaptersHost.addView(chapterRow(chapter, downloadedRefs.contains(chapter.ref.value)))
            chaptersHost.addView(divider())
        }
    }

    private fun renderDetails(manga: Manga?, description: String?) {
        val lines = mutableListOf<String>()
        manga?.altTitles?.firstOrNull()?.let { lines.add(it) }
        manga?.author?.let { lines.add("${str(R.string.details_author)}: $it") }
        manga?.artist?.takeIf { it != manga?.author }?.let { lines.add("${str(R.string.details_artist)}: $it") }
        manga?.status?.let { lines.add("${str(R.string.details_status)}: ${it.name.lowercase()}") }
        if (lines.isNotEmpty()) {
            content.addView(spacer(10))
            content.addView(text(lines.joinToString("\n"), 13f, ShuraColors.textSecondary))
        }
        val genres = manga?.genres.orEmpty()
        if (genres.isNotEmpty()) {
            content.addView(spacer(10))
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            genres.forEachIndexed { index, genre ->
                val view = chip(genre)
                if (index > 0) {
                    (view.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(6)
                }
                chips.addView(view)
            }
            content.addView(
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(chips)
                },
            )
        }
        if (!description.isNullOrBlank()) {
            content.addView(spacer(12))
            content.addView(text(description, 13.5f, ShuraColors.onBackground))
        }
    }

    private fun chapterRow(chapter: Chapter, downloaded: Boolean): LinearLayout {
        val read = chapter.ref.value == readingChapterRef
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPaddingRelative(0, dp(14), 0, dp(14))
            setOnClickListener { open(chapter) }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            text(
                chapter.name,
                14f,
                if (read) ShuraColors.accent else ShuraColors.onBackground,
                maxLines = 1,
            ),
        )
        if (chapter.uploadDateMillis > 0) {
            column.addView(
                text(
                    DateUtils.getRelativeTimeSpanString(chapter.uploadDateMillis).toString(),
                    11f,
                    ShuraColors.textTertiary,
                ).apply { setPaddingRelative(0, dp(2), 0, 0) },
            )
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (downloaded) {
            row.addView(
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_download)
                    imageTintList = android.content.res.ColorStateList.valueOf(ShuraColors.success)
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                },
            )
        }
        return row
    }

    private fun read() {
        val chapter = chapters.firstOrNull { it.ref.value == readingChapterRef } ?: chapters.firstOrNull()
        if (chapter != null) open(chapter)
    }

    private fun toggleLibrary() {
        val target = repository ?: return
        val packageName = packageName ?: return
        val mangaRef = mangaRef ?: return
        val library = target.library
        scope.launch {
            val result = runCatching {
                if (present) {
                    library.remove(packageName, sourceId, mangaRef)
                } else {
                    library.add(packageName, sourceId, mangaRef, mangaTitle, null)
                }
            }
            runOnUiThread {
                result.fold(
                    onSuccess = {
                        present = !present
                        libraryButton.text = str(
                            if (present) {
                                R.string.details_remove_library
                            } else {
                                R.string.details_add_library
                            },
                        )
                    },
                    onFailure = { failure ->
                        Diagnostics.recordError(failure.describe())
                        status.text = str(R.string.error_prefix, failure.describe())
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
