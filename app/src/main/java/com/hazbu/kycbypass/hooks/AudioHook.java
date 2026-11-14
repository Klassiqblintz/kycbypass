package com.hazbu.kycbypass.hooks;

import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.hazbu.kycbypass.KycBypassApp;
import com.hazbu.kycbypass.xposed.XC_MethodHook;
import com.hazbu.kycbypass.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * Audio injection hook.
 *
 * Hooks AudioRecord.read() to inject audio samples from the selected video file
 * into the target app's audio capture pipeline. This allows the virtual camera
 * to carry audio along with the video when the target app records.
 *
 * How it works:
 * 1. Hook AudioRecord.read() — intercept every audio buffer read
 * 2. When the target app reads audio, replace the PCM bytes with samples from
 *    the selected video file (decoded via MediaCodec/MediaExtractor)
 * 3. Loop the audio so it stays in sync with the video
 *
 * This is critical for video recording bypass — without audio injection,
 * the target app records silent video.
 */
public class AudioHook {

    private static final String TAG = "AudioHook";

    private volatile boolean isActive = false;
    private volatile boolean isReading = false;

    // Audio buffer pool for lazy loading
    private volatile byte[] audioBuffer = null;
    private volatile int audioPosition = 0;
    private volatile int audioSampleRate = 44100;
    private volatile int audioChannelConfig = android.media.AudioFormat.CHANNEL_IN_STEREO;
    private volatile int audioEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT;
    private volatile int audioBufferSize = 0;

    public AudioHook() {}

    public void install() {
        try {
            hookAudioRecordRead();
            Log.d(TAG, "Audio hooks installed successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Audio hooks failed: " + t.getMessage(), t);
        }
    }

    // ---- AudioRecord.read() hook ----
    private void hookAudioRecordRead() {
        try {
            // AudioRecord.read() has two overloads:
            //   read(byte[] audioData, int offsetInBytes, int sizeInBytes)
            //   read(ShortBuffer audioData, int offsetInSamples, int samples)
            for (Method method : AudioRecord.class.getDeclaredMethods()) {
                if (method.getName().equals("read")) {
                    final Method readMethod = method;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!isActive) return;
                            if (isReading) return;

                            AudioRecord record = (AudioRecord) param.thisObject;

                            // Only hook if this AudioRecord is from the target app
                            // (We detect by checking the device ID or sample rate)
                            if (!isTargetAudioRecord(record)) return;

                            // Get buffer size from params
                            int sizeInBytes = 0;
                            if (param.args.length >= 3 && param.args[2] instanceof Integer) {
                                sizeInBytes = (Integer) param.args[2];
                            }
                            if (sizeInBytes == 0) return;

                            // Handle byte[] overload
                            if (param.args[0] instanceof byte[]) {
                                byte[] buffer = (byte[]) param.args[0];
                                int offset = param.args.length > 1 && param.args[1] instanceof Integer
                                        ? (Integer) param.args[1] : 0;

                                byte[] audioData = getAudioSamples(sizeInBytes);
                                if (audioData != null) {
                                    int copyLen = Math.min(audioData.length, sizeInBytes);
                                    System.arraycopy(audioData, 0, buffer, offset, copyLen);
                                }
                            }

                            // Handle ShortBuffer overload
                            if (param.args[0] instanceof ShortBuffer) {
                                ShortBuffer buffer = (ShortBuffer) param.args[0];
                                short[] samples = getAudioSamplesAsShorts(sizeInBytes);
                                if (samples != null) {
                                    int copyLen = Math.min(samples.length, buffer.remaining());
                                    buffer.put(samples, 0, copyLen);
                                }
                            }
                        }
                    });

                    Log.d(TAG, "Hooked: AudioRecord#read (" + method.getParameterTypes().length + " args)");
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord.read hook failed: " + t.getMessage());
        }
    }

    /**
     * Check if this AudioRecord belongs to the target app.
     * We detect this by matching the sample rate and channel config.
     */
    private boolean isTargetAudioRecord(AudioRecord record) {
        try {
            int sampleRate = record.getSampleRate();
            int channelConfig = record.getChannelCount();

            // Most KYC apps use 16kHz or 44.1kHz mono/stereo
            if (sampleRate == 16000 || sampleRate == 44100 || sampleRate == 48000) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Get PCM audio samples for the requested size.
     * Loads from the selected video file on first call, then loops.
     */
    private byte[] getAudioSamples(int byteCount) {
        if (!isActive) return null;

        String mediaPath = KycBypassApp.settingsManager.getMediaPath();
        if (mediaPath == null) return null;

        // Lazy load: decode audio from the video file once
        if (audioBuffer == null || audioPosition >= audioBufferSize) {
            audioBuffer = decodeAudioFromMedia(mediaPath, byteCount * 2); // Request more samples
            if (audioBuffer == null) return null;
            audioPosition = 0;
            audioBufferSize = audioBuffer.length;
        }

        // Return the next chunk
        int available = audioBufferSize - audioPosition;
        int toCopy = Math.min(available, byteCount);

        byte[] chunk = new byte[toCopy];
        System.arraycopy(audioBuffer, audioPosition, chunk, 0, toCopy);
        audioPosition += toCopy;

        // Loop if we've reached the end
        if (audioPosition >= audioBufferSize) {
            if (KycBypassApp.settingsManager.getLoopVideo()) {
                audioPosition = 0;
            } else {
                // Fill remaining with zeros (silence)
                if (toCopy < byteCount) {
                    byte[] fullChunk = new byte[byteCount];
                    System.arraycopy(chunk, 0, fullChunk, 0, toCopy);
                    chunk = fullChunk;
                }
            }
        }

        return chunk;
    }

    /**
     * Get PCM audio samples as short array (16-bit PCM).
     */
    private short[] getAudioSamplesAsShorts(int byteCount) {
        byte[] bytes = getAudioSamples(byteCount);
        if (bytes == null) return null;

        short[] shorts = new short[bytes.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = (short) ((bytes[i * 2 + 1] << 8) | (bytes[i * 2] & 0xFF));
        }
        return shorts;
    }

    /**
     * Decode audio from a media file using MediaExtractor + MediaCodec.
     * Returns raw PCM bytes (16-bit).
     *
     * Note: This is a simplified decoder. For production, use
     * MediaCodec + AudioTrack for proper decoding.
     */
    private byte[] decodeAudioFromMedia(String mediaPath, int maxBytes) {
        try {
            android.media.MediaExtractor extractor = new android.media.MediaExtractor();
            extractor.setDataSource(mediaPath);

            // Find the audio track
            int trackCount = extractor.getTrackCount();
            int audioTrackIndex = -1;

            for (int i = 0; i < trackCount; i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    // Extract sample rate and channel info
                    if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                        audioSampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                    }
                    break;
                }
            }

            if (audioTrackIndex == -1) {
                // No audio track in the media file
                extractor.release();
                return null;
            }

            extractor.selectTrack(audioTrackIndex);

            // Allocate output buffer
            byte[] output = new byte[Math.min(maxBytes, 65536)];
            ByteBuffer buffer = ByteBuffer.wrap(output);

            int bytesRead = 0;
            long endTimeUs = -1;

            // Try to get duration for looping
            String duration = null;
            try {
                duration = extractor.getFormat().getString(android.media.MediaFormat.KEY_DURATION);
            } catch (Exception ignored) {}
            if (duration != null) {
                try { endTimeUs = Long.parseLong(duration); } catch (NumberFormatException ignored) {}
            }

            while (bytesRead < output.length) {
                int sampleSize = extractor.readSampleData(buffer, bytesRead);
                if (sampleSize < 0) {
                    // End of stream
                    if (KycBypassApp.settingsManager.getLoopVideo() && endTimeUs > 0) {
                        extractor.unselectTrack(audioTrackIndex);
                        extractor.selectTrack(audioTrackIndex);
                        extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_NEXT_SYNC);
                        buffer.position(bytesRead);
                        continue;
                    }
                    break;
                }
                bytesRead += sampleSize;
                extractor.advance();
            }

            extractor.release();

            // Trim to actual read bytes
            if (bytesRead < output.length) {
                byte[] trimmed = new byte[bytesRead];
                System.arraycopy(output, 0, trimmed, 0, bytesRead);
                return trimmed;
            }

            return output;

        } catch (Throwable t) {
            Log.e(TAG, "Audio decode failed: " + t.getMessage());
            return null;
        }
    }

    // ---- State management ----
    public void setActive(boolean active) {
        this.isActive = active;
        if (!active) {
            // Reset audio buffer for next activation
            audioBuffer = null;
            audioPosition = 0;
        }
    }

    public boolean isActive() {
        return isActive;
    }
}
