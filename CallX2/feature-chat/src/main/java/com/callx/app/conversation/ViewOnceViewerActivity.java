package com.callx.app.conversation;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.chat.R;

/**
 * ViewOnceViewerActivity — Full-screen lightweight viewer for view-once messages.
 *
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  SECURITY:                                                               │
 * │  • FLAG_SECURE is set in onCreate — no screenshot, no screen record.    │
 * │    ChatViewOnceController also sets it, but we double-set here for       │
 * │    defence-in-depth (e.g., direct deep-link launch edge case).          │
 * │                                                                          │
 * │  PERFORMANCE:                                                            │
 * │  • No shared element transition.                                         │
 * │  • Image loaded with Glide: no memory cache (skipMemoryCache=true),      │
 * │    disk cache evicted after load (DiskCacheStrategy.NONE on open).       │
 * │  • Video: VideoView (SurfaceView-backed) — hardware-decoded, zero copy. │
 * │  • No animation loops, no Handler ticks.                                 │
 * │  • Bitmap cleared on close via Glide.clear().                           │
 * │                                                                          │
 * │  MEMORY:                                                                 │
 * │  • Glide skipMemoryCache(true) — image not added to LRU after open.     │
 * │  • DiskCacheStrategy.NONE — not cached to disk either (view-once only). │
 * │  • VideoView.stopPlayback() + setVideoURI(null) on finish.              │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Extras expected:
 *   EXTRA_MSG_ID    — String  — message ID (for delete callback)
 *   EXTRA_TYPE      — String  — "text" | "image" | "video" | "audio" | "file"
 *   EXTRA_CONTENT   — String  — text content (for text type)
 *   EXTRA_MEDIA_URL — String  — Cloudinary/CDN URL (for image/video/audio/file)
 *   EXTRA_FILE_NAME — String  — original filename (for file type display)
 *   EXTRA_DURATION  — long    — duration ms (for audio display)
 */
public class ViewOnceViewerActivity extends AppCompatActivity {

    public static final String EXTRA_MSG_ID    = "vo_msg_id";
    public static final String EXTRA_TYPE      = "vo_type";
    public static final String EXTRA_CONTENT   = "vo_content";
    public static final String EXTRA_MEDIA_URL = "vo_media_url";
    public static final String EXTRA_FILE_NAME = "vo_file_name";
    public static final String EXTRA_DURATION  = "vo_duration";

    private android.widget.ImageView ivImage;
    private VideoView                vvVideo;
    private TextView                 tvText;
    private LinearLayout             llAudio;
    private LinearLayout             llFile;
    private ImageButton              btnPlayAudio;
    private TextView                 tvAudioDuration;
    private TextView                 tvFileName;

    private MediaPlayer  audioPlayer;
    private boolean      audioPlaying = false;
    private boolean      cleanedUp = false;
    private String       msgId;
    private String       type;
    private String       mediaUrl;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // SECURITY: block screenshot/screen-record during viewing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_view_once_viewer);

        // Parse extras
        msgId    = getIntent().getStringExtra(EXTRA_MSG_ID);
        type     = getIntent().getStringExtra(EXTRA_TYPE);
        mediaUrl = getIntent().getStringExtra(EXTRA_MEDIA_URL);
        String content  = getIntent().getStringExtra(EXTRA_CONTENT);
        String fileName = getIntent().getStringExtra(EXTRA_FILE_NAME);
        long   duration = getIntent().getLongExtra(EXTRA_DURATION, 0L);

        // Bind views
        ivImage         = findViewById(R.id.iv_view_once);
        vvVideo         = findViewById(R.id.vv_view_once);
        tvText          = findViewById(R.id.tv_view_once_text);
        llAudio         = findViewById(R.id.ll_audio_player);
        llFile          = findViewById(R.id.ll_file_info);
        btnPlayAudio    = findViewById(R.id.btn_play_audio);
        tvAudioDuration = findViewById(R.id.tv_audio_duration);
        tvFileName      = findViewById(R.id.tv_file_name);
        TextView btnClose = findViewById(R.id.btn_close_viewer);

        btnClose.setOnClickListener(v -> finishViewer());

        // Render content by type
        if (type == null) type = "text";
        switch (type) {
            case "image":  showImage();           break;
            case "video":  showVideo();           break;
            case "audio":  showAudio(duration);   break;
            case "file":   showFile(fileName);    break;
            default:       showText(content);     break;
        }
    }

    // ── Content renderers ─────────────────────────────────────────────────

    private void showImage() {
        ivImage.setVisibility(android.view.View.VISIBLE);
        if (mediaUrl == null) return;
        // MEMORY: skipMemoryCache=true + DiskCacheStrategy.NONE
        // → image never enters Glide LRU after this view-once open.
        Glide.with(this)
                .load(mediaUrl)
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(ivImage);
    }

    private void showVideo() {
        vvVideo.setVisibility(android.view.View.VISIBLE);
        if (mediaUrl == null) return;
        vvVideo.setVideoURI(Uri.parse(mediaUrl));
        vvVideo.start();
    }

    private void showText(@Nullable String content) {
        tvText.setVisibility(android.view.View.VISIBLE);
        tvText.setText(content != null ? content : "");
    }

    private void showAudio(long durationMs) {
        llAudio.setVisibility(android.view.View.VISIBLE);
        String label = durationMs > 0 ? formatDuration(durationMs) : "--:--";
        tvAudioDuration.setText(label);
        btnPlayAudio.setOnClickListener(v -> toggleAudio());
    }

    private void showFile(@Nullable String fileName) {
        llFile.setVisibility(android.view.View.VISIBLE);
        tvFileName.setText(fileName != null ? fileName : "File");
    }

    // ── Audio playback ────────────────────────────────────────────────────

    private void toggleAudio() {
        if (mediaUrl == null) return;
        if (audioPlayer == null) {
            audioPlayer = new MediaPlayer();
            try {
                audioPlayer.setDataSource(mediaUrl);
                audioPlayer.prepareAsync();
                audioPlayer.setOnPreparedListener(mp -> {
                    mp.start();
                    audioPlaying = true;
                    btnPlayAudio.setImageResource(com.callx.app.chat.R.drawable.ic_pause);
                });
                audioPlayer.setOnCompletionListener(mp -> {
                    audioPlaying = false;
                    btnPlayAudio.setImageResource(com.callx.app.chat.R.drawable.ic_play);
                });
            } catch (Exception e) {
                releaseAudio();
            }
        } else {
            if (audioPlaying) {
                audioPlayer.pause();
                audioPlaying = false;
                btnPlayAudio.setImageResource(com.callx.app.chat.R.drawable.ic_play);
            } else {
                audioPlayer.start();
                audioPlaying = true;
                btnPlayAudio.setImageResource(com.callx.app.chat.R.drawable.ic_pause);
            }
        }
    }

    private void releaseAudio() {
        if (audioPlayer != null) {
            try { audioPlayer.stop(); } catch (Exception ignored) {}
            audioPlayer.release();
            audioPlayer = null;
        }
        audioPlaying = false;
    }

    // ── Cleanup / finish ──────────────────────────────────────────────────

    private void finishViewer() {
        cleanup();
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Auto-close if user leaves (home button, recents, etc.)
        cleanup();
        finish();
    }

    private void cleanup() {
        // CRASH FIX: onStop() and onDestroy() both call cleanup(). Without this
        // guard, the second call hits Glide.with(this) on an already-destroyed
        // Activity, which throws IllegalArgumentException("You cannot start a
        // load for a destroyed activity") and crashes the app.
        if (cleanedUp) return;
        cleanedUp = true;

        // MEMORY: clear Glide image from LRU immediately — only safe while
        // the activity is still alive; skip entirely once destroyed/finishing.
        if (ivImage != null && !isDestroyed() && !isFinishing()) {
            Glide.with(this).clear(ivImage);
        }

        // MEMORY: stop video, release surface
        if (vvVideo != null) {
            vvVideo.stopPlayback();
            vvVideo.setVideoURI(null);
        }

        // MEMORY: release audio player
        releaseAudio();

        // SECURITY: clear FLAG_SECURE
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } catch (Exception ignored) {}

        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanup();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String formatDuration(long ms) {
        long secs  = ms / 1000;
        long mins  = secs / 60;
        secs = secs % 60;
        return String.format(java.util.Locale.US, "%d:%02d", mins, secs);
    }
}
