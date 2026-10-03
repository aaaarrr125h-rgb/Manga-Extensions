package app.shura.manga.ui

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import app.shura.manga.BuildConfig
import app.shura.manga.ShuraRepository
import app.shura.source.host.DownloadStatus
import app.shura.manga.R

/**
 * The on-device diagnostic screen.
 *
 * It answers, in one place, the questions that otherwise need logcat: which build is running, which
 * repositories are configured and whether each answered, what the merged extension list looks like
 * (including packages several repositories publish), which extensions installed, which sources
 * actually loaded pages, and what the last failures were. Every check is run live and its failure is
 * reported as text rather than thrown, so a half-working device still produces a useful screen.
 *
 * It is the place the extension-installation problem is meant to be diagnosable from, so it reports
 * registered and loadable separately rather than collapsing them into "installed".
 */
class DiagnosticsActivity : AsyncScreenActivity() {

    private lateinit var content: LinearLayout
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = buildScreen(titleRes = R.string.diagnostics_title, showBack = true).content
        addStatusViews(content)

        content.addView(spacer(4))
        content.addView(primaryButton(str(R.string.diagnostics_run)) { runChecks() })
        content.addView(spacer(8))
        content.addView(
            secondaryButton(str(R.string.diagnostics_clear)) {
                Diagnostics.clear()
                runChecks()
            },
        )
        content.addView(spacer(16))
        output = text("", 11f, ShuraColors.textSecondary).apply { typeface = Typeface.MONOSPACE }
        content.addView(output)

        runChecks()
    }

    private fun runChecks() {
        runLoad(
            loading = str(R.string.loading),
            retry = { runChecks() },
            block = { collect() },
            onLoaded = { output.text = it },
        )
    }

    private suspend fun collect(): String {
        val report = StringBuilder()
        report.appendLine("build: ${BuildConfig.GIT_SHA}")
        report.appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        report.appendLine("built: ${BuildConfig.BUILD_TIME}")
        report.appendLine("default repository: ${ShuraRepository.DEFAULT_REPOSITORY_URL}")
        report.appendLine()

        val repository = try {
            ShuraRepository.create(this)
        } catch (failure: Throwable) {
            Diagnostics.recordError(failure.describe())
            report.appendLine("wiring: FAILED ${failure.describe()}")
            return report.toString()
        }
        report.appendLine("wiring: ok")

        try {
            val catalogue = repository.repositories.refresh()
            report.appendLine(
                "repositories: ${catalogue.reachableCount}/${catalogue.repositories.size} reachable",
            )
            catalogue.repositories.forEach { status ->
                val marker = if (status.config.isDefault) " [default]" else ""
                report.appendLine("  ${status.config.name}$marker")
                report.appendLine("    ${status.config.url}")
                if (status.isReachable) {
                    report.appendLine(
                        "    reachable: ${status.extensionCount} installable, " +
                            "${status.unusableCount} unusable, key " +
                            "${status.snapshot!!.signingFingerprint.take(12)}…",
                    )
                } else {
                    report.appendLine("    FAILED: ${status.error}")
                }
            }
            report.appendLine("extensions available (deduplicated): ${catalogue.extensions.size}")
            report.appendLine("duplicate packages: ${catalogue.duplicates.size}")
            catalogue.duplicates.take(10).forEach { duplicate ->
                report.appendLine(
                    "  ${duplicate.packageName}: kept ${duplicate.kept.name}, ignored ${duplicate.ignored.name}",
                )
            }
        } catch (failure: Throwable) {
            Diagnostics.recordError(failure.describe())
            report.appendLine("repositories: error - ${failure.describe()}")
        }

        try {
            val installed = repository.repositories.installed()
            report.appendLine("installed extensions: ${installed.size}")
            installed.forEach { extension ->
                // Registered and loadable are different facts. Reporting both is what makes an
                // "installed but no sources" report diagnosable from this screen alone.
                val loads = repository.repositories.isLoadable(extension.packageName)
                report.appendLine(
                    "  ${extension.packageName} v${extension.versionCode} (${extension.extensionLib}) " +
                        (if (loads) "loads" else "NEEDS REPAIR"),
                )
            }
        } catch (failure: Throwable) {
            Diagnostics.recordError(failure.describe())
            report.appendLine("installed extensions: error - ${failure.describe()}")
        }

        try {
            val sources = repository.repositories.catalogue()
            report.appendLine("loadable sources: ${sources.size}")
        } catch (failure: Throwable) {
            Diagnostics.recordError(failure.describe())
            report.appendLine("loadable sources: error - ${failure.describe()}")
        }

        try {
            val downloads = repository.downloads.all()
            val complete = downloads.count { it.status == DownloadStatus.COMPLETE }
            report.appendLine("downloads: $complete complete, ${downloads.size - complete} failed")
            report.appendLine("library: ${repository.library.entries().size} manga")
        } catch (failure: Throwable) {
            Diagnostics.recordError(failure.describe())
            report.appendLine("library/downloads: error - ${failure.describe()}")
        }

        report.appendLine()
        report.appendLine("last error: ${Diagnostics.lastError ?: "none"}")
        Diagnostics.recentErrors().forEach { report.appendLine("  $it") }
        return report.toString()
    }
}
