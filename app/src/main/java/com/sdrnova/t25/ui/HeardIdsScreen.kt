package com.sdrnova.t25.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrnova.t25.playlist.HeardIds
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** What the groups and radios lists can do, wired to the engine by [MainScreen]. */
class HeardIdActions(
    val onSaveGroup: (HeardIds.Entry) -> Unit,
    val onSaveRadio: (HeardIds.Entry) -> Unit,
    val onRemoveGroup: (HeardIds.Entry) -> Unit,
    val onRemoveRadio: (HeardIds.Entry) -> Unit,
    val onHold: (Int?) -> Unit,
    val onImport: () -> Unit,
    val onExportGroups: () -> Unit,
    val onExportRadios: () -> Unit,
)

/**
 * DSD+-style lists that fill themselves in: every talkgroup (or radio) heard, with its hit count and when it was last
 * heard. Tap one to name it, rank it or lock it out.
 */
@Composable
fun HeardIdList(entries: List<HeardIds.Entry>, radios: Boolean, held: Int?, actions: HeardIdActions) {
    var editing by remember { mutableStateOf<HeardIds.Entry?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            item {
                EmptyNote(
                    (if (radios) "Radios" else "Talkgroups") + " fill in here as calls are heard, with hit counts and " +
                        "last-heard times. Import a DSDPlus." + (if (radios) "radios" else "groups") + " file to bring " +
                        "names, priorities and lockouts over from DSD+.",
                )
            }
        } else {
            item {
                val locked = entries.count { it.lockout }
                val named = entries.count { it.alias.isNotEmpty() }
                Text(
                    "${entries.size} heard · $named named" + if (locked > 0) " · $locked locked out" else "",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = Muted, fontSize = 12.sp,
                )
            }
        }
        items(entries, key = { it.key }) { entry ->
            HeardIdRow(entry, radios, held = !radios && entry.id == held) { editing = entry }
        }
    }
    editing?.let { entry ->
        HeardIdEditor(
            entry = entry,
            radio = radios,
            held = !radios && entry.id == held,
            onDismiss = { editing = null },
            onSave = {
                if (radios) actions.onSaveRadio(it) else actions.onSaveGroup(it)
                editing = null
            },
            onRemove = {
                if (radios) actions.onRemoveRadio(entry) else actions.onRemoveGroup(entry)
                editing = null
            },
            onHold = { hold ->
                actions.onHold(if (hold) entry.id else null)
                editing = null
            },
        )
    }
}

@Composable
private fun HeardIdRow(entry: HeardIds.Entry, radio: Boolean, held: Boolean, onClick: () -> Unit) {
    val bar = when {
        entry.lockout -> Alert
        held -> Amber
        entry.alias.isNotEmpty() -> Cyan
        else -> Outline
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(bar))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.alias.ifEmpty { if (radio) "Radio ${entry.id}" else "TG ${entry.id}" },
                    color = if (entry.lockout) Muted else Ink, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.lockout) Chip("L/O", Alert)
                if (held) Chip("Hold", Amber)
                if (entry.priority != HeardIds.DEFAULT_PRIORITY) Chip("P${entry.priority}", Ink.copy(alpha = 0.75f))
            }
            val details = buildList {
                if (entry.alias.isNotEmpty()) add((if (radio) "ID " else "TG ") + entry.id)
                if (radio && entry.group != 0) add("on TG ${entry.group}")
                add("${entry.protocol} ${entry.network}")
                add(if (entry.hits == 1) "1 hit" else "${entry.hits} hits")
            }.joinToString(" · ")
            Text(details, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(lastHeard(entry.lastHeard), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
    Box(Modifier.fillMaxWidth().padding(start = 25.dp).height(1.dp).background(Hairline))
}

@Composable
private fun HeardIdEditor(
    entry: HeardIds.Entry,
    radio: Boolean,
    held: Boolean,
    onDismiss: () -> Unit,
    onSave: (HeardIds.Entry) -> Unit,
    onRemove: () -> Unit,
    onHold: (Boolean) -> Unit,
) {
    var alias by remember { mutableStateOf(entry.alias) }
    var priority by remember { mutableFloatStateOf(entry.priority.toFloat()) }
    var lockout by remember { mutableStateOf(entry.lockout) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Column {
                Text((if (radio) "Radio " else "Talkgroup ") + entry.id, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${entry.protocol} ${entry.network} · ${entry.hits} hits · last ${lastHeard(entry.lastHeard)}",
                    color = Muted, fontSize = 12.sp,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it.replace("\"", "'") },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Cyan, focusedLabelColor = Cyan, cursorColor = Cyan,
                        focusedTextColor = Ink, unfocusedTextColor = Ink,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!radio) {
                    Column {
                        Row {
                            Text("Priority", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(
                                if (priority.roundToInt() == HeardIds.DEFAULT_PRIORITY) "50 (default)" else priority.roundToInt().toString(),
                                color = Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                            )
                        }
                        Slider(
                            value = priority,
                            onValueChange = { priority = it.roundToInt().toFloat() },
                            valueRange = 1f..100f,
                            colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Hairline),
                        )
                        Text("Lower numbers take the speaker first, as in DSD+.", color = Muted, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Lock out", color = Ink, fontSize = 14.sp)
                            Text("Never followed or played", color = Muted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = lockout,
                            onCheckedChange = { lockout = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = Alert, checkedThumbColor = Color.White),
                        )
                    }
                    TextButton(onClick = { onHold(!held) }) {
                        Text(if (held) "Release hold" else "Hold on this talkgroup", color = Amber)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(entry.copy(alias = alias.trim(), priority = priority.roundToInt(), lockout = lockout))
            }) { Text("Save", color = Cyan) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) { Text("Forget", color = Alert) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
            }
        },
    )
}

/** Time of day for today, otherwise the date too. */
private fun lastHeard(time: Long): String {
    if (time <= 0) return "—"
    val then = Calendar.getInstance().apply { timeInMillis = time }
    val now = Calendar.getInstance()
    val today = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return if (today) TIME.format(Date(time)) else SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(time))
}
