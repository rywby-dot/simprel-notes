package com.simprel.notes

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Live frequency bars morph into the complete amplitude envelope when recording pauses. */
class VoiceWaveform(context: Context, private val ink: Int, private val muted: Int) : View(context) {
    var start = 0f
    var end = 1f
    var changed: (() -> Unit)? = null
    var seeked: ((Float) -> Unit)? = null
    var playhead: Float? = null
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val live = FloatArray(48)
    private var timeline = FloatArray(48)
    private var blend = 0f
    private var sensitivityPeak = .08f
    private var running = true
    private var animation: ValueAnimator? = null
    private var edge = 0
    private val density = resources.displayMetrics.density
    fun update(spectrum: FloatArray, peaks: List<Float>, recording: Boolean) {
        sensitivityPeak = maxOf(.08f, sensitivityPeak * .97f, spectrum.maxOrNull() ?: 0f)
        for (i in live.indices) {
            val target = sqrt(spectrum[minOf(spectrum.lastIndex, i / 2)] / sensitivityPeak).coerceIn(0f, 1f)
            live[i] += (target - live[i]) * if (target > live[i]) .7f else .2f
        }
        if (recording != running) {
            running = recording
            if (!recording) {
                val maximum = maxOf(.01f, peaks.maxOrNull() ?: 0f)
                timeline = VoiceAudio.envelope(peaks).map { sqrt(it / maximum) }.toFloatArray()
            }
            animation?.cancel()
            animation = ValueAnimator.ofFloat(blend, if (recording) 0f else 1f).apply {
                duration = 280; addUpdateListener { blend = it.animatedValue as Float; invalidate() }; start()
            }
        }
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val inset = 14 * density
        val available = (width - inset * 2).coerceAtLeast(1f)
        val middle = height / 2f
        val step = available / live.size
        val radius = minOf(3 * density, step * .32f)
        for (i in live.indices) {
            val fraction = (i + .5f) / live.size
            val level = live[i] * (1 - blend) + timeline[i] * blend
            val half = maxOf(radius, level * (height / 2 - 20 * density))
            paint.color = if (blend > .5f && (fraction < start || fraction > end)) muted else ink
            val x = inset + fraction * available
            canvas.drawRoundRect(x - radius, middle - half, x + radius, middle + half, radius, radius, paint)
        }
        if (blend > 0f) {
            paint.color = ink; paint.alpha = (255 * blend).toInt()
            paint.strokeWidth = 2 * density
            for (fraction in listOf(start, end)) {
                val x = inset + fraction * available
                canvas.drawRoundRect(x - 5 * density, 8 * density, x + 5 * density, height - 8 * density, 5 * density, 5 * density, paint)
            }
            paint.alpha = 255
            playhead?.let { position ->
                val x = inset + position * available
                paint.strokeWidth = 2 * density
                canvas.drawLine(x, 4 * density, x, height - 4 * density, paint)
                canvas.drawCircle(x, height / 2f, 4 * density, paint)
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (running || !isEnabled) return false
        val inset = 14 * density
        val fraction = ((event.x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = 24 * density / (width - 2 * inset).coerceAtLeast(1f)
                edge = when {
                    minOf(abs(fraction - start), abs(fraction - end)) > distance -> 3
                    abs(fraction - start) <= abs(fraction - end) -> 1
                    else -> 2
                }
                if (edge == 3) { playhead = fraction.coerceIn(start, end); seeked?.invoke(playhead!!) }
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (edge == 1) start = fraction.coerceAtMost(end - .01f).coerceAtLeast(0f)
                if (edge == 2) end = fraction.coerceAtLeast(start + .01f).coerceAtMost(1f)
                if (edge == 3) { playhead = fraction.coerceIn(start, end); seeked?.invoke(playhead!!) }
                else changed?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { edge = 0; parent.requestDisallowInterceptTouchEvent(false); performClick() }
        }
        return true
    }
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (running) return
        info.className = "android.widget.SeekBar"
        info.rangeInfo = android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(1, 0f, 1f, playhead ?: start)
        info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        for ((id, label) in listOf(101 to R.string.voice_start_earlier, 102 to R.string.voice_start_later, 103 to R.string.voice_end_earlier, 104 to R.string.voice_end_later)) {
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(id, context.getString(label)))
        }
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (!running) {
            when (action) {
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                    playhead = (arguments?.getFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: start).coerceIn(start, end)
                    seeked?.invoke(playhead!!); return true
                }
                101 -> start = (start - .01f).coerceAtLeast(0f)
                102 -> start = (start + .01f).coerceAtMost(end - .01f)
                103 -> end = (end - .01f).coerceAtLeast(start + .01f)
                104 -> end = (end + .01f).coerceAtMost(1f)
                else -> return super.performAccessibilityAction(action, arguments)
            }
            changed?.invoke(); invalidate(); return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { animation?.cancel(); super.onDetachedFromWindow() }
}
