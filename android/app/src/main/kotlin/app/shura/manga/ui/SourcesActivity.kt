package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import app.shura.manga.ShuraRepository
import app.shura.source.host.InstalledSource
import app.shura.manga.R

/**
 * The installed extensions' sources, grouped by language.
 *
 * A source is only a gateway: this screen lists what can be opened and hands the chosen one to
 * Browse. Installing or removing extensions stays in Settings, under Sources, because it is a
 * configuration act rather than a reading one.
 */
class SourcesActivity : AsyncScreenActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(titleRes = R.string.sources_title, bottomTab = Tab.SOURCES).content
        addStatusViews(content)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        runLoad(
            loading = str(R.string.loading),
            retry = { load() },
            block = { ShuraRepository.create(this).repositories.catalogue() },
            onLoaded = { show(it) },
        )
    }

    private fun show(sources: List<InstalledSource>) {
        content.removeAllViews()
        addStatusViews(content)
        status.text = ""

        content.addView(spacer(4))

        if (sources.isEmpty()) {
            content.addView(
                emptyState(
                    str(R.string.sources_empty),
                    str(R.string.sources_empty_hint),
                    str(R.string.settings_extensions),
                ) { startActivity(Intent(this, ExtensionsActivity::class.java)) },
            )
            return
        }

        sources.groupBy { it.descriptor.language }.toSortedMap().forEach { (language, group) ->
            content.addView(spacer(16))
            content.addView(sectionHeader(language))
            group.sortedBy { it.descriptor.name }.forEach { source ->
                content.addView(
                    settingRow(
                        source.descriptor.name,
                        "${source.descriptor.language} · ${source.extension.versionName}",
                    ) { open(source) },
                )
                content.addView(divider())
            }
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
