package app.shura.manga.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.shura.manga.ShuraRepository
import app.shura.source.api.Chapter
import app.shura.source.api.ChapterRef
import app.shura.source.api.MangaRef
import app.shura.source.api.PageRef
import app.shura.source.api.SourceCapability
import app.shura.source.host.DownloadStatus
import app.shura.source.host.UrlHttpTransport
import kotlinx.coroutines.launch
import java.io.File
import app.shura.manga.R

/**
 * The reader.
 *
 * Pages scroll vertically as one continuous strip, which is the layout that needs no page-turn
 * gesture and works the same whether the chapter came from the network or from disk. Chrome is
 * hidden by default and any tap on the strip brings it back, so the screen is the page and nothing
 * else. The position is written to the library as the visible page changes, not only on exit,
 * because a reader is the screen most likely to be killed while it is open.
 */
class ReaderActivity : AsyncScreenActivity() {

    private val transport = UrlHttpTransport()

    private var repository: ShuraRepository? = null
    private var packageName: String? = null
    private var sourceId = -1L
    private var mangaRef: String? = null
    private var mangaTitle = ""
    private var chapterRef: String? = null
    private var chapterName = ""

    private var pages: List<PageRef> = emptyList()
    private var localFiles: List<File> = emptyList()
    private var chapterList: List<Chapter> = emptyList()
    private var current = 0

    private lateinit var topControls: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var pagesColumn: LinearLayout
    private lateinit var counter: android.widget.TextView
    private lateinit var downloadButton: android.widget.ImageButton
    private lateinit var prevButton: android.widget.Button
    private lateinit var nextButton: android.widget.Button

    private data class Contents(
        val offlineFiles: List<File>,
        val onlinePages: List<PageRef>,
        val chapters: List<Chapter>,
        val startPage: Int,
        val offline: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = intent.getStringExtra(SourceExtras.PACKAGE)
        sourceId = intent.getLongExtra(SourceExtras.SOURCE_ID, -1L)
        mangaRef = intent.getStringExtra(SourceExtras.MANGA_REF)
        mangaTitle = intent.getStringExtra(SourceExtras.MANGA_TITLE).orEmpty()
        chapterRef = intent.getStringExtra(SourceExtras.CHAPTER_REF)
        chapterName = intent.getStringExtra(SourceExtras.CHAPTER_NAME).orEmpty()
        if (packageName == null || sourceId < 0 || chapterRef == null) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ShuraColors.background)
        }
        setContentView(root)

        topControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ShuraColors.surface)
            setPaddingRelative(dp(4), dp(4), dp(4), dp(4))
        }
        topControls.addView(iconButton(R.drawable.ic_back) { finish() })
        topControls.addView(
            text(chapterName, 15f, ShuraColors.onBackground, bold = true, maxLines = 1),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        topControls.addView(iconButton(R.drawable.ic_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        downloadButton = iconButton(R.drawable.ic_download) { download() }
        topControls.addView(downloadButton)
        root.addView(
            topControls,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)),
        )

        progress = progressBar()
        status = text("", 12f, ShuraColors.textSecondary).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(status)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            isClickable = true
            setOnClickListener { toggleControls() }
            setOnScrollChangeListener { _, _, _, _, _ -> updateVisiblePage() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        pagesColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(
            pagesColumn,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(scroll)

        bottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ShuraColors.surface)
            setPaddingRelative(dp(8), dp(6), dp(8), dp(6))
        }
        prevButton = secondaryButton(str(R.string.reader_prev_chapter)) { moveChapter(-1) }
        nextButton = secondaryButton(str(R.string.reader_next_chapter)) { moveChapter(1) }
        counter = text("", 12f, ShuraColors.textSecondary).apply { gravity = Gravity.CENTER }
        bottomControls.addView(prevButton)
        bottomControls.addView(
            counter,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bottomControls.addView(nextButton)
        root.addView(
            bottomControls,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
        )

        open()
    }

    private fun toggleControls() {
        val show = topControls.visibility != View.VISIBLE
        topControls.visibility = if (show) View.VISIBLE else View.GONE
        bottomControls.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun open() {
        val packageName = packageName ?: return
        val chapterRef = chapterRef ?: return
        runLoad(
            loading = str(R.string.loading),
            retry = { open() },
            block = {
                val created = ShuraRepository.create(this)
                repository = created
                val source = created.findSource(packageName, sourceId)
                    ?: error("Source is no longer installed")
                val chapters = runCatching {
                    if (source.provider.descriptor.supports(SourceCapability.FETCH_CHAPTERS)) {
                        source.provider.chapterList(MangaRef(mangaRef.orEmpty()))
                    } else {
                        emptyList()
                    }
                }.getOrDefault(emptyList())
                val state = runCatching {
                    created.library.readingState(packageName, sourceId, mangaRef.orEmpty())
                }.getOrNull()
                val startPage = if (state?.chapterRef == chapterRef) state.page else 0

                val local = created.downloads.find(packageName, sourceId, mangaRef.orEmpty(), chapterRef)
                if (local != null && local.status == DownloadStatus.COMPLETE) {
                    val files = created.downloads.pageFiles(local)
                    if (files.isNotEmpty()) {
                        return@runLoad Contents(files, emptyList(), chapters, startPage, offline = true)
                    }
                }
                if (!source.provider.descriptor.supports(SourceCapability.FETCH_PAGES)) {
                    error("Source cannot list pages")
                }
                val online = source.provider.pageList(ChapterRef(chapterRef))
                Contents(emptyList(), online, chapters, startPage, offline = false)
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(contents: Contents) {
        localFiles = contents.offlineFiles
        pages = contents.onlinePages
        chapterList = contents.chapters
        status.text = if (contents.offline) str(R.string.reader_offline) else ""
        updateDownloadButton()

        pagesColumn.removeAllViews()
        val count = maxOf(localFiles.size, pages.size)
        counter.text = str(R.string.reader_page_of, 1, count)
        if (count == 0) {
            status.text = "No pages"
            return
        }
        for (index in 0 until count) {
            val image = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(ShuraColors.surface)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            pagesColumn.addView(image)
            bindPage(image, index)
        }

        val start = contents.startPage.coerceIn(0, count - 1)
        current = start
        counter.text = str(R.string.reader_page_of, start + 1, count)
        updateChapterButtons()
        if (start > 0) {
            scroll.post { scroll.scrollTo(0, pagesColumn.getChildAt(start)?.top ?: 0) }
        }
    }

    private fun bindPage(image: ImageView, index: Int) {
        val file = localFiles.getOrNull(index)
        val url = pages.getOrNull(index)?.let { it.imageUrl ?: it.url }
        image.setOnClickListener(null)
        scope.launch {
            val bitmap = runCatching { decodePage(file, url) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                    image.isClickable = false
                } else {
                    image.setImageDrawable(null)
                    image.setBackgroundColor(ShuraColors.surfaceVariant)
                    image.isClickable = true
                    image.setOnClickListener { bindPage(image, index) }
                    Diagnostics.recordError("page ${index + 1} of $chapterName failed")
                    status.text = str(R.string.reader_failed_page, index + 1)
                }
            }
        }
    }

    private fun decodePage(file: File?, url: String?): Bitmap? {
        val targetWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val bytes = when {
            file != null -> file.readBytes()
            url != null -> transport.get(url).body
            else -> return null
        }
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun updateVisiblePage() {
        if (pagesColumn.childCount == 0) return
        val scrollTop = scroll.scrollY
        val scrollBottom = scrollTop + scroll.height
        var best = current
        var bestOverlap = -1
        for (index in 0 until pagesColumn.childCount) {
            val child = pagesColumn.getChildAt(index)
            val overlap = minOf(child.bottom, scrollBottom) - maxOf(child.top, scrollTop)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = index
            }
        }
        if (best != current) {
            current = best
            counter.text = str(R.string.reader_page_of, current + 1, pagesColumn.childCount)
            saveReadingState(current)
        }
    }

    private fun updateChapterButtons() {
        val index = chapterList.indexOfFirst { it.ref.value == chapterRef }
        val hasPrev = index > 0
        val hasNext = index >= 0 && index < chapterList.size - 1
        prevButton.isEnabled = hasPrev
        nextButton.isEnabled = hasNext
        prevButton.alpha = if (hasPrev) 1f else 0.4f
        nextButton.alpha = if (hasNext) 1f else 0.4f
    }

    private fun moveChapter(delta: Int) {
        val index = chapterList.indexOfFirst { it.ref.value == chapterRef }
        if (index < 0) return
        val target = chapterList.getOrNull(index + delta) ?: return
        val packageName = packageName ?: return
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, packageName)
                .putExtra(SourceExtras.SOURCE_ID, sourceId)
                .putExtra(SourceExtras.MANGA_REF, mangaRef)
                .putExtra(SourceExtras.MANGA_TITLE, mangaTitle)
                .putExtra(SourceExtras.CHAPTER_REF, target.ref.value)
                .putExtra(SourceExtras.CHAPTER_NAME, target.name),
        )
        finish()
    }

    private fun saveReadingState(page: Int) {
        if (!Prefs.savePosition(this)) return
        val repository = repository ?: return
        val packageName = packageName ?: return
        val mangaRef = mangaRef ?: return
        val chapterRef = chapterRef ?: return
        scope.launch {
            runCatching {
                repository.library.saveReadingState(
                    packageName = packageName,
                    sourceId = sourceId,
                    mangaRef = mangaRef,
                    chapterRef = chapterRef,
                    chapterName = chapterName,
                    page = page,
                )
            }
        }
    }

    private fun download() {
        val repository = repository ?: return
        val packageName = packageName ?: return
        val chapterRef = chapterRef ?: return
        if (pages.isEmpty()) {
            status.text = "This chapter is already on disk"
            return
        }
        downloadButton.isEnabled = false
        status.text = "Starting download..."
        scope.launch {
            val directory = runCatching {
                repository.downloads.prepareDirectory(packageName, sourceId, mangaRef.orEmpty(), chapterRef)
            }.getOrElse { failure ->
                runOnUiThread {
                    downloadButton.isEnabled = true
                    Diagnostics.recordError(failure.describe())
                    status.text = str(R.string.error_prefix, failure.describe())
                }
                return@launch
            }

            var downloaded = 0
            var bytesTotal = 0L
            pages.forEachIndexed { index, page ->
                val url = page.imageUrl ?: page.url
                runCatching {
                    val data = transport.get(url).body
                    File(directory, repository.downloads.pageFileName(index)).writeBytes(data)
                    data.size
                }.onSuccess { written ->
                    downloaded++
                    bytesTotal += written
                }.onFailure { failure ->
                    Diagnostics.recordError(failure.describe())
                }
                val progressCount = index + 1
                runOnUiThread { status.text = "Downloading $progressCount/${pages.size}..." }
            }

            val complete = downloaded == pages.size
            runOnUiThread {
                downloadButton.isEnabled = true
                if (complete) {
                    val record = runCatching {
                        repository.downloads.recordComplete(
                            packageName = packageName,
                            sourceId = sourceId,
                            mangaRef = mangaRef.orEmpty(),
                            mangaTitle = mangaTitle,
                            chapterRef = chapterRef,
                            chapterName = chapterName,
                            pageCount = pages.size,
                            bytes = bytesTotal,
                        )
                    }.getOrNull()
                    localFiles = record?.let { repository.downloads.pageFiles(it) }.orEmpty()
                    status.text = str(R.string.reader_downloaded)
                } else {
                    runCatching {
                        repository.downloads.recordFailed(
                            packageName = packageName,
                            sourceId = sourceId,
                            mangaRef = mangaRef.orEmpty(),
                            mangaTitle = mangaTitle,
                            chapterRef = chapterRef,
                            chapterName = chapterName,
                            pageCount = pages.size,
                            bytes = bytesTotal,
                            error = "downloaded $downloaded/${pages.size}",
                        )
                    }
                    status.text = "Download failed ($downloaded/${pages.size})"
                }
                updateDownloadButton()
            }
        }
    }

    private fun updateDownloadButton() {
        val repository = repository ?: return
        val packageName = packageName ?: return
        val chapterRef = chapterRef ?: return
        val record = runCatching {
            repository.downloads.find(packageName, sourceId, mangaRef.orEmpty(), chapterRef)
        }.getOrNull()
        downloadButton.imageTintList = android.content.res.ColorStateList.valueOf(
            when (record?.status) {
                DownloadStatus.COMPLETE -> ShuraColors.success
                DownloadStatus.FAILED -> ShuraColors.error
                null -> ShuraColors.onBackground
            },
        )
    }

    override fun onPause() {
        super.onPause()
        saveReadingState(current)
    }
}
