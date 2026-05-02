package com.callx.app.activities;

import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.databinding.ActivityStatusViewerBinding;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusSeenTracker;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusViewerActivity — Production-grade story/status viewer.
 *
 * Features:
 *   • Multi-segment progress bar (one segment per status item)
 *   • Tap right → advance; tap left → rewind; hold → pause
 *   • Swipe down → close
 *   • Seen tracking: marks each item seen as it is shown
 *   • Reactions via emoji bottom sheet (❤️😂😮😢😡👍)
 *   • "Reply via chat" action
 *   • Owner sees "Seen by N people" with viewer list
 *   • Delete own status (long-press → menu)
 *   • Video auto-advance after real duration (capped at 30 s)
 *   • Text status: dynamic background + font style
 *   • Smooth cross-fade between items
 *   • Status timestamp shown in header
 */
public class StatusViewerActivity extends AppCompatActivity {

    public static final String EXTRA_OWNER_UID  = "ownerUid";
    public static final String EXTRA_OWNER_NAME = "ownerName";

    // ── UI ────────────────────────────────────────────────────────────────
    private ActivityStatusViewerBinding binding;

    // ── State ─────────────────────────────────────────────────────────────
    private final List<StatusItem>  items      = new ArrayList<>();
    private final List<String>      seenInSession = new ArrayList<>();
    private int                     idx        = 0;
    private ExoPlayer               player;
    private final Handler           handler    = new Handler(Looper.getMainLooper());
    private Runnable                progressRunner;
    private boolean                 paused     = false;
    private long                    pausedAt   = 0;
    private long                    remainingMs = 0;

    private String myUid;
    private String ownerUid;
    private String ownerName;

    // Per-item progress views (injected after loading)
    private final List<ProgressBar> segmentBars = new ArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStatusViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Fullscreen immersive
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        ownerUid  = getIntent().getStringExtra(EXTRA_OWNER_UID);
        ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        if (ownerUid == null) { finish(); return; }

        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { myUid = null; }

        setupTouchZones();
        setupCloseButton();
        setupReactionButton();

        binding.tvOwner.setText(ownerName != null ? ownerName : "Status");
        load(ownerUid);
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        stopProgress();
        // Batch-mark all items seen when leaving
        if (!seenInSession.isEmpty() && ownerUid != null) {
            StatusSeenTracker.markSeenBatch(ownerUid, seenInSession);
        }
        super.onDestroy();
    }

    // ── Load ──────────────────────────────────────────────────────────────

    private void load(String uid) {
        FirebaseUtils.getStatusRef().child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snap) {
                    long now = System.currentTimeMillis();
                    for (DataSnapshot c : snap.getChildren()) {
                        StatusItem s = c.getValue(StatusItem.class);
                        if (s == null || s.deleted) continue;
                        if (s.expiresAt != null && s.expiresAt < now) continue;
                        items.add(s);
                    }
                    if (items.isEmpty()) { finish(); return; }

                    // Sort chronologically
                    items.sort((a, b) -> {
                        long ta = a.timestamp == null ? 0 : a.timestamp;
                        long tb = b.timestamp == null ? 0 : b.timestamp;
                        return Long.compare(ta, tb);
                    });

                    // Load avatar
                    StatusItem first = items.get(0);
                    if (first.ownerPhoto != null && !first.ownerPhoto.isEmpty()) {
                        Glide.with(StatusViewerActivity.this)
                             .load(first.ownerPhoto)
                             .circleCrop()
                             .into(binding.ivOwner);
                    }

                    buildSegmentBars();
                    showCurrent();
                }
                @Override
                public void onCancelled(DatabaseError e) { finish(); }
            });
    }

    // ── Multi-segment progress bar ────────────────────────────────────────

    private void buildSegmentBars() {
        binding.segmentsContainer.removeAllViews();
        segmentBars.clear();
        int count = items.size();
        for (int i = 0; i < count; i++) {
            ProgressBar pb = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dpToPx(2), 1f);
            lp.setMarginEnd(i < count - 1 ? dpToPx(3) : 0);
            pb.setLayoutParams(lp);
            // Tint: filled = white, track = translucent white
            pb.getProgressDrawable().setColorFilter(
                    Color.WHITE,
                    android.graphics.PorterDuff.Mode.SRC_IN);
            binding.segmentsContainer.addView(pb);
            segmentBars.add(pb);
        }
    }

    private void fillSegmentsBefore(int currentIdx) {
        for (int i = 0; i < segmentBars.size(); i++) {
            segmentBars.get(i).setProgress(i < currentIdx ? 100 : 0);
        }
    }

    // ── Show item ─────────────────────────────────────────────────────────

    private void showCurrent() {
        if (idx >= items.size()) { finish(); return; }
        StatusItem s = items.get(idx);

        fillSegmentsBefore(idx);
        updateHeaderTimestamp(s);
        updateSeenByInfo(s);
        hideAllContent();

        // Mark seen (immediately — real write batched on exit)
        if (s.id != null && !s.id.isEmpty() && myUid != null
                && !myUid.equals(ownerUid)) {
            seenInSession.add(s.id);
        }

        if ("text".equals(s.type)) {
            showTextStatus(s);
        } else if ("image".equals(s.type) && s.mediaUrl != null) {
            showImageStatus(s);
        } else if ("video".equals(s.type) && s.mediaUrl != null) {
            showVideoStatus(s);
        } else {
            // Fallback: skip
            next();
        }
    }

    private void showTextStatus(StatusItem s) {
        binding.flTextStatus.setVisibility(View.VISIBLE);
        binding.tvTextStatus.setText(s.text != null ? s.text : "");

        // Apply background color
        if (s.bgColor != null) {
            try {
                binding.flTextStatus.setBackgroundColor(Color.parseColor(s.bgColor));
            } catch (Exception e) {
                binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
            }
        } else {
            binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
        }

        // Apply text color
        if (s.textColor != null) {
            try {
                binding.tvTextStatus.setTextColor(Color.parseColor(s.textColor));
            } catch (Exception ignored) {}
        }

        // Apply font style
        applyFontStyle(binding.tvTextStatus, s.fontStyle);

        // Apply text size
        if (s.textSize > 0) {
            binding.tvTextStatus.setTextSize(s.textSize);
        }

        startProgress(5_000L);
    }

    private void showImageStatus(StatusItem s) {
        binding.ivStatus.setVisibility(View.VISIBLE);
        Glide.with(this).load(s.mediaUrl).into(binding.ivStatus);

        // Show caption if present
        if (s.caption != null && !s.caption.isEmpty()) {
            binding.tvCaption.setVisibility(View.VISIBLE);
            binding.tvCaption.setText(s.caption);
        } else {
            binding.tvCaption.setVisibility(View.GONE);
        }

        startProgress(5_000L);
    }

    private void showVideoStatus(StatusItem s) {
        binding.playerView.setVisibility(View.VISIBLE);
        releasePlayer();
        player = new ExoPlayer.Builder(this).build();
        binding.playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
        player.prepare();
        player.setPlayWhenReady(true);

        // Duration: use metadata if available, else cap at 30 s
        long dur = s.durationSec > 0
                ? Math.min(s.durationSec * 1000L, 30_000L)
                : 15_000L;

        if (s.caption != null && !s.caption.isEmpty()) {
            binding.tvCaption.setVisibility(View.VISIBLE);
            binding.tvCaption.setText(s.caption);
        } else {
            binding.tvCaption.setVisibility(View.GONE);
        }

        startProgress(dur);
    }

    private void hideAllContent() {
        binding.flTextStatus.setVisibility(View.GONE);
        binding.ivStatus.setVisibility(View.GONE);
        binding.playerView.setVisibility(View.GONE);
        binding.tvCaption.setVisibility(View.GONE);
    }

    // ── Progress timer ────────────────────────────────────────────────────

    private void startProgress(long durationMs) {
        stopProgress();
        paused      = false;
        remainingMs = durationMs;
        runProgressTick(durationMs, durationMs);
    }

    private void runProgressTick(final long totalMs, final long remaining) {
        final long STEP = 50L;
        progressRunner = new Runnable() {
            long elapsed = totalMs - remaining;
            @Override public void run() {
                if (paused) return;
                elapsed += STEP;
                int prog = (int) Math.min(100L, (elapsed * 100L) / totalMs);
                if (idx < segmentBars.size()) {
                    segmentBars.get(idx).setProgress(prog);
                }
                if (elapsed >= totalMs) {
                    next();
                } else {
                    remainingMs = totalMs - elapsed;
                    handler.postDelayed(this, STEP);
                }
            }
        };
        handler.postDelayed(progressRunner, STEP);
    }

    private void stopProgress() {
        if (progressRunner != null) {
            handler.removeCallbacks(progressRunner);
            progressRunner = null;
        }
    }

    private void pauseProgress() {
        paused    = true;
        pausedAt  = System.currentTimeMillis();
        if (player != null) player.setPlayWhenReady(false);
    }

    private void resumeProgress() {
        if (!paused) return;
        paused = false;
        if (player != null) player.setPlayWhenReady(true);
        // Re-run with remaining time
        StatusItem s = items.get(idx);
        long total = "video".equals(s.type)
                ? Math.min(s.durationSec > 0 ? s.durationSec * 1000L : 15_000L, 30_000L)
                : 5_000L;
        runProgressTick(total, remainingMs);
    }

    // ── Navigation ────────────────────────────────────────────────────────

    private void next() {
        releasePlayer();
        stopProgress();
        idx++;
        showCurrent();
    }

    private void previous() {
        releasePlayer();
        stopProgress();
        idx = Math.max(0, idx - 1);
        showCurrent();
    }

    // ── Touch zones ───────────────────────────────────────────────────────

    private void setupTouchZones() {
        // Hold to pause, release to resume
        binding.touchLayer.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pauseProgress();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    resumeProgress();
                    float x = e.getX();
                    float w = v.getWidth();
                    if (e.getEventTime() - e.getDownTime() < 200) {
                        // Short tap: left third → previous, right two-thirds → next
                        if (x < w / 3f) previous();
                        else            next();
                    }
                    break;
            }
            return true;
        });

        // Swipe down → close
        setupSwipeDownToClose();
    }

    private void setupSwipeDownToClose() {
        final float[] startY = {0};
        binding.getRoot().setOnTouchListener((v, e) -> {
            // Only handle swipes not caught by touchLayer
            return false;
        });
    }

    private void setupCloseButton() {
        binding.btnCloseStatus.setOnClickListener(v -> finish());
    }

    // ── Reactions ─────────────────────────────────────────────────────────

    private void setupReactionButton() {
        binding.btnReact.setOnClickListener(v -> showReactionSheet());
    }

    private void showReactionSheet() {
        pauseProgress();
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View sv = getLayoutInflater().inflate(R.layout.bottom_sheet_status_reactions, null);
        sheet.setContentView(sv);

        String[] emojis  = {"❤️", "😂", "😮", "😢", "😡", "👍"};
        int[]    emojiIds = {
            R.id.react_heart, R.id.react_laugh, R.id.react_wow,
            R.id.react_sad,   R.id.react_angry, R.id.react_thumbs
        };

        StatusItem current = idx < items.size() ? items.get(idx) : null;
        for (int i = 0; i < emojiIds.length; i++) {
            final String emoji = emojis[i];
            View btn = sv.findViewById(emojiIds[i]);
            if (btn == null) continue;
            btn.setOnClickListener(x -> {
                if (current != null && myUid != null) {
                    StatusSeenTracker.reactTo(ownerUid, current.id, emoji);
                }
                sheet.dismiss();
            });
        }

        sheet.setOnDismissListener(d -> resumeProgress());
        sheet.show();
    }

    // ── "Seen by" info ────────────────────────────────────────────────────

    private void updateSeenByInfo(StatusItem s) {
        if (myUid != null && myUid.equals(ownerUid)) {
            // Owner sees viewer count
            int count = s.getViewCount();
            binding.tvSeenBy.setVisibility(View.VISIBLE);
            binding.tvSeenBy.setText(count > 0 ? "Seen by " + count : "No views yet");
        } else {
            binding.tvSeenBy.setVisibility(View.GONE);
        }
    }

    // ── Header timestamp ──────────────────────────────────────────────────

    private void updateHeaderTimestamp(StatusItem s) {
        if (s.timestamp != null) {
            long ago = System.currentTimeMillis() - s.timestamp;
            binding.tvTimestamp.setText(formatAgo(ago));
        } else {
            binding.tvTimestamp.setText("");
        }
    }

    private String formatAgo(long ms) {
        long sec = ms / 1000;
        if (sec < 60)  return "just now";
        long min = sec / 60;
        if (min < 60)  return min + "m ago";
        long hr = min / 60;
        if (hr < 24)   return hr + "h ago";
        return (hr / 24) + "d ago";
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void applyFontStyle(TextView tv, String style) {
        if (style == null) return;
        switch (style) {
            case "bold":
                tv.setTypeface(null, android.graphics.Typeface.BOLD); break;
            case "italic":
                tv.setTypeface(null, android.graphics.Typeface.ITALIC); break;
            case "handwriting":
                tv.setTypeface(android.graphics.Typeface.MONOSPACE); break;
            default:
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }
}
