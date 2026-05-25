package com.callx.app.utils;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import com.callx.app.models.YouTubeVideo;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * YouTubeDownloadManager — In-App Offline Downloads
 *
 * Real YouTube jaisi behaviour:
 *  - Internal storage me save (gallery me nahi dikhta)
 *  - App ke Downloads folder: getFilesDir()/yt_downloads/<videoId>.mp4
 *  - Firebase me metadata save: youtube/downloads/{uid}/{videoId}
 *  - Progress notification (DownloadManager ki jagah manual OkHttp)
 *  - Offline player ke liye local path return karta hai
 *  - Download cancel / delete support
 */
public class YouTubeDownloadManager {

    private static final String TAG          = "YT_DOWNLOAD";
    private static final String CHANNEL_ID   = "yt_dl_channel";
    private static final String DL_DIR       = "yt_downloads";

    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final Handler mainHandler     = new Handler(Looper.getMainLooper());

    // ── Public API ────────────────────────────────────────────────────────────

    /** Download start karo. Callback on main thread. */
    public static void startDownload(Context ctx, YouTubeVideo video,
                                     DownloadCallback callback) {
        if (video == null || video.videoUrl == null || video.videoUrl.trim().isEmpty()) {
            toast(ctx, "❌ Video URL nahi mili");
            if (callback != null) callback.onError("No URL");
            return;
        }

        String uid = currentUid();
        String dlUrl = ensureMp4(video.videoUrl.trim());
        File outFile = getLocalFile(ctx, video.videoId);

        // Already downloaded?
        if (outFile.exists() && outFile.length() > 1024) {
            toast(ctx, "✅ Ye video pehle se download hai!");
            if (callback != null) callback.onAlreadyDownloaded(outFile.getAbsolutePath());
            return;
        }

        createNotifChannel(ctx);
        toast(ctx, "⬇️ Download shuru...\n\"" + video.title + "\"");
        if (callback != null) callback.onStarted();

        // Firebase: status = downloading
        if (!uid.isEmpty()) saveRecord(uid, video, "downloading", null);

        int notifId = (int)(System.currentTimeMillis() % 100000);

        executor.submit(() -> {
            try {
                // Create dir
                File dir = outFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();

                // Open connection
                HttpURLConnection conn = (HttpURLConnection) new URL(dlUrl).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setRequestProperty("User-Agent",
                    "ExoPlayer/2.0 (Linux;Android " + Build.VERSION.RELEASE + ")");
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode / 100 != 2) {
                    throw new Exception("HTTP " + responseCode);
                }

                long total = conn.getContentLengthLong();
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(outFile);

                byte[] buf = new byte[8192];
                long downloaded = 0;
                int read;
                int lastPct = 0;

                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        int pct = (int)(downloaded * 100 / total);
                        if (pct - lastPct >= 10) {
                            lastPct = pct;
                            int finalPct = pct;
                            showProgress(ctx, notifId, video.title, pct);
                            mainHandler.post(() -> {
                                if (callback != null) callback.onProgress(finalPct);
                            });
                        }
                    }
                }
                out.flush();
                out.close();
                in.close();
                conn.disconnect();

                String localPath = outFile.getAbsolutePath();
                Log.d(TAG, "Download complete: " + localPath);

                // Firebase update
                if (!uid.isEmpty()) saveRecord(uid, video, "completed", localPath);

                cancelNotif(ctx, notifId);
                showCompleteNotif(ctx, video.title);

                mainHandler.post(() -> {
                    toast(ctx, "✅ Download complete!\n\"" + video.title + "\"\nOffline dekh sakte ho 📱");
                    if (callback != null) callback.onCompleted(localPath);
                });

            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + e.getMessage());
                outFile.delete(); // cleanup partial file
                if (!uid.isEmpty()) saveRecord(uid, video, "failed", null);
                cancelNotif(ctx, notifId);
                mainHandler.post(() -> {
                    toast(ctx, "❌ Download fail: " + e.getMessage());
                    if (callback != null) callback.onError(e.getMessage());
                });
            }
        });
    }

    /** Local file path return karo agar download hua hai */
    public static String getOfflinePath(Context ctx, String videoId) {
        File f = getLocalFile(ctx, videoId);
        return (f.exists() && f.length() > 1024) ? f.getAbsolutePath() : null;
    }

    /** Check agar video downloaded hai */
    public static boolean isDownloaded(Context ctx, String videoId) {
        return getOfflinePath(ctx, videoId) != null;
    }

    /** Download delete karo */
    public static void deleteDownload(Context ctx, String videoId) {
        File f = getLocalFile(ctx, videoId);
        if (f.exists()) f.delete();
        String uid = currentUid();
        if (!uid.isEmpty())
            YouTubeFirebaseUtils.downloadsRef(uid).child(videoId).removeValue();
        toast(ctx, "🗑️ Download delete ho gaya");
    }

    /** Firebase se downloaded videos list fetch karo */
    public static void loadDownloadedVideos(ValueEventListener listener) {
        String uid = currentUid();
        if (uid.isEmpty()) return;
        YouTubeFirebaseUtils.downloadsRef(uid)
            .orderByChild("savedAt")
            .addListenerForSingleValueEvent(listener);
    }

    /** Get all downloaded video IDs (local files jo exist karti hain) */
    public static java.util.List<String> getLocalDownloadedIds(Context ctx) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        File dir = new File(ctx.getFilesDir(), DL_DIR);
        if (!dir.exists()) return ids;
        File[] files = dir.listFiles();
        if (files == null) return ids;
        for (File f : files) {
            if (f.getName().endsWith(".mp4") && f.length() > 1024) {
                ids.add(f.getName().replace(".mp4", ""));
            }
        }
        return ids;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    public static File getLocalFile(Context ctx, String videoId) {
        File dir = new File(ctx.getFilesDir(), DL_DIR);
        return new File(dir, videoId + ".mp4");
    }

    private static String ensureMp4(String url) {
        if (url.contains("cloudinary.com") && url.contains("/video/upload/")) {
            if (!url.contains("/f_mp4") && !url.contains("/f_auto"))
                url = url.replace("/video/upload/", "/video/upload/f_mp4/");
            String base = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            if (!base.toLowerCase().endsWith(".mp4")) url = base + ".mp4";
        }
        return url;
    }

    private static String currentUid() {
        return FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
    }

    private static void saveRecord(String uid, YouTubeVideo video,
                                    String status, String localPath) {
        Map<String, Object> r = new HashMap<>();
        r.put("videoId",      video.videoId);
        r.put("title",        video.title != null ? video.title : "");
        r.put("thumbnailUrl", video.thumbnailUrl != null ? video.thumbnailUrl : "");
        r.put("channelName",  video.uploaderName != null ? video.uploaderName : "");
        r.put("duration",     video.duration);
        r.put("videoUrl",     video.videoUrl != null ? video.videoUrl : "");
        r.put("status",       status);
        r.put("savedAt",      System.currentTimeMillis());
        if (localPath != null) r.put("localPath", localPath);
        YouTubeFirebaseUtils.downloadsRef(uid).child(video.videoId).setValue(r);
    }

    private static void createNotifChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "YouTube Downloads", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static void showProgress(Context ctx, int notifId, String title, int pct) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading...")
            .setContentText("\"" + title + "\"  " + pct + "%")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);
        nm.notify(notifId, b.build());
    }

    private static void showCompleteNotif(Context ctx, String title) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete ✅")
            .setContentText("\"" + title + "\" — offline dekh sakte ho!")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        nm.notify((int)(System.currentTimeMillis() % 100000), b.build());
    }

    private static void cancelNotif(Context ctx, int notifId) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifId);
    }

    private static void toast(Context ctx, String msg) {
        mainHandler.post(() ->
            Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    // ── Callback interface ────────────────────────────────────────────────────

    public interface DownloadCallback {
        void onStarted();
        void onProgress(int percent);
        void onCompleted(String localPath);
        void onAlreadyDownloaded(String localPath);
        void onError(String error);
    }
}
