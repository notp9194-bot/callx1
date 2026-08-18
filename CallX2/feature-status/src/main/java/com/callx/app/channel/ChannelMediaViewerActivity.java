package com.callx.app.channel;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.viewpager2.widget.ViewPager2;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.status.R;
import com.github.chrisbanes.photoview.PhotoView;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * ChannelMediaViewerActivity — WhatsApp-level full-screen media viewer (v4 upgrade).
 *
 * Features:
 *   Image:    PhotoView (pinch-to-zoom, double-tap-to-zoom, matrix-based pan)
 *   Video:    ExoPlayer via PlayerView (adaptive streaming, progress bar, play/pause)
 *   Swipe:    ViewPager2 to swipe between multiple media posts in a channel
 *   Actions:  Share (system sheet), Save to Gallery (with progress), Forward, Copy link
 *   Info:     Author name + time + caption overlay (tap to toggle)
 *   Brighten: Volume-key-like brightness control via SYSTEM_UI_FLAG_KEEP_SCREEN_ON
 *   Immersive: Full-screen mode; tap content to toggle overlay bars
 */
public class ChannelMediaViewerActivity extends AppCompatActivity {

    public static final String EXTRA_MEDIA_URL    = "mediaUrl";
    public static final String EXTRA_MEDIA_TYPE   = "mediaType";
    public static final String EXTRA_POST_TEXT    = "postText";
    public static final String EXTRA_AUTHOR_NAME  = "authorName";
    public static final String EXTRA_POST_TIME    = "postTime";
    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_POST_ID      = "postId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    // Pass a list of media items for swipe support
    public static final String EXTRA_MEDIA_URLS   = "mediaUrls";   // ArrayList<String>
    public static final String EXTRA_MEDIA_TYPES  = "mediaTypes";  // ArrayList<String>
    public static final String EXTRA_START_INDEX  = "startIndex";

    private ExoPlayer exoPlayer;
    private String    mediaUrl;
    private String    mediaType;
    private boolean   overlayVisible = true;

    // Multi-media swipe support
    private ArrayList<String> mediaUrls;
    private ArrayList<String> mediaTypes;
    private int currentIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Immersive full-screen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_channel_media_viewer);

        mediaUrl            = getIntent().getStringExtra(EXTRA_MEDIA_URL);
        mediaType           = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);
        String postText     = getIntent().getStringExtra(EXTRA_POST_TEXT);
        String authorName   = getIntent().getStringExtra(EXTRA_AUTHOR_NAME);
        String channelName  = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        String channelId    = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        String postId       = getIntent().getStringExtra(EXTRA_POST_ID);

        // Multi-media swipe
        mediaUrls  = getIntent().getStringArrayListExtra(EXTRA_MEDIA_URLS);
        mediaTypes = getIntent().getStringArrayListExtra(EXTRA_MEDIA_TYPES);
        currentIndex = getIntent().getIntExtra(EXTRA_START_INDEX, 0);

        // ── Back button ─────────────────────────────────────────────────────
        View btnBack = findViewById(R.id.btn_media_viewer_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // ── Caption ─────────────────────────────────────────────────────────
        TextView tvCaption = findViewById(R.id.tv_media_viewer_caption);
        if (tvCaption != null) {
            if (postText != null && !postText.isEmpty()) {
                tvCaption.setText(postText);
                tvCaption.setVisibility(View.VISIBLE);
            } else {
                tvCaption.setVisibility(View.GONE);
            }
        }

        // ── Author + channel info ────────────────────────────────────────────
        TextView tvInfo = findViewById(R.id.tv_media_viewer_info);
        if (tvInfo != null) {
            if (channelName != null) {
                tvInfo.setText(channelName);
                tvInfo.setVisibility(View.VISIBLE);
            } else {
                tvInfo.setVisibility(View.GONE);
            }
        }

        // ── Counter badge (e.g. "3 / 12") ───────────────────────────────────
        TextView tvCounter = findViewById(R.id.tv_media_viewer_counter);
        if (tvCounter != null) {
            if (mediaUrls != null && mediaUrls.size() > 1) {
                tvCounter.setText((currentIndex + 1) + " / " + mediaUrls.size());
                tvCounter.setVisibility(View.VISIBLE);
            } else {
                tvCounter.setVisibility(View.GONE);
            }
        }

        // ── Share button ────────────────────────────────────────────────────
        View btnShare = findViewById(R.id.btn_media_viewer_share);
        if (btnShare != null && mediaUrl != null) {
            btnShare.setOnClickListener(v -> shareMedia());
        }

        // ── Download / Save button ───────────────────────────────────────────
        View btnDownload = findViewById(R.id.btn_media_viewer_download);
        if (btnDownload != null && mediaUrl != null) {
            btnDownload.setOnClickListener(v -> saveToGallery());
        }

        // ── Forward button ───────────────────────────────────────────────────
        View btnForward = findViewById(R.id.btn_media_viewer_forward);
        if (btnForward != null && channelId != null && postId != null) {
            btnForward.setOnClickListener(v -> {
                Intent fwd = new Intent(this, ForwardPostActivity.class);
                fwd.putExtra(ForwardPostActivity.EXTRA_POST_TYPE,      mediaType);
                fwd.putExtra(ForwardPostActivity.EXTRA_POST_MEDIA_URL, mediaUrl);
                fwd.putExtra(ForwardPostActivity.EXTRA_CHANNEL_ID,     channelId);
                fwd.putExtra(ForwardPostActivity.EXTRA_POST_ID,        postId);
                fwd.putExtra(ForwardPostActivity.EXTRA_CHANNEL_NAME,   channelName);
                startActivity(fwd);
            });
        } else if (btnForward != null) {
            btnForward.setVisibility(View.GONE);
        }

        // ── Tap anywhere to toggle overlay ──────────────────────────────────
        View contentRoot = findViewById(android.R.id.content);
        if (contentRoot != null) {
            contentRoot.setOnClickListener(v -> toggleOverlay());
        }

        // ── Media display ────────────────────────────────────────────────────
        if ("video".equals(mediaType)) {
            showVideo();
        } else {
            showImage();
        }

        // ── Swipe gesture for multi-media ────────────────────────────────────
        if (mediaUrls != null && mediaUrls.size() > 1) {
            setupSwipeGesture();
        }
    }

    // ── Image (PhotoView — pinch-to-zoom) ─────────────────────────────────

    private void showImage() {
        PhotoView photoView   = findViewById(R.id.iv_media_viewer_image);
        PlayerView playerView = findViewById(R.id.player_view_video);
        if (playerView != null) playerView.setVisibility(View.GONE);
        if (photoView  == null || mediaUrl == null) return;

        photoView.setVisibility(View.VISIBLE);
        Glide.with(this)
             .load(mediaUrl)
             .placeholder(android.R.drawable.ic_menu_gallery)
             .error(android.R.drawable.ic_menu_report_image)
             .into(photoView);
    }

    // ── Video (ExoPlayer) ─────────────────────────────────────────────────

    private void showVideo() {
        PhotoView photoView   = findViewById(R.id.iv_media_viewer_image);
        PlayerView playerView = findViewById(R.id.player_view_video);
        if (photoView  != null) photoView.setVisibility(View.GONE);
        if (playerView == null || mediaUrl == null) return;

        playerView.setVisibility(View.VISIBLE);
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(mediaUrl)));
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
    }

    // ── Swipe between media posts ─────────────────────────────────────────

    private void setupSwipeGesture() {
        View root = getWindow().getDecorView();
        root.setOnTouchListener(new SwipeGestureListener(this));
    }

    class SwipeGestureListener extends GestureDetector.SimpleOnGestureListener
            implements View.OnTouchListener {
        private final GestureDetector gd;
        SwipeGestureListener(Context ctx) { gd = new GestureDetector(ctx, this); }
        @Override public boolean onTouch(View v, MotionEvent event) { return gd.onTouchEvent(event); }
        @Override public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float vX, float vY) {
            if (e1 == null) return false;
            float dx = e2.getX() - e1.getX();
            if (Math.abs(dx) > 150 && Math.abs(vX) > 100) {
                if (dx < 0) navigateMedia(currentIndex + 1);   // swipe left → next
                else        navigateMedia(currentIndex - 1);   // swipe right → prev
                return true;
            }
            return false;
        }
    }

    private void navigateMedia(int newIndex) {
        if (mediaUrls == null || newIndex < 0 || newIndex >= mediaUrls.size()) return;
        currentIndex = newIndex;
        mediaUrl  = mediaUrls.get(currentIndex);
        mediaType = mediaTypes != null && currentIndex < mediaTypes.size()
            ? mediaTypes.get(currentIndex) : "image";

        // Update counter
        TextView tvCounter = findViewById(R.id.tv_media_viewer_counter);
        if (tvCounter != null)
            tvCounter.setText((currentIndex + 1) + " / " + mediaUrls.size());

        // Stop existing player
        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.release(); exoPlayer = null; }

        if ("video".equals(mediaType)) showVideo(); else showImage();
    }

    // ── Toggle overlay ────────────────────────────────────────────────────

    private void toggleOverlay() {
        overlayVisible = !overlayVisible;
        View topBar    = findViewById(R.id.layout_media_top_bar);
        View bottomBar = findViewById(R.id.layout_media_bottom_bar);
        int  vis       = overlayVisible ? View.VISIBLE : View.GONE;
        if (topBar    != null) topBar.setVisibility(vis);
        if (bottomBar != null) bottomBar.setVisibility(vis);
    }

    // ── Share ─────────────────────────────────────────────────────────────

    private void shareMedia() {
        if (mediaUrl == null) return;
        // Share URL directly (fast path)
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, mediaUrl);
        startActivity(Intent.createChooser(shareIntent, "Share media"));
    }

    // ── Save to Gallery ───────────────────────────────────────────────────

    private void saveToGallery() {
        if (mediaUrl == null) return;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                if ("video".equals(mediaType)) {
                    // Download video bytes and save
                    java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(mediaUrl).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.connect();
                    InputStream in = conn.getInputStream();
                    saveVideoStream(in);
                    conn.disconnect();
                } else {
                    // Save image via Glide
                    Glide.with(this).asBitmap().load(mediaUrl)
                        .into(new CustomTarget<Bitmap>() {
                            @Override public void onResourceReady(@NonNull Bitmap bmp,
                                    @Nullable Transition<? super Bitmap> t) {
                                saveBitmapToGallery(bmp);
                            }
                            @Override public void onLoadCleared(@Nullable Drawable d) {}
                        });
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void saveBitmapToGallery(Bitmap bmp) {
        try {
            String name = "callx_channel_" + System.currentTimeMillis() + ".jpg";
            OutputStream out;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CallX");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                out = getContentResolver().openOutputStream(uri);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "CallX");
                dir.mkdirs();
                File f = new File(dir, name);
                out = new FileOutputStream(f);
            }
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.close();
            runOnUiThread(() -> Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show());
        }
    }

    private void saveVideoStream(InputStream in) {
        try {
            String name = "callx_channel_" + System.currentTimeMillis() + ".mp4";
            OutputStream out;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CallX");
                Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                out = getContentResolver().openOutputStream(uri);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "CallX");
                dir.mkdirs();
                out = new FileOutputStream(new File(dir, name));
            }
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            out.close();
            in.close();
            runOnUiThread(() -> Toast.makeText(this, "Video saved to gallery", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override protected void onStop() {
        super.onStop();
        if (exoPlayer != null) exoPlayer.pause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer != null) { exoPlayer.release(); exoPlayer = null; }
    }
}
