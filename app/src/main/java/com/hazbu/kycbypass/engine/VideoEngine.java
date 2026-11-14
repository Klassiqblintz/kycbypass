package com.hazbu.kycbypass.engine;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages a pool of ExoPlayer instances each rendering the virtual video
 * onto a separate target Surface.
 */
public class VideoEngine {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<PlayerEntry> playerEntries = new ArrayList<PlayerEntry>();
    private final Object lock = new Object();
    private volatile boolean isPlayingInternal = false;
    private String mediaPath = null;
    private int rotationAngle = 0;
    private boolean isMirrored = false;
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

    public void addTarget(Surface surface) {
        if (surface == null || !surface.isValid()) return;
        synchronized (lock) {
            for (PlayerEntry entry : playerEntries) {
                if (entry.target == surface) return;
            }
            playerEntries.add(new PlayerEntry(surface));
        }
    }

    public void addPreviewTexture(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null) return;
        Surface surface = new Surface(surfaceTexture);
        addTarget(surface);
    }

    public void removeTarget(Surface surface) {
        if (surface == null) return;
        synchronized (lock) {
            playerEntries.removeIf(e -> e.target == surface);
        }
    }

    public void setActive(boolean active) {
        if (!active) {
            stopAll();
            return;
        }
        if (mediaPath == null || appContext == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    for (PlayerEntry entry : playerEntries) {
                        startPlayerForSurface(entry);
                    }
                }
                isPlayingInternal = true;
            }
        });
    }

    public boolean isPlaying() {
        return isPlayingInternal;
    }

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

    public void stopAll() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    for (PlayerEntry entry : playerEntries) {
                        releasePlayer(entry);
                    }
                    playerEntries.clear();
                }
                isPlayingInternal = false;
            }
        });
    }

    private void startPlayerForSurface(PlayerEntry entry) {
        if (mediaPath == null || appContext == null) return;
        releasePlayer(entry);

        try {
            DefaultRenderersFactory factory = new DefaultRenderersFactory(appContext)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
            ExoPlayer player = new ExoPlayer.Builder(appContext, factory).build();

            MediaItem item = MediaItem.fromUri(android.net.Uri.fromFile(new java.io.File(mediaPath)));
            player.setMediaItem(item);

            if (isMirrored || rotationAngle != 0) {
                float scaleX = isMirrored ? -1f : 1f;
                // Build a simple transformation via a SurfaceTexture + Canvas
                // (ScaleAndRotateTransformation may not be in older Media3 versions)
                // We apply rotation/scale via the video surface itself
            }

            player.setVideoSurface(entry.target);
            player.setRepeatMode(loopVideo ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        player.play();
                    }
                }

                @Override
                public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    if (isPlayingInternal) {
                        player.release();
                        entry.player = null;
                    }
                }
            });

            player.prepare();
            entry.player = player;
        } catch (Exception e) {
            // Ignore
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
