package com.steamcontroller.android.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.steamcontroller.android.R
import kotlin.math.hypot

/**
 * Top-view illustration of the SC2026 controller with tap-to-remap hitspots over
 * each remappable button. Drawn on top of the official SC2026 silhouette.
 *
 * The hitspot positions are normalised against the source SVG viewBox (503.84×364.21)
 * so they scale with the view regardless of size. Positions are approximate and may
 * need small tweaks once viewed on-device.
 */
class InteractiveControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** A circular tap target over one button on the controller image. */
    data class Hitspot(
        val button: SteamButton,
        /** Normalised X position in [0,1] over the source SVG. */
        val nx: Float,
        /** Normalised Y position in [0,1] over the source SVG. */
        val ny: Float,
        /** Radius in dp. */
        val radiusDp: Float = 16f,
    )

    /**
     * Hitspot map approximated from the SVG paths.
     * Tweak coordinates here if a button feels off-target on real screens.
     */
    private val hitspots: List<Hitspot> = listOf(
        // Bumpers (top edge)
        Hitspot(SteamButton.LB, nx = 0.18f, ny = 0.10f),
        Hitspot(SteamButton.RB, nx = 0.82f, ny = 0.10f),
        // Face buttons (right cluster — diamond)
        Hitspot(SteamButton.Y, nx = 0.83f, ny = 0.22f),
        Hitspot(SteamButton.X, nx = 0.76f, ny = 0.30f),
        Hitspot(SteamButton.B, nx = 0.90f, ny = 0.30f),
        Hitspot(SteamButton.A, nx = 0.83f, ny = 0.38f),
        // Stick clicks
        Hitspot(SteamButton.LS, nx = 0.33f, ny = 0.45f, radiusDp = 22f),
        Hitspot(SteamButton.RS, nx = 0.67f, ny = 0.45f, radiusDp = 22f),
        // System cluster (center)
        Hitspot(SteamButton.STEAM, nx = 0.50f, ny = 0.25f),
        Hitspot(SteamButton.QUICK_ACCESS, nx = 0.50f, ny = 0.42f),
        Hitspot(SteamButton.VIEW, nx = 0.42f, ny = 0.55f),
        Hitspot(SteamButton.MENU, nx = 0.58f, ny = 0.55f),
        // Grips (sides)
        Hitspot(SteamButton.GRIP_LT, nx = 0.08f, ny = 0.62f),
        Hitspot(SteamButton.GRIP_RT, nx = 0.92f, ny = 0.62f),
        // Back paddles (bottom corners)
        Hitspot(SteamButton.L4, nx = 0.20f, ny = 0.82f),
        Hitspot(SteamButton.L5, nx = 0.30f, ny = 0.85f),
        Hitspot(SteamButton.R4, nx = 0.80f, ny = 0.82f),
        Hitspot(SteamButton.R5, nx = 0.70f, ny = 0.85f),
    )

    private val controllerDrawable = ContextCompat.getDrawable(context, R.drawable.sc2026_controller_black)!!.also {
        DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(),
            ContextCompat.getColor(context, android.R.color.holo_blue_light))
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(0x60, 0xFF, 0xFF, 0xFF)
    }
    private val highlightRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = ContextCompat.getColor(context, android.R.color.holo_blue_light)
    }

    private var highlightedButton: SteamButton? = null
    private var onButtonTapped: ((SteamButton) -> Unit)? = null

    /** Match the SVG's aspect ratio so hitspot normalisation stays accurate. */
    private val sourceAspect: Float = 503.84f / 364.21f

    init {
        // No background — the drawable is the visual.
        isClickable = true
        isFocusable = true
    }

    fun setOnButtonTappedListener(listener: (SteamButton) -> Unit) {
        onButtonTapped = listener
    }

    /** Optional: highlight a row's button to indicate it on the diagram. */
    fun highlight(button: SteamButton?) {
        if (highlightedButton == button) return
        highlightedButton = button
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Lock the height to match the SVG aspect ratio against measured width.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w / sourceAspect).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        controllerDrawable.setBounds(0, 0, width, height)
        controllerDrawable.draw(canvas)

        for (hs in hitspots) {
            val cx = hs.nx * width
            val cy = hs.ny * height
            val r = dp(hs.radiusDp)
            val paint = if (hs.button == highlightedButton) highlightRingPaint else ringPaint
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val tx = event.x
        val ty = event.y
        // Find the closest hitspot within its own radius.
        var best: Hitspot? = null
        var bestDist = Float.MAX_VALUE
        for (hs in hitspots) {
            val cx = hs.nx * width
            val cy = hs.ny * height
            val d = hypot(cx - tx, cy - ty)
            if (d < dp(hs.radiusDp) && d < bestDist) {
                best = hs
                bestDist = d
            }
        }
        if (best != null) {
            performClick()
            onButtonTapped?.invoke(best.button)
            return true
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
