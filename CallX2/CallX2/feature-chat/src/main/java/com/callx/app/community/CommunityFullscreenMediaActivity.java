package com.callx.app.community;

import android.os.Bundle;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;

/**
 * v31: Fullscreen media viewer for community gallery.
 */
public class CommunityFullscreenMediaActivity extends AppCompatActivity {

    public static final String EXTRA_MEDIA_URL   = "mediaUrl";
    public static final String EXTRA_MEDIA_TYPE  = "mediaType";
    public static final String EXTRA_AUTHOR_NAME = "authorName";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String mediaUrl  = getIntent().getStringExtra(EXTRA_MEDIA_URL);
        String mediaType = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);
        String author    = getIntent().getStringExtra(EXTRA_AUTHOR_NAME);

        // Simple full-screen image display
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF000000);
        iv.setOnClickListener(v -> finish());

        setContentView(iv);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(author != null ? author : "Media");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        if (mediaUrl != null) {
            Glide.with(this).load(mediaUrl).override(720, 720).into(iv);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
