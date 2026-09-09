package com.aitextassistant.bubble

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.hypot

/**
 * The resting circle: drag it anywhere, let go and it flies to the nearest edge,
 * drop it on the target at the bottom to put it away, leave it alone and it
 * fades back so it stops competing with whatever is underneath.
 *
 * All of that is hand-rolled because nothing in the framework does it. The
 * window position is two integers in [WindowManager.LayoutParams], so the spring
 * animates a float and writes those integers every frame.
 */
class ChatHead(
    private val context: Context,
    private val owner: OverlayOwner,
    private val onTap: () -> Unit,
    private val onDismissed: () -> Unit,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val size = dp(56)
    private val targetSize = dp(64)

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var edgeSpring: SpringAnimation? = null

    private var targetView: View? = null
    private var armed by mutableStateOf(false)

    /** Kept across expand and collapse so the bubble stays where it was left. */
    private var restingX = 0
    private var restingY = dp(180)

    private var downX = 0
    private var downY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    private val fade = Runnable {
        view?.animate()?.alpha(IDLE_ALPHA)?.setDuration(220)?.start()
    }

    fun show() {
        if (view != null) return

        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = restingX
            y = restingY
        }

        val bubble = ComposeView(context).apply { setContent { CollapsedBubble() } }
        attachOwners(bubble)
        bubble.setOnTouchListener { v, event -> onTouch(v, event) }

        runCatching { windowManager?.addView(bubble, layout) }
            .onSuccess {
                view = bubble
                params = layout
                scheduleFade()
            }
    }

    fun hide() {
        edgeSpring?.cancel()
        edgeSpring = null
        handler.removeCallbacks(fade)
        hideTarget()
        view?.let { v -> runCatching { windowManager?.removeView(v) } }
        view = null
        params = null
    }

    // ---- touch --------------------------------------------------------------

    private fun onTouch(v: View, event: MotionEvent): Boolean {
        val layout = params ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSpring?.cancel()
                handler.removeCallbacks(fade)
                v.animate().cancel()
                v.alpha = 1f

                downX = layout.x
                downY = layout.y
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
            }

            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY

                if (!dragging && hypot(dx, dy) > touchSlop) {
                    dragging = true
                    showTarget()
                }
                if (!dragging) return true

                layout.x = downX + dx.toInt()
                layout.y = (downY + dy.toInt()).coerceIn(minY(), maxY())

                val near = distanceToTarget(layout) < dp(88)
                if (near != armed) armed = near
                if (near) {
                    // Let the target take it, the way a magnet would.
                    layout.x = targetCentreX() - size / 2
                    layout.y = targetCentreY() - size / 2
                }
                applyLayout()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                val velocityX = velocity?.xVelocity ?: 0f
                velocity?.recycle()
                velocity = null

                val wasArmed = armed
                hideTarget()

                when {
                    !dragging -> {
                        onTap()
                        return true
                    }
                    wasArmed -> {
                        onDismissed()
                        return true
                    }
                    else -> {
                        snapToEdge(velocityX)
                        scheduleFade()
                    }
                }
            }

            else -> return false
        }
        return true
    }

    /**
     * Throw it and it should carry, not stop dead where your finger left it, so
     * the fling velocity biases which edge wins and seeds the spring.
     */
    private fun snapToEdge(velocityX: Float) {
        val layout = params ?: return
        val projected = layout.x + size / 2f + velocityX * FLING_PROJECTION
        val destination = if (projected > screenWidth() / 2f) (screenWidth() - size) else 0

        edgeSpring?.cancel()
        edgeSpring = SpringAnimation(FloatValueHolder(layout.x.toFloat())).apply {
            setSpring(
                SpringForce(destination.toFloat()).apply {
                    stiffness = SpringForce.STIFFNESS_LOW
                    dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                },
            )
            setStartVelocity(velocityX)
            addUpdateListener { _, value, _ ->
                layout.x = value.toInt()
                applyLayout()
            }
            addEndListener { _, _, _, _ ->
                restingX = layout.x
                restingY = layout.y
            }
        }
        edgeSpring?.start()
    }

    private fun scheduleFade() {
        handler.removeCallbacks(fade)
        handler.postDelayed(fade, IDLE_DELAY_MS)
    }

    // ---- the put-it-away target --------------------------------------------

    private fun showTarget() {
        if (targetView != null) return
        armed = false

        val marker = ComposeView(context).apply { setContent { DismissTarget(armed) } }
        attachOwners(marker)

        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never touchable: the finger dragging the bubble must keep it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(TARGET_BOTTOM_DP)
        }

        runCatching { windowManager?.addView(marker, layout) }
            .onSuccess { targetView = marker }
    }

    private fun hideTarget() {
        targetView?.let { v -> runCatching { windowManager?.removeView(v) } }
        targetView = null
        armed = false
    }

    private fun targetCentreX(): Int = screenWidth() / 2

    private fun targetCentreY(): Int = screenHeight() - dp(TARGET_BOTTOM_DP) - targetSize / 2

    private fun distanceToTarget(layout: WindowManager.LayoutParams): Float = hypot(
        (layout.x + size / 2f) - targetCentreX(),
        (layout.y + size / 2f) - targetCentreY(),
    )

    // ---- plumbing -----------------------------------------------------------

    private fun applyLayout() {
        val v = view ?: return
        val layout = params ?: return
        runCatching { windowManager?.updateViewLayout(v, layout) }
    }

    private fun attachOwners(target: View) {
        target.setViewTreeLifecycleOwner(owner)
        target.setViewTreeViewModelStoreOwner(owner)
        target.setViewTreeSavedStateRegistryOwner(owner)
    }

    private fun minY(): Int = dp(28)

    private fun maxY(): Int = screenHeight() - size - dp(56)

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.width()
                ?: context.resources.displayMetrics.widthPixels
        } else {
            context.resources.displayMetrics.widthPixels
        }

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.height()
                ?: context.resources.displayMetrics.heightPixels
        } else {
            context.resources.displayMetrics.heightPixels
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val IDLE_DELAY_MS = 2500L
        const val IDLE_ALPHA = 0.55f
        const val FLING_PROJECTION = 0.06f
        const val TARGET_BOTTOM_DP = 64
    }
}
