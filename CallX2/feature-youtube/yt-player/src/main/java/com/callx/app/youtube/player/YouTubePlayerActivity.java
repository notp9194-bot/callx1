package com.callx.app.youtube.player;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.youtube.core.models.YouTubeVideo;
import com.callx.app.youtube.core.navigator.YTNavigatorProvider;
import com.callx.app.youtube.core.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.core.utils.YouTubePrefs;
import com.callx.app.youtube.core.utils.YTConstants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.MediaItem;

/**
 * YouTubePlayerActivity — ExoPlayer-based full-screen video player.
 *
 * Responsibilities (yt-player module):
 *  - Load video metadata from Firebase via YouTubeFirebaseUtils
 *  - ExoPlayer setup, lifecycle management (pause/resume/release)
 *  - Like / Dislike / Subscribe actions
 *  - Speed & quality bottom sheet (YouTubeSpeedQualitySheet)
 *  - Mini player on back press (YouTubeMiniPlayerManager)
 *  - PiP support
 *
 * Cross-module navigation:
 *  - openComments  → YTNavigatorProvider.get().openComments(ctx, videoId)
 *  - openChannel   → YTNavigatorProvider.get().openChannel(ctx, uploaderUid)
 *  - openReelSheet → reflection (cross-module boundary kept loose)
 *
 * NOTE: This is a skeleton. Paste the full implementation from the original
 * YouTubePlayerActivity.java (feature-youtube module) and update:
 *   - package → com.callx.app.youtube.player
 *   - imports → use com.callx.app.youtube.core.*
 *   - R → com.callx.app.youtube.player.R
 *   - Cross-module intents → YTNavigatorProvider
 */
public class YouTubePlayerActivity extends AppCompatActivity {

    private static final String TAG = "YT_PLAYER";

    private ExoPlayer   player;
    private PlayerView  playerView;
    private YouTubeVideo currentVideo;
    private String      videoId;
    private boolean     playerReadyToPlay;
    private String      myUid = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_player);

        videoId = getIntent().getStringExtra(YTConstants.EXTRA_VIDEO_ID);
        if (videoId == null) { finish(); return; }

        var user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) myUid = user.getUid();

        playerView = findViewById(R.id.yt_player_view);
        initPlayer();
        loadVideoData();
        setupLikeDislike();
        setupCommentButton();
        setupChannelButton();
        setupSpeedQualityButton();
        setupBackButton();
    }

    private void initPlayer() {
        YouTubePrefs prefs = new YouTubePrefs(this);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
    }

    private void loadVideoData() {
        YouTubeFirebaseUtils.videoRef(videoId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    currentVideo = snap.getValue(YouTubeVideo.class);
                    if (currentVideo != null) {
                        bindVideoToUI(currentVideo);
                        startPlayback(currentVideo.videoUrl);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void bindVideoToUI(YouTubeVideo v) {
        // TODO: bind title, description, like/view counts, channel info via views
    }

    private void startPlayback(String url) {
        if (url == null || url.isEmpty()) return;
        // Handle Cloudinary f_mp4 transformation
        if (url.contains("/upload/") && !url.contains("f_mp4")) {
            url = url.replace("/upload/", "/upload/f_mp4,q_auto/");
        }
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        playerReadyToPlay = true;
    }

    private void setupCommentButton() {
        // Example: btnComments.setOnClickListener(v ->
        //     YTNavigatorProvider.get().openComments(this, videoId));
    }

    private void setupChannelButton() {
        // Example: avatarView.setOnClickListener(v ->
        //     YTNavigatorProvider.get().openChannel(this, currentVideo.uploaderUid));
    }

    private void setupLikeDislike() {
        // Wire like/dislike Firebase toggle logic here
    }

    private void setupSpeedQualityButton() {
        // YouTubeSpeedQualitySheet.newInstance(speed, quality).setCallback(...).show(...)
    }

    private void setupBackButton() {
        // On back: show mini player, then finish
    }

    @Override protected void onPause() { super.onPause(); if (player != null) player.pause(); }
    @Override protected void onResume() { super.onResume(); if (player != null && playerReadyToPlay) player.play(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
    }
}
