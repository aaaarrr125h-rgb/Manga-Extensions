package app.shura.manga.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.shura.manga.ShuraRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourcesActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48,48,48,48) }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "Sources"; textSize = 20f; setPadding(0,0,0,16) })
        val log = TextView(this).apply { text = "Loading..."; textSize = 11f }
        root.addView(log)

        scope.launch {
            try {
                val repo = ShuraRepository.create(this@SourcesActivity)
                val installed = withContext(Dispatchers.IO) { repo.repository.installed() }
                if (installed.isEmpty()) {
                    log.text = "No installed extensions"
                    return@launch
                }
                val catalogue = withContext(Dispatchers.IO) { repo.repository.catalogue() }
                if (catalogue.isEmpty()) {
                    log.text = "Installed: ${installed.size}\nNo sources loaded"
                } else {
                    log.text = catalogue.joinToString("\n") { "${it.descriptor.name} • ${it.abi.version} • ${it.descriptor.language}" }
                }
            } catch (e: Exception) {
                log.text = "Error: ${e.message}"
            }
        }
    }
}
