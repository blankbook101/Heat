package com.heat.app

import android.app.Application
import com.heat.app.core.HeatEngine

class HeatApp : Application() {
    lateinit var engine: HeatEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = HeatEngine(this)
    }
}
