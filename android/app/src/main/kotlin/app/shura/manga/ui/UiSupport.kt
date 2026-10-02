package app.shura.manga.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The last errors this process hit, for the diagnostics screen.
 *
 * Deliberately in memory only: it exists to answer "what just went wrong" while the app is open,
 * not to survive a restart. It is never consulted to make a decision, only shown.
 */
internal object Diagnostics {
    private const val LIMIT = 20
    private val errors = ArrayDeque<String>()

    @Volatile
    var lastError: String? = null
        private set

    fun recordError(message: String) {
        lastError = message
        errors.addFirst("${System.currentTimeMillis()}: $message")
        while (errors.size > LIMIT) errors.removeLast()
    }

    fun recentErrors(): List<String> = errors.toList()

    fun clear() {
        errors.clear()
        lastError = null
    }
}

internal fun View.setVisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

internal fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/**
 * A screen that loads something and has to survive the load failing.
 *
 * An extension is arbitrary third party code in its own classloader; it can fail with an
 * `Exception`, an `Error` such as `NoClassDefFoundError`, or a `LinkageError`. The load path
 * therefore catches `Throwable`, records it for diagnostics, and leaves the screen usable with a
 * retry rather than letting the process die. Every network or extension call in the UI goes
 * through [runLoad] for that reason.
 */
abstract class AsyncScreenActivity : ComponentActivity() {

    protected val scope = CoroutineScope(Dispatchers.Default)
    protected lateinit var progress: ProgressBar
    protected lateinit var status: TextView

    /** Adds the shared spinner and status line to a vertical root, after the screen's own header. */
    protected fun addStatusViews(root: LinearLayout) {
        progress = ProgressBar(this).apply { visibility = View.GONE }
        status = TextView(this).apply {
            textSize = 11f
            setPadding(0, 4, 0, 8)
        }
        root.addView(progress)
        root.addView(status)
    }

    /**
     * Runs [block] off the main thread and hands the result to [onSuccess] on the main thread.
     *
     * When [retry] is given, a failure shows it as a tap target on the status line, so a network
     * blip or a source that is temporarily broken does not strand the user on an error.
     */
    protected fun <T> runLoad(
        loading: String = "Loading...",
        retry: (() -> Unit)? = null,
        block: suspend () -> T,
        onLoaded: (T) -> Unit,
    ) {
        progress.setVisible(true)
        status.setOnClickListener(null)
        status.text = loading
        scope.launch {
            val result = runCatching { block() }
            runOnUiThread {
                progress.setVisible(false)
                result.fold(
                    onSuccess = { value ->
                        status.text = ""
                        if (!isFinishing && !isDestroyed) onLoaded(value)
                    },
                    onFailure = { failure ->
                        val message = failure.describe()
                        Diagnostics.recordError(message)
                        status.text = if (retry == null) {
                            "Error: $message"
                        } else {
                            "Error: $message\nTap to retry."
                        }
                        if (retry != null) status.setOnClickListener { retry() }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
