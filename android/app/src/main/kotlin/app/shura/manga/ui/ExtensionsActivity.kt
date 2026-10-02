package app.shura.manga.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shura.manga.ShuraRepository
import app.shura.source.host.AvailableExtension
import app.shura.source.host.InstallOutcome
import app.shura.source.host.RepositorySnapshot
import kotlinx.coroutines.launch

class ExtensionsActivity : AsyncScreenActivity() {

    private var repository: ShuraRepository? = null
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

        root.addView(TextView(this).apply { text = "Extensions"; textSize = 20f })
        addStatusViews(root)
        root.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { refresh() }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        refresh()
    }

    private fun refresh() {
        runLoad(
            loading = "Reading repository...",
            retry = { refresh() },
            block = {
                val created = ShuraRepository.create(this)
                repository = created
                created.repository.discover() to created
            },
            onLoaded = { (snapshot, _) -> show(snapshot) },
        )
    }

    private fun show(snapshot: RepositorySnapshot) {
        list.removeAllViews()
        if (snapshot.extensions.isEmpty()) {
            status.text = "No usable extensions in the repository"
            return
        }
        status.text = "${snapshot.extensions.size} extension(s), ${snapshot.unusable.size} unusable"
        snapshot.extensions.sortedBy { it.name }.forEach { available ->
            list.addView(Button(this).apply {
                text = "${available.name}  •  ${available.versionName}"
                setOnClickListener { install(available, this) }
            })
        }
    }

    private fun install(available: AvailableExtension, button: Button) {
        val target = repository ?: return
        button.isEnabled = false
        button.text = "Installing ${available.name}..."
        scope.launch {
            val result = runCatching { target.repository.install(available) }
            runOnUiThread {
                button.isEnabled = true
                result.fold(
                    onSuccess = { outcome -> button.text = "${available.name}  •  ${label(outcome)}" },
                    onFailure = { failure ->
                        Diagnostics.recordError(failure.describe())
                        button.text = "${available.name}  •  failed, tap to retry"
                    },
                )
            }
        }
    }

    private fun label(outcome: InstallOutcome): String = when (outcome) {
        is InstallOutcome.AlreadyInstalled -> "installed"
        is InstallOutcome.Installed -> "installed"
        is InstallOutcome.Upgraded -> "updated"
        is InstallOutcome.Refused -> "refused: ${outcome.reason}"
    }
}
