package com.callx.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.media.session.MediaSessionCompat;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.bumptech.glide.Glide;
import com.callx.app.home.YouTubeActivity;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.youtube.R;

/**
 * YouTubeBackgroundPlayService — Foreground service for background audio playback.
 *
 * Usage:
 *   Intent intent = new Intent(ctx, YouTubeBackgroundPlayService.class)
 *       .putExtra("video_url", url)
 *       .putExtra("video_title", title)
 *       .putExtra("video_id", id)
 *       .putExtra("channel_name", channel);
 *   ctx.startForegroundService(intent);
 *
 * Stop:
 *   ctx.stopService(new Intent(ctx, YouTubeBackgroundPlayService.class));
 */
public class YouTubeBackgroundPlayService extends Service {

    private static final String TAG        = "YT_BG_PLAY";
    private static final String CHANNEL_ID = "yt_bg_play_channel";
    private static final int    NOTIF_ID   = 8871;

    public static final String ACTION_PLAY   = "yt.bg.PLAY";
    public static final String ACTION_PAUSE  = "yt.bg.PAUSE";
    public static final String ACTION_STOP   = "yt.bg.STOP";
    public static final String ACTION_NEXT   = "yt.bg.NEXT";

    private ExoPlayer          player;
    private MediaSessionCompat mediaSession;
    private String             currentTitle   = "";
    private String             currentChannel = "";
    private String             currentThumbUrl = "";

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public YouTubeBackgroundPlayService getService() {
            return YouTubeBackgroundPlayService.this;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mediaSession = new MediaSessionCompat(this, "YTBackgroundPlay");
        mediaSession.setActive(true);

        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                updateNotification(player.isPlaying());
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updateNotification(isPlaying);
            }
        });

        // Start as foreground immediately
        startForeground(NOTIF_ID, buildNotification(false));
        Log.d(TAG, "BackgroundPlayService created");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();

        if (ACTION_PAUSE.equals(action)) {
            if (player.isPlaying()) player.pause();
            updateNotification(false);
            return START_STICKY;
        }

        if (ACTION_PLAY.equals(action)) {
            if (!player.isPlaying()) player.play();
            updateNotification(true);
            return START_STICKY;
        }

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // New video to play
        String url     = intent.getStringExtra("video_url");
        String title   = intent.getStringExtra("video_title");
        String channel = intent.getStringExtra("channel_name");
        String thumb   = intent.getStringExtra("thumb_url");

        if (url == null || url.isEmpty()) { stopSelf(); return START_NOT_STICKY; }

        currentTitle    = title != null ? title : "YouTube Video";
        currentChannel  = channel != null ? channel : "";
        currentThumbUrl = thumb != null ? thumb : "";

        player.stop();
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();

        Log.d(TAG, "Playing: " + currentTitle + " | URL: " + url);
        updateNotification(true);

        return START_STICKY;
    }

    private void updateNotification(boolean isPlaying) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(isPlaying));
    }

    private Notification buildNotification(boolean isPlaying) {
        // Intent to open app
        Intent openIntent = new Intent(this, YouTubeActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Play/Pause action
        Intent toggleIntent = new Intent(this, YouTubeBackgroundPlayService.class)
            .setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent togglePi = PendingIntent.getService(this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Stop action
        Intent stopIntent = new Intent(this, YouTubeBackgroundPlayService.class)
            .setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle.isEmpty() ? "YouTube" : currentTitle)
            .setContentText(currentChannel.isEmpty() ? "Playing in background" : currentChannel)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying ? "Pause" : "Play",
                togglePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1));

        return builder.build();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.stop();
            player.release();
        }
        if (mediaSession != null) mediaSession.release();
        Log.d(TAG, "BackgroundPlayService destroyed");
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    /** Check if service is currently running */
    public static boolean isRunning(Context ctx) {
        android.app.ActivityManager am =
            (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo si : am.getRunningServices(50)) {
            if (YouTubeBackgroundPlayService.class.getName().equals(si.service.getClassName()))
                return true;
        }
        return false;
    }

    /** Helper: Start background play from any context */
    public static void start(Context ctx, YouTubeVideo video) {
        if (video == null || video.videoUrl == null) return;
        Intent intent = new Intent(ctx, YouTubeBackgroundPlayService.class)
            .putExtra("video_url",    video.videoUrl)
            .putExtra("video_title",  video.title)
            .putExtra("channel_name", video.uploaderName)
            .putExtra("video_id",     video.videoId)
            .putExtra("thumb_url",    video.thumbnailUrl);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    /** Helper: Stop background play */
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, YouTubeBackgroundPlayService.class));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "YouTube Background Play",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Plays YouTube videos in the background");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
