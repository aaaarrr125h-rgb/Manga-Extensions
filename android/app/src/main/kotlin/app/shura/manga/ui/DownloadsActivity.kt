package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import app.shura.manga.ShuraRepository
import app.shura.source.host.DownloadStatus
import app.shura.source.host.DownloadedChapter
import kotlinx.coroutines.launch
import app.shura.manga.R

/**
 * Chapters kept on disk, newest first.
 *
 * Not a destination in the bottom bar: a download is something the user chooses from the reader and
 * revisits from Library or Settings, not a place to browse. Complete chapters open offline; failed
 * ones keep their error so a retry has context.
 */
class DownloadsActivity : AsyncScreenActivity() {

    private var repository: ShuraRepository? = null
    private lateinit var content: LinearLayout
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(titleRes = R.string.downloads_title, showBack = true).content
        addStatusViews(content)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        load()
    }

    private fun load() {
        runLoad(
            loading = str(R.string.loading),
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
            status.text = ""
            list.addView(
                emptyState(str(R.string.downloads_empty), str(R.string.downloads_empty_hint)),
            )
            return
        }
        val complete = records.count { it.status == DownloadStatus.COMPLETE }
        status.text = "${str(R.string.downloads_complete)} $complete · ${records.size - complete}"
        records.forEach { record ->
            list.addView(row(record))
            list.addView(divider())
        }
    }

    private fun row(record: DownloadedChapter): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(0, dp(12), 0, dp(12))
        }
        column.addView(text(record.mangaTitle, 15f, ShuraColors.onBackground, maxLines = 1))
        column.addView(
            text(record.chapterName, 13f, ShuraColors.textSecondary, maxLines = 1).apply {
                setPaddingRelative(0, dp(2), 0, 0)
            },
        )
        val size = "${record.bytes / 1024} KiB"
        column.addView(
            text(
                str(R.string.downloads_pages, record.pageCount, size),
                11f,
                if (record.status == DownloadStatus.COMPLETE) ShuraColors.textTertiary else ShuraColors.error,
            ).apply { setPaddingRelative(0, dp(2), 0, 0) },
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingRelative(0, dp(8), 0, 0)
        }
        if (record.status == DownloadStatus.COMPLETE) {
            actions.addView(
                secondaryButton(str(R.string.downloads_open)) { open(record) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                },
            )
        }
        actions.addView(
            secondaryButton(str(R.string.downloads_delete)) { remove(record) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        column.addView(actions)
        return column
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
