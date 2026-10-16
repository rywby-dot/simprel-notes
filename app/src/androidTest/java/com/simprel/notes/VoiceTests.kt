package com.simprel.notes

import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/** Attachment, recorder, card routing and media lifecycle regressions in the isolated clone. */
class VoiceTests : Instrumentation() {
    private lateinit var activity: MainActivity
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun call(name: String, vararg args: Any) = MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(activity, *args)
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }; waitForIdleSync()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun dialog() = field("reminderDialog") as Dialog
    private fun button(id: Int) { views(dialog().window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(id) }.performClick() }
    private fun toggle() { dialog().window!!.decorView.findViewWithTag<View>("voice-toggle").performClick() }
    private fun listen() { dialog().window!!.decorView.findViewWithTag<View>("voice-preview-toggle").performClick() }
    private fun waitUntil(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6000
        do {
            var okay = false
            main { okay = condition() }
            if (okay) return
            // Use the normal Android permission UI; do not depend on OEM shell grant support.
            fun allow(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.viewIdResourceName?.let { it.endsWith(":id/permission_allow_foreground_only_button") || it.endsWith(":id/permission_allow_button") } == true)
                    return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                for (i in 0 until node.childCount) if (allow(node.getChild(i))) return true
                return false
            }
            allow(uiAutomation.rootInActiveWindow)
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Timed out: $label")
    }
    private fun recorder() = waitUntil("recorder") { field("voiceAudio") != null }
    private fun screenshot(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: return
        File(targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun wave() = field("voiceWave") as VoiceWaveform
    private fun drag(from: Float, to: Float) {
        val wave = wave(); val density = activity.resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()
        for ((action, fraction) in listOf(android.view.MotionEvent.ACTION_DOWN to from, android.view.MotionEvent.ACTION_MOVE to to, android.view.MotionEvent.ACTION_UP to to)) {
            val event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, 14 * density + fraction * (wave.width - 28 * density), wave.height / 2f, 0)
            check(wave.dispatchTouchEvent(event)); event.recycle()
        }
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        try {
            check(targetContext.packageName.endsWith(".copytests"))
            targetContext.getSharedPreferences("settings", 0).edit().clear().putString("start", "list").putString("language", "en").commit()
            activity = startActivitySync(Intent().setClassName(targetContext.packageName, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            waitForIdleSync()
            if (android.os.Build.VERSION.SDK_INT >= 33 && targetContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                main { activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 22) }
                waitUntil("notification permission") { targetContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            }
            var store = field("store") as Store
            val neighbor = Note(text = "Keep neighbor\nПривет 🙂  ")
            val note = Note()
            main {
                store.notes.clear(); store.notes.add(neighbor); store.save()
                call("open", note, false, false); call("chooseVoice")
            }
            recorder()
            SystemClock.sleep(1100)
            screenshot("voice-recording.png")
            var firstDuration = 0L
            main {
                check((field("voiceAudio") as VoiceAudio).recording)
                toggle()
                firstDuration = (field("voiceAudio") as VoiceAudio).durationMs
                check(firstDuration >= 500)
                check(views(dialog().window!!.decorView).none { it is SeekBar }) { "Trim sliders still visible" }
                check(dialog().window!!.decorView.findViewWithTag<View>("voice-preview-toggle").visibility == View.VISIBLE)
                if (android.os.Build.VERSION.SDK_INT >= 30) check(dialog().window!!.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true)
                listen()
            }
            waitUntil("preview playback") { (wave().playhead ?: 0f) > .03f }
            main {
                val args = Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .55f) }
                check(wave().performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
                listen() // Pause playback, not recording.
                check(!(field("voiceAudio") as VoiceAudio).recording)
                toggle() // The separate microphone appends.
            }
            SystemClock.sleep(700)
            var duration = 0L
            main {
                toggle(); duration = (field("voiceAudio") as VoiceAudio).durationMs
                check(duration > firstDuration + 200)
                drag(0f, .2f); drag(1f, .8f)
                check(kotlin.math.abs(wave().start - .2f) < .001f && kotlin.math.abs(wave().end - .8f) < .001f)
                drag(.4f, .5f)
                check(kotlin.math.abs(wave().start - .2f) < .001f && kotlin.math.abs(wave().end - .8f) < .001f) { "Seeking changed trim" }
                check(kotlin.math.abs(wave().playhead!! - .5f) < .001f)
            }
            SystemClock.sleep(350); screenshot("voice-trim.png")
            main { button(R.string.voice_save) }
            waitUntil("save attachment") { field("voiceAudio") == null }
            var originalFile = ""
            main {
                check(field("screen") == "editor" && (field("current") as Note).id == note.id)
                check(store.notes.map { it.id } == listOf(note.id, neighbor.id)) { "New voice note is not first" }
                check(note.text.isEmpty() && note.audioFile.isNotEmpty() && note.hasContent)
                check(Store(targetContext).notes.first() == note)
                check(note.audioPeaks.size == 48)
                originalFile = note.audioFile
                check(kotlin.math.abs((store.audioFile(originalFile).length() - 44) - duration * .6 * VoiceAudio.RATE * 2 / 1000) < 200)
                check(activity.window.decorView.findViewWithTag<View>("editor-voice").visibility == View.VISIBLE)
                (field("activeEditor") as EditText).setText("# Mixed note\n**Текст** 🙂  ")
                check(store.notes.size == 2 && note.text.endsWith("  ") && note.audioFile == originalFile)
                call("open", note, false, false) // Establish a new immutable entry snapshot.
                activity.window.decorView.findViewWithTag<View>("editor-voice").performClick()
            }
            recorder()
            main {
                check(!(field("voiceAudio") as VoiceAudio).recording)
                drag(0f, .15f); button(R.string.cancel)
            }
            waitUntil("cancel") { field("voiceAudio") == null }
            main {
                check(note.audioFile == originalFile && store.notes.size == 2)
                call("editVoice")
            }
            recorder()
            main { drag(0f, .15f); button(R.string.voice_save) }
            waitUntil("replace audio") { field("voiceAudio") == null }
            var replacement = ""
            main {
                replacement = note.audioFile
                check(replacement != originalFile && store.notes.map { it.id } == listOf(note.id, neighbor.id))
                check(store.audioFile(originalFile).exists()) { "Entry snapshot audio removed too early" }
                call("discardEditorChanges")
                check(note.audioFile == originalFile && note.text == "# Mixed note\n**Текст** 🙂  ")
                check(store.audioFile(originalFile).exists() && !store.audioFile(replacement).exists())
                check(Store(targetContext).notes.first().audioFile == originalFile)
            }
            val originalBytes = store.audioFile(originalFile).readBytes()
            main { activity.window.decorView.findViewWithTag<View>("record-voice").performClick() }
            recorder()
            main {
                check((field("voiceAudio") as VoiceAudio).recording) { "Toolbar microphone did not start a fresh recording" }
                check(note.audioFile == originalFile && store.audioFile(originalFile).readBytes().contentEquals(originalBytes))
                check(Store(targetContext).notes.first().audioFile == originalFile)
            }
            SystemClock.sleep(400)
            main { button(R.string.cancel) }
            waitUntil("cancel replacement recording") { field("voiceAudio") == null }
            main {
                check(note.audioFile == originalFile && store.audioFile(originalFile).readBytes().contentEquals(originalBytes))
                activity.window.decorView.findViewWithTag<View>("record-voice").performClick()
            }
            recorder(); SystemClock.sleep(500)
            main {
                check(note.audioFile == originalFile && store.audioFile(originalFile).readBytes().contentEquals(originalBytes))
                button(R.string.voice_save)
            }
            waitUntil("save fresh replacement") { field("voiceAudio") == null }
            main {
                val newFile = note.audioFile
                check(newFile != originalFile && store.notes.map { it.id } == listOf(note.id, neighbor.id))
                check(Store(targetContext).notes.first().audioFile == newFile)
                check(store.audioFile(newFile).length() < originalBytes.size) { "New recording appended the old audio" }
                check(store.audioFile(originalFile).readBytes().contentEquals(originalBytes))
                call("discardEditorChanges")
                check(note.audioFile == originalFile && !store.audioFile(newFile).exists())
            }
            for (layout in listOf("grid", "compact")) for (direction in listOf("normal", "reverse")) {
                main {
                    store.prefs.edit().putString("layout", layout).putString("list_direction", direction).commit()
                    call("discardList", true); call("showList")
                }
                SystemClock.sleep(300)
                main {
                    val grid = views(activity.window.decorView).filterIsInstance<AnimatedNotesGrid>().single()
                    val card = grid.cards().single { it.tag == note.id }
                    val attachment = card.findViewWithTag<View>("note-voice")
                    check(attachment.visibility == View.VISIBLE && attachment.width > 0)
                    if (layout == "compact") check(attachment.right <= card.width / 3 + 1)
                    val text = views(card).filterIsInstance<NotePreview>().single()
                    check(text.text.contains("Текст") && text.visibility == View.VISIBLE)
                    if (layout == "grid") check(text.paddingTop >= attachment.bottom - 1)
                    attachment.performClick()
                    check(field("screen") == "list" && field("playingId") == note.id)
                    check((field("player") as android.media.MediaPlayer).isPlaying)
                }
                if (layout == "grid" && direction == "normal") screenshot("voice-card-player.png")
                main {
                    val panel = field("playerPanel") as ViewGroup
                    val seek = views(panel).filterIsInstance<SeekBar>().single()
                    val position = IntArray(2); seek.getLocationOnScreen(position)
                    val time = SystemClock.uptimeMillis()
                    for ((index, entry) in listOf(android.view.MotionEvent.ACTION_DOWN to .2f, android.view.MotionEvent.ACTION_MOVE to .7f, android.view.MotionEvent.ACTION_UP to .7f).withIndex()) {
                        val event = android.view.MotionEvent.obtain(time, time + index * 60, entry.first, position[0] + seek.width * entry.second, position[1] + seek.height / 2f, 0)
                        activity.dispatchTouchEvent(event); event.recycle()
                    }
                    check(field("screen") == "list" && field("playerPanel") === panel) { "Seeking triggered list navigation" }
                    panel.getChildAt(2).performClick()
                    check(!(field("player") as android.media.MediaPlayer).isPlaying)
                    panel.getChildAt(2).performClick()
                    val player = field("player") as android.media.MediaPlayer
                    player.seekTo((player.duration - 100).coerceAtLeast(0))
                }
                waitUntil("mini player closes on completion") { field("player") == null && field("playerPanel") == null }
                main {
                    val card = views(activity.window.decorView).filterIsInstance<AnimatedNotesGrid>().single().cards().single { it.tag == note.id }
                    card.performClick()
                    check(field("screen") == "editor" && (field("current") as Note).id == note.id)
                }
            }
            main { call("showList") }
            // Drain NotificationManager cancellations queued by the last editor opening.
            SystemClock.sleep(500)
            main {
                note.reminderAt = System.currentTimeMillis() - 100; store.save()
            }
            // Saving a new reminder cancels its previous notification asynchronously too.
            SystemClock.sleep(500)
            main { Reminders.deliver(targetContext, note.id, note.reminderAt) }
            waitUntil("voice reminder notification") { targetContext.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.tag == note.id } }
            main {
                val manager = targetContext.getSystemService(android.app.NotificationManager::class.java)
                val notification = manager.activeNotifications.firstOrNull { it.tag == note.id }?.notification
                check(manager.areNotificationsEnabled()) { "Enable notifications for the isolated test app" }
                run {
                    check(notification != null && notification.contentView != null)
                    val content = notification!!.contentView.apply(activity, null)
                    check(content.findViewById<TextView>(R.id.notification_voice_text).text.toString() == note.text)
                    check(content.findViewById<android.widget.ImageView>(R.id.notification_voice_wave).drawable != null)
                }
                note.reminderAt = 0; store.save()
                call("showList")
                call("reorder", listOf(neighbor.id, note.id))
                call("delete", note, "confirm_quick", false, { })
                check(store.notes.none { it.id == note.id } && store.audioFile(originalFile).exists())
                call("showPendingUndo")
                views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.undo) }.performClick()
                check(store.notes.last() == note && Store(targetContext).notes.last() == note)
                call("playVoice", store.notes.last())
                (field("playerPanel") as ViewGroup).getChildAt(0).performClick()
                check(field("player") == null)
            }
            val pcm = File(targetContext.cacheDir, "test-voice.pcm"); val wav = File(targetContext.cacheDir, "test-voice.wav")
            try {
                val samples = ByteArray(48000) { (it % 251).toByte() }; pcm.writeBytes(samples)
                VoiceAudio.writeWave(pcm, wav, .25f, .75f)
                check(wav.readBytes().drop(44).toByteArray().contentEquals(samples.copyOfRange(12000, 36000)))
            } finally { pcm.delete(); wav.delete() }
            for (theme in listOf("light", "dark", "amoled")) for (language in listOf("en", "ru")) {
                main { activity.finishAndRemoveTask() }; SystemClock.sleep(150)
                targetContext.getSharedPreferences("settings", 0).edit().putString("theme", theme).putString("language", language).putInt("text_scale", if (language == "ru") 150 else 100).commit()
                activity = startActivitySync(Intent().setClassName(targetContext.packageName, MainActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                waitForIdleSync(); store = field("store") as Store
                main { call("open", store.notes.single { it.id == note.id }, false, false); call("editVoice") }
                recorder(); SystemClock.sleep(350); screenshot("voice-$theme-$language.png")
                main {
                    check(views(dialog().window!!.decorView).none { it is SeekBar })
                    for (id in listOf(R.string.cancel, R.string.voice_save, R.string.save_exit)) {
                        val label = views(dialog().window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(id) }
                        check(label.width > 0 && label.height >= 48 * activity.resources.displayMetrics.density)
                    }
                    button(R.string.cancel)
                }
                waitUntil("cancel themed dialog") { field("voiceAudio") == null }
                main {
                    call("chooseReminder", store.notes.single { it.id == note.id })
                    val dates = views(dialog().window!!.decorView).filterIsInstance<android.widget.NumberPicker>()[0].displayedValues
                    check(dates[0] == activity.getString(R.string.today) && dates[1] == activity.getString(R.string.tomorrow))
                    val today = java.time.LocalDate.now()
                    val locale = activity.resources.configuration.locales[0]
                    for (index in listOf(2, 17, 45, dates.lastIndex)) {
                        check(dates[index] == reminderDateLabel(today.plusDays(index.toLong()), today, locale))
                    }
                    button(R.string.cancel)
                }
                waitUntil("cancel reminder dialog") { field("reminderDialog") == null }
            }
            main { call("editVoice") }; recorder()
            var retainedDuration = 0L
            main { drag(0f, .1f); drag(1f, .9f); retainedDuration = (field("voiceAudio") as VoiceAudio).durationMs }
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            main { activity.recreate() }
            activity = monitor.waitForActivityWithTimeout(5000) as? MainActivity ?: error("Rotation did not recreate activity")
            removeMonitor(monitor); waitForIdleSync(); recorder()
            main {
                check(!(field("voiceAudio") as VoiceAudio).recording)
                check((field("voiceAudio") as VoiceAudio).durationMs == retainedDuration)
                check(kotlin.math.abs(wave().start - .1f) < .001f && kotlin.math.abs(wave().end - .9f) < .001f)
                toggle()
            }
            SystemClock.sleep(300)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("input keyevent 3")).use { it.readBytes() }
            SystemClock.sleep(350)
            main { check(!(field("voiceAudio") as VoiceAudio).recording) }
            main { activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            SystemClock.sleep(500)
            main { button(R.string.save_exit) }
            waitUntil("save and exit") { activity.isFinishing }
            results.putString("stream", "PASS: attachment-only and mixed notes; canonical insertion; same UUID/no duplicates; real microphone pause/append; preview and seek; draggable trim without sliders; toolbar fresh recording replaces only after Save; cancel/replacement/discard/files; persistence; grid/compact and normal/reverse routing; mini player completion/close/pause; reminders with custom waveform notification; deletion undo/order; rotation/background; English/Russian and all themes; localized weekday labels except Today/Tomorrow; save and exit.\n")
            finish(-1, results)
        } catch (e: Throwable) { results.putString("stream", "FAILED: ${e.stackTraceToString()}\n"); finish(1, results) }
    }
}
