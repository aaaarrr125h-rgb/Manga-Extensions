package app.shura.manga

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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
 *
 * Colours are set explicitly rather than inherited. [android.R.style.Theme_Material_Light] is the
 * parent theme in `themes.xml`, and its default text colour is near-black, which is invisible on
 * the near-black background this screen draws. Every colour used here is therefore spelled out
 * against [BACKGROUND] and never left to the system.
 */
class MainActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT_BODY)
            setPadding(PADDING, PADDING, PADDING, PADDING)
            gravity = Gravity.START
        }
        setContentView(
            ScrollView(this).apply {
                addView(output)
                setBackgroundColor(BACKGROUND)
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
        output.text = colorise("running self test...")
        worker.execute {
            val abiReport = runCatching { AbiRuntimeSelfTest(this).run() }
                .fold(
                    onSuccess = { it },
                    onFailure = { "FAIL  self test crashed\n${it.stackTraceText()}" },
                )
            val screen = buildString {
                appendLine(buildIdentity())
                appendLine()
                append(abiReport)
            }
            Log.i(LOG_TAG, "\n$screen")
            runOnUiThread { output.text = colorise(screen) }

            // The extension test goes second and publishes itself when it lands, because it can
            // take noticeably longer: it loads two classloaders and makes real catalogue calls.
            // The ABI report above stays on screen while it runs rather than being replaced by
            // "running", so a slow extension test never looks like a lost first one.
            val extensionReport = runCatching { ExtensionRuntimeSelfTest(this).run() }
                .fold(
                    onSuccess = { it },
                    onFailure = { "FAIL  extension test crashed\n${it.stackTraceText()}" },
                )
            val finished = buildString {
                append(screen)
                appendLine()
                append(extensionReport)
            }
            Log.i(LOG_TAG, "\n$finished")
            runOnUiThread { output.text = colorise(finished) }
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

    /**
     * Paints the report so each line can be read at a glance.
     *
     * The self test's own text is left exactly as it produces it; only the presentation is added
     * here, in the activity that shows it. The headline is enlarged, the identity values and every
     * verdict are bold, and pass/fail are separated by colour so a failure cannot be missed in a
     * long report.
     */
    private fun colorise(text: String): CharSequence {
        val spanned = SpannableStringBuilder(text)
        var start = 0
        for (line in text.split('\n')) {
            val end = start + line.length
            if (line.isNotEmpty()) {
                styleLine(spanned, start, end, line)
            }
            start = end + 1
        }
        return spanned
    }

    private fun styleLine(text: SpannableStringBuilder, start: Int, end: Int, line: String) {
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length

        // A run of dashes is decoration, not information, so it recedes.
        if (trimmed.isNotEmpty() && trimmed.all { it == '-' }) {
            paint(text, start, end, TEXT_RULE)
            return
        }

        when {
            trimmed.startsWith(HEADLINE) -> {
                paint(text, start, end, TEXT_HEADLINE)
                bold(text, start, end)
                text.setSpan(
                    RelativeSizeSpan(HEADLINE_SCALE),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }

            // "PASS  <label>" and "FAIL  <label>": only the verdict word is coloured, so the label
            // that explains it stays in the readable body colour. Both words are VERDICT_WIDTH long.
            trimmed.startsWith(PASS) || trimmed.startsWith(FAIL) -> {
                val verdictEnd = start + indent + VERDICT_WIDTH
                paint(text, start, verdictEnd, if (trimmed.startsWith(FAIL)) TEXT_FAIL else TEXT_PASS)
                bold(text, start, verdictEnd)
                paint(text, verdictEnd, end, TEXT_BODY)
            }

            // The "7/7 passed" tally decides at a glance whether the run succeeded.
            PASSED_TALLY.matches(trimmed) -> {
                paint(text, start, end, if (tallyComplete(trimmed)) TEXT_PASS else TEXT_FAIL)
                bold(text, start, end)
            }

            // "BUILD: <value>": the label is a caption, the value is the point of the line. Only
            // the three identity lines are split this way, so a stack trace that happens to
            // contain ": " keeps its body colour instead of being cut in half.
            else -> {
                val valueStart = identityValueStart(line)
                if (valueStart < 0) {
                    paint(text, start, end, TEXT_BODY)
                } else {
                    paint(text, start, start + valueStart, TEXT_LABEL)
                    paint(text, start + valueStart, end, TEXT_VALUE)
                    bold(text, start + valueStart, end)
                }
            }
        }
    }

    /** Index within [line] where an identity line's value starts, or -1 for anything else. */
    private fun identityValueStart(line: String): Int =
        IDENTITY_LABELS.firstOrNull { line.startsWith(it + IDENTITY_SEPARATOR) }
            ?.let { it.length + IDENTITY_SEPARATOR.length }
            ?: -1

    /** True when the report's tally is total, i.e. nothing failed. */
    private fun tallyComplete(tally: String): Boolean {
        val counts = tally.substringBefore(' ').split('/')
        return counts.size == 2 && counts[0] == counts[1]
    }

    private fun paint(text: SpannableStringBuilder, start: Int, end: Int, colour: Int) {
        if (end > start) {
            text.setSpan(ForegroundColorSpan(colour), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun bold(text: SpannableStringBuilder, start: Int, end: Int) {
        if (end > start) {
            text.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun Throwable.stackTraceText(): String {
        val buffer = StringWriter()
        PrintWriter(buffer).use { printStackTrace(it) }
        return buffer.toString()
    }

    private companion object {
        const val PADDING = 32
        const val IDENTITY_RULE_WIDTH = 34
        const val HEADLINE = "SHURA SELF TEST"
        const val PASS = "PASS"
        const val FAIL = "FAIL"
        const val VERDICT_WIDTH = 4
        const val IDENTITY_SEPARATOR = ": "
        val IDENTITY_LABELS = listOf("APK BUILD TIME", "VERSION", "BUILD")
        const val HEADLINE_SCALE = 1.25f
        val PASSED_TALLY = Regex("""\d+/\d+ passed""")

        // Every colour below is checked against BACKGROUND for WCAG contrast. The theme is a Light
        // theme, so nothing here may be left to the default.
        const val BACKGROUND = 0xFF101010.toInt()
        const val TEXT_BODY = 0xFFE6E6E6.toInt() // 15.25:1
        const val TEXT_VALUE = 0xFFFFFFFF.toInt() // 19.03:1
        const val TEXT_HEADLINE = 0xFF7FD4FF.toInt() // 11.57:1
        const val TEXT_PASS = 0xFF7BE07F.toInt() // 11.61:1
        const val TEXT_FAIL = 0xFFFF7070.toInt() // 7.07:1
        const val TEXT_LABEL = 0xFFB0BEC5.toInt() // 9.98:1
        const val TEXT_RULE = 0xFF6E6E6E.toInt() // 3.73:1, decoration only
        const val LOG_TAG = "ShuraAbiSelfTest"
    }
}
