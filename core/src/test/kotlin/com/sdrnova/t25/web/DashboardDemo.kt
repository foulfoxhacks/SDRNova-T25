package com.sdrnova.t25.web

import com.sdrnova.t25.playlist.Playlist
import com.sdrnova.t25.playlist.PlaylistAccess
import com.sdrnova.t25.playlist.PlaylistImporter
import java.net.InetAddress

/** `./gradlew :core:dashboardDemo`: the dashboard with a sample playlist, for previewing the page in a browser. */
fun main() {
    val sample = """
        <playlist version="4">
          <alias list="County" name="Fire Dispatch" group="Fire"><id type="talkgroup" value="1201" protocol="APCO25"/><id type="priority" priority="1"/><id type="record"/></alias>
          <alias list="County" name="Interop"><id type="talkgroupRange" min="9000" max="9099" protocol="APCO25"/></alias>
          <alias list="County" name="Encrypted PD"><id type="talkgroup" value="2201" protocol="APCO25"/><id type="priority" priority="-1"/></alias>
          <channel system="County P25" site="Simulcast 1" name="Control" enabled="true"><alias_list_name>County</alias_list_name>
            <source_configuration type="sourceConfigTunerMultipleFrequency"><frequency>851012500</frequency><frequency>851262500</frequency></source_configuration>
            <decode_configuration type="decodeConfigP25Phase1" modulation="LSM"/></channel>
          <channel system="County P25" site="North" name="North site" enabled="false"><alias_list_name>County</alias_list_name>
            <source_configuration type="sourceConfigTuner" frequency="852462500"/><decode_configuration type="decodeConfigP25Phase1" modulation="C4FM"/></channel>
          <channel system="Ham" name="DMR repeater" enabled="true"><source_configuration type="sourceConfigTuner" frequency="443500000"/><decode_configuration type="decodeConfigDMR"/></channel>
          <channel system="Ham" name="Simplex" enabled="true"><source_configuration type="sourceConfigTuner" frequency="146520000"/><decode_configuration type="decodeConfigNBFM"/></channel>
          <channel system="Air" name="Tower" enabled="false"><source_configuration type="sourceConfigTuner" frequency="118300000"/><decode_configuration type="decodeConfigAM"/></channel>
        </playlist>
    """.trimIndent()
    val access = object : PlaylistAccess {
        @Volatile
        override var playlist: Playlist = PlaylistImporter.parse(sample.byteInputStream())

        @Synchronized
        override fun update(transform: (Playlist) -> Playlist): Playlist = transform(playlist).also { playlist = it }
    }
    val server = MiniHttpServer(InetAddress.getLoopbackAddress(), 8025, Dashboard(access, "123456")::handle).start()
    println("Dashboard on http://127.0.0.1:${server.port} (PIN 123456)")
    Thread.currentThread().join()
}
