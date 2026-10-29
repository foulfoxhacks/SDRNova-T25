package com.sdrnova.t25.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrnova.t25.receiver.Mode
import java.text.SimpleDateFormat
import java.util.Locale

/** A slim header bar in the panel colour, used at the top of every screen. */
@Composable
fun ScreenBar(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Muted)
        }
        actions()
    }
}

/** Small coloured label, as on a scanner display: mode, timeslot, audio, encrypted. */
@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.background(color.copy(alpha = 0.22f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

@Composable
fun StatusDot(color: Color, size: Int = 9) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** One line of the info panel: a coloured icon, then text. */
@Composable
fun InfoRow(icon: ImageVector, tint: Color, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        content()
    }
}

/** A horizontal meter, 0..1. */
@Composable
fun LevelBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(6.dp).background(Hairline, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(color, RoundedCornerShape(3.dp)))
    }
}

/** Section title inside lists and settings. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, color: Color = Cyan) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

/** A rounded panel for grouping, used by settings and the call display. */
@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).background(CardSurface, RoundedCornerShape(12.dp)),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

@Composable
fun EmptyNote(text: String) {
    Text(text, modifier = Modifier.padding(20.dp), color = Muted, style = MaterialTheme.typography.bodyMedium)
}

fun modeColor(mode: Mode): Color = when (mode) {
    Mode.P25_PHASE1, Mode.P25_PHASE2 -> Cyan
    Mode.DMR -> Violet
    Mode.NBFM -> GoodText
}

fun modeShort(mode: Mode, simulcast: Boolean = false): String = when (mode) {
    Mode.P25_PHASE1 -> if (simulcast) "P25 LSM" else "P25"
    Mode.P25_PHASE2 -> "P25 P2"
    Mode.DMR -> "DMR"
    Mode.NBFM -> "NBFM"
}

/** m:ss for call timers. */
fun duration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)
