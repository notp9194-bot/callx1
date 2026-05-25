package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.callx.app.cache.XTweetCacheManager;
import com.callx.app.x.R;

/**
 * XVideoPlayerActivity — ExoPlayer + XTweetCacheManager se tweet videos play karta hai.
 *
 * Pehle VideoView tha → har baar network se fresh download hota tha.
 * Ab ExoPlayer + cache:
 *   ✅ Pehli baar: internet se download + automatically cache mein save
 *   ✅ Dobaara open karo → INSTANTLY play, zero data use
 *   ✅ Feed scroll mein preloader ne already 2MB cache kar rakhi hoti hai → instant start
 */
@OptIn(markerClass = UnstableApi.class)
public class XVideoPlayerActivity extends AppCompatActivity {

    private ExoPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_video_player);

        String videoUrl = getIntent().getStringExtra("video_url");
        PlayerView playerView = findViewById(R.id.pv_x_player);

        if (videoUrl != null && playerView != null) {
            // Cache init (already done in CallxApp, par safety ke liye)
            XTweetCacheManager.init(getApplicationContext());

            // ExoPlayer banao with CacheDataSource — yahi asli fix hai
            // CacheDataSource pehle disk cache check karta hai, network sirf miss hone par
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(
                XTweetCacheManager.getCacheDataSourceFactory()
            ).createMediaSource(MediaItem.fromUri(videoUrl));

            player.setMediaSource(mediaSource);
            player.prepare();
            player.setPlayWhenReady(true);
        }

        View btnClose = findViewById(R.id.btn_x_player_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
