package com.hazbu.kycbypass.hooks;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.hazbu.kycbypass.KycBypassApp;
import com.hazbu.kycbypass.capture.CaptureManager;
import com.hazbu.kycbypass.engine.VideoEngine;
import com.hazbu.kycbypass.xposed.XC_MethodHook;
import com.hazbu.kycbypass.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Camera1 (Legacy) hook layer.
 *
 * Mirrors xCam's CameraHook.java but adapted for a standalone app.
 *
 * How it works:
 * 1. Hook Camera.setPreviewTexture() → give the real camera a dummy SurfaceTexture,
 *    keep the app's original SurfaceTexture, feed video to it via VideoEngine.
 * 2. Hook Camera.setPreviewDisplay() → same pattern, via SurfaceHolder.
 * 3. Hook Camera.startPreview() → trigger video playback.
 * 4. Hook Camera.takePicture() → extract a frame from the video, feed as JPEG.
 * 5. Hook onPreviewFrame callbacks → replace NV21 bytes with decoded video frame.
 */
public class Camera1Hook {

    private static final long PREVIEW_RESTART_DELAY_MS = 100;

    private final VideoEngine videoEngine;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Surface viewfinderSurface;
    private SurfaceTexture dummySurfaceTexture;

    private final Set<String> hookedCallbackClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public Camera1Hook(VideoEngine videoEngine) {
        this.videoEngine = videoEngine;
    }

    /**
     * Install all Camera1 hooks.
     */
    public void install() {
        try {
            hookPreviewTexture();
            hookPreviewDisplay();
            hookStartPreview();
            hookStopPreview();
            hookTakePicture();
            hookRelease();
            hookPreviewCallbacks();
            hookEventHandler();
        } catch (Throwable t) {
            android.util.Log.e("Camera1Hook", "Failed to install hooks: " + t.getMessage(), t);
        }
    }

    // ---- setPreviewTexture ----
    private void hookPreviewTexture() {
        try {
            Method method = Camera.class.getDeclaredMethod("setPreviewTexture", SurfaceTexture.class);
            method.setAccessible(true);

            // We use reflection-based hooking compatible with both Xposed and non-root
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SurfaceTexture texture = (SurfaceTexture) param.args[0];
                    if (texture == null) return;
                    if (dummySurfaceTexture == null) {
                        dummySurfaceTexture = new SurfaceTexture(10);
                    }
                    viewfinderSurface = new Surface(texture);
                    videoEngine.addPreviewTexture(texture);
                    param.args[0] = dummySurfaceTexture;
                    android.util.Log.d("Camera1Hook", "Hijacked setPreviewTexture -> dummy texture");
                }
            });
            android.util.Log.d("Camera1Hook", "Hooked: Camera#setPreviewTexture");
        } catch (Throwable t) {
            android.util.Log.e("Camera1Hook", "setPreviewTexture hook failed: " + t.getMessage());
        }
    }

    // ---- setPreviewDisplay ----
    private void hookPreviewDisplay() {
        try {
            Method method = Camera.class.getDeclaredMethod("setPreviewDisplay", SurfaceHolder.class);

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    SurfaceHolder holder = (SurfaceHolder) param.args[0];
                    if (holder == null || holder.getSurface() == null) return;
                    if (dummySurfaceTexture == null) {
                        dummySurfaceTexture = new SurfaceTexture(10);
                    }
                    viewfinderSurface = holder.getSurface();
                    videoEngine.addTarget(viewfinderSurface);
                    Camera camera = (Camera) param.thisObject;
                    camera.setPreviewTexture(dummySurfaceTexture);
                    android.util.Log.d("Camera1Hook", "Hijacked setPreviewDisplay -> dummy texture");
                }
            });
            android.util.Log.d("Camera1Hook", "Hooked: Camera#setPreviewDisplay");
        } catch (Throwable t) {
            android.util.Log.e("Camera1Hook", "setPreviewDisplay hook failed: " + t.getMessage());
        }
    }

    // ---- startPreview ----
    private void hookStartPreview() {
        try {
            Method method = Camera.class.getDeclaredMethod("startPreview");

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (viewfinderSurface != null && viewfinderSurface.isValid()) {
                        videoEngine.setActive(true);
                        android.util.Log.d("Camera1Hook", "Video playback started for preview surface");
                    }
                }
            });
            android.util.Log.d("Camera1Hook", "Hooked: Camera#startPreview");
        } catch (Throwable t) {
            android.util.Log.e("Camera1Hook", "startPreview hook failed: " + t.getMessage());
        }
    }

    // ---- stopPreview ----
    private void hookStopPreview() {
        try {
            Method method = Camera.class.getDeclaredMethod("stopPreview");

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    videoEngine.setActive(false);
                    if (dummySurfaceTexture != null) {
                        try { dummySurfaceTexture.release(); } catch (Throwable ignored) {}
                        dummySurfaceTexture = null;
                    }
                }
            });
            android.util.Log.d("Camera1Hook", "Hooked: Camera#stopPreview");
        } catch (Throwable t) {
            android.util.Log.e("Camera1Hook", "stopPreview hook failed: " + t.getMessage());
        }
    }

    // ---- takePicture ----
    private void hookTakePicture() {
        try {
            for (Method m : Camera.class.getDeclaredMethods()) {
                if (m.getName().equals("takePicture") && m.getParameterTypes().length == 4) {
                    final Method pictureMethod = m;

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Camera camera = (Camera) param.thisObject;
                            Camera.PictureCallback jpegCallback = (Camera.PictureCallback) param.args[3];
                            if (jpegCallback == null) return;

                            Camera.Parameters params = camera.getParameters();
                            int picW = params.getPictureSize().width;
                            int picH = params.getPictureSize().height;

                            byte[] replacement = extractFrame(picW, picH);
                            if (replacement == null) return;

                            final Camera.PictureCallback originalCallback = jpegCallback;
                            final Camera originalCamera = camera;

                            mainHandler.post(() -> {
                                try {
                                    if (param.args[0] != null)
                                        ((Camera.ShutterCallback) param.args[0]).onShutter();
                                    if (param.args[1] != null)
                                        ((Camera.PictureCallback) param.args[1]).onPictureTaken(null, originalCamera);
                                    if (param.args[2] != null)
                                        ((Camera.PictureCallback) param.args[2]).onPictureTaken(replacement, originalCamera);
                                    originalCallback.onPictureTaken(replacement, originalCamera);
                                } catch (Throwable ignored) {}
                            });
                            param.setResult(null);
                        }
                    });
                    android.util.Log.d("Camera1Hook", "Hooked: Camera#takePicture");
                    break;
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("Camera1Hook", "takePicture hook failed: " + t.getMessage());
        }
    }

    // ---- onPreviewFrame callbacks ----
    private void hookPreviewCallbacks() {
        try {
            hookCallbackMethod("setPreviewCallback", Camera.class);
            hookCallbackMethod("setPreviewCallbackWithBuffer", Camera.class);
            hookCallbackMethod("setOneShotPreviewCallback", Camera.class);
            android.util.Log.d("Camera1Hook", "Hooked all preview callback methods");
        } catch (Throwable t) {
            android.util.Log.e("Camera1Hook", "Preview callback hooks failed: " + t.getMessage());
        }
    }

    private void hookCallbackMethod(String methodName, Class<?> targetClass) {
        try {
            Method method = targetClass.getDeclaredMethod(methodName, Camera.PreviewCallback.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object callback = param.args[0];
                    if (callback == null) return;
                    registerCallback((Class<?>) callback.getClass());
                }
            });
            android.util.Log.d("Camera1Hook", "Hooked: Camera#" + methodName);
        } catch (Throwable t) {
            // Method may not exist on all Android versions
        }
    }

    private void registerCallback(Class<?> callbackClass) {
        if (!hookedCallbackClasses.add(callbackClass.getName())) return;

        Class<?> clazz = callbackClass;
        while (clazz != null && clazz != Object.class) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals("onPreviewFrame")
                        && method.getParameterTypes().length == 2
                        && method.getParameterTypes()[0] == byte[].class
                        && method.getParameterTypes()[1] == Camera.class) {

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            byte[] buffer = (byte[]) param.args[0];
                            Camera camera = (Camera) param.args[1];
                            if (buffer == null || camera == null) return;

                            int w = 640, h = 480;
                            try {
                                Camera.Parameters params = camera.getParameters();
                                if (params != null) {
                                    Camera.Size size = params.getPreviewSize();
                                    if (size != null) { w = size.width; h = size.height; }
                                }
                            } catch (Throwable ignored) {}

                            byte[] nv21 = decodeVideoToNv21(w, h);
                            if (nv21 != null && nv21.length <= buffer.length) {
                                System.arraycopy(nv21, 0, buffer, 0, nv21.length);
                            }
                        }
                    });
                    android.util.Log.d("Camera1Hook", "Hooked onPreviewFrame on: " + callbackClass.getName());
                    return;
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    // ---- Camera$EventHandler (native preview frames) ----
    private void hookEventHandler() {
        try {
            Class<?> eventHandlerClass = Class.forName("android.hardware.Camera$EventHandler");
            Method handleMessage = eventHandlerClass.getDeclaredMethod("handleMessage", android.os.Message.class);

            XposedBridge.hookMethod(handleMessage, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    android.os.Message msg = (android.os.Message) param.args[0];
                    // 0x010 = CAMERA_MSG_PREVIEW_FRAME in AOSP
                    if (msg != null && msg.what == 0x010 && msg.obj instanceof byte[]) {
                        byte[] buffer = (byte[]) msg.obj;
                        int w = 640, h = 480;

                        // Extract camera from the handler's mCamera field
                        try {
                            java.lang.reflect.Field camField = eventHandlerClass.getDeclaredField("mCamera");
                            camField.setAccessible(true);
                            Camera camera = (Camera) camField.get(param.thisObject);
                            if (camera != null) {
                                Camera.Parameters params = camera.getParameters();
                                if (params != null) {
                                    Camera.Size size = params.getPreviewSize();
                                    if (size != null) { w = size.width; h = size.height; }
                                }
                            }
                        } catch (Throwable ignored) {}

                        byte[] nv21 = decodeVideoToNv21(w, h);
                        if (nv21 != null && nv21.length <= buffer.length) {
                            System.arraycopy(nv21, 0, buffer, 0, nv21.length);
                        }
                    }
                }
            });
            android.util.Log.d("Camera1Hook", "Hooked: Camera$EventHandler#handleMessage");
        } catch (Throwable t) {
            android.util.Log.d("Camera1Hook", "EventHandler hook skipped: " + t.getMessage());
        }
    }

    // ---- release ----
    private void hookRelease() {
        try {
            Method method = Camera.class.getDeclaredMethod("release");

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    videoEngine.setActive(false);
                }
            });
            android.util.Log.d("Camera1Hook", "Hooked: Camera#release");
        } catch (Throwable t) {
            android.util.Log.e("Camera1Hook", "release hook failed: " + t.getMessage());
        }
    }

    // ---- Frame extraction helpers ----
    private byte[] extractFrame(int width, int height) {
        String mediaPath = KycBypassApp.settingsManager.getMediaPath();
        if (mediaPath == null) return null;

        CaptureManager capture = new CaptureManager();
        return capture.extractFrame(
                KycBypassApp.instance,
                mediaPath,
                width,
                height,
                KycBypassApp.settingsManager.getRotationAngle(),
                KycBypassApp.settingsManager.isMirrored(),
                (int) videoEngine.getCurrentPositionMs(),
                Integer.MAX_VALUE
        );
    }

    private byte[] decodeVideoToNv21(int width, int height) {
        String mediaPath = KycBypassApp.settingsManager.getMediaPath();
        if (mediaPath == null) return null;

        try {
            // Use MediaMetadataRetriever to get a frame, then convert to NV21
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(mediaPath);

            long duration = 0;
            String dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                try { duration = Long.parseLong(dur); } catch (NumberFormatException ignored) {}
            }

            long loopedTime = (duration > 0) ? (android.os.SystemClock.elapsedRealtime() % duration) : 0;
            android.graphics.Bitmap bitmap = retriever.getFrameAtTime(loopedTime * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST);
            retriever.release();

            if (bitmap == null) return null;

            // Convert ARGB_8888 to NV21
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, Math.min(bitmap.getHeight(), height));
            bitmap.recycle();

            byte[] nv21 = argbToNv21(pixels, width, height);
            return nv21;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] argbToNv21(int[] argb, int width, int height) {
        int len = width * height * 3 / 2;
        byte[] nv21 = new byte[len];

        // Y plane
        int yIdx = 0;
        for (int i = 0; i < width * height; i++) {
            int r = (argb[i] >> 16) & 0xff;
            int g = (argb[i] >> 8) & 0xff;
            int b = argb[i] & 0xff;
            // BT.601 luminance
            nv21[yIdx++] = (byte) ((66 * r + 129 * g + 25 * b + 128) >> 8);
        }

        // UV plane (interleaved VU = NV21)
        int uvIdx = yIdx;
        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int idx = x + y * width;
                if (idx >= width * height) continue;
                int r0 = (argb[idx] >> 16) & 0xff;
                int g0 = (argb[idx] >> 8) & 0xff;
                int b0 = argb[idx] & 0xff;
                int r1 = (argb[idx + 1] >> 16) & 0xff;
                int b1 = argb[idx + width] & 0xff;

                // BT.601 chroma
                int u = 128 + ((-38 * r0 - 74 * g0 + 112 * b0 + 128) >> 8);
                int v = 128 + ((112 * r0 - 93 * g0 - 18 * b0 + 128) >> 8);
                nv21[uvIdx++] = (byte) v; // V first (NV21 = VU interleaved)
                nv21[uvIdx++] = (byte) u;
            }
        }
        return nv21;
    }

    // ---- Public accessors ----
    public SurfaceTexture getDummySurfaceTexture() {
        return dummySurfaceTexture;
    }

    public void setActive(boolean active) {
        videoEngine.setActive(active);
    }

    public boolean isActive() {
        return videoEngine.isPlaying();
    }
}
