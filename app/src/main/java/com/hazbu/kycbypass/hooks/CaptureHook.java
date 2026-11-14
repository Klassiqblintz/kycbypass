package com.hazbu.kycbypass.hooks;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.net.Uri;

import com.hazbu.kycbypass.KycBypassApp;
import com.hazbu.kycbypass.capture.CaptureManager;
import com.hazbu.kycbypass.xposed.XC_MethodHook;
import com.hazbu.kycbypass.xposed.XC_MethodHook.MethodHookParam;
import com.hazbu.kycbypass.xposed.XposedBridge;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.io.File;

/**
 * Capture/Photo hook layer.
 *
 * Mirrors xCam's CaptureHook.java.
 *
 * Hooks multiple injection points where captured photos pass through:
 * - BitmapFactory.decodeByteArray()
 * - BitmapFactory.decodeStream()
 * - Bitmap.compress()
 * - ContentResolver.openFileDescriptor() (MediaStore writes)
 * - MediaRecorder (video recording replacement)
 *
 * When the app triggers a photo capture, we extract a frame from the virtual
 * video and inject it into each of these paths.
 */
public class CaptureHook {

    private final CaptureManager captureManager = new CaptureManager();
    private volatile boolean ignoringHooks = false;
    private volatile boolean isCapturingState = false;
    private volatile long captureTimestampMs = 0;

    public CaptureHook() {}

    public void install() {
        try {
            hookBitmapFactoryDecodeByteArray();
            hookBitmapFactoryDecodeStream();
            hookBitmapCompress();
            hookMediaStoreOpenFileDescriptor();
            hookMediaRecorder();
            android.util.Log.d("CaptureHook", "Capture hooks installed successfully");
        } catch (Throwable t) {
            android.util.Log.e("CaptureHook", "Capture hooks failed: " + t.getMessage(), t);
        }
    }

    // ---- BitmapFactory.decodeByteArray ----
    private void hookBitmapFactoryDecodeByteArray() {
        try {
            for (Method method : BitmapFactory.class.getDeclaredMethods()) {
                if (method.getName().equals("decodeByteArray")) {
                    final Method decodeMethod = method;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (ignoringHooks || isCapturingState == false) return;

                            byte[] replacement = getReplacementImage(param);
                            if (replacement != null) {
                                param.args[0] = replacement;
                                if (param.args.length >= 3) {
                                    param.args[2] = replacement.length;
                                }
                                android.util.Log.d("CaptureHook", "Injected into decodeByteArray");
                            }
                        }
                    });
                    android.util.Log.d("CaptureHook", "Hooked: BitmapFactory#decodeByteArray");
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---- BitmapFactory.decodeStream ----
    private void hookBitmapFactoryDecodeStream() {
        try {
            for (Method method : BitmapFactory.class.getDeclaredMethods()) {
                if (method.getName().equals("decodeStream") && method.getParameterTypes().length >= 1) {
                    final Method decodeStreamMethod = method;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (ignoringHooks) return;
                            if (!isCapturingState) return;

                            byte[] replacement = getReplacementImage(param);
                            if (replacement != null) {
                                ignoringHooks = true;
                                try {
                                    Bitmap bitmap = BitmapFactory.decodeByteArray(replacement, 0, replacement.length,
                                            param.args.length >= 3 && param.args[2] instanceof BitmapFactory.Options
                                                    ? (BitmapFactory.Options) param.args[2] : null);
                                    if (bitmap != null) {
                                        param.setResult(bitmap);
                                        android.util.Log.d("CaptureHook", "Injected Bitmap into decodeStream");
                                    }
                                } finally {
                                    ignoringHooks = false;
                                }
                            }
                        }
                    });
                    android.util.Log.d("CaptureHook", "Hooked: BitmapFactory#decodeStream");
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---- Bitmap.compress ----
    private void hookBitmapCompress() {
        try {
            Method compress = Bitmap.class.getDeclaredMethod("compress",
                    Bitmap.CompressFormat.class, int.class, OutputStream.class);

            XposedBridge.hookMethod(compress, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (ignoringHooks) return;
                    if (!isCapturingState) return;

                    OutputStream os = (OutputStream) param.args[2];
                    if (os == null) return;

                    byte[] replacement = getReplacementImage(param);
                    if (replacement != null) {
                        ignoringHooks = true;
                        try {
                            os.write(replacement);
                            os.flush();
                            android.util.Log.d("CaptureHook", "Injected into Bitmap#compress");
                            param.setResult(true);
                        } finally {
                            ignoringHooks = false;
                        }
                    }
                }
            });
            android.util.Log.d("CaptureHook", "Hooked: Bitmap#compress");
        } catch (Throwable ignored) {}
    }

    // ---- ContentResolver.openFileDescriptor (MediaStore writes) ----
    private void hookMediaStoreOpenFileDescriptor() {
        try {
            Method openFD = ContentResolver.class.getDeclaredMethod("openFileDescriptor",
                    Uri.class, String.class);

            XposedBridge.hookMethod(openFD, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (ignoringHooks) return;
                    if (!isCapturingState) return;

                    Uri uri = (Uri) param.args[0];
                    String mode = (String) param.args[1];

                    if (uri != null && mode != null && mode.contains("w")) {
                        ParcelFileDescriptor pfd = (ParcelFileDescriptor) param.result;
                        if (pfd != null) {
                            byte[] replacement = getReplacementImage(param);
                            if (replacement != null) {
                                ignoringHooks = true;
                                try {
                                    FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
                                    fos.write(replacement);
                                    fos.flush();
                                    fos.close();
                                    android.util.Log.d("CaptureHook", "Injected into MediaStore FD: " + uri);
                                } finally {
                                    ignoringHooks = false;
                                }
                            }
                        }
                    }
                }
            });
            android.util.Log.d("CaptureHook", "Hooked: ContentResolver#openFileDescriptor");
        } catch (Throwable ignored) {}
    }

    // ---- MediaRecorder (video recording replacement) ----
    private void hookMediaRecorder() {
        try {
            Class<?> mrClass = Class.forName("android.media.MediaRecorder");

            // Hook setOutputFile / setNextOutputFile
            for (Method m : mrClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("setOutputFile") || name.equals("setNextOutputFile")) {
                    final Method outputFileMethod = m;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object recorder = param.thisObject;
                            if (param.args != null && param.args.length > 0) {
                                Object target = param.args[0];
                                String path = null;
                                if (target instanceof String) path = (String) target;
                                else if (target instanceof File) path = ((File) target).getAbsolutePath();
                                if (path != null) {
                                    android.util.Log.d("CaptureHook", "MediaRecorder output: " + path);
                                }
                            }
                        }
                    });
                    android.util.Log.d("CaptureHook", "Hooked: MediaRecorder#" + name);
                }
            }

            // Hook start()
            Method start = mrClass.getDeclaredMethod("start");
            XposedBridge.hookMethod(start, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    android.util.Log.d("CaptureHook", "MediaRecorder#start");
                }
            });

            // Hook stop() → replace output file with virtual video
            Method stop = mrClass.getDeclaredMethod("stop");
            XposedBridge.hookMethod(stop, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object recorder = param.thisObject;
                    // The actual file replacement happens via a tracked output path.
                    // For simplicity, the VideoEngine is already playing onto the
                    // MediaRecorder's input surface, so the output is already replaced.
                }
            });
            android.util.Log.d("CaptureHook", "Hooked: MediaRecorder#stop");

        } catch (Throwable ignored) {}
    }

    // ---- Core: get replacement image bytes ----
    private byte[] getReplacementImage(MethodHookParam param) {
        String mediaPath = KycBypassApp.settingsManager.getMediaPath();
        if (mediaPath == null || mediaPath.isEmpty()) return null;

        int targetW = 1080;
        int targetH = 1920;

        // Try to determine the expected capture size from context
        try {
            if (param.args[0] instanceof byte[]) {
                byte[] data = (byte[]) param.args[0];
                // Estimate from JPEG header
                if (data.length > 2 && data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) {
                    // Could extract from JPEG, but use defaults for now
                }
            }
        } catch (Throwable ignored) {}

        return captureManager.extractFrame(
                KycBypassApp.instance,
                mediaPath,
                targetW,
                targetH,
                KycBypassApp.settingsManager.getRotationAngle(),
                KycBypassApp.settingsManager.isMirrored(),
                (int) captureTimestampMs,
                Integer.MAX_VALUE
        );
    }

    // ---- State management ----
    public void triggerCaptureState(long currentVideoPosMs) {
        captureTimestampMs = currentVideoPosMs;
        isCapturingState = true;

        // Auto-reset after 3 seconds
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            isCapturingState = false;
        }, 3000);
    }

    public void setIgnoringHooks(boolean ignoring) {
        ignoringHooks = ignoring;
    }

    public boolean isCapturingState() {
        return isCapturingState;
    }

    public void setCaptureTimestamp(long ms) {
        this.captureTimestampMs = ms;
    }
}
