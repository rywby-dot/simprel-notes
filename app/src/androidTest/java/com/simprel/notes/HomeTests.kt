package com.simprel.notes

import android.app.*
import android.appwidget.*
import android.content.*
import android.os.*
import android.view.*
import android.widget.*

/** A disposable host surface; it renders the same RemoteViews that launchers receive. */
class HomePreviewActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.rgb(100, 110, 125)); setPadding(0, (40*d).toInt(), 0, 0) }
        val notes = Store(this).notes
        for (kind in 0..4) {
            val width = if (kind < 2) 72 else 152
            val height = if (kind < 3) 72 else 152
            val view = HomeWidgets.build(this, kind, notes, width, height).apply(this, root)
            val x = when (kind) { 0, 3 -> 12; 1 -> 92; else -> 172 }
            val y = if (kind < 3) 120 else 220
            root.addView(view, FrameLayout.LayoutParams((width*d).toInt(), (height*d).toInt()).apply { leftMargin = (x*d).toInt(); topMargin = (y*d).toInt() })
        }
        setContentView(root)
    }
}

class HomeTests : Instrumentation() {
    private lateinit var activity: MainActivity
    private var mainMonitor: ActivityMonitor? = null
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try {
            (mainMonitor?.lastActivity as? MainActivity)?.takeIf { !it.isDestroyed }?.let { activity = it }
            action()
        } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }; waitForIdleSync()
    }
    private fun waitUntil(label: String, action: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 6000
        do {
            var result = false; main { result = action() }; if (result) return
            fun allow(n: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (n == null) return false
                if (n.viewIdResourceName?.endsWith(":id/permission_allow_foreground_only_button") == true) return n.performAction(16)
                for (i in 0 until n.childCount) if (allow(n.getChild(i))) return true
                return false
            }
            allow(uiAutomation.rootInActiveWindow); SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < limit)
        error("Timed out: $label")
    }
    private fun screenshot(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: return
        java.io.File(targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        var testHost: AppWidgetHost? = null
        var boundHost = false
        try {
            check(targetContext.packageName.endsWith(".copytests"))
            targetContext.getSharedPreferences("settings", 0).edit().clear().putString("start", "list").putString("language", "en").commit()
            activity = startActivitySync(HomeShortcuts.intent(targetContext, HomeShortcuts.NEW_NOTE)) as MainActivity
            waitForIdleSync()
            mainMonitor = addMonitor(MainActivity::class.java.name, null, false)
            var store = field("store") as Store
            val old = Note(text = "# Old\nСтарое 🙂", reminderAt = 1000)
            val newest = Note(text = "# Newest\nLatest **raw text**  ", reminderAt = 3000)
            main {
                check(field("screen") == "editor" && (field("current") as Note).text.isEmpty())
                store.notes.clear(); store.notes.addAll(listOf(newest, old))
                val reminderPrefs = targetContext.getSharedPreferences("reminders", 0)
                reminderPrefs.edit().putLong("fired:${old.id}", old.reminderAt).putLong("fired:${newest.id}", newest.reminderAt).commit()
                store.save(); store.prefs.edit().putString("last", old.id).commit()
                val providers = AppWidgetManager.getInstance(targetContext).installedProviders.filter { it.provider.packageName == targetContext.packageName }
                check(providers.map { it.provider.className }.toSet() == HomeWidgets.providers.map { it.name }.toSet()) { "Launcher did not discover all five widgets" }
                val shortcuts = targetContext.packageManager.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(targetContext.packageName), 0)
                check(shortcuts.map { it.activityInfo.name }.toSet() == setOf(NewNoteShortcut::class.java.name, NewVoiceShortcut::class.java.name))
                for (voice in listOf(false, true)) {
                    val result = HomeShortcuts.result(targetContext, voice)
                    val intent = result.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)!!
                    check(intent.component!!.packageName == targetContext.packageName)
                    check(intent.action == if (voice) HomeShortcuts.NEW_VOICE else HomeShortcuts.NEW_NOTE)
                    check(result.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)!!.isNotEmpty())
                }
                val dynamic = targetContext.getSystemService(android.content.pm.ShortcutManager::class.java).dynamicShortcuts
                check(dynamic.map { it.id }.toSet() == setOf("new_note", "new_voice"))
                fun render(kind: Int) = HomeWidgets.build(targetContext, kind, store.notes, if (kind < 2) 72 else 152, if (kind < 3) 72 else 152).apply(activity, null)
                val last = render(3)
                check(last.findViewById<TextView>(R.id.widget_content).text.toString().contains("Старое"))
                val unread = render(4)
                check(unread.findViewById<TextView>(R.id.widget_content).text.toString().contains("Latest raw text"))
                check(unread.findViewById<TextView>(R.id.widget_count).text == "2" && unread.findViewById<View>(R.id.widget_count).visibility == View.VISIBLE)
                Reminders.seen(targetContext, newest)
                val next = render(4)
                check(next.findViewById<TextView>(R.id.widget_content).text.toString().contains("Старое"))
                check(next.findViewById<View>(R.id.widget_count).visibility == View.GONE)
                Reminders.seen(targetContext, old)
                check(render(4).findViewById<TextView>(R.id.widget_content).text == activity.getString(R.string.widget_no_unread))
                reminderPrefs.edit().remove("seen:${old.id}").remove("seen:${newest.id}").commit()
            }
            // Bind real widgets when the device exposes the shell's standard test permission.
            val manager = AppWidgetManager.getInstance(targetContext)
            val host = AppWidgetHost(targetContext, 4217); testHost = host
            val ids = mutableListOf<Int>()
            try {
                uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
                boundHost = HomeWidgets.providers.all { provider ->
                    val id = host.allocateAppWidgetId(); ids.add(id)
                    manager.bindAppWidgetIdIfAllowed(id, ComponentName(targetContext.packageName, provider.name))
                }
            } catch (_: SecurityException) { boundHost = false }
            finally { uiAutomation.dropShellPermissionIdentity() }
            if (boundHost) {
                lateinit var unreadHost: AppWidgetHostView
                main {
                    host.startListening()
                    ids.forEachIndexed { kind, id ->
                        val view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                        view.updateAppWidgetSize(null, if (kind < 2) 72 else 152, if (kind < 3) 72 else 152, if (kind < 2) 72 else 152, if (kind < 3) 72 else 152)
                        if (kind == 4) unreadHost = view
                    }
                    HomeWidgets.updateAll(targetContext, store.notes)
                }
                waitUntil("host receives unread count") { unreadHost.findViewById<TextView>(R.id.widget_count)?.text?.toString() == "2" }
                main { Reminders.seen(targetContext, newest) }
                waitUntil("host refreshes after reading") {
                    unreadHost.findViewById<View>(R.id.widget_count)?.visibility == View.GONE && unreadHost.findViewById<TextView>(R.id.widget_content)?.text?.contains("Старое") == true
                }
                main {
                    targetContext.getSharedPreferences("reminders",0).edit().remove("seen:${newest.id}").commit()
                    HomeWidgets.updateAll(targetContext, store.notes)
                }
            } else { host.deleteHost(); testHost = null }
            for (theme in listOf("light", "dark", "amoled")) for (language in listOf("en", "ru")) {
                main {
                    store.prefs.edit().putString("theme", theme).putString("language", language).putInt("text_scale", if (language == "ru") 150 else 100).commit()
                    for (kind in 0..4) {
                        val width = if (kind < 2) 72 else 152
                        val height = if (kind < 3) 72 else 152
                        val view = HomeWidgets.build(targetContext, kind, store.notes, width, height).apply(activity, null)
                        view.measure(View.MeasureSpec.makeMeasureSpec(width*3, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height*3, View.MeasureSpec.EXACTLY)); view.layout(0,0,width*3,height*3)
                        check(view.width > 0)
                    }
                }
                val preview = startActivitySync(Intent(targetContext, HomePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomePreviewActivity
                waitForIdleSync(); SystemClock.sleep(150); screenshot("widgets-$theme-$language.png")
                main { preview.finish() }; waitForIdleSync()
            }
            main { store = field("store") as Store; store.prefs.edit().putString("theme", "light").putString("language", "en").commit() }
            // Actual RemoteViews PendingIntents, including a warm existing editor with user text.
            var before: String? = null
            main { before = (field("current") as? Note)?.id }
            main {
                val views = HomeWidgets.build(targetContext, 0, store.notes, 72,72).apply(activity, null)
                views.findViewById<View>(R.id.widget_text_action).performClick()
            }
            waitUntil("text widget launch") { (field("current") as? Note)?.let { it.id != before && field("screen") == "editor" } == true }
            val textId = (field("current") as Note).id
            main {
                (field("activeEditor") as EditText).setText("Keep text before voice")
                val views = HomeWidgets.build(targetContext, 2, store.notes, 152,72).apply(activity, null)
                views.findViewById<View>(R.id.widget_voice_action).performClick()
            }
            waitUntil("voice widget starts recording") { field("voiceAudio") is VoiceAudio && (field("voiceAudio") as VoiceAudio).recording }
            main {
                check((field("current") as Note).id != textId)
                check((field("store") as Store).notes.single { it.id == textId }.text == "Keep text before voice")
                if (android.os.Build.VERSION.SDK_INT >= 30) check((field("reminderDialog") as Dialog).window!!.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true)
                (field("reminderDialog") as Dialog).dismiss()
            }
            waitUntil("recorder closes") { field("voiceAudio") == null }
            main {
                val view = HomeWidgets.build(targetContext, 4, store.notes,152,152).apply(activity,null)
                view.findViewById<View>(R.id.widget_root).performClick()
            }
            waitUntil("unread widget targets UUID") { (field("current") as Note).id == newest.id }
            main {
                check(!Reminders.unread(targetContext,newest))
                store.notes.clear(); store.save()
                val view = HomeWidgets.build(targetContext, 3, store.notes,152,152).apply(activity,null)
                check(view.findViewById<TextView>(R.id.widget_content).text == HomeWidgets.localized(targetContext).getString(R.string.empty_notes))
                activity.finishAndRemoveTask()
            }
            results.putString("stream", "Host binding: $boundHost. PASS: five discoverable widget providers; Key Mapper CREATE_SHORTCUT contract; two launcher shortcuts; real RemoteViews inflation in all themes/languages; last-note identity; newest unread/count/read/empty; actual text/voice widget launches; warm editor override; automatic microphone/hidden keyboard; unread widget UUID navigation.\n")
            finish(-1, results)
        } catch (e: Throwable) { results.putString("stream", "FAILED: ${e.stackTraceToString()}\n"); finish(1,results) }
        finally { mainMonitor?.let { removeMonitor(it) }; testHost?.stopListening(); testHost?.deleteHost() }
    }
}
