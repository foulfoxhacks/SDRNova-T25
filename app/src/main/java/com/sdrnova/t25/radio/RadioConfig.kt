package com.sdrnova.t25.radio

import android.content.Context
import com.sdrnova.t25.receiver.Mode
import com.sdrnova.t25.source.IqSource

enum class SourceType(val label: String) {
    USB("USB dongle"),
    RTL_TCP("rtl_tcp"),
}

data class RadioConfig(
    val frequencyHz: Long = 851_012_500,
    val mode: Mode = Mode.P25_PHASE1,
    val source: SourceType = SourceType.USB,
    val host: String = "192.168.1.10",
    val port: Int = 1234,
    val gainTenthsDb: Int = IqSource.AUTO_GAIN,
    val ppm: Int = 0,
    val biasTee: Boolean = false,
    val squelch: Float = 0.8f,
    val aliasText: String = "",
    val sampleRate: Int = 2_400_000,
    /** Tuner centre; 0 places it 150 kHz above the channel so the DC spike stays clear. */
    val centerHz: Long = 0,
    /** Voice channels decoded alongside the control channel (0 turns following off). */
    val maxTrafficChannels: Int = 2,
    /** Speaker volume in dB; [AudioOutput.MUTE_DB] or below is mute. */
    val volumeDb: Float = 0f,
    /** The playlist channel picked in Channels; blank when tuned by hand. */
    val channelName: String = "",
    /** Playlist alias list for names and priorities; blank uses every alias. */
    val aliasList: String = "",
    /** Scan the ticked playlist channels instead of sitting on one frequency. */
    val scan: Boolean = false,
    /** How long scanning listens to a quiet channel. */
    val scanDwellMs: Int = 500,
    /** How long scanning waits on a channel after its call ends, for the reply. */
    val scanHangMs: Int = 2_000,
    /** P25 Phase 1 from a simulcast site, which transmits LSM (CQPSK) instead of C4FM. */
    val simulcast: Boolean = false,
    /** Which calls are saved as WAV files; "marked" follows the record flag on sdrtrunk aliases. */
    val recordMode: RecordMode = RecordMode.MARKED,
) {
    val effectiveCenterHz: Long get() = if (centerHz > 0) centerHz else frequencyHz + RadioEngine.TUNER_OFFSET_HZ

    val tuning: Tuning get() = Tuning(frequencyHz, mode, channelName, aliasList.ifEmpty { null }, simulcast)

    /** Talkgroup aliases from "id,name" lines; RadioReference CSV rows (Decimal,Hex,Alpha Tag,...) also work. */
    val aliases: Map<Int, String> by lazy {
        aliasText.lineSequence().mapNotNull { line ->
            val fields = line.split(',', '\t').map { it.trim().trim('"') }
            val id = fields.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
            val name = when {
                fields.size >= 3 && fields[1].matches(Regex("[0-9A-Fa-f]+")) -> fields[2]
                fields.size >= 2 -> fields[1]
                else -> return@mapNotNull null
            }
            if (name.isEmpty()) null else id to name
        }.toMap()
    }
}

/** Persists [RadioConfig] in shared preferences. */
class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("sdrnova", Context.MODE_PRIVATE)

    fun load(): RadioConfig {
        val defaults = RadioConfig()
        return RadioConfig(
            frequencyHz = prefs.getLong("frequency", defaults.frequencyHz),
            mode = runCatching { Mode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(defaults.mode),
            source = runCatching { SourceType.valueOf(prefs.getString("source", null)!!) }.getOrDefault(defaults.source),
            host = prefs.getString("host", defaults.host)!!,
            port = prefs.getInt("port", defaults.port),
            gainTenthsDb = prefs.getInt("gain", defaults.gainTenthsDb),
            ppm = prefs.getInt("ppm", defaults.ppm),
            biasTee = prefs.getBoolean("biasTee", defaults.biasTee),
            squelch = prefs.getFloat("squelch", defaults.squelch),
            aliasText = prefs.getString("aliases", defaults.aliasText)!!,
            sampleRate = prefs.getInt("sampleRate", defaults.sampleRate),
            centerHz = prefs.getLong("center", defaults.centerHz),
            maxTrafficChannels = prefs.getInt("maxTraffic", defaults.maxTrafficChannels),
            volumeDb = prefs.getFloat("volumeDb", defaults.volumeDb),
            channelName = prefs.getString("channelName", defaults.channelName)!!,
            aliasList = prefs.getString("aliasList", defaults.aliasList)!!,
            scan = prefs.getBoolean("scan", defaults.scan),
            scanDwellMs = prefs.getInt("scanDwellMs", defaults.scanDwellMs),
            scanHangMs = prefs.getInt("scanHangMs", defaults.scanHangMs),
            simulcast = prefs.getBoolean("simulcast", defaults.simulcast),
            recordMode = runCatching { RecordMode.valueOf(prefs.getString("recordMode", null)!!) }.getOrDefault(defaults.recordMode),
        )
    }

    fun save(config: RadioConfig) {
        prefs.edit()
            .putLong("frequency", config.frequencyHz)
            .putString("mode", config.mode.name)
            .putString("source", config.source.name)
            .putString("host", config.host)
            .putInt("port", config.port)
            .putInt("gain", config.gainTenthsDb)
            .putInt("ppm", config.ppm)
            .putBoolean("biasTee", config.biasTee)
            .putFloat("squelch", config.squelch)
            .putString("aliases", config.aliasText)
            .putInt("sampleRate", config.sampleRate)
            .putLong("center", config.centerHz)
            .putInt("maxTraffic", config.maxTrafficChannels)
            .putFloat("volumeDb", config.volumeDb)
            .putString("channelName", config.channelName)
            .putString("aliasList", config.aliasList)
            .putBoolean("scan", config.scan)
            .putInt("scanDwellMs", config.scanDwellMs)
            .putInt("scanHangMs", config.scanHangMs)
            .putBoolean("simulcast", config.simulcast)
            .putString("recordMode", config.recordMode.name)
            .apply()
    }
}
