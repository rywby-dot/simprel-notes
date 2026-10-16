package com.simprel.notes

import android.app.Dialog
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.TextView

/** All writes and alarms are confined to the test clone. */
class ReminderTests : Instrumentation() {
    private lateinit var activity: MainActivity
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun call(name: String, vararg args: Any) {
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(activity, *args)
    }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }; waitForIdleSync()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun click(dialog: Dialog, id: Int) {
        views(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(id) }.performClick()
    }
    private fun setField(name: String, value: Any) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
    private fun grid() = views(activity.window.decorView).filterIsInstance<AnimatedNotesGrid>().single()
    private fun filter() = activity.window.decorView.findViewWithTag<View>("note-reminder-filter")
    private fun visibleIds() = grid().cards().map { it.tag as String }.toSet()
    private fun longBadge(id: String) {
        lateinit var grid: AnimatedNotesGrid
        lateinit var badge: View
        val time = SystemClock.uptimeMillis()
        var x = 0f; var y = 0f
        main {
            grid = grid()
            badge = grid.cards().single { it.tag == id }.findViewWithTag("note-reminder-badge")
            val card = badge.parent as View
            x = card.x + badge.x + badge.width / 2f; y = card.y + badge.y + badge.height / 2f
            val event = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
            check(grid.dispatchTouchEvent(event)); event.recycle()
        }
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150)
        main {
            val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, y, 0)
            grid.dispatchTouchEvent(event); event.recycle()
        }
    }
    private fun verifyRefinements() {
        var store = field("store") as Store
        val plainA = Note(text = "Same text")
        val timedB = Note(text = "Same text", reminderAt = System.currentTimeMillis() + 600000)
        val plainC = Note(text = "Keep C")
        val timedD = Note(text = "Keep D", reminderAt = System.currentTimeMillis() + 1200000)
        val all = listOf(plainA, timedB, plainC, timedD)
        main {
            store.prefs.edit().clear().putString("start", "list").commit()
            store.notes.clear(); store.notes.addAll(all); store.save()
            call("open", timedB, false, false)
            check(Reminders.hasReminder(targetContext, timedB)) { "Opening a future reminder must preserve it" }
            val toolbar = field("toolbar") as ViewGroup
            val actions = (0 until toolbar.childCount).mapNotNull { toolbar.getChildAt(it).contentDescription?.toString() }
            check(actions == listOf(R.string.all_notes, R.string.reminder, R.string.voice_note, R.string.delete_current, R.string.copy_note, R.string.done).map { activity.getString(it) })
            check(toolbar.getChildAt(1).layoutParams.width == 0) { "Spacer must follow Back" }
        }
        for (layout in listOf("grid", "compact")) for (direction in listOf("normal", "reverse")) for (withoutFirst in listOf(false, true)) {
            main {
                store.notes.clear(); store.notes.addAll(all); store.save()
                store.prefs.edit().putString("layout", layout).putString("list_direction", direction)
                    .putBoolean("filter_without_first", withoutFirst).putBoolean("always_delete", direction == "reverse").commit()
                setField("reminderFilter", "all"); call("discardList", true); call("showList")
                check(visibleIds() == all.map { it.id }.toSet())
                val bar = filter().parent as ViewGroup
                check(bar.indexOfChild(filter()) == bar.childCount - 2)
                if (direction == "normal") check(bar.getChildAt(bar.indexOfChild(filter()) - 1).visibility == View.VISIBLE)
                filter().performClick()
                check(visibleIds() == (if (withoutFirst) listOf(plainA, plainC) else listOf(timedB, timedD)).map { it.id }.toSet())
                check(filter().contentDescription == activity.getString(if (withoutFirst) R.string.filter_without else R.string.filter_with))
                filter().performClick()
                check(visibleIds() == (if (withoutFirst) listOf(timedB, timedD) else listOf(plainA, plainC)).map { it.id }.toSet())
                filter().performClick()
                check(visibleIds() == all.map { it.id }.toSet() && filter().contentDescription == activity.getString(R.string.filter_all))
                filter().performClick()
                if (withoutFirst) filter().performClick()
                call("reorder", listOf(timedD.id, timedB.id))
                check(store.notes.map { it.id } == listOf(plainA.id, timedD.id, plainC.id, timedB.id))
                check(Store(targetContext).notes.map { it.id } == store.notes.map { it.id })
                setField("reminderFilter", "all"); call("showList")
                check(visibleIds() == all.map { it.id }.toSet())
            }
        }
        main {
            store.prefs.edit().putBoolean("filter_without_first", false).commit()
            call("settings", false, false)
            views(activity.window.decorView).filterIsInstance<SettingToggle>().single { it.contentDescription == activity.getString(R.string.reminder) }.performClick()
            views(activity.window.decorView).filterIsInstance<SettingToggle>().single { it.contentDescription == activity.getString(R.string.filter_without_first) }.performClick()
            check(store.prefs.getBoolean("confirm_reminder", false) && store.prefs.getBoolean("filter_without_first", false))
            setField("reminderFilter", "with"); call("showList")
        }
        SystemClock.sleep(350)
        longBadge(timedB.id)
        main {
            check(timedB.reminderAt > 0)
            (field("confirmation") as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            check(timedB.reminderAt > 0 && Store(targetContext).notes.single { it.id == timedB.id }.reminderAt == timedB.reminderAt)
            check(field("reminderDialog") == null) { "Long press also opened the picker" }
        }
        longBadge(timedB.id)
        main {
            (field("confirmation") as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        }
        main {
            check(timedB.reminderAt == 0L && timedD.reminderAt > 0)
            check(store.notes.size == 4 && visibleIds() == setOf(timedD.id))
        }
        SystemClock.sleep(300)
        screenshot("filter-with-reminders.png")
        main { setField("reminderFilter", "without"); call("showList") }
        SystemClock.sleep(300)
        screenshot("filter-without-reminders.png")
        main {
            check(visibleIds() == setOf(plainA.id, timedB.id, plainC.id))
            setField("reminderFilter", "with"); call("showList")
            activity.moveTaskToBack(true)
        }
        SystemClock.sleep(300)
        main { targetContext.startActivity(Intent().setClassName(targetContext.packageName, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        SystemClock.sleep(600)
        main {
            check(field("reminderFilter") == "all" && visibleIds().size == 4) { "Filter did not reset on foreground entry" }
            filter().performClick()
            check(field("reminderFilter") == "without") { "Saved filter order was lost" }
        }
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        main { activity.recreate() }
        activity = checkNotNull(monitor.waitForActivityWithTimeout(4000)) as MainActivity
        removeMonitor(monitor); waitForIdleSync()
        store = field("store") as Store
        main {
            check(field("reminderFilter") == "without" && visibleIds().size == 3) { "Rotation must retain the session filter" }
            setField("reminderFilter", "with"); call("showList")
            store.notes.single { it.id == timedD.id }.reminderAt = 0; store.save(); call("showList")
            check(visibleIds().isEmpty())
            check(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.empty_filtered_notes) && it.visibility == View.VISIBLE })
            call("settings", false, false)
            views(activity.window.decorView).single { it.contentDescription == activity.getString(R.string.reset_settings) }.performClick()
            (field("confirmation") as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        }
        main {
            check(!store.prefs.contains("confirm_reminder") && !store.prefs.contains("filter_without_first") && !store.prefs.contains("undo_reminder"))
            setField("reminderFilter", "all"); call("showList")
        }
    }
    private fun undoReminder() {
        views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.undo) }.performClick()
    }
    private fun verifyReminderUndo() {
        var store = field("store") as Store
        val original = System.currentTimeMillis() + 600000
        val note = Note(text = "Reminder undo **raw** 🙂", reminderAt = original)
        val neighbor = Note(text = "Keep neighbor", reminderAt = original + 60000)
        main {
            store.prefs.edit().clear().putString("start", "list").putInt("undo_seconds", 10).commit()
            store.notes.clear(); store.notes.addAll(listOf(note, neighbor)); store.save()
            call("showList"); call("removeReminder", note)
            check(note.reminderAt == 0L && field("undoPanel") != null)
            check(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.reminder_deleted) })
            note.text = "Edited after removal  "; store.notes.reverse(); store.save()
            undoReminder()
            check(note.reminderAt == original && note.text == "Edited after removal  ")
            check(store.notes.map { it.id } == listOf(neighbor.id, note.id))
            check(Store(targetContext).notes.single { it.id == note.id }.reminderAt == original)
            call("settings", false, false)
            views(activity.window.decorView).filterIsInstance<SettingToggle>().single { it.contentDescription == activity.getString(R.string.undo_reminder) }.performClick()
            check(!store.prefs.getBoolean("undo_reminder", true))
            call("showList"); call("removeReminder", note)
            check(field("undoPanel") == null && !store.prefs.contains("pending_undo"))
            call("settings", false, false)
            views(activity.window.decorView).filterIsInstance<SettingToggle>().single { it.contentDescription == activity.getString(R.string.undo_reminder) }.performClick()
            check(store.prefs.getBoolean("undo_reminder", false))
            call("showList")
            note.reminderAt = original; store.save()
            store.prefs.edit().putBoolean("confirm_reminder", true).commit()
            call("removeReminder", note)
            (field("confirmation") as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        main {
            check(note.reminderAt == original && field("undoPanel") == null)
            store.prefs.edit().putBoolean("confirm_reminder", false).putInt("undo_seconds", 1).commit()
            call("removeReminder", note)
        }
        SystemClock.sleep(1300)
        main {
            check(field("undoPanel") == null && note.reminderAt == 0L && !store.prefs.contains("pending_undo"))
            store.prefs.edit().putInt("undo_seconds", 10).commit()
            note.reminderAt = original; store.save(); call("removeReminder", note)
            activity.finishAndRemoveTask()
        }
        SystemClock.sleep(250)
        activity = startActivitySync(Intent().setClassName(targetContext.packageName, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        waitForIdleSync(); store = field("store") as Store
        main {
            undoReminder()
            val restored = store.notes.single { it.id == note.id }
            check(restored.reminderAt == original && restored.text == "Edited after removal  ")
            val other = store.notes.single { it.id == neighbor.id }
            call("removeReminder", restored); call("removeReminder", other); undoReminder()
            check(restored.reminderAt == 0L && other.reminderAt == neighbor.reminderAt)
            call("removeReminder", other)
            store.notes.remove(other); store.save(); call("showList"); undoReminder()
            check(store.notes.none { it.id == neighbor.id }) { "Reminder undo resurrected a deleted note" }
            restored.reminderAt = original; store.save(); call("removeReminder", restored)
            restored.reminderAt = original + 120000; store.save(); undoReminder()
            check(restored.reminderAt == original + 120000) { "Undo overwrote a newly set reminder" }
            restored.reminderAt = System.currentTimeMillis() + 6000; store.save()
            val time = restored.reminderAt
            call("removeReminder", restored); undoReminder()
            check(restored.reminderAt == time)
            call("showList")
        }
        SystemClock.sleep(8500)
        main {
            val restored = store.notes.single()
            check(Reminders.unread(targetContext, restored)) { "Restored alarm did not fire" }
            check(targetContext.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == restored.id })
            call("removeReminder", restored); undoReminder()
            check(Reminders.unread(targetContext, restored)) { "Undo lost the unread reminder state" }
        }
    }
    private fun screenshot(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: return
        java.io.File(targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        try {
            check(targetContext.packageName.endsWith(".copytests"))
            activity = startActivitySync(Intent().setClassName(targetContext.packageName, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitForIdleSync()
            verifyRefinements()
            verifyReminderUndo()
            val store = field("store") as Store
            var note = Note(text = "# Reminder\n**Привет** 🙂  \nraw text ")
            main {
                store.prefs.edit().clear().putString("start", "list").commit()
                store.notes.clear(); store.notes.add(note); store.save()
                call("open", note, false, false)
                call("chooseReminder", note)
            }
            SystemClock.sleep(600)
            screenshot("reminder-picker.png")
            lateinit var minuteWheel: NumberPicker
            main {
                minuteWheel = views((field("reminderDialog") as Dialog).window!!.decorView).filterIsInstance<NumberPicker>()[2]
                minuteWheel.value = 59
                minuteWheel.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
            }
            SystemClock.sleep(500)
            main {
                minuteWheel.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
                check(minuteWheel.value == 0) { "Minutes did not wrap after 59" }
                minuteWheel.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
            }
            SystemClock.sleep(500)
            main {
                minuteWheel.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_UP))
                check(minuteWheel.value == 59) { "Minutes did not wrap before 0" }
            }
            main {
                val dialog = field("reminderDialog") as Dialog
                val wheels = views(dialog.window!!.decorView).filterIsInstance<NumberPicker>()
                if (android.os.Build.VERSION.SDK_INT >= 30) check(activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true)
                check(wheels.size == 3)
                check(!wheels[0].wrapSelectorWheel && wheels[0].maxValue in 365..366)
                check(wheels[1].wrapSelectorWheel && wheels[2].wrapSelectorWheel)
                check(wheels[0].displayedValues[0] == activity.getString(R.string.today))
                check(wheels[0].displayedValues[1] == activity.getString(R.string.tomorrow))
                val now = java.time.ZonedDateTime.now()
                val dates = reminderDates(now)
                val locale = activity.resources.configuration.locales[0]
                for (index in listOf(2, 17, 45, dates.lastIndex)) {
                    check(wheels[0].displayedValues[index] == reminderDateLabel(dates[index], dates.first(), locale))
                }
                click(dialog, R.string.cancel); check(note.reminderAt == 0L)
                call("chooseReminder", note)
                click(field("reminderDialog") as Dialog, R.string.set_reminder)
                check(note.reminderAt > System.currentTimeMillis())
                check(Store(targetContext).notes.single().reminderAt == note.reminderAt)
                check(field("screen") == "editor")
            }
            for (layout in listOf("grid", "compact")) for (direction in listOf("normal", "reverse")) {
                main {
                    store.prefs.edit().putString("layout", layout).putString("list_direction", direction).commit()
                    note.reminderAt = System.currentTimeMillis() + 600000; store.save()
                    call("discardList", true); call("showList")
                }
                SystemClock.sleep(350)
                main {
                    val grid = views(activity.window.decorView).filterIsInstance<AnimatedNotesGrid>().single()
                    val badge = grid.cards().single().findViewWithTag<View>("note-reminder-badge")
                    check(badge.visibility == View.VISIBLE)
                    check(badge.top >= 0 && badge.bottom <= badge.parent.let { (it as View).height } + 14 * activity.resources.displayMetrics.density)
                    badge.performClick(); check((field("reminderDialog") as Dialog).isShowing)
                    click(field("reminderDialog") as Dialog, R.string.cancel)
                    badge.performLongClick(); check(note.reminderAt == 0L)
                    check(Store(targetContext).notes.single().reminderAt == 0L)
                }
            }
            main {
                note.reminderAt = System.currentTimeMillis() + 600000; store.save()
                val original = note.copy()
                call("open", note, false, false)
                note.text = "Changed"; note.reminderAt += 600000; store.save()
                call("discardEditorChanges")
                check(note == original && Store(targetContext).notes.single() == original)
                call("delete", note, "confirm_quick", false, {})
                check(store.notes.isEmpty())
                call("showList"); call("showPendingUndo")
                views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.undo) }.performClick()
                note = store.notes.single()
                check(note == original && Store(targetContext).notes.single() == original)
                note.reminderAt = System.currentTimeMillis() + 6000; store.save()
                call("showList")
            }
            SystemClock.sleep(8500)
            screenshot("reminder-unread.png")
            main {
                check(Reminders.unread(targetContext, note)) { "Alarm not delivered" }
                val notification = targetContext.getSystemService(NotificationManager::class.java).activeNotifications.single { it.tag == note.id }
                check(notification.notification.extras.getCharSequence("android.bigText").toString() == note.text)
                notification.notification.contentIntent.send()
            }
            SystemClock.sleep(600)
            main {
                check(field("screen") == "editor")
                check((field("current") as Note).id == note.id)
                check(!Reminders.unread(targetContext, note))
                check(!Reminders.hasReminder(targetContext, note))
                call("showList")
                check(grid().cards().single().findViewWithTag<View>("note-reminder-badge").visibility == View.GONE)
                call("open", note, false, false)
                check(targetContext.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag == note.id })
                call("chooseReminder", note)
                click(field("reminderDialog") as Dialog, R.string.set_exit)
                check(activity.isFinishing)
            }
            SystemClock.sleep(350)
            for (theme in listOf("light", "dark", "amoled")) for (language in listOf("en", "ru")) {
                targetContext.getSharedPreferences("settings", 0).edit().putString("theme", theme).putString("language", language).putInt("text_scale", if (language == "ru") 150 else 100).commit()
                activity = startActivitySync(Intent().setClassName(targetContext.packageName, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                waitForIdleSync()
                main { call("open", (field("store") as Store).notes.single(), false, false); call("chooseReminder", (field("store") as Store).notes.single()) }
                SystemClock.sleep(500)
                screenshot("reminder-$theme-$language.png")
                main {
                    val dialog = field("reminderDialog") as Dialog
                    for (label in listOf(R.string.cancel, R.string.set_reminder, R.string.set_exit)) {
                        val button = views(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(label) }
                        for (line in 0 until button.layout.lineCount - 1) {
                            val end = button.layout.getLineEnd(line)
                            check(end == button.text.length || button.text[end - 1].isWhitespace() || button.text[end].isWhitespace()) { "Button splits a word: $theme $language ${button.text}" }
                        }
                    }
                    click(dialog, R.string.cancel); activity.finishAndRemoveTask()
                }
                SystemClock.sleep(250)
            }
            results.putString("stream", "PASS: reminder removal undo/toggle/expiry/relaunch/latest-action/real delivery, toolbar order, confirmation cancellation/acceptance, all filter cycles, filtered reordering, lifecycle reset/rotation, completed badge removal, reminder picker, cyclic wheels, persistence, badges in both layouts/directions, removal, real alarm delivery, exact notification text, notification navigation, read state, discard, deletion undo, set and exit, English/Russian and all themes with enlarged text.\n")
            finish(-1, results)
        } catch (e: Throwable) {
            results.putString("stream", "FAILED: ${e.stackTraceToString()}\n"); finish(1, results)
        }
    }
}
