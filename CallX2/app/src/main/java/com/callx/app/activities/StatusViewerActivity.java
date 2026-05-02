package com.callx.app.activities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import com.bumptech.glide.Glide;
import com.callx.app.databinding.ActivityStatusViewerBinding;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;
public class StatusViewerActivity extends AppCompatActivity {
    private ActivityStatusViewerBinding binding;
    private final List<StatusItem> items = new ArrayList<>();
    private int idx = 0;
    private ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunner;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStatusViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        String ownerUid = getIntent().getStringExtra("ownerUid");
        if (ownerUid == null) { finish(); return; }
        binding.btnCloseStatus.setOnClickListener(v -> finish());
        load(ownerUid);
    }
    private void load(String ownerUid) {
        FirebaseUtils.getStatusRef().child(ownerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    long now = System.currentTimeMillis();
                    for (DataSnapshot c : snap.getChildren()) {
                        StatusItem s = c.getValue(StatusItem.class);
                        if (s == null) continue;
                        if (s.expiresAt != null && s.expiresAt < now) continue;
                        items.add(s);
                    }
                    if (items.isEmpty()) { finish(); return; }
                    StatusItem first = items.get(0);
                    binding.tvOwner.setText(first.ownerName == null ? "Status" : first.ownerName);
                    if (first.ownerPhoto != null && !first.ownerPhoto.isEmpty()) {
                        Glide.with(StatusViewerActivity.this).load(first.ownerPhoto)
                            .into(binding.ivOwner);
                    }
                    showCurrent();
                }
                @Override public void onCancelled(DatabaseError e) { finish(); }
            });
    }
    private void showCurrent() {
        if (idx >= items.size()) { finish(); return; }
        StatusItem s = items.get(idx);
        hideAll();
        if ("text".equals(s.type)) {
            binding.flTextStatus.setVisibility(View.VISIBLE);
            binding.tvTextStatus.setText(s.text == null ? "" : s.text);
            startProgress(5000);
        } else if ("video".equals(s.type) && s.mediaUrl != null) {
            binding.player.setVisibility(View.VISIBLE);
            if (player != null) player.release();
            player = new ExoPlayer.Builder(this).build();
            binding.player.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
            player.prepare();
            player.setPlayWhenReady(true);
            startProgress(15000);
        } else if ("image".equals(s.type) && s.mediaUrl != null) {
            binding.ivStatus.setVisibility(View.VISIBLE);
            Glide.with(this).load(s.mediaUrl).into(binding.ivStatus);
            startProgress(5000);
        } else {
            next();
        }
    }
    private void hideAll() {
        binding.flTextStatus.setVisibility(View.GONE);
        binding.player.setVisibility(View.GONE);
        binding.ivStatus.setVisibility(View.GONE);
    }
    private void startProgress(long durationMs) {
        if (progressRunner != null) handler.removeCallbacks(progressRunner);
        binding.progressStatus.setProgress(0);
        long step = 50;
        long total = durationMs;
        progressRunner = new Runnable() {
            long elapsed = 0;
            @Override public void run() {
                elapsed += step;
                int prog = (int)((elapsed * 100L) / total);
                binding.progressStatus.setProgress(Math.min(100, prog));
                if (elapsed >= total) { next(); }
                else handler.postDelayed(this, step);
            }
        };
        handler.postDelayed(progressRunner, step);
    }
    private void next() {
        if (player != null) { player.release(); player = null; }
        if (progressRunner != null) handler.removeCallbacks(progressRunner);
        idx++;
        showCurrent();
    }
    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        if (progressRunner != null) handler.removeCallbacks(progressRunner);
        super.onDestroy();
    }
}
