package com.aitextassistant.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aitextassistant.RemixUiState
import com.aitextassistant.RemixViewModel
import com.aitextassistant.generate.Generators
import kotlin.math.min

/**
 * Ties the resting chat head to the panel it opens into.
 *
 * Two windows rather than one that resizes. The head stays non-focusable so it
 * never steals typing from the app underneath; the panel has to take focus, both
 * to receive the back key and because the clipboard is only readable by the app
 * that holds focus.
 */
class BubbleWindow(
    private val context: Context,
    private val owner: OverlayOwner,
    private val textSource: TextSource,
    private val onStopRequested: () -> Unit,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val head = ChatHead(
        context = context,
        owner = owner,
        // Posted, not called. The tap arrives inside the collapsed window's own
        // touch dispatch, and tearing that window down mid-dispatch leaves the
        // WindowManager in a state where adding the panel quietly fails. The
        // symptom is a bubble that does nothing at all when tapped.
        onTap = { handler.post { expand() } },
        onDismissed = { onStopRequested() },
    )

    private val viewModel: RemixViewModel =
        ViewModelProvider(owner, RemixViewModel.factory(Generators.default(context)))
            .get(RemixViewModel::class.java)

    private var panel: View? = null

    /** Set on open, cleared once the clipboard has been read for this opening. */
    private var readPending = false

    fun show() {
        if (panel == null) head.show()
    }

    fun hide() {
        head.hide()
        removePanel()
    }

    // ---- the opened panel ---------------------------------------------------

    private fun expand() {
        if (panel != null) return
        head.hide()
        readPending = true

        val root = FocusAwareLayout(context).apply {
            onFocusGained = {
                if (readPending) {
                    readPending = false
                    loadFromClipboard()
                }
            }
            onBack = { collapseLater() }
            // The tap that opened the panel can still be in flight when it
            // appears, and would close it again immediately. Ignore outside
            // touches until the finger that opened it has certainly lifted.
            val openedAt = System.currentTimeMillis()
            onTouchAbove = {
                if (System.currentTimeMillis() - openedAt > 300) collapseLater()
            }
        }

        val compose = ComposeView(context).apply {
            setContent {
                ExpandedPanel(
                    vm = viewModel,
                    onCollapse = { collapseLater() },
                    onCopy = ::copyToClipboard,
                    onStop = onStopRequested,
                )
            }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        attachOwners(root)

        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelHeightPx(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        runCatching { windowManager?.addView(root, layout) }
            .onSuccess { panel = root }
            .onFailure { head.show() }
    }

    private fun removePanel() {
        panel?.let { view -> runCatching { windowManager?.removeView(view) } }
        panel = null
    }

    /**
     * Tearing a window down from inside its own touch or key dispatch crashes, so
     * every collapse goes through the next main-loop pass.
     */
    private fun collapseLater() {
        handler.post {
            if (panel == null) return@post
            removePanel()
            head.show()
        }
    }

    // ---- text in and out ----------------------------------------------------

    private fun loadFromClipboard() {
        val text = textSource.read()
        when {
            text.isNullOrBlank() ->
                // Only fall back to the paste field if there is nothing already
                // on screen worth keeping.
                if (viewModel.state !is RemixUiState.Ready) viewModel.reset()
            else -> viewModel.load(text)
        }
    }

    private fun copyToClipboard(text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Remix", text))
        Toast.makeText(context, "Copied. Paste it where you were.", Toast.LENGTH_SHORT).show()
    }

    // ---- plumbing -----------------------------------------------------------

    private fun attachOwners(view: View) {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
    }

    private fun panelHeightPx(): Int {
        val screen = context.resources.displayMetrics.heightPixels
        return min(dp(620), screen - dp(110)).coerceAtLeast(dp(360))
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/**
 * The panel needs three things a plain FrameLayout will not report: when its
 * window actually gains focus (the clipboard read has to wait for that, it is not
 * ready the moment addView returns), the back key, and a tap on the app showing
 * above it.
 */
private class FocusAwareLayout(context: Context) : FrameLayout(context) {

    var onFocusGained: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onTouchAbove: (() -> Unit)? = null

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) onFocusGained?.invoke()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            // Negative y means above this window, so it is the app behind. Taps
            // below are the keyboard, which must not close the panel it serves.
            if (event.y < 0) onTouchAbove?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }
}
