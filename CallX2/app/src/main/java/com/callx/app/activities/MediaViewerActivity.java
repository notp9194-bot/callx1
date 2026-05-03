package com.callx.app.activities;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import com.bumptech.glide.Glide;
import com.callx.app.databinding.ActivityMediaViewerBinding;
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
            player = new ExoPlayer.Builder(this).build();
            binding.player.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
            player.prepare();
            player.setPlayWhenReady(true);
        } else {
            binding.ivFull.setVisibility(View.VISIBLE);
            Glide.with(this).load(url).into(binding.ivFull);
        }
    }
    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
