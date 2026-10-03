package app.shura.manga.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import app.shura.manga.R
import app.shura.manga.ShuraRepository
import app.shura.source.host.DuplicateRepositoryException
import app.shura.source.host.RepositoryCatalogue
import app.shura.source.host.RepositoryConfig
import app.shura.source.host.RepositoryStatus
import app.shura.source.host.RepositoryUrls
import kotlinx.coroutines.launch

/**
 * Repository management, reachable from Settings only.
 *
 * The page lists every configured repository with what this device knows about it: its URL, whether
 * it answered on the last refresh, how many extensions it publishes, when it last answered, and
 * whether it is the default. A repository the user added can be removed; the one this build ships
 * with cannot. Adding a repository fetches it first and refuses to store one that does not answer
 * with a usable index, so the list never contains a repository that is known to be broken.
 */
class RepositoryActivity : AsyncScreenActivity() {

    private var repository: ShuraRepository? = null
    private lateinit var content: LinearLayout
    private lateinit var list: LinearLayout
    private var catalogue: RepositoryCatalogue? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(titleRes = R.string.repository_title, showBack = true).content
        addStatusViews(content)

        content.addView(spacer(4))
        content.addView(primaryButton(str(R.string.repository_add)) { addRepository() })
        content.addView(spacer(12))
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
                created.repositories.refresh()
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(catalogue: RepositoryCatalogue) {
        this.catalogue = catalogue
        list.removeAllViews()

        list.addView(
            text(
                str(R.string.repository_count, catalogue.reachableCount, catalogue.repositories.size),
                12f,
                ShuraColors.textSecondary,
            ),
        )
        list.addView(spacer(8))

        catalogue.repositories.forEach { status ->
            list.addView(card(status))
            list.addView(spacer(10))
        }

        if (catalogue.duplicates.isNotEmpty()) {
            list.addView(spacer(4))
            list.addView(
                text(
                    str(R.string.repository_duplicates, catalogue.duplicates.size),
                    11f,
                    ShuraColors.textTertiary,
                ),
            )
        }
    }

    private fun card(status: RepositoryStatus): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ShuraColors.card, 16, ShuraColors.outline, 1)
            setPaddingRelative(dp(14), dp(14), dp(14), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            text(status.config.name, 16f, ShuraColors.onBackground, bold = true, maxLines = 1),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (status.config.isDefault) header.addView(chip(str(R.string.repository_default_badge)))
        card.addView(header)

        card.addView(
            text(status.config.url, 11f, ShuraColors.textTertiary, maxLines = 2).apply {
                setPaddingRelative(0, dp(3), 0, 0)
            },
        )

        val stateColor = if (status.isReachable) ShuraColors.success else ShuraColors.error
        val stateText = if (status.isReachable) {
            str(R.string.repository_status_ok, status.extensionCount, status.unusableCount)
        } else {
            str(R.string.repository_status_error, status.error.orEmpty())
        }
        card.addView(
            text(stateText, 12f, stateColor).apply { setPaddingRelative(0, dp(6), 0, 0) },
        )
        card.addView(
            text(lastSyncLabel(status), 11f, ShuraColors.textTertiary).apply {
                setPaddingRelative(0, dp(2), 0, dp(8))
            },
        )

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!status.config.isDefault) {
            actions.addView(
                smallButton(str(R.string.repository_make_default)) { makeDefault(status.config.url) },
            )
            actions.addView(spacer(8))
        }
        if (!status.config.builtIn) {
            actions.addView(
                smallButton(str(R.string.repository_remove)) { confirmRemove(status.config) },
            )
        }
        if (actions.childCount == 0) {
            // The built-in default repository has no action of its own; say so rather than leaving
            // an empty row.
            card.addView(text(str(R.string.repository_builtin_hint), 11f, ShuraColors.textTertiary))
        } else {
            card.addView(
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(actions)
                },
            )
        }
        return card
    }

    private fun smallButton(label: CharSequence, onClick: () -> Unit): android.widget.Button =
        secondaryButton(label, onClick).apply {
            textSize = 12f
            setPaddingRelative(dp(12), dp(7), dp(12), dp(7))
        }

    private fun lastSyncLabel(status: RepositoryStatus): String {
        val at = status.lastSyncedAtMillis ?: return str(R.string.repository_never_synced)
        val stamped = DateFormat.format("yyyy-MM-dd HH:mm", at).toString()
        return str(R.string.repository_last_sync, stamped)
    }

    // ------------------------------------------------------------------ actions

    private fun makeDefault(url: String) {
        val manager = repository?.repositories ?: return
        if (manager.setDefault(url)) refresh()
    }

    private fun confirmRemove(config: RepositoryConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.repository_remove)
            .setMessage(str(R.string.repository_remove_confirm, config.name))
            .setPositiveButton(R.string.repository_remove) { _, _ ->
                repository?.repositories?.remove(config.url)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addRepository() {
        val nameInput = EditText(this).apply {
            hint = str(R.string.repository_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val urlInput = EditText(this).apply {
            hint = str(R.string.repository_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(20), dp(8), dp(20), dp(4))
            addView(nameInput)
            addView(spacer(8))
            addView(urlInput)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.repository_add)
            .setView(form)
            .setPositiveButton(R.string.repository_add_action, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                submitAdd(dialog, nameInput, urlInput)
            }
        }
        dialog.show()
    }

    private fun submitAdd(dialog: AlertDialog, nameInput: EditText, urlInput: EditText) {
        val manager = repository?.repositories ?: return
        val name = nameInput.text.toString().trim()
        val rawUrl = urlInput.text.toString().trim()

        val normalized = try {
            RepositoryUrls.normalize(rawUrl)
        } catch (failure: Exception) {
            urlInput.error = failure.describe()
            return
        }
        if (manager.configs().any { it.url == normalized }) {
            urlInput.error = DuplicateRepositoryException(normalized).describe()
            return
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        urlInput.error = null
        scope.launch {
            val result = runCatching {
                // Fetch first: a URL that does not answer with a usable index is never stored.
                manager.validate(RepositoryConfig(name, normalized))
                manager.add(name, normalized)
            }
            runOnUiThread {
                result.fold(
                    onSuccess = {
                        dialog.dismiss()
                        refresh()
                    },
                    onFailure = { failure ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        urlInput.error = failure.describe()
                    },
                )
            }
        }
    }
}
