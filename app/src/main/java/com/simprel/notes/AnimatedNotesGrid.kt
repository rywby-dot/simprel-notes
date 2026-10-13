package com.simprel.notes

import android.content.Context
import android.content.ClipData
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator

/** Stable note views; changes animate positions instead of replacing the list hierarchy. */
class AnimatedNotesGrid(
    context: Context,
    private val createCard: (Note) -> View,
    private val updateCard: (View, Note) -> Unit,
    private val commitOrder: (List<String>) -> Boolean,
) : ViewGroup(context) {
    private val cards = linkedMapOf<String, View>()
    private var canonical = emptyList<String>()
    private var order = emptyList<String>()
    private var dragged: String? = null
    var reverse = false
    var compact = false
    var rounding = .20f
    var borderWidth = 1f
    var borderColor = 0
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outlinePath = Path()
    private val compactBounds = RectF()
    private val columns get() = if (compact) 1 else 2
    private val spacing get() = if (compact) 0 else gap
    var compactRowHeight = (56 * resources.displayMetrics.density).toInt()
    private val rowHeight get() = if (compact) compactRowHeight else size()
    private var presentationAnimating = false
    private val presentationEnd = Runnable { presentationAnimating = false; invalidate() }
    private var heldHeight = 0
    private var outgoing = 0
    private val gap = (12 * resources.displayMetrics.density).toInt()
    private val easing = PathInterpolator(.2f, 0f, 0f, 1f)
    private val origins = mutableMapOf<String, Rect>()
    private var animationPending = false
    var dragLocation: (Float) -> Unit = {}
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private fun capturePositions() {
        if (!isAttachedToWindow || !isLaidOut) return
        cards.forEach { (id, view) ->
            val xy = IntArray(2)
            view.getLocationOnScreen(xy)
            origins[id] = Rect(xy[0], xy[1], xy[0] + (view.width * view.scaleX).toInt(), xy[1] + (view.height * view.scaleY).toInt())
            view.animate().cancel()
            if (id != dragged) view.alpha = 1f
        }
        if (animationPending) return
        animationPending = true
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                animationPending = false
                origins.forEach { (id, origin) ->
                    val card = cards[id] ?: return@forEach
                    val xy = IntArray(2)
                    card.getLocationOnScreen(xy)
                    val targetX = xy[0] - card.translationX
                    val targetY = xy[1] - card.translationY
                    card.translationX = origin.left - targetX
                    card.translationY = origin.top - targetY
                    card.pivotX = 0f; card.pivotY = 0f
                    card.scaleX = origin.width().toFloat() / card.width.coerceAtLeast(1)
                    card.scaleY = origin.height().toFloat() / card.height.coerceAtLeast(1)
                    card.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setInterpolator(easing).setDuration(240).start()
                }
                origins.clear()
                return true
            }
        })
    }
    private fun size(): Int {
        val inner = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        return if (compact) inner else ((inner - gap) / 2).coerceAtLeast(0)
    }

    init {
        clipChildren = false
        clipToPadding = false
        setOnDragListener { _, event -> handleDrag(event) }
    }

    fun submit(notes: List<Note>, animate: Boolean = true) {
        val ids = notes.map { it.id }
        require(ids.distinct().size == ids.size)
        val changed = ids != canonical
        if (changed && animate) capturePositions()
        val removed = cards.keys.filter { it !in ids }
        if (removed.isNotEmpty() && animate && isLaidOut) heldHeight = height
        removed.forEach { id ->
            val view = cards.remove(id) ?: return@forEach
            view.animate().cancel()
            if (animate && isLaidOut) {
                outgoing++
                view.isClickable = false
                view.animate().alpha(0f).scaleX(.85f).scaleY(.85f).setDuration(150).withEndAction {
                    removeView(view)
                    outgoing--
                    if (outgoing == 0) { capturePositions(); heldHeight = 0; requestLayout() }
                }.start()
            } else removeView(view)
        }
        notes.forEach { note ->
            val existing = cards[note.id]
            if (existing != null) updateCard(existing, note)
            else {
                val card = createCard(note)
                cards[note.id] = card
                addView(card)
                card.setOnLongClickListener {
                    card.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    card.startDragAndDrop(ClipData.newPlainText("note", note.id), LiftedShadow(card), note.id, 0)
                }
                if (animate && isLaidOut) {
                    card.alpha = 0f
                    card.animate().alpha(1f).setDuration(180).start()
                }
            }
        }
        canonical = ids
        order = ids
        if (changed) requestLayout()
    }

    fun changePresentation(value: Boolean) {
        capturePositions()
        presentationAnimating = true
        removeCallbacks(presentationEnd); postDelayed(presentationEnd, 260)
        compact = value
        val d = resources.displayMetrics.density
        setPadding(if (value) 0 else (22*d).toInt(), if (value) 0 else (28*d).toInt(), if (value) 0 else (22*d).toInt(), if (value) 0 else (14*d).toInt())
        requestLayout(); invalidate()
    }
    fun cards(): Collection<View> = cards.values

    private var touchedBadge: View? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchedBadge = cards.values.reversed().firstNotNullOfOrNull { card ->
                val badge = listOf("note-delete-badge", "note-reminder-badge").mapNotNull { card.findViewWithTag<View>(it) }.firstOrNull { badge ->
                    val expand = 10 * resources.displayMetrics.density
                    badge.visibility == View.VISIBLE && event.x >= card.x + badge.x - expand && event.x <= card.x + badge.x + badge.width + expand && event.y >= card.y + badge.y - expand && event.y <= card.y + badge.y + badge.height + expand
                }
                if (badge == null || badge.visibility != View.VISIBLE) null else {
                    val x = card.x + badge.x
                    val y = card.y + badge.y
                    val expand = 10 * resources.displayMetrics.density
                    if (event.x >= x - expand && event.x <= x + badge.width + expand && event.y >= y - expand && event.y <= y + badge.height + expand) badge else null
                }
            }
        }
        touchedBadge?.let { badge ->
            val card = badge.parent as View
            val local = MotionEvent.obtain(event)
            local.offsetLocation(-card.x - badge.x, -card.y - badge.y)
            val extra = 10 * resources.displayMetrics.density
            if (event.actionMasked == MotionEvent.ACTION_MOVE && (local.x < -extra || local.x > badge.width + extra || local.y < -extra || local.y > badge.height + extra)) {
                local.action = MotionEvent.ACTION_CANCEL
                touchedBadge = null
            }
            // The expanded 48dp target should behave as a normal click inside the 28dp badge.
            if (local.actionMasked != MotionEvent.ACTION_CANCEL) local.setLocation(local.x.coerceIn(0f, badge.width - 1f), local.y.coerceIn(0f, badge.height - 1f))
            val consumed = badge.dispatchTouchEvent(local)
            local.recycle()
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touchedBadge = null
            return consumed
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val innerWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val tileWidth = if (compact) innerWidth else ((innerWidth - gap) / 2).coerceAtLeast(0)
        val tileHeight = if (compact) compactRowHeight else tileWidth
        cards.values.forEach { it.measure(MeasureSpec.makeMeasureSpec(tileWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tileHeight, MeasureSpec.EXACTLY)) }
        val rows = (order.size + columns - 1) / columns
        val contentHeight = paddingTop + paddingBottom + if (rows == 0) 0 else rows * tileHeight + (rows - 1) * spacing
        val viewport = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(width, maxOf(contentHeight, heldHeight, viewport, if (reverse) minimumHeight else 0))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val tileWidth = size()
        val rows = (order.size + columns - 1) / columns
        val contentHeight = rows * (rowHeight + spacing) - spacing
        val offset = if (reverse) (measuredHeight - paddingTop - paddingBottom - contentHeight).coerceAtLeast(0) else 0
        order.forEachIndexed { index, id ->
            val card = cards[id] ?: return@forEachIndexed
            val cell = if (compact) 0 to (if (reverse) order.lastIndex - index else index) else noteCell(index, order.size, reverse)
            val x = paddingLeft + cell.first * (tileWidth + spacing)
            val y = paddingTop + offset + cell.second * (rowHeight + spacing)
            card.layout(x, y, x + tileWidth, y + rowHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!compact || presentationAnimating || order.isEmpty()) { super.dispatchDraw(canvas); return }
        val contentHeight = order.size * rowHeight
        val offset = if (reverse) (height - contentHeight).coerceAtLeast(0) else 0
        val halfStroke = borderWidth / 2f
        compactBounds.set(halfStroke, offset + halfStroke, width - halfStroke, offset + contentHeight - halfStroke)
        // Limit the corner to the end row so intermediate rows keep straight edges.
        val radius = minOf(width, rowHeight) * rounding.coerceIn(0f, 1f) / 2f
        outlinePath.reset()
        outlinePath.addRoundRect(compactBounds, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(outlinePath)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
        if (borderWidth > 0f) {
            borderPaint.color = borderColor; borderPaint.strokeWidth = borderWidth
            canvas.drawPath(outlinePath, borderPaint)
        }
    }

    private class LiftedShadow(private val card: View) : DragShadowBuilder(card) {
        private val scale = 1.04f
        private val padding = (12 * card.resources.displayMetrics.density).toInt()
        override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
            size.set((card.width * scale).toInt() + padding * 2, (card.height * scale).toInt() + padding * 2)
            touch.set(size.x / 2, size.y / 2)
        }
        override fun onDrawShadow(canvas: Canvas) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
            val radius = 24 * card.resources.displayMetrics.density
            canvas.drawRoundRect(padding/2f, padding.toFloat(), (canvas.width-padding/2).toFloat(), (canvas.height-padding/2).toFloat(), radius, radius, paint)
            canvas.save(); canvas.translate(padding.toFloat(), padding.toFloat()); canvas.scale(scale, scale)
            val alpha = card.alpha; card.alpha = 1f; card.draw(canvas); card.alpha = alpha
            canvas.restore()
        }
    }
    private fun handleDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                val id = event.localState as? String ?: return false
                if (id !in cards) return false
                dragged = id
                cards[id]?.alpha = .2f
                return true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                val id = dragged ?: return false
                dragLocation(event.y)
                val stride = rowHeight + spacing
                if (stride <= 0 || order.isEmpty()) return true
                val column = if (compact) 0 else ((event.x - paddingLeft) / (size() + spacing)).toInt().coerceIn(0, 1)
                val contentHeight = ((order.size + columns - 1) / columns) * stride - spacing
                val offset = paddingTop + if (reverse) (height - paddingTop - paddingBottom - contentHeight).coerceAtLeast(0) else 0
                if (event.y < offset) return true
                val row = ((event.y - offset) / stride).toInt().coerceAtLeast(0)
                val target = if (compact) (if (reverse) order.lastIndex - row else row).coerceIn(order.indices) else noteIndex(column, row, order.size, reverse)
                val from = order.indexOf(id)
                if (from >= 0 && target != from) {
                    capturePositions()
                    order = moveNoteId(order, id, target)
                    requestLayout()
                }
            }
            DragEvent.ACTION_DROP -> {
                if (dragged == null) return false
                if (commitOrder(order)) canonical = order else { capturePositions(); order = canonical; requestLayout() }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                cards[dragged]?.let { card ->
                    card.alpha = 1f
                    card.scaleX = 1.025f; card.scaleY = 1.025f
                    card.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }
                dragged = null
                if (order != canonical) { capturePositions(); order = canonical; requestLayout() }
            }
        }
        return dragged != null || event.action == DragEvent.ACTION_DRAG_ENDED
    }
}
