package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shura.manga.ShuraRepository
import app.shura.source.host.InstalledSource

class SourcesActivity : AsyncScreenActivity() {

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

        root.addView(TextView(this).apply { text = "Sources"; textSize = 20f })
        addStatusViews(root)
        root.addView(Button(this).apply {
            text = "Reload sources"
            setOnClickListener { load() }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        load()
    }

    private fun load() {
        runLoad(
            loading = "Loading installed extensions...",
            retry = { load() },
            block = { ShuraRepository.create(this).repository.catalogue() },
            onLoaded = { sources -> show(sources) },
        )
    }

    private fun show(sources: List<InstalledSource>) {
        list.removeAllViews()
        if (sources.isEmpty()) {
            status.text = "No installed extensions. Install one from Extensions first."
            return
        }
        status.text = "${sources.size} source(s)"
        sources.forEach { source ->
            list.addView(Button(this).apply {
                text = "${source.descriptor.name} (${source.descriptor.language})"
                setOnClickListener { open(source) }
            })
        }
    }

    private fun open(source: InstalledSource) {
        startActivity(
            Intent(this, BrowseActivity::class.java)
                .putExtra(SourceExtras.PACKAGE, source.packageName)
                .putExtra(SourceExtras.SOURCE_ID, source.descriptor.sourceId),
        )
    }
}
