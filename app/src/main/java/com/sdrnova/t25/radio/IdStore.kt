package com.sdrnova.t25.radio

import android.content.Context
import android.net.Uri
import com.sdrnova.t25.playlist.HeardIds
import java.io.File
import java.io.IOException

/** Keeps the heard talkgroups and radios as DSDPlus.groups and DSDPlus.radios in app storage. */
class IdStore(private val context: Context) {
    private val groups get() = File(context.filesDir, "DSDPlus.groups")
    private val radios get() = File(context.filesDir, "DSDPlus.radios")

    fun load(): HeardIds = HeardIds().apply {
        runCatching { import(groups.readText()) }
        runCatching { import(radios.readText()) }
    }

    fun save(ids: HeardIds) {
        write(groups, ids.groupsFile())
        write(radios, ids.radiosFile())
    }

    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) throw IOException("Couldn't save ${file.name}")
    }

    /** Reads a DSD+ groups or radios file the user picked. */
    fun read(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw IOException("Couldn't open the file")

    fun export(uri: Uri, text: String) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't write the file")
        output.use { it.write(text.toByteArray()) }
    }
}
