package com.hazbu.kycbypass.engine;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages a pool of ExoPlayer instances that each render the virtual video
 * onto a separate target Surface. This is the core "video injection" engine
 * borrowed from xCam's MediaEngine + XCamEngine.
 *
 * Usage:
 * 1. Call addTarget(surface) for each Surface the target app creates for preview
 * 2. Call setActive(true) to start all players
 * 3. Call setActive(false)/stop() to stop all players
 */
@UnstableApi
public class VideoEngine {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<PlayerEntry> playerEntries = new ArrayList<>();
    private final Object lock = new Object();

    private volatile boolean isPlayingInternal = false;
    private String mediaPath = null;
    private int rotationAngle = 0;
    boolean isMirrored = false;
    private boolean loopVideo = true;
    private Context appContext;

    public void setMediaPath(String path) {
        mediaPath = path;
    }

    public void setRotationAngle(int angle) {
        rotationAngle = angle % 360;
    }

    public void setIsMirrored(boolean mirrored) {
        isMirrored = mirrored;
    }

    public void setLoopVideo(boolean loop) {
        loopVideo = loop;
    }

    public void setAppContext(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Register a Surface that should receive the video feed.
     * Each Surface gets its own ExoPlayer instance.
     */
    public void addTarget(Surface surface) {
        if (surface == null || !surface.isValid()) return;

        synchronized (lock) {
            // Check if already tracked
            for (PlayerEntry entry : playerEntries) {
                if (entry.target == surface) return;
            }
            playerEntries.add(new PlayerEntry(surface));
        }
    }

    /**
     * Register a SurfaceTexture directly (Camera1 legacy path).
     * Creates a Surface from it internally.
     */
    public void addPreviewTexture(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null) return;
        Surface surface = new Surface(surfaceTexture);
        addTarget(surface);
    }

    /**
     * Remove a Surface from the pool.
     */
    public void removeTarget(Surface surface) {
        if (surface == null) return;
        synchronized (lock) {
            playerEntries.removeIf(e -> e.target == surface);
        }
    }

    /**
     * Start all players if media path is set.
     */
    public void setActive(boolean active) {
        if (!active) {
            stopAll();
            return;
        }

        if (mediaPath == null || appContext == null) return;

        mainHandler.post(() -> {
            synchronized (lock) {
                for (PlayerEntry entry : playerEntries) {
                    startPlayerForSurface(entry);
                }
            }
            isPlayingInternal = true;
        });
    }

    public boolean isPlaying() {
        return isPlayingInternal;
    }

    /**
     * Get the current playback position in milliseconds of the first active player.
     * Returns 0 if no player is active.
     */
    public long getCurrentPositionMs() {
        synchronized (lock) {
            for (PlayerEntry entry : playerEntries) {
                if (entry.player != null) {
                    try {
                        return entry.player.getCurrentPosition();
                    } catch (Exception ignored) {}
                }
            }
        }
        return 0;
    }

    /**
     * Stop all players and release all resources.
     */
    public void stopAll() {
        mainHandler.post(() -> {
            synchronized (lock) {
                for (PlayerEntry entry : playerEntries) {
                    releasePlayer(entry);
                }
                playerEntries.clear();
            }
            isPlayingInternal = false;
        });
    }

    private void startPlayerForSurface(PlayerEntry entry) {
        if (mediaPath == null || appContext == null) return;

        // Release existing player
        releasePlayer(entry);

        try {
            DefaultRenderersFactory factory = new DefaultRenderersFactory(appContext)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);

            ExoPlayer player = new ExoPlayer.Builder(appContext, factory).build();

            MediaItem item = MediaItem.fromUri(android.net.Uri.fromFile(new java.io.File(mediaPath)));
            player.setMediaItem(item);

            // Apply transformations
            List<ScaleAndRotateTransformation> effects = new ArrayList<>();
            if (isMirrored || rotationAngle != 0) {
                float scaleX = isMirrored ? -1f : 1f;
                effects.add(new ScaleAndRotateTransformation.Builder()
                    .setScale(scaleX, 1f)
                    .setRotationDegrees(rotationAngle)
                    .build());
            }
            if (!effects.isEmpty()) {
                player.setVideoEffects(effects);
            }

            player.setVideoSurface(entry.target);
            player.setRepeatMode(loopVideo ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);

            player.addListener(new Player.Listener() {
                @Override
                public void onIsPlayingChanged(boolean playing) {
                    // Track state
                }

                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        player.play();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    // If player errors, try to restart
                    if (isPlayingInternal) {
                        player.release();
                        player = null;
                        entry.player = null;
                    }
                }
            });

            player.prepare();
            entry.player = player;
        } catch (Exception e) {
            // Ignore – will retry on next active call
        }
    }

    private void releasePlayer(PlayerEntry entry) {
        if (entry.player != null) {
            try {
                entry.player.stop();
                entry.player.clearVideoSurface();
                entry.player.release();
            } catch (Exception ignored) {}
            entry.player = null;
        }
    }

    private static class PlayerEntry {
        final Surface target;
        ExoPlayer player;

        PlayerEntry(Surface target) {
            this.target = target;
        }
    }
}
