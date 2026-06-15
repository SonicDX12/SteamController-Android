package com.steamcontroller.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

// Square 2D view that draws:
//  - the outer stick range (circle)
//  - the deadzone radius (faded circle)
//  - the current calibrated position (dot)
class StickPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // Values in normalized [-1, 1] range
    var posX: Float = 0f
    var posY: Float = 0f
    var deadzoneFraction: Float = 0.08f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#2A475E")
    }
    private val deadzonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33000000")
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#33FFFFFF")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66C0F4")
    }

    fun setPosition(x: Float, y: Float) {
        posX = x.coerceIn(-1f, 1f)
        posY = y.coerceIn(-1f, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = min(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (min(width, height) / 2f) - 6f

        // Outer ring
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Crosshair
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crosshairPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crosshairPaint)

        // Deadzone
        if (deadzoneFraction > 0f) {
            canvas.drawCircle(cx, cy, radius * deadzoneFraction, deadzonePaint)
        }

        // Position dot
        val dotX = cx + posX * radius
        val dotY = cy + posY * radius
        canvas.drawCircle(dotX, dotY, 10f, dotPaint)
    }
}
