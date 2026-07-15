package com.example.devcontrol

import android.app.Application
import com.example.devcontrol.data.AppContainer

class DevControlApp : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
}
