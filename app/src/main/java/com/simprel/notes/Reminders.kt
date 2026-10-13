package com.simprel.notes

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import java.util.Locale

/** Alarm identity includes the UUID; notification state is separate from autosaved text. */
object Reminders {
    private fun prefs(c: Context) = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private fun intent(c: Context, id: String) = Intent(c, ReminderReceiver::class.java)
        .setAction("com.simprel.notes.REMIND").setData(Uri.parse("simprel://reminder/$id"))
    private fun alarm(c: Context, id: String, time: Long = 0) = PendingIntent.getBroadcast(c, 0,
        intent(c, id).putExtra("time", time), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun hasReminder(c: Context, note: Note): Boolean {
        val p = prefs(c)
        return note.reminderAt > 0 && !(p.getLong("fired:${note.id}", 0) == note.reminderAt && p.getLong("seen:${note.id}", 0) == note.reminderAt)
    }
    fun unread(c: Context, note: Note) = note.reminderAt > 0 && prefs(c).getLong("fired:${note.id}", 0) == note.reminderAt &&
        prefs(c).getLong("seen:${note.id}", 0) != note.reminderAt
    fun seen(c: Context, note: Note) {
        if (unread(c, note)) prefs(c).edit().putLong("seen:${note.id}", note.reminderAt).apply()
        c.getSystemService(NotificationManager::class.java).cancel(note.id, 1)
    }
    fun sync(c: Context, notes: List<Note>, force: Boolean = false) {
        val p = prefs(c)
        val manager = c.getSystemService(AlarmManager::class.java)
        val desired = notes.filter { it.text.isNotBlank() && it.reminderAt > 0 }.associateBy { it.id }
        val old = p.getStringSet("scheduled", emptySet())!!.toSet()
        for (id in old - desired.keys) {
            manager.cancel(alarm(c, id)); c.getSystemService(NotificationManager::class.java).cancel(id, 1)
            p.edit().remove("at:$id").apply()
        }
        desired.forEach { (id, note) ->
            if (force || p.getLong("at:$id", 0) != note.reminderAt) {
                manager.cancel(alarm(c, id))
                if (p.getLong("at:$id", 0) != note.reminderAt) c.getSystemService(NotificationManager::class.java).cancel(id, 1)
                if (p.getLong("fired:$id", 0) != note.reminderAt) {
                    val operation = alarm(c, id, note.reminderAt)
                    val time = maxOf(note.reminderAt, System.currentTimeMillis() + 100)
                    try {
                        if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
                        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
                    } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation) }
                }
                p.edit().putLong("at:$id", note.reminderAt).apply()
            }
        }
        p.edit().putStringSet("scheduled", desired.keys).apply()
    }
    fun deliver(c: Context, id: String, time: Long) {
        val note = Store(c).notes.find { it.id == id && it.reminderAt == time && time > 0 } ?: return
        if (time > System.currentTimeMillis()) return
        val p = prefs(c)
        if (p.getLong("fired:$id", 0) == time) return
        p.edit().putLong("fired:$id", time).commit()
        val language = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "system") ?: "system"
        val locale = Locale.forLanguageTag(resolveLanguage(language, c.resources.configuration.locales[0].language))
        val local = c.createConfigurationContext(android.content.res.Configuration(c.resources.configuration).apply { setLocale(locale) })
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("reminders", local.getString(R.string.reminders), NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(c, MainActivity::class.java).setAction("com.simprel.notes.OPEN_REMINDER")
            .setData(Uri.parse("simprel://note/$id")).putExtra("note_id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(c, "reminders").setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(local.getString(R.string.reminder)).setContentText(note.text)
            .setStyle(Notification.BigTextStyle().bigText(note.text)).setContentIntent(tap).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER).build()
        try { manager.notify(id, 1, notification) } catch (_: SecurityException) { }
        c.sendBroadcast(Intent("${c.packageName}.REMINDERS_CHANGED").setPackage(c.packageName))
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.simprel.notes.REMIND") {
            Reminders.deliver(context, intent.data?.lastPathSegment ?: return, intent.getLongExtra("time", 0))
        } else Reminders.sync(context, Store(context).notes, force = true)
    }
}
