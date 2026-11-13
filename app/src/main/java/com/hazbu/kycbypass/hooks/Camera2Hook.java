package com.hazbu.kycbypass.hooks;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.params.OutputConfiguration;
import android.view.Surface;

import com.hazbu.kycbypass.KycBypassApp;
import com.hazbu.kycbypass.engine.VideoEngine;
import com.hazbu.kycbypass.xposed.XC_MethodHook;
import com.hazbu.kycbypass.xposed.XposedBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * Camera2 API hook layer.
 *
 * Mirrors xCam's Camera2Hook.java.
 *
 * How it works:
 * 1. Hook CameraDeviceImpl.createCaptureSession() → intercept the Surface list
 * 2. For each preview Surface, register it in VideoEngine
 * 3. Replace the Surface with a dummy SurfaceTexture surface
 * 4. VideoEngine plays the video onto the original Surface
 * 5. Hook CaptureRequest.Builder.addTarget() → redirect preview targets to dummy
 * 6. Hook ImageReader → intercept JPEG capture
 * 7. Hook ContentResolver.openFileDescriptor() → intercept MediaStore writes
 * 8. Hook Bitmap.compress() / decodeStream() → intercept decode path
 */
public class Camera2Hook {

    private final VideoEngine videoEngine;
    private boolean previewSwapped = false;

    public Camera2Hook(VideoEngine videoEngine) {
        this.videoEngine = videoEngine;
    }

    public void install() {
        try {
            hookModernHijack();
            hookSurgicalDiverter();
            hookCaptureFailed();
            hookCleanup();
            android.util.Log.d("Camera2Hook", "Camera2 API hooks installed successfully");
        } catch (Throwable t) {
            android.util.Log.e("Camera2Hook", "Camera2 hooks failed: " + t.getMessage(), t);
        }
    }

    // ---- OutputConfiguration constructor hook (modern hijack) ----
    private void hookModernHijack() {
        try {
            Class<?> ocClass = Class.forName("android.hardware.camera2.params.OutputConfiguration");
            for (Constructor<?> constructor : ocClass.getDeclaredConstructors()) {
                XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (KycBypassApp.settingsManager.getMediaPath() == null) return;

                        Surface surface = null;
                        int surfaceIndex = -1;
                        Object[] args = param.getArgs();
                        for (int i = 0; i < args.length; i++) {
                            if (args[i] instanceof Surface && ((Surface) args[i]).isValid()) {
                                surface = (Surface) args[i];
                                surfaceIndex = i;
                                break;
                            }
                        }

                        if (surface == null) return;

                        String sStr = surface.toString();
                        boolean isPreview = sStr.contains("SurfaceTexture")
                                || sStr.contains("SurfaceView")
                                || sStr.contains("BLAST")
                                || sStr.contains("Surface");

                        if (isPreview) {
                            videoEngine.addTarget(surface);
                        }

                        if (isPreview && !previewSwapped) {
                            String surfaceType = sStr.contains("SurfaceView") ? "SurfaceView" : "SurfaceTexture";
                            android.util.Log.d("Camera2Hook", "Hijacking " + surfaceType + ": " + sStr);
                            previewSwapped = true;
                            videoEngine.setActive(true);

                            Surface dummy = getDummySurface();
                            if (dummy != null && dummy.isValid()) {
                                args[surfaceIndex] = dummy;
                            }
                        }
                    }
                });
                android.util.Log.d("Camera2Hook", "Hooked: OutputConfiguration constructor");
            }
        } catch (Throwable t) {
            android.util.Log.e("Camera2Hook", "Modern hijack failed: " + t.getMessage());
        }
    }

    // ---- CaptureRequest.Builder.addTarget() surgical diverter ----
    private void hookSurgicalDiverter() {
        try {
            Method addTarget = CaptureRequest.Builder.class.getDeclaredMethod("addTarget", Surface.class);

            XposedBridge.hookMethod(addTarget, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Surface surface = (Surface) param.args[0];
                    if (surface == null) return;

                    try {
                        Integer intent = (Integer) ((CaptureRequest.Builder) param.thisObject)
                                .get(CaptureRequest.CONTROL_CAPTURE_INTENT);
                        if (intent != null) {
                            if (intent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                                android.util.Log.d("Camera2Hook", "addTarget: STILL_CAPTURE");
                            } else if (intent == CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD) {
                                android.util.Log.d("Camera2Hook", "addTarget: VIDEO_RECORD");
                            }
                        }
                    } catch (Throwable ignored) {}

                    if (isPreviewSurface(surface)) {
                        param.args[0] = getDummySurface();
                    }
                }
            });
            android.util.Log.d("Camera2Hook", "Hooked: CaptureRequest.Builder#addTarget");
        } catch (Throwable t) {
            android.util.Log.e("Camera2Hook", "Surgical diverter failed: " + t.getMessage());
        }
    }

    // ---- onCaptureFailed ----
    private void hookCaptureFailed() {
        try {
            Method method = CameraCaptureSession.CaptureCallback.class.getDeclaredMethod(
                    "onCaptureFailed",
                    CameraCaptureSession.class,
                    CaptureRequest.class,
                    CaptureFailure.class
            );
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    CaptureFailure failure = (CaptureFailure) param.args[2];
                    if (failure != null) {
                        android.util.Log.d("Camera2Hook", "onCaptureFailed: reason=" + failure.getReason());
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    // ---- Cleanup hooks ----
    private void hookCleanup() {
        try {
            Class<?> deviceImplClass = Class.forName("android.hardware.camera2.impl.CameraDeviceImpl");
            Method close = deviceImplClass.getDeclaredMethod("close");
            XposedBridge.hookMethod(close, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    previewSwapped = false;
                    videoEngine.setActive(false);
                }
            });
            android.util.Log.d("Camera2Hook", "Hooked: CameraDeviceImpl#close");
        } catch (Throwable t) {
            android.util.Log.e("Camera2Hook", "Cleanup hook failed: " + t.getMessage());
        }
    }

    // ---- Helpers ----
    private Surface getDummySurface() {
        try {
            return new Surface(new android.graphics.SurfaceTexture(10));
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isPreviewSurface(Surface surface) {
        if (surface == null) return false;
        String sStr = surface.toString();
        return sStr.contains("SurfaceTexture")
                || sStr.contains("SurfaceView")
                || sStr.contains("BLAST")
                || sStr.contains("Surface");
    }

    public boolean isPreviewSwapped() {
        return previewSwapped;
    }
}
