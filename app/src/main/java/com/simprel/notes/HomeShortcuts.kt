package com.simprel.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle

/** Stable, distinct actions are also important for Key Mapper's PendingIntent caching. */
object HomeShortcuts {
    const val NEW_NOTE = "com.simprel.notes.NEW_NOTE"
    const val NEW_VOICE = "com.simprel.notes.NEW_VOICE"
    const val SHOW_LIST = "com.simprel.notes.SHOW_LIST"
    fun intent(c: Context, action: String): Intent = Intent(action)
        .setClassName(c.packageName, MainActivity::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    fun result(c: Context, voice: Boolean): Intent {
        val local = HomeWidgets.localized(c)
        return Intent().putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent(c, if (voice) NEW_VOICE else NEW_NOTE))
            .putExtra(Intent.EXTRA_SHORTCUT_NAME, local.getString(if (voice) R.string.new_voice_note else R.string.new_note))
            .putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(c, if (voice) R.drawable.ic_shortcut_voice else R.drawable.ic_new_note))
    }
    fun publish(c: Context) {
        val local = HomeWidgets.localized(c)
        val entries = listOf(false, true).map { voice ->
            ShortcutInfo.Builder(c, if (voice) "new_voice" else "new_note")
                .setShortLabel(local.getString(if (voice) R.string.new_voice_note else R.string.new_note))
                .setIcon(Icon.createWithResource(c, if (voice) R.drawable.ic_shortcut_voice else R.drawable.ic_new_note))
                .setActivity(android.content.ComponentName(c.packageName, MainActivity::class.java.name))
                .setIntent(intent(c, if (voice) NEW_VOICE else NEW_NOTE)).build()
        }
        try { c.getSystemService(ShortcutManager::class.java).dynamicShortcuts = entries } catch (_: IllegalStateException) { /* User storage can be locked during boot. */ }
    }
}

/** Legacy shortcut contract consumed by Key Mapper and other shortcut pickers. */
class NewNoteShortcut : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); setResult(RESULT_OK, HomeShortcuts.result(this, false)); finish() }
}
class NewVoiceShortcut : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state); setResult(RESULT_OK, HomeShortcuts.result(this, true)); finish() }
}
