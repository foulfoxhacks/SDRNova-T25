package com.sdrnova.t25.radio

import android.content.Context
import android.net.Uri
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.playlist.PlaylistAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one copy of the channel list: the screens show it, the web dashboard and RadioReference import change it, and
 * every change is saved and handed to the running radio.
 */
class PlaylistRepository(context: Context, private val engine: RadioEngine) : PlaylistAccess {
    private val store = PlaylistStore(context)
    private val current = MutableStateFlow(store.load())

    val flow: StateFlow<Playlist> = current

    override val playlist: Playlist get() = current.value

    @Synchronized
    override fun update(transform: (Playlist) -> Playlist): Playlist {
        val next = transform(current.value)
        store.save(next)
        current.value = next
        engine.playlistChanged()
        return next
    }

    fun read(uri: Uri): Playlist = store.read(uri)

    fun export(uri: Uri) = store.export(uri, playlist)
}
