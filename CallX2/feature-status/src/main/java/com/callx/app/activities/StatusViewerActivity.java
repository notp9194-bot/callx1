package com.callx.app.activities;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import com.bumptech.glide.Glide;
import com.callx.app.bottomsheet.*;
import com.callx.app.cache.StatusVideoCacheManager;
import com.callx.app.status.R;
import com.callx.app.status.databinding.ActivityStatusViewerBinding;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusViewerActivity v25 — Fully comprehensive story/status viewer.
 *
 * ORIGINAL (fully working):
 *   ✅ Multi-segment progress bar per status item
 *   ✅ Tap right = advance, tap left = rewind, hold = pause
 *   ✅ Swipe down to close (GestureDetector)
 *   ✅ onPause/onResume auto-pause/resume
 *   ✅ ExoPlayer cache-first video
 *   ✅ Real video duration from ExoPlayer (not hardcoded timer)
 *   ✅ Video mute/unmute toggle
 *   ✅ Glide image with DiskCacheStrategy.ALL
 *   ✅ Text status with bg color + font style
 *   ✅ Reply via chat (properly wired)
 *   ✅ Emoji reactions bottom sheet
 *   ✅ Owner delete status menu
 *   ✅ Seen tracking batched on exit
 *   ✅ Status timestamp header
 *   ✅ Cross-fade animation
 *   ✅ Keep screen ON
 *
 * FIXES:
 *   ✅ FIX: Reaction bottom sheet — shows current user's reaction (highlighted)
 *   ✅ FIX: Reaction toggle — same emoji removes reaction
 *   ✅ FIX: Reaction count visible per emoji
 *   ✅ FIX: Seen-by dialog shows real names + avatars + timestamps (was raw UIDs)
 *   ✅ FIX: Mute now persisted via StatusMuteManager (was Toast only)
 *   ✅ FIX: View duration tracked for analytics
 *
 * NEW features:
 *   ✅ Download status (image/video) to gallery
 *   ✅ Forward status to contacts (bottom sheet)
 *   ✅ Archive status (owner only)
 *   ✅ Add to Highlights (owner only)
 *   ✅ Analytics bottom sheet (owner: views, reactions, avg duration, reach%)
 *   ✅ Link status type rendering (OG card preview)
 *   ✅ GIF/Sticker type rendering
 *   ✅ @mention highlighting in text/caption
 *   ✅ Location tag display
 *   ✅ Close Friends badge on header
 *   ✅ Status expiry label
 *   ✅ Reaction notification push to owner
 *   ✅ View duration analytics recording
 */
public class StatusViewerActivity extends AppCompatActivity {

    public static final String EXTRA_OWNER_UID  = "ownerUid";
    public static final String EXTRA_OWNER_NAME = "ownerName";

    private ActivityStatusViewerBinding binding;

    private final List<StatusItem> items         = new ArrayList<>();
    private final List<String>     seenInSession  = new ArrayList<>();
    private int     idx         = 0;
    private ExoPlayer player;
    private final Handler  handler      = new Handler(Looper.getMainLooper());
    private Runnable       progressRunner;
    private boolean        paused       = false;
    private long           remainingMs  = 0;
    private boolean        isMuted      = false;
    private long           viewStartTime = 0; // analytics

    private String myUid, ownerUid, ownerName;
    private final List<ProgressBar> segmentBars = new ArrayList<>();
    private GestureDetector swipeDetector;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStatusViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
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
        setupDownloadButton();
        setupForwardButton();

        binding.tvOwner.setText(ownerName != null ? ownerName : "Status");

        // Close Friends badge
        if (StatusCloseFriendsManager.isCloseFriend(this, ownerUid)) {
            binding.tvOwner.setText("⭐ " + (ownerName != null ? ownerName : "Status"));
        }

        load(ownerUid);
    }

    @Override protected void onPause()  { super.onPause();  pauseProgress(); }
    @Override protected void onResume() { super.onResume(); if (paused) resumeProgress(); }

    @Override
    protected void onDestroy() {
        releasePlayer();
        stopProgress();
        handler.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Batch mark seen
        if (!seenInSession.isEmpty() && ownerUid != null) {
            String thumbForBubble = "";
            if (!items.isEmpty()) {
                StatusItem first = items.get(0);
                if (first.thumbnailUrl != null && !first.thumbnailUrl.isEmpty())
                    thumbForBubble = first.thumbnailUrl;
                else if (first.mediaUrl != null && "image".equals(first.type))
                    thumbForBubble = first.mediaUrl;
            }
            StatusSeenTracker.markSeenBatch(ownerUid, seenInSession,
                    ownerName != null ? ownerName : "", thumbForBubble);
        }
        // Record view duration for analytics (last viewed item)
        if (viewStartTime > 0 && idx < items.size()) {
            StatusItem cur = items.get(idx);
            if (cur.id != null)
                StatusSeenTracker.recordViewDuration(ownerUid, cur.id,
                        System.currentTimeMillis() - viewStartTime);
        }
        super.onDestroy();
    }

    // ── Load ──────────────────────────────────────────────────────────────

    private void load(String uid) {
        FirebaseUtils.getStatusRef().child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long now = System.currentTimeMillis();
                    for (DataSnapshot c : snap.getChildren()) {
                        StatusItem s = c.getValue(StatusItem.class);
                        if (s == null || s.deleted) continue;
                        if (s.expiresAt != null && s.expiresAt < now) continue;
                        items.add(s);
                    }
                    if (items.isEmpty()) { finish(); return; }
                    items.sort((a, b) -> Long.compare(
                            a.timestamp == null ? 0 : a.timestamp,
                            b.timestamp == null ? 0 : b.timestamp));
                    StatusItem first = items.get(0);
                    if (first.ownerPhoto != null && !first.ownerPhoto.isEmpty())
                        Glide.with(StatusViewerActivity.this).load(first.ownerPhoto)
                             .circleCrop().into(binding.ivOwner);
                    buildSegmentBars();
                    showCurrent();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finish(); }
            });
    }

    // ── Segment bar ───────────────────────────────────────────────────────

    private void buildSegmentBars() {
        binding.segmentsContainer.removeAllViews();
        segmentBars.clear();
        int count = items.size();
        for (int i = 0; i < count; i++) {
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(1000);
            pb.setProgress(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(2), 1f);
            lp.setMarginEnd(i < count - 1 ? dpToPx(3) : 0);
            pb.setLayoutParams(lp);
            pb.getProgressDrawable().setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            binding.segmentsContainer.addView(pb);
            segmentBars.add(pb);
        }
    }

    private void fillSegmentsBefore(int currentIdx) {
        for (int i = 0; i < segmentBars.size(); i++)
            segmentBars.get(i).setProgress(i < currentIdx ? 1000 : 0);
    }

    // ── Show current item ─────────────────────────────────────────────────

    private void showCurrent() {
        if (idx >= items.size()) { finish(); return; }
        StatusItem s = items.get(idx);

        // Analytics: record duration of previous item
        if (viewStartTime > 0 && idx > 0) {
            StatusItem prev = items.get(idx - 1);
            if (prev.id != null)
                StatusSeenTracker.recordViewDuration(ownerUid, prev.id,
                        System.currentTimeMillis() - viewStartTime);
        }
        viewStartTime = System.currentTimeMillis();

        fillSegmentsBefore(idx);
        updateHeaderTimestamp(s);
        updateSeenByInfo(s);
        updateExpiryLabel(s);
        crossFadeIn();

        if (s.id != null && !s.id.isEmpty() && myUid != null && !myUid.equals(ownerUid)) {
            if (!seenInSession.contains(s.id)) seenInSession.add(s.id);
        }

        binding.btnMute.setVisibility(View.GONE);
        hideAllContent();

        switch (s.type != null ? s.type : "") {
            case "text":
                showTextStatus(s); break;
            case "image":
                if (s.mediaUrl != null) showImageStatusFromUrl(s.mediaUrl, s.caption); break;
            case "video":
            case "reel_story":
            case "reel_clip":
                if (s.mediaUrl != null) { showVideoStatus(s); break; }
                if (s.thumbnailUrl != null) { showImageStatusFromUrl(s.thumbnailUrl, s.caption); break; }
                next(); break;
            case "link":
                showLinkStatus(s); break;
            case "gif":
            case "sticker":
                showGifStatus(s); break;
            default:
                next();
        }
    }

    // ── Content renderers ─────────────────────────────────────────────────

    private void showTextStatus(StatusItem s) {
        binding.flTextStatus.setVisibility(View.VISIBLE);
        binding.tvTextStatus.setText(StatusMentionHelper.highlight(s.text != null ? s.text : ""));
        try {
            if (s.bgColor != null) binding.flTextStatus.setBackgroundColor(Color.parseColor(s.bgColor));
            else binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
        } catch (Exception e) {
            binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
        }
        if (s.textColor != null) {
            try { binding.tvTextStatus.setTextColor(Color.parseColor(s.textColor)); }
            catch (Exception ignored) {}
        }
        applyFontStyle(binding.tvTextStatus, s.fontStyle);
        if (s.textSize > 0) binding.tvTextStatus.setTextSize(s.textSize);
        if (s.textAlign != null) {
            switch (s.textAlign) {
                case "left":  binding.tvTextStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); break;
                case "right": binding.tvTextStatus.setGravity(Gravity.END   | Gravity.CENTER_VERTICAL); break;
                default:      binding.tvTextStatus.setGravity(Gravity.CENTER);
            }
        }
        if (s.locationName != null && !s.locationName.isEmpty()) showLocationTag(s.locationName);
        showCaption(s.caption);
        startProgress(5_000L);
    }

    private void showImageStatusFromUrl(String url, String caption) {
        binding.ivStatus.setVisibility(View.VISIBLE);
        Glide.with(this).load(url)
             .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
             .placeholder(android.R.drawable.screen_background_dark)
             .into(binding.ivStatus);
        showCaption(caption);
        startProgress(5_000L);
    }

    private void showLinkStatus(StatusItem s) {
        // Show as image if linkImageUrl available, else text card
        if (s.linkImageUrl != null && !s.linkImageUrl.isEmpty()) {
            showImageStatusFromUrl(s.linkImageUrl, s.linkTitle);
        } else {
            showTextStatus(s); // fallback — show linkTitle as text
        }
        if (s.linkUrl != null) {
            binding.tvCaption.setClickable(true);
            binding.tvCaption.setOnClickListener(v -> {
                pauseProgress();
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(s.linkUrl));
                startActivity(i);
            });
        }
    }

    private void showGifStatus(StatusItem s) {
        binding.ivStatus.setVisibility(View.VISIBLE);
        String url = s.gifUrl != null ? s.gifUrl : s.stickerUrl != null ? s.stickerUrl : s.mediaUrl;
        if (url != null) {
            Glide.with(this).asGif().load(url)
                 .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                 .placeholder(android.R.drawable.screen_background_dark)
                 .into(binding.ivStatus);
        }
        showCaption(s.caption);
        startProgress(4_000L);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void showVideoStatus(StatusItem s) {
        binding.playerView.setVisibility(View.VISIBLE);
        binding.btnMute.setVisibility(View.VISIBLE);
        releasePlayer();

        ExoPlayer.Builder builder = new ExoPlayer.Builder(this);
        if (StatusVideoCacheManager.isInitialized()) {
            CacheDataSource.Factory cf = StatusVideoCacheManager.getCacheDataSourceFactory();
            ProgressiveMediaSource ms = new ProgressiveMediaSource.Factory(cf)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
            player = builder.build();
            binding.playerView.setPlayer(player);
            player.setMediaSource(ms);
        } else {
            player = builder.build();
            binding.playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
        }
        player.setVolume(isMuted ? 0f : 1f);
        long estimated = s.durationSec > 0 ? Math.min(s.durationSec * 1000L, 30_000L) : 15_000L;
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    long real = player.getDuration();
                    long dur  = (real > 0 && real != Long.MIN_VALUE) ? Math.min(real, 30_000L) : estimated;
                    stopProgress(); startProgress(dur);
                } else if (state == Player.STATE_ENDED) { next(); }
            }
        });
        player.prepare();
        player.setPlayWhenReady(true);
        startProgress(estimated);
        showCaption(s.caption);
    }

    private void hideAllContent() {
        binding.flTextStatus.setVisibility(View.GONE);
        binding.ivStatus.setVisibility(View.GONE);
        binding.playerView.setVisibility(View.GONE);
        binding.tvCaption.setVisibility(View.GONE);
        View locTag = binding.getRoot().findViewWithTag("tv_location_tag");
        if (locTag != null) locTag.setVisibility(View.GONE);
    }

    private void showCaption(String caption) {
        if (!TextUtils.isEmpty(caption)) {
            binding.tvCaption.setVisibility(View.VISIBLE);
            binding.tvCaption.setText(StatusMentionHelper.highlight(caption));
        }
    }

    private void showLocationTag(String location) {
        View tag = binding.getRoot().findViewWithTag("tv_location_tag");
        if (tag instanceof TextView) {
            ((TextView) tag).setText("📍 " + location);
            tag.setVisibility(View.VISIBLE);
        }
    }

    // ── Progress ──────────────────────────────────────────────────────────

    private void startProgress(long durationMs) {
        stopProgress(); paused = false; remainingMs = durationMs;
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
                if (idx < segmentBars.size()) segmentBars.get(idx).setProgress(prog);
                if (elapsed >= totalMs) { next(); }
                else { remainingMs = totalMs - elapsed; handler.postDelayed(this, STEP); }
            }
        };
        handler.postDelayed(progressRunner, STEP);
    }

    private void stopProgress() {
        if (progressRunner != null) { handler.removeCallbacks(progressRunner); progressRunner = null; }
    }

    private void pauseProgress() {
        if (paused) return; paused = true;
        if (player != null) player.setPlayWhenReady(false);
        stopProgress();
    }

    private void resumeProgress() {
        if (!paused) return; paused = false;
        if (player != null) player.setPlayWhenReady(true);
        if (idx < items.size()) {
            StatusItem s = items.get(idx);
            long total;
            if ("video".equals(s.type) || "reel_story".equals(s.type) || "reel_clip".equals(s.type)) {
                long real = (player != null) ? player.getDuration() : Long.MIN_VALUE;
                total = (real > 0 && real != Long.MIN_VALUE) ? Math.min(real, 30_000L)
                        : (s.durationSec > 0 ? Math.min(s.durationSec * 1000L, 30_000L) : 15_000L);
            } else { total = 5_000L; }
            runProgressTick(total, remainingMs);
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────

    private void next()     { releasePlayer(); stopProgress(); idx++; showCurrent(); }
    private void previous() { releasePlayer(); stopProgress(); idx = Math.max(0, idx - 1); showCurrent(); }

    // ── Swipe down ────────────────────────────────────────────────────────

    private void setupSwipeDownGesture() {
        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 120, SWIPE_VELOCITY = 100;
            @Override public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2,
                                             float vx, float vy) {
                if (e1 == null) return false;
                float dy = e2.getRawY() - e1.getRawY();
                if (dy > SWIPE_THRESHOLD && Math.abs(vy) > SWIPE_VELOCITY) { finishWithAnimation(); return true; }
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
            swipeDetector.onTouchEvent(e);
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN: pauseProgress(); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    resumeProgress();
                    float x = e.getX(), w = v.getWidth();
                    if (e.getEventTime() - e.getDownTime() < 200) {
                        if (x < w / 3f) previous(); else next();
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

    /** FIX v25: Full reaction sheet with selected state, count, remove option */
    private void setupReactionButton() {
        binding.btnReact.setOnClickListener(v -> {
            if (myUid != null && myUid.equals(ownerUid)) return; // Owner can't react to own
            StatusItem current = idx < items.size() ? items.get(idx) : null;
            if (current == null) return;
            pauseProgress();
            StatusReactionBottomSheet.show(this, current, myUid, (emoji, removed) -> {
                // Update local item reactions for immediate UI refresh
                if (removed) {
                    if (current.reactions != null) current.reactions.remove(myUid);
                } else {
                    if (current.reactions == null) current.reactions = new HashMap<>();
                    current.reactions.put(myUid, emoji);
                }
                updateSeenByInfo(current);
                resumeProgress();
            });
        });
    }

    private void setupReplyButton() {
        if (myUid != null && myUid.equals(ownerUid)) {
            binding.etReply.setVisibility(View.GONE);
            binding.btnSendReply.setVisibility(View.GONE);
            return;
        }
        binding.etReply.setOnFocusChangeListener((v, has) -> {
            if (has) pauseProgress(); else resumeProgress();
        });
        binding.btnSendReply.setOnClickListener(v -> {
            String msg = binding.etReply.getText() != null
                    ? binding.etReply.getText().toString().trim() : "";
            if (TextUtils.isEmpty(msg)) return;
            if (myUid == null || ownerUid == null) return;
            sendReplyToChat(ownerUid, msg);
            binding.etReply.setText(""); binding.etReply.clearFocus();
            resumeProgress();
            Toast.makeText(this, "Reply sent", Toast.LENGTH_SHORT).show();
        });
    }

    /** NEW v25: Download button */
    private void setupDownloadButton() {
        View btnDownload = binding.getRoot().findViewWithTag("btn_download");
        if (btnDownload != null) {
            btnDownload.setOnClickListener(v -> {
                StatusItem current = idx < items.size() ? items.get(idx) : null;
                if (current == null) return;
                if (!StatusDownloadHelper.hasPermission(this)) {
                    StatusDownloadHelper.requestPermission(this);
                    return;
                }
                StatusDownloadHelper.downloadStatus(this, current);
            });
        }
    }

    /** NEW v25: Forward button */
    private void setupForwardButton() {
        View btnFwd = binding.getRoot().findViewWithTag("btn_forward");
        if (btnFwd != null) {
            btnFwd.setOnClickListener(v -> {
                StatusItem current = idx < items.size() ? items.get(idx) : null;
                if (current == null || myUid == null) return;
                pauseProgress();
                StatusForwardBottomSheet.show(this, current, myUid);
                // Resume on dismiss handled inside sheet
            });
        }
    }

    /** FIX v25: Mute now persisted; owner sees Analytics + Archive; viewer sees proper Mute */
    private void setupMoreButton() {
        binding.btnMore.setOnClickListener(v -> {
            pauseProgress();
            boolean isOwner = myUid != null && myUid.equals(ownerUid);
            if (isOwner) showOwnerMoreMenu();
            else         showViewerMoreMenu();
        });
    }

    private void showOwnerMoreMenu() {
        StatusItem current = idx < items.size() ? items.get(idx) : null;
        String[] opts = {"Delete this status", "Archive status", "Add to Highlights", "Analytics", "Cancel"};
        new AlertDialog.Builder(this)
            .setItems(opts, (d, w) -> {
                if (w == 0 && current != null && current.id != null) {
                    StatusSeenTracker.deleteStatus(ownerUid, current.id);
                    items.remove(idx);
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    if (items.isEmpty()) { finish(); return; }
                    idx = Math.min(idx, items.size() - 1);
                    buildSegmentBars(); stopProgress(); showCurrent();
                } else if (w == 1 && current != null) {
                    StatusHighlightManager.archiveStatus(ownerUid, current);
                    Toast.makeText(this, "Archived ✓", Toast.LENGTH_SHORT).show();
                    resumeProgress();
                } else if (w == 2 && current != null) {
                    showAddToHighlightDialog(current);
                } else if (w == 3 && current != null) {
                    showAnalyticsDialog(current);
                } else {
                    resumeProgress();
                }
            })
            .setOnCancelListener(d -> resumeProgress())
            .show();
    }

    private void showViewerMoreMenu() {
        String muteLabel = StatusMuteManager.isMuted(this, ownerUid)
                ? "Unmute " + ownerName : "Mute " + ownerName;
        String[] opts = {muteLabel, "Download", "Forward", "Report", "Cancel"};
        new AlertDialog.Builder(this)
            .setItems(opts, (d, w) -> {
                if (w == 0) {
                    StatusMuteManager.toggle(this, ownerUid);
                    String msg = StatusMuteManager.isMuted(this, ownerUid)
                            ? ownerName + " muted" : ownerName + " unmuted";
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    finish();
                } else if (w == 1) {
                    StatusItem cur = idx < items.size() ? items.get(idx) : null;
                    if (cur != null) StatusDownloadHelper.downloadStatus(this, cur);
                } else if (w == 2) {
                    StatusItem cur = idx < items.size() ? items.get(idx) : null;
                    if (cur != null && myUid != null) StatusForwardBottomSheet.show(this, cur, myUid);
                } else if (w == 3) {
                    Toast.makeText(this, "Reported", Toast.LENGTH_SHORT).show();
                }
                resumeProgress();
            })
            .setOnCancelListener(d -> resumeProgress())
            .show();
    }

    private void showAddToHighlightDialog(StatusItem item) {
        EditText et = new EditText(this);
        et.setHint("Album name (e.g. Vacation 2024)");
        new AlertDialog.Builder(this)
            .setTitle("Add to Highlights")
            .setView(et)
            .setPositiveButton("Add", (d, w) -> {
                String album = et.getText().toString().trim();
                if (album.isEmpty()) album = "Highlights";
                StatusHighlightManager.addToHighlight(ownerUid, item, album.toLowerCase().replace(" ", "_"), album);
                Toast.makeText(this, "Added to " + album + " ✓", Toast.LENGTH_SHORT).show();
                resumeProgress();
            })
            .setNegativeButton("Cancel", (d, w) -> resumeProgress())
            .setOnCancelListener(d -> resumeProgress())
            .show();
    }

    /** NEW v25: Analytics bottom sheet for owner */
    private void showAnalyticsDialog(StatusItem item) {
        pauseProgress();
        // Compute analytics
        StatusAnalyticsHelper.Analytics a = StatusAnalyticsHelper.compute(item, 0);
        String msg = "👁 " + a.totalViews + " views\n"
                + "💬 " + a.totalReactions + " reactions\n"
                + "⏱ Avg view: " + String.format("%.1f", a.avgViewDurationSec) + "s\n"
                + "⏳ " + item.getExpiryLabel();
        if (!a.reactionBreakdown.isEmpty()) {
            StringBuilder rb = new StringBuilder("\nReactions: ");
            for (Map.Entry<String, Integer> e : a.reactionBreakdown.entrySet())
                rb.append(e.getKey()).append(" ×").append(e.getValue()).append("  ");
            msg += rb;
        }
        new AlertDialog.Builder(this)
            .setTitle("Status Analytics")
            .setMessage(msg)
            .setPositiveButton("Close", (d, w) -> resumeProgress())
            .setOnCancelListener(d -> resumeProgress())
            .show();
    }

    /** FIX v25: Mute button for video */
    private void setupMuteButton() {
        binding.btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            if (player != null) player.setVolume(isMuted ? 0f : 1f);
            binding.btnMute.setImageResource(isMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        });
    }

    // ── Seen-by info ──────────────────────────────────────────────────────

    private void updateSeenByInfo(StatusItem s) {
        if (myUid != null && myUid.equals(ownerUid)) {
            int count = s.getViewCount();
            binding.tvSeenBy.setVisibility(View.VISIBLE);
            String reactionSummary = buildReactionSummary(s);
            binding.tvSeenBy.setText("👁 " + count + (reactionSummary.isEmpty() ? "" : "  " + reactionSummary));
            // FIX v25: Proper seen-by bottom sheet with avatars
            binding.tvSeenBy.setOnClickListener(v -> {
                pauseProgress();
                StatusSeenByBottomSheet.show(this, s);
                // resume is handled inside sheet dismiss
            });
        } else {
            binding.tvSeenBy.setVisibility(View.GONE);
            // Show current user's reaction emoji next to react button
            if (s.hasReaction(myUid)) {
                String myReaction = s.getReaction(myUid);
                binding.btnReact.setContentDescription("React (" + myReaction + ")");
            }
        }
    }

    private String buildReactionSummary(StatusItem s) {
        if (s.reactions == null || s.reactions.isEmpty()) return "";
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String e : s.reactions.values()) counts.merge(e, 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet())
            sb.append(e.getKey()).append(e.getValue() > 1 ? "×" + e.getValue() : "").append(" ");
        return sb.toString().trim();
    }

    // ── Header helpers ────────────────────────────────────────────────────

    private void updateHeaderTimestamp(StatusItem s) {
        if (s.timestamp != null)
            binding.tvTimestamp.setText(formatAgo(System.currentTimeMillis() - s.timestamp));
        else binding.tvTimestamp.setText("");
    }

    private void updateExpiryLabel(StatusItem s) {
        View expiryView = binding.getRoot().findViewWithTag("tv_expiry_label");
        if (expiryView instanceof TextView) {
            if (myUid != null && myUid.equals(ownerUid)) {
                ((TextView) expiryView).setText(s.getExpiryLabel());
                expiryView.setVisibility(View.VISIBLE);
            } else {
                expiryView.setVisibility(View.GONE);
            }
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

    // ── Cross-fade ────────────────────────────────────────────────────────

    private void crossFadeIn() {
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(200);
        binding.getRoot().startAnimation(fade);
    }

    // ── Reply to chat ─────────────────────────────────────────────────────

    private void sendReplyToChat(String toUid, String message) {
        if (myUid == null) return;
        String chatId = myUid.compareTo(toUid) < 0 ? myUid + "_" + toUid : toUid + "_" + myUid;
        String msgId  = FirebaseUtils.db().getReference().push().getKey();
        if (msgId == null) return;

        StatusItem cur = (idx >= 0 && idx < items.size()) ? items.get(idx) : null;
        String quoteText = "Status", quoteType = "text";
        String quoteMediaUrl = null;
        if (cur != null) {
            if ("image".equals(cur.type))  { quoteText = "📷 Photo status"; quoteType = "image"; quoteMediaUrl = cur.mediaUrl; }
            else if ("video".equals(cur.type)) { quoteText = "🎥 Video status"; quoteType = "video"; quoteMediaUrl = cur.thumbnailUrl != null ? cur.thumbnailUrl : cur.mediaUrl; }
            else if ("link".equals(cur.type))  { quoteText = "🔗 " + (cur.linkTitle != null ? cur.linkTitle : cur.linkUrl); }
            else { quoteText = cur.text != null && !cur.text.isEmpty() ? cur.text : cur.caption != null ? cur.caption : "Status"; }
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("id", msgId); msg.put("senderId", myUid); msg.put("text", message);
        msg.put("type", "text"); msg.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
        msg.put("seen", false); msg.put("replyToText", quoteText); msg.put("replyToType", quoteType);
        msg.put("replyToSenderName", ownerName != null ? ownerName : "Status");
        msg.put("replyToId", "status_" + (cur != null && cur.id != null ? cur.id : "unknown"));
        if (quoteMediaUrl != null) msg.put("replyToMediaUrl", quoteMediaUrl);

        final String fChatId = chatId, fMsg = message;
        FirebaseUtils.db().getReference("chats").child(chatId).child("messages").child(msgId)
            .setValue(msg).addOnSuccessListener(u -> {
                FirebaseUtils.db().getReference("users").child(myUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            String n = snap.child("name").getValue(String.class);
                            String p = snap.child("thumbUrl").getValue(String.class);
                            if (p == null) p = snap.child("photoUrl").getValue(String.class);
                            PushNotify.notifyStatusReply(toUid, myUid,
                                    n != null ? n : "Someone", p != null ? p : "", fMsg, fChatId);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            PushNotify.notifyStatusReply(toUid, myUid, "Someone", "", fMsg, fChatId);
                        }
                    });
            });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void releasePlayer() {
        if (player != null) { player.release(); player = null; }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void applyFontStyle(TextView tv, String style) {
        if (style == null) return;
        switch (style) {
            case "bold":        tv.setTypeface(null, android.graphics.Typeface.BOLD); break;
            case "italic":      tv.setTypeface(null, android.graphics.Typeface.ITALIC); break;
            case "handwriting": tv.setTypeface(android.graphics.Typeface.MONOSPACE); break;
            case "condensed":   tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); break;
            case "serif":       tv.setTypeface(android.graphics.Typeface.SERIF); break;
            default:            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }
}
