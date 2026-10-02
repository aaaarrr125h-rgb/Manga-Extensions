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

class ExtensionsActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48,48,48,48) }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "Extensions"; textSize = 20f; setPadding(0,0,0,16) })
        val log = TextView(this).apply { text = "Loading..."; textSize = 11f }
        root.addView(log)

        scope.launch {
            try {
                val repo = ShuraRepository.create(this@ExtensionsActivity)
                val snap = withContext(Dispatchers.IO) { repo.repository.discover() }
                val sb = StringBuilder()
                snap.extensions.sortedBy { it.name }.forEach {
                    sb.append("${it.name} • ${it.abi.version} • v${it.versionName}\n")
                }
                if (sb.isEmpty()) sb.append("No extensions available")
                log.text = sb.toString()
            } catch (e: Exception) {
                log.text = "Error: ${e.message}"
            }
        }
    }
}
