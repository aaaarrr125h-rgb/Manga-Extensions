package app.shura.manga.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import app.shura.manga.ShuraRepository
import app.shura.source.host.AvailableExtension
import app.shura.source.host.InstallOutcome
import app.shura.source.host.RepositorySnapshot
import kotlinx.coroutines.launch
import app.shura.manga.R

/**
 * Every installable extension in the repository.
 *
 * Installation is the only action here, and each row owns its own progress so a slow install never
 * blocks the rest of the list. Verification and refusal happen in the host layer; this screen only
 * reports the outcome.
 */
class ExtensionsActivity : AsyncScreenActivity() {

    private var repository: ShuraRepository? = null
    private lateinit var content: LinearLayout
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(titleRes = R.string.extensions_title, showBack = true).content
        addStatusViews(content)

        content.addView(spacer(4))
        content.addView(secondaryButton(str(R.string.extensions_refresh)) { refresh() })
        content.addView(spacer(8))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        refresh()
    }

    private fun refresh() {
        runLoad(
            loading = str(R.string.loading),
            retry = { refresh() },
            block = {
                val created = ShuraRepository.create(this)
                repository = created
                created.repository.discover()
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(snapshot: RepositorySnapshot) {
        list.removeAllViews()
        if (snapshot.extensions.isEmpty()) {
            status.text = str(R.string.extensions_empty)
            return
        }
        status.text = "${snapshot.extensions.size} · ${snapshot.unusable.size}"
        snapshot.extensions.sortedBy { it.name }.forEach { available ->
            list.addView(row(available))
            list.addView(divider())
        }
    }

    private fun row(available: AvailableExtension): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(0, dp(8), 0, dp(8))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(available.name, 15f, ShuraColors.onBackground, maxLines = 1))
        column.addView(
            text("${available.versionName} · ${available.abi.version}", 11f, ShuraColors.textSecondary).apply {
                setPaddingRelative(0, dp(2), 0, 0)
            },
        )
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val button = secondaryButton(str(R.string.extensions_install)) { install(available) }
        row.addView(button)
        return row
    }

    private fun install(available: AvailableExtension) {
        val target = repository ?: return
        scope.launch {
            val result = runCatching { target.repository.install(available) }
            runOnUiThread {
                result.fold(
                    onSuccess = { outcome -> status.text = "${available.name} · ${label(outcome)}" },
                    onFailure = { failure ->
                        Diagnostics.recordError(failure.describe())
                        status.text = str(R.string.error_prefix, failure.describe())
                    },
                )
            }
        }
    }

    private fun label(outcome: InstallOutcome): String = when (outcome) {
        is InstallOutcome.AlreadyInstalled -> str(R.string.extensions_installed)
        is InstallOutcome.Installed -> str(R.string.extensions_installed)
        is InstallOutcome.Upgraded -> str(R.string.extensions_updated)
        is InstallOutcome.Refused -> str(R.string.extensions_refused, outcome.reason)
    }
}
