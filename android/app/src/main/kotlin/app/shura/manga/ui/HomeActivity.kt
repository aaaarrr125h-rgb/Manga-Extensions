package app.shura.manga.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.shura.manga.BuildConfig

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "Shura"
            textSize = 26f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Build: ${BuildConfig.GIT_SHA}"
            textSize = 10f
            setPadding(0, 0, 0, 32)
        })

        root.addView(Button(this).apply {
            text = "Repositories"
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, RepositoryActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Extensions"
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, ExtensionsActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Sources"
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, SourcesActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Library"
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, LibraryActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Downloads"
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, DownloadsActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Diagnostics"
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, DiagnosticsActivity::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "Self Test"
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, app.shura.manga.MainActivity::class.java))
            }
        })
    }
}
