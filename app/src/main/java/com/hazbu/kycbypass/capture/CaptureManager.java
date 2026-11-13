package com.hazbu.kycbypass.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import android.graphics.Canvas;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Extracts still frames from video or image files for use as fake camera captures.
 * Adapted from xCam's XCamCapture + CaptureManager.
 *
 * This is called when the target app takes a photo:
 * 1. Grab a frame from the selected video at the current timestamp
 * 2. Apply rotation, mirroring, and scaling to match the app's expected dimensions
 * 3. Encode as JPEG and return the byte array
 */
public class CaptureManager {

    private long lastFrameTimeMs = 0;

    public CaptureManager() {
    }

    /**
     * Get a frame from the media file, ready to be injected as a captured photo.
     *
     * @param context    Application context
     * @param mediaPath  Path to video (.mp4) or image (.jpg/.png)
     * @param targetW    Expected output width
     * @param targetH    Expected output height
     * @param rotation   Rotation angle (0, 90, 180, 270)
     * @param mirrored   Whether to horizontally flip
     * @param timeMs     Timestamp within the video (ms), or 1000 for first frame
     * @param maxSize    Max JPEG size in bytes (for quality tuning)
     * @return JPEG byte array, or null on failure
     */
    public byte[] extractFrame(
            Context context,
            String mediaPath,
            int targetW,
            int targetH,
            int rotation,
            boolean mirrored,
            int timeMs,
            int maxSize
    ) {
        if (mediaPath == null || context == null) return null;

        Bitmap rawBitmap = null;
        MediaMetadataRetriever retriever = null;

        try {
            String lower = mediaPath.toLowerCase();

            if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm")) {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(context, android.net.Uri.fromFile(new java.io.File(mediaPath)));

                long durationMs = 0;
                String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (dur != null) {
                    try { durationMs = Long.parseLong(dur); } catch (NumberFormatException ignored) {}
                }

                long loopedTimeMs = (durationMs > 0) ? (timeMs % durationMs) : timeMs;
                long targetUs = loopedTimeMs * 1000L;

                // Try closest frame, then closest sync, then next sync
                rawBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (rawBitmap == null) {
                    rawBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
                if (rawBitmap == null) {
                    rawBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_NEXT_SYNC);
                }

                lastFrameTimeMs = timeMs;
            } else {
                // Static image
                try (InputStream is = context.getContentResolver().openInputStream(
                        android.net.Uri.fromFile(new java.io.File(mediaPath)))
                ) {
                    if (is != null) {
                        rawBitmap = BitmapFactory.decodeStream(is);
                    }
                }
            }

            if (rawBitmap == null) return null;

            // Apply transforms: scale, rotate, mirror, then crop/center to target size
            Bitmap result = applyTransforms(rawBitmap, targetW, targetH, rotation, mirrored);

            // Encode as JPEG with quality tuning
            return encodeAsJpeg(result, maxSize);

        } catch (Exception e) {
            return null;
        } finally {
            if (rawBitmap != null) rawBitmap.recycle();
            if (retriever != null) retriever.release();
        }
    }

    /**
     * Get the current video position in milliseconds (for capture timing).
     */
    public int getCurrentVideoPositionMs() {
        return (int) (lastFrameTimeMs % 10000);
    }

    /**
     * Apply rotation, mirroring, and center-crop scaling.
     */
    private Bitmap applyTransforms(
            Bitmap source,
            int targetW,
            int targetH,
            int rotation,
            boolean mirrored
    ) {
        int srcW = source.getWidth();
        int srcH = source.getHeight();

        Matrix matrix = new Matrix();

        // Horizontal mirror
        if (mirrored) {
            matrix.postScale(-1f, 1f, srcW / 2f, srcH / 2f);
        }

        // Rotation
        if (rotation != 0) {
            matrix.postRotate(rotation, srcW / 2f, srcH / 2f);
        }

        // Scale to fit target aspect ratio (center-crop)
        float rotatedSrcW = (rotation % 180 != 0) ? srcH : srcW;
        float rotatedSrcH = (rotation % 180 != 0) ? srcW : srcH;

        float scaleX = (float) targetW / rotatedSrcW;
        float scaleY = (float) targetH / rotatedSrcH;
        float scale = Math.max(scaleX, scaleY); // crop to fill

        matrix.postScale(scale, scale);

        Bitmap transformed = Bitmap.createBitmap(source, 0, 0, srcW, srcH, matrix, true);

        // Center crop to exact target size
        Bitmap finalBitmap = Bitmap.createBitmap(targetW, targetH);
        android.graphics.Canvas canvas = new android.graphics.Canvas(finalBitmap);
        float left = (targetW - transformed.getWidth()) / 2f;
        float top = (targetH - transformed.getHeight()) / 2f;
        canvas.drawBitmap(transformed, left, top, null);

        if (transformed != source) transformed.recycle();

        return finalBitmap;
    }

    /**
     * Compress bitmap to JPEG, reducing quality if needed to meet maxSize.
     */
    private byte[] encodeAsJpeg(Bitmap bitmap, int maxSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int quality = 100;

        do {
            out.reset();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            byte[] data = out.toByteArray();

            if (data.length <= maxSize) return data;

            quality -= 5;
        } while (quality > 5);

        return out.toByteArray();
    }
}
