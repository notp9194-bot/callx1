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
import com.callx.app.utils.StatusAnalyticsTracker;
import com.callx.app.utils.StatusHighlightsManager;
import com.callx.app.utils.StatusMuteManager;
import com.callx.app.utils.StatusReactionAggregator;
import com.callx.app.utils.StatusSeenTracker;
import com.callx.app.utils.StatusShareManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.*;

import java.util.*;

/**
 * StatusViewerActivity — Production-grade story/status viewer.
 *
 * Features (complete):
 *   ✅ Multi-segment progress bar (one segment per status item)
 *   ✅ Tap right → advance; tap left → rewind; hold → pause
 *   ✅ Swipe down → close (GestureDetector)
 *   ✅ onPause/onResume — call/notification par auto-pause/resume
 *   ✅ Video: ExoPlayer cache-first (StatusVideoCacheManager)
 *   ✅ Video: actual duration from ExoPlayer player (not hardcoded timer)
 *   ✅ Video: mute/unmute toggle
 *   ✅ Image: Glide DiskCacheStrategy.ALL
 *   ✅ Text: background color + font style + text size
 *   ✅ Link: preview card (title + domain + thumbnail)
 *   ✅ Reply via chat (et_reply + btn_send_reply)
 *   ✅ Emoji reactions bottom sheet + reaction summary in seen-by row
 *   ✅ Owner: "Seen by N" tap → viewer list dialog with REAL NAMES + analytics
 *   ✅ Owner: delete status
 *   ✅ Owner: add to highlights album
 *   ✅ Owner: download status to gallery
 *   ✅ Share status via Android share sheet
 *   ✅ Mute contact (real persistence via StatusMuteManager — not just Toast)
 *   ✅ Seen tracking batched on exit
 *   ✅ View duration analytics (StatusAnalyticsTracker)
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
    private long                    currentItemTotalMs = 0; // for analytics

    private String myUid;
    private String ownerUid;
    private String ownerName;

    // Analytics: track per-item view start time
    private long itemViewStartMs = 0;

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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        setupDownloadButton();
        setupHighlightButton();
        setupShareButton();

        binding.tvOwner.setText(ownerName != null ? ownerName : "Status");
        load(ownerUid);
    }

    @Override
    protected void onPause() {
        super.onPause();
        endCurrentItemAnalytics();
        pauseProgress();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (paused) {
            resumeProgress();
            itemViewStartMs = StatusAnalyticsTracker.startView();
        }
    }

    @Override
    protected void onDestroy() {
        endCurrentItemAnalytics();
        releasePlayer();
        stopProgress();
        handler.removeCallbacksAndMessages(null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!seenInSession.isEmpty() && ownerUid != null) {
            String thumbForBubble = "";
            if (!items.isEmpty()) {
                StatusItem first = items.get(0);
                if (first.thumbnailUrl != null && !first.thumbnailUrl.isEmpty()) {
                    thumbForBubble = first.thumbnailUrl;
                } else if (first.mediaUrl != null && "image".equals(first.type)) {
                    thumbForBubble = first.mediaUrl;
                }
            }
            StatusSeenTracker.markSeenBatch(ownerUid, seenInSession,
                    ownerName != null ? ownerName : "", thumbForBubble);
        }
        super.onDestroy();
    }

    // ── Analytics helpers ─────────────────────────────────────────────────

    private void startCurrentItemAnalytics() {
        itemViewStartMs = StatusAnalyticsTracker.startView();
    }

    private void endCurrentItemAnalytics() {
        if (itemViewStartMs == 0 || idx >= items.size()) return;
        StatusItem current = items.get(idx);
        if (current.id == null) return;
        StatusAnalyticsTracker.endView(
                ownerUid, current.id, itemViewStartMs, currentItemTotalMs);
        itemViewStartMs = 0;
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
                    items.sort((a, b) -> Long.compare(
                        a.timestamp == null ? 0 : a.timestamp,
                        b.timestamp == null ? 0 : b.timestamp));

                    StatusItem first = items.get(0);
                    if (first.ownerPhoto != null && !first.ownerPhoto.isEmpty()) {
                        Glide.with(StatusViewerActivity.this)
                             .load(first.ownerPhoto).circleCrop().into(binding.ivOwner);
                    }
                    buildSegmentBars();
                    showCurrent();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finish(); }
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
            pb.setMax(1000);
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

        endCurrentItemAnalytics();
        fillSegmentsBefore(idx);
        updateHeaderTimestamp(s);
        updateSeenByInfo(s);
        crossFadeIn();
        startCurrentItemAnalytics();

        if (s.id != null && !s.id.isEmpty() && myUid != null && !myUid.equals(ownerUid)) {
            if (!seenInSession.contains(s.id)) seenInSession.add(s.id);
        }

        // Show/hide download + highlight buttons (owner only)
        boolean isOwner = myUid != null && myUid.equals(ownerUid);
        if (binding.btnDownload != null)
            binding.btnDownload.setVisibility(isOwner ? View.VISIBLE : View.GONE);
        if (binding.btnHighlight != null)
            binding.btnHighlight.setVisibility(isOwner ? View.VISIBLE : View.GONE);

        binding.btnMute.setVisibility(View.GONE);
        hideAllContent();

        if ("text".equals(s.type)) {
            showTextStatus(s);
        } else if ("image".equals(s.type) && s.mediaUrl != null) {
            showImageStatus(s);
        } else if ("video".equals(s.type) && s.mediaUrl != null) {
            showVideoStatus(s);
        } else if (("reel_story".equals(s.type) || "reel_clip".equals(s.type)) && s.mediaUrl != null) {
            showVideoStatus(s);
        } else if (("reel_story".equals(s.type) || "reel_clip".equals(s.type)) && s.thumbnailUrl != null) {
            showImageStatusFromUrl(s.thumbnailUrl, s.caption);
        } else if ("link".equals(s.type)) {
            showLinkStatus(s);
        } else {
            next();
        }
    }

    private void showTextStatus(StatusItem s) {
        binding.flTextStatus.setVisibility(View.VISIBLE);
        binding.tvTextStatus.setText(s.text != null ? s.text : "");
        if (s.bgColor != null) {
            try { binding.flTextStatus.setBackgroundColor(Color.parseColor(s.bgColor)); }
            catch (Exception e) { binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand); }
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
        currentItemTotalMs = 5_000L;
        startProgress(5_000L);
    }

    private void showImageStatus(StatusItem s) {
        showImageStatusFromUrl(s.mediaUrl, s.caption);
    }

    private void showImageStatusFromUrl(String url, String caption) {
        binding.ivStatus.setVisibility(View.VISIBLE);
        Glide.with(this).load(url)
             .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
             .placeholder(android.R.drawable.screen_background_dark)
             .into(binding.ivStatus);
        showCaption(caption);
        currentItemTotalMs = 5_000L;
        startProgress(5_000L);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void showVideoStatus(StatusItem s) {
        binding.playerView.setVisibility(View.VISIBLE);
        binding.btnMute.setVisibility(View.VISIBLE);
        releasePlayer();

        ExoPlayer.Builder builder = new ExoPlayer.Builder(this);

        if (StatusVideoCacheManager.isInitialized()) {
            CacheDataSource.Factory factory = StatusVideoCacheManager.getCacheDataSourceFactory();
            ProgressiveMediaSource src = new ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
            player = builder.build();
            binding.playerView.setPlayer(player);
            player.setMediaSource(src);
        } else {
            player = builder.build();
            binding.playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
        }

        player.setVolume(isMuted ? 0f : 1f);

        long estimatedDur = s.durationSec > 0
                ? Math.min(s.durationSec * 1000L, 30_000L) : 15_000L;
        currentItemTotalMs = estimatedDur;

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    long realDur = player.getDuration();
                    long dur = (realDur > 0 && realDur != Long.MIN_VALUE)
                            ? Math.min(realDur, 30_000L) : estimatedDur;
                    currentItemTotalMs = dur;
                    stopProgress();
                    startProgress(dur);
                } else if (state == Player.STATE_ENDED) {
                    next();
                }
            }
        });

        player.prepare();
        player.setPlayWhenReady(true);
        startProgress(estimatedDur);
        showCaption(s.caption);
    }

    private void showLinkStatus(StatusItem s) {
        // Display link title + domain + thumbnail in text area
        binding.flTextStatus.setVisibility(View.VISIBLE);
        String title = s.linkTitle != null ? s.linkTitle : s.linkUrl;
        String domain = s.linkDescription != null ? s.linkDescription : "";
        binding.tvTextStatus.setText(
            (title != null ? title : "") + (domain.isEmpty() ? "" : "\n\n" + domain));
        binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
        binding.tvTextStatus.setTextColor(Color.WHITE);
        // Show thumbnail if available
        if (s.thumbnailUrl != null && !s.thumbnailUrl.isEmpty()) {
            binding.ivStatus.setVisibility(View.VISIBLE);
            Glide.with(this).load(s.thumbnailUrl)
                 .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                 .into(binding.ivStatus);
        }
        showCaption(s.linkUrl);
        currentItemTotalMs = 7_000L;
        startProgress(7_000L);
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

    // ── Cross-fade ────────────────────────────────────────────────────────

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
        paused = true;
        if (player != null) player.setPlayWhenReady(false);
    }

    private void resumeProgress() {
        if (!paused) return;
        paused = false;
        if (player != null) player.setPlayWhenReady(true);
        long total = currentItemTotalMs > 0 ? currentItemTotalMs : 5_000L;
        runProgressTick(total, remainingMs);
    }

    private void next() {
        endCurrentItemAnalytics();
        stopProgress();
        releasePlayer();
        idx++;
        showCurrent();
    }

    private void prev() {
        endCurrentItemAnalytics();
        stopProgress();
        releasePlayer();
        if (idx > 0) idx--;
        showCurrent();
    }

    // ── Touch / gesture setup ─────────────────────────────────────────────

    private void setupSwipeDownGesture() {
        swipeDetector = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                private static final float SWIPE_THRESHOLD    = 100f;
                private static final float SWIPE_VELOCITY_THR = 100f;

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2,
                                       float vX, float vY) {
                    if (e1 == null || e2 == null) return false;
                    float dY = e2.getY() - e1.getY();
                    float dX = e2.getX() - e1.getX();
                    if (dY > SWIPE_THRESHOLD && Math.abs(dY) > Math.abs(dX)
                            && Math.abs(vY) > SWIPE_VELOCITY_THR) {
                        finish();
                        return true;
                    }
                    return false;
                }
            });
    }

    private void setupTouchZones() {
        binding.touchLayer.setOnTouchListener((v, event) -> {
            swipeDetector.onTouchEvent(event);
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pauseProgress();
                    return true;
                case MotionEvent.ACTION_UP:
                    resumeProgress();
                    float x = event.getX();
                    float w = v.getWidth();
                    if (event.getEventTime() - event.getDownTime() < 300L) {
                        if (x < w * 0.35f) prev();
                        else if (x > w * 0.65f) next();
                    }
                    return true;
            }
            return true;
        });
    }

    private void setupCloseButton() {
        binding.btnCloseStatus.setOnClickListener(v -> finish());
    }

    private void setupReactionButton() {
        binding.btnReact.setOnClickListener(v -> showReactionSheet());
    }

    // ── Download button ───────────────────────────────────────────────────

    private void setupDownloadButton() {
        if (binding.btnDownload == null) return;
        binding.btnDownload.setOnClickListener(v -> {
            if (idx >= items.size()) return;
            pauseProgress();
            StatusItem current = items.get(idx);
            Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();
            StatusShareManager.downloadToGallery(this, current,
                new StatusShareManager.DownloadCallback() {
                    @Override public void onSuccess(Uri savedUri) {
                        Toast.makeText(StatusViewerActivity.this,
                            "Saved to gallery", Toast.LENGTH_SHORT).show();
                        resumeProgress();
                    }
                    @Override public void onError(String message) {
                        Toast.makeText(StatusViewerActivity.this,
                            "Save failed: " + message, Toast.LENGTH_SHORT).show();
                        resumeProgress();
                    }
                });
        });
    }

    // ── Highlight button ──────────────────────────────────────────────────

    private void setupHighlightButton() {
        if (binding.btnHighlight == null) return;
        binding.btnHighlight.setOnClickListener(v -> {
            if (idx >= items.size()) return;
            pauseProgress();
            showAddToHighlightsDialog(items.get(idx));
        });
    }

    private void showAddToHighlightsDialog(StatusItem item) {
        if (myUid == null) { resumeProgress(); return; }
        StatusHighlightsManager.loadAlbums(myUid, albums -> {
            if (albums.isEmpty()) {
                // No albums — prompt to create one
                showCreateAlbumDialog(item, null);
                return;
            }
            String[] names = new String[albums.size() + 1];
            for (int i = 0; i < albums.size(); i++) names[i] = albums.get(i).name;
            names[albums.size()] = "+ New highlight";

            new AlertDialog.Builder(this)
                .setTitle("Add to Highlights")
                .setItems(names, (d, which) -> {
                    if (which == albums.size()) {
                        showCreateAlbumDialog(item, null);
                    } else {
                        StatusHighlightsManager.addStatus(myUid, albums.get(which).id, item);
                        Toast.makeText(this, "Added to " + albums.get(which).name,
                                Toast.LENGTH_SHORT).show();
                        resumeProgress();
                    }
                })
                .setOnCancelListener(dd -> resumeProgress())
                .show();
        });
    }

    private void showCreateAlbumDialog(StatusItem item, Runnable afterCreate) {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Highlight name (e.g. Travel)");
        new AlertDialog.Builder(this)
            .setTitle("New Highlight Album")
            .setView(et)
            .setPositiveButton("Create", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) { resumeProgress(); return; }
                String thumb = item.thumbnailUrl != null ? item.thumbnailUrl : item.mediaUrl;
                StatusHighlightsManager.createAlbum(myUid, name, thumb,
                    (success, albumId) -> {
                        if (success && albumId != null) {
                            StatusHighlightsManager.addStatus(myUid, albumId, item);
                            Toast.makeText(this, "Added to " + name, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Failed to create album", Toast.LENGTH_SHORT).show();
                        }
                        resumeProgress();
                    });
            })
            .setNegativeButton("Cancel", (d, w) -> resumeProgress())
            .setOnCancelListener(dd -> resumeProgress())
            .show();
    }

    // ── Share button ──────────────────────────────────────────────────────

    private void setupShareButton() {
        // Share is triggered from the "More" menu for non-owners
        // Owner share is in the owner more menu
    }

    // ── Reply button ──────────────────────────────────────────────────────

    private void setupReplyButton() {
        binding.btnSendReply.setOnClickListener(v -> {
            String msg = binding.etReply.getText().toString().trim();
            if (msg.isEmpty()) return;
            if (idx >= items.size()) return;
            StatusItem current = items.get(idx);
            if (myUid == null || ownerUid == null) return;

            binding.etReply.setText("");
            binding.etReply.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(binding.etReply.getWindowToken(), 0);

            String chatId = myUid.compareTo(ownerUid) < 0
                    ? myUid + "_" + ownerUid : ownerUid + "_" + myUid;
            String msgId = FirebaseUtils.db()
                    .getReference("messages").child(chatId).push().getKey();
            if (msgId == null) return;

            String finalMsg = msg;
            String finalChatId = chatId;
            String toUid = ownerUid;

            Map<String, Object> message = new HashMap<>();
            message.put("id",        msgId);
            message.put("senderId",  myUid);
            message.put("text",      finalMsg);
            message.put("type",      "text");
            message.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
            message.put("seen",      false);
            if (current.id != null) {
                message.put("replyToStatusId", current.id);
                message.put("replyToStatusType", current.type);
            }

            FirebaseUtils.db()
                .getReference("messages")
                .child(finalChatId)
                .child(msgId)
                .setValue(message);

            Toast.makeText(this, "Reply sent", Toast.LENGTH_SHORT).show();

            // FCM notification
            FirebaseUtils.db()
                .getReference("users").child(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String myName  = snap.child("name").getValue(String.class);
                        String myPhoto = snap.child("photoUrl").getValue(String.class);
                        String myThumb = snap.child("thumbUrl").getValue(String.class);
                        String avatar  = (myThumb != null && !myThumb.isEmpty()) ? myThumb : myPhoto;
                        PushNotify.notifyStatusReply(toUid, myUid,
                            myName != null ? myName : "Someone",
                            avatar != null ? avatar : "", finalMsg, finalChatId);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        PushNotify.notifyStatusReply(toUid, myUid, "Someone", "",
                            finalMsg, finalChatId);
                    }
                });
        });
    }

    // ── More button ───────────────────────────────────────────────────────

    private void setupMoreButton() {
        binding.btnMore.setOnClickListener(v -> {
            pauseProgress();
            if (myUid != null && myUid.equals(ownerUid)) showOwnerMoreMenu();
            else showViewerMoreMenu();
        });
    }

    private void showOwnerMoreMenu() {
        StatusItem current = idx < items.size() ? items.get(idx) : null;
        new AlertDialog.Builder(this)
            .setItems(new String[]{"Delete this status", "Share", "Cancel"}, (d, which) -> {
                if (which == 0 && current != null && current.id != null) {
                    StatusSeenTracker.deleteStatus(ownerUid, current.id);
                    Toast.makeText(this, "Status deleted", Toast.LENGTH_SHORT).show();
                    items.remove(idx);
                    if (items.isEmpty()) { finish(); return; }
                    idx = Math.min(idx, items.size() - 1);
                    buildSegmentBars();
                    stopProgress();
                    showCurrent();
                } else if (which == 1 && current != null) {
                    StatusShareManager.shareStatus(this, current);
                    resumeProgress();
                } else {
                    resumeProgress();
                }
            })
            .setOnCancelListener(d -> resumeProgress())
            .show();
    }

    private void showViewerMoreMenu() {
        boolean isMuted = StatusMuteManager.isMuted(this, ownerUid);
        String muteLabel = isMuted ? "Unmute " + ownerName : "Mute " + ownerName;
        new AlertDialog.Builder(this)
            .setItems(new String[]{muteLabel, "Share", "Report", "Cancel"}, (d, which) -> {
                if (which == 0) {
                    boolean nowMuted = StatusMuteManager.toggleMute(this, ownerUid);
                    Toast.makeText(this,
                        nowMuted ? ownerName + " muted" : ownerName + " unmuted",
                        Toast.LENGTH_SHORT).show();
                    if (nowMuted) finish();
                    else resumeProgress();
                } else if (which == 1) {
                    if (idx < items.size()) StatusShareManager.shareStatus(this, items.get(idx));
                    resumeProgress();
                } else if (which == 2) {
                    Toast.makeText(this, "Reported", Toast.LENGTH_SHORT).show();
                    resumeProgress();
                } else {
                    resumeProgress();
                }
            })
            .setOnCancelListener(d -> resumeProgress())
            .show();
    }

    // ── Mute button (video) ───────────────────────────────────────────────

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
                    // Notify owner + aggregate
                    FirebaseUtils.db()
                        .getReference("users").child(myUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                                String myName  = snap.child("name").getValue(String.class);
                                String myPhoto = snap.child("photoUrl").getValue(String.class);
                                StatusReactionAggregator.notifyOwner(
                                    StatusViewerActivity.this, ownerUid, current.id,
                                    myUid, myName, myPhoto, emoji);
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
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
            // Reaction summary
            String reactionSummary = StatusReactionAggregator.formatSummary(s);
            String seenText = "👁 Seen by " + count;
            if (!reactionSummary.isEmpty()) seenText += "  " + reactionSummary;
            if (count == 0) seenText = "No views yet";
            binding.tvSeenBy.setVisibility(View.VISIBLE);
            binding.tvSeenBy.setText(seenText);
            binding.tvSeenBy.setOnClickListener(v -> showViewerListDialog(s));
        } else {
            binding.tvSeenBy.setVisibility(View.GONE);
        }
    }

    /**
     * Shows viewer list with REAL NAMES resolved via StatusAnalyticsTracker.
     * Falls back to basic list from seenBy map if analytics not available.
     */
    private void showViewerListDialog(StatusItem s) {
        if ((s.seenBy == null || s.seenBy.isEmpty()) && s.id == null) {
            Toast.makeText(this, "No viewers yet", Toast.LENGTH_SHORT).show();
            return;
        }
        pauseProgress();

        if (s.id != null) {
            StatusAnalyticsTracker.loadViewerList(ownerUid, s.id, viewers -> {
                if (viewers.isEmpty()) {
                    // Fallback to seenBy map
                    showBasicViewerList(s);
                    return;
                }
                // Build display strings with name + duration
                String[] rows = new String[viewers.size()];
                for (int i = 0; i < viewers.size(); i++) {
                    StatusAnalyticsTracker.ViewerEntry e = viewers.get(i);
                    String dur = e.durationMs > 0
                        ? " — " + (e.durationMs / 1000) + "s" : "";
                    String comp = e.completed ? " ✓" : "";
                    rows[i] = (e.name != null ? e.name : "Unknown") + dur + comp;
                }
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                        .setTitle("Seen by " + viewers.size())
                        .setItems(rows, null)
                        .setOnDismissListener(d -> resumeProgress())
                        .show();
                });
            });
        } else {
            showBasicViewerList(s);
        }
    }

    private void showBasicViewerList(StatusItem s) {
        if (s.seenBy == null || s.seenBy.isEmpty()) {
            Toast.makeText(this, "No viewers yet", Toast.LENGTH_SHORT).show();
            resumeProgress();
            return;
        }
        // Resolve UIDs → names from Firebase users node
        List<String> uids = new ArrayList<>(s.seenBy.keySet());
        String[] names = new String[uids.size()];
        int[] resolved = {0};
        for (int i = 0; i < uids.size(); i++) {
            final int idx = i;
            names[idx] = uids.get(idx); // default to UID
            FirebaseUtils.db().getReference("users").child(uids.get(idx))
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String n = snap.child("name").getValue(String.class);
                        names[idx] = n != null ? n : uids.get(idx);
                        resolved[0]++;
                        if (resolved[0] == uids.size()) {
                            runOnUiThread(() -> {
                                new AlertDialog.Builder(StatusViewerActivity.this)
                                    .setTitle("Seen by " + uids.size())
                                    .setItems(names, null)
                                    .setOnDismissListener(d -> resumeProgress())
                                    .show();
                            });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        resolved[0]++;
                        if (resolved[0] == uids.size()) {
                            runOnUiThread(() -> {
                                new AlertDialog.Builder(StatusViewerActivity.this)
                                    .setTitle("Seen by " + uids.size())
                                    .setItems(names, null)
                                    .setOnDismissListener(d -> resumeProgress())
                                    .show();
                            });
                        }
                    }
                });
        }
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
        if (player != null) { player.release(); player = null; }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void applyFontStyle(TextView tv, String style) {
        if (style == null) return;
        switch (style) {
            case "bold":        tv.setTypeface(null, android.graphics.Typeface.BOLD);   break;
            case "italic":      tv.setTypeface(null, android.graphics.Typeface.ITALIC); break;
            case "handwriting": tv.setTypeface(android.graphics.Typeface.MONOSPACE);    break;
            default:            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
    }
}
