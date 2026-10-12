package com.simprel.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.SeekBar

/** Material 3 track and handle; SeekBar supplies touch, keyboard and accessibility semantics. */
class RoundingSlider(context: Context) : SeekBar(context) {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    var activeColor = Color.BLACK
    var trackColor = Color.LTGRAY
    private val density = resources.displayMetrics.density
    init {
        max = 100
        keyProgressIncrement = 1
        setPadding((12*density).toInt(), 0, (12*density).toInt(), 0)
        thumb = GradientDrawable().apply { setColor(Color.TRANSPARENT); setSize((4*density).toInt(), (44*density).toInt()) }
        thumbOffset = (2*density).toInt()
        background = null
    }
    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width-paddingRight).toFloat()
        val y = height/2f
        val x = left + (right-left)*progress/max.coerceAtLeast(1)
        if (isPressed || isFocused) {
            brush.color = (activeColor and 0xFFFFFF) or 0x1F000000
            canvas.drawCircle(x, y, 24*density, brush)
        }
        fun track(a: Float, b: Float, color: Int) {
            if (b <= a) return
            brush.color = color
            canvas.drawRoundRect(a,y-8*density,b,y+8*density,8*density,8*density,brush)
        }
        track(left, x-6*density, activeColor)
        track(x+6*density, right, trackColor)
        brush.color = activeColor
        val handle = if (isPressed) 3*density else 2*density
        canvas.drawRoundRect(x-handle,y-22*density,x+handle,y+22*density,2*density,2*density,brush)
    }
}
