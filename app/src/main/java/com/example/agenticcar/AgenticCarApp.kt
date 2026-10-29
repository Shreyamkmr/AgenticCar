package com.example.agenticcar

import android.app.Application
import com.example.agenticcar.data.LiveHistory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AgenticCarApp : Application() {
    /** Injected only to start recording trend history with the process. */
    @Inject lateinit var liveHistory: LiveHistory
}
