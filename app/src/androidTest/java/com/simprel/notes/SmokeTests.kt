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
                val source = "# Заголовок\n**Жирный** и *курсив*\n`**код**`\n[ссылка](https://example.com)"
                val editable = SpannableStringBuilder(source)
                Selection.setSelection(editable, 10)
                val styler = MarkdownStyler(Color.BLUE)
                styler.apply(editable)
                check(editable.toString() == source)
                check(Selection.getSelectionStart(editable) == 10)
                check(editable.getSpans(0, editable.length, StyleSpan::class.java).size >= 3)
                val count = editable.getSpans(0, editable.length, Any::class.java).size
                styler.apply(editable)
                check(editable.getSpans(0, editable.length, Any::class.java).size == count)
                editable.append("\n**Новый текст**"); styler.apply(editable)
                check(editable.toString() == source + "\n**Новый текст**")
                editable.clear(); styler.apply(editable); check(editable.isEmpty())
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
