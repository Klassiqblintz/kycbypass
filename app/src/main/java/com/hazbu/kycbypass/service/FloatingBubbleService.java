package com.hazbu.kycbypass.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.hazbu.kycbypass.KycBypassApp;
import com.hazbu.kycbypass.R;
import com.hazbu.kycbypass.ui.MainActivity;

/**
 * Floating bubble service.
 *
 * Displays a small draggable/tappable bubble overlay that shows the
 * current state of the virtual camera (active/inactive) and toggles it
 * when tapped. This provides quick enable/disable without opening the
 * main app.
 *
 * Uses WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY for
 * non-root compatibility on Android 8+ (API 26+).
 */
public class FloatingBubbleService extends Service {

    private static final String CHANNEL_ID = "kycbypass_bubble";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private ImageView bubbleView;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        createBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "TOGGLE".equals(intent.getAction())) {
            toggleState();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bubbleView != null && bubbleView.isShown()) {
            windowManager.removeView(bubbleView);
        }
    }

    /**
     * Create the floating bubble view.
     * A small circular button with an icon and status text.
     */
    private void createBubble() {
        bubbleView = new ImageView(this);

        // Set up the bubble appearance
        int bubbleSize = (int) (48 * getResources().getDisplayMetrics().density);

        // Use a circle background via drawable
        bubbleView.setBackgroundResource(com.hazbu.kycbypass.R.drawable.bubble_background);
        bubbleView.setScaleType(ImageView.ScaleType.FIT_XY);

        // Set tap listener
        bubbleView.setOnClickListener(v -> toggleState());
        bubbleView.setOnLongClickListener(v -> {
            // Long press opens the main app
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
            }
            return true;
        });

        // Layout params for overlay
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                bubbleSize,
                bubbleSize,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
        );

        // Position in top-right area
        params.x = 100;
        params.y = 100;
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;

        // Allow drag
        bubbleView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                params.x = (int) event.getRawX() - bubbleSize / 2;
                params.y = (int) event.getRawY() - bubbleSize / 2;
                windowManager.updateViewLayout(bubbleView, params);
            }
            return true;
        });

        // Update appearance based on current state
        updateBubbleAppearance();

        windowManager.addView(bubbleView, params);
    }

    /**
     * Update the bubble icon/text based on current state.
     */
    private void updateBubbleAppearance() {
        boolean active = KycBypassApp.settingsManager.getIsActive();
        // Green dot = active, gray = inactive
        int colorRes = active ? com.hazbu.kycbypass.R.color.bubble_active : com.hazbu.kycbypass.R.color.bubble_inactive;
        bubbleView.setBackgroundColor(getResources().getColor(colorRes, getTheme()));
    }

    /**
     * Toggle the virtual camera on/off.
     */
    private void toggleState() {
        boolean current = KycBypassApp.settingsManager.getIsActive();
        KycBypassApp.settingsManager.setIsActive(!current);
        updateBubbleAppearance();

        String state = KycBypassApp.settingsManager.getIsActive() ? "ON" : "OFF";
        Toast.makeText(this, "Virtual Camera: " + state, Toast.LENGTH_SHORT).show();
    }

    /**
     * Build the foreground notification for the service.
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
                .setContentText("Virtual camera service running")
                .setSmallIcon(com.hazbu.kycbypass.R.drawable.ic_bubble)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    /**
     * Create the notification channel (required on Android 8+).
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Floating Bubble",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows the floating bubble for virtual camera control");
            channel.setShowBadge(false);

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
