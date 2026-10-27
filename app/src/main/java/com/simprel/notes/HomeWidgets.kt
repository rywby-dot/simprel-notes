package com.simprel.notes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/** RemoteViews only: no background service, network or periodic polling. */
object HomeWidgets {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending: Runnable? = null
    val providers = listOf(QuickNoteWidget::class.java, QuickVoiceWidget::class.java, QuickBothWidget::class.java, LastNoteWidget::class.java, UnreadNoteWidget::class.java)
    fun localized(c: Context): Context {
        val selected = c.getSharedPreferences("settings", 0).getString("language", "system") ?: "system"
        val locale = Locale.forLanguageTag(resolveLanguage(selected, android.content.res.Resources.getSystem().configuration.locales[0].language))
        return c.createConfigurationContext(Configuration(c.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) })
    }
    fun requestUpdate(c: Context, notes: List<Note>? = null) {
        val context = c.applicationContext
        val snapshot = notes?.map { it.copy(audioPeaks = it.audioPeaks.toList()) }
        pending?.let { handler.removeCallbacks(it) }
        pending = Runnable { pending = null; if (snapshot == null) refresh(context) else updateAll(context, snapshot) }
            .also { handler.postDelayed(it, 200) }
    }
    fun refresh(c: Context) {
        try { updateAll(c, Store(c).notes) } catch (_: Exception) { /* Leave the last valid widget content on a read failure. */ }
    }
    fun updateAll(c: Context, notes: List<Note>) {
        val manager = AppWidgetManager.getInstance(c)
        for ((kind, provider) in providers.withIndex()) {
            for (id in manager.getAppWidgetIds(ComponentName(c.packageName, provider.name))) {
                update(c, manager, id, kind, notes, manager.getAppWidgetOptions(id))
            }
        }
    }
    fun update(c: Context, manager: AppWidgetManager, id: Int, kind: Int, notes: List<Note>, options: Bundle) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val sizes = options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                val layouts = sizes.filter { it.width > 0 && it.height > 0 }.distinct().take(4)
                    .associateWith { build(c, kind, notes, it.width.toInt(), it.height.toInt()) }
                if (layouts.isNotEmpty()) { manager.updateAppWidget(id, RemoteViews(layouts)); return }
            }
        }
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (kind < 2) 72 else 160)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, if (kind < 3) 72 else 160)
        manager.updateAppWidget(id, build(c, kind, notes, width, height))
    }
    private fun background(width: Int, height: Int, columns: Int, rows: Int, color: Int, density: Float = 1f): Bitmap {
        val logicalWidth = width.coerceAtLeast(32); val logicalHeight = height.coerceAtLeast(32)
        val scale = minOf(density, 768f / maxOf(logicalWidth, logicalHeight))
        val w = (logicalWidth * scale).toInt().coerceAtLeast(1); val h = (logicalHeight * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            if (columns == 1 && rows == 1) canvas.drawCircle(w / 2f, h / 2f, minOf(w, h) / 2f, paint)
            else {
                val radius = widgetCornerRadius(w.toFloat(), h.toFloat(), columns, rows)
                canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, paint)
            }
        }
    }
    private fun glyph(c: Context, resource: Int, color: Int): Bitmap =
        Bitmap.createBitmap((32 * c.resources.displayMetrics.density).toInt().coerceAtLeast(1), (32 * c.resources.displayMetrics.density).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmap ->
            c.getDrawable(resource)!!.mutate().apply { setTint(color); setBounds(0, 0, bitmap.width, bitmap.height); draw(Canvas(bitmap)) }
        }
    fun build(c: Context, kind: Int, notes: List<Note>, width: Int, height: Int): RemoteViews {
        val local = localized(c); val prefs = c.getSharedPreferences("settings", 0)
        val systemDark = c.resources.configuration.uiMode and 0x30 == 0x20
        val theme = prefs.getString("theme", "system")
        val dark = theme in setOf("dark", "amoled") || (theme == "system" && systemDark)
        val surface = Color.parseColor(if (theme == "amoled") "#000000" else if (dark) "#211F26" else "#F7F2FA")
        val ink = Color.parseColor(if (dark) "#E6E0E9" else "#1D1B20")
        val accent = when (prefs.getString("accent", "system")) {
            "blue" -> Color.parseColor(if (dark) "#ADC6FF" else "#275EA7")
            "green" -> Color.parseColor(if (dark) "#9DD49E" else "#326B36")
            "rose" -> Color.parseColor(if (dark) "#FFB1C8" else "#984061")
            "violet" -> Color.parseColor(if (dark) "#D0BCFF" else "#6750A4")
            else -> if (android.os.Build.VERSION.SDK_INT >= 31) c.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600) else Color.rgb(103, 80, 164)
        }
        val views = RemoteViews(c.packageName, when (kind) { 0 -> R.layout.widget_text; 1 -> R.layout.widget_voice; 2 -> R.layout.widget_actions; else -> R.layout.widget_note })
        views.setImageViewBitmap(R.id.widget_background, background(width, height, if (kind < 2) 1 else 2, if (kind < 3) 1 else 2, surface, c.resources.displayMetrics.density))
        fun click(view: Int, action: String, note: Note? = null) {
            val intent = if (note == null) HomeShortcuts.intent(c, action) else HomeShortcuts.intent(c, "com.simprel.notes.OPEN_NOTE")
                .setData(android.net.Uri.parse("simprel://widget/${note.id}")).putExtra("note_id", note.id)
            views.setOnClickPendingIntent(view, PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (kind < 3) {
            views.setViewVisibility(R.id.widget_text_action, if (kind == 1) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_voice_action, if (kind == 0) View.GONE else View.VISIBLE)
            views.setImageViewBitmap(R.id.widget_text_action, glyph(c, R.drawable.ic_new_note, accent))
            views.setImageViewBitmap(R.id.widget_voice_action, glyph(c, R.drawable.ic_voice, accent))
            views.setContentDescription(R.id.widget_text_action, local.getString(R.string.new_note))
            views.setContentDescription(R.id.widget_voice_action, local.getString(R.string.new_voice_note))
            click(R.id.widget_text_action, HomeShortcuts.NEW_NOTE); click(R.id.widget_voice_action, HomeShortcuts.NEW_VOICE)
            return views
        }
        val unread = widgetUnreadNotes(notes, notes.filter { Reminders.unread(c, it) }.map { it.id }.toSet())
        val note = if (kind == 3) widgetLastNote(notes, prefs.getString("last", "") ?: "") else unread.firstOrNull()
        val title = local.getString(if (kind == 3) R.string.widget_last_note else R.string.widget_unread_note)
        views.setTextViewText(R.id.widget_title, title); views.setTextColor(R.id.widget_title, accent)
        val text = note?.text?.let { io.noties.markwon.Markwon.create(local).toMarkdown(it.take(4000)).toString() }
            ?: local.getString(if (kind == 3) R.string.empty_notes else R.string.widget_no_unread)
        views.setTextViewText(R.id.widget_content, text); views.setTextColor(R.id.widget_content, ink)
        val scale = prefs.getInt("text_scale", 100).coerceIn(50, 400) / 100f
        views.setTextViewTextSize(R.id.widget_content, android.util.TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * scale)
        views.setInt(R.id.widget_content, "setMaxLines", ((height - if (note?.audioFile?.isNotEmpty() == true) 104 else 72) / (18 * scale)).toInt().coerceAtLeast(1))
        views.setContentDescription(R.id.widget_root, "$title. ${if (note?.audioFile?.isNotEmpty() == true) local.getString(R.string.voice_note) + ". " else ""}$text")
        val audio = note?.audioFile?.isNotEmpty() == true
        views.setViewVisibility(R.id.widget_audio, if (audio) View.VISIBLE else View.GONE)
        if (audio) {
            val bitmap = Bitmap.createBitmap(240, 48, Bitmap.Config.ARGB_8888)
            VoiceStrip.drawStrip(Canvas(bitmap), RectF(0f, 0f, 240f, 48f), note!!.audioPeaks, accent, 1.5f)
            views.setImageViewBitmap(R.id.widget_audio, bitmap)
            views.setContentDescription(R.id.widget_audio, local.getString(R.string.voice_note))
        }
        val showCount = kind == 4 && unread.size > 1
        views.setViewVisibility(R.id.widget_count, if (showCount) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_count, unread.size.toString())
        views.setTextColor(R.id.widget_count, surface)
        views.setViewVisibility(R.id.widget_count_bg, if (showCount) View.VISIBLE else View.GONE)
        if (showCount) views.setImageViewBitmap(R.id.widget_count_bg, background(32, 32, 1, 1, accent, c.resources.displayMetrics.density))
        views.setTextViewTextSize(R.id.widget_count, android.util.TypedValue.COMPLEX_UNIT_SP, minOf(12f, 36f / unread.size.toString().length))
        views.setContentDescription(R.id.widget_count, local.getString(R.string.widget_unread_count, unread.size))
        click(R.id.widget_root, if (kind == 3) HomeShortcuts.NEW_NOTE else HomeShortcuts.SHOW_LIST, note)
        return views
    }
}

open class NotesWidget(private val kind: Int) : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) {
        val notes = try { Store(c).notes } catch (_: Exception) { return }
        ids.forEach { HomeWidgets.update(c, manager, it, kind, notes, manager.getAppWidgetOptions(it)) }
    }
    override fun onAppWidgetOptionsChanged(c: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        val notes = try { Store(c).notes } catch (_: Exception) { return }
        HomeWidgets.update(c, manager, id, kind, notes, options)
    }
}
class QuickNoteWidget : NotesWidget(0)
class QuickVoiceWidget : NotesWidget(1)
class QuickBothWidget : NotesWidget(2)
class LastNoteWidget : NotesWidget(3)
class UnreadNoteWidget : NotesWidget(4)

class HomeRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) { HomeShortcuts.publish(c); HomeWidgets.refresh(c) }
}
