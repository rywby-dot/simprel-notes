package com.simprel.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Material 3 radio geometry; the enclosing row supplies a 48dp touch target. */
class MaterialRadio(context: Context, private val checked: Boolean, private val accent: Int, private val outline: Int) : View(context) {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        brush.color = if (checked) accent else outline
        brush.style = Paint.Style.STROKE; brush.strokeWidth = 2*d
        canvas.drawCircle(width/2f, height/2f, 9*d, brush)
        if (checked) { brush.style = Paint.Style.FILL; canvas.drawCircle(width/2f, height/2f, 5*d, brush) }
    }
}
