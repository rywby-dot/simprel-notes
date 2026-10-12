package com.simprel.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.ScrollView

/** Scroll indicator in its own gutter; it never overlays cards or delete buttons. */
class ScrollRail(context: Context, color: Int, private val metrics: () -> Triple<Int, Int, Int>) : View(context) {
    constructor(context: Context, scroll: ScrollView, color: Int) : this(context, color, {
        Triple((scroll.getChildAt(0)?.height ?: 0) + scroll.paddingTop + scroll.paddingBottom, scroll.height, scroll.scrollY)
    })
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false }
    override fun onDraw(canvas: Canvas) {
        val (content, viewport, position) = metrics()
        val range = (content - viewport).coerceAtLeast(0)
        if (range == 0 || height <= 0) return
        val inset = 8 * resources.displayMetrics.density
        val track = (height - 2 * inset).coerceAtLeast(0f)
        val thumb = (track * viewport / content).coerceAtLeast(32 * resources.displayMetrics.density).coerceAtMost(track)
        val top = inset + (track - thumb) * position.coerceIn(0, range) / range
        val radius = 1.5f * resources.displayMetrics.density
        canvas.drawRoundRect(width/2f-radius, top, width/2f+radius, top+thumb, radius, radius, brush)
    }
}
