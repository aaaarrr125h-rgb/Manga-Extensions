package app.shura.manga.ui

import android.os.Bundle
import android.widget.LinearLayout
import app.shura.manga.ShuraRepository
import app.shura.manga.R

/**
 * The repository the app trusts, and a way to re-read it.
 *
 * Reachable from Settings only. It reports what the index publishes rather than what was installed,
 * which is the distinction an "update available" decision is made from.
 */
class RepositoryActivity : AsyncScreenActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(titleRes = R.string.repository_title, showBack = true).content
        addStatusViews(content)

        content.addView(spacer(4))
        content.addView(primaryButton(str(R.string.repository_refresh)) { refresh() })
        content.addView(spacer(16))
        content.addView(
            text(str(R.string.repository_default, ShuraRepository.DEFAULT_REPOSITORY_URL), 11f, ShuraColors.textTertiary),
        )

        refresh()
    }

    private fun refresh() {
        runLoad(
            loading = str(R.string.loading),
            retry = { refresh() },
            block = {
                val snapshot = ShuraRepository.create(this).repository.discover()
                "${snapshot.extensions.size} installable · ${snapshot.unusable.size} unusable"
            },
            onLoaded = { status.text = it },
        )
    }
}
