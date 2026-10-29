package com.sdrnova.t25.radio

import android.content.Context
import android.net.Uri
import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.playlist.PlaylistImporter
import com.sdrnova.t25.playlist.PlaylistWriter
import java.io.File
import java.io.IOException

/** The channels and aliases, kept as sdrtrunk playlist XML in app storage so they can be exported again as-is. */
class PlaylistStore(private val context: Context) {
    private val file get() = File(context.filesDir, "playlist.xml")

    fun load(): Playlist = runCatching { file.inputStream().use(PlaylistImporter::parse) }.getOrDefault(Playlist.EMPTY)

    fun save(playlist: Playlist) {
        val temp = File(context.filesDir, "playlist.xml.tmp")
        temp.writeText(PlaylistWriter.write(playlist))
        if (!temp.renameTo(file)) throw IOException("Couldn't save the playlist")
    }

    /** Reads an sdrtrunk playlist (playlist_v4.xml) the user picked; throws if it isn't one. */
    fun read(uri: Uri): Playlist =
        context.contentResolver.openInputStream(uri)?.use(PlaylistImporter::parse) ?: throw IOException("Couldn't open the file")

    fun export(uri: Uri, playlist: Playlist) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't write the file")
        output.use { it.write(PlaylistWriter.write(playlist).toByteArray()) }
    }
}
