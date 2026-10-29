package com.sdrnova.t25.radio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Plays 48 kHz mono PCM on its own thread so a slow audio device never stalls the DSP thread. */
class AudioOutput(val sampleRate: Int = 48_000) {
    private val queue = ArrayBlockingQueue<ShortArray>(32)
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Linear gain applied to every sample; 0 mutes. */
    @Volatile
    var gain = 1f

    fun setVolumeDb(db: Float) {
        gain = if (db <= MUTE_DB) 0f else Math.pow(10.0, db / 20.0).toFloat()
    }

    fun start() {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minimum * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack.play()
        track = audioTrack
        running = true
        thread = Thread({
            while (running) {
                val samples = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                audioTrack.write(samples, 0, samples.size)
            }
        }, "audio-out").apply { start() }
    }

    /** Copies [count] samples at the current volume and queues them; drops them if playback has fallen behind. */
    fun write(samples: ShortArray, count: Int) {
        if (!running) return
        val g = gain
        val copy = ShortArray(count) { (samples[it] * g).coerceIn(-32767f, 32767f).toInt().toShort() }
        queue.offer(copy)
    }

    companion object {
        /** Volume at or below this is mute. */
        const val MUTE_DB = -40f
    }

    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
        queue.clear()
    }
}
