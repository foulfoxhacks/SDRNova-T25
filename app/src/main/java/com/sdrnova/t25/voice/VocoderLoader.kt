package com.sdrnova.t25.voice

import android.content.Context
import android.net.Uri
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Method

/**
 * Finds an IMBE vocoder without the app depending on one. Two places are checked, in order:
 *
 * 1. JMBE compiled into this build with `-Pjmbe.jar=...` (local builds only; see docs/VOCODER.md).
 * 2. A provider jar the user imported, already converted to dex, loaded with [DexClassLoader].
 *
 * Both are driven through JMBE's `IAudioCodecLibrary` API by reflection, so nothing codec-related is compiled in.
 */
object VocoderLoader {
    private const val TAG = "VocoderLoader"
    private const val LIBRARY_CLASS = "jmbe.JMBEAudioLibrary"
    private const val CODEC_INTERFACE = "jmbe.iface.IAudioCodec"
    private const val IMBE = "IMBE"
    private const val AMBE = "AMBE"

    fun providerFile(context: Context) = File(context.filesDir, "vocoder/provider.jar")

    fun load(context: Context): VocoderProvider? =
        fromLoader(context.classLoader, "built in")
            ?: providerFile(context).takeIf { it.exists() }?.let { file ->
                val loader = DexClassLoader(file.path, context.codeCacheDir.path, null, context.classLoader)
                fromLoader(loader, "imported")
            }

    /** Copies a provider jar from [uri] into app storage and checks that it loads. */
    fun import(context: Context, uri: Uri): VocoderProvider? {
        val file = providerFile(context)
        file.parentFile?.mkdirs()
        if (file.exists()) {
            file.setWritable(true)
            file.delete()
        }
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: return null
        // Android 14 refuses to load dex from a writable file.
        file.setReadOnly()
        return load(context)
    }

    fun remove(context: Context) {
        providerFile(context).apply {
            setWritable(true)
            delete()
        }
    }

    private fun fromLoader(loader: ClassLoader, origin: String): VocoderProvider? = try {
        val libraryClass = Class.forName(LIBRARY_CLASS, true, loader)
        val library = libraryClass.getDeclaredConstructor().newInstance()
        val version = runCatching { libraryClass.getMethod("getVersion").invoke(library) as String }.getOrDefault("?")
        val codecInterface = Class.forName(CODEC_INTERFACE, true, loader)
        JmbeProvider(
            name = "JMBE $version ($origin)",
            library = library,
            getConverter = libraryClass.getMethod("getAudioConverter", String::class.java),
            getAudio = codecInterface.getMethod("getAudio", ByteArray::class.java),
            reset = codecInterface.getMethod("reset"),
        )
    } catch (_: ClassNotFoundException) {
        null
    } catch (e: Throwable) {
        Log.w(TAG, "vocoder provider failed to load", e)
        null
    }

    private class JmbeProvider(
        override val name: String,
        private val library: Any,
        private val getConverter: Method,
        private val getAudio: Method,
        private val reset: Method,
    ) : VocoderProvider {
        override fun createImbeDecoder(): VoiceDecoder? = decoder(IMBE)

        override fun createAmbeDecoder(): VoiceDecoder? = decoder(AMBE)

        private fun decoder(codecName: String): VoiceDecoder? {
            val codec = runCatching { getConverter.invoke(library, codecName) }.getOrNull() ?: return null
            return object : VoiceDecoder {
                override fun decode(frame: ByteArray): FloatArray = getAudio.invoke(codec, lsbFirst(frame)) as FloatArray

                override fun reset() {
                    reset.invoke(codec)
                }
            }
        }

        /** JMBE reads frames the way java.util.BitSet packs them: bit 0 in the low bit of byte 0. */
        private fun lsbFirst(frame: ByteArray): ByteArray {
            val out = ByteArray(frame.size)
            for (bit in 0 until frame.size * 8) {
                if ((frame[bit / 8].toInt() ushr (7 - bit % 8)) and 1 == 1) {
                    out[bit / 8] = (out[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
                }
            }
            return out
        }
    }
}
