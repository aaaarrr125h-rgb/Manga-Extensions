package app.shura.manga.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.shura.manga.ShuraRepository
import app.shura.source.api.ChapterRef
import app.shura.source.api.PageRef
import app.shura.source.api.SourceCapability
import app.shura.source.api.SourceProvider
import app.shura.source.host.UrlHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class ReaderActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val transport = UrlHttpTransport()

    private var provider: SourceProvider? = null
    private var sourceId = -1L
    private var pages: List<PageRef> = emptyList()
    private var current = 0

    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var image: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(SourceExtras.PACKAGE)
        sourceId = intent.getLongExtra(SourceExtras.SOURCE_ID, -1L)
        val chapterRef = intent.getStringExtra(SourceExtras.CHAPTER_REF)
        val chapterName = intent.getStringExtra(SourceExtras.CHAPTER_NAME).orEmpty()
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
        counter = TextView(this).apply { text = "-/-"; textSize = 11f; setPadding(0, 4, 0, 4) }
        root.addView(counter)
        status = TextView(this).apply { text = "Loading..."; textSize = 11f; setPadding(0, 4, 0, 8) }
        root.addView(status)

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
        row.addView(Button(this).apply {
            text = "Download"
            setOnClickListener { download() }
        })
        root.addView(row)

        scope.launch {
            val source = try {
                ShuraRepository.create(this@ReaderActivity).findSource(packageName, sourceId)
            } catch (e: Exception) {
                null
            }
            if (source == null) {
                runOnUiThread { status.text = "Source is no longer installed" }
                return@launch
            }
            provider = source.provider
            val loaded = try {
                if (source.provider.descriptor.supports(SourceCapability.FETCH_PAGES)) {
                    source.provider.pageList(ChapterRef(chapterRef))
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Page error: ${e.message}" }
                emptyList()
            }
            pages = loaded
            runOnUiThread {
                counter.text = if (pages.isEmpty()) "0/0" else "1/${pages.size}"
                if (pages.isEmpty()) {
                    status.text = "No pages"
                } else {
                    showPage(0)
                }
            }
        }
    }

    private fun showPage(index: Int) {
        if (pages.isEmpty()) return
        val clamped = index.coerceIn(0, pages.size - 1)
        current = clamped
        val page = pages[clamped]
        counter.text = "${clamped + 1}/${pages.size}"
        status.text = "Loading page ${clamped + 1}..."
        val url = page.imageUrl ?: page.url
        scope.launch {
            val bitmap = try {
                val bytes = transport.get(url).body
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                    status.text = url
                } else {
                    status.text = "Failed to load page ${clamped + 1}"
                }
            }
        }
    }

    private fun download() {
        if (pages.isEmpty()) return
        status.text = "Downloading ${pages.size} page(s)..."
        scope.launch {
            val directory = File(cacheDir, "pages/$sourceId")
            directory.mkdirs()
            var downloaded = 0
            var bytesTotal = 0L
            pages.forEachIndexed { index, page ->
                val url = page.imageUrl ?: page.url
                try {
                    val bytes = transport.get(url).body
                    File(directory, "page-%03d.img".format(index)).writeBytes(bytes)
                    downloaded++
                    bytesTotal += bytes.size
                } catch (e: Exception) {
                    // One page failing must not lose the pages around it.
                }
            }
            runOnUiThread {
                status.text = "Downloaded $downloaded/${pages.size} page(s), $bytesTotal bytes\n${directory.path}"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
