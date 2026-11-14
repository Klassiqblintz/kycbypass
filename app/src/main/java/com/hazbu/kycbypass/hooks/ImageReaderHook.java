package com.hazbu.kycbypass.hooks;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.hazbu.kycbypass.KycBypassApp;
import com.hazbu.kycbypass.capture.CaptureManager;
import com.hazbu.kycbypass.engine.VideoEngine;
import com.hazbu.kycbypass.xposed.XC_MethodHook;
import com.hazbu.kycbypass.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ImageReader hook layer.
 *
 * Captures the JPEG output path used by Camera2 when the target app
 * takes a still photo. Replaces the ImageReader's buffer with a frame
 * from the virtual video instead.
 *
 * Mirrors xCam's ImageReaderHook.java.
 *
 * How it works:
 * 1. Hook ImageReader.newInstance() to track each instance
 * 2. Hook each ImageReader.setOnImageAvailableListener() to intercept the callback
 * 3. When the callback fires, replace the Image with a JPEG frame from the video
 * 4. Also hook OutputConfiguration for CameraX-style capture
 */
public class ImageReaderHook {

    private static final String TAG = "ImageReaderHook";

    private final VideoEngine videoEngine;
    private final CaptureManager captureManager = new CaptureManager();

    // Track all hooked ImageReaders and their callbacks
    private final CopyOnWriteArrayList<ReaderEntry> readerEntries = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean isCapturing = false;
    private volatile long captureTimestampMs = 0;

    public ImageReaderHook(VideoEngine videoEngine) {
        this.videoEngine = videoEngine;
    }

    /**
     * Install all ImageReader hooks.
     */
    public void install() {
        try {
            hookImageReaderNewInstance();
            hookSetOnImageAvailableListener();
            hookOutputConfiguration();
            Log.d(TAG, "ImageReader hooks installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "ImageReader hooks failed: " + t.getMessage(), t);
        }
    }

    // ---- ImageReader.newInstance ----
    private void hookImageReaderNewInstance() {
        try {
            // ImageReader has multiple overloads of newInstance
            for (Method method : ImageReader.class.getDeclaredMethods()) {
                if (method.getName().equals("newInstance") && method.getReturnType() == ImageReader.class) {
                    final Method newInstanceMethod = method;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            ImageReader reader = (ImageReader) param.result;
                            if (reader == null) return;

                            int maxImages = 0;
                            try {
                                if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                    maxImages = (Integer) param.args[0];
                                }
                            } catch (Throwable ignored) {}

                            Log.d(TAG, "Tracked ImageReader: maxImages=" + maxImages);

                            ReaderEntry entry = new ReaderEntry(reader, maxImages);
                            readerEntries.add(entry);
                        }
                    });
                    Log.d(TAG, "Hooked: ImageReader#newInstance");
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "ImageReader.newInstance hook failed: " + t.getMessage());
        }
    }

    // ---- ImageReader.setOnImageAvailableListener ----
    private void hookSetOnImageAvailableListener() {
        try {
            // Find the right overload
            for (Method method : ImageReader.class.getDeclaredMethods()) {
                if (method.getName().equals("setOnImageAvailableListener")) {
                    final Method setListenerMethod = method;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            ImageReader reader = (ImageReader) param.thisObject;
                            android.media.ImageReader.OnImageAvailableListener listener =
                                    (android.media.ImageReader.OnImageAvailableListener) param.args[0];
                            android.os.Handler handler =
                                    (android.os.Handler) ((param.args.length > 1) ? param.args[1] : null);

                            if (listener == null) return;

                            // Wrap the listener with our own
                            WrappedListener wrapped = new WrappedListener(reader, listener, handler);
                            param.args[0] = wrapped;
                            Log.d(TAG, "Wrapped ImageReader listener");
                        }
                    });
                    Log.d(TAG, "Hooked: ImageReader#setOnImageAvailableListener");
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "setOnImageAvailableListener hook failed: " + t.getMessage());
        }
    }

    // ---- OutputConfiguration (CameraX path) ----
    private void hookOutputConfiguration() {
        try {
            Class<?> ocClass = Class.forName("android.hardware.camera2.params.OutputConfiguration");

            // Hook the setImageReader method (sets the output surface from an ImageReader)
            try {
                Method setImageReader = ocClass.getDeclaredMethod("setImageReader", ImageReader.class);
                XposedBridge.hookMethod(setImageReader, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        ImageReader reader = (ImageReader) param.args[0];
                        if (reader != null) {
                            Log.d(TAG, "OutputConfiguration setImageReader: " + reader);
                        }
                    }
                });
                Log.d(TAG, "Hooked: OutputConfiguration#setImageReader");
            } catch (Throwable ignored) {}

            // Hook setSurface
            try {
                Method setSurface = ocClass.getDeclaredMethod("setSurface", Surface.class);
                XposedBridge.hookMethod(setSurface, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Surface surface = (Surface) param.args[0];
                        if (surface != null && surface.isValid()) {
                            videoEngine.addTarget(surface);
                            Log.d(TAG, "OutputConfiguration setSurface -> videoEngine");
                        }
                    }
                });
                Log.d(TAG, "Hooked: OutputConfiguration#setSurface");
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(TAG, "OutputConfiguration hook failed: " + t.getMessage());
        }
    }

    /**
     * Wrapped listener that intercepts ImageReader callback.
     */
    private class WrappedListener implements android.media.ImageReader.OnImageAvailableListener {

        private final ImageReader reader;
        private final android.media.ImageReader.OnImageAvailableListener original;
        private final Handler handler;

        WrappedListener(ImageReader reader,
                        android.media.ImageReader.OnImageAvailableListener original,
                        Handler handler) {
            this.reader = reader;
            this.original = original;
            this.handler = handler != null ? handler : new Handler(Looper.getMainLooper());
        }

        @Override
        public void onImageAvailable(android.media.ImageReader reader) {
            if (!isCapturing) {
                // Not in capture state, pass through to original
                if (original != null) {
                    original.onImageAvailable(reader);
                }
                return;
            }

            // Replace the image with a JPEG frame from the virtual video
            try {
                android.media.Image image = reader.acquireLatestImage();
                if (image != null) {
                    replaceImageBuffer(image);
                    image.close();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error replacing ImageReader buffer: " + t.getMessage());
            }

            if (original != null) {
                original.onImageAvailable(reader);
            }
        }

        private void replaceImageBuffer(android.media.Image image) {
            String mediaPath = KycBypassApp.settingsManager.getMediaPath();
            if (mediaPath == null) return;

            // Get the expected capture dimensions
            int width = image.getWidth();
            int height = image.getHeight();

            byte[] jpeg = captureManager.extractFrame(
                    KycBypassApp.instance,
                    mediaPath,
                    width,
                    height,
                    KycBypassApp.settingsManager.getRotationAngle(),
                    KycBypassApp.settingsManager.isMirrored(),
                    (int) captureTimestampMs,
                    Integer.MAX_VALUE
            );

            if (jpeg == null) return;

            // Get the first plane of the image and overwrite its buffer
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0 || planes[0].getBuffer() == null) return;

            ByteBuffer buffer = planes[0].getBuffer();
            buffer.position(0);
            buffer.limit(jpeg.length);
            buffer.put(jpeg);
            buffer.position(0);
        }
    }

    // ---- State management ----
    public void triggerCapture() {
        isCapturing = true;
        captureTimestampMs = (int) videoEngine.getCurrentPositionMs();

        // Auto-reset after 5 seconds
        mainHandler.postDelayed(() -> {
            isCapturing = false;
        }, 5000);
    }

    public void setCaptureTimestamp(long ms) {
        captureTimestampMs = ms;
    }

    // ---- Internal data class ----
    private static class ReaderEntry {
        final ImageReader reader;
        final int maxImages;

        ReaderEntry(ImageReader reader, int maxImages) {
            this.reader = reader;
            this.maxImages = maxImages;
        }
    }
}
