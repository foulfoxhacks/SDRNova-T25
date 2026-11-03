package com.sdrnova.t25.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.radio.RadioReferenceAccount
import com.sdrnova.t25.radioreference.RadioReferenceClient
import com.sdrnova.t25.radioreference.RadioReferenceException
import com.sdrnova.t25.radioreference.RrCategory
import com.sdrnova.t25.radioreference.RrCounty
import com.sdrnova.t25.radioreference.RrPlaylist
import com.sdrnova.t25.radioreference.RrSubcategory
import com.sdrnova.t25.radioreference.RrSystemRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What an import would add, shown for confirmation before it touches the channel list. */
private data class Preview(val title: String, val detail: String, val playlist: Playlist)

/**
 * Pulls channels and talkgroups from RadioReference: sign in, enter a zip code, then pick a trunked system or a group
 * of conventional frequencies from that county.
 */
@Composable
fun RadioReferenceScreen(account: RadioReferenceAccount, onAdd: (Playlist) -> Unit, onClose: () -> Unit) {
    var saved by remember { mutableStateOf(account.load()) }
    var zip by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var county by remember { mutableStateOf<RrCounty?>(null) }
    var typeNames by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val needsKey = account.bundledAppKey.isEmpty()
    var client by remember { mutableStateOf<Pair<RadioReferenceAccount.Saved, RadioReferenceClient>?>(null) }

    fun clientFor(): RadioReferenceClient {
        client?.takeIf { it.first == saved }?.let { return it.second }
        return RadioReferenceClient(account.auth(saved)).also { client = saved to it }
    }

    /** Runs a lookup off the main thread with a progress label, turning failures into a readable message. */
    fun <T> load(label: String, work: (RadioReferenceClient) -> T, done: (T) -> Unit) {
        val missing = when {
            saved.username.isBlank() -> "Enter your RadioReference username."
            saved.password.isEmpty() -> "Enter your RadioReference password."
            needsKey && saved.appKey.isBlank() -> "Enter a RadioReference app key."
            else -> null
        }
        if (missing != null) {
            error = missing
            return
        }
        account.save(saved)
        val api = clientFor()
        busy = label
        error = null
        added = null
        scope.launch {
            try {
                done(withContext(Dispatchers.IO) { work(api) })
            } catch (e: RadioReferenceException) {
                error = e.message
            } catch (e: IOException) {
                error = "Couldn't reach RadioReference. Check the connection and try again."
            } finally {
                busy = null
            }
        }
    }

    fun openSystem(system: RrSystemRef) = load("Loading ${system.name}…", { api ->
        val typeName = typeNames[system.type] ?: api.systemTypeName(system.type)
        val sites = api.sites(system.id)
        val talkgroups = api.talkgroups(system.id)
        val categories = api.talkgroupCategories(system.id)
        val playlist = RrPlaylist.trunked(system.name, typeName, sites, talkgroups, categories, county?.id)
        val local = playlist.channels.count { it.enabled }
        val protocol = RrPlaylist.protocolForSystem(typeName)
        val detail = buildString {
            append("$typeName. ${playlist.channels.size} sites with control channels, $local in ${county?.name ?: "this"} county ")
            append("ticked for scanning, and ${playlist.aliases.size} talkgroup names.")
            if (!protocol.supported) append(" SDRNova can't decode this system type yet; it'll be added unticked.")
        }
        Preview(system.name, detail, playlist)
    }) { preview = it }

    fun openSubcategory(category: RrCategory, subcategory: RrSubcategory) = load("Loading ${subcategory.name}…", { api ->
        val frequencies = api.subcategoryFrequencies(subcategory.id)
        val system = listOfNotNull(county?.name?.let { "$it County" }, category.name).joinToString(" · ")
        val playlist = RrPlaylist.conventional(system, subcategory.name, frequencies, api::modeName)
        val decodable = playlist.channels.count { it.protocol.supported }
        Preview(
            subcategory.name,
            "${playlist.channels.size} frequencies, $decodable that SDRNova decodes (P25, DMR and NBFM). The rest are added unticked.",
            playlist,
        )
    }) { preview = it }

    Column(Modifier.fillMaxSize().background(Canvas)) {
        ScreenBar("RadioReference", "Import frequencies and talkgroups by zip code") {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Ink) }
        }
        if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Cyan, trackColor = Hairline)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SectionTitle("Your account")
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp).background(CardSurface, RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = saved.username,
                        onValueChange = { saved = saved.copy(username = it) },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = saved.password,
                        onValueChange = { saved = saved.copy(password = it) },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (needsKey) {
                        OutlinedTextField(
                            value = saved.appKey,
                            onValueChange = { saved = saved.copy(appKey = it) },
                            label = { Text("App key") },
                            supportingText = {
                                Text("RadioReference gives each app its own web service key. Request one from your RadioReference account and paste it here.")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remember my password", color = Ink, fontSize = 15.sp)
                            Text("Stored encrypted on this phone", color = Muted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = saved.remember,
                            onCheckedChange = { saved = saved.copy(remember = it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Color(0xFF00363D)),
                        )
                    }
                    Text(
                        "Needs a RadioReference Premium Subscription. Your login is sent only to radioreference.com, over HTTPS.",
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
            item {
                SectionTitle("Find your area")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = zip,
                        onValueChange = { text -> zip = text.filter(Char::isDigit).take(5) },
                        label = { Text("US zip code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        val code = zip.toIntOrNull()
                        if (zip.length != 5 || code == null) {
                            error = "Enter a 5-digit zip code."
                        } else {
                            load("Looking up $zip…", { api ->
                                val found = api.county(api.zipcode(code).countyId)
                                found to found.systems.map { it.type }.distinct().associateWith { type ->
                                    runCatching { api.systemTypeName(type) }.getOrDefault("")
                                }
                            }) { (found, types) ->
                                county = found
                                typeNames = types
                            }
                        }
                    }) { Text("Find") }
                }
                busy?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Cyan, fontSize = 13.sp) }
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth().padding(10.dp).background(Alert.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).padding(12.dp),
                        color = Alert, fontSize = 13.sp,
                    )
                }
                added?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth().padding(10.dp).background(Good.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).padding(12.dp),
                        color = GoodText, fontSize = 13.sp,
                    )
                }
            }
            county?.let { found ->
                item { SectionTitle("${found.name} County · trunked systems") }
                if (found.systems.isEmpty()) item { EmptyNote("No trunked systems are listed for this county.") }
                items(found.systems, key = { "s${it.id}" }) { system ->
                    PickRow(
                        title = system.name,
                        detail = listOf(typeNames[system.type].orEmpty(), system.city).filter { it.isNotEmpty() }.joinToString(" · "),
                        chip = typeNames[system.type]?.let { RrPlaylist.protocolForSystem(it) }?.let { protocol ->
                            protocol.mode?.let { modeShort(it) to modeColor(it) } ?: ("Not yet" to Muted)
                        },
                    ) { if (busy == null) openSystem(system) }
                }
                if (found.categories.isNotEmpty()) item { SectionTitle("${found.name} County · conventional") }
                for (category in found.categories) {
                    item(key = "c${category.id}") {
                        Text(category.name, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    items(category.subcategories, key = { "sc${category.id}-${it.id}" }) { subcategory ->
                        PickRow(subcategory.name, null, null) { if (busy == null) openSubcategory(category, subcategory) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    preview?.let { pending ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Add ${pending.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.detail)
                    pending.playlist.channels.take(6).forEach { channel ->
                        Text(
                            "${channel.name} · " + channel.frequencies.take(2).joinToString(", ") { "%.4f".format(it / 1e6) },
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (pending.playlist.channels.size > 6) Text("and ${pending.playlist.channels.size - 6} more", fontSize = 12.sp, color = Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAdd(pending.playlist)
                    added = "Added ${pending.title}: ${pending.playlist.channels.size} channels and ${pending.playlist.aliases.size} talkgroup names. " +
                        "Anything already on the phone was skipped."
                    preview = null
                }) { Text("Add to channels") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PickRow(title: String, detail: String?, chip: Pair<String, Color>?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) Text(detail, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        chip?.let { (label, color) ->
            Chip(label, color)
            Spacer(Modifier.width(8.dp))
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp))
    }
}
