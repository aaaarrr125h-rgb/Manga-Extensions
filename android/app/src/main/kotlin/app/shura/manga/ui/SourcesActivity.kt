package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.shura.manga.ShuraRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SourcesActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Sources"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        })
        val log = TextView(this).apply { text = "Loading..."; textSize = 11f }
        root.addView(log)

        scope.launch {
            val sources = try {
                val repository = ShuraRepository.create(this@SourcesActivity)
                repository.repository.catalogue()
            } catch (e: Exception) {
                runOnUiThread { log.text = "Error: ${e.message}" }
                return@launch
            }
            if (sources.isEmpty()) {
                runOnUiThread { log.text = "No installed extensions. Install one from Extensions first." }
                return@launch
            }
            runOnUiThread {
                log.text = "${sources.size} source(s)"
                sources.forEach { source ->
                    root.addView(Button(this@SourcesActivity).apply {
                        text = "${source.descriptor.name} (${source.descriptor.language})"
                        setOnClickListener {
                            startActivity(
                                Intent(this@SourcesActivity, BrowseActivity::class.java)
                                    .putExtra(SourceExtras.PACKAGE, source.packageName)
                                    .putExtra(SourceExtras.SOURCE_ID, source.descriptor.sourceId),
                            )
                        }
                    })
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
