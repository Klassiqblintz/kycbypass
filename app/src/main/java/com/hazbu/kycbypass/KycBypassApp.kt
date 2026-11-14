package com.hazbu.kycbypass

import android.app.Application

class KycBypassApp : Application() {

    companion object {
        @JvmField var instance: KycBypassApp? = null
        @JvmField val settingsManager = com.hazbu.kycbypass.utils.SettingsManager()
    }

    override fun onCreate() {
        super.onCreate()
        try {
            instance = this
            settingsManager.init(this)
        } catch (e: Throwable) {
            android.util.Log.e("KycBypassApp", "onCreate crash: " + e.message, e)
        }
    }
}
