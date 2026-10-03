package app.shura.manga.ui

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.shura.manga.R
import app.shura.manga.ShuraRepository
import app.shura.source.host.CataloguedExtension
import app.shura.source.host.InstallOutcome
import app.shura.source.host.InstalledExtension
import app.shura.source.host.RepositoryCatalogue
import kotlinx.coroutines.launch

/**
 * The installable extensions, merged from every enabled repository.
 *
 * This screen is about extensions, not repositories: a package published by more than one
 * repository is shown once (the manager has already picked the default repository's copy, or the
 * newer one), and each row still names the repository it comes from. Installation is per row, each
 * row keeps its own progress, and the row reflects what is on disk: installed, an update the
 * repository has moved past, or a registered extension whose file no longer loads ("needs repair").
 *
 * Search, a source-language filter and a persistent "hide English" toggle narrow the list, and the
 * filter lives in [Prefs] so it survives a restart.
 */
class ExtensionsActivity : AsyncScreenActivity() {

    private var repository: ShuraRepository? = null
    private lateinit var content: LinearLayout
    private lateinit var filters: LinearLayout
    private lateinit var list: LinearLayout
    private var catalogue: RepositoryCatalogue? = null
    private var query: String = ""
    private var selectedLanguage: String? = null

    private val installed = mutableMapOf<String, InstalledExtension>()
    private val loadable = mutableSetOf<String>()

    private data class Screen(
        val catalogue: RepositoryCatalogue,
        val installed: Map<String, InstalledExtension>,
        val loadable: Set<String>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedLanguage = Prefs.extensionsLanguage(this)
        content = buildScreen(titleRes = R.string.extensions_title, showBack = true).content
        addStatusViews(content)

        content.addView(spacer(4))
        filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(filters)
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
                val catalogue = created.repositories.refresh()
                val installedNow = created.repositories.installed().associateBy { it.packageName }
                // Loadability is checked off the main thread: opening an extension runs its code,
                // and a registered file that cannot be opened must not be called installed.
                val loadableNow = installedNow.keys
                    .filter { created.repositories.isLoadable(it) }
                    .toSet()
                Screen(catalogue, installedNow, loadableNow)
            },
            onLoaded = { show(it) },
        )
    }

    private fun show(screen: Screen) {
        catalogue = screen.catalogue
        installed.clear()
        installed.putAll(screen.installed)
        loadable.clear()
        loadable.addAll(screen.loadable)
        renderFilters()
        renderList()
    }

    // ------------------------------------------------------------------ filters

    private fun renderFilters() {
        filters.removeAllViews()

        val search = EditText(this).apply {
            hint = str(R.string.extensions_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            if (query.isNotEmpty()) {
                setText(query)
                setSelection(query.length)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    renderList()
                }
            })
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(secondaryButton(str(R.string.extensions_refresh)) { refresh() })
        filters.addView(searchRow)

        filters.addView(
            switchRow(
                str(R.string.extensions_hide_english),
                checked = Prefs.hideEnglish(this),
            ) {
                Prefs.setHideEnglish(this, it)
                renderList()
            },
        )

        if (catalogue == null) return
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(languageChip(null, str(R.string.extensions_all_languages)))
        languages().forEach { language ->
            chips.addView(spacer(8))
            chips.addView(languageChip(language, languageLabel(language)))
        }
        filters.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            },
        )
    }

    private fun languageChip(language: String?, label: String): TextView {
        val selected = selectedLanguage == language
        return text(label, 12f, if (selected) ShuraColors.onAccent else ShuraColors.textSecondary).apply {
            background = rounded(if (selected) ShuraColors.accent else ShuraColors.surfaceVariant, 999)
            setPaddingRelative(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedLanguage = language
                Prefs.setExtensionsLanguage(this@ExtensionsActivity, language)
                renderFilters()
                renderList()
            }
        }
    }

    private fun languages(): List<String> = (catalogue?.extensions ?: emptyList())
        .flatMap { it.sources }
        .map { it.language }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    private fun languageLabel(code: String): String = when (code) {
        "mul", "all" -> str(R.string.language_multiple)
        "other" -> str(R.string.language_other)
        else -> java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
    }

    // ------------------------------------------------------------------ list

    private fun visibleExtensions(): List<CataloguedExtension> {
        val all = catalogue?.extensions ?: return emptyList()
        val hideEnglish = Prefs.hideEnglish(this)
        return all.filter { extension ->
            val nameMatches = query.isBlank() ||
                extension.name.contains(query, ignoreCase = true) ||
                extension.packageName.contains(query, ignoreCase = true)
            val extensionLanguages = extension.sources.map { it.language }.filter { it.isNotBlank() }.toSet()
            val languageMatches = selectedLanguage == null || selectedLanguage in extensionLanguages
            val englishOnly = extensionLanguages.isNotEmpty() && extensionLanguages.all { it == "en" }
            nameMatches && languageMatches && !(hideEnglish && englishOnly)
        }
    }

    private fun renderList() {
        list.removeAllViews()
        val all = catalogue ?: return
        if (all.extensions.isEmpty()) {
            status.text = str(R.string.extensions_empty)
            return
        }
        val visible = visibleExtensions()
        status.text = str(R.string.extensions_shown, visible.size, all.extensions.size)
        if (visible.isEmpty()) {
            list.addView(
                text(str(R.string.extensions_no_match), 13f, ShuraColors.textSecondary).apply {
                    setPaddingRelative(0, dp(24), 0, 0)
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
            return
        }
        visible.forEach { extension ->
            list.addView(row(extension))
            list.addView(divider())
        }
    }

    private fun row(extension: CataloguedExtension): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(0, dp(8), 0, dp(8))
        }
        val icon = cover(44, 44, 10).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
        }
        CoverLoader.load(icon, extension.iconUrl, dp(44), dp(44))
        row.addView(icon)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(extension.name, 15f, ShuraColors.onBackground, maxLines = 1))
        column.addView(
            text("${extension.versionName} · ${extension.abi.version}", 11f, ShuraColors.textSecondary).apply {
                setPaddingRelative(0, dp(2), 0, 0)
            },
        )
        column.addView(
            text(extension.config.name, 11f, ShuraColors.textTertiary, maxLines = 1).apply {
                setPaddingRelative(0, dp(2), 0, 0)
            },
        )
        stateLabel(extension)?.let { label ->
            column.addView(
                text(label.text, 11f, label.color).apply { setPaddingRelative(0, dp(3), 0, 0) },
            )
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(secondaryButton(actionLabel(extension)) { install(extension) })
        return row
    }

    private fun actionLabel(extension: CataloguedExtension): String {
        val record = installed[extension.packageName]
        return when {
            record == null -> str(R.string.extensions_install)
            record.versionCode != extension.versionCode -> str(R.string.extensions_update)
            extension.packageName in loadable -> str(R.string.extensions_reinstall)
            else -> str(R.string.extensions_repair)
        }
    }

    private fun stateLabel(extension: CataloguedExtension): StateLabel? {
        val record = installed[extension.packageName] ?: return null
        val loads = extension.packageName in loadable
        return when {
            record.versionCode == extension.versionCode && loads ->
                StateLabel(str(R.string.extensions_state_installed, record.versionName), ShuraColors.success)
            loads ->
                StateLabel(str(R.string.extensions_state_installed, record.versionName), ShuraColors.textSecondary)
            else ->
                StateLabel(str(R.string.extensions_state_repair), ShuraColors.error)
        }
    }

    private fun install(extension: CataloguedExtension) {
        val target = repository ?: return
        status.setOnClickListener(null)
        status.text = str(R.string.loading)
        scope.launch {
            val result = runCatching {
                val outcome = target.repositories.install(extension)
                // A download that lands is not yet a working extension. Proving it loads separates
                // "installed" from "downloaded but broken"; if it cannot load, the row offers a
                // retry rather than claiming success.
                val loads = target.repositories.isLoadable(extension.packageName)
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
                        target.repositories.installed()
                            .firstOrNull { it.packageName == extension.packageName }
                            ?.let { installed[extension.packageName] = it }
                        if (loads) loadable += extension.packageName else loadable -= extension.packageName
                        renderList()
                        if (loads) {
                            status.text = "${extension.name} · ${label(outcome)}"
                        } else {
                            Diagnostics.recordError(
                                "${extension.packageName}: installed but did not load",
                            )
                            status.text = str(R.string.extensions_start_failed)
                            status.setOnClickListener { install(extension) }
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
