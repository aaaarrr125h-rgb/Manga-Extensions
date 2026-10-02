package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shura.manga.ShuraRepository
import app.shura.source.host.DownloadStatus
import app.shura.source.host.DownloadedChapter
import kotlinx.coroutines.launch

class DownloadsActivity : AsyncScreenActivity() {

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

        root.addView(TextView(this).apply { text = "Downloads"; textSize = 20f })
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
            loading = "Loading downloads...",
            retry = { load() },
            block = {
                val created = ShuraRepository.create(this)
                repository = created
                created.downloads.all()
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(records: List<DownloadedChapter>) {
        list.removeAllViews()
        if (records.isEmpty()) {
            status.text = "No downloaded chapters yet. Open a chapter and tap Download."
            return
        }
        val complete = records.count { it.status == DownloadStatus.COMPLETE }
        status.text = "$complete complete, ${records.size - complete} failed"
        records.forEach { record ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(this).apply {
                text = buildString {
                    append(if (record.status == DownloadStatus.COMPLETE) "\u2713 " else "\u2717 ")
                    append(record.mangaTitle)
                    append(" \u2014 ")
                    append(record.chapterName)
                    append("\n")
                    append("${record.pageCount} pages, ${record.bytes / 1024} KiB")
                    record.error?.let { append("\n$it") }
                }
                textSize = 12f
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (record.status == DownloadStatus.COMPLETE) {
                row.addView(Button(this).apply {
                    text = "Open"
                    setOnClickListener { open(record) }
                })
            }
            row.addView(Button(this).apply {
                text = "Delete"
                setOnClickListener { remove(record) }
            })
            column.addView(row)
            list.addView(column)
        }
    }

    private fun open(record: DownloadedChapter) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, record.packageName)
                .putExtra(SourceExtras.SOURCE_ID, record.sourceId)
                .putExtra(SourceExtras.MANGA_REF, record.mangaRef)
                .putExtra(SourceExtras.MANGA_TITLE, record.mangaTitle)
                .putExtra(SourceExtras.CHAPTER_REF, record.chapterRef)
                .putExtra(SourceExtras.CHAPTER_NAME, record.chapterName),
        )
    }

    private fun remove(record: DownloadedChapter) {
        val repository = repository ?: return
        scope.launch {
            runCatching {
                repository.downloads.remove(record.packageName, record.sourceId, record.mangaRef, record.chapterRef)
            }.onFailure { Diagnostics.recordError(it.describe()) }
            runOnUiThread { load() }
        }
    }
}
