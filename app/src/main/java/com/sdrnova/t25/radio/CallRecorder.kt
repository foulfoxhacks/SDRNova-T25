package com.sdrnova.t25.radio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class RecordMode(val label: String) {
    OFF("Off"),
    MARKED("Marked talkgroups"),
    ALL("Every call"),
}

/** One call's audio, kept as 16-bit PCM until the call ends and [Recordings] saves it as a WAV file. */
class CallRecorder(val sampleRate: Int, val title: String, val startedAt: Long = System.currentTimeMillis()) {
    private var samples = ShortArray(sampleRate * 8)
    private var size = 0

    val seconds: Double get() = size.toDouble() / sampleRate

    /** Vocoder output: floats around ±1 at 8 kHz, scaled like the speaker path. */
    fun write(pcm: FloatArray) {
        ensure(pcm.size)
        for (value in pcm) samples[size++] = (value * VOICE_GAIN).coerceIn(-32767f, 32767f).toInt().toShort()
    }

    /** Analog audio, keeping every [step]th sample (the NBFM audio filter already stops above 4 kHz). */
    fun write(pcm: ShortArray, count: Int, step: Int) {
        ensure(count / step + 1)
        var k = 0
        while (k < count) {
            samples[size++] = pcm[k]
            k += step
        }
    }

    private fun ensure(extra: Int) {
        if (size + extra > samples.size) samples = samples.copyOf(maxOf(samples.size * 2, size + extra))
    }

    fun wav(): ByteArray {
        val data = size * 2
        val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(data)
        for (k in 0 until size) buffer.putShort(samples[k])
        return buffer.array()
    }

    private companion object {
        /** Same scale [com.sdrnova.t25.voice.VoiceResampler] uses for the speaker. */
        const val VOICE_GAIN = 24_000f
    }
}

/** Saves recordings where music players and file managers find them: Music/SDRNova T25. */
object Recordings {
    private const val FOLDER = "SDRNova T25"

    /** Writes [wav] as [name] and returns where it went. Blocking; call off the DSP thread. */
    fun save(context: Context, name: String, wav: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Android refused to create the recording")
            (resolver.openOutputStream(uri) ?: throw IOException("Couldn't open the recording")).use { it.write(wav) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Music/$FOLDER/$name"
        }
        // Before Android 10, the app's own music folder needs no storage permission.
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), FOLDER).apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(wav)
        return file.path
    }
}
