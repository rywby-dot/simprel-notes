package com.simprel.notes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/** Material 3's 52 x 32dp switch, with a full-width accessible touch target. */
class SettingToggle(
    context: Context, title: String, initial: Boolean,
    private var accent: Int, private var surface: Int, private var textColor: Int,
    private var outline: Int, private val changed: (Boolean) -> Unit,
) : LinearLayout(context) {
    private var checked = initial
    private var fraction = if (initial) 1f else 0f
    private var animation: android.animation.ValueAnimator? = null
    private val evaluator = android.animation.ArgbEvaluator()
    private lateinit var label: TextView
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val toggle = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            canvas.save()
            val density = resources.displayMetrics.density
            canvas.translate((width - 52 * density) / 2, (height - 32 * density) / 2)
            canvas.scale(density, density)
            paint.style = Paint.Style.FILL; paint.color = evaluator.evaluate(fraction, surface, accent) as Int
            canvas.drawRoundRect(0f, 0f, 52f, 32f, 16f, 16f, paint)
            if (fraction < 1f) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = outline; paint.alpha = ((1f - fraction) * 255).toInt()
                canvas.drawRoundRect(1f, 1f, 51f, 31f, 15f, 15f, paint)
            }
            paint.style = Paint.Style.FILL; paint.alpha = 255; paint.color = evaluator.evaluate(fraction, outline, surface) as Int
            canvas.drawCircle(16f + 20f * fraction, 16f, 8f + 4f * fraction, paint)
            if (fraction > 0f) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.strokeCap = Paint.Cap.ROUND; paint.color = accent; paint.alpha = (fraction * 255).toInt()
                canvas.drawLine(30f, 16f, 34f, 20f, paint); canvas.drawLine(34f, 20f, 42f, 12f, paint)
            }
            paint.alpha = 255; canvas.restore()
        }
    }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56); isClickable = true; isFocusable = true; contentDescription = title
        val mask = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(-1) }
        background = RippleDrawable(ColorStateList.valueOf((accent and 0xFFFFFF) or 0x22000000), null, mask)
        label = TextView(context).apply {
            text = title; textSize = 16f; setTextColor(this@SettingToggle.textColor)
            setPadding(0, dp(12), dp(16), dp(12)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(label, LayoutParams(0, -2, 1f))
        toggle.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(toggle, LayoutParams(dp(52), dp(48)))
        setOnClickListener {
            checked = !checked
            animation?.cancel()
            animation = android.animation.ValueAnimator.ofFloat(fraction, if (checked) 1f else 0f).apply {
                duration = 180
                addUpdateListener { fraction = it.animatedValue as Float; toggle.invalidate() }
                start()
            }
            changed(checked); refreshDrawableState()
        }
    }
    fun updateAppearance(title: String, accent: Int, surface: Int, textColor: Int, outline: Int) {
        this.accent = accent; this.surface = surface; this.textColor = textColor; this.outline = outline
        if (label.text.toString() != title) label.text = title
        label.setTextColor(textColor); contentDescription = title
        val mask = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(-1) }
        background = RippleDrawable(ColorStateList.valueOf((accent and 0xFFFFFF) or 0x22000000), null, mask)
        toggle.invalidate()
    }
    override fun onDetachedFromWindow() { animation?.cancel(); super.onDetachedFromWindow() }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"; info.isCheckable = true; info.isChecked = checked
    }
}
