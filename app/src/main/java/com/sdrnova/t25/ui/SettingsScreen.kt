package com.sdrnova.t25.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrnova.t25.radio.RadioConfig
import com.sdrnova.t25.radio.RecordMode
import com.sdrnova.t25.radio.SourceType
import com.sdrnova.t25.receiver.Mode
import com.sdrnova.t25.source.IqSource
import kotlin.math.roundToInt

/** Everything that takes effect on the next start, grouped the way you'd look for it. */
@Composable
fun SettingsScreen(config: RadioConfig, onChange: (RadioConfig) -> Unit, onSave: () -> Unit) {
    var frequencyText by remember { mutableStateOf("%.5f".format(config.frequencyHz / 1e6)) }
    var centerText by remember { mutableStateOf(if (config.centerHz > 0) "%.5f".format(config.centerHz / 1e6) else "") }
    var portText by remember { mutableStateOf(config.port.toString()) }
    var ppmText by remember { mutableStateOf(config.ppm.toString()) }

    Column(Modifier.fillMaxSize().background(Canvas)) {
        ScreenBar(title = "Settings", subtitle = "Saved changes apply on the next start")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Section("Listening") {
                OutlinedTextField(
                    value = frequencyText,
                    onValueChange = { text ->
                        frequencyText = text
                        text.toDoubleOrNull()?.takeIf { it in 24.0..1766.0 }
                            ?.let { onChange(config.copy(frequencyHz = (it * 1e6).roundToInt().toLong(), channelName = "")) }
                    },
                    label = { Text("Frequency (MHz)") },
                    supportingText = { Text("The P25 control channel, or the DMR or NBFM channel") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Label("Mode")
                Choices(Mode.entries.filter { it.selectable }, config.mode, { it.label }) { onChange(config.copy(mode = it)) }
                if (config.mode == Mode.P25_PHASE1) {
                    Toggle(
                        "Simulcast (LSM)",
                        "For simulcast sites, which send CQPSK. sdrtrunk calls this LSM modulation.",
                        config.simulcast,
                    ) { onChange(config.copy(simulcast = it)) }
                }
                Label("Voice channels followed at once: " + if (config.maxTrafficChannels == 0) "off" else config.maxTrafficChannels.toString())
                StyledSlider(config.maxTrafficChannels.toFloat(), 0f..4f, steps = 3) {
                    onChange(config.copy(maxTrafficChannels = it.roundToInt()))
                }
            }

            Section("Radio source") {
                Choices(SourceType.entries, config.source, { it.label }) { onChange(config.copy(source = it)) }
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
                Label("Sample rate (wider follows more voice channels and uses more battery)")
                Choices(IqSource.RTL_SAMPLE_RATES.toList(), config.sampleRate, { "%.2f MS/s".format(it / 1e6) }) {
                    onChange(config.copy(sampleRate = it))
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
                    label = { Text("Tuner centre (MHz)") },
                    placeholder = { Text("Automatic") },
                    supportingText = {
                        Text("Voice channels within ±%.0f kHz of the centre can be followed.".format(config.sampleRate * 0.42 / 1000))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("Tuner") {
                val gainIndex = if (config.gainTenthsDb == IqSource.AUTO_GAIN) -1 else
                    IqSource.GAIN_STEPS.indexOfFirst { it >= config.gainTenthsDb }.let { if (it < 0) IqSource.GAIN_STEPS.lastIndex else it }
                Label("Gain: " + if (gainIndex < 0) "automatic" else "%.1f dB".format(IqSource.GAIN_STEPS[gainIndex] / 10.0))
                StyledSlider(gainIndex.toFloat(), -1f..IqSource.GAIN_STEPS.lastIndex.toFloat(), steps = IqSource.GAIN_STEPS.size - 1) { value ->
                    val index = value.roundToInt()
                    onChange(config.copy(gainTenthsDb = if (index < 0) IqSource.AUTO_GAIN else IqSource.GAIN_STEPS[index]))
                }
                OutlinedTextField(
                    value = ppmText,
                    onValueChange = { text ->
                        ppmText = text
                        text.toIntOrNull()?.takeIf { it in -200..200 }?.let { onChange(config.copy(ppm = it)) }
                    },
                    label = { Text("Frequency correction (ppm)") },
                    supportingText = { Text("The scanner screen offers a correction once a control channel locks.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Toggle("Bias tee", "Powers an LNA through the antenna jack (RTL-SDR Blog dongles).", config.biasTee) {
                    onChange(config.copy(biasTee = it))
                }
            }

            Section("Scan") {
                Label("Listen %.1f s to each quiet channel".format(config.scanDwellMs / 1000.0))
                StyledSlider(config.scanDwellMs.toFloat(), 200f..3_000f) {
                    onChange(config.copy(scanDwellMs = (it / 100).roundToInt() * 100))
                }
                Label("Wait %.1f s after a call for the reply".format(config.scanHangMs / 1000.0))
                StyledSlider(config.scanHangMs.toFloat(), 0f..10_000f) {
                    onChange(config.copy(scanHangMs = (it / 500).roundToInt() * 500))
                }
            }

            Section("Audio and recording") {
                Label("NBFM squelch: " + if (config.squelch <= 0f) "off" else "%.2f".format(config.squelch))
                StyledSlider(config.squelch, 0f..2f) { onChange(config.copy(squelch = it)) }
                Label("Record calls as WAV files in Music/SDRNova T25")
                Choices(RecordMode.entries, config.recordMode, { it.label }) { onChange(config.copy(recordMode = it)) }
            }

            Section("Quick aliases") {
                OutlinedTextField(
                    value = config.aliasText,
                    onValueChange = { onChange(config.copy(aliasText = it)) },
                    label = { Text("Talkgroup names") },
                    placeholder = { Text("1201,Fire Dispatch\n1305,PD Main") },
                    supportingText = { Text("One per line. RadioReference CSV works too, and imported sdrtrunk aliases are used as well.") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
            Text(
                "Saving while the radio runs stops it, so the new settings take effect when you start it again.",
                modifier = Modifier.padding(top = 6.dp),
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionTitle(title)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).background(CardSurface, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Ink.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val on = option == selected
            Text(
                label(option),
                modifier = Modifier
                    .border(1.dp, if (on) Cyan else Outline, RoundedCornerShape(8.dp))
                    .background(if (on) Cyan.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (on) Cyan else Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(detail, color = Muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Color(0xFF00363D)),
        )
    }
}

@Composable
private fun StyledSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Hairline),
        modifier = Modifier.height(32.dp),
    )
}
