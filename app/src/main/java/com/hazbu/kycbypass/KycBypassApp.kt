package com.hazbu.kycbypass

import android.app.Application

class KycBypassApp : Application() {

    companion object {
        @JvmStatic
        lateinit var instance: KycBypassApp
            private set
    }

    @JvmStatic
    val settingsManager: com.hazbu.kycbypass.utils.SettingsManager by lazy {
        com.hazbu.kycbypass.utils.SettingsManager()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsManager.init(this)
    }
}
