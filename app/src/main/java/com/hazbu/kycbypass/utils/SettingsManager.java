package com.hazbu.kycbypass.utils;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;

public class SettingsManager {

    public static final String PREFS_NAME = "kycbypass_prefs";
    public static final String KEY_MEDIA_PATH = "media_path";
    public static final String KEY_ROTATION = "rotation_angle";
    public static final String KEY_MIRRORED = "mirrored";
    public static final String KEY_ACTIVE = "virtual_camera_active";
    public static final String KEY_TARGET_APPS = "target_apps";
    public static final String KEY_LOOP_VIDEO = "loop_video";

    private SharedPreferences prefs;

    public void init(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getMediaPath() {
        return prefs.getString(KEY_MEDIA_PATH, null);
    }

    public void setMediaPath(String path) {
        prefs.edit().putString(KEY_MEDIA_PATH, path).apply();
    }

    public int getRotationAngle() {
        return prefs.getInt(KEY_ROTATION, 0);
    }

    public void setRotationAngle(int angle) {
        prefs.edit().putInt(KEY_ROTATION, angle % 360).apply();
    }

    public boolean isMirrored() {
        return prefs.getBoolean(KEY_MIRRORED, false);
    }

    public void setMirrored(boolean mirrored) {
        prefs.edit().putBoolean(KEY_MIRRORED, mirrored).apply();
    }

    public boolean isActive() {
        return prefs.getBoolean(KEY_ACTIVE, false);
    }

    public void setActive(boolean active) {
        prefs.edit().putBoolean(KEY_ACTIVE, active).apply();
    }

    public boolean getLoopVideo() {
        return prefs.getBoolean(KEY_LOOP_VIDEO, true);
    }

    public void setLoopVideo(boolean loop) {
        prefs.edit().putBoolean(KEY_LOOP_VIDEO, loop).apply();
    }

    public String[] getTargetApps() {
        String raw = prefs.getString(KEY_TARGET_APPS, "");
        if (raw == null || raw.isEmpty()) {
            return new String[0];
        }
        String[] parts = raw.split(",");
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list.toArray(new String[0]);
    }

    public void addTargetApp(String packageName) {
        java.util.List<String> list = new java.util.ArrayList<>(Arrays.asList(getTargetApps()));
        list.add(packageName);
        prefs.edit().putString(KEY_TARGET_APPS, String.join(",", list)).apply();
    }

    public void removeTargetApp(String packageName) {
        java.util.List<String> list = new java.util.ArrayList<>(Arrays.asList(getTargetApps()));
        list.remove(packageName);
        prefs.edit().putString(KEY_TARGET_APPS, String.join(",", list)).apply();
    }

    public boolean hasTargetApp(String packageName) {
        for (String p : getTargetApps()) {
            if (p.equals(packageName)) return true;
        }
        return false;
    }
}
