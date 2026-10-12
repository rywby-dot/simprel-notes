package com.simprel.notes

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Float-width strokes keep half-dp borders distinct even on mdpi screens. */
class NoteSurfaceDrawable(private val fill: Int, private val radius: Float, private val stroke: Float, private val outline: Int) : Drawable() {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        val inset = stroke.coerceAtLeast(0f) / 2f
        area.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        val corner = (radius - inset).coerceAtLeast(0f)
        brush.style = Paint.Style.FILL; brush.color = fill; brush.alpha = opacity
        canvas.drawRoundRect(area, corner, corner, brush)
        if (stroke > 0f) {
            brush.style = Paint.Style.STROKE; brush.color = outline; brush.alpha = opacity; brush.strokeWidth = stroke
            canvas.drawRoundRect(area, corner, corner, brush)
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { brush.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
