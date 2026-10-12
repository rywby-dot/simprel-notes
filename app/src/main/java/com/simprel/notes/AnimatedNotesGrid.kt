package com.simprel.notes

import android.content.Context
import android.content.ClipData
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Point
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
    private var heldHeight = 0
    private var outgoing = 0
    private val gap = (12 * resources.displayMetrics.density).toInt()
    private val easing = PathInterpolator(.2f, 0f, 0f, 1f)
    private val origins = mutableMapOf<String, Point>()
    private var animationPending = false
    var dragLocation: (Float) -> Unit = {}
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private fun capturePositions() {
        if (!isAttachedToWindow || !isLaidOut) return
        cards.forEach { (id, view) ->
            val xy = IntArray(2)
            view.getLocationOnScreen(xy)
            origins[id] = Point(xy[0], xy[1])
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
                    card.translationX = origin.x - targetX
                    card.translationY = origin.y - targetY
                    card.animate().translationX(0f).translationY(0f).setInterpolator(easing).setDuration(240).start()
                }
                origins.clear()
                return true
            }
        })
    }
    private fun size() = ((measuredWidth - gap) / 2).coerceAtLeast(0)

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
                    card.startDragAndDrop(ClipData.newPlainText("note", note.id), DragShadowBuilder(card), note.id, 0)
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

    fun cards(): Collection<View> = cards.values

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val tileSize = ((width - gap) / 2).coerceAtLeast(0)
        cards.values.forEach { it.measure(MeasureSpec.makeMeasureSpec(tileSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tileSize, MeasureSpec.EXACTLY)) }
        val rows = (order.size + 1) / 2
        val height = if (rows == 0) 0 else rows * tileSize + (rows - 1) * gap
        setMeasuredDimension(width, maxOf(height, heldHeight, if (reverse) minimumHeight else 0))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val tileSize = size()
        order.forEachIndexed { index, id ->
            val card = cards[id] ?: return@forEachIndexed
            val cell = noteCell(index, order.size, reverse)
            val contentHeight = ((order.size + 1) / 2) * (tileSize + gap) - gap
            val offset = if (reverse) (measuredHeight - contentHeight).coerceAtLeast(0) else 0
            val x = cell.first * (tileSize + gap)
            val y = offset + cell.second * (tileSize + gap)
            card.layout(x, y, x + tileSize, y + tileSize)
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
                val stride = size() + gap
                if (stride <= 0 || order.isEmpty()) return true
                val column = (event.x / stride).toInt().coerceIn(0, 1)
                val contentHeight = ((order.size + 1) / 2) * stride - gap
                val offset = if (reverse) (height - contentHeight).coerceAtLeast(0) else 0
                if (event.y < offset) return true
                val row = ((event.y - offset) / stride).toInt().coerceAtLeast(0)
                val target = noteIndex(column, row, order.size, reverse)
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
                cards[dragged]?.alpha = 1f
                dragged = null
                if (order != canonical) { capturePositions(); order = canonical; requestLayout() }
            }
        }
        return dragged != null || event.action == DragEvent.ACTION_DRAG_ENDED
    }
}
