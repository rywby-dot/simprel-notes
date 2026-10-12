package com.simprel.notes

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Note(val id: String = UUID.randomUUID().toString(), var text: String = "")

/** Atomic replacement keeps the previous complete file if a write is interrupted. */
class Store(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "notes.json"))
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val notes = mutableListOf<Note>()
    init {
        try {
            val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                notes.add(Note(item.getString("id"), item.getString("text")))
            }
        } catch (_: java.io.FileNotFoundException) { }
    }
    fun save() {
        val array = JSONArray()
        notes.filter { it.text.isNotBlank() }.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text)) }
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
