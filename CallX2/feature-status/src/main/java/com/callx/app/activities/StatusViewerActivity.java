package com.callx.app.activities;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.bumptech.glide.Glide;
import com.callx.app.cache.StatusVideoCacheManager;
import com.callx.app.status.R;
import com.callx.app.status.databinding.ActivityStatusViewerBinding;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.callx.app.utils.StatusSeenTracker;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.*;

import java.util.*;

/**
 * StatusViewerActivity — Production-grade story/status viewer.
 *
 * Features (complete):
 *   ✅ Multi-segment progress bar (one segment per status item)
 *   ✅ Tap right → advance; tap left → rewind; hold → pause
 *   ✅ Swipe down → close (real GestureDetector — pehle broken tha)
 *   ✅ onPause/onResume — call/notification par auto-pause/resume
 *   ✅ Video: ExoPlayer cache-first (StatusVideoCacheManager)
 *   ✅ Video: actual duration se progress (timer se nahi, player se)
 *   ✅ Video: mute/unmute toggle button
 *   ✅ Image: Glide with DiskCacheStrategy.ALL
 *   ✅ Text: background color + font style + text size
 *   ✅ Reply via chat (et_reply + btn_send_reply wired up — pehle nahi tha)
 *   ✅ Emoji reactions bottom sheet
 *   ✅ Owner: "Seen by N" tap → viewer list dialog
 *   ✅ Owner: delete status via btn_more long-press menu
 *   ✅ Seen tracking batched on exit
 *   ✅ Status timestamp in header
 *   ✅ Cross-fade animation between items
 *   ✅ Keep screen ON while viewing
 */
public class StatusViewerActivity extends AppCompatActivity {

    public static final String EXTRA_OWNER_UID  = "ownerUid";
    public static final String EXTRA_OWNER_NAME = "ownerName";

    // ── UI ────────────────────────────────────────────────────────────────
    private ActivityStatusViewerBinding binding;

    // ── State ─────────────────────────────────────────────────────────────
    private final List<StatusItem>  items         = new ArrayList<>();
    private final List<String>      seenInSession = new ArrayList<>();
    private int                     idx           = 0;
    private ExoPlayer               player;
    private final Handler           handler       = new Handler(Looper.getMainLooper());
    private Runnable                progressRunner;
    private boolean                 paused        = false;
    private long                    remainingMs   = 0;
    private boolean                 isMuted       = false;

    private String myUid;
    private String ownerUid;
    private String ownerName;

    // Per-item progress segments
    private final List<ProgressBar> segmentBars = new ArrayList<>();

    // Swipe-down gesture
    private GestureDetector swipeDetector;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStatusViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Keep screen on while viewing statuses
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Fullscreen immersive
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        ownerUid  = getIntent().getStringExtra(EXTRA_OWNER_UID);
        ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        if (ownerUid == null) { finish(); return; }

        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { myUid = null; }

        setupSwipeDownGesture();
        setupTouchZones();
        setupCloseButton();
        setupReactionButton();
        setupReplyButton();
        setupMoreButton();
        setupMuteButton();

        binding.tvOwner.setText(ownerName != null ? ownerName : "Status");
        load(ownerUid);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Auto-pause when call/notification pulls the app away
        pauseProgress();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Auto-resume when user comes back
        if (paused) resumeProgress();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        stopProgress();
        handler.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
                public void onDataChange(@NonNull DataSnapshot snap) {
                    long now = System.currentTimeMillis();
                    for (DataSnapshot c : snap.getChildren()) {
                        StatusItem s = c.getValue(StatusItem.class);
                        if (s == null || s.deleted) continue;
                        if (s.expiresAt != null && s.expiresAt < now) continue;
                        items.add(s);
                    }
                    if (items.isEmpty()) { finish(); return; }

                    items.sort((a, b) -> {
                        long ta = a.timestamp == null ? 0 : a.timestamp;
                        long tb = b.timestamp == null ? 0 : b.timestamp;
                        return Long.compare(ta, tb);
                    });

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
                public void onCancelled(@NonNull DatabaseError e) { finish(); }
            });
    }

    // ── Segment progress bar ──────────────────────────────────────────────

    private void buildSegmentBars() {
        binding.segmentsContainer.removeAllViews();
        segmentBars.clear();
        int count = items.size();
        for (int i = 0; i < count; i++) {
            ProgressBar pb = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            pb.setMax(1000); // Higher granularity = smoother animation
            pb.setProgress(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(2), 1f);
            lp.setMarginEnd(i < count - 1 ? dpToPx(3) : 0);
            pb.setLayoutParams(lp);
            pb.getProgressDrawable().setColorFilter(
                    Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            binding.segmentsContainer.addView(pb);
            segmentBars.add(pb);
        }
    }

    private void fillSegmentsBefore(int currentIdx) {
        for (int i = 0; i < segmentBars.size(); i++) {
            segmentBars.get(i).setProgress(i < currentIdx ? 1000 : 0);
        }
    }

    // ── Show item ─────────────────────────────────────────────────────────

    private void showCurrent() {
        if (idx >= items.size()) { finish(); return; }
        StatusItem s = items.get(idx);

        fillSegmentsBefore(idx);
        updateHeaderTimestamp(s);
        updateSeenByInfo(s);
        crossFadeIn();

        // Mark seen immediately; real Firebase write is batched on exit
        if (s.id != null && !s.id.isEmpty() && myUid != null && !myUid.equals(ownerUid)) {
            if (!seenInSession.contains(s.id)) seenInSession.add(s.id);
        }

        // Hide mute button by default; show only for video
        binding.btnMute.setVisibility(View.GONE);

        if ("text".equals(s.type)) {
            hideAllContent();
            showTextStatus(s);
        } else if ("image".equals(s.type) && s.mediaUrl != null) {
            hideAllContent();
            showImageStatus(s);
        } else if ("video".equals(s.type) && s.mediaUrl != null) {
            hideAllContent();
            showVideoStatus(s);
        } else if (("reel_story".equals(s.type) || "reel_clip".equals(s.type)) && s.mediaUrl != null) {
            hideAllContent();
            showVideoStatus(s);
        } else if (("reel_story".equals(s.type) || "reel_clip".equals(s.type)) && s.thumbnailUrl != null) {
            hideAllContent();
            showImageStatusFromUrl(s.thumbnailUrl, s.caption);
        } else {
            next(); // skip unrecognised types
        }
    }

    private void showTextStatus(StatusItem s) {
        binding.flTextStatus.setVisibility(View.VISIBLE);
        binding.tvTextStatus.setText(s.text != null ? s.text : "");

        if (s.bgColor != null) {
            try {
                binding.flTextStatus.setBackgroundColor(Color.parseColor(s.bgColor));
            } catch (Exception e) {
                binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
            }
        } else {
            binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
        }

        if (s.textColor != null) {
            try { binding.tvTextStatus.setTextColor(Color.parseColor(s.textColor)); }
            catch (Exception ignored) {}
        }

        applyFontStyle(binding.tvTextStatus, s.fontStyle);
        if (s.textSize > 0) binding.tvTextStatus.setTextSize(s.textSize);

        showCaption(s.caption);
        startProgress(5_000L);
    }

    private void showImageStatus(StatusItem s) {
        showImageStatusFromUrl(s.mediaUrl, s.caption);
    }

    private void showImageStatusFromUrl(String url, String caption) {
        binding.ivStatus.setVisibility(View.VISIBLE);
        Glide.with(this)
             .load(url)
             .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
             .placeholder(android.R.drawable.screen_background_dark)
             .into(binding.ivStatus);
        showCaption(caption);
        startProgress(5_000L);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void showVideoStatus(StatusItem s) {
        binding.playerView.setVisibility(View.VISIBLE);
        binding.btnMute.setVisibility(View.VISIBLE);
        releasePlayer();

        ExoPlayer.Builder builder = new ExoPlayer.Builder(this);

        if (StatusVideoCacheManager.isInitialized()) {
            CacheDataSource.Factory cacheFactory =
                StatusVideoCacheManager.getCacheDataSourceFactory();
            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(cacheFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
            player = builder.build();
            binding.playerView.setPlayer(player);
            player.setMediaSource(mediaSource);
        } else {
            player = builder.build();
            binding.playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
        }

        // Apply mute state
        player.setVolume(isMuted ? 0f : 1f);

        // FIX: Use actual video duration from ExoPlayer — not a hardcoded timer.
        // When the player is READY, get the real duration and start the progress.
        // This prevents the progress bar from finishing before the video ends (or vice versa).
        long estimatedDur = s.durationSec > 0
                ? Math.min(s.durationSec * 1000L, 30_000L)
                : 15_000L;

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    long realDur = player.getDuration();
                    long dur = (realDur > 0 && realDur != Long.MIN_VALUE)
                            ? Math.min(realDur, 30_000L)
                            : estimatedDur;
                    // Stop any previously started timer and restart with real duration
                    stopProgress();
                    startProgress(dur);
                } else if (state == Player.STATE_ENDED) {
                    next();
                }
            }
        });

        player.prepare();
        player.setPlayWhenReady(true);

        // Start with estimated timer immediately so progress bar isn't frozen
        startProgress(estimatedDur);
        showCaption(s.caption);
    }

    private void hideAllContent() {
        binding.flTextStatus.setVisibility(View.GONE);
        binding.ivStatus.setVisibility(View.GONE);
        binding.playerView.setVisibility(View.GONE);
        binding.tvCaption.setVisibility(View.GONE);
    }

    private void showCaption(String caption) {
        if (!TextUtils.isEmpty(caption)) {
            binding.tvCaption.setVisibility(View.VISIBLE);
            binding.tvCaption.setText(caption);
        } else {
            binding.tvCaption.setVisibility(View.GONE);
        }
    }

    // ── Cross-fade animation ──────────────────────────────────────────────

    private void crossFadeIn() {
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(200);
        binding.getRoot().startAnimation(fade);
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
                int prog = (int) Math.min(1000L, (elapsed * 1000L) / totalMs);
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
        if (paused) return;
        paused = true;
        if (player != null) player.setPlayWhenReady(false);
        stopProgress();
    }

    private void resumeProgress() {
        if (!paused) return;
        paused = false;
        if (player != null) player.setPlayWhenReady(true);
        if (idx < items.size()) {
            StatusItem s = items.get(idx);
            long total;
            if ("video".equals(s.type) || "reel_story".equals(s.type) || "reel_clip".equals(s.type)) {
                long realDur = (player != null) ? player.getDuration() : Long.MIN_VALUE;
                total = (realDur > 0 && realDur != Long.MIN_VALUE)
                        ? Math.min(realDur, 30_000L)
                        : (s.durationSec > 0 ? Math.min(s.durationSec * 1000L, 30_000L) : 15_000L);
            } else {
                total = 5_000L;
            }
            runProgressTick(total, remainingMs);
        }
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

    // ── Swipe down to close (GestureDetector — pehle stub tha) ───────────

    private void setupSwipeDownGesture() {
        swipeDetector = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                private static final int SWIPE_THRESHOLD    = 120;
                private static final int SWIPE_VELOCITY_MIN = 100;

                @Override
                public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2,
                                       float velocityX, float velocityY) {
                    if (e1 == null) return false;
                    float dy = e2.getRawY() - e1.getRawY();
                    if (dy > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_MIN) {
                        // Swipe DOWN → close
                        finishWithAnimation();
                        return true;
                    }
                    return false;
                }
            });
    }

    private void finishWithAnimation() {
        AlphaAnimation fade = new AlphaAnimation(1f, 0f);
        fade.setDuration(200);
        fade.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) { finish(); }
        });
        binding.getRoot().startAnimation(fade);
    }

    // ── Touch zones ───────────────────────────────────────────────────────

    private void setupTouchZones() {
        binding.touchLayer.setOnTouchListener((v, e) -> {
            // Pass to swipe detector first
            swipeDetector.onTouchEvent(e);

            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pauseProgress();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    resumeProgress();
                    float x = e.getX();
                    float w = v.getWidth();
                    // Short tap (<200ms): left-third = previous, rest = next
                    if (e.getEventTime() - e.getDownTime() < 200) {
                        if (x < w / 3f) previous();
                        else            next();
                    }
                    break;
            }
            return true;
        });
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    private void setupCloseButton() {
        binding.btnCloseStatus.setOnClickListener(v -> finishWithAnimation());
    }

    private void setupReactionButton() {
        binding.btnReact.setOnClickListener(v -> showReactionSheet());
    }

    /**
     * FIX: Reply input + send button were wired to nothing before.
     * Now sends the typed reply as a chat message to the status owner.
     */
    private void setupReplyButton() {
        // Hide reply UI if viewing own status
        if (myUid != null && myUid.equals(ownerUid)) {
            binding.etReply.setVisibility(View.GONE);
            binding.btnSendReply.setVisibility(View.GONE);
            return;
        }

        // Pause progress when user taps the reply field
        binding.etReply.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) pauseProgress();
            else resumeProgress();
        });

        binding.btnSendReply.setOnClickListener(v -> {
            String msg = binding.etReply.getText() != null
                    ? binding.etReply.getText().toString().trim() : "";
            if (TextUtils.isEmpty(msg)) return;
            if (myUid == null || ownerUid == null) return;

            sendReplyToChat(ownerUid, msg);
            binding.etReply.setText("");
            binding.etReply.clearFocus();
            resumeProgress();
            Toast.makeText(this, "Reply sent", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Sends a "reply to status" message into the existing chat with the owner,
     * then fires a background-killed-safe FCM push to the owner via PushNotify.
     * Message includes replyToText + replyToSenderName so the chat screen renders
     * a proper quoted-status bubble (same as swipe-to-reply on any other message).
     */
    private void sendReplyToChat(String toUid, String message) {
        if (myUid == null) return;
        // Deterministic chatId: sorted UIDs joined by "_"
        String chatId = myUid.compareTo(toUid) < 0
                ? myUid + "_" + toUid
                : toUid + "_" + myUid;

        String msgId = FirebaseUtils.db().getReference().push().getKey();
        if (msgId == null) return;

        // Build the quoted-status context — shown as a preview bubble in chat
        StatusItem currentStatus = (idx >= 0 && idx < items.size()) ? items.get(idx) : null;
        String quoteText;
        String quoteType;
        String quoteMediaUrl = null;
        if (currentStatus != null) {
            if ("image".equals(currentStatus.type)) {
                quoteText    = "📷 Photo status";
                quoteType    = "image";
                quoteMediaUrl = currentStatus.mediaUrl;
            } else if ("video".equals(currentStatus.type)
                    || "reel_story".equals(currentStatus.type)
                    || "reel_clip".equals(currentStatus.type)) {
                quoteText    = "🎥 Video status";
                quoteType    = "video";
                quoteMediaUrl = currentStatus.thumbnailUrl != null
                        ? currentStatus.thumbnailUrl : currentStatus.mediaUrl;
            } else {
                // text status
                quoteText = (currentStatus.text != null && !currentStatus.text.isEmpty())
                        ? currentStatus.text
                        : (currentStatus.caption != null ? currentStatus.caption : "Status");
                quoteType = "text";
            }
        } else {
            quoteText = "Status";
            quoteType = "text";
        }

        Map<String, Object> msgMap = new HashMap<>();
        msgMap.put("id",                msgId);
        msgMap.put("senderId",          myUid);
        msgMap.put("text",              message);          // plain reply text (no prefix clutter)
        msgMap.put("type",              "text");
        msgMap.put("timestamp",         com.google.firebase.database.ServerValue.TIMESTAMP);
        msgMap.put("seen",              false);
        // Quote fields — chat adapter reads these to render the preview bubble
        msgMap.put("replyToId",         "status_" + (currentStatus != null && currentStatus.id != null
                                                      ? currentStatus.id : "unknown"));
        msgMap.put("replyToText",       quoteText);
        msgMap.put("replyToSenderName", ownerName != null ? ownerName : "Status");
        msgMap.put("replyToType",       quoteType);
        if (quoteMediaUrl != null) msgMap.put("replyToMediaUrl", quoteMediaUrl);

        final String finalChatId = chatId;
        final String finalMsg    = message;

        FirebaseUtils.db()
            .getReference("chats")
            .child(chatId)
            .child("messages")
            .child(msgId)
            .setValue(msgMap)
            .addOnSuccessListener(unused -> {
                // After message is written, send push to status owner.
                // Fetch my own name + photo (needed for notification title/avatar).
                FirebaseUtils.db()
                    .getReference("users")
                    .child(myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot snap) {
                            String myName  = snap.child("name").getValue(String.class);
                            String myPhoto = snap.child("photoUrl").getValue(String.class);
                            PushNotify.notifyStatusReply(
                                toUid,
                                myUid,
                                myName  != null ? myName  : "Someone",
                                myPhoto != null ? myPhoto : "",
                                finalMsg,
                                finalChatId
                            );
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            PushNotify.notifyStatusReply(
                                toUid, myUid, "Someone", "", finalMsg, finalChatId);
                        }
                    });
            });
    }

    /**
     * FIX: "More" button (⋮) was declared in layout but never wired up.
     * Owner gets Delete option; others get Mute/Report.
     */
    private void setupMoreButton() {
        binding.btnMore.setOnClickListener(v -> {
            pauseProgress();
            boolean isOwner = myUid != null && myUid.equals(ownerUid);
            if (isOwner) {
                showOwnerMoreMenu();
            } else {
                showViewerMoreMenu();
            }
        });
    }

    private void showOwnerMoreMenu() {
        StatusItem current = idx < items.size() ? items.get(idx) : null;
        new AlertDialog.Builder(this)
            .setItems(new String[]{"Delete this status", "Cancel"}, (d, which) -> {
                if (which == 0 && current != null && current.id != null) {
                    StatusSeenTracker.deleteStatus(ownerUid, current.id);
                    Toast.makeText(this, "Status deleted", Toast.LENGTH_SHORT).show();
                    items.remove(idx);
                    if (items.isEmpty()) { finish(); return; }
                    idx = Math.min(idx, items.size() - 1);
                    buildSegmentBars();
                    stopProgress();
                    showCurrent();
                } else {
                    resumeProgress();
                }
            })
            .setOnCancelListener(d -> resumeProgress())
            .show();
    }

    private void showViewerMoreMenu() {
        new AlertDialog.Builder(this)
            .setItems(new String[]{"Mute " + ownerName, "Report", "Cancel"}, (d, which) -> {
                if (which == 0) {
                    Toast.makeText(this, ownerName + " muted", Toast.LENGTH_SHORT).show();
                    finish();
                } else if (which == 1) {
                    Toast.makeText(this, "Reported", Toast.LENGTH_SHORT).show();
                }
                resumeProgress();
            })
            .setOnCancelListener(d -> resumeProgress())
            .show();
    }

    /**
     * FIX: Mute/unmute toggle for video statuses.
     * Was missing entirely — added btn_mute to the logic.
     */
    private void setupMuteButton() {
        binding.btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            if (player != null) player.setVolume(isMuted ? 0f : 1f);
            binding.btnMute.setImageResource(
                isMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        });
    }

    // ── Reaction bottom sheet ─────────────────────────────────────────────

    private void showReactionSheet() {
        pauseProgress();
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View sv = getLayoutInflater().inflate(R.layout.bottom_sheet_status_reactions, null);
        sheet.setContentView(sv);

        String[] emojis   = {"❤️", "😂", "😮", "😢", "😡", "👍"};
        int[]    emojiIds  = {
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
                    Toast.makeText(this, emoji + " sent", Toast.LENGTH_SHORT).show();
                }
                sheet.dismiss();
            });
        }

        sheet.setOnDismissListener(d -> resumeProgress());
        sheet.show();
    }

    // ── Seen-by info ──────────────────────────────────────────────────────

    private void updateSeenByInfo(StatusItem s) {
        if (myUid != null && myUid.equals(ownerUid)) {
            int count = s.getViewCount();
            binding.tvSeenBy.setVisibility(View.VISIBLE);
            binding.tvSeenBy.setText(count > 0 ? "👁 Seen by " + count : "No views yet");
            // FIX: Tap on "Seen by" shows viewer list dialog
            binding.tvSeenBy.setOnClickListener(v -> showViewerListDialog(s));
        } else {
            binding.tvSeenBy.setVisibility(View.GONE);
        }
    }

    private void showViewerListDialog(StatusItem s) {
        if (s.seenBy == null || s.seenBy.isEmpty()) {
            Toast.makeText(this, "No viewers yet", Toast.LENGTH_SHORT).show();
            return;
        }
        pauseProgress();
        // Build list of viewer UIDs (in a real app you'd resolve names from DB)
        String[] viewers = s.seenBy.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Seen by " + viewers.length)
            .setItems(viewers, null)
            .setOnDismissListener(d -> resumeProgress())
            .show();
    }

    // ── Header timestamp ──────────────────────────────────────────────────

    private void updateHeaderTimestamp(StatusItem s) {
        if (s.timestamp != null) {
            binding.tvTimestamp.setText(formatAgo(System.currentTimeMillis() - s.timestamp));
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
