package com.simprel.notes

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Disk-backed mono PCM recording. Pauses append; saving physically trims sample boundaries. */
class VoiceAudio(private val temporary: File) {
    companion object {
        const val RATE = 24000
        fun envelope(peaks: List<Float>, count: Int = 48): List<Float> = (0 until count).map { bar ->
            val from = bar * peaks.size / count
            val until = maxOf(from + 1, (bar + 1) * peaks.size / count).coerceAtMost(peaks.size)
            if (from >= peaks.size) 0f else peaks.subList(from, until).maxOrNull() ?: 0f
        }
        fun readEnvelope(source: File): List<Float> {
            val peaks = mutableListOf<Float>()
            RandomAccessFile(source, "r").use { input ->
                input.seek(44)
                val buffer = ByteArray(1920)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    var peak = 0
                    for (i in 0 until count - 1 step 2) peak = maxOf(peak, abs(((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()))
                    peaks.add(peak / 32768f)
                }
            }
            return envelope(peaks)
        }
        fun writeWave(source: File, target: File, start: Float, end: Float) {
            val samples = source.length() / 2
            val first = (samples * start.coerceIn(0f, 1f)).toLong()
            val last = (samples * end.coerceIn(start, 1f)).toLong()
            val bytes = ((last - first) * 2).toInt()
            require(bytes > 0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
            target.outputStream().use { output ->
                output.write(header.array())
                RandomAccessFile(source, "r").use { input ->
                    input.seek(first * 2)
                    val buffer = ByteArray(8192)
                    var remaining = bytes
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        check(count > 0); output.write(buffer, 0, count); remaining -= count
                    }
                }
            }
        }
    }
    @Volatile var recording = false
        private set
    @Volatile var failure: Exception? = null
        private set
    @Volatile var spectrum = FloatArray(24)
        private set
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val peaks = mutableListOf<Float>()
    val durationMs get() = temporary.length() * 1000 / (RATE * 2)
    fun waveform(): List<Float> = synchronized(peaks) { peaks.toList() }
    fun importWave(source: File) {
        check(!recording)
        source.inputStream().use { input ->
            check(input.skip(44) == 44L)
            temporary.outputStream().use { input.copyTo(it) }
        }
        synchronized(peaks) { peaks.clear()
            RandomAccessFile(temporary, "r").use { input ->
                val buffer = ByteArray(1920)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    var peak = 0
                    for (i in 0 until count - 1 step 2) peak = maxOf(peak, abs(((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()))
                    peaks.add(peak / 32768f)
                }
            } }
    }
    fun preview(target: File) { writeWave(temporary, target, 0f, 1f) }
    fun start() {
        check(!recording)
        if (recorder != null) pause()
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192))
        try { check(audio.state == AudioRecord.STATE_INITIALIZED); audio.startRecording(); check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) }
        catch (e: Exception) { audio.release(); throw e }
        recorder = audio; failure = null; recording = true
        worker = Thread({
            try {
                java.io.FileOutputStream(temporary, true).use { output ->
                    val samples = ShortArray(960)
                    val bytes = ByteArray(samples.size * 2)
                    while (recording) {
                        val count = audio.read(samples, 0, samples.size)
                        if (count <= 0) { if (recording) error("Audio read: $count"); break }
                        var peak = 0
                        for (i in 0 until count) {
                            val sample = samples[i].toInt()
                            peak = maxOf(peak, abs(sample)); bytes[i * 2] = sample.toByte(); bytes[i * 2 + 1] = (sample shr 8).toByte()
                        }
                        output.write(bytes, 0, count * 2)
                        synchronized(peaks) { peaks.add(peak / 32768f) }
                        // Logarithmic frequency bands; windowing reduces spectral leakage.
                        spectrum = FloatArray(24) { band ->
                            val frequency = 80.0 * (8000.0 / 80).pow(band / 23.0)
                            var real = 0.0; var imaginary = 0.0
                            for (i in 0 until count step 2) {
                                val value = samples[i] / 32768.0 * (.5 - .5 * cos(2 * PI * i / count))
                                val angle = 2 * PI * frequency * i / RATE
                                real += value * cos(angle); imaginary += value * sin(angle)
                            }
                            (sqrt(real * real + imaginary * imaginary) / count * 24).toFloat().coerceIn(0f, 1f)
                        }
                    }
                }
            } catch (e: Exception) { failure = e }
            finally { recording = false }
        }, "voice-recording").also { it.start() }
    }
    fun pause() {
        recording = false
        try { recorder?.stop() } catch (_: IllegalStateException) { }
        worker?.join(2000)
        recorder?.release(); recorder = null; worker = null
    }
    fun save(target: File, start: Float, end: Float) { pause(); check(failure == null); writeWave(temporary, target, start, end) }
    fun close() { pause(); temporary.delete() }
}
