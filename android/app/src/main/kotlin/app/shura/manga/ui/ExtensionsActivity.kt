package app.shura.manga.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import app.shura.manga.ShuraRepository
import app.shura.source.host.AvailableExtension
import app.shura.source.host.InstallOutcome
import app.shura.source.host.InstalledExtension
import app.shura.source.host.RepositorySnapshot
import kotlinx.coroutines.launch
import app.shura.manga.R

/**
 * Every installable extension in the repository.
 *
 * Installation is the main action here, and each row owns its own progress so a slow install never
 * blocks the rest of the list. The screen also reflects what is already on disk: an installed row
 * shows its version, an installed package the repository has moved past offers an update, and a
 * registered extension whose file no longer loads is reported as needing repair rather than being
 * passed off as working. Verification and refusal happen in the host layer; this screen only reports
 * the outcome and offers the next action.
 */
class ExtensionsActivity : AsyncScreenActivity() {

    private var repository: ShuraRepository? = null
    private lateinit var content: LinearLayout
    private lateinit var list: LinearLayout
    private var lastSnapshot: RepositorySnapshot? = null

    /** What is registered, keyed by package. Kept in memory so a row can render its own state. */
    private val installed = mutableMapOf<String, InstalledExtension>()

    /** Packages whose registered artifact really loads, not merely registered. */
    private val loadable = mutableSetOf<String>()

    private data class Screen(
        val snapshot: RepositorySnapshot,
        val installed: Map<String, InstalledExtension>,
        val loadable: Set<String>,
    )

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
                val snapshot = created.repository.discover()
                val installedNow = created.repository.installed().associateBy { it.packageName }
                // Loadability is checked here, off the main thread: opening an extension runs its
                // code, and a registered file that cannot be opened must not be called installed.
                val loadableNow = installedNow.keys
                    .filter { created.repository.isLoadable(it) }
                    .toSet()
                Screen(snapshot, installedNow, loadableNow)
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(screen: Screen) {
        lastSnapshot = screen.snapshot
        installed.clear()
        installed.putAll(screen.installed)
        loadable.clear()
        loadable.addAll(screen.loadable)
        render()
    }

    private fun render() {
        val snapshot = lastSnapshot ?: return
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
        stateLabel(available)?.let { label ->
            column.addView(
                text(label.text, 11f, label.color).apply { setPaddingRelative(0, dp(3), 0, 0) },
            )
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(secondaryButton(actionLabel(available)) { install(available) })
        return row
    }

    /** The action the row's button offers, based on what is on disk. */
    private fun actionLabel(available: AvailableExtension): String {
        val record = installed[available.packageName]
        return when {
            record == null -> str(R.string.extensions_install)
            record.versionCode != available.versionCode -> str(R.string.extensions_update)
            available.packageName in loadable -> str(R.string.extensions_reinstall)
            else -> str(R.string.extensions_repair)
        }
    }

    private fun stateLabel(available: AvailableExtension): StateLabel? {
        val record = installed[available.packageName] ?: return null
        val loads = available.packageName in loadable
        return when {
            record.versionCode == available.versionCode && loads ->
                StateLabel(str(R.string.extensions_state_installed, record.versionName), ShuraColors.success)
            loads ->
                StateLabel(str(R.string.extensions_state_installed, record.versionName), ShuraColors.textSecondary)
            else ->
                StateLabel(str(R.string.extensions_state_repair), ShuraColors.error)
        }
    }

    private fun install(available: AvailableExtension) {
        val target = repository ?: return
        status.setOnClickListener(null)
        status.text = str(R.string.loading)
        scope.launch {
            val result = runCatching {
                val outcome = target.repository.install(available)
                // A download that lands is not yet a working extension. Proving it loads is what
                // separates "installed" from "downloaded but broken"; if it cannot load, the row
                // offers a retry rather than claiming success.
                val loads = target.repository.isLoadable(available.packageName)
                outcome to loads
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { (outcome, loads) ->
                        val refusal = outcome.refusalReason
                        if (refusal != null) {
                            status.text = str(R.string.extensions_refused, refusal)
                            return@fold
                        }
                        // Re-read the record so the row's label and button reflect what is now on
                        // disk, whether or not the extension turned out to be loadable.
                        target.repository.installed()
                            .firstOrNull { it.packageName == available.packageName }
                            ?.let { installed[available.packageName] = it }
                        if (loads) loadable += available.packageName else loadable -= available.packageName
                        render()
                        if (loads) {
                            status.text = "${available.name} · ${label(outcome)}"
                        } else {
                            Diagnostics.recordError(
                                "${available.packageName}: installed but did not load",
                            )
                            status.text = str(R.string.extensions_start_failed)
                            status.setOnClickListener { install(available) }
                        }
                    },
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

    private data class StateLabel(val text: String, val color: Int)
}
