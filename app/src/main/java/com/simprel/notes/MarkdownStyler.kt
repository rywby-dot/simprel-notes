package com.simprel.notes

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.Spanned
import android.text.style.ReplacementSpan
import io.noties.markwon.Markwon

/** The editor and previews share CommonMark parsing and the exact same rendering spans. */
class MarkdownStyler(private val markdown: Markwon) {
    private val applied = mutableListOf<Any>()
    fun apply(text: Editable) {
        applied.forEach { text.removeSpan(it) }; applied.clear()
        val source = text.toString()
        val preservedWhitespace = editorWhitespace(source)
        val rendered = markdown.toMarkdown(source)
        val runs = alignMarkdown(source, rendered.toString()) ?: return
        val starts = IntArray(rendered.length + 1) { -1 }
        val ends = IntArray(rendered.length + 1) { -1 }
        fun span(value: Any, start: Int, end: Int) {
            if (start >= end) return
            text.setSpan(value, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            applied.add(value)
        }
        for (run in runs) {
            if (run.equal) {
                for (i in run.renderedStart..run.renderedEnd) {
                    val position = run.sourceStart + i - run.renderedStart
                    if (starts[i] < 0) starts[i] = position
                    ends[i] = position
                }
            } else {
                // Preserve paragraph boundaries and trailing spaces so every space advances the cursor.
                var from = run.sourceStart
                for (i in run.sourceStart..run.sourceEnd) {
                    if (i == run.sourceEnd || preservedWhitespace[i]) {
                        span(SyntaxSpan(if (from == run.sourceStart) rendered.subSequence(run.renderedStart, run.renderedEnd).toString().replace("\n", "") else ""), from, i)
                        from = i + 1
                    }
                }
                for (i in run.renderedStart..run.renderedEnd) {
                    if (starts[i] < 0) starts[i] = run.sourceStart
                    ends[i] = run.sourceEnd
                }
            }
        }
        for (value in rendered.getSpans(0, rendered.length, Any::class.java)) {
            val start = rendered.getSpanStart(value)
            val end = rendered.getSpanEnd(value)
            if (start >= 0 && end <= rendered.length && starts[start] >= 0 && ends[end] >= 0) {
                span(value, starts[start], ends[end])
            }
        }
    }
    private class SyntaxSpan(private val visible: String) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = kotlin.math.ceil(paint.measureText(visible)).toInt()
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            canvas.drawText(visible, x, y.toFloat(), paint)
        }
    }
}
