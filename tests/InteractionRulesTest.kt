package com.simprel.notes

fun main() {
    check(copyAction("copy_delete_exit") == CopyAction(true, "exit"))
    check(copyAction("copy_stay") == CopyAction(false, "editor"))
    check(copyAction("copy_list") == CopyAction(false, "list"))
    check(copyAction("copy_delete_list") == CopyAction(true, "list"))
    check(copyAction("copy_exit") == CopyAction(false, "exit"))
    check(copyAction("unknown") == copyAction("copy_delete_exit"))
    println("PASS: all copy destinations, deletion flags and default fallback")

    fun visibleWhitespace(source: String): List<Int> = editorWhitespace(source).indices.filter { editorWhitespace(source)[it] }
    check(visibleWhitespace("word ") == listOf(4))
    check(visibleWhitespace("word  ") == listOf(4, 5))
    check(visibleWhitespace("   ") == listOf(0, 1, 2))
    check(visibleWhitespace("**bold** ") == listOf(8))
    check(visibleWhitespace("first  \nsecond ") == listOf(5, 6, 7, 14))
    check(visibleWhitespace("# heading ") == listOf(9))
    check(visibleWhitespace("word next").isEmpty())
    check(visibleWhitespace("word\t\r\n") == listOf(4, 5, 6))
    println("PASS: editor preserves trailing spaces, blank-line spaces, tabs, hard-break spaces and closing-markup spaces")

    val existing = Note("existing", "original")
    val other = Note("other", "unrelated")
    val draft = EditorDraft(existing.id, existing.text, 1)
    existing.text = "autosaved edits"
    val restored = draft.discardFrom(listOf(other, existing))
    check(restored.map { it.id } == listOf("other", "existing"))
    check(restored.last().text == "original")
    check(existing.text == "autosaved edits") // Failed persistence can restore untouched pre-discard objects.
    check(restored.first() === other)
    val newNote = Note("new", "autosaved new text")
    check(EditorDraft("new", "", -1).discardFrom(listOf(newNote, other)) == listOf(other))
    existing.text = ""
    check(draft.discardFrom(listOf(existing, other)).first().text == "original")
    check(draft.discardFrom(listOf(other)).last().text == "original")
    println("PASS: discard restores original text and order, removes new notes, preserves other notes and failure rollback")

    check(editorSwipeAction(-160f, 400f, 0f, 1f) == -1)
    check(editorSwipeAction(-30f, 400f, -900f, 1f) == -1)
    check(editorSwipeAction(-160f, 400f, 900f, 1f) == 0)
    check(editorSwipeAction(-40f, 400f, 0f, 1f) == 0)
    check(editorSwipeAction(160f, 400f, 0f, 1f) == 1)
    check(editorSwipeAction(30f, 400f, 900f, 1f) == 1)
    check(editorSwipeAction(0f, 400f, -1200f, 1f) == 0)
    check(editorSwipeAction(-30f, 800f, -1000f, 2f) == 0)
    println("PASS: editor swipe left exits, right returns, short swipes and reversal cancel")

    val afterExit = UndoWindow(0, true).onEntry(10000, 4)
    check(afterExit.remaining(10000) == 4000L)
    check(afterExit.onEntry(11000, 10) == afterExit)
    check(afterExit.remaining(13000) == 1000L)
    check(afterExit.remaining(14000) == 0L)
    check(afterExit.remaining(15000) == 0L)
    check(UndoWindow(0, true).onEntry(0, 0).expiresAt == 1000L)
    check(UndoWindow(0, true).onEntry(0, 100).expiresAt == 10000L)

    println("PASS: undo expiry, resume without timeout reset, pending exit and duration bounds")
    val cases = listOf("" to "", "**bold**" to "bold", "a &amp; b" to "a & b",
        "# Heading\n\n*hello*" to "Heading\n\nhello", "[label](https://example.com)" to "label",
        "foo_bar_baz" to "foo_bar_baz", "```kotlin\n**code**\n```" to "**code**\n")
    for ((source, rendered) in cases) {
        val runs = checkNotNull(alignMarkdown(source, rendered))
        check(runs.joinToString("") { source.substring(it.sourceStart, it.sourceEnd) } == source)
        check(runs.joinToString("") { rendered.substring(it.renderedStart, it.renderedEnd) } == rendered)
        runs.filter { it.equal }.forEach {
            check(source.substring(it.sourceStart, it.sourceEnd) == rendered.substring(it.renderedStart, it.renderedEnd))
        }
    }
    val random = kotlin.random.Random(47)
    repeat(500) {
        val source = (0 until random.nextInt(80)).map { "ab *_\n"[random.nextInt(6)] }.joinToString("")
        val rendered = (0 until random.nextInt(80)).map { "ab *_\n"[random.nextInt(6)] }.joinToString("")
        val runs = checkNotNull(alignMarkdown(source, rendered))
        check(runs.joinToString("") { source.substring(it.sourceStart, it.sourceEnd) } == source)
        check(runs.joinToString("") { rendered.substring(it.renderedStart, it.renderedEnd) } == rendered)
        runs.filter { it.equal }.forEach { check(source.substring(it.sourceStart, it.sourceEnd) == rendered.substring(it.renderedStart, it.renderedEnd)) }
    }

    check(resolveLanguage("system", "ru") == "ru")
    check(resolveLanguage("system", "en") == "en")
    listOf("de", "sr", "ar", "zh", "").forEach { check(resolveLanguage("system", it) == "en") }
    check(resolveLanguage("ru", "en") == "ru")
    check(resolveLanguage("en", "ru") == "en")

    val swipe = SwipeProgress(8f, 500)
    swipe.start(300f, 400f, 0)
    check(swipe.move(304f, 400f, 20) == null)
    check(swipe.move(320f, 402f, 80) == 20f)
    check(swipe.move(390f, 410f, 160) == 90f)
    // Dragging back reverses the animation rather than forcing navigation.
    check(swipe.move(330f, 410f, 200) == 30f)
    check(swipe.move(290f, 410f, 230) == 0f)
    swipe.start(300f, 400f, 0)
    check(swipe.move(304f, 450f, 80) == null)
    check(swipe.move(420f, 450f, 160) == null)
    swipe.start(300f, 400f, 0)
    check(swipe.move(302f, 400f, 550) == null)
    check(swipe.move(420f, 400f, 600) == null)
    swipe.start(300f, 400f, 0)
    check(swipe.move(270f, 400f, 80) == null)
    check(swipe.move(420f, 400f, 160) == null)
    swipe.start(300f, 400f, 0)
    check(swipe.move(330f, 402f, 100) == 30f)
    check(swipe.move(400f, 404f, 700) == 100f)
    check(!shouldDismissEditor(40f, 400f, 0f, 1f))
    check(shouldDismissEditor(160f, 400f, 0f, 1f))
    check(shouldDismissEditor(30f, 400f, 900f, 1f))
    check(!shouldDismissEditor(160f, 400f, -900f, 1f))
    check(!shouldDismissEditor(0f, 400f, 1200f, 1f))
    check(!shouldDismissEditor(20f, 0f, 1200f, 1f))
    check(!shouldDismissEditor(30f, 800f, 1000f, 2f))

    val bidirectional = SwipeProgress(8f, 500, true)
    for (sign in listOf(-1f, 1f)) {
        bidirectional.start(300f, 400f, 0)
        check(bidirectional.move(300f + sign * 80f, 402f, 100) == sign * 80f)
        check(bidirectional.move(300f + sign * 20f, 402f, 180) == sign * 20f)
        check(bidirectional.move(300f, 402f, 200) == 0f)
    }
    bidirectional.start(300f, 400f, 0)
    check(bidirectional.move(302f, 450f, 100) == null)
    check(bidirectional.move(100f, 450f, 200) == null)
    bidirectional.start(300f, 400f, 0)
    check(bidirectional.move(280f, 400f, 550) == null)

    // The badge follows rounding continuously and keeps its overlap on a circle.
    val squareBadge = noteBadgeCenter(200f, 0f, 14f)
    check(squareBadge.first > 180f && squareBadge.second < 14f)
    var previousInset = 0f
    for (rounding in 0..100) {
        val center = noteBadgeCenter(200f, rounding.toFloat(), 14f)
        check(center.first in 0f..200f && center.second in 0f..100f)
        check(kotlin.math.abs(200f - center.first - center.second) < .001f)
        check(200f - center.first >= previousInset - 1f)
        previousInset = 200f - center.first
    }
    val circleBadge = noteBadgeCenter(200f, 100f, 14f)
    val distance = kotlin.math.sqrt((circleBadge.first - 100f) * (circleBadge.first - 100f) + (circleBadge.second - 100f) * (circleBadge.second - 100f))
    check(kotlin.math.abs((distance + 14f - 100f) / 28f - 1f / 3f) < .001f)

    val anchor = ListScrollAnchor()
    check(anchor.target(1200, 500) == null) // Opening an empty editor creates no scroll event.
    anchor.followInsertion(false)
    check(anchor.target(1200, 500) == 0)
    check(anchor.target(1600, 800) == 0)
    anchor.release() // User scrolls freely after automatic positioning.
    check(anchor.target(1600, 500) == null)
    anchor.followInsertion(true)
    check(anchor.target(1200, 500) == 700)
    check(anchor.target(1200, 800) == 400) // Keyboard closes: still exactly at the bottom.
    check(anchor.target(1600, 800) == 800) // New row changes content height.
    check(anchor.target(300, 800) == 0) // Short list has no scroll range.
    anchor.release()
    check(anchor.target(1600, 800) == null)
    anchor.restore(275)
    check(anchor.target(1600, 800) == 275)
    check(anchor.target(1600, 800) == null) // Saved positions restore once, not on every frame.
    anchor.restore(900)
    check(anchor.target(600, 500) == 100)
    anchor.followBottom()
    check(anchor.target(1600, 800) == 800)

    val ids = listOf("a", "b", "c", "d")
    check(moveNoteId(ids, "a", 2) == listOf("b", "c", "a", "d"))
    check(moveNoteId(ids, "d", 0) == listOf("d", "a", "b", "c"))
    check(moveNoteId(ids, "missing", 0) == ids)
    check(moveNoteId(ids, "a", -1) == ids)
    check(ids == listOf("a", "b", "c", "d"))
    for (count in 1..15) {
        for (reverse in listOf(false, true)) {
            val cells = (0 until count).map { noteCell(it, count, reverse) }
            check(cells.distinct().size == count)
            cells.forEachIndexed { index, cell -> check(noteIndex(cell.first, cell.second, count, reverse) == index) }
            if (reverse) check(cells.first() == 1 to ((count + 1) / 2 - 1))
            else check(cells.first() == 0 to 0)
        }
    }
    check(noteIndex(0, 0, 0, true) == -1)
    println("PASS: locale fallback, interactive swipe/reversal/fling, stable reorder, both layout directions, insertion edge and keyboard resize anchoring")
}
