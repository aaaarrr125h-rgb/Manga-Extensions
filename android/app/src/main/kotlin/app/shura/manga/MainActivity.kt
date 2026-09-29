package app.shura.manga

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors

/**
 * The application's entry point.
 *
 * It exists so the package has a real MAIN/LAUNCHER target: without one the APK installs but
 * there is nothing to start, and the installer disables "Open".
 *
 * It currently runs [AbiRuntimeSelfTest] and shows the report, which is a temporary stand-in for
 * the library UI. The self test has to be started by a person on a real device, so the report is
 * on screen rather than only in logcat, and a tap re-runs it without reinstalling.
 *
 * The screen opens with the build identity of the running APK. A stale install and a fresh one
 * look identical otherwise, which is exactly the confusion this header removes: a report that does
 * not start with the commit it was built from is not a report about this APK.
 */
class MainActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(PADDING, PADDING, PADDING, PADDING)
            gravity = Gravity.START
        }
        setContentView(
            ScrollView(this).apply {
                addView(output)
                setBackgroundColor(0xFF101010.toInt())
                setOnClickListener { startSelfTest() }
            },
        )
        startSelfTest()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /**
     * Runs the self test off the main thread and puts the report on screen.
     *
     * Dexing is not involved here and nothing is downloaded, but classloading and instantiation
     * are disk work, and a report that arrived after an ANR would be no report at all.
     *
     * A crash is reported with its whole stack trace. The first line of a [Throwable] is rarely
     * enough to tell a missing asset from a broken dex, and this report is the only diagnosis
     * available on a device with no debugger attached.
     */
    private fun startSelfTest() {
        output.text = buildString {
            appendLine("running self test...")
            appendLine()
            append(buildIdentity())
        }
        worker.execute {
            val report = runCatching { AbiRuntimeSelfTest(this).run() }
                .fold(
                    onSuccess = { it },
                    onFailure = { "FAIL  self test crashed\n${it.stackTraceText()}" },
                )
            val screen = buildString {
                appendLine(buildIdentity())
                appendLine()
                append(report)
            }
            Log.i(LOG_TAG, "\n$screen")
            runOnUiThread { output.text = screen }
        }
    }

    /**
     * The commit, version and build time of the APK currently running, taken from the generated
     * [BuildConfig] rather than from the manifest, so a repackaged or re-signed APK cannot forge
     * the identity it claims.
     */
    private fun buildIdentity(): String = buildString {
        appendLine("SHURA SELF TEST")
        appendLine("BUILD: ${BuildConfig.GIT_SHA}")
        appendLine("VERSION: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("APK BUILD TIME: ${BuildConfig.BUILD_TIME}")
        appendLine("-".repeat(IDENTITY_RULE_WIDTH))
    }

    private fun Throwable.stackTraceText(): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { printStackTrace(it) }
        return buffer.toString()
    }

    private companion object {
        const val PADDING = 32
        const val IDENTITY_RULE_WIDTH = 34
        const val LOG_TAG = "ShuraAbiSelfTest"
    }
}
