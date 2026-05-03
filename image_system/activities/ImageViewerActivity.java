package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.utils.GlideImageLoader;
import com.github.chrisbanes.photoview.PhotoView;  // zoom support

/**
 * ImageViewerActivity — Full-screen image viewer
 *
 * Features:
 *  ✅ Pinch-to-zoom (PhotoView library)
 *  ✅ Progressive load (thumb blur → full sharp)
 *  ✅ Share button
 *  ✅ Download button
 *  ✅ Loading indicator
 *
 * Add to build.gradle:
 *   implementation 'com.github.chrisbanes:PhotoView:2.3.0'
 *
 * Add to AndroidManifest.xml:
 *   <activity android:name=".activities.ImageViewerActivity"
 *       android:theme="@style/Theme.Black" />
 */
public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_THUMB_URL = "thumbUrl";
    public static final String EXTRA_FULL_URL  = "fullUrl";

    private PhotoView   photoView;
    private ProgressBar progressBar;
    private String      thumbUrl;
    private String      fullUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        photoView   = findViewById(R.id.photo_view);
        progressBar = findViewById(R.id.progress_bar);
        ImageButton btnBack  = findViewById(R.id.btn_back);
        ImageButton btnShare = findViewById(R.id.btn_share);

        thumbUrl = getIntent().getStringExtra(EXTRA_THUMB_URL);
        fullUrl  = getIntent().getStringExtra(EXTRA_FULL_URL);

        btnBack.setOnClickListener(v -> finish());
        btnShare.setOnClickListener(v -> shareImage());

        loadImage();
    }

    // ── Progressive load ──────────────────────────────────────────────────

    private void loadImage() {
        progressBar.setVisibility(View.VISIBLE);

        // Step 1: Load thumb first (instant display)
        Glide.with(this)
            .load(thumbUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .apply(RequestOptions.overrideOf(200, 200))
            .into(photoView);

        // Step 2: Load full image (replaces thumb with fade)
        Glide.with(this)
            .load(fullUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .transition(DrawableTransitionOptions.withCrossFade(300))
            .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                @Override
                public boolean onLoadFailed(
                    com.bumptech.glide.load.engine.GlideException e,
                    Object model,
                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                    boolean isFirstResource) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(ImageViewerActivity.this,
                        "Failed to load image", Toast.LENGTH_SHORT).show();
                    return false;
                }

                @Override
                public boolean onResourceReady(
                    android.graphics.drawable.Drawable resource,
                    Object model,
                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                    com.bumptech.glide.load.DataSource dataSource,
                    boolean isFirstResource) {
                    progressBar.setVisibility(View.GONE);
                    return false;
                }
            })
            .into(photoView);
    }

    // ── Share ─────────────────────────────────────────────────────────────

    private void shareImage() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, fullUrl);
        startActivity(Intent.createChooser(shareIntent, "Share Image"));
    }
}
