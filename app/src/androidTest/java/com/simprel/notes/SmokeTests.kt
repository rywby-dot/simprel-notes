package com.simprel.notes

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Bundle
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.AtomicFile
import java.io.File

/** Run with connectedDebugAndroidTest. Uses isolated files, never the user's notes. */
class SmokeTests : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        val status = Bundle().apply {
            putString("class", SmokeTests::class.java.name); putString("test", "storageAndMarkdown")
            putInt("numtests", 1); putInt("current", 1); putString("id", "SimprelTests")
        }
        sendStatus(1, status)
        val directory = File(targetContext.cacheDir, "test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(targetContext) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("test-$name", mode)
        }
        try {
            val store = Store(isolated)
            check(store.notes.isEmpty())
            val first = Note(text = "# Привет\n**Мир** 🙂")
            val second = Note(text = "Вторая заметка\nНовая строка")
            store.notes.addAll(listOf(first, Note(text = " \n\t"), second))
            store.save()
            check(Store(isolated).notes.map { it.id } == listOf(first.id, second.id))
            check(Store(isolated).notes.first().text == first.text)
            store.notes.clear(); store.notes.addAll(listOf(second, first)); store.save()
            check(Store(isolated).notes.map { it.id } == listOf(second.id, first.id))
            // An interrupted write must retain the previous complete document.
            val file = AtomicFile(File(directory, "notes.json"))
            val stream = file.startWrite(); stream.write("broken".toByteArray()); file.failWrite(stream)
            check(Store(isolated).notes.first().id == second.id)
            store.notes.remove(second); store.save()
            check(Store(isolated).notes.single().id == first.id)
            store.notes.clear(); store.save(); check(Store(isolated).notes.isEmpty())
            runOnMainSync {
                val notes = listOf(Note("tile-a", "A"), Note("tile-b", "B"), Note("tile-c", "C"))
                val grid = AnimatedNotesGrid(targetContext,
                    { android.widget.TextView(targetContext).apply { text = it.text } },
                    { view, note -> (view as android.widget.TextView).text = note.text },
                    { true })
                grid.submit(notes, false)
                val measure = android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY)
                val height = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                grid.measure(measure, height); grid.layout(0, 0, 400, grid.measuredHeight)
                val originals = grid.cards().toList()
                grid.submit(listOf(notes[2], notes[0], notes[1]), false)
                grid.measure(measure, height); grid.layout(0, 0, 400, grid.measuredHeight)
                check(grid.cards().toSet() == originals.toSet())
                check(originals[2].top == 0 && originals[2].left == 0)
                grid.submit(listOf(notes[2], notes[1]), false)
                check(grid.cards().toSet() == setOf(originals[1], originals[2]))
                grid.reverse = true
                grid.minimumHeight = 600
                grid.measure(measure, height); grid.layout(0, 0, 400, grid.measuredHeight)
                check(originals[2].left > originals[1].left)
                check(originals[2].bottom == 600)
                // A single tile keeps a half-width square, including a filled viewport.
                grid.minimumHeight = 0
                grid.submit(listOf(notes[2]), false)
                val viewport = android.view.View.MeasureSpec.makeMeasureSpec(700, android.view.View.MeasureSpec.EXACTLY)
                grid.measure(measure, viewport); grid.layout(0, 0, 400, grid.measuredHeight)
                check(originals[2].width < 200 && originals[2].width == originals[2].height)
                check(originals[2].bottom == 700)
                // Compact rows span the width, touch each other and reverse from the bottom.
                grid.compact = true
                grid.submit(listOf(notes[2], notes[1]), false)
                grid.measure(measure, viewport); grid.layout(0, 0, 400, grid.measuredHeight)
                val compact = grid.cards().toList()
                check(compact.all { it.width == 400 })
                check(compact[0].bottom == 700)
                check(compact[1].bottom == compact[0].top)
                grid.reverse = false
                grid.requestLayout()
                grid.measure(measure, viewport); grid.layout(0, 0, 400, grid.measuredHeight)
                check(compact[0].top == 0 && compact[0].bottom == compact[1].top)
                // Only the outer compact-list contour rounds; row joints stay filled.
                grid.cards().forEach { it.setBackgroundColor(Color.WHITE) }
                grid.borderWidth = 0f
                grid.rounding = 1f
                val bitmap = android.graphics.Bitmap.createBitmap(400, 700, android.graphics.Bitmap.Config.ARGB_8888)
                grid.draw(android.graphics.Canvas(bitmap))
                check(Color.alpha(bitmap.getPixel(0, 0)) == 0)
                check(Color.alpha(bitmap.getPixel(200, 2)) > 0)
                check(Color.alpha(bitmap.getPixel(200, compact[0].bottom)) > 0)
                check(Color.alpha(bitmap.getPixel(0, compact[0].bottom)) > 0)
                grid.rounding = 0f
                bitmap.eraseColor(Color.TRANSPARENT)
                grid.draw(android.graphics.Canvas(bitmap))
                check(Color.alpha(bitmap.getPixel(0, 0)) > 0)
                bitmap.recycle()
                // An overflowing badge must remain inside the grid's measured bounds,
                // even when ScrollView clips the grid child on every edge.
                val padded = AnimatedNotesGrid(targetContext, {
                    val badge = android.view.View(targetContext).apply {
                        tag = "test-badge"
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE)
                        }
                    }
                    object : android.widget.FrameLayout(targetContext) {
                        init { clipChildren = false; clipToPadding = false; addView(badge, LayoutParams(28,28)) }
                        override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
                            super.onLayout(c,l,t,r,b)
                            badge.layout(width-9, -10, width+19, 18)
                        }
                    }
                }, { _, _ -> }, { true }).apply { setPadding(22,28,22,14) }
                padded.submit(listOf(notes[0], notes[1]), false)
                val viewportScroll = android.widget.ScrollView(targetContext).apply {
                    isFillViewport = true; isVerticalScrollBarEnabled = false
                    isVerticalFadingEdgeEnabled = false; overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    addView(padded)
                }
                viewportScroll.measure(measure, viewport)
                viewportScroll.layout(0,0,400,700)
                val card = padded.cards().last()
                val badge = card.findViewWithTag<android.view.View>("test-badge")
                check(card.top + badge.top >= 0 && card.right + 19 <= padded.width)
                val rendered = android.graphics.Bitmap.createBitmap(400,700,android.graphics.Bitmap.Config.ARGB_8888)
                viewportScroll.draw(android.graphics.Canvas(rendered))
                check(rendered.getPixel(card.left + badge.left + 14, card.top + badge.top + 1) == Color.WHITE)
                check(rendered.getPixel(card.left + badge.right - 2, card.top + badge.top + 14) == Color.WHITE)
                rendered.recycle()
                val borderBitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
                val borderCanvas = android.graphics.Canvas(borderBitmap)
                val thinBorder = NoteSurfaceDrawable(Color.WHITE, 0f, .5f, Color.BLACK)
                thinBorder.setBounds(0, 0, 20, 20); thinBorder.draw(borderCanvas)
                val halfPixel = borderBitmap.getPixel(0, 10)
                borderBitmap.eraseColor(Color.TRANSPARENT)
                val wholeBorder = NoteSurfaceDrawable(Color.WHITE, 0f, 1f, Color.BLACK)
                wholeBorder.setBounds(0, 0, 20, 20); wholeBorder.draw(borderCanvas)
                check(halfPixel != borderBitmap.getPixel(0, 10))
                borderBitmap.recycle()
                val beforePresentation = grid.cards().toList()
                grid.changePresentation(true)
                grid.measure(measure, height); grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
                check(grid.cards().toList() == beforePresentation)
                check(grid.cards().all { it.height == grid.compactRowHeight })
                grid.compactRowHeight = 120
                grid.requestLayout(); grid.measure(measure, height); grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
                check(grid.cards().all { it.height == 120 })
                grid.changePresentation(false)
                grid.measure(measure, height); grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
                check(grid.cards().toList() == beforePresentation)
                val slider = RoundingSlider(targetContext).apply { max = 50; progress = 4 }
                check(slider.progress / 2f == 2f)
                slider.progress = 50; check(slider.progress / 2f == 25f)
                val source = "# Заголовок\n**Жирный** и *курсив*\n`**код**`\n[ссылка](https://example.com)"
                val editable = SpannableStringBuilder(source)
                Selection.setSelection(editable, 10)
                val styler = MarkdownStyler(io.noties.markwon.Markwon.builder(targetContext).usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create()).build())
                styler.apply(editable)
                check(editable.toString() == source)
                check(Selection.getSelectionStart(editable) == 10)
                check(editable.getSpans(0, editable.length, android.text.style.MetricAffectingSpan::class.java).size >= 3)
                val count = editable.getSpans(0, editable.length, Any::class.java).size
                styler.apply(editable)
                check(editable.getSpans(0, editable.length, Any::class.java).size == count)
                editable.append("\n**Новый текст**"); styler.apply(editable)
                check(editable.toString() == source + "\n**Новый текст**")
                editable.clear(); styler.apply(editable); check(editable.isEmpty())
                for (value in listOf("word ", "word  ", "   ", "**bold** ", "# heading ")) {
                    val spaced = SpannableStringBuilder(value)
                    Selection.setSelection(spaced, spaced.length)
                    styler.apply(spaced)
                    val lastSpace = spaced.lastIndex
                    check(spaced.getSpans(lastSpace, lastSpace + 1, android.text.style.ReplacementSpan::class.java).isEmpty())
                    val textPaint = android.text.TextPaint().apply { textSize = 24f }
                    val layout = android.text.StaticLayout.Builder.obtain(spaced, 0, spaced.length, textPaint, 1000).build()
                    check(layout.getPrimaryHorizontal(spaced.length) > layout.getPrimaryHorizontal(spaced.length - 1))
                    check(spaced.toString() == value && Selection.getSelectionStart(spaced) == spaced.length)
                }
            }
            results.putString("stream", "Storage, atomic recovery, stable grid view identities, both layouts and Markdown checks passed.\n")
            sendStatus(0, status)
            finish(-1, results)
        } catch (e: Throwable) {
            results.putString("stream", "FAILED: ${e.stackTraceToString()}\n")
            status.putString("stack", e.stackTraceToString()); sendStatus(-2, status)
            finish(1, results)
        } finally { directory.deleteRecursively() }
    }
}
