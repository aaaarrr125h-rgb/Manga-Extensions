package app.shura.manga.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.shura.manga.BuildConfig
import app.shura.manga.ShuraRepository
import app.shura.source.host.DownloadStatus

/**
 * The on-device diagnostic screen.
 *
 * It answers, in one place, the questions that otherwise need logcat: which build is running, can
 * this device reach the repository, is the signing key what we expect, which extensions installed,
 * which sources actually loaded pages, and what the last failures were. Every check is run live and
 * its failure is reported as text rather than thrown, so a half-working device still produces a
 * useful screen.
 */
class DiagnosticsActivity : AsyncScreenActivity() {

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply { text = "Diagnostics"; textSize = 20f })
        addStatusViews(root)
        root.addView(Button(this).apply {
            text = "Run checks"
            setOnClickListener { runChecks() }
        })
        root.addView(Button(this).apply {
            text = "Clear recorded errors"
            setOnClickListener {
                Diagnostics.clear()
                runChecks()
            }
        })
        output = TextView(this).apply { textSize = 11f }
        root.addView(output)

        runChecks()
    }

    private fun runChecks() {
        runLoad(
            loading = "Running diagnostics...",
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
        report.appendLine("repository: ${ShuraRepository.DEFAULT_REPOSITORY_URL}")
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
            val snapshot = repository.repository.discover()
            report.appendLine(
                "repository reachable: yes, ${snapshot.extensions.size} installable, " +
                    "${snapshot.unusable.size} unusable",
            )
            report.appendLine("trusted key: ${snapshot.signingFingerprint}")
        } catch (failure: Throwable) {
            Diagnostics.recordError(failure.describe())
            report.appendLine("repository reachable: NO - ${failure.describe()}")
        }

        try {
            val installed = repository.repository.installed()
            report.appendLine("installed extensions: ${installed.size}")
            installed.forEach { extension ->
                report.appendLine("  ${extension.packageName} v${extension.versionCode} (${extension.extensionLib})")
            }
        } catch (failure: Throwable) {
            Diagnostics.recordError(failure.describe())
            report.appendLine("installed extensions: error - ${failure.describe()}")
        }

        try {
            val sources = repository.repository.catalogue()
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
