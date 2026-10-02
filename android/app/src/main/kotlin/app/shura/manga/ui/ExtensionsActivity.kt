package app.shura.manga.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.shura.manga.ShuraRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ExtensionsActivity : ComponentActivity() {
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
            text = "Extensions"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        })
        val log = TextView(this).apply { text = "Loading..."; textSize = 11f }
        root.addView(log)

        scope.launch {
            val message = try {
                val repository = ShuraRepository.create(this@ExtensionsActivity)
                val snapshot = repository.repository.discover()
                val text = snapshot.extensions.sortedBy { it.name }
                    .joinToString("\n") { "${it.name} • ABI ${it.abi.version} • v${it.versionName}" }
                text.ifEmpty { "No extensions available" }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread { log.text = message }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
