package com.hazbu.kycbypass

import android.app.Application
import com.hazbu.kycbypass.utils.SettingsManager

class KycBypassApp : Application() {

    companion object {
        @JvmStatic
        lateinit var instance: KycBypassApp
            private set

        @JvmStatic
        val settingsManager = SettingsManager()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsManager.init(this)
    }
}
