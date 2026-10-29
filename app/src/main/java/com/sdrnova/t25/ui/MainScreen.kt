package com.sdrnova.t25.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.radio.RadioConfig
import com.sdrnova.t25.radio.RadioEngine
import com.sdrnova.t25.radio.Tuning
import kotlinx.coroutines.delay

private enum class Screen(val label: String, val icon: ImageVector) {
    SCANNER("Scanner", Glyphs.Scanner),
    CALLS("Calls", Icons.AutoMirrored.Filled.List),
    CHANNELS("Channels", Glyphs.Grid),
    SPECTRUM("Spectrum", Glyphs.Waveform),
    SETTINGS("Settings", Icons.Filled.Settings),
}

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
    // The waterfall keeps filling while other screens are showing.
    val waterfall = remember { WaterfallImage() }
    LaunchedEffect(spectrum) { spectrum?.let(waterfall::push) }
    val live: (RadioConfig) -> Unit = { updated ->
        config = updated
        onLiveChange(updated)
    }
    var screen by rememberSaveable { mutableStateOf(Screen.SCANNER) }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(500)
            value = SystemClock.elapsedRealtime()
        }
    }

    Scaffold(
        containerColor = Canvas,
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                Screen.entries.forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cyan,
                            selectedTextColor = Cyan,
                            indicatorColor = Cyan.copy(alpha = 0.16f),
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                Screen.SCANNER -> ScannerScreen(
                    status = status,
                    trunking = trunking,
                    aliases = aliases,
                    config = config,
                    now = now,
                    onStart = { onStart(config) },
                    onStop = onStop,
                    onLiveChange = live,
                    onScanHold = engine::setScanHold,
                    onScanSkip = engine::skip,
                    onShowCalls = { screen = Screen.CALLS },
                )
                Screen.CALLS -> CallsScreen(trunking, aliases, log, running = status.running)
                Screen.CHANNELS -> ChannelsScreen(
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
                                    simulcast = tuning.simulcast,
                                ),
                            )
                            screen = Screen.SCANNER
                        }
                    },
                    onChange = onPlaylistChange,
                )
                Screen.SPECTRUM -> SpectrumScreen(spectrum, waterfall, status) { hz ->
                    live(config.copy(frequencyHz = hz, mode = status.mode, channelName = "", scan = false))
                }
                Screen.SETTINGS -> SettingsScreen(config, onChange = { config = it }, onSave = { onSave(config) })
            }
        }
    }
}
