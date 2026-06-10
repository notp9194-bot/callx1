package com.callx.app.player;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.social.DuetReelActivity;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;

/**
 * SingleReelPlayerActivity — Fix 1
 *
 * Plays a single reel (or one reel from a list) by its Firebase reelId.
 * Referenced by DuetsByReelActivity, UserReelsActivity, and HomeFragment.
 *
 * Supported intent modes:
 *  Mode A — single reel:
 *      EXTRA_REEL_ID  (String)  — Firebase reel ID to load
 *      EXTRA_TITLE    (String)  — optional display title
 *
 *  Mode B — reel list (UserReelsActivity / HomeFragment):
 *      EXTRA_REEL_IDS           (ArrayList<String>) — ordered list of reel IDs
 *      EXTRA_START_POSITION     (int)               — index into list to start playback
 *
 * Features:
 *  ✅ Loads reel by ID from Firebase
 *  ✅ Loops video with ExoPlayer
 *  ✅ Shows owner name + caption
 *  ✅ "Duet this" button (honours allowDuetLevel)
 *  ✅ Back button
 */
@OptIn(markerClass = UnstableApi.class)
public class SingleReelPlayerActivity extends AppCompatActivity {

    // ── Mode A — single reel ─────────────────────────────────────────────────
    public static final String EXTRA_REEL_ID   = "single_reel_id";
    public static final String EXTRA_TITLE     = "single_reel_title";

    // ── Mode B — reel list (UserReelsActivity, HomeFragment) ─────────────────
    public static final String EXTRA_REEL_IDS       = "single_reel_ids";
    public static final String EXTRA_START_POSITION = "single_reel_start";

    // ─────────────────────────────────────────────────────────────────────────

    private PlayerView  playerView;
    private ExoPlayer   exoPlayer;
    private ImageButton btnBack;
    private View        btnDuet;
    private TextView    tvOwner, tvCaption;
    private View        progressLoad;

    private ReelModel currentReel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_single_reel_player);

        playerView   = findViewById(R.id.player_single_reel);
        btnBack      = findViewById(R.id.btn_single_back);
        btnDuet      = findViewById(R.id.btn_single_duet);
        tvOwner      = findViewById(R.id.tv_single_owner);
        tvCaption    = findViewById(R.id.tv_single_caption);
        progressLoad = findViewById(R.id.progress_single_reel);

        btnBack.setOnClickListener(v -> finish());

        // ── Resolve reel ID ───────────────────────────────────────────────────
        String reelId = resolveReelId(getIntent());
        if (reelId == null) {
            Toast.makeText(this, "Reel not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && tvOwner != null) tvOwner.setText(title);

        loadReel(reelId);
    }

    /**
     * Resolves the reel ID from either Mode A or Mode B intent extras.
     * Mode B: picks the ID at EXTRA_START_POSITION from EXTRA_REEL_IDS.
     */
    private String resolveReelId(Intent intent) {
        // Mode B — list
        ArrayList<String> ids = intent.getStringArrayListExtra(EXTRA_REEL_IDS);
        if (ids != null && !ids.isEmpty()) {
            int pos = intent.getIntExtra(EXTRA_START_POSITION, 0);
            if (pos < 0 || pos >= ids.size()) pos = 0;
            return ids.get(pos);
        }
        // Mode A — single
        return intent.getStringExtra(EXTRA_REEL_ID);
    }

    private void loadReel(String reelId) {
        if (progressLoad != null) progressLoad.setVisibility(View.VISIBLE);

        FirebaseUtils.db()
            .getReference("reels")
            .child(reelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    if (progressLoad != null) progressLoad.setVisibility(View.GONE);

                    ReelModel reel = snap.getValue(ReelModel.class);
                    if (reel == null) {
                        Toast.makeText(SingleReelPlayerActivity.this,
                            "Reel not available", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    if (reel.reelId == null) reel.reelId = snap.getKey();
                    currentReel = reel;
                    bindReel(reel);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    if (isFinishing() || isDestroyed()) return;
                    if (progressLoad != null) progressLoad.setVisibility(View.GONE);
                    Toast.makeText(SingleReelPlayerActivity.this,
                        "Failed to load reel", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
    }

    private void bindReel(ReelModel reel) {
        if (tvOwner != null && reel.ownerName != null)
            tvOwner.setText("@" + reel.ownerName);
        if (tvCaption != null && reel.caption != null)
            tvCaption.setText(reel.caption);

        setupPlayer(reel.videoUrl);

        if (btnDuet != null) {
            btnDuet.setOnClickListener(v -> openDuet(reel));
            String duetLevel = reel.effectiveAllowDuetLevel();
            btnDuet.setVisibility("off".equals(duetLevel) ? View.GONE : View.VISIBLE);
        }
    }

    private void setupPlayer(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) return;

        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.prepare();
    }

    private void openDuet(ReelModel reel) {
        com.google.firebase.auth.FirebaseUser me =
            com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) return;

        Intent i = new Intent(this, DuetReelActivity.class);
        i.putExtra(DuetReelActivity.EXTRA_REEL_ID,         reel.reelId);
        i.putExtra(DuetReelActivity.EXTRA_VIDEO_URL,        reel.videoUrl);
        i.putExtra(DuetReelActivity.EXTRA_OWNER_NAME,       reel.ownerName);
        i.putExtra(DuetReelActivity.EXTRA_OWNER_UID,        reel.uid);
        i.putExtra(DuetReelActivity.EXTRA_DURATION_SEC,     reel.duration);
        i.putExtra(DuetReelActivity.EXTRA_ALLOW_DUET_LEVEL, reel.effectiveAllowDuetLevel());
        startActivity(i);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) exoPlayer.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exoPlayer != null) exoPlayer.play();
    }

    @Override
    protected void onDestroy() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
        super.onDestroy();
    }
}
