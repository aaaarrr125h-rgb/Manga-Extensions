package app.shura.manga.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shura.manga.ShuraRepository
import app.shura.source.api.ChapterRef
import app.shura.source.api.PageRef
import app.shura.source.api.SourceCapability
import app.shura.source.host.DownloadStatus
import app.shura.source.host.UrlHttpTransport
import kotlinx.coroutines.launch
import java.io.File

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
    private var current = 0

    private lateinit var counter: TextView
    private lateinit var image: ImageView
    private lateinit var downloadButton: Button

    private sealed class Loaded {
        data class Online(val pages: List<PageRef>) : Loaded()
        data class Offline(val files: List<File>) : Loaded()
    }

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

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply { text = chapterName; textSize = 20f })
        addStatusViews(root)
        counter = TextView(this).apply { text = "-/-"; textSize = 11f; setPadding(0, 4, 0, 4) }
        root.addView(counter)

        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(image)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Prev"
            setOnClickListener { showPage(current - 1) }
        })
        row.addView(Button(this).apply {
            text = "Next"
            setOnClickListener { showPage(current + 1) }
        })
        downloadButton = Button(this).apply {
            text = "Download"
            setOnClickListener { download() }
        }
        row.addView(downloadButton)
        root.addView(row)

        open()
    }

    private fun open() {
        val packageName = packageName ?: return
        val chapterRef = chapterRef ?: return
        runLoad(
            loading = "Loading chapter...",
            retry = { open() },
            block = {
                val created = ShuraRepository.create(this)
                repository = created
                val local = created.downloads.find(packageName, sourceId, mangaRef.orEmpty(), chapterRef)
                if (local != null && local.status == DownloadStatus.COMPLETE) {
                    val files = created.downloads.pageFiles(local)
                    if (files.isNotEmpty()) Loaded.Offline(files) else loadOnline(created, packageName, chapterRef)
                } else {
                    loadOnline(created, packageName, chapterRef)
                }
            },
            onLoaded = { loaded ->
                when (loaded) {
                    is Loaded.Offline -> {
                        localFiles = loaded.files
                        pages = emptyList()
                    }

                    is Loaded.Online -> {
                        pages = loaded.pages
                        localFiles = emptyList()
                    }
                }
                val count = maxOf(localFiles.size, pages.size)
                counter.text = if (count == 0) "0/0" else "1/$count"
                updateDownloadButton()
                if (count == 0) {
                    status.text = "No pages"
                } else {
                    showPage(0)
                }
            },
        )
    }

    private suspend fun loadOnline(repository: ShuraRepository, packageName: String, chapterRef: String): Loaded {
        val source = repository.findSource(packageName, sourceId)
            ?: error("Source is no longer installed")
        if (!source.provider.descriptor.supports(SourceCapability.FETCH_PAGES)) {
            error("Source cannot list pages")
        }
        return Loaded.Online(source.provider.pageList(ChapterRef(chapterRef)))
    }

    private fun showPage(index: Int) {
        val count = maxOf(localFiles.size, pages.size)
        if (count == 0) return
        val clamped = index.coerceIn(0, count - 1)
        current = clamped
        counter.text = "${clamped + 1}/$count"
        status.text = "Loading page ${clamped + 1}..."
        val file = localFiles.getOrNull(clamped)
        val url = pages.getOrNull(clamped)?.let { it.imageUrl ?: it.url }

        scope.launch {
            val bitmap = runCatching {
                when {
                    file != null -> BitmapFactory.decodeFile(file.path)
                    url != null -> {
                        val bytes = transport.get(url).body
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }

                    else -> null
                }
            }.getOrNull()
            runOnUiThread {
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                    status.text = file?.name ?: url.orEmpty()
                } else {
                    status.text = "Failed to load page ${clamped + 1}\nTap to retry."
                    status.setOnClickListener { showPage(clamped) }
                    Diagnostics.recordError("page ${clamped + 1} of $chapterName failed")
                }
            }
        }
        saveReadingState(clamped)
    }

    private fun saveReadingState(page: Int) {
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
                    status.text = "Cannot start download: ${failure.describe()}"
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
                val progress = index + 1
                runOnUiThread { status.text = "Downloading $progress/${pages.size}..." }
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
                    status.text = "Downloaded ${pages.size} page(s), $bytesTotal bytes"
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
                    status.text = "Download failed ($downloaded/${pages.size})\nTap to retry."
                    status.setOnClickListener { download() }
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
        downloadButton.text = when (record?.status) {
            DownloadStatus.COMPLETE -> "Downloaded"
            DownloadStatus.FAILED -> "Retry download"
            null -> "Download"
        }
    }
}
