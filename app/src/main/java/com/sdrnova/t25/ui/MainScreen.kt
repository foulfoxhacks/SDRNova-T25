package com.sdrnova.t25.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.sdrnova.t25.radio.EngineStatus
import com.sdrnova.t25.radio.LogLine
import com.sdrnova.t25.radio.RadioConfig
import com.sdrnova.t25.radio.RadioEngine
import com.sdrnova.t25.radio.SourceType
import com.sdrnova.t25.receiver.Mode
import com.sdrnova.t25.source.IqSource
import com.sdrnova.t25.source.rtlsdr.RtlSdrUsbSource
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
    onStart: (RadioConfig) -> Unit,
    onStop: () -> Unit,
    onSave: (RadioConfig) -> Unit,
) {
    val status by engine.status.collectAsStateWithLifecycle()
    val trunking by engine.trunking.collectAsStateWithLifecycle()
    val log by engine.log.collectAsStateWithLifecycle()
    val spectrum by engine.spectrum.collectAsStateWithLifecycle()
    var config by remember { mutableStateOf(initialConfig) }
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
            )
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("Calls", "Log", "Spectrum", "Setup").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> CallsTab(trunking.active, trunking.recent, config.aliases, trunking.bands.size, trunking.neighbors.size)
                1 -> LogTab(log)
                2 -> SpectrumTab(spectrum, config.frequencyHz)
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
) {
    val active = status.running || status.starting
    val frequency = if (active) status.frequencyHz else config.frequencyHz
    val mode = if (active) status.mode else config.mode
    val locked = status.lastSyncAt > 0 && now - status.lastSyncAt < 2_000
    val (dot, label) = when {
        status.starting -> Amber to "Starting…"
        status.running && mode == Mode.NBFM -> Good to "Listening"
        status.running && locked -> Good to "Control channel locked"
        status.running -> Amber to "Searching for P25 sync"
        else -> Muted to "Stopped"
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
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
                Text(label, style = MaterialTheme.typography.bodyMedium)
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
        }
    }
}

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
private fun CallsTab(active: List<Call>, recent: List<Call>, aliases: Map<Int, String>, bands: Int, neighbors: Int) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (active.isEmpty() && recent.isEmpty()) {
            item {
                Text(
                    "No calls yet. Voice grants from the control channel show up here, with frequencies once the " +
                        "site has broadcast its band plan ($bands bands, $neighbors neighbor sites heard).",
                    modifier = Modifier.padding(20.dp),
                    color = Muted,
                )
            }
        }
        if (active.isNotEmpty()) {
            item { SectionHeader("Active") }
            items(active, key = { "a${it.unitToUnit}${it.talkgroup}" }) { CallRow(it, aliases, live = true) }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeader("Recent") }
            items(recent) { CallRow(it, aliases, live = false) }
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
private fun CallRow(call: Call, aliases: Map<Int, String>, live: Boolean) {
    val alias = if (call.unitToUnit) null else aliases[call.talkgroup]
    val title = alias ?: if (call.unitToUnit) "Private ${call.talkgroup}" else "TG ${call.talkgroup}"
    val details = buildList {
        if (alias != null) add("TG ${call.talkgroup}")
        add(call.frequencyHz?.let(TrunkingTracker::mhz) ?: "channel ${call.channel}")
        call.slot?.let { add("TS${it + 1}") }
        call.source?.takeIf { it != 0 }?.let { add("from $it") }
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
private fun SpectrumTab(spectrum: FloatArray?, channelHz: Long) {
    if (spectrum == null) {
        Text("Start the radio to see the spectrum around the channel.", modifier = Modifier.padding(20.dp), color = Muted)
    } else {
        SpectrumPlot(spectrum, channelHz)
    }
}

@Composable
private fun SpectrumPlot(spectrum: FloatArray, channelHz: Long) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        val sorted = spectrum.sorted()
        val floor = sorted[sorted.size / 10] - 6f
        val top = maxOf(sorted.last() + 6f, floor + 30f)
        val channelFraction = 0.5f - RadioEngine.TUNER_OFFSET_HZ.toFloat() / RtlSdrUsbSource.SAMPLE_RATE
        val lineColor = MaterialTheme.colorScheme.primary
        Canvas(
            Modifier.fillMaxWidth().weight(1f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
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
        Spacer(Modifier.height(8.dp))
        Text(
            "Amber: ${TrunkingTracker.mhz(channelHz)}. Grey: tuner centre (DC spike). Span 960 kHz, " +
                "%.0f to %.0f dBFS.".format(floor, top),
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
    }
}

@Composable
private fun SetupTab(config: RadioConfig, onChange: (RadioConfig) -> Unit, onSave: () -> Unit) {
    var frequencyText by remember { mutableStateOf("%.5f".format(config.frequencyHz / 1e6)) }
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
                text.toDoubleOrNull()?.takeIf { it in 24.0..1766.0 }?.let { onChange(config.copy(frequencyHz = (it * 1e6).roundToInt().toLong())) }
            },
            label = { Text("Frequency (MHz) — the control channel for P25") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )

        Label("Mode")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.forEach { mode ->
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
