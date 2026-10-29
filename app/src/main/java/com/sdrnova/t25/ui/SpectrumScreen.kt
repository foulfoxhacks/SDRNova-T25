package com.sdrnova.t25.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrnova.t25.radio.EngineStatus
import com.sdrnova.t25.trunking.TrunkingTracker
import kotlin.math.roundToInt

/** Spectrum over a waterfall for the whole tuner band. Tapping either tunes there, snapped to a 6.25 kHz raster. */
@Composable
fun SpectrumScreen(spectrum: FloatArray?, waterfall: WaterfallImage, status: EngineStatus, onTune: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().background(Canvas)) {
        ScreenBar(
            title = "Spectrum",
            subtitle = if (status.sampleRate > 0) {
                "Centre ${TrunkingTracker.mhz(status.centerHz)} · %.2f MHz wide".format(status.sampleRate / 1e6)
            } else {
                null
            },
        )
        if (spectrum == null || status.sampleRate == 0) {
            EmptyNote("Start the radio to see the band around the channel. Tap anywhere on it to tune there.")
        } else {
            SpectrumPlot(spectrum, waterfall, status.frequencyHz, status.centerHz, status.sampleRate, onTune)
        }
    }
}

@Composable
private fun SpectrumPlot(
    spectrum: FloatArray,
    waterfall: WaterfallImage,
    channelHz: Long,
    centerHz: Long,
    sampleRate: Int,
    onTune: (Long) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        val floor = waterfall.floorDb - 4f
        val top = waterfall.topDb + 4f
        val channelFraction = 0.5f + (channelHz - centerHz).toFloat() / sampleRate
        val tap = Modifier.pointerInput(centerHz, sampleRate) {
            detectTapGestures { position ->
                val hz = centerHz + ((position.x / size.width) - 0.5) * sampleRate
                onTune((hz / 6_250).roundToInt() * 6_250L)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().weight(0.38f)
                .background(PanelDeep, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .then(tap),
        ) {
            for (k in 1..3) {
                val y = size.height * k / 4
                drawLine(Hairline, Offset(0f, y), Offset(size.width, y))
            }
            val path = Path()
            spectrum.forEachIndexed { index, db ->
                val x = size.width * index / (spectrum.size - 1)
                val y = size.height * (1 - ((db - floor) / (top - floor)).coerceIn(0f, 1f))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLine(Muted.copy(alpha = 0.5f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
            val channelX = size.width * channelFraction
            drawLine(Amber, Offset(channelX, 0f), Offset(channelX, size.height), strokeWidth = 2f)
            drawPath(path, Cyan, style = Stroke(width = 2f))
        }
        Canvas(Modifier.fillMaxWidth().weight(0.62f).then(tap)) {
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
        Row {
            Chip("Tuned", Amber)
            Spacer(Modifier.width(6.dp))
            Text(TrunkingTracker.mhz(channelHz), color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("%.0f to %.0f dBFS".format(floor, top), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text(
            "Grey line: tuner centre (the dongle's DC spike sits there). Tap to tune.",
            modifier = Modifier.padding(top = 4.dp),
            color = Muted, fontSize = 12.sp,
        )
    }
}
