package com.simprel.notes

fun main() {
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
    println("PASS: locale fallback, interactive swipe/reversal/fling, stable reorder, both layout directions")
}
