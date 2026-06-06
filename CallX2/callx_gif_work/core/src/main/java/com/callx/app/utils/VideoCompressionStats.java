package com.callx.app.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * VideoCompressionStats — Per-user + per-chat compression analytics.
 *
 * Features:
 *  ✅ Local stats (SharedPrefs): total saved, count, average ratio
 *  ✅ Codec usage breakdown (H.264 vs HEVC vs AV1)
 *  ✅ Lifetime bandwidth saved estimate
 *  ✅ Firebase sync (optional) for cross-device stats
 *  ✅ Best compression record tracking
 *  ✅ Human-readable summary string
 *
 * Usage:
 *   VideoCompressionStats stats = new VideoCompressionStats(ctx);
 *   stats.record(result); // after each compress
 *   String summary = stats.getSummary();
 */
public class VideoCompressionStats {

    private static final String TAG = "VideoStats";

    private final VideoQualityPreferences prefs;
    private final Context ctx;

    // Additional detailed stats keys
    private static final String KEY_CODEC_AVC  = "codec_avc_count";
    private static final String KEY_CODEC_HEVC = "codec_hevc_count";
    private static final String KEY_CODEC_AV1  = "codec_av1_count";
    private static final String KEY_BEST_RATIO = "best_savings_pct";
    private static final String KEY_TOTAL_ORIG = "total_original_bytes";
    private static final String KEY_TOTAL_COMP = "total_compressed_bytes";
    private static final String PREFS_NAME     = "callx_video_quality";

    public VideoCompressionStats(Context ctx) {
        this.ctx   = ctx.getApplicationContext();
        this.prefs = new VideoQualityPreferences(ctx);
    }

    /**
     * Record a compression result — updates all local stats.
     */
    public void record(VideoCompressor.Result result) {
        if (result == null) return;

        prefs.recordCompression(result.originalBytes, result.compressedBytes);

        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME,
            Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = sp.edit();

        // Codec breakdown
        if (VideoCompressor.MIME_AV1.equals(result.codecUsed))
            ed.putInt(KEY_CODEC_AV1,  sp.getInt(KEY_CODEC_AV1, 0) + 1);
        else if (VideoCompressor.MIME_HEVC.equals(result.codecUsed))
            ed.putInt(KEY_CODEC_HEVC, sp.getInt(KEY_CODEC_HEVC, 0) + 1);
        else
            ed.putInt(KEY_CODEC_AVC,  sp.getInt(KEY_CODEC_AVC, 0) + 1);

        // Best savings record
        float currentBest = sp.getFloat(KEY_BEST_RATIO, 0f);
        if (result.savingsPercent() > currentBest)
            ed.putFloat(KEY_BEST_RATIO, result.savingsPercent());

        // Total bytes
        long prevOrig = sp.getLong(KEY_TOTAL_ORIG, 0);
        long prevComp = sp.getLong(KEY_TOTAL_COMP, 0);
        ed.putLong(KEY_TOTAL_ORIG, prevOrig + result.originalBytes);
        ed.putLong(KEY_TOTAL_COMP, prevComp + result.compressedBytes);

        ed.apply();
        Log.d(TAG, "Recorded: " + result.compressionSummary());
    }

    public int getTotalCompressed() {
        return prefs.getTotalVideosCompressed();
    }

    public long getTotalBytesSaved() {
        return prefs.getTotalBytesSaved();
    }

    /** Average savings ratio across all compressed videos (0.0 – 1.0). */
    public float getAverageSavingsRatio() {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME,
            Context.MODE_PRIVATE);
        long orig = sp.getLong(KEY_TOTAL_ORIG, 0);
        long comp = sp.getLong(KEY_TOTAL_COMP, 0);
        if (orig == 0) return 0;
        return Math.max(0, 1f - (float) comp / orig);
    }

    /** Best single-video savings percentage ever achieved. */
    public float getBestSavingsPercent() {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_BEST_RATIO, 0f);
    }

    public CodecBreakdown getCodecBreakdown() {
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME,
            Context.MODE_PRIVATE);
        CodecBreakdown b = new CodecBreakdown();
        b.avcCount  = sp.getInt(KEY_CODEC_AVC,  0);
        b.hevcCount = sp.getInt(KEY_CODEC_HEVC, 0);
        b.av1Count  = sp.getInt(KEY_CODEC_AV1,  0);
        return b;
    }

    public static class CodecBreakdown {
        public int avcCount, hevcCount, av1Count;
        public int total() { return avcCount + hevcCount + av1Count; }
        @Override public String toString() {
            int t = Math.max(total(), 1);
            return String.format("H.264: %d%% | HEVC: %d%% | AV1: %d%%",
                avcCount * 100 / t, hevcCount * 100 / t, av1Count * 100 / t);
        }
    }

    /**
     * Human-readable multi-line summary for UI display.
     */
    public String getSummary() {
        long saved = getTotalBytesSaved();
        int  count = getTotalCompressed();
        float avgRatio = getAverageSavingsRatio() * 100f;
        float best     = getBestSavingsPercent();
        CodecBreakdown codecs = getCodecBreakdown();

        StringBuilder sb = new StringBuilder();
        sb.append("📦 ").append(count).append(" videos compressed\n");
        sb.append("💾 ").append(VideoQualityPreferences.formatBytes(saved)).append(" total saved\n");
        if (count > 0) {
            sb.append("📊 Avg savings: ").append(String.format("%.0f%%", avgRatio)).append("\n");
            sb.append("🏆 Best: ").append(String.format("%.0f%%", best)).append("\n");
            sb.append("🔧 Codecs: ").append(codecs.toString());
        }
        return sb.toString();
    }

    /**
     * Short one-liner for toast display after compression.
     * e.g. "2.1 MB → 0.7 MB saved 67% (H.264)"
     */
    public static String formatResult(VideoCompressor.Result r) {
        if (r == null) return "";
        return String.format("%.1fMB → %.1fMB  saved %.0f%%",
            r.originalBytes   / 1_000_000f,
            r.compressedBytes / 1_000_000f,
            r.savingsPercent());
    }

    /**
     * Optional: sync lifetime stats to Firebase for cross-device totals.
     * Call only if user is signed in.
     */
    public void syncToFirebase(String uid) {
        if (uid == null || uid.isEmpty()) return;
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("totalSaved",  getTotalBytesSaved());
            data.put("totalCount",  getTotalCompressed());
            data.put("avgSavings",  getAverageSavingsRatio());
            data.put("bestSavings", getBestSavingsPercent());
            FirebaseDatabase.getInstance()
                .getReference("videoStats/" + uid)
                .updateChildren(data);
        } catch (Exception e) {
            Log.w(TAG, "Firebase sync failed: " + e.getMessage());
        }
    }

    public void reset() {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
