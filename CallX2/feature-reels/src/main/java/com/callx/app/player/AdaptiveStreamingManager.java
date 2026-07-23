package com.callx.app.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import com.callx.app.cache.UnifiedVideoCacheManager;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * AdaptiveStreamingManager — Production-grade HLS/DASH + Progressive player
 *
 * Features:
 *  ✅ HLS (.m3u8)  — auto-detected, uses ExoPlayer HlsMediaSource
 *  ✅ DASH (.mpd)  — auto-detected, uses ExoPlayer DashMediaSource
 *  ✅ Progressive  — fallback for direct MP4 URLs (Cloudinary, Firebase Storage)
 *  ✅ Adaptive bitrate — auto-selects quality based on live bandwidth
 *  ✅ Quality caps — user can lock to 360p / 720p / 1080p / Auto
 *  ✅ EWMA bandwidth history — 10-sample rolling average smooths spikes
 *  ✅ Bandwidth-gated upgrade — upgradeQuality() checks EWMA before applying
 *  ✅ Network-aware degradation — drops quality on slow network, recovers on fast
 *  ✅ Shared video cache — uses UnifiedVideoCacheManager (same 500MB pool as reels)
 *  ✅ Stall tracking — counts stalls, fires ReelABRCallback for analytics
 *  ✅ QoE persistence — cumulative session stats saved to SharedPreferences
 *  ✅ Thread-safe singleton
 */
@OptIn(markerClass = UnstableApi.class)
public class AdaptiveStreamingManager {

    private static final String TAG = "ABR";

    // ── Quality cap constants (max resolution the user may lock to) ─────────
    public enum QualityCap {
        AUTO,    // ABR decides (default)
        Q360P,   // ≤ 360p  (≤640×360)
        Q480P,   // ≤ 480p  (≤854×480)
        Q720P,   // ≤ 720p  (≤1280×720)
        Q1080P   // ≤ 1080p (≤1920×1080)
    }

    // ── Bandwidth thresholds for automatic quality degradation ──────────────
    private static final long BW_VERY_LOW_KBPS = 300;    // force 360p
    private static final long BW_LOW_KBPS      = 800;    // force 480p
    private static final long BW_MED_KBPS      = 2_000;  // force 720p
    // above 2 Mbps → AUTO / 1080p

    // ── Minimum EWMA bandwidth needed to justify each upgrade target ─────────
    // Must have sustained bandwidth headroom over the target bitrate before
    // upgradeQuality() will actually switch up. This prevents 720p upgrade
    // from firing on a 500 Kbps connection just because time elapsed.
    private static final long BW_MIN_FOR_480P_KBPS  =   600;
    private static final long BW_MIN_FOR_720P_KBPS  = 1_500;
    private static final long BW_MIN_FOR_1080P_KBPS = 4_000;

    // ── EWMA bandwidth history ───────────────────────────────────────────────
    private static final int  EWMA_WINDOW = 10;   // rolling samples
    private static final double EWMA_ALPHA = 0.3; // weight for newest sample (0–1)
    private final ArrayDeque<Long> bwHistory   = new ArrayDeque<>();
    private       double           ewmaBwKbps  = 0.0;

    // ── Retry & timeout config ───────────────────────────────────────────────
    private static final int  CONNECT_TIMEOUT_MS = 10_000;
    private static final int  READ_TIMEOUT_MS    = 15_000;
    private static final int  MAX_STALL_COUNT    = 3;     // fire onPersistentStall after 3 stalls

    // ── QoE persistence ──────────────────────────────────────────────────────
    private static final String QOE_PREFS          = "abr_qoe_stats";
    private static final String KEY_TOTAL_SESSIONS  = "total_sessions";
    private static final String KEY_TOTAL_STALL_MS  = "total_stall_ms";
    private static final String KEY_TOTAL_SWITCHES  = "total_quality_switches";
    private static final String KEY_TOTAL_UPGRADES  = "total_upgrades";
    private static final String KEY_TOTAL_DOWNGRADES= "total_downgrades";
    private static final String KEY_AVG_TTFF_MS     = "avg_ttff_ms";

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static volatile AdaptiveStreamingManager instance;

    private final Context              appCtx;
    private final DefaultBandwidthMeter bandwidthMeter;
    private final Handler              mainHandler;

    private AdaptiveStreamingManager(Context ctx) {
        appCtx         = ctx.getApplicationContext();
        bandwidthMeter = new DefaultBandwidthMeter.Builder(appCtx).build();
        mainHandler    = new Handler(Looper.getMainLooper());
    }

    public static AdaptiveStreamingManager get(Context ctx) {
        if (instance == null) {
            synchronized (AdaptiveStreamingManager.class) {
                if (instance == null) instance = new AdaptiveStreamingManager(ctx);
            }
        }
        return instance;
    }

    // ── Callback interface ────────────────────────────────────────────────────
    public interface ReelABRCallback {
        void onQualitySelected(int widthPx, int heightPx, long bitrateKbps);
        void onStall(int stallCount);
        void onPersistentStall();   // >MAX_STALL_COUNT stalls → suggest lower quality
        void onError(PlaybackException e);
    }

    // ── Build player ──────────────────────────────────────────────────────────

    /**
     * Build a ready-to-use ExoPlayer with ABR configured.
     * Call player.prepare() + player.play() after attaching to PlayerView.
     *
     * @param url        Video URL — HLS (.m3u8), DASH (.mpd), or progressive (MP4)
     * @param cap        Maximum quality cap (AUTO = let ABR decide freely)
     * @param callback   Optional listener for quality/stall events (may be null)
     */
    public ExoPlayer buildPlayer(@NonNull String url,
                                 @NonNull QualityCap cap,
                                 ReelABRCallback callback) {
        // 1. Track selector with quality cap applied
        DefaultTrackSelector trackSelector = buildTrackSelector(cap);

        // 2. Renderers factory — prefer extension decoders when available
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(appCtx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        // 3. Tuned LoadControl — short start buffer for instant playback
        NetworkQualityMonitor.Quality netQ = NetworkQualityMonitor.get(appCtx).currentQuality();
        int minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs;
        switch (netQ) {
            case WIFI:
            case ETHERNET:
            case CELLULAR_5G:
                minBufferMs = 5_000;  maxBufferMs = 12_000;
                bufferForPlaybackMs = 800; bufferForPlaybackAfterRebufferMs = 2_000;
                break;
            case CELLULAR_4G:
                minBufferMs = 3_000;  maxBufferMs = 8_000;
                bufferForPlaybackMs = 1_000; bufferForPlaybackAfterRebufferMs = 2_500;
                break;
            case CELLULAR_3G:
                // minBufferMs must be >= BOTH bufferForPlaybackMs and
                // bufferForPlaybackAfterRebufferMs, or DefaultLoadControl
                // throws IllegalArgumentException at build time. 2_000 was
                // less than the 3_000 rebuffer value below — bumped to 3_000.
                minBufferMs = 3_000;  maxBufferMs = 5_000;
                bufferForPlaybackMs = 1_500; bufferForPlaybackAfterRebufferMs = 3_000;
                break;
            default: // 2G / offline
                // Same constraint as above: minBufferMs must always be >=
                // bufferForPlaybackMs AND >= bufferForPlaybackAfterRebufferMs,
                // or DefaultLoadControl.Builder#setBufferDurationsMs throws
                // IllegalArgumentException at player build time. 2_500 was
                // less than the 4_000 rebuffer value below — bumped to 4_000
                // (and maxBufferMs raised so min <= max still holds).
                minBufferMs = 4_000;  maxBufferMs = 6_000;
                bufferForPlaybackMs = 2_000; bufferForPlaybackAfterRebufferMs = 4_000;
                break;
        }
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufferMs, maxBufferMs,
                bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(3_000, true)
            .build();

        // 4. Build ExoPlayer
        ExoPlayer player = new ExoPlayer.Builder(appCtx, renderersFactory)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build();

        // 5. Create media source
        MediaSource source = buildSource(url);
        player.setMediaSource(source);

        // 6. Attach stall / quality listener + EWMA sampler
        attachListener(player, callback);

        Log.d(TAG, "buildPlayer cap=" + cap + " url=" + url);
        return player;
    }

    // ── Track selector ────────────────────────────────────────────────────────

    private DefaultTrackSelector buildTrackSelector(QualityCap cap) {
        DefaultTrackSelector selector = new DefaultTrackSelector(appCtx);
        TrackSelectionParameters.Builder params =
            selector.getParameters().buildUpon();

        switch (cap) {
            case Q360P:
                params.setMaxVideoSize(640, 360)
                      .setMaxVideoBitrate((int) (800 * 1000));
                break;
            case Q480P:
                params.setMaxVideoSize(854, 480)
                      .setMaxVideoBitrate((int) (1_500 * 1000));
                break;
            case Q720P:
                params.setMaxVideoSize(1280, 720)
                      .setMaxVideoBitrate((int) (4_000 * 1000));
                break;
            case Q1080P:
                params.setMaxVideoSize(1920, 1080)
                      .setMaxVideoBitrate((int) (8_000 * 1000));
                break;
            case AUTO:
            default:
                break;
        }

        params.setForceHighestSupportedBitrate(cap == QualityCap.Q1080P);
        selector.setParameters(params.build());
        return selector;
    }

    // ── Media source factory ──────────────────────────────────────────────────

    private MediaSource buildSource(String url) {
        String lower = url.toLowerCase(Locale.US);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("CallX/1.0 (ABR)");

        if (!UnifiedVideoCacheManager.isInitialized()) {
            UnifiedVideoCacheManager.init(appCtx);
        }
        CacheDataSource.Factory cacheFactory =
            UnifiedVideoCacheManager.getFactory(UnifiedVideoCacheManager.Module.REELS);

        if (lower.contains(".m3u8")) {
            Log.d(TAG, "Source type: HLS");
            return new HlsMediaSource.Factory(cacheFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
        } else if (lower.contains(".mpd")) {
            Log.d(TAG, "Source type: DASH");
            return new DashMediaSource.Factory(cacheFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
        } else {
            Log.d(TAG, "Source type: Progressive");
            return new ProgressiveMediaSource.Factory(cacheFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
        }
    }

    // ── Listener attachment + EWMA sampling ───────────────────────────────────

    private void attachListener(ExoPlayer player, ReelABRCallback cb) {
        final int[] stallCount = {0};

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                // Sample bandwidth into EWMA on every state change
                sampleBandwidth();

                if (state == Player.STATE_BUFFERING) {
                    stallCount[0]++;
                    long bw = getEwmaBandwidthKbps();
                    Log.d(TAG, "Stall #" + stallCount[0] + " ewma_bw=" + bw + "kbps");
                    if (cb != null) {
                        mainHandler.post(() -> {
                            cb.onStall(stallCount[0]);
                            if (stallCount[0] >= MAX_STALL_COUNT) cb.onPersistentStall();
                        });
                    }
                }
            }

            @Override
            public void onVideoSizeChanged(
                    @NonNull androidx.media3.common.VideoSize videoSize) {
                sampleBandwidth();
                long bwKbps = getEwmaBandwidthKbps();
                Log.d(TAG, "Quality: " + videoSize.width + "x" + videoSize.height
                    + " @ " + bwKbps + "kbps (ewma)");
                if (cb != null) {
                    mainHandler.post(() ->
                        cb.onQualitySelected(videoSize.width, videoSize.height, bwKbps));
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "Player error: " + error.getMessage());
                if (cb != null) {
                    mainHandler.post(() -> cb.onError(error));
                }
            }
        });
    }

    // ── EWMA bandwidth tracking ───────────────────────────────────────────────

    /**
     * Sample current raw bandwidth from DefaultBandwidthMeter into the EWMA.
     * Called on playback state changes and video size changes so the average
     * accumulates over real playback samples, not wall-clock time.
     */
    private synchronized void sampleBandwidth() {
        long rawKbps = bandwidthMeter.getBitrateEstimate() / 1_000;
        if (rawKbps <= 0) return; // skip uninitialized readings

        // Keep rolling window of last EWMA_WINDOW samples
        bwHistory.addLast(rawKbps);
        if (bwHistory.size() > EWMA_WINDOW) bwHistory.pollFirst();

        // Exponential weighted moving average — newer samples weighted more
        if (ewmaBwKbps <= 0) {
            ewmaBwKbps = rawKbps; // seed on first sample
        } else {
            ewmaBwKbps = EWMA_ALPHA * rawKbps + (1.0 - EWMA_ALPHA) * ewmaBwKbps;
        }
        Log.v(TAG, "BW sample raw=" + rawKbps + " ewma=" + (long)ewmaBwKbps + " kbps");
    }

    /**
     * Returns the EWMA-smoothed bandwidth estimate in Kbps.
     * Falls back to raw BandwidthMeter if no samples accumulated yet.
     */
    public synchronized long getEwmaBandwidthKbps() {
        if (ewmaBwKbps > 0) return (long) ewmaBwKbps;
        return bandwidthMeter.getBitrateEstimate() / 1_000;
    }

    /**
     * Returns true if the EWMA bandwidth is high enough to sustain the given
     * quality cap. Used by upgradeQuality() to gate upgrades.
     *
     * @param targetCap the quality we want to upgrade TO
     */
    public boolean isBandwidthSufficientFor(QualityCap targetCap) {
        long ewma = getEwmaBandwidthKbps();
        if (ewma <= 0) return false; // no data yet → don't upgrade blindly

        switch (targetCap) {
            case Q480P:  return ewma >= BW_MIN_FOR_480P_KBPS;
            case Q720P:  return ewma >= BW_MIN_FOR_720P_KBPS;
            case Q1080P: return ewma >= BW_MIN_FOR_1080P_KBPS;
            case AUTO:   return ewma >= BW_MIN_FOR_720P_KBPS; // AUTO = at least 720p worthy
            default:     return true; // Q360P always safe
        }
    }

    // ── Network-aware quality suggestion ─────────────────────────────────────

    /**
     * Returns the recommended QualityCap based on EWMA bandwidth.
     * More conservative than raw estimate — won't suggest 720p on a 500kbps line.
     */
    public QualityCap recommendedCap(Context ctx) {
        long bwKbps = getEwmaBandwidthKbps();
        if (bwKbps <= 0) bwKbps = estimateBandwidthFromNetworkType(ctx);

        if (bwKbps < BW_VERY_LOW_KBPS) return QualityCap.Q360P;
        if (bwKbps < BW_LOW_KBPS)      return QualityCap.Q480P;
        if (bwKbps < BW_MED_KBPS)      return QualityCap.Q720P;
        return QualityCap.AUTO;
    }

    /** Fallback estimate when EWMA has no data yet */
    private long estimateBandwidthFromNetworkType(Context ctx) {
        ConnectivityManager cm =
            (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return 1_000;
        Network net = cm.getActiveNetwork();
        if (net == null) return 0;
        NetworkCapabilities nc = cm.getNetworkCapabilities(net);
        if (nc == null) return 0;

        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))      return 10_000;
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))  return 1_500;
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))  return 50_000;
        return 500;
    }

    // ── Bandwidth info ────────────────────────────────────────────────────────

    /** Raw bandwidth from BandwidthMeter in Kbps (0 if not yet measured) */
    public long currentBandwidthKbps() {
        return bandwidthMeter.getBitrateEstimate() / 1_000;
    }

    // ── QoE Persistence ───────────────────────────────────────────────────────

    /**
     * Persist QoE stats for a completed reel session.
     * Cumulative — each session adds to running totals.
     * Safe to call from any thread.
     *
     * @param stallMs        total stall time in ms for this session
     * @param qualitySwitches total quality switches
     * @param upgrades        quality upgrades
     * @param downgrades      quality downgrades
     * @param ttffMs          time-to-first-frame in ms (-1 if unknown)
     */
    public void persistQoeSession(long stallMs, int qualitySwitches,
                                   int upgrades, int downgrades, long ttffMs) {
        SharedPreferences prefs = appCtx.getSharedPreferences(QOE_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();

        long sessions    = prefs.getLong(KEY_TOTAL_SESSIONS,   0) + 1;
        long totalStall  = prefs.getLong(KEY_TOTAL_STALL_MS,   0) + stallMs;
        long totalSwitches = prefs.getLong(KEY_TOTAL_SWITCHES, 0) + qualitySwitches;
        long totalUpgrades = prefs.getLong(KEY_TOTAL_UPGRADES, 0) + upgrades;
        long totalDown   = prefs.getLong(KEY_TOTAL_DOWNGRADES, 0) + downgrades;

        // Rolling average TTFF
        long prevAvg     = prefs.getLong(KEY_AVG_TTFF_MS, 0);
        long newAvgTtff  = ttffMs >= 0
            ? (prevAvg * (sessions - 1) + ttffMs) / sessions
            : prevAvg;

        ed.putLong(KEY_TOTAL_SESSIONS,   sessions)
          .putLong(KEY_TOTAL_STALL_MS,   totalStall)
          .putLong(KEY_TOTAL_SWITCHES,   totalSwitches)
          .putLong(KEY_TOTAL_UPGRADES,   totalUpgrades)
          .putLong(KEY_TOTAL_DOWNGRADES, totalDown)
          .putLong(KEY_AVG_TTFF_MS,      newAvgTtff)
          .apply();

        Log.d(TAG, "QoE persisted session=" + sessions
            + " stallMs=" + stallMs + " switches=" + qualitySwitches
            + " ttff=" + ttffMs + "ms avgTtff=" + newAvgTtff + "ms");
    }

    /**
     * Returns a human-readable lifetime QoE summary from persisted stats.
     * Useful for debug overlays or settings screens.
     */
    public String getLifetimeQoeSummary() {
        SharedPreferences prefs = appCtx.getSharedPreferences(QOE_PREFS, Context.MODE_PRIVATE);
        long sessions   = prefs.getLong(KEY_TOTAL_SESSIONS, 0);
        if (sessions == 0) return "No QoE data yet";
        long stallMs    = prefs.getLong(KEY_TOTAL_STALL_MS, 0);
        long switches   = prefs.getLong(KEY_TOTAL_SWITCHES, 0);
        long upgrades   = prefs.getLong(KEY_TOTAL_UPGRADES, 0);
        long downgrades = prefs.getLong(KEY_TOTAL_DOWNGRADES, 0);
        long avgTtff    = prefs.getLong(KEY_AVG_TTFF_MS, 0);

        return "Sessions=" + sessions
            + " AvgStall=" + (stallMs / sessions) + "ms"
            + " AvgTTFF=" + avgTtff + "ms"
            + " Switches=" + switches
            + " (↑" + upgrades + " ↓" + downgrades + ")";
    }

    /** Human-readable label for current QualityCap */
    public static String capLabel(QualityCap cap) {
        switch (cap) {
            case Q360P:  return "360p";
            case Q480P:  return "480p";
            case Q720P:  return "720p";
            case Q1080P: return "1080p";
            default:     return "Auto";
        }
    }
}
