package com.simprel.notes

import kotlin.math.abs

fun resolveLanguage(selected: String, systemLanguage: String): String = when (selected) {
    "ru", "en" -> selected
    else -> if (systemLanguage == "ru") "ru" else "en"
}


fun moveNoteId(ids: List<String>, id: String, target: Int): List<String> {
    val from = ids.indexOf(id)
    if (from < 0 || target !in ids.indices || from == target) return ids
    return ids.toMutableList().apply { removeAt(from); add(target, id) }
}

/** Coordinates of a logical note; reversing presentation never changes the stored order. */
fun noteCell(index: Int, count: Int, reverse: Boolean): Pair<Int, Int> {
    require(index in 0 until count)
    return if (reverse) (1 - index % 2) to ((count + 1) / 2 - 1 - index / 2) else (index % 2) to (index / 2)
}

fun noteIndex(column: Int, row: Int, count: Int, reverse: Boolean): Int {
    if (count == 0) return -1
    val slot = row * 2 + column
    return (if (reverse) ((count + 1) / 2) * 2 - 1 - slot else slot).coerceIn(0, count - 1)
}

class SwipeProgress(private val slop: Float, private val longPressTimeout: Long) {
    private var startX = 0f
    private var startY = 0f
    private var startedAt = 0L
    private var cancelled = true
    private var horizontal = false
    fun start(x: Float, y: Float, time: Long) { startX = x; startY = y; startedAt = time; cancelled = false; horizontal = false }
    fun move(x: Float, y: Float, time: Long): Float? {
        if (cancelled) return null
        val dx = x - startX
        val dy = abs(y - startY)
        if (!horizontal) {
            if (time - startedAt >= longPressTimeout || (dy > slop && dy > abs(dx)) || dx < -slop) { cancelled = true; return null }
            if (dx > slop && dx > dy * 2) horizontal = true
        }
        return if (horizontal) dx.coerceAtLeast(0f) else null
    }
}

fun shouldDismissEditor(distance: Float, width: Float, velocity: Float, density: Float): Boolean {
    if (width <= 0f || distance <= 0f) return false
    if (velocity < -600f * density) return false
    return distance >= width * .32f || (distance >= 24f * density && velocity >= 700f * density)
}
