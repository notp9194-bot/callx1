package com.callx.app.activities;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import com.bumptech.glide.Glide;
import com.callx.app.databinding.ActivityMediaViewerBinding;
import com.callx.app.utils.MediaCache;

import java.io.File;

/**
 * MediaViewerActivity — Full-screen media viewer.
 *
 * VIDEO CACHE FIX:
 *   Pehle MediaCache check karta hai — agar video already downloaded hai
 *   to local file:// URI se play karta hai (zero network, instant start).
 *   Agar cached nahi hai to pehle download karta hai, tab play karta hai.
 *   Kabhi bhi bina cache check ke URL se seedha play nahi karta.
 */
public class MediaViewerActivity extends AppCompatActivity {

    private ActivityMediaViewerBinding binding;
    private ExoPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMediaViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String url  = getIntent().getStringExtra("url");
        String type = getIntent().getStringExtra("type");
        if (url == null) { finish(); return; }

        binding.btnClose.setOnClickListener(v -> finish());

        if ("video".equals(type)) {
            binding.player.setVisibility(View.VISIBLE);
            playVideo(url);
        } else {
            binding.ivFull.setVisibility(View.VISIBLE);
            // ── Progressive image load: thumb instantly → full replaces ──
            String thumbUrl = getIntent().getStringExtra("thumbUrl");
            loadImageProgressive(url, thumbUrl);
        }
    }

    /**
     * Progressive image load:
     *   1. Show thumbnail immediately (instant, ~30KB already cached)
     *   2. Full image loads in background → crossfade replaces thumb
     */
    private void loadImageProgressive(String fullUrl, String thumbUrl) {
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            // Show thumb instantly
            Glide.with(this)
                .load(thumbUrl)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .override(400, 400)
                .into(binding.ivFull);

            // Full image replaces thumb with smooth crossfade
            Glide.with(this)
                .load(fullUrl)
                .thumbnail(Glide.with(this)
                    .load(thumbUrl)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL))
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .transition(com.bumptech.glide.load.resource.drawable
                    .DrawableTransitionOptions.withCrossFade(500))
                .into(binding.ivFull);
        } else {
            // No thumbnail — check local cache first, else direct load
            File cachedImg = MediaCache.getCached(this, fullUrl);
            if (cachedImg != null) {
                Glide.with(this).load(cachedImg)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .into(binding.ivFull);
            } else {
                Glide.with(this).load(fullUrl)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .into(binding.ivFull);
                MediaCache.get(this, fullUrl, new MediaCache.Callback() {
                    @Override public void onReady(File f) {}
                    @Override public void onError(String r) {}
                });
            }
        }
    }

    /**
     * VIDEO CACHE FIX — yahi tha asli bug.
     *
     * Pehle local cache check karo.
     * - HIT  → seedha file:// URI se ExoPlayer start karo (no download, instant)
     * - MISS → MediaCache se download karo, callback mein ExoPlayer start karo
     *          (ek baar download → hamesha cache se)
     */
    private void playVideo(String url) {
        // Step 1: Already cached?
        File cached = MediaCache.getCached(this, url);
        if (cached != null) {
            android.util.Log.d("MediaViewer", "Video CACHE HIT → playing local: " + cached.getAbsolutePath());
            startExoPlayer(Uri.fromFile(cached));
            return;
        }

        // Step 2: Cache miss — show loading, download first
        android.util.Log.d("MediaViewer", "Video CACHE MISS → downloading first: " + url);
        showLoading(true);

        MediaCache.get(this, url, new MediaCache.Callback() {
            @Override
            public void onReady(File file) {
                android.util.Log.d("MediaViewer", "Video downloaded & cached: " + file.getAbsolutePath());
                showLoading(false);
                startExoPlayer(Uri.fromFile(file));
            }

            @Override
            public void onError(String reason) {
                // Download fail hua — network URL se fallback (last resort)
                android.util.Log.w("MediaViewer", "Cache download failed (" + reason + "), fallback to URL");
                showLoading(false);
                startExoPlayer(Uri.parse(url));
            }
        });
    }

    private void startExoPlayer(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        player = new ExoPlayer.Builder(this).build();
        binding.player.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void showLoading(boolean show) {
        runOnUiThread(() -> {
            // ProgressBar agar layout mein hai to dikhao, warna ignore karo
            View pb = binding.getRoot().findViewById(
                    com.callx.app.R.id.pb_loading);
            if (pb != null) pb.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
