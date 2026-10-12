package com.simprel.notes

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/** Device regression suite. Install only into a separate .copytests application. */
class CopyTests : Instrumentation() {
    private val source = "# Copy test\n**Привет** 🙂  \nsecond line"
    private val actions = listOf("copy_delete_exit", "copy_stay", "copy_list", "copy_delete_list", "copy_exit")
    private lateinit var activity: MainActivity
    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun call(name: String, vararg args: Any) {
        val method = MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true; method.invoke(activity, *args)
    }
    private fun main(action: () -> Unit) { runOnMainSync(action); waitForIdleSync() }
    private fun launch() {
        activity = startActivitySync(Intent().setClassName(targetContext.packageName, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        waitForIdleSync()
    }
    private fun store() = field("store") as Store
    private fun find(view: View, description: String): View? {
        if (view.contentDescription == description) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), description)?.let { return it }
        return null
    }
    private fun clipboard(): String? = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
    private fun prepare(action: String): Note {
        launch()
        val note = Note(text = source)
        main {
            store().prefs.edit().clear().putString("copy_action", action).putString("left_swipe_action", action).putBoolean("undo_editor", true).commit()
            store().notes.clear(); store().notes.add(note); store().save()
            call("open", note, false, false)
            call("hideKeyboard")
        }
        SystemClock.sleep(350)
        return note
    }
    private fun swipeLeft() {
        val bounds = Rect()
        main { (field("activeEditor") as EditText).getGlobalVisibleRect(bounds) }
        val x = bounds.left + bounds.width() * .8f
        val y = bounds.top + 40f
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, offset: Float, elapsed: Long) {
            val event = MotionEvent.obtain(start, start + elapsed, action, x - offset, y, 0)
            main { activity.dispatchTouchEvent(event) }; event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f, 0)
        send(MotionEvent.ACTION_MOVE, bounds.width() * .4f, 80)
        send(MotionEvent.ACTION_UP, bounds.width() * .5f, 150)
    }
    private fun verifyEditorActions() {
        for (swipe in listOf(false, true)) for (action in actions) {
            val note = prepare(action)
            if (swipe) swipeLeft() else main {
                checkNotNull(find(activity.window.decorView, activity.getString(R.string.copy_note))).performClick()
            }
            SystemClock.sleep(900); waitForIdleSync()
            val expected = copyAction(action)
            check(store().notes.any { it.id == note.id } != expected.delete) { "$action swipe=$swipe: wrong deletion" }
            check(Store(targetContext).notes.any { it.id == note.id } != expected.delete) { "$action: wrong disk state" }
            if (expected.destination == "exit") {
                check(activity.isFinishing) { "$action swipe=$swipe: did not exit" }
                launch()
            } else main {
                check(field("screen") == expected.destination) { "$action swipe=$swipe: wrong screen ${field("screen")}" }
                if (expected.destination == "editor") check((field("activeEditor") as EditText).text.toString() == source)
            }
            main { check(clipboard() == source) { "$action swipe=$swipe: incorrect clipboard" } }
            main { activity.finishAndRemoveTask() }
            SystemClock.sleep(250)
        }
    }
    private fun verifyCrosses() {
        for (layout in listOf("grid", "compact")) for (direction in listOf("normal", "reverse")) {
            prepare("copy_stay")
            val target = Note(text = "Badge $layout $direction\n**raw** 🙂")
            val neighbor = Note(text = "Keep this note")
            main {
                store().prefs.edit().putString("layout", layout).putString("list_direction", direction).commit()
                store().notes.clear(); store().notes.addAll(listOf(neighbor, target)); store().save()
                call("discardList", true); call("showList")
            }
            SystemClock.sleep(300)
            lateinit var grid: AnimatedNotesGrid
            lateinit var badge: View
            fun locate(view: View): AnimatedNotesGrid? {
                if (view is AnimatedNotesGrid) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) locate(view.getChildAt(i))?.let { return it }
                return null
            }
            main {
                grid = checkNotNull(locate(activity.window.decorView))
                badge = grid.cards().single { it.tag == target.id }.findViewWithTag("note-delete-badge")
            }
            // Exercise grid forwarding, including the expanded badge target outside its bounds.
            val card = badge.parent as View
            val x = card.x + badge.x + badge.width / 2f
            val y = card.y + badge.y + badge.height / 2f
            val time = SystemClock.uptimeMillis()
            main {
                val event = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
                check(grid.dispatchTouchEvent(event)); event.recycle()
            }
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 200)
            main {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                grid.dispatchTouchEvent(event); event.recycle()
                check(store().notes.map { it.id } == listOf(neighbor.id)) { "Long cross failed: $layout $direction" }
                check(clipboard() == target.text)
            }
            SystemClock.sleep(350)
            // A short click deletes but leaves the clipboard unchanged.
            main {
                val remaining = grid.cards().single().findViewWithTag<View>("note-delete-badge")
                remaining.performClick()
                check(store().notes.isEmpty())
                check(clipboard() == target.text)
                activity.finishAndRemoveTask()
            }
            SystemClock.sleep(250)
        }
    }
    private fun verifyConfirmationAndUndo() {
        val note = prepare("copy_delete_list")
        main {
            store().prefs.edit().putBoolean("confirm_editor", true).commit()
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("test", "unchanged"))
            checkNotNull(find(activity.window.decorView, activity.getString(R.string.copy_note))).performClick()
            (field("confirmation") as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            check(store().notes.single().id == note.id)
            check(clipboard() == "unchanged")
        }
        SystemClock.sleep(150)
        main {
            checkNotNull(find(activity.window.decorView, activity.getString(R.string.copy_note))).performClick()
            (field("confirmation") as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        }
        SystemClock.sleep(450)
        main {
            check(store().notes.isEmpty()); check(clipboard() == source)
            fun undo(view: View): View? {
                if (view is android.widget.TextView && view.text.toString() == activity.getString(R.string.undo)) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) undo(view.getChildAt(i))?.let { return it }
                return null
            }
            checkNotNull(undo(activity.window.decorView)).performClick()
            check(store().notes.single().id == note.id)
            check(store().notes.single().text == source)
        }
        SystemClock.sleep(350)
        main { activity.finishAndRemoveTask() }
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        try {
            check(targetContext.packageName.endsWith(".copytests")) { "Requires an isolated test installation" }
            verifyEditorActions()
            verifyCrosses()
            verifyConfirmationAndUndo()
            results.putString("stream", "PASS: 5 copy actions via button and swipe; long/short cross in grid/compact and normal/reverse; exact Unicode/Markdown clipboard, persistent deletion, confirmation cancellation and undo.\n")
            finish(-1, results)
        } catch (e: Throwable) {
            results.putString("stream", "FAILED: ${e.stackTraceToString()}")
            finish(1, results)
        }
    }
}
