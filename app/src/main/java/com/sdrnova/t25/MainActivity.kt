package com.sdrnova.t25

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.radio.ConfigStore
import com.sdrnova.t25.radio.PlaylistStore
import com.sdrnova.t25.radio.RadioConfig
import com.sdrnova.t25.radio.RadioService
import com.sdrnova.t25.radio.SourceType
import com.sdrnova.t25.source.UsbRadios
import com.sdrnova.t25.ui.MainScreen
import com.sdrnova.t25.ui.SdrNovaTheme

class MainActivity : ComponentActivity() {
    private val engine get() = (application as SdrNovaApp).engine
    private lateinit var store: ConfigStore
    private lateinit var playlists: PlaylistStore
    private var playlist by mutableStateOf(Playlist.EMPTY)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startWithUsbCheck() }

    private val importPlaylist = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        // Big playlists take a moment to parse; keep it off the main thread.
        Thread {
            val result = runCatching { playlists.read(uri) }
            runOnUiThread {
                result.onSuccess { imported ->
                    updatePlaylist(imported)
                    toast("Imported ${imported.channels.size} channels and ${imported.aliases.size} aliases")
                }.onFailure { toast("Couldn't import that file: ${it.message}") }
            }
        }.start()
    }

    private val exportPlaylist = registerForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { playlists.export(uri, playlist) }
            .onSuccess { toast("Exported ${playlist.channels.size} channels") }
            .onFailure { toast("Couldn't export: ${it.message}") }
    }


    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                RadioService.start(this@MainActivity)
            } else {
                Toast.makeText(context, "USB permission denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = ConfigStore(this)
        playlists = PlaylistStore(this)
        playlist = playlists.load()
        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent {
            SdrNovaTheme {
                MainScreen(
                    engine = engine,
                    initialConfig = store.load(),
                    playlist = playlist,
                    onStart = ::start,
                    onStop = { RadioService.stop(this) },
                    onLiveChange = { config ->
                        val previous = store.load()
                        store.save(config)
                        if (config.gainTenthsDb != previous.gainTenthsDb) engine.setGain(config.gainTenthsDb)
                        if (config.volumeDb != previous.volumeDb) engine.setVolumeDb(config.volumeDb)
                        if (config.ppm != previous.ppm) engine.setPpm(config.ppm)
                        if (config.tuning != previous.tuning) engine.select(config.tuning)
                        if (config.scan != previous.scan) engine.setScan(config.scan)
                    },
                    onImportPlaylist = { importPlaylist.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                    onExportPlaylist = { exportPlaylist.launch("playlist_v4.xml") },
                    onPlaylistChange = ::updatePlaylist,
                    onSave = { config ->
                        store.save(config)
                        if (engine.status.value.running) {
                            RadioService.stop(this)
                            Toast.makeText(this, "Saved. Press Start to apply.", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermissionReceiver)
        super.onDestroy()
    }

    private fun updatePlaylist(updated: Playlist) {
        runCatching { playlists.save(updated) }.onFailure {
            toast("Couldn't save the channel list: ${it.message}")
            return
        }
        playlist = updated
        engine.playlistChanged()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun start(config: RadioConfig) {
        store.save(config)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The radio runs either way; the answer only decides whether its notification shows.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startWithUsbCheck()
        }
    }

    private fun startWithUsbCheck() {
        if (store.load().source != SourceType.USB) {
            RadioService.start(this)
            return
        }
        val device = UsbRadios.find(this)
        if (device == null) {
            Toast.makeText(this, "Plug in an RTL-SDR or HackRF (use a USB OTG adapter)", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(UsbManager::class.java)
        if (manager.hasPermission(device)) {
            RadioService.start(this)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
            manager.requestPermission(device, intent)
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.sdrnova.t25.USB_PERMISSION"
    }
}
