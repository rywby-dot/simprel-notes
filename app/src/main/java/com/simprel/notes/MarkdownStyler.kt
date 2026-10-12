package com.simprel.notes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.style.*

/** Visual formatting changes spans only; the stored Markdown and selection stay intact. */
class MarkdownStyler(private val accent: Int) {
    private val applied = mutableListOf<Any>()
    fun apply(text: Editable) {
        applied.forEach { text.removeSpan(it) }; applied.clear()
        val source = text.toString()
        fun span(value: Any, start: Int, end: Int) {
            if (start >= end) return
            text.setSpan(value, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); applied.add(value)
        }
        fun hide(start: Int, end: Int) = span(HiddenSyntax(), start, end)
        val codeRanges = mutableListOf<IntRange>()
        Regex("(```[\\s\\S]*?```|`[^`\\n]+`)").findAll(source).forEach {
            val fence = if (it.value.startsWith("```")) 3 else 1
            hide(it.range.first, it.range.first + fence); hide(it.range.last + 1 - fence, it.range.last + 1)
            span(TypefaceSpan("monospace"), it.range.first + fence, it.range.last + 1 - fence)
            span(ForegroundColorSpan(accent), it.range.first + fence, it.range.last + 1 - fence)
            codeRanges.add(it.range)
        }
        Regex("(?m)^(#{1,6}) (.+)$").findAll(source).forEach {
            if (codeRanges.any { range -> it.range.first in range }) return@forEach
            hide(it.range.first, it.groups[2]!!.range.first)
            span(StyleSpan(Typeface.BOLD), it.groups[2]!!.range.first, it.range.last + 1)
            span(RelativeSizeSpan(1.5f - it.groupValues[1].length * .06f), it.groups[2]!!.range.first, it.range.last + 1)
        }
        Regex("(?m)^([ \t]*)([-+*]) ").findAll(source).forEach {
            if (codeRanges.any { range -> it.range.first in range }) return@forEach
            val start = it.groups[2]!!.range.first
            span(BulletSyntax(), start, it.range.last + 1)
        }
        Regex("(?m)^> ?(.+)$").findAll(source).forEach {
            if (codeRanges.any { range -> it.range.first in range }) return@forEach
            hide(it.range.first, it.groups[1]!!.range.first)
            span(QuoteSpan(accent), it.range.first, it.range.last + 1)
        }
        fun markup(pattern: String, marker: Int, style: () -> Any) {
            Regex(pattern).findAll(source).forEach {
                if (codeRanges.any { range -> it.range.first in range }) return@forEach
                hide(it.range.first, it.range.first + marker); hide(it.range.last + 1 - marker, it.range.last + 1)
                span(style(), it.range.first + marker, it.range.last + 1 - marker)
            }
        }
        markup("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*", 2) { StyleSpan(Typeface.BOLD) }
        markup("__(?=\\S)(.+?)(?<=\\S)__", 2) { StyleSpan(Typeface.BOLD) }
        markup("(?<!\\*)\\*(?!\\*)(?=\\S)(.+?)(?<=\\S)\\*(?!\\*)", 1) { StyleSpan(Typeface.ITALIC) }
        markup("(?<!_)_(?!_)(?=\\S)(.+?)(?<=\\S)_(?!_)", 1) { StyleSpan(Typeface.ITALIC) }
        markup("~~(?=\\S)(.+?)(?<=\\S)~~", 2) { StrikethroughSpan() }
        Regex("\\[([^]\\n]+)]\\(([^)\\n]+)\\)").findAll(source).forEach {
            if (codeRanges.any { range -> it.range.first in range }) return@forEach
            val label = it.groups[1]!!.range
            hide(it.range.first, label.first); hide(label.last + 1, it.range.last + 1)
            span(ForegroundColorSpan(accent), label.first, label.last + 1)
            span(UnderlineSpan(), label.first, label.last + 1)
        }
    }
    private class BulletSyntax : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = paint.measureText("• ").toInt()
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            canvas.drawText("• ", x, y.toFloat(), paint)
        }
    }
    private class HiddenSyntax : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = 0
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }
}
