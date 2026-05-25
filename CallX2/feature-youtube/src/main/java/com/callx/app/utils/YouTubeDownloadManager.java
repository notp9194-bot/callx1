package com.callx.app.utils;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * YouTubeDownloadManager
 *
 * Real YouTube jaisi download functionality:
 *  - Android DownloadManager use karta hai (background download, progress notification)
 *  - Download folder: Movies/CallXYouTube/<title>.mp4
 *  - Firebase me download track karta hai: youtube/downloads/{uid}/{videoId}
 *  - Duplicate download check
 *  - Offline access ke liye local path save karta hai
 *  - Download complete notification
 */
public class YouTubeDownloadManager {

    private static final String TAG              = "YT_DOWNLOAD";
    private static final String CHANNEL_ID       = "yt_download_channel";
    private static final String CHANNEL_NAME     = "YouTube Downloads";
    private static final String DOWNLOAD_FOLDER  = "CallXYouTube";

    /** Start download — returns downloadId or -1 on failure */
    public static long startDownload(Context ctx, String videoId,
                                     String videoUrl, String title) {

        if (videoUrl == null || videoUrl.trim().isEmpty()) {
            Toast.makeText(ctx, "❌ Video URL nahi mili — download nahi ho sakta", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "startDownload — videoUrl null/empty");
            return -1L;
        }

        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        // MP4 URL ensure karo (Cloudinary)
        String dlUrl = ensureMp4Url(videoUrl);

        // Safe filename
        String safeTitle = title != null && !title.trim().isEmpty()
            ? title.trim().replaceAll("[\\\\/:*?\"<>|]", "_")
            : "video_" + videoId;
        if (!safeTitle.toLowerCase().endsWith(".mp4"))
            safeTitle += ".mp4";

        // Check already downloaded
        if (isAlreadyDownloaded(ctx, safeTitle)) {
            Toast.makeText(ctx, "✅ Ye video pehle se download hai!", Toast.LENGTH_SHORT).show();
            return -1L;
        }

        // Create notification channel
        createNotificationChannel(ctx);

        // DownloadManager request
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(ctx, "❌ Download Manager available nahi hai", Toast.LENGTH_SHORT).show();
            return -1L;
        }

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(dlUrl));
        req.setTitle(title != null ? title : "CallX YouTube Video");
        req.setDescription("Downloading...");
        req.setMimeType("video/mp4");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES,
            DOWNLOAD_FOLDER + File.separator + safeTitle);
        req.addRequestHeader("User-Agent",
            "ExoPlayer/2.0 (Linux;Android " + Build.VERSION.RELEASE + ")");
        req.setAllowedNetworkTypes(
            DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
        req.setAllowedOverRoaming(false);

        long downloadId;
        try {
            downloadId = dm.enqueue(req);
        } catch (Exception e) {
            Log.e(TAG, "DownloadManager.enqueue fail: " + e.getMessage());
            Toast.makeText(ctx, "❌ Download shuru nahi hua: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return -1L;
        }

        Log.d(TAG, "Download started — downloadId=" + downloadId + " url=" + dlUrl);
        Toast.makeText(ctx, "⬇️ Download shuru ho gaya!\n\"" + title + "\"", Toast.LENGTH_SHORT).show();

        // Firebase me download track karo
        if (!uid.isEmpty()) {
            saveDownloadRecord(uid, videoId, title, dlUrl, downloadId);
        }

        // Listen for completion
        listenForCompletion(ctx, downloadId, title, videoId, safeTitle, uid);

        return downloadId;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String ensureMp4Url(String url) {
        if (url == null) return url;
        if (url.contains("cloudinary.com") && url.contains("/video/upload/")) {
            if (!url.contains("/f_mp4") && !url.contains("/f_auto")) {
                url = url.replace("/video/upload/", "/video/upload/f_mp4/");
            }
            String base = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            if (!base.toLowerCase().endsWith(".mp4")) {
                url = base + ".mp4";
            }
        }
        return url;
    }

    private static boolean isAlreadyDownloaded(Context ctx, String fileName) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES), DOWNLOAD_FOLDER);
        File f = new File(dir, fileName);
        return f.exists() && f.length() > 0;
    }

    private static void saveDownloadRecord(String uid, String videoId,
                                            String title, String url, long downloadId) {
        Map<String, Object> record = new HashMap<>();
        record.put("videoId",    videoId);
        record.put("title",      title != null ? title : "");
        record.put("url",        url);
        record.put("downloadId", downloadId);
        record.put("status",     "downloading");
        record.put("savedAt",    System.currentTimeMillis());
        YouTubeFirebaseUtils.downloadsRef(uid).child(videoId).setValue(record);
        Log.d(TAG, "Firebase download record saved for videoId=" + videoId);
    }

    private static void updateDownloadStatus(String uid, String videoId,
                                              String status, String localPath) {
        if (uid == null || uid.isEmpty() || videoId == null) return;
        Map<String, Object> update = new HashMap<>();
        update.put("status",      status);
        update.put("completedAt", System.currentTimeMillis());
        if (localPath != null) update.put("localPath", localPath);
        YouTubeFirebaseUtils.downloadsRef(uid).child(videoId).updateChildren(update);
    }

    private static void listenForCompletion(Context ctx, long downloadId,
                                             String title, String videoId,
                                             String fileName, String uid) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;

                DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) return;

                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterById(downloadId);
                Cursor cursor = dm.query(q);
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int status    = statusIdx >= 0 ? cursor.getInt(statusIdx) : -1;

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        String localPath = new File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_MOVIES),
                            DOWNLOAD_FOLDER + File.separator + fileName
                        ).getAbsolutePath();

                        Toast.makeText(ctx, "✅ Download complete!\n\"" + title + "\"\nOffline dekh sakte ho",
                            Toast.LENGTH_LONG).show();
                        Log.d(TAG, "Download complete — path=" + localPath);

                        // Firebase status update
                        if (!uid.isEmpty()) updateDownloadStatus(uid, videoId, "completed", localPath);

                        // Show completion notification
                        showCompletionNotification(ctx, title);

                    } else if (status == DownloadManager.STATUS_FAILED) {
                        int reasonIdx  = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                        int reason     = reasonIdx >= 0 ? cursor.getInt(reasonIdx) : -1;
                        Toast.makeText(ctx, "❌ Download fail hua (reason=" + reason + ")", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Download failed reason=" + reason);
                        if (!uid.isEmpty()) updateDownloadStatus(uid, videoId, "failed", null);
                    }
                    cursor.close();
                }
                try { ctx.unregisterReceiver(this); } catch (Exception ignored) {}
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
    }

    private static void createNotificationChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("YouTube video download notifications");
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static void showCompletionNotification(Context ctx, String title) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText("\"" + title + "\" download ho gaya — offline dekh sakte ho!")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true);

        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    /**
     * Check if a video is downloaded for offline viewing.
     * Returns local file path if found, null otherwise.
     */
    public static String getOfflinePath(String videoId, String title) {
        if (title == null || title.trim().isEmpty()) return null;
        String safeTitle = title.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!safeTitle.toLowerCase().endsWith(".mp4")) safeTitle += ".mp4";
        File f = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            DOWNLOAD_FOLDER + File.separator + safeTitle);
        return (f.exists() && f.length() > 0) ? f.getAbsolutePath() : null;
    }

    /** Firebase downloads node list lao */
    public static void loadDownloads(String uid,
            com.google.firebase.database.ValueEventListener listener) {
        if (uid == null || uid.isEmpty()) return;
        YouTubeFirebaseUtils.downloadsRef(uid).addListenerForSingleValueEvent(listener);
    }
}
