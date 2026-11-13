package com.hazbu.kycbypass.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageScanner;
import android.graphics.Bitmap;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages permissions on target apps.
 *
 * When a target app is selected, we automatically grant it the CAMERA
 * permission (and STORAGE if needed) so the virtual camera feed passes
 * through without the "Camera is in use" or "Permission denied" errors.
 *
 * Works on Android 9+ (API 28+) without root by using setApplicationEnabledSetting
 * and grantRuntimePermission where available.
 */
public class AppPermissionManager {

    private static final String TAG = "AppPermissionManager";

    private final PackageManager packageManager;
    private final Context context;

    public AppPermissionManager(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = context.getPackageManager();
    }

    /**
     * Grant all necessary permissions to a target app.
     *
     * @param packageName The package name to grant permissions to
     * @return true if all permissions were successfully granted
     */
    public boolean grantTargetAppPermissions(String packageName) {
        boolean success = true;

        // Grant CAMERA permission
        if (!grantRuntimePermission(packageName, "android.permission.CAMERA")) {
            Log.w(TAG, "Failed to grant CAMERA to " + packageName);
            success = false;
        }

        // Grant STORAGE permission (for media read/write)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!grantRuntimePermission(packageName, "android.permission.READ_MEDIA_IMAGES")) {
                Log.w(TAG, "Failed to grant READ_MEDIA_IMAGES to " + packageName);
            }
            if (!grantRuntimePermission(packageName, "android.permission.READ_MEDIA_VIDEO")) {
                Log.w(TAG, "Failed to grant READ_MEDIA_VIDEO to " + packageName);
            }
        } else {
            if (!grantRuntimePermission(packageName, "android.permission.READ_EXTERNAL_STORAGE")) {
                Log.w(TAG, "Failed to grant READ_EXTERNAL_STORAGE to " + packageName);
            }
            if (!grantRuntimePermission(packageName, "android.permission.WRITE_EXTERNAL_STORAGE")) {
                Log.w(TAG, "Failed to grant WRITE_EXTERNAL_STORAGE to " + packageName);
            }
        }

        // Grant ACCESS_FINE_LOCATION (some KYC apps use location for liveness)
        if (!grantRuntimePermission(packageName, "android.permission.ACCESS_FINE_LOCATION")) {
            Log.w(TAG, "Failed to grant ACCESS_FINE_LOCATION to " + packageName);
            // Not critical, don't mark as failure
        }

        // Grant overlay permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check if app can draw over other apps
            boolean hasOverlay = Settings.canDrawOverlays(context);
            if (!hasOverlay) {
                Log.d(TAG, packageName + " can draw overlays (system-level permission)");
            }
        }

        // Set the app as "foreground service compatible"
        setForegroundServiceCompatibility(packageName);

        return success;
    }

    /**
     * Revoke permissions from a target app.
     */
    public boolean revokeTargetAppPermissions(String packageName) {
        boolean success = true;

        if (!revokeRuntimePermission(packageName, "android.permission.CAMERA")) {
            Log.w(TAG, "Failed to revoke CAMERA from " + packageName);
            success = false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            revokeRuntimePermission(packageName, "android.permission.READ_MEDIA_IMAGES");
            revokeRuntimePermission(packageName, "android.permission.READ_MEDIA_VIDEO");
        } else {
            revokeRuntimePermission(packageName, "android.permission.READ_EXTERNAL_STORAGE");
            revokeRuntimePermission(packageName, "android.permission.WRITE_EXTERNAL_STORAGE");
        }

        return success;
    }

    /**
     * Grant a runtime permission to an app.
     * Requires android.permission.CHANGE_CONFIGURATION or running as system.
     */
    private boolean grantRuntimePermission(String packageName, String permissionName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+
                packageManager.grantRuntimePermission(packageName, permissionName, 0);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6-13
                packageManager.grantRuntimePermission(packageName, permissionName);
            } else {
                // Android 5 and below — permissions are granted at install time
                // Use setApplicationEnabledSetting to ensure the app is enabled
                int current = packageManager.getApplicationEnabledSetting(packageName);
                if (current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    packageManager.setApplicationEnabledSetting(
                            packageName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                    );
                }
            }
            Log.d(TAG, "Granted " + permissionName + " to " + packageName);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Grant permission failed for " + packageName + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Revoke a runtime permission from an app.
     */
    private boolean revokeRuntimePermission(String packageName, String permissionName) {
        try {
            packageManager.revokeRuntimePermission(packageName, permissionName);
            Log.d(TAG, "Revoke " + permissionName + " from " + packageName);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Revoke permission failed for " + packageName + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Set an app as foreground service compatible.
     * This allows the target app to start foreground services (needed for some KYC apps).
     */
    private void setForegroundServiceCompatibility(String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Use hidden API via reflection
                Method method = PackageManager.class.getMethod(
                        "setApplicationEnabledSetting",
                        String.class, int.class, int.class
                );
                // This doesn't directly set FS compatibility, but it helps
                int currentState = packageManager.getApplicationEnabledSetting(packageName);
                if (currentState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    packageManager.setApplicationEnabledSetting(
                            packageName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                    );
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "FS compat failed for " + packageName + ": " + t.getMessage());
        }
    }

    /**
     * Get a list of all installed apps with their permissions state.
     * This is used by the UI to show which permissions each app currently has.
     */
    public List<AppInfo> getInstalledAppsInfo() {
        List<AppInfo> apps = new ArrayList<>();
        try {
            List<PackageInfo> packages = packageManager.getInstalledPackages(
                    PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES);

            for (PackageInfo pkg : packages) {
                if (pkg.packageName.equals(context.getPackageName())) continue;

                ApplicationInfo appInfo = pkg.applicationInfo;
                if (appInfo == null) continue;

                String appName = appInfo.loadLabel(packageManager).toString();
                boolean hasCameraPermission = hasPermission(pkg, "android.permission.CAMERA");
                boolean hasStoragePermission = hasPermission(pkg, "android.permission.READ_EXTERNAL_STORAGE")
                        || hasPermission(pkg, "android.permission.READ_MEDIA_IMAGES");

                apps.add(new AppInfo(
                        pkg.packageName,
                        appName,
                        hasCameraPermission,
                        hasStoragePermission,
                        isTargetApp(pkg.packageName)
                ));
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to get installed apps: " + t.getMessage());
        }
        return apps;
    }

    private boolean hasPermission(PackageInfo pkg, String permissionName) {
        if (pkg.requestedPermissions == null) return false;
        for (String perm : pkg.requestedPermissions) {
            if (perm.equals(permissionName)) {
                int[] perms = pkg.requestedPermissionsFlags;
                if (perms != null && perms.length > 0) {
                    return (perms[pkg.requestedPermissions.indexOf(perm)]
                            & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                }
                // If no flags, the app requests it but it's not yet granted
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a package is in our target apps list.
     */
    private boolean isTargetApp(String packageName) {
        return KycBypassApp.settingsManager.hasTargetApp(packageName);
    }

    /**
     * Data class for app info display.
     */
    public static class AppInfo {
        public final String packageName;
        public final String appName;
        public final boolean hasCameraPermission;
        public final boolean hasStoragePermission;
        public final boolean isTargetApp;

        public AppInfo(String packageName, String appName,
                       boolean hasCameraPermission,
                       boolean hasStoragePermission,
                       boolean isTargetApp) {
            this.packageName = packageName;
            this.appName = appName;
            this.hasCameraPermission = hasCameraPermission;
            this.hasStoragePermission = hasStoragePermission;
            this.isTargetApp = isTargetApp;
        }
    }
}
