package com.sdrnova.t25.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrnova.t25.playlist.ChannelProtocol
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.playlist.PlaylistChannel
import com.sdrnova.t25.radio.EngineStatus
import com.sdrnova.t25.radio.RadioConfig
import com.sdrnova.t25.radio.Tuning

/** The playlist as a grid of channel cards: tap to listen, tick to scan, long-press to remove. */
@Composable
fun ChannelsScreen(
    playlist: Playlist,
    config: RadioConfig,
    status: EngineStatus,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onPick: (PlaylistChannel) -> Unit,
    onChange: (Playlist) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<PlaylistChannel?>(null) }
    val active = status.running || status.starting
    val hereHz = if (active) status.frequencyHz else config.frequencyHz
    val hereMode = if (active) status.mode else config.mode
    val scannable = playlist.channels.count { it.enabled && Tuning.of(it) != null }
    val empty = playlist.channels.isEmpty() && playlist.aliases.isEmpty()

    Column(Modifier.fillMaxSize().background(Canvas)) {
        ScreenBar(
            title = "Channels",
            subtitle = if (empty) null else "${playlist.channels.size} channels · $scannable in scan · ${playlist.aliases.size} aliases",
        ) {
            IconButton(onClick = onImport) { Icon(Glyphs.Import, contentDescription = "Import sdrtrunk playlist", tint = Cyan) }
            if (!empty) IconButton(onClick = onExport) { Icon(Glyphs.Export, contentDescription = "Export playlist", tint = Ink) }
            IconButton(onClick = {
                val channel = PlaylistChannel(
                    name = "%.4f MHz".format(hereHz / 1e6), system = "SDRNova", site = "", enabled = true,
                    frequencies = listOf(hereHz), protocol = ChannelProtocol.of(hereMode), decoderType = "",
                    modulation = if (hereMode == com.sdrnova.t25.receiver.Mode.P25_PHASE1 && config.simulcast) "LSM" else null,
                    aliasList = config.aliasList.ifEmpty { null },
                )
                onChange(playlist.copy(channels = playlist.channels + channel))
            }) { Icon(Icons.Filled.Add, contentDescription = "Add the current frequency", tint = Ink) }
            if (!empty) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, contentDescription = "Clear all", tint = Muted) }
        }

        if (empty) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Glyphs.Grid, contentDescription = null, tint = Outline, modifier = Modifier.size(48.dp))
                Text("Bring over your sdrtrunk channels", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Import playlist_v4.xml from sdrtrunk (it's in SDRTrunk/playlist on the desktop). Channels, alias " +
                        "lists, talkgroup names, priorities and lockouts all come across.",
                    color = Muted, fontSize = 14.sp,
                )
                Button(onClick = onImport) { Text("Import sdrtrunk playlist") }
                OutlinedButton(onClick = {
                    val channel = PlaylistChannel(
                        "%.4f MHz".format(hereHz / 1e6), "SDRNova", "", true, listOf(hereHz), ChannelProtocol.of(hereMode), "", null, null,
                    )
                    onChange(playlist.copy(channels = playlist.channels + channel))
                }) { Text("Add %.4f MHz".format(hereHz / 1e6)) }
            }
        } else {
            ChannelGrid(playlist, config, hereHz, onPick, onChange) { removing = it }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all channels and aliases?") },
            text = { Text("This removes the imported playlist from the phone. Your sdrtrunk file isn't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    onChange(Playlist.EMPTY)
                    confirmClear = false
                }) { Text("Clear", color = Alert) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    removing?.let { channel ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${channel.name.ifEmpty { "this channel" }}?") },
            confirmButton = {
                TextButton(onClick = {
                    onChange(playlist.copy(channels = playlist.channels.filter { it !== channel }))
                    removing = null
                }) { Text("Remove", color = Alert) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChannelGrid(
    playlist: Playlist,
    config: RadioConfig,
    hereHz: Long,
    onPick: (PlaylistChannel) -> Unit,
    onChange: (Playlist) -> Unit,
    onRemove: (PlaylistChannel) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((system, channels) in playlist.channels.groupBy { it.system.ifEmpty { "Other" } }) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-$system") {
                Text(
                    "$system · ${channels.size}",
                    modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
                    color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            items(channels) { channel ->
                ChannelCard(
                    channel = channel,
                    current = channel.name == config.channelName && channel.frequencies.firstOrNull() == hereHz,
                    onPick = { onPick(channel) },
                    onToggleScan = {
                        onChange(playlist.copy(channels = playlist.channels.map { if (it === channel) it.copy(enabled = !it.enabled) else it }))
                    },
                    onLongPress = { onRemove(channel) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Tap a card to listen. Tick it to include it in the scan. Long-press to remove it.",
                modifier = Modifier.padding(6.dp, 8.dp),
                color = Muted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ChannelCard(
    channel: PlaylistChannel,
    current: Boolean,
    onPick: () -> Unit,
    onToggleScan: () -> Unit,
    onLongPress: () -> Unit,
) {
    val tuning = Tuning.of(channel)
    val supported = tuning != null
    val color = tuning?.let { modeColor(it.mode) } ?: Muted
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (supported) 1f else 0.5f)
            .background(CardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, if (current) Cyan else Hairline, RoundedCornerShape(10.dp))
            .pointerInput(channel) {
                detectTapGestures(onTap = { if (supported) onPick() }, onLongPress = { onLongPress() })
            }
            .padding(start = 10.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                channel.name.ifEmpty { "(unnamed)" },
                color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { if (supported) onToggleScan() }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (channel.enabled && supported) Glyphs.Checked else Glyphs.Unchecked,
                    contentDescription = if (channel.enabled) "In scan" else "Not in scan",
                    tint = if (channel.enabled && supported) Cyan else Muted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            channel.frequencies.firstOrNull()?.let { "%.5f".format(it / 1e6) } ?: "no frequency",
            color = Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
        )
        if (channel.site.isNotEmpty()) Text(channel.site, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip(
                if (tuning != null) modeShort(tuning.mode, tuning.simulcast) else "${channel.protocol.label} · not yet",
                color,
            )
            if (channel.frequencies.size > 1) Chip("+${channel.frequencies.size - 1}", Muted)
        }
    }
}
