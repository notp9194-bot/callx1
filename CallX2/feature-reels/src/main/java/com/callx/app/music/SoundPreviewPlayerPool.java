package com.callx.app.music;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.callx.app.cache.UnifiedVideoCacheManager;

/**
 * SoundPreviewPlayerPool — single reusable ExoPlayer for the Sound Detail
 * screen's audio-preview player.
 *
 * PERF (mirrors Reels' {@code ExoPlayerPool}, scoped to pool-size-1 since
 * only one sound preview ever plays at a time app-wide): building an
 * ExoPlayer — renderers factory, decoder setup, internal playback
 * Handler/thread — is the expensive part of opening this screen.
 * {@code SoundDetailFragment} previously paid that cost on every single
 * open: {@code initAndStartPlayer()} called {@code new ExoPlayer.Builder(...)
 * .build()} fresh every time, even reopening the exact same sound seconds
 * later (e.g. bouncing between a reel and its sound page). This pool keeps
 * one instance alive app-wide and hands it back out, so every open after
 * the first skips straight to {@code setMediaItem()+prepare()} — no
 * renderer/decoder/thread rebuild.
 *
 * AUDIO FOCUS: handled by ExoPlayer itself, not manual AudioManager/
 * AudioFocusRequest bookkeeping. The pooled instance is built once with
 * {@code setAudioAttributes(attrs, handleAudioFocus=true)}, so it
 * transparently requests AUDIOFOCUS_GAIN on play, pauses/ducks on a
 * transient loss (an incoming call, another app starting playback), and
 * resumes on regain — exactly the behavior Sound Detail was missing.
 *
 * PERF (disk cache): {@link #buildMediaSource(String)} routes playback
 * through {@link UnifiedVideoCacheManager}'s MUSIC {@code CacheDataSource}
 * (shares the same on-disk, DB-indexed SimpleCache as X/Status/Chat).
 * {@code SoundDetailFragment} previously called
 * {@code exoPlayer.setMediaItem(MediaItem.fromUri(url))} directly — a plain
 * {@code DefaultHttpDataSource} with no cache underneath, so replaying the
 * exact same preview URL (reopening a sound, or the retry/fallback path in
 * {@code onPlayerError}) re-downloaded it from the network every time. Now
 * the first play caches bytes to disk as they stream, and every subsequent
 * play of that same URL — this session or a future one, since the cache
 * index is SQLite-backed — is served straight off disk.
 *
 * Thread-confined to the main thread, same as every other ExoPlayer touch
 * point in this codebase.
 */
@OptIn(markerClass = UnstableApi.class)
public final class SoundPreviewPlayerPool {

    private static final String TAG = "SoundPreviewPlayerPool";
    private static volatile SoundPreviewPlayerPool instance;

    private final Context appCtx;
    private ExoPlayer player; // lazily built, kept alive across screen opens

    private SoundPreviewPlayerPool(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    public static SoundPreviewPlayerPool get(Context ctx) {
        if (instance == null) {
            synchronized (SoundPreviewPlayerPool.class) {
                if (instance == null) instance = new SoundPreviewPlayerPool(ctx);
            }
        }
        return instance;
    }

    /**
     * Context-free accessor for release paths (e.g. an async
     * onPlayerError callback firing after the Fragment has detached, where
     * requireContext()/getContext() may throw or return null). Returns null
     * only if acquire() was never called this process — in which case
     * there's nothing pooled to release anyway.
     */
    public static SoundPreviewPlayerPool getExisting() {
        return instance;
    }

    /**
     * Builds a disk-cached {@link MediaSource} for a preview/sound URL —
     * caller passes this to {@code exoPlayer.setMediaSource(...)} instead of
     * {@code setMediaItem(MediaItem.fromUri(url))}. Goes through
     * {@link UnifiedVideoCacheManager}'s {@code MUSIC} module, so a replay of
     * the same URL (same sound reopened, or the retry/fallback-URL path in
     * {@code onPlayerError}) is served from disk instead of re-hitting the
     * network. {@code UnifiedVideoCacheManager} is initialized once at app
     * startup ({@code CallxApp}), so it's always ready by the time a Sound
     * Detail screen can open.
     */
    @NonNull
    public MediaSource buildMediaSource(@NonNull String url) {
        CacheDataSource.Factory cacheFactory =
            UnifiedVideoCacheManager.getFactory(UnifiedVideoCacheManager.Module.MUSIC);
        return new ProgressiveMediaSource.Factory(cacheFactory)
            .createMediaSource(MediaItem.fromUri(url));
    }

    /**
     * Hand out the pooled player, building it lazily on first use. Caller
     * still owns addListener()/setMediaSource()/setRepeatMode()/prepare();
     * call {@link #release(Player.Listener)} (never {@code player.release()}
     * directly) when the screen goes away so the instance survives for the
     * next Sound Detail open.
     */
    public synchronized ExoPlayer acquire() {
        if (player == null) {
            DefaultTrackSelector ts = new DefaultTrackSelector(appCtx);
            ts.setParameters(ts.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true));
            player = new ExoPlayer.Builder(appCtx)
                .setTrackSelector(ts)
                // Fixed buffer window baked in once at construction (unlike the
                // old per-open thermal-recompute) — safe middle-ground size since
                // this is audio-only and buffer cost is small either way.
                .setLoadControl(new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3_000, 10_000, 1_000, 1_500).build())
                .build();
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
            player.setAudioAttributes(attrs, /* handleAudioFocus= */ true);
            Log.d(TAG, "acquire: built pooled instance (first use)");
        } else {
            Log.d(TAG, "acquire: reused pooled instance");
        }
        return player;
    }

    /**
     * Return the player after a screen goes away — stops it, clears media
     * items, resets repeat mode, and strips the caller's listener. Does
     * NOT call {@code player.release()}, so the instance (and its decoder/
     * thread setup) survives for the next acquire().
     */
    public synchronized void release(Player.Listener listener) {
        if (player == null) return;
        try {
            if (listener != null) player.removeListener(listener);
            player.stop();
            player.clearMediaItems();
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
        } catch (Exception e) {
            Log.w(TAG, "release: reset failed, hard-releasing instead: " + e.getMessage());
            hardRelease();
        }
    }

    /**
     * Full teardown of the pooled instance. Call only from a process-wide
     * low-memory signal (e.g. TRIM_MEMORY_CRITICAL) — never on ordinary
     * Sound Detail screen close, where keeping the instance warm is the
     * entire point of this pool.
     */
    public synchronized void hardRelease() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
        }
        player = null;
    }
}
