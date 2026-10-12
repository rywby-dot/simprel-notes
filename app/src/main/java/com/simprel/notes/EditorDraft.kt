package com.simprel.notes

/** Immutable editor-entry snapshot: autosaving never changes the discard target. */
data class EditorDraft(val id: String, val text: String, val index: Int, val previousLast: String = "") {
    fun discardFrom(notes: List<Note>): List<Note> {
        if (index < 0) return notes.filter { it.id != id }
        if (notes.any { it.id == id }) return notes.map { if (it.id == id) Note(id, text) else it }
        return notes.toMutableList().apply { add(index.coerceIn(0, size), Note(id, text)) }
    }
}
