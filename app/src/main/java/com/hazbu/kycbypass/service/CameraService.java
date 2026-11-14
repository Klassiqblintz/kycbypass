package com.hazbu.kycbypass.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import androidx.core.app.NotificationCompat;

import com.hazbu.kycbypass.KycBypassApp;
import com.hazbu.kycbypass.R;
import com.hazbu.kycbypass.engine.VideoEngine;
import com.hazbu.kycbypass.hooks.Camera1Hook;
import com.hazbu.kycbypass.hooks.Camera2Hook;
import com.hazbu.kycbypass.hooks.CaptureHook;
import com.hazbu.kycbypass.hooks.ImageReaderHook;
import com.hazbu.kycbypass.hooks.AudioHook;
import com.hazbu.kycbypass.utils.AppPermissionManager;
import com.hazbu.kycbypass.ui.MainActivity;

/**
 * Camera injection foreground service.
 *
 * This service keeps the video engine and all hooks alive while the target
 * app runs. It:
 * 1. Initializes all hook layers (Camera1, Camera2, Capture, ImageReader)
 * 2. Loads the video engine with the user's selected media
 * 3. Installs hooks via XposedBridge
 * 4. Monitors the current app to detect when a target app opens
 * 5. Activates/deactivates hooks based on the target app's package name
 *
 * This runs as a foreground service so Android doesn't kill it when memory
 * is low, making it work without root on Android 9+ (API 28+).
 */
public class CameraService extends Service {

    private static final String CHANNEL_ID = "kycbypass_camera";
    private static final int NOTIFICATION_ID = 2;

    private VideoEngine videoEngine;
    private Camera1Hook camera1Hook;
    private Camera2Hook camera2Hook;
    private CaptureHook captureHook;
    private ImageReaderHook imageReaderHook;
    private AudioHook audioHook;

    private Handler mainHandler;
    private boolean isInstalled = false;
    private AppPermissionManager permissionManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize video engine
        videoEngine = new VideoEngine();
        videoEngine.setAppContext(this);
        videoEngine.setMediaPath(KycBypassApp.settingsManager.getMediaPath());
        videoEngine.setRotationAngle(KycBypassApp.settingsManager.getRotationAngle());
        videoEngine.setIsMirrored(KycBypassApp.settingsManager.isMirrored());
        videoEngine.setLoopVideo(KycBypassApp.settingsManager.getLoopVideo());

        // Initialize hooks
        camera1Hook = new Camera1Hook(videoEngine);
        camera2Hook = new Camera2Hook(videoEngine);
        captureHook = new CaptureHook();
        imageReaderHook = new ImageReaderHook(videoEngine);
        audioHook = new AudioHook();
        permissionManager = new AppPermissionManager(this);

        // Install all hooks
        installAllHooks();

        // Grant permissions to target apps
        grantTargetAppPermissions();

        // Start foreground notification
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        // Watch for target app launch
        startTargetAppMonitor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAllHooks();
    }

    /**
     * Install all hook layers.
     */
    private void installAllHooks() {
        try {
            // Camera1 (Legacy) path
            camera1Hook.install();

            // Camera2 path
            camera2Hook.install();

            // Capture/Photo injection points
            captureHook.install();

            // ImageReader (Camera2 JPEG output)
            imageReaderHook.install();

            // Audio injection
            audioHook.install();

            isInstalled = true;
        } catch (Throwable t) {
            android.util.Log.e("CameraService", "Hook installation failed: " + t.getMessage(), t);
        }
    }

    /**
     * Stop all hooks and release resources.
     */
    private void stopAllHooks() {
        try {
            if (videoEngine != null) {
                videoEngine.stopAll();
            }
            isInstalled = false;
        } catch (Throwable t) {
            android.util.Log.e("CameraService", "Hook cleanup failed: " + t.getMessage());
        }
    }

    /**
     * Monitor the current running app and activate/deactivate hooks accordingly.
     * Checks every 500ms for performance.
     */
    private void startTargetAppMonitor() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isInstalled) return;

                String currentPackage = getCurrentTopPackage();
                if (currentPackage != null && isTargetApp(currentPackage)) {
                    activateForApp(currentPackage);
                } else if (currentPackage != null && !isTargetApp(currentPackage)) {
                    deactivateIfNotTarget(currentPackage);
                }

                // Reschedule
                mainHandler.postDelayed(this, 500);
            }
        }, 500);
    }

    /**
     * Get the package name of the currently visible app.
     */
    private String getCurrentTopPackage() {
        try {
            android.app.ActivityManager activityManager =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (activityManager == null) return null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Android 5+
                android.app.ActivityManager.RunningTaskInfo taskInfo =
                        activityManager.getRunningTasks(1).get(0);
                return taskInfo.topActivity != null ? taskInfo.topActivity.getPackageName() : null;
            } else {
                // Android 4.x / older
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                        activityManager.getRunningAppProcesses();
                if (processes != null && !processes.isEmpty()) {
                    android.app.ActivityManager.RunningAppProcessInfo info = processes.get(0);
                    if (info != null && info.processName != null) {
                        return info.processName;
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("CameraService", "Failed to get top package: " + t.getMessage());
        }
        return null;
    }

    /**
     * Check if the given package is in our target apps list.
     */
    private boolean isTargetApp(String packageName) {
        return KycBypassApp.settingsManager.hasTargetApp(packageName);
    }

    /**
     * Activate hooks for a target app.
     */
    private void activateForApp(String packageName) {
        if (KycBypassApp.settingsManager.getIsActive()) {
            if (!videoEngine.isPlaying()) {
                videoEngine.setActive(true);
            }
            camera1Hook.setActive(true);
            audioHook.setActive(true);
            camera2Hook.install(); // Re-ensure hooks are live
        }
    }

    /**
     * Deactivate if the current app is NOT a target.
     */
    private void deactivateIfNotTarget(String packageName) {
        if (videoEngine.isPlaying()) {
            videoEngine.setActive(false);
            camera1Hook.setActive(false);
            audioHook.setActive(false);
        }
    }

    /**
     * Grant permissions to all registered target apps.
     */
    private void grantTargetAppPermissions() {
        for (String packageName : KycBypassApp.settingsManager.getTargetApps()) {
            permissionManager.grantTargetAppPermissions(packageName);
        }
    }

    /**
     * Create the notification channel.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Virtual Camera",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Camera injection service for KYC bypass");
            channel.setShowBadge(false);

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Build the foreground notification.
     */
    private Notification buildNotification() {
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                java.lang.reflect.Field immutable = PendingIntent.class.getField("FLAG_IMMUTABLE");
                pendingIntentFlags |= immutable.getInt(null);
            } catch (Exception ignored) {}
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                pendingIntentFlags
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("KYC Bypass")
                .setContentText("Injecting virtual camera")
                .setSmallIcon(R.drawable.ic_bubble)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
