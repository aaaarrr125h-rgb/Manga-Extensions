package app.shura.manga

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * The application's entry point.
 *
 * It exists so the package has a real MAIN/LAUNCHER target: without one the APK installs but
 * there is nothing to start, and the installer disables "Open". It shows a placeholder rather than
 * a real library UI, which is the next piece of work.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.welcome_message)
                textSize = 20f
                setPadding(PADDING, PADDING, PADDING, PADDING)
            },
        )
    }

    private companion object {
        const val PADDING = 48
    }
}
