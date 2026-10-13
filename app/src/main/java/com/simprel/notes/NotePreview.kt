package com.simprel.notes

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextUtils
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.sqrt

/** Per-line insets wrap text around just the badge and the rounded outline. */
class NotePreview(context: Context) : TextView(context) {
    var cornerRadius = 0f
    var badgeLeft = 0f
    var badgeBottom = 0f
    var badgeVisible = false
    var reminderVisible = false
    var reminderLeft = 0f
    var reminderTop = 0f
    var compact = false
    private var cached: StaticLayout? = null
    fun geometryChanged() { cached = null; invalidate() }
    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        cached = null
    }
    override fun onDraw(canvas: Canvas) {
        val available = width - paddingLeft - paddingRight
        if (available <= 0 || height <= paddingTop + paddingBottom) return
        paint.color = currentTextColor
        val layout = cached ?: run {
            val lines = maxLines.coerceIn(1, 1000)
            val left = IntArray(lines)
            val right = IntArray(lines)
            fun curveInset(y: Float): Float {
                val distance = when {
                    y < cornerRadius -> cornerRadius - y
                    y > height - cornerRadius -> y - (height - cornerRadius)
                    else -> 0f
                }.coerceIn(0f, cornerRadius)
                return cornerRadius - sqrt((cornerRadius * cornerRadius - distance * distance).coerceAtLeast(0f))
            }
            for (line in 0 until lines) {
                val top = paddingTop + line * lineHeight
                val inset = if (compact) 0f else curveInset(top + lineHeight / 2f)
                left[line] = ceil((inset - paddingLeft).coerceAtLeast(0f)).toInt()
                right[line] = ceil((inset - paddingRight).coerceAtLeast(0f)).toInt()
                if (badgeVisible && top < badgeBottom && top + lineHeight > 0) {
                    right[line] = maxOf(right[line], ceil((width - paddingRight - badgeLeft).coerceAtLeast(0f)).toInt())
                }
                if (reminderVisible && !compact && top + lineHeight > reminderTop) {
                    right[line] = maxOf(right[line], ceil((width - paddingRight - reminderLeft).coerceAtLeast(0f)).toInt())
                }
                // Keep at least one glyph's width even on very small cards.
                val limit = (available - paint.textSize).toInt().coerceAtLeast(0)
                left[line] = left[line].coerceAtMost(limit / 2)
                right[line] = right[line].coerceAtMost(limit - left[line])
            }
            fun build(count: Int): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, available)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(includeFontPadding)
                .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
                .setMaxLines(count).setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(available)
                .setIndents(left, right).build()
            var result = build(lines)
            val availableHeight = height - paddingTop - paddingBottom
            if (!compact && result.height > availableHeight) {
                val fits = (0 until result.lineCount).count { result.getLineBottom(it) <= availableHeight }.coerceAtLeast(1)
                result = build(fits)
            }
            result.also { cached = it }
        }
        val save = canvas.save()
        val top = if (compact) (height - layout.height) / 2f else paddingTop.toFloat()
        canvas.translate(paddingLeft.toFloat(), top)
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }
}
