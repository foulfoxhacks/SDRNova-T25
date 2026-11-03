package com.sdrnova.t25

import android.app.Application
import com.sdrnova.t25.radio.PlaylistRepository
import com.sdrnova.t25.radio.RadioEngine
import com.sdrnova.t25.radio.RadioReferenceAccount
import com.sdrnova.t25.radio.WebDashboard

class SdrNovaApp : Application() {
    val engine: RadioEngine by lazy { RadioEngine(this) }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(this, engine) }
    val dashboard: WebDashboard by lazy { WebDashboard(this, playlists) }
    val radioReference: RadioReferenceAccount by lazy { RadioReferenceAccount(this) }
}
