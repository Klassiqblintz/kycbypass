package com.hazbu.kycbypass.capture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;

/**
 * Extracts still frames from video or image files for use as fake camera captures.
 */
public class CaptureManager {

    private long lastFrameTimeMs = 0;

    public CaptureManager() {
    }

    /**
     * Get a frame from the media file, ready to be injected as a captured photo.
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
                retriever.setDataSource(context, android.net.Uri.fromFile(new File(mediaPath)));

                long durationMs = 0;
                String dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (dur != null) {
                    try { durationMs = Long.parseLong(dur); } catch (NumberFormatException ignored) {}
                }

                long loopedTimeMs = (durationMs > 0) ? (timeMs % durationMs) : timeMs;
                long targetUs = loopedTimeMs * 1000L;

                rawBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (rawBitmap == null) {
                    rawBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
                if (rawBitmap == null) {
                    rawBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_NEXT_SYNC);
                }

                lastFrameTimeMs = timeMs;
            } else {
                try (InputStream is = context.getContentResolver().openInputStream(
                        android.net.Uri.fromFile(new File(mediaPath)))
                ) {
                    if (is != null) {
                        rawBitmap = BitmapFactory.decodeStream(is);
                    }
                }
            }

            if (rawBitmap == null) return null;

            Bitmap result = applyTransforms(rawBitmap, targetW, targetH, rotation, mirrored);
            return encodeAsJpeg(result, maxSize);

        } catch (Exception e) {
            return null;
        } finally {
            if (rawBitmap != null) rawBitmap.recycle();
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }
    }

    public int getCurrentVideoPositionMs() {
        return (int) (lastFrameTimeMs % 10000);
    }

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

        if (mirrored) {
            matrix.postScale(-1f, 1f, srcW / 2f, srcH / 2f);
        }

        if (rotation != 0) {
            matrix.postRotate(rotation, srcW / 2f, srcH / 2f);
        }

        float rotatedSrcW = (rotation % 180 != 0) ? srcH : srcW;
        float rotatedSrcH = (rotation % 180 != 0 ? srcW : srcH);

        float scaleX = (float) targetW / rotatedSrcW;
        float scaleY = (float) targetH / rotatedSrcH;
        float scale = Math.max(scaleX, scaleY);

        matrix.postScale(scale, scale);

        Bitmap transformed = Bitmap.createBitmap(source, 0, 0, srcW, srcH, matrix, true);

        Bitmap finalBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(finalBitmap);
        float left = (targetW - transformed.getWidth()) / 2f;
        float top = (targetH - transformed.getHeight()) / 2f;
        canvas.drawBitmap(transformed, left, top, null);

        if (transformed != source) transformed.recycle();

        return finalBitmap;
    }

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
