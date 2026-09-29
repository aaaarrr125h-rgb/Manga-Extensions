package app.shura.manga

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
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
     */
    private fun startSelfTest() {
        output.text = "running self test..."
        worker.execute {
            val report = runCatching { AbiRuntimeSelfTest(this).run() }
                .getOrElse { "FAIL  self test crashed\n${it.javaClass.simpleName}: ${it.message}" }
            Log.i(LOG_TAG, "\n$report")
            runOnUiThread { output.text = report }
        }
    }

    private companion object {
        const val PADDING = 32
        const val LOG_TAG = "ShuraAbiSelfTest"
    }
}
