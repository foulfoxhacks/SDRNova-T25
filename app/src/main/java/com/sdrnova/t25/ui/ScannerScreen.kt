package com.sdrnova.t25.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrnova.t25.playlist.AliasBook
import com.sdrnova.t25.radio.AudioOutput
import com.sdrnova.t25.radio.EngineStatus
import com.sdrnova.t25.radio.FollowedCall
import com.sdrnova.t25.radio.HeardCall
import com.sdrnova.t25.radio.RadioConfig
import com.sdrnova.t25.radio.TrunkingView
import com.sdrnova.t25.receiver.Mode
import com.sdrnova.t25.source.IqSource
import com.sdrnova.t25.trunking.TrunkingTracker
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/** The main display: radio status, what's on the air now in big type, and the calls just heard. */
@Composable
fun ScannerScreen(
    status: EngineStatus,
    trunking: TrunkingView,
    aliases: AliasBook,
    config: RadioConfig,
    now: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLiveChange: (RadioConfig) -> Unit,
    onScanHold: (Boolean) -> Unit,
    onScanSkip: () -> Unit,
    onShowCalls: () -> Unit,
) {
    var showLevels by rememberSaveable { mutableStateOf(false) }
    val heard = trunking.heard.take(25)
    LazyColumn(Modifier.fillMaxSize().background(Canvas)) {
        item { StatusStrip(status, config, now, onStart, onStop, onLiveChange) }
        item { InfoPanel(status, trunking, config, now, onLiveChange) }
        item { NowPlaying(status, trunking, aliases, config) }
        item {
            ControlRow(
                status = status,
                config = config,
                showLevels = showLevels,
                onScanHold = onScanHold,
                onScanSkip = onScanSkip,
                onToggleScan = { onLiveChange(config.copy(scan = !config.scan)) },
                onToggleLevels = { showLevels = !showLevels },
            )
        }
        if (showLevels) item { Levels(config, onLiveChange) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).background(Panel).clickable(onClick = onShowCalls)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyphs.History, contentDescription = null, tint = Ink.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Recent calls", fontSize = 13.sp, color = Ink.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
                if (status.running) Chip("Live", Good) else Chip("Stopped", Muted)
            }
        }
        if (heard.isEmpty()) {
            item { EmptyNote("Calls you hear show up here, with their talkgroup, radio and how long they ran.") }
        } else {
            items(heard) { RecentCallTile(it, aliases) }
        }
    }
}

@Composable
private fun StatusStrip(
    status: EngineStatus,
    config: RadioConfig,
    now: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLiveChange: (RadioConfig) -> Unit,
) {
    val active = status.running || status.starting
    val mode = if (active) status.mode else config.mode
    val locked = status.lastSyncAt > 0 && now - status.lastSyncAt < 2_000
    val place = "${status.scanPosition} of ${status.scanSize}"
    val (dot, label) = when {
        status.starting -> Amber to "Starting…"
        status.running && status.scanning && status.scanHold -> Amber to "Scan held · $place"
        status.running && status.scanning && status.busy -> Good to "Stopped on activity · $place"
        status.running && status.scanning -> Amber to "Scanning $place"
        status.running && mode == Mode.NBFM -> (if (status.busy) Good else Teal) to if (status.busy) "Receiving" else "Listening"
        status.running && locked -> Good to if (mode == Mode.DMR) "DMR signal locked" else "Control channel locked"
        status.running -> Amber to if (mode == Mode.DMR) "Searching for DMR sync" else "Searching for P25 sync"
        else -> Muted to "Stopped"
    }
    Column(Modifier.fillMaxWidth().background(Panel)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(dot, 10)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = if (dot == Muted) Muted else dot, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(status.source ?: "SDRNova T25", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ScanToggle(config.scan) { onLiveChange(config.copy(scan = !config.scan)) }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = if (active) onStop else onStart,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (active) Alert.copy(alpha = 0.22f) else Cyan,
                    contentColor = if (active) Alert else Color(0xFF00363D),
                ),
            ) {
                Icon(if (active) Glyphs.Power else Icons.Filled.PlayArrow, contentDescription = if (active) "Stop" else "Start")
            }
        }
        // Lock indicator: solid when synced, sweeping while searching.
        when {
            status.starting || (status.running && !locked && !status.scanning && mode != Mode.NBFM) ->
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Amber, trackColor = Hairline)
            status.running -> Box(Modifier.fillMaxWidth().height(3.dp).background(if (locked || status.busy) Good else Hairline))
            else -> Box(Modifier.fillMaxWidth().height(3.dp).background(Hairline))
        }
        status.error?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth().background(Alert.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 8.dp),
                color = Alert,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ScanToggle(on: Boolean, onClick: () -> Unit) {
    Text(
        "Scan",
        modifier = Modifier
            .border(1.dp, if (on) Cyan else Outline, RoundedCornerShape(8.dp))
            .background(if (on) Cyan.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (on) Cyan else Ink,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun InfoPanel(status: EngineStatus, trunking: TrunkingView, config: RadioConfig, now: Long, onLiveChange: (RadioConfig) -> Unit) {
    val active = status.running || status.starting
    val frequency = if (active) status.frequencyHz else config.frequencyHz
    val mode = if (active) status.mode else config.mode
    val simulcast = if (active) status.simulcast else config.simulcast && mode == Mode.P25_PHASE1
    val channelName = if (active) status.channelName else config.channelName
    val site = trunking.site
    val locked = status.lastSyncAt > 0 && now - status.lastSyncAt < 2_000

    Column(Modifier.fillMaxWidth().background(PanelDeep).padding(vertical = 6.dp)) {
        InfoRow(Glyphs.Scanner, Cyan) {
            val siteText = listOfNotNull(site.rfss?.let { "RFSS $it" }, site.site?.let { "Site $it" }).joinToString(" · ")
            Text(
                listOf(channelName, siteText).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Manual tuning" },
                color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (status.scanHold) Icon(Icons.Filled.Lock, contentDescription = "Scan held", tint = Amber, modifier = Modifier.size(16.dp))
        }
        InfoRow(Glyphs.Waveform, SkyBlue) {
            Text("%.5f MHz".format(frequency / 1e6), color = Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Chip(modeShort(mode, simulcast), modeColor(mode))
        }
        if (mode == Mode.P25_PHASE1 || mode == Mode.P25_PHASE2) {
            InfoRow(Glyphs.Tag, Teal) {
                val parts = listOfNotNull(
                    "NAC " + if (status.nac >= 0) "%03X".format(status.nac) else "—",
                    site.wacn?.let { "WACN %05X".format(it) },
                    site.system?.let { "SYS %03X".format(it) },
                )
                Text(parts.joinToString(" · "), color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        }
        InfoRow(Glyphs.Signal, SkyBlue) {
            if (status.running) {
                LevelBar((status.signalDbfs + 100f) / 80f, SkyBlue, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text("%.0f dBFS".format(status.signalDbfs), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("No signal · press start", color = Muted, fontSize = 13.sp)
            }
        }
        if (status.running && mode == Mode.P25_PHASE1) {
            Text(
                "TSBK ${status.messages} · CRC fail ${status.crcErrors} · AFC %+.0f Hz · dropped ${status.droppedBuffers}".format(status.afcHz),
                modifier = Modifier.padding(start = 42.dp, top = 2.dp, bottom = 2.dp),
                color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
        }
        if (status.running && locked && status.frequencyHz > 0) {
            // The AFC is covering the dongle's crystal error; offer to fold it into the ppm correction.
            val ppmError = -status.afcHz / status.frequencyHz * 1e6
            if (abs(ppmError) >= 1.0 && abs(status.afcHz) >= 150.0) {
                Row(Modifier.padding(start = 42.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Dongle reads %+.1f ppm off".format(ppmError), color = Amber, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onLiveChange(config.copy(ppm = config.ppm + ppmError.roundToInt())) }) {
                        Text("Correct to ${config.ppm + ppmError.roundToInt()} ppm", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** The call that has the speaker, in big type like a scanner's display. */
@Composable
private fun NowPlaying(status: EngineStatus, trunking: TrunkingView, aliases: AliasBook, config: RadioConfig) {
    val call = trunking.following.firstOrNull { it.playing } ?: trunking.following.firstOrNull()
    val others = trunking.following.filter { it !== call }
    PanelCard(Modifier.padding(top = 4.dp)) {
        when {
            call != null -> ActiveCall(call, status, aliases)
            status.running && status.mode == Mode.NBFM && status.busy -> AnalogActivity(status, config)
            else -> IdleDisplay(status)
        }
        if (others.isNotEmpty()) {
            Text(
                "Also decoding: " + others.joinToString { other ->
                    (if (other.unitToUnit) null else aliases.name(other.talkgroup)) ?: "TG ${other.talkgroup}"
                },
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ActiveCall(call: FollowedCall, status: EngineStatus, aliases: AliasBook) {
    val alias = if (call.unitToUnit) null else aliases.alias(call.talkgroup)
    val lockedOut = !call.unitToUnit && aliases.lockedOut(call.talkgroup)
    val accent = when {
        call.encrypted -> Amber
        lockedOut -> Muted
        call.playing -> Good
        else -> Cyan
    }
    Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(modeShort(status.mode, status.simulcast), modeColor(status.mode))
        call.timeslot?.let { Chip("TS$it", SkyBlue) }
        when {
            call.encrypted -> Chip("Encrypted", Amber)
            lockedOut -> Chip("Locked out", Muted)
            call.playing -> Chip("Audio", Good)
            else -> Chip("Decoding", Cyan)
        }
        alias?.priority?.takeIf { it > 0 }?.let { Chip("Priority $it", Ink.copy(alpha = 0.75f)) }
        Spacer(Modifier.weight(1f))
        Text(duration((call.voiceFrames / 50).toLong()), color = accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(alias?.color?.let { Color(it) } ?: accent))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                alias?.name ?: if (call.unitToUnit) "Private call" else "TG ${call.talkgroup}",
                color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = 32.sp,
            )
            val subtitle = buildList {
                if (alias != null || call.unitToUnit) add(if (call.unitToUnit) "To ${call.talkgroup}" else "TG ${call.talkgroup}")
                alias?.group?.let(::add)
            }.joinToString(" · ")
            if (subtitle.isNotEmpty()) Text(subtitle, color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Ink.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                val source = call.source
                Text(
                    when {
                        source == null -> "Radio not identified yet"
                        aliases.radioName(source) != null -> aliases.radioName(source)!!
                        else -> source.toString()
                    },
                    color = Ink.copy(alpha = 0.85f), fontSize = 19.sp,
                )
                if (source != null && aliases.radioName(source) != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(source.toString(), color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
    Text(
        TrunkingTracker.mhz(call.frequencyHz),
        modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun AnalogActivity(status: EngineStatus, config: RadioConfig) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("NBFM", GoodText)
            Chip("Squelch open", Good)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            status.channelName.ifEmpty { config.channelName }.ifEmpty { TrunkingTracker.mhz(status.frequencyHz) },
            color = Ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(TrunkingTracker.mhz(status.frequencyHz), color = Muted, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun IdleDisplay(status: EngineStatus) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyphs.Scanner, contentDescription = null, tint = Outline, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                if (status.running) "Waiting for activity" else "Radio off",
                color = Muted, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (status.running) "Calls show here as they start." else "Press play to start listening.",
                color = Muted.copy(alpha = 0.8f), fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ControlRow(
    status: EngineStatus,
    config: RadioConfig,
    showLevels: Boolean,
    onScanHold: (Boolean) -> Unit,
    onScanSkip: () -> Unit,
    onToggleScan: () -> Unit,
    onToggleLevels: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.running && status.scanning) {
            ControlButton(if (status.scanHold) "Resume" else "Hold", if (status.scanHold) Icons.Filled.PlayArrow else Glyphs.Pause, status.scanHold, Modifier.weight(1f)) {
                onScanHold(!status.scanHold)
            }
            ControlButton("Skip", Glyphs.SkipNext, false, Modifier.weight(1f), onScanSkip)
        } else {
            ControlButton(if (config.scan) "Scan on" else "Scan off", Glyphs.Scanner, config.scan, Modifier.weight(2f), onToggleScan)
        }
        val volume = if (config.volumeDb <= AudioOutput.MUTE_DB) "Muted" else "%+.0f dB".format(config.volumeDb)
        ControlButton(volume, Glyphs.Volume, showLevels, Modifier.weight(1f), onToggleLevels)
    }
}

@Composable
private fun ControlButton(text: String, icon: ImageVector, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .border(1.dp, if (active) Cyan else Outline, RoundedCornerShape(10.dp))
            .background(if (active) Cyan.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) Cyan else Ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (active) Cyan else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** RF gain and speaker volume, both in dB, applied immediately. */
@Composable
private fun Levels(config: RadioConfig, onLiveChange: (RadioConfig) -> Unit) {
    var gainIndex by remember(config.gainTenthsDb) { mutableIntStateOf(gainIndexOf(config.gainTenthsDb)) }
    var volume by remember(config.volumeDb) { mutableFloatStateOf(config.volumeDb) }
    val sliderColors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Hairline)
    PanelCard {
        LevelSlider(
            label = "RF gain",
            value = if (gainIndex < 0) "AGC" else "%.1f dB".format(IqSource.GAIN_STEPS[gainIndex] / 10.0),
        ) {
            Slider(
                value = gainIndex.toFloat(),
                onValueChange = { gainIndex = it.roundToInt() },
                onValueChangeFinished = { onLiveChange(config.copy(gainTenthsDb = gainFromIndex(gainIndex))) },
                valueRange = -1f..IqSource.GAIN_STEPS.lastIndex.toFloat(),
                steps = IqSource.GAIN_STEPS.size - 1,
                colors = sliderColors,
            )
        }
        LevelSlider(label = "Volume", value = if (volume <= AudioOutput.MUTE_DB) "Muted" else "%+.0f dB".format(volume)) {
            Slider(
                value = volume,
                onValueChange = { volume = it.roundToInt().toFloat() },
                onValueChangeFinished = { onLiveChange(config.copy(volumeDb = volume)) },
                valueRange = AudioOutput.MUTE_DB..12f,
                colors = sliderColors,
            )
        }
    }
}

@Composable
private fun LevelSlider(label: String, value: String, slider: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row {
            Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(value, color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        slider()
    }
}

private fun gainIndexOf(tenthsDb: Int): Int = if (tenthsDb == IqSource.AUTO_GAIN) -1 else
    IqSource.GAIN_STEPS.indexOfFirst { it >= tenthsDb }.let { if (it < 0) IqSource.GAIN_STEPS.lastIndex else it }

private fun gainFromIndex(index: Int): Int = if (index < 0) IqSource.AUTO_GAIN else IqSource.GAIN_STEPS[index]

@Composable
fun RecentCallTile(call: HeardCall, aliases: AliasBook) {
    val alias = if (call.unitToUnit) null else aliases.alias(call.talkgroup)
    val bar = alias?.color?.let { Color(it) } ?: if (call.encrypted) Amber else Cyan
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(34.dp).background(bar))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    alias?.name ?: if (call.unitToUnit) "Private ${call.talkgroup}" else "TG ${call.talkgroup}",
                    color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (call.encrypted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Lock, contentDescription = "Encrypted", tint = Amber, modifier = Modifier.size(14.dp))
                }
            }
            val details = buildList {
                if (alias != null) add("TG ${call.talkgroup}")
                add("${call.seconds}s")
                call.source?.let { add("from " + (aliases.radioName(it) ?: it.toString())) }
                add(call.channel)
            }.joinToString(" · ")
            Text(details, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(TIME.format(Date(call.endedAt)), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
    Box(Modifier.fillMaxWidth().padding(start = 25.dp).height(1.dp).background(Hairline))
}
