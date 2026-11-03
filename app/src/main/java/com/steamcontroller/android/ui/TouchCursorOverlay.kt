package com.steamcontroller.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Draws a floating, non-touchable cursor reticle over all apps using WindowManager
 * (`TYPE_APPLICATION_OVERLAY`) when Hold for Cursor Mode is in Touch Simulation mode.
 *
 * Appears immediately when the hold button is pressed, moves smoothly with the joystick,
 * highlights when clicking/dragging, and disappears immediately on release.
 */
class TouchCursorOverlay(private val context: Context) {

    private val TAG = "TouchCursorOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val sizePx = (28 * context.resources.displayMetrics.density).toInt().coerceAtLeast(32)
    private val halfPx = sizePx / 2

    private var cursorView: CursorReticleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var currentVisible = false
    private var currentClicking = false

    @Volatile private var pendingVisible = false
    @Volatile private var pendingX = 0f
    @Volatile private var pendingY = 0f
    @Volatile private var pendingClicking = false
    @Volatile private var updateScheduled = false

    private val applyUpdateRunnable = Runnable {
        updateScheduled = false
        val visible = pendingVisible
        val x = pendingX
        val y = pendingY
        val clicking = pendingClicking

        if (!visible) {
            if (attached && currentVisible) {
                cursorView?.visibility = View.GONE
                currentVisible = false
            }
            return@Runnable
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return@Runnable
        }

        if (!attached) {
            try {
                val view = CursorReticleView(context)
                val lp = WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x.toInt() - halfPx
                    this.y = y.toInt() - halfPx
                }
                windowManager.addView(view, lp)
                cursorView = view
                params = lp
                attached = true
                currentVisible = true
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to attach overlay: ${t.message}")
                return@Runnable
            }
        }

        val view = cursorView ?: return@Runnable
        val lp = params ?: return@Runnable
        if (!currentVisible) {
            view.visibility = View.VISIBLE
            currentVisible = true
        }
        view.clicking = clicking
        val targetX = x.toInt() - halfPx
        val targetY = y.toInt() - halfPx
        if (lp.x != targetX || lp.y != targetY) {
            lp.x = targetX
            lp.y = targetY
            try {
                windowManager.updateViewLayout(view, lp)
            } catch (_: Throwable) {}
        }
    }

    private class CursorReticleView(context: Context) : View(context) {
        var clicking: Boolean = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(140, 26, 159, 255) // Steam blue translucent fill
        }
        private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(210, 60, 220, 130) // Greenish highlight when pressing
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * context.resources.displayMetrics.density
            color = Color.WHITE
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.0f * context.resources.displayMetrics.density
            color = Color.argb(160, 0, 0, 0)
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width.coerceAtMost(height) / 2f) * (if (clicking) 0.72f else 0.82f)
            canvas.drawCircle(cx, cy, radius, if (clicking) activeFillPaint else fillPaint)
            canvas.drawCircle(cx, cy, radius, shadowPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)
            canvas.drawCircle(cx, cy, 2.5f * resources.displayMetrics.density, dotPaint)
        }
    }

    fun update(visible: Boolean, x: Float, y: Float, clicking: Boolean) {
        pendingVisible = visible
        pendingX = x
        pendingY = y
        pendingClicking = clicking
        if (!updateScheduled) {
            updateScheduled = true
            mainHandler.post(applyUpdateRunnable)
        }
    }

    fun destroy() {
        mainHandler.post {
            if (attached) {
                try {
                    cursorView?.let { windowManager.removeView(it) }
                } catch (_: Throwable) {}
                cursorView = null
                params = null
                attached = false
                currentVisible = false
            }
        }
    }
}
