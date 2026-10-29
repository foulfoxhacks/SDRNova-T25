package com.sdrnova.t25.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.sdrnova.t25.radio.AudioOutput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdrnova.t25.playlist.AliasBook
import com.sdrnova.t25.playlist.ChannelProtocol
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.playlist.PlaylistChannel
import com.sdrnova.t25.radio.EngineStatus
import com.sdrnova.t25.radio.FollowedCall
import com.sdrnova.t25.radio.HeardCall
import com.sdrnova.t25.radio.LogLine
import com.sdrnova.t25.radio.TrunkingView
import com.sdrnova.t25.radio.RadioConfig
import com.sdrnova.t25.radio.RadioEngine
import com.sdrnova.t25.radio.SourceType
import com.sdrnova.t25.radio.Tuning
import com.sdrnova.t25.receiver.Mode
import com.sdrnova.t25.source.IqSource
import com.sdrnova.t25.trunking.Call
import com.sdrnova.t25.trunking.SiteInfo
import com.sdrnova.t25.trunking.TrunkingTracker
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    engine: RadioEngine,
    initialConfig: RadioConfig,
    playlist: Playlist,
    onStart: (RadioConfig) -> Unit,
    onStop: () -> Unit,
    onSave: (RadioConfig) -> Unit,
    /** Applied live to the running radio and saved: gain, volume, channel picks, scanning and tap-to-tune. */
    onLiveChange: (RadioConfig) -> Unit,
    onImportPlaylist: () -> Unit,
    onExportPlaylist: () -> Unit,
    onPlaylistChange: (Playlist) -> Unit,
) {
    val status by engine.status.collectAsStateWithLifecycle()
    val trunking by engine.trunking.collectAsStateWithLifecycle()
    val log by engine.log.collectAsStateWithLifecycle()
    val spectrum by engine.spectrum.collectAsStateWithLifecycle()
    val aliases by engine.aliases.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(initialConfig) }
    // The waterfall keeps filling while other tabs are showing.
    val waterfall = remember { WaterfallImage() }
    LaunchedEffect(spectrum) { spectrum?.let(waterfall::push) }
    val live: (RadioConfig) -> Unit = { updated ->
        config = updated
        onLiveChange(updated)
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(500)
            value = SystemClock.elapsedRealtime()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SDRNova T25", fontWeight = FontWeight.Bold)
                        Text(
                            status.source ?: "P25 trunk tracker",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusCard(
                status = status,
                site = trunking.site,
                config = config,
                now = now,
                onStart = { onStart(config) },
                onStop = onStop,
                onLiveChange = live,
                onScanHold = engine::setScanHold,
                onScanSkip = engine::skip,
            )
            ScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 4.dp) {
                listOf("Calls", "Channels", "Log", "Spectrum", "Setup").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> CallsTab(trunking, aliases)
                1 -> ChannelsTab(
                    playlist = playlist,
                    config = config,
                    status = status,
                    onImport = onImportPlaylist,
                    onExport = onExportPlaylist,
                    onPick = { channel ->
                        Tuning.of(channel)?.let { tuning ->
                            live(
                                config.copy(
                                    frequencyHz = tuning.frequencyHz, mode = tuning.mode, channelName = channel.name,
                                    aliasList = channel.aliasList.orEmpty(), centerHz = 0, scan = false,
                                ),
                            )
                        }
                    },
                    onChange = onPlaylistChange,
                )
                2 -> LogTab(log)
                3 -> SpectrumTab(spectrum, waterfall, status.frequencyHz, status.centerHz, status.sampleRate) { hz ->
                    live(config.copy(frequencyHz = hz, mode = status.mode, channelName = "", scan = false))
                }
                else -> SetupTab(config, onChange = { config = it }, onSave = { onSave(config) })
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: EngineStatus,
    site: SiteInfo,
    config: RadioConfig,
    now: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLiveChange: (RadioConfig) -> Unit,
    onScanHold: (Boolean) -> Unit,
    onScanSkip: () -> Unit,
) {
    val active = status.running || status.starting
    val frequency = if (active) status.frequencyHz else config.frequencyHz
    val mode = if (active) status.mode else config.mode
    val channelName = if (active) status.channelName else config.channelName
    val locked = status.lastSyncAt > 0 && now - status.lastSyncAt < 2_000
    val scanPlace = "${status.scanPosition}/${status.scanSize}"
    val (dot, label) = when {
        status.starting -> Amber to "Starting…"
        status.running && status.scanning && status.scanHold -> Amber to "Scan held on $scanPlace"
        status.running && status.scanning && status.busy -> Good to "Scan stopped on activity, $scanPlace"
        status.running && status.scanning -> Amber to "Scanning $scanPlace"
        status.running && mode == Mode.NBFM -> Good to "Listening"
        status.running && locked -> Good to if (mode == Mode.DMR) "DMR signal locked" else "Control channel locked"
        status.running -> Amber to if (mode == Mode.DMR) "Searching for DMR sync" else "Searching for P25 sync"
        else -> Muted to "Stopped"
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (channelName.isNotEmpty()) {
                        Text(channelName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "%.5f".format(frequency / 1e6) + " MHz",
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(mode.label, style = MaterialTheme.typography.labelMedium, color = Muted)
                }
                if (active) {
                    Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Alert)) { Text("Stop") }
                } else {
                    Button(onClick = onStart) { Text("Start") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = config.scan,
                    onClick = { onLiveChange(config.copy(scan = !config.scan)) },
                    label = { Text("Scan") },
                )
            }
            if (status.running && status.scanning) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onScanHold(!status.scanHold) }) { Text(if (status.scanHold) "Resume" else "Hold") }
                    OutlinedButton(onClick = onScanSkip) { Text("Skip") }
                }
            }
            status.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Alert, style = MaterialTheme.typography.bodySmall)
            }
            if (status.running) {
                Spacer(Modifier.height(10.dp))
                StatRow(
                    "Signal" to "%.0f dBFS".format(status.signalDbfs),
                    "AFC" to "%+.0f Hz".format(status.afcHz),
                    "NAC" to if (status.nac >= 0) "%03X".format(status.nac) else "—",
                )
                if (mode == Mode.P25_PHASE1) {
                    Spacer(Modifier.height(6.dp))
                    StatRow(
                        "TSBK" to status.messages.toString(),
                        "CRC fail" to status.crcErrors.toString(),
                        "Dropped" to status.droppedBuffers.toString(),
                    )
                }
            }
            siteLine(site)?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Muted)
            }
            Spacer(Modifier.height(4.dp))
            QuickControls(config, onLiveChange)
        }
    }
}

/** RF gain and speaker volume, both in dB, applied immediately. */
@Composable
private fun QuickControls(config: RadioConfig, onLiveChange: (RadioConfig) -> Unit) {
    var gainIndex by remember(config.gainTenthsDb) { mutableIntStateOf(gainIndexOf(config.gainTenthsDb)) }
    var volume by remember(config.volumeDb) { mutableStateOf(config.volumeDb) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("RF", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.width(52.dp))
        Slider(
            value = gainIndex.toFloat(),
            onValueChange = { gainIndex = it.roundToInt() },
            onValueChangeFinished = { onLiveChange(config.copy(gainTenthsDb = gainFromIndex(gainIndex))) },
            valueRange = -1f..IqSource.GAIN_STEPS.lastIndex.toFloat(),
            steps = IqSource.GAIN_STEPS.size - 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (gainIndex < 0) "AGC" else "%.1f dB".format(IqSource.GAIN_STEPS[gainIndex] / 10.0),
            style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Volume", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.width(52.dp))
        Slider(
            value = volume,
            onValueChange = { volume = it.roundToInt().toFloat() },
            onValueChangeFinished = { onLiveChange(config.copy(volumeDb = volume)) },
            valueRange = AudioOutput.MUTE_DB..12f,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (volume <= AudioOutput.MUTE_DB) "mute" else "%+.0f dB".format(volume),
            style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp),
        )
    }
}

private fun gainIndexOf(tenthsDb: Int): Int = if (tenthsDb == IqSource.AUTO_GAIN) -1 else
    IqSource.GAIN_STEPS.indexOfFirst { it >= tenthsDb }.let { if (it < 0) IqSource.GAIN_STEPS.lastIndex else it }

private fun gainFromIndex(index: Int): Int = if (index < 0) IqSource.AUTO_GAIN else IqSource.GAIN_STEPS[index]

private fun siteLine(site: SiteInfo): String? {
    val parts = listOfNotNull(
        site.wacn?.let { "WACN %05X".format(it) },
        site.system?.let { "SYS %03X".format(it) },
        site.rfss?.let { "RFSS $it" },
        site.site?.let { "SITE $it" },
    )
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

@Composable
private fun StatRow(vararg stats: Pair<String, String>) {
    Row(Modifier.fillMaxWidth()) {
        for ((name, value) in stats) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelSmall, color = Muted)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun CallsTab(trunking: TrunkingView, aliases: AliasBook) {
    val active = trunking.active
    val recent = trunking.recent
    LazyColumn(Modifier.fillMaxSize()) {
        if (trunking.following.isEmpty() && active.isEmpty() && recent.isEmpty() && trunking.heard.isEmpty()) {
            item {
                Text(
                    "No calls yet. Voice grants from the control channel show up here, with frequencies once the " +
                        "site has broadcast its band plan (${trunking.bands.size} bands, " +
                        "${trunking.neighbors.size} neighbor sites heard).",
                    modifier = Modifier.padding(20.dp),
                    color = Muted,
                )
            }
        }
        if (trunking.following.isNotEmpty()) {
            item { SectionHeader("Decoding now") }
            items(trunking.following, key = { "f${it.id}" }) { FollowedRow(it, aliases) }
        }
        if (active.isNotEmpty()) {
            item { SectionHeader("Active") }
            items(active, key = { "a${it.unitToUnit}${it.talkgroup}" }) { CallRow(it, aliases, live = true) }
        }
        if (trunking.heard.isNotEmpty()) {
            item { SectionHeader("Heard") }
            items(trunking.heard) { HeardRow(it, aliases) }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeader("Recent grants") }
            items(recent) { CallRow(it, aliases, live = false) }
        }
    }
}

@Composable
private fun HeardRow(call: HeardCall, aliases: AliasBook) {
    val alias = if (call.unitToUnit) null else aliases.name(call.talkgroup)
    val title = alias ?: if (call.unitToUnit) "Private ${call.talkgroup}" else "TG ${call.talkgroup}"
    val details = buildList {
        add(call.channel)
        TrunkingTracker.mhz(call.frequencyHz).takeIf { it != call.channel }?.let(::add)
        if (alias != null) add("TG ${call.talkgroup}")
        call.timeslot?.let { add("TS$it") }
        call.source?.let { add("from " + (aliases.radioName(it) ?: it.toString())) }
    }.joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(details, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(TIME.format(Date(call.endedAt)), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = Muted)
            Text("${call.seconds}s", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = Muted)
            if (call.encrypted) Flag("ENC", Amber)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun FollowedRow(call: FollowedCall, aliases: AliasBook) {
    val alias = if (call.unitToUnit) null else aliases.name(call.talkgroup)
    val title = alias ?: if (call.unitToUnit) "Private ${call.talkgroup}" else "TG ${call.talkgroup}"
    val details = buildList {
        if (alias != null) add("TG ${call.talkgroup}")
        add(TrunkingTracker.mhz(call.frequencyHz))
        call.timeslot?.let { add("TS$it") }
        call.source?.let { add("from " + (aliases.radioName(it) ?: it.toString())) }
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(if (call.playing) Good else Amber, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(details, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${call.voiceFrames / 50}s",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = Good,
            )
            when {
                call.encrypted -> Flag("ENC", Amber)
                !call.unitToUnit && aliases.lockedOut(call.talkgroup) -> Flag("LOCKOUT", Muted)
                call.playing -> Flag("AUDIO", Good)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun CallRow(call: Call, aliases: AliasBook, live: Boolean) {
    val alias = if (call.unitToUnit) null else aliases.name(call.talkgroup)
    val title = alias ?: if (call.unitToUnit) "Private ${call.talkgroup}" else "TG ${call.talkgroup}"
    val details = buildList {
        if (alias != null) add("TG ${call.talkgroup}")
        add(call.frequencyHz?.let(TrunkingTracker::mhz) ?: "channel ${call.channel}")
        call.slot?.let { add("TS${it + 1}") }
        call.source?.takeIf { it != 0 }?.let { add("from " + (aliases.radioName(it) ?: it.toString())) }
    }.joinToString(" · ")
    val seconds = ((call.lastSeen - call.firstSeen) / 1000).coerceAtLeast(1)

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (call.emergency) Alert else MaterialTheme.colorScheme.onSurface,
            )
            Text(details, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (live) "${seconds}s" else TIME.format(Date(call.lastSeen)),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (live) Good else Muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (call.emergency) Flag("EMERG", Alert)
                if (call.encrypted) Flag("ENC", Amber)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun Flag(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier.padding(top = 2.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

/** sdrtrunk playlist channels: pick one to listen to, tick the ones to scan. */
@Composable
private fun ChannelsTab(
    playlist: Playlist,
    config: RadioConfig,
    status: EngineStatus,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onPick: (PlaylistChannel) -> Unit,
    onChange: (Playlist) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val active = status.running || status.starting
    val hereHz = if (active) status.frequencyHz else config.frequencyHz
    val hereMode = if (active) status.mode else config.mode
    val scannable = playlist.channels.count { it.enabled && Tuning.of(it) != null }
    val empty = playlist.channels.isEmpty() && playlist.aliases.isEmpty()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImport) { Text("Import sdrtrunk XML") }
                    OutlinedButton(onClick = onExport, enabled = !empty) { Text("Export") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val channel = PlaylistChannel(
                            name = "%.4f MHz".format(hereHz / 1e6), system = "SDRNova", site = "", enabled = true,
                            frequencies = listOf(hereHz), protocol = ChannelProtocol.of(hereMode), decoderType = "",
                            modulation = null, aliasList = config.aliasList.ifEmpty { null },
                        )
                        onChange(playlist.copy(channels = playlist.channels + channel))
                    }) { Text("Add %.4f".format(hereHz / 1e6)) }
                    if (!empty) {
                        TextButton(onClick = {
                            if (confirmClear) onChange(Playlist.EMPTY)
                            confirmClear = !confirmClear
                        }) { Text(if (confirmClear) "Tap again to clear" else "Clear all", color = Alert) }
                    }
                }
                Text(
                    if (empty) {
                        "Import playlist_v4.xml from sdrtrunk (in SDRTrunk/playlist on the desktop) to bring over its " +
                            "channels and talkgroup aliases, or add the frequency you're on. Tap a channel to listen; " +
                            "ticked channels are scanned when Scan is on."
                    } else {
                        "${playlist.channels.size} channels, $scannable ticked for scanning · ${playlist.aliases.size} aliases"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        for ((system, channels) in playlist.channels.groupBy { it.system.ifEmpty { "Other" } }) {
            item { SectionHeader(system) }
            items(channels) { channel ->
                ChannelRow(
                    channel = channel,
                    selected = channel.name == config.channelName && channel.frequencies.firstOrNull() == hereHz,
                    onPick = { onPick(channel) },
                    onToggleScan = {
                        onChange(playlist.copy(channels = playlist.channels.map { if (it === channel) it.copy(enabled = !it.enabled) else it }))
                    },
                    onRemove = { onChange(playlist.copy(channels = playlist.channels.filter { it !== channel })) },
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: PlaylistChannel,
    selected: Boolean,
    onPick: () -> Unit,
    onToggleScan: () -> Unit,
    onRemove: () -> Unit,
) {
    val supported = Tuning.of(channel) != null
    val details = buildList {
        if (channel.site.isNotEmpty()) add(channel.site)
        add(channel.protocol.label + if (channel.simulcast) " (LSM)" else "")
        channel.frequencies.firstOrNull()?.let { add(TrunkingTracker.mhz(it)) }
        if (channel.frequencies.size > 1) add("+${channel.frequencies.size - 1} alternate")
        if (!supported) add("not decoded yet")
    }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = supported, onClick = onPick)
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = channel.enabled && supported, onCheckedChange = { onToggleScan() }, enabled = supported)
        Column(Modifier.weight(1f)) {
            Text(
                channel.name.ifEmpty { "(unnamed)" },
                style = MaterialTheme.typography.titleMedium,
                color = if (supported) MaterialTheme.colorScheme.onSurface else Muted,
            )
            Text(details, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text("✕", modifier = Modifier.clickable(onClick = onRemove).padding(12.dp), color = Muted)
    }
}

@Composable
private fun LogTab(log: List<LogLine>) {
    if (log.isEmpty()) {
        Text("Decoded messages appear here.", modifier = Modifier.padding(20.dp), color = Muted)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(log) { line ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(
                    TIME.format(Date(line.time)),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Muted,
                )
                Spacer(Modifier.width(8.dp))
                Text(line.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SpectrumTab(
    spectrum: FloatArray?,
    waterfall: WaterfallImage,
    channelHz: Long,
    centerHz: Long,
    sampleRate: Int,
    onTune: (Long) -> Unit,
) {
    if (spectrum == null || sampleRate == 0) {
        Text("Start the radio to see the spectrum around the channel.", modifier = Modifier.padding(20.dp), color = Muted)
    } else {
        SpectrumPlot(spectrum, waterfall, channelHz, centerHz, sampleRate, onTune)
    }
}

/** Spectrum line over a waterfall. Tapping either tunes the decoder there, snapped to a 6.25 kHz raster. */
@Composable
private fun SpectrumPlot(
    spectrum: FloatArray,
    waterfall: WaterfallImage,
    channelHz: Long,
    centerHz: Long,
    sampleRate: Int,
    onTune: (Long) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        val floor = waterfall.floorDb - 4f
        val top = waterfall.topDb + 4f
        val channelFraction = 0.5f + (channelHz - centerHz).toFloat() / sampleRate
        val lineColor = MaterialTheme.colorScheme.primary
        val tap = Modifier.pointerInput(centerHz, sampleRate) {
            detectTapGestures { position ->
                val hz = centerHz + ((position.x / size.width) - 0.5) * sampleRate
                onTune((hz / 6_250).roundToInt() * 6_250L)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().weight(0.4f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .then(tap),
        ) {
            val path = Path()
            spectrum.forEachIndexed { index, db ->
                val x = size.width * index / (spectrum.size - 1)
                val y = size.height * (1 - ((db - floor) / (top - floor)).coerceIn(0f, 1f))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (k in 1..3) {
                val y = size.height * k / 4
                drawLine(Muted.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y))
            }
            val channelX = size.width * channelFraction
            drawLine(Amber, Offset(channelX, 0f), Offset(channelX, size.height), strokeWidth = 2f)
            drawLine(Muted.copy(alpha = 0.5f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
            drawPath(path, lineColor, style = Stroke(width = 2f))
        }
        Canvas(Modifier.fillMaxWidth().weight(0.6f).then(tap)) {
            waterfall.version // redraw on every new row
            drawImage(
                waterfall.image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(waterfall.width, waterfall.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
            val channelX = size.width * channelFraction
            drawLine(Amber.copy(alpha = 0.7f), Offset(channelX, 0f), Offset(channelX, size.height), strokeWidth = 1.5f)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Amber: ${TrunkingTracker.mhz(channelHz)}. Grey: tuner centre (DC spike). Tap to tune. " +
                "Span %.2f MHz, %.0f to %.0f dBFS.".format(sampleRate / 1e6, floor, top),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

@Composable
private fun SetupTab(config: RadioConfig, onChange: (RadioConfig) -> Unit, onSave: () -> Unit) {
    var frequencyText by remember { mutableStateOf("%.5f".format(config.frequencyHz / 1e6)) }
    var centerText by remember { mutableStateOf(if (config.centerHz > 0) "%.5f".format(config.centerHz / 1e6) else "") }
    var portText by remember { mutableStateOf(config.port.toString()) }
    var ppmText by remember { mutableStateOf(config.ppm.toString()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = frequencyText,
            onValueChange = { text ->
                frequencyText = text
                text.toDoubleOrNull()?.takeIf { it in 24.0..1766.0 }
                    ?.let { onChange(config.copy(frequencyHz = (it * 1e6).roundToInt().toLong(), channelName = "")) }
            },
            label = { Text("Frequency (MHz): P25 control channel, or the DMR/NBFM channel") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        Label("Mode")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.filter { it.selectable }.forEach { mode ->
                FilterChip(selected = config.mode == mode, onClick = { onChange(config.copy(mode = mode)) }, label = { Text(mode.label) })
            }
        }

        Label("Source")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceType.entries.forEach { source ->
                FilterChip(selected = config.source == source, onClick = { onChange(config.copy(source = source)) }, label = { Text(source.label) })
            }
        }
        if (config.source == SourceType.RTL_TCP) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.host,
                    onValueChange = { onChange(config.copy(host = it.trim())) },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { text ->
                        portText = text
                        text.toIntOrNull()?.takeIf { it in 1..65535 }?.let { onChange(config.copy(port = it)) }
                    },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Label("Sample rate (wider follows more voice channels, costs more CPU)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IqSource.RTL_SAMPLE_RATES.forEach { rate ->
                FilterChip(
                    selected = config.sampleRate == rate,
                    onClick = { onChange(config.copy(sampleRate = rate)) },
                    label = { Text("%.2f MS/s".format(rate / 1e6)) },
                )
            }
        }

        OutlinedTextField(
            value = centerText,
            onValueChange = { text ->
                centerText = text
                if (text.isBlank()) {
                    onChange(config.copy(centerHz = 0))
                } else {
                    text.toDoubleOrNull()?.takeIf { it in 24.0..1766.0 }
                        ?.let { onChange(config.copy(centerHz = (it * 1e6).roundToInt().toLong())) }
                }
            },
            label = { Text("Tuner centre (MHz), blank for automatic") },
            supportingText = {
                Text(
                    "Voice channels within ±%.0f kHz of the centre can be followed."
                        .format(config.sampleRate * 0.42 / 1000),
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        Label("Voice channels decoded at once: " + if (config.maxTrafficChannels == 0) "off" else config.maxTrafficChannels.toString())
        Slider(
            value = config.maxTrafficChannels.toFloat(),
            onValueChange = { onChange(config.copy(maxTrafficChannels = it.roundToInt())) },
            valueRange = 0f..4f,
            steps = 3,
        )

        val gainIndex = if (config.gainTenthsDb == IqSource.AUTO_GAIN) -1 else
            IqSource.GAIN_STEPS.indexOfFirst { it >= config.gainTenthsDb }.let { if (it < 0) IqSource.GAIN_STEPS.lastIndex else it }
        Label("Tuner gain: " + if (gainIndex < 0) "automatic" else "%.1f dB".format(IqSource.GAIN_STEPS[gainIndex] / 10.0))
        Slider(
            value = gainIndex.toFloat(),
            onValueChange = { value ->
                val index = value.roundToInt()
                onChange(config.copy(gainTenthsDb = if (index < 0) IqSource.AUTO_GAIN else IqSource.GAIN_STEPS[index]))
            },
            valueRange = -1f..IqSource.GAIN_STEPS.lastIndex.toFloat(),
            steps = IqSource.GAIN_STEPS.size - 1,
        )

        OutlinedTextField(
            value = ppmText,
            onValueChange = { text ->
                ppmText = text
                text.toIntOrNull()?.takeIf { it in -200..200 }?.let { onChange(config.copy(ppm = it)) }
            },
            label = { Text("Frequency correction (ppm)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bias tee")
                Text("Powers an LNA through the antenna jack (RTL-SDR Blog dongles)", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Switch(checked = config.biasTee, onCheckedChange = { onChange(config.copy(biasTee = it)) })
        }

        Label("Scan: listen %.1f s to each quiet channel".format(config.scanDwellMs / 1000.0))
        Slider(
            value = config.scanDwellMs.toFloat(),
            onValueChange = { onChange(config.copy(scanDwellMs = (it / 100).roundToInt() * 100)) },
            valueRange = 200f..3_000f,
        )
        Label("Scan: wait %.1f s after a call for the reply".format(config.scanHangMs / 1000.0))
        Slider(
            value = config.scanHangMs.toFloat(),
            onValueChange = { onChange(config.copy(scanHangMs = (it / 500).roundToInt() * 500)) },
            valueRange = 0f..10_000f,
        )

        Label("NBFM squelch: " + if (config.squelch <= 0f) "off" else "%.2f".format(config.squelch))
        Slider(value = config.squelch, onValueChange = { onChange(config.copy(squelch = it)) }, valueRange = 0f..2f)

        OutlinedTextField(
            value = config.aliasText,
            onValueChange = { onChange(config.copy(aliasText = it)) },
            label = { Text("Talkgroup aliases") },
            placeholder = { Text("1201,Fire Dispatch\n1305,PD Main\n(RadioReference CSV works too)") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        Text(
            "Settings also save when you press Start. Changing them while running stops the radio so the new " +
                "settings take effect on the next Start.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
