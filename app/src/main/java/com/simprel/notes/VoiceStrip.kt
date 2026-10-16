package com.simprel.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.io.File
import kotlin.math.*

/** A single non-editable attachment row, shared by editor and note cards. */
class VoiceStrip(context: Context) : View(context) {
    private var peaks = emptyList<Float>()
    private var file = ""
    var ink = 0
    fun bind(note: Note, source: File, color: Int) {
        ink = color
        if (file == note.audioFile && peaks.isNotEmpty()) { invalidate(); return }
        file = note.audioFile; peaks = note.audioPeaks; invalidate()
        if (peaks.isEmpty()) {
            val requested = file
            Thread({
                val loaded = try { VoiceAudio.readEnvelope(source) } catch (_: Exception) { emptyList() }
                post { if (file == requested) { peaks = loaded; invalidate() } }
            }, "voice-envelope").start()
        }
    }
    override fun onDraw(canvas: Canvas) {
        drawStrip(canvas, RectF(0f, 0f, width.toFloat(), height.toFloat()), peaks, ink, resources.displayMetrics.density)
    }
    companion object {
        fun drawStrip(canvas: Canvas, bounds: RectF, peaks: List<Float>, color: Int, density: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND }
            val size = minOf(24 * density, bounds.height() - 4 * density)
            val x = bounds.left + size / 2 + 4 * density; val y = bounds.centerY()
            canvas.save(); canvas.translate(x - size / 2, y - size / 2); canvas.scale(size / 24, size / 24)
            paint.strokeWidth = 1.9f
            canvas.drawRoundRect(9f, 2f, 15f, 15f, 3f, 3f, paint)
            canvas.drawArc(5f, 7f, 19f, 19f, 0f, 180f, false, paint)
            canvas.drawLine(5f, 10f, 5f, 12f, paint); canvas.drawLine(19f, 10f, 19f, 12f, paint)
            canvas.drawLine(12f, 19f, 12f, 22f, paint); canvas.drawLine(8f, 22f, 16f, 22f, paint)
            canvas.restore()
            paint.style = Paint.Style.FILL
            val left = bounds.left + size + 12 * density
            val available = (bounds.right - left - 4 * density).coerceAtLeast(0f)
            val count = (available / (5 * density)).toInt().coerceIn(1, 48)
            val values = VoiceAudio.envelope(peaks, count)
            val maximum = maxOf(.01f, values.maxOrNull() ?: 0f)
            val step = available / count
            val radius = minOf(1.7f * density, step * .32f)
            for (i in 0 until count) {
                val half = maxOf(radius, sqrt(values[i] / maximum) * minOf(12 * density, bounds.height() * .35f))
                val center = left + (i + .5f) * step
                canvas.drawRoundRect(center - radius, y - half, center + radius, y + half, radius, radius, paint)
            }
        }
    }
}
