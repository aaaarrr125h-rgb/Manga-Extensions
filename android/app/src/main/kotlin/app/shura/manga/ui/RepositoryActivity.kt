package app.shura.manga.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.shura.manga.ShuraRepository

class RepositoryActivity : AsyncScreenActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        setContentView(root)

        root.addView(TextView(this).apply { text = "Repositories"; textSize = 20f })
        addStatusViews(root)

        root.addView(Button(this).apply {
            text = "Refresh Default Repository"
            setOnClickListener { refresh() }
        })
        root.addView(TextView(this).apply {
            text = "Default: ${ShuraRepository.DEFAULT_REPOSITORY_URL}"
            textSize = 10f
            setPadding(0, 24, 0, 0)
        })

        refresh()
    }

    private fun refresh() {
        runLoad(
            loading = "Refreshing repository...",
            retry = { refresh() },
            block = {
                val repository = ShuraRepository.create(this)
                val snapshot = repository.repository.discover()
                "OK: ${snapshot.extensions.size} extension(s), ${snapshot.unusable.size} unusable"
            },
            onLoaded = { summary -> status.text = summary },
        )
    }
}
