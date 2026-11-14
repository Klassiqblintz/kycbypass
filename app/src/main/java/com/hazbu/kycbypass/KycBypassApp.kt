package com.hazbu.kycbypass

import android.app.Application

class KycBypassApp : Application() {

    companion object {
        @JvmField var instance: KycBypassApp? = null
        @JvmField val settingsManager = com.hazbu.kycbypass.utils.SettingsManager()
    }

    override fun onCreate() {
        try {
            android.util.Log.e("KycBypassApp", "=== APPLICATION ONCREATE START ===")
            super.onCreate()
            android.util.Log.e("KycBypassApp", "=== SUPER ONCREATE DONE ===")
            instance = this
            android.util.Log.e("KycBypassApp", "=== INSTANCE SET ===")
            settingsManager.init(this)
            android.util.Log.e("KycBypassApp", "=== SETTINGS INIT DONE ===")
        } catch (e: Throwable) {
            android.util.Log.e("KycBypassApp", "onCreate crash: " + e.message, e)
        }
    }
}
