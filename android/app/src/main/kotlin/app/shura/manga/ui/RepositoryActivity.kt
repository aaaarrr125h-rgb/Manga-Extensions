package app.shura.manga.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.shura.manga.ShuraRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RepositoryActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default)

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
                status.text = "Refreshing..."
                scope.launch {
                    val message = try {
                        val repository = ShuraRepository.create(this@RepositoryActivity)
                        val snapshot = repository.repository.discover()
                        "OK: ${snapshot.extensions.size} extensions, ${snapshot.unusable.size} unusable"
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                    runOnUiThread { status.text = message }
                }
            }
        })
        root.addView(TextView(this).apply {
            text = "Default: ${ShuraRepository.DEFAULT_REPOSITORY_URL}"
            textSize = 10f
            setPadding(0, 24, 0, 0)
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
