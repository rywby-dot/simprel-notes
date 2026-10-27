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

class SwipeProgress(private val slop: Float, private val longPressTimeout: Long, private val bothDirections: Boolean = false) {
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
            if (time - startedAt >= longPressTimeout || (dy > slop && dy > abs(dx)) || (!bothDirections && dx < -slop)) { cancelled = true; return null }
            if (abs(dx) > slop && abs(dx) > dy * 2) horizontal = true
        }
        return if (horizontal) (if (bothDirections) dx else dx.coerceAtLeast(0f)) else null
    }
}

fun shouldDismissEditor(distance: Float, width: Float, velocity: Float, density: Float): Boolean {
    if (width <= 0f || distance <= 0f) return false
    if (velocity < -600f * density) return false
    return distance >= width * .32f || (distance >= 24f * density && velocity >= 700f * density)
}

/** Badge center follows the 45-degree point of the rounded top-right corner.
 * One third of its diameter protrudes beyond the local outline. */
fun noteBadgeCenter(width: Float, radius: Float, badgeRadius: Float): Pair<Float, Float> {
    val diagonal = 0.70710677f
    val arcInset = radius * (1f - diagonal) + badgeRadius / 3f * diagonal
    val blend = (radius / badgeRadius.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val inset = badgeRadius / 3f * (1f - blend) + arcInset * blend
    return (width - inset) to inset
}

/** Recompute an edge against the latest layout, including keyboard resize passes. */
class ListScrollAnchor {
    private var edge: Int? = null
    private var restored: Int? = null
    fun followInsertion(reverse: Boolean) { edge = if (reverse) 1 else 0; restored = null }
    fun followBottom() { edge = 1; restored = null }
    fun restore(position: Int) { edge = null; restored = position }
    fun release() { edge = null; restored = null }
    fun target(contentHeight: Int, viewportHeight: Int): Int? {
        val bottom = (contentHeight - viewportHeight).coerceAtLeast(0)
        val result = when (edge) { 0 -> 0; 1 -> bottom; else -> restored?.coerceIn(0, bottom) }
        restored = null
        return result
    }
}

/** An exit keeps Undo available until the next entry; normal panels never restart their timer. */
data class UndoWindow(val expiresAt: Long, val awaitingEntry: Boolean = false) {
    fun onEntry(now: Long, seconds: Int): UndoWindow = if (awaitingEntry)
        UndoWindow(now + seconds.coerceIn(1, 10) * 1000L) else this
    fun remaining(now: Long): Long = (expiresAt - now).coerceAtLeast(0)
}

/** Right returns to the list; left saves and exits. Reversing velocity cancels either action. */
fun editorSwipeAction(distance: Float, width: Float, velocity: Float, density: Float): Int {
    val direction = if (distance < 0f) -1 else 1
    return if (shouldDismissEditor(kotlin.math.abs(distance), width, velocity * direction, density)) direction else 0
}

/** Shared destinations for the copy button and the editor's left swipe. */
data class CopyAction(val delete: Boolean, val destination: String)

fun copyAction(value: String): CopyAction = when (value) {
    "copy_stay" -> CopyAction(false, "editor")
    "copy_list" -> CopyAction(false, "list")
    "copy_delete_list" -> CopyAction(true, "list")
    "copy_exit" -> CopyAction(false, "exit")
    else -> CopyAction(true, "exit")
}

/** The active filter is session state; only the order of the last two modes is a preference. */
fun nextReminderFilter(current: String, withoutFirst: Boolean): String {
    val order = if (withoutFirst) listOf("all", "without", "with") else listOf("all", "with", "without")
    return order[(order.indexOf(current).coerceAtLeast(0) + 1) % order.size]
}

fun matchesReminderFilter(filter: String, hasReminder: Boolean): Boolean = when (filter) {
    "with" -> hasReminder
    "without" -> !hasReminder
    else -> true
}

/** Reorder just the visible slots, keeping hidden notes and their canonical positions. */
fun reorderFilteredNotes(notes: List<Note>, ids: List<String>, visibleIds: Set<String>): List<Note>? {
    if (ids.size != visibleIds.size || ids.toSet() != visibleIds) return null
    val byId = notes.associateBy { it.id }
    if (byId.size != notes.size || !byId.keys.containsAll(visibleIds)) return null
    val reordered = ids.iterator()
    return notes.map { if (it.id in visibleIds) byId.getValue(reordered.next()) else it }
}

/** Widgets follow the last editor entry, independently of sorting and drag presentation. */
fun widgetLastNote(notes: List<Note>, lastId: String): Note? =
    notes.find { it.id == lastId && it.hasContent } ?: notes.firstOrNull { it.hasContent }

fun widgetUnreadNotes(notes: List<Note>, unreadIds: Set<String>): List<Note> =
    notes.filter { it.hasContent && it.id in unreadIds }.sortedByDescending { it.reminderAt }

fun widgetCornerRadius(width: Float, height: Float, columns: Int, rows: Int): Float =
    minOf(width / columns.coerceAtLeast(1), height / rows.coerceAtLeast(1)) / 2f
