package com.simprel.notes

import java.util.UUID

data class Note(val id: String = UUID.randomUUID().toString(), var text: String = "", var reminderAt: Long = 0, var audioFile: String = "", var audioPeaks: List<Float> = emptyList()) {
    val hasContent get() = text.isNotBlank() || audioFile.isNotEmpty()
}
