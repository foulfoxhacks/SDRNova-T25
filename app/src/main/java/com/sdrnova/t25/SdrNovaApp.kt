package com.sdrnova.t25

import android.app.Application
import com.sdrnova.t25.radio.RadioEngine

class SdrNovaApp : Application() {
    val engine: RadioEngine by lazy { RadioEngine(this) }
}
