package com.sdrnova.t25.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrnova.t25.playlist.AliasBook
import com.sdrnova.t25.radio.LogLine
import com.sdrnova.t25.radio.TrunkingView
import com.sdrnova.t25.trunking.Call
import com.sdrnova.t25.trunking.TrunkingTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class CallsView(val label: String) { HEARD("Heard"), GRANTS("Grants"), LOG("Event log") }

/** Everything that happened: calls decoded, grants seen on the control channel, and the event log. */
@Composable
fun CallsScreen(trunking: TrunkingView, aliases: AliasBook, log: List<LogLine>, running: Boolean) {
    var view by rememberSaveable { mutableStateOf(CallsView.HEARD) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(Canvas)) {
        ScreenBar(
            title = "Calls",
            subtitle = "${trunking.heard.size} heard · ${trunking.recent.size} grants · ${log.size} events",
        ) {
            if (view == CallsView.LOG && log.isNotEmpty()) {
                IconButton(onClick = {
                    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    val text = log.asReversed().joinToString("\n") { "${stamp.format(Date(it.time))}  ${it.text}" }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "SDRNova T25 event log")
                        .putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share event log"))
                }) { Icon(Icons.Filled.Share, contentDescription = "Share event log", tint = Cyan) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CallsView.entries.forEach { option ->
                val selected = option == view
                Text(
                    option.label,
                    modifier = Modifier.weight(1f)
                        .border(1.dp, if (selected) Cyan else Outline, RoundedCornerShape(8.dp))
                        .background(if (selected) Cyan.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { view = option }
                        .padding(vertical = 8.dp),
                    color = if (selected) Cyan else Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        when (view) {
            CallsView.HEARD -> LazyColumn(Modifier.fillMaxSize()) {
                if (trunking.heard.isEmpty()) {
                    item { EmptyNote(if (running) "No calls decoded yet. They'll appear as they end." else "Start the radio to hear calls.") }
                }
                items(trunking.heard) { RecentCallTile(it, aliases) }
            }
            CallsView.GRANTS -> LazyColumn(Modifier.fillMaxSize()) {
                if (trunking.active.isEmpty() && trunking.recent.isEmpty()) {
                    item {
                        EmptyNote(
                            "Voice grants from a P25 control channel show here, including calls outside the tuner's " +
                                "range (${trunking.bands.size} bands, ${trunking.neighbors.size} neighbor sites heard).",
                        )
                    }
                }
                if (trunking.active.isNotEmpty()) {
                    item { SectionTitle("Active now") }
                    items(trunking.active, key = { "a${it.unitToUnit}${it.talkgroup}" }) { GrantRow(it, aliases, live = true) }
                }
                if (trunking.recent.isNotEmpty()) {
                    item { SectionTitle("Recent") }
                    items(trunking.recent) { GrantRow(it, aliases, live = false) }
                }
            }
            CallsView.LOG -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                if (log.isEmpty()) item { EmptyNote("Decoded messages and call events appear here.") }
                items(log) { line ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(TIME.format(Date(line.time)), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
                        Spacer(Modifier.width(8.dp))
                        Text(line.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun GrantRow(call: Call, aliases: AliasBook, live: Boolean) {
    val alias = if (call.unitToUnit) null else aliases.alias(call.talkgroup)
    val bar = when {
        call.emergency -> Alert
        call.encrypted -> Amber
        else -> alias?.color?.let { Color(it) } ?: Outline
    }
    Row(
        Modifier.fillMaxWidth().background(if (call.emergency) Alert.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(34.dp).background(bar))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                alias?.name ?: if (call.unitToUnit) "Private ${call.talkgroup}" else "TG ${call.talkgroup}",
                color = if (call.emergency) Alert else Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val details = buildList {
                if (alias != null) add("TG ${call.talkgroup}")
                add(call.frequencyHz?.let(TrunkingTracker::mhz) ?: "channel ${call.channel}")
                call.slot?.let { add("TS${it + 1}") }
                call.source?.takeIf { it != 0 }?.let { add("from " + (aliases.radioName(it) ?: it.toString())) }
            }.joinToString(" · ")
            Text(details, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            val seconds = ((call.lastSeen - call.firstSeen) / 1000).coerceAtLeast(1)
            Text(
                if (live) duration(seconds) else TIME.format(Date(call.lastSeen)),
                color = if (live) GoodText else Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (call.emergency) Chip("Emergency", Alert)
                if (call.encrypted) Chip("Enc", Amber)
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 25.dp).height(1.dp).background(Hairline))
}
