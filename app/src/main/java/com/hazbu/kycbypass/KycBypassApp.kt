package com.hazbu.kycbypass

import android.app.Application

class KycBypassApp : Application() {

    companion object {
        @JvmStatic val instance: KycBypassApp
            get() = Companion._instance!!
        private var _instance: KycBypassApp? = null
        @JvmStatic val settingsManager: com.hazbu.kycbypass.utils.SettingsManager
            get() = Companion._settingsManager
        private val _settingsManager = com.hazbu.kycbypass.utils.SettingsManager()
    }

    override fun onCreate() {
        super.onCreate()
        Companion._instance = this
        Companion._settingsManager.init(this)
    }
}
