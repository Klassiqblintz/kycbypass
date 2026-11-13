package com.hazbu.kycbypass.utils

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Manages persistent settings: media path, target apps, rotation, mirroring, etc.
 * Stores config in /data/data/<package>/files/kycbypass_prefs
 */
class SettingsManager {

    companion object {
        const val PREFS_NAME = "kycbypass_prefs"
        const val KEY_MEDIA_PATH = "media_path"
        const val KEY_ROTATION = "rotation_angle"       // 0, 90, 180, 270
        const val KEY_MIRRORED = "mirrored"
        const val KEY_ACTIVE = "virtual_camera_active"
        const val KEY_TARGET_APPS = "target_apps"        // comma-separated package names
        const val KEY_LOOP_VIDEO = "loop_video"
    }

    private var prefs: SharedPreferences? = null
    private val mediaDir = File("/sdcard/DCIM/Camera1/")

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mediaDir.mkdirs()
    }

    // ---- Media ----
    var mediaPath: String?
        get() = prefs?.getString(KEY_MEDIA_PATH, null)
        set(value) {
            prefs?.edit()?.putString(KEY_MEDIA_PATH, value)?.apply()
        }

    fun setMediaPath(path: String?) {
        mediaPath = path
    }

    fun getMediaFile(): File? {
        val path = mediaPath
        return if (path != null && File(path).exists()) File(path) else null
    }

    // ---- Transforms ----
    var rotationAngle: Int
        get() = prefs?.getInt(KEY_ROTATION, 0) ?: 0
        set(value) {
            prefs?.edit()?.putInt(KEY_ROTATION, value % 360)?.apply()
        }

    var isMirrored: Boolean
        get() = prefs?.getBoolean(KEY_MIRRORED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_MIRRORED, value)?.apply()
        }

    // ---- State ----
    var isActive: Boolean
        get() = prefs?.getBoolean(KEY_ACTIVE, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_ACTIVE, value)?.apply()
        }

    // ---- Scoping ----
    fun getTargetApps(): Set<String> {
        val raw = prefs?.getString(KEY_TARGET_APPS, "") ?: ""
        return if (raw.isEmpty()) emptySet() else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun addTargetApp(packageName: String) {
        val current = getTargetApps() + packageName
        prefs?.edit()?.putString(KEY_TARGET_APPS, current.joinToString(","))?.apply()
    }

    fun removeTargetApp(packageName: String) {
        val current = getTargetApps().minus(packageName)
        prefs?.edit()?.putString(KEY_TARGET_APPS, current.joinToString(","))?.apply()
    }

    fun hasTargetApp(packageName: String): Boolean = getTargetApps().contains(packageName)

    var loopVideo: Boolean
        get() = prefs?.getBoolean(KEY_LOOP_VIDEO, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_LOOP_VIDEO, value)?.apply()
        }
}
