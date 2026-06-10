package com.callx.app.workers;

import android.content.Context;
import android.media.*;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.*;

import com.callx.app.social.collab.CollabVideoCompositor;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.ServerValue;
import android.net.Uri;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * CollabDuetCompositorWorker — Mixes two video tracks side-by-side into one reel.
 *
 * ✅ Runs in background via WorkManager (survives app death)
 *
 * Pipeline:
 *  1. Download host video  → /tmp/collab_{sessionId}_host.mp4
 *  2. Download partner video → /tmp/collab_{sessionId}_partner.mp4
 *  3. Transcode both to same codec/resolution using MediaCodec
 *  4. Composite side-by-side using MediaMuxer
 *  5. Upload composited file to Firebase Storage
 *  6. Write new reel entry to Firebase RTDB (reels/{newReelId})
 *  7. Update collabDuetSessions/{sessionId}/compositedReelId
 *
 * Note: Full hardware-accelerated compositing with canvas overlay for a true
 * side-by-side layout. Uses Surface-based MediaCodec pipeline for efficiency.
 */
public class CollabDuetCompositorWorker extends Worker {

    private static final String TAG = "CollabDuetCompositor";

    public static final String KEY_SESSION_ID     = "session_id";
    public static final String KEY_HOST_VIDEO_URL = "host_video_url";
    public static final String KEY_PART_VIDEO_URL = "partner_video_url";
    public static final String KEY_REEL_ID        = "original_reel_id";
    public static final String KEY_HOST_UID       = "host_uid";
    public static final String KEY_HOST_NAME      = "host_name";
    public static final String KEY_PARTNER_UID    = "partner_uid";
    public static final String KEY_PARTNER_NAME   = "partner_name";

    private static final int TARGET_WIDTH  = 720;
    private static final int TARGET_HEIGHT = 1280;
    private static final int OUTPUT_BITRATE = 4_000_000; // 4 Mbps

    public CollabDuetCompositorWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull @Override
    public Result doWork() {
        String sessionId    = getInputData().getString(KEY_SESSION_ID);
        String hostUrl      = getInputData().getString(KEY_HOST_VIDEO_URL);
        String partnerUrl   = getInputData().getString(KEY_PART_VIDEO_URL);
        String originalReelId = getInputData().getString(KEY_REEL_ID);
        String hostUid      = getInputData().getString(KEY_HOST_UID);
        String hostName     = getInputData().getString(KEY_HOST_NAME);
        String partnerUid   = getInputData().getString(KEY_PARTNER_UID);
        String partnerName  = getInputData().getString(KEY_PARTNER_NAME);

        if (sessionId == null || hostUrl == null || partnerUrl == null) {
            return Result.failure();
        }

        Context ctx = getApplicationContext();
        File   hostFile    = new File(ctx.getCacheDir(), "collab_" + sessionId + "_host.mp4");
        File   partnerFile = new File(ctx.getCacheDir(), "collab_" + sessionId + "_partner.mp4");
        File   outputFile  = new File(ctx.getCacheDir(), "collab_" + sessionId + "_composed.mp4");

        try {
            // Step 1 & 2: Download both videos
            Log.d(TAG, "Downloading host video…");
            downloadFile(hostUrl, hostFile);
            Log.d(TAG, "Downloading partner video…");
            downloadFile(partnerUrl, partnerFile);

            // Step 3 & 4: Composite side-by-side
            Log.d(TAG, "Compositing…");
            compositeSideBySide(hostFile, partnerFile, outputFile);

            // Step 5: Upload to Firebase Storage
            Log.d(TAG, "Uploading composited video…");
            String storagePath = "collab_duets_composed/" + sessionId + "/composed.mp4";
            String downloadUrl = uploadToStorage(outputFile, storagePath);

            // Step 6: Write new reel to Firebase RTDB
            String newReelId = writeNewReel(downloadUrl, originalReelId,
                hostUid, hostName, partnerUid, partnerName);

            // Step 7: Update session
            if (sessionId != null) {
                FirebaseUtils.getCollabDuetSessionsRef()
                    .child(sessionId).child("compositedReelId").setValue(newReelId);
            }

            // Cleanup temp files
            hostFile.delete(); partnerFile.delete(); outputFile.delete();

            Log.d(TAG, "Collab duet composited: " + newReelId);
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Compositor failed", e);
            hostFile.delete(); partnerFile.delete(); outputFile.delete();
            return Result.retry();
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private void downloadFile(String url, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.connect();
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32_768];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { conn.disconnect(); }
    }

    // ── Compositing ───────────────────────────────────────────────────────────

    /**
     * Composite two portrait videos side-by-side into one landscape video.
     *
     * Output: TARGET_WIDTH x TARGET_HEIGHT (portrait), split vertically:
     *   Left  half (0 .. W/2)    = host video (scaled to W/2 x H)
     *   Right half (W/2 .. W)    = partner video (scaled to W/2 x H)
     *
     * Uses MediaExtractor + MediaMuxer for fast re-mux without full transcode
     * when both inputs share the same codec. For production, a full
     * MediaCodec pipeline (encode + decode with Canvas compositing) is used
     * when codecs differ.
     */
    private void compositeSideBySide(File host, File partner, File output) throws IOException {
        // For robust compositing, use Android's built-in MediaCodec pipeline.
        // Here we implement a simplified version using MediaExtractor to extract
        // audio from host and use it as the final audio track.
        // The actual frame-level compositing uses android.graphics.Canvas + SurfaceView.

        // Full implementation uses:
        // 1. MediaExtractor(host)  → decode video frames → Canvas.drawBitmap (left half)
        // 2. MediaExtractor(partner) → decode video frames → Canvas.drawBitmap (right half)
        // 3. Combined Canvas → encode via MediaCodec (AVC) → MediaMuxer
        // 4. MediaExtractor(host) → copy audio track → MediaMuxer

        CollabVideoCompositor compositor = new CollabVideoCompositor(
            host, partner, output, TARGET_WIDTH, TARGET_HEIGHT, OUTPUT_BITRATE);
        compositor.compose();
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    private String uploadToStorage(File file, String path) throws Exception {
        com.google.firebase.storage.StorageReference ref =
            com.google.firebase.storage.FirebaseStorage.getInstance().getReference(path);
        // Synchronous upload using Tasks.await on background thread (WorkManager thread)
        com.google.firebase.storage.UploadTask upload = ref.putFile(Uri.fromFile(file));
        Tasks.await(upload);
        Uri downloadUri = Tasks.await(ref.getDownloadUrl());
        return downloadUri.toString();
    }

    // ── Write reel ────────────────────────────────────────────────────────────

    private String writeNewReel(String videoUrl, String originalReelId,
                                String hostUid, String hostName,
                                String partnerUid, String partnerName) {
        com.google.firebase.database.DatabaseReference reelsRef = FirebaseUtils.getReelsRef();
        com.google.firebase.database.DatabaseReference newRef   = reelsRef.push();
        String newReelId = newRef.getKey();

        Map<String, Object> reel = new HashMap<>();
        reel.put("reelId",       newReelId);
        reel.put("videoUrl",     videoUrl);
        reel.put("uid",          hostUid);
        reel.put("ownerName",    hostName);
        reel.put("caption",      "🤝 Collab Duet with @" + partnerName);
        reel.put("isCollabDuet", true);
        reel.put("collabPartnerUid",  partnerUid);
        reel.put("collabPartnerName", partnerName);
        reel.put("originalReelId",    originalReelId);
        reel.put("createdAt",    ServerValue.TIMESTAMP);
        reel.put("likesCount",   0);
        reel.put("viewsCount",   0);
        reel.put("commentsCount",0);

        newRef.setValue(reel);

        // Index under both users' reels
        FirebaseUtils.getReelsByUserRef(hostUid).child(newReelId).setValue(true);
        FirebaseUtils.getReelsByUserRef(partnerUid).child(newReelId).setValue(true);

        return newReelId;
    }
}
