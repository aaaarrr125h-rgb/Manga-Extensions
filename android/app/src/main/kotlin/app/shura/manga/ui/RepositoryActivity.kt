package app.shura.manga.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.shura.manga.ShuraRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RepositoryActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        setContentView(root)

        root.addView(TextView(this).apply { text = "Repositories"; textSize = 20f })
        val status = TextView(this).apply { text = "Idle"; setPadding(0, 16, 0, 16) }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Refresh Default Repository"
            setOnClickListener {
                scope.launch {
                    status.text = "Refreshing..."
                    try {
                        val repo = ShuraRepository.create(this@RepositoryActivity)
                        val snap = withContext(Dispatchers.IO) { repo.repository.discover() }
                        status.text = "OK: ${snap.extensions.size} extensions • ${snap.unusable.size} unusable"
                    } catch (e: Exception) {
                        status.text = "Error: ${e.message}"
                    }
                }
            }
        })
        root.addView(TextView(this).apply {
            text = "Default: https://raw.githubusercontent.com/aaaarrr125h-rgb/Manga-Extensions/main/repo/index.json"
            textSize = 10f
            setPadding(0, 24, 0, 0)
        })
    }
}
