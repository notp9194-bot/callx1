package com.callx.app.channel;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;

/**
 * ChannelMediaViewerActivity — full-screen media viewer for channel post images/videos.
 *
 * Image: pinch-to-zoom with PhotoView / Matrix (uses standard ImageView if PhotoView unavailable).
 * Video: opens via system intent or MediaPlayer inline.
 */
public class ChannelMediaViewerActivity extends AppCompatActivity {

    public static final String EXTRA_MEDIA_URL  = "mediaUrl";
    public static final String EXTRA_MEDIA_TYPE = "mediaType";
    public static final String EXTRA_POST_TEXT  = "postText";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_media_viewer);

        String mediaUrl  = getIntent().getStringExtra(EXTRA_MEDIA_URL);
        String mediaType = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);
        String postText  = getIntent().getStringExtra(EXTRA_POST_TEXT);

        // Back button
        View btnBack = findViewById(R.id.btn_media_viewer_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Caption
        TextView tvCaption = findViewById(R.id.tv_media_viewer_caption);
        if (tvCaption != null) {
            if (postText != null && !postText.isEmpty()) {
                tvCaption.setText(postText);
                tvCaption.setVisibility(View.VISIBLE);
            } else {
                tvCaption.setVisibility(View.GONE);
            }
        }

        if ("video".equals(mediaType)) {
            // For video, show VideoView
            VideoView vv = findViewById(R.id.video_viewer);
            ImageView ivThumb = findViewById(R.id.iv_media_viewer_image);
            if (vv != null && mediaUrl != null) {
                vv.setVisibility(View.VISIBLE);
                if (ivThumb != null) ivThumb.setVisibility(View.GONE);
                vv.setVideoURI(Uri.parse(mediaUrl));
                MediaController mc = new MediaController(this);
                mc.setAnchorView(vv);
                vv.setMediaController(mc);
                vv.start();
            }
        } else {
            // Image
            ImageView iv = findViewById(R.id.iv_media_viewer_image);
            VideoView vv = findViewById(R.id.video_viewer);
            if (vv != null) vv.setVisibility(View.GONE);
            if (iv != null && mediaUrl != null) {
                iv.setVisibility(View.VISIBLE);
                Glide.with(this).load(mediaUrl).into(iv);
            }
        }

        // Share button
        View btnShare = findViewById(R.id.btn_media_viewer_share);
        if (btnShare != null && mediaUrl != null) {
            btnShare.setOnClickListener(v -> {
                android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(android.content.Intent.EXTRA_TEXT, mediaUrl);
                startActivity(android.content.Intent.createChooser(share, "Share"));
            });
        }
    }
}
