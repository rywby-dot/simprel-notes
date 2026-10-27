package com.simprel.notes

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic replacement keeps the previous complete file if a write is interrupted. */
class Store(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "notes.json"))
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val notes = mutableListOf<Note>()
    init {
        try {
            val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                notes.add(Note(item.getString("id"), item.getString("text"), item.optLong("reminderAt"), item.optString("audioFile"), readPeaks(item.optJSONArray("audioPeaks"))))
            }
        } catch (_: java.io.FileNotFoundException) { }
    }
    companion object {
        fun readPeaks(array: JSONArray?): List<Float> = if (array == null) emptyList() else
            (0 until minOf(array.length(), 48)).map { array.optDouble(it, 0.0).toFloat().coerceIn(0f, 1f) }
    }
    fun audioFile(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9-]+\\.wav")))
        return File(context.filesDir, "voice").apply { mkdirs() }.resolve(name)
    }
    fun removeUnusedAudio(name: String) {
        if (name.isEmpty() || notes.any { it.audioFile == name }) return
        val undo = try { JSONObject(prefs.getString("pending_undo", "{}") ?: "{}").optString("audioFile") } catch (_: Exception) { "" }
        if (undo != name) try { audioFile(name).delete() } catch (_: IllegalArgumentException) { }
    }
    fun save() {
        val array = JSONArray()
        notes.filter { it.hasContent }.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("reminderAt", it.reminderAt).put("audioFile", it.audioFile).put("audioPeaks", JSONArray(it.audioPeaks))) }
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        Reminders.sync(context, notes)
        HomeWidgets.requestUpdate(context, notes)
    }
}
