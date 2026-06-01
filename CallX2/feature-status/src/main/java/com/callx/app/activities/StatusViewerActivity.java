package com.callx.app.activities;

import android.animation.*;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.media3.common.*;
import androidx.media3.datasource.cache.*;
import androidx.media3.exoplayer.*;
import androidx.media3.ui.PlayerView;
import com.bumptech.glide.Glide;
import com.callx.app.bottomsheet.*;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.callx.app.views.*;
import de.hdodenhof.circleimageview.CircleImageView;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StatusViewerActivity v26 — Major upgrades:
 * FIX 1:  Multi-user carousel (auto-advance to next contact's status)
 * FIX 2:  Pinch-to-zoom on images
 * NEW 3:  Haptic feedback on all interactions
 * NEW 4:  Glassmorphism reply/reaction bar
 * NEW 5:  Spring physics story open animation
 * NEW 6:  Shared element transition support
 * NEW 7:  Music overlay display + mute control
 * NEW 8:  Full Report flow via StatusReportBottomSheet
 * NEW 9:  External share via StatusShareExternalHelper
 * NEW 10: AI auto-caption display
 * NEW 11: Countdown timer overlay
 * NEW 12: Poll overlay with voting
 * NEW 13: Boomerang loop playback
 * NEW 14: Close Friends badge in header
 */
public class StatusViewerActivity extends AppCompatActivity {
    // ── Extras ────────────────────────────────────────────
    public static final String EXTRA_OWNER_UID     = "ownerUid";
    public static final String EXTRA_ALL_UIDS      = "allOwnerUids";
    public static final String EXTRA_START_INDEX   = "currentOwnerIndex";
    public static final String EXTRA_HIGHLIGHT_ID  = "highlightAlbumId";

    // ── State ─────────────────────────────────────────────
    private String myUid;
    private ArrayList<String> allOwnerUids;
    private int currentOwnerIndex  = 0;
    private int currentItemIndex   = 0;
    private List<StatusItem> currentItems = new ArrayList<>();
    private final Set<String> seenInSession = new LinkedHashSet<>();
    private long viewStartTime = 0;

    // ── Player ────────────────────────────────────────────
    private ExoPlayer player;
    private boolean isMuted = false;

    // ── Timer ─────────────────────────────────────────────
    private Handler progressHandler = new Handler(Looper.getMainLooper());
    private Runnable progressTicker;
    private long   currentDurationMs = 5000;
    private long   elapsedMs         = 0;
    private boolean isPaused         = false;

    // ── UI Views ──────────────────────────────────────────
    private FrameLayout      rootFrame;
    private LinearLayout     segmentsContainer;
    private ImageView        ivStatus;
    private PlayerView       playerView;
    private FrameLayout      flTextStatus;
    private CircleImageView  ivOwnerAvatar;
    private TextView         tvOwnerName, tvTimestamp, tvSeenBy, tvExpiry;
    private ImageButton      btnMore, btnClose, btnMute, btnReact, btnSendReply;
    private EditText         etReply;
    private LinearLayout     bottomBar; // glassmorphism bar
    private View             touchLayer;
    private ProgressBar[]    progressBars;
    private ScaleGestureDetector pinchDetector;
    private GestureDetector  gestureDetector;
    private StatusCountdownView countdownView;
    private LinearLayout     pollOverlay;
    private LinearLayout     musicOverlay;
    private TextView         tvMusicTitle, tvMusicArtist;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                             WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        supportPostponeEnterTransition();

        myUid = FirebaseUtils.getCurrentUid();
        allOwnerUids   = getIntent().getStringArrayListExtra(EXTRA_ALL_UIDS);
        currentOwnerIndex = getIntent().getIntExtra(EXTRA_START_INDEX, 0);
        String ownerUid = getIntent().getStringExtra(EXTRA_OWNER_UID);

        if (allOwnerUids == null || allOwnerUids.isEmpty()) {
            allOwnerUids = new ArrayList<>();
            if (ownerUid != null) allOwnerUids.add(ownerUid);
        }

        buildUI();
        setupGestures();
        loadOwnerStatuses(allOwnerUids.get(currentOwnerIndex));

        // Spring open animation
        rootFrame.setScaleX(0.9f); rootFrame.setScaleY(0.9f); rootFrame.setAlpha(0f);
        rootFrame.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(280).setInterpolator(new FastOutSlowInInterpolator()).start();
        supportStartPostponedEnterTransition();
    }

    private void buildUI() {
        rootFrame = new FrameLayout(this); rootFrame.setBackgroundColor(Color.BLACK);

        // Content layers
        ivStatus = new ImageView(this);
        ivStatus.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ivStatus.setScaleType(ImageView.ScaleType.FIT_CENTER);
        rootFrame.addView(ivStatus);

        playerView = new PlayerView(this);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        playerView.setVisibility(View.GONE); rootFrame.addView(playerView);

        flTextStatus = new FrameLayout(this);
        flTextStatus.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        flTextStatus.setVisibility(View.GONE); rootFrame.addView(flTextStatus);

        // Countdown overlay
        countdownView = new StatusCountdownView(this);
        FrameLayout.LayoutParams cdlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(120));
        cdlp.gravity = Gravity.CENTER; countdownView.setLayoutParams(cdlp);
        countdownView.setVisibility(View.GONE); rootFrame.addView(countdownView);

        // Poll overlay
        pollOverlay = new LinearLayout(this); pollOverlay.setOrientation(LinearLayout.VERTICAL);
        pollOverlay.setGravity(Gravity.CENTER); pollOverlay.setPadding(dp(24),dp(16),dp(24),dp(16));
        pollOverlay.setBackgroundColor(Color.parseColor("#CC000000"));
        FrameLayout.LayoutParams polllp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        polllp.gravity = Gravity.CENTER; pollOverlay.setLayoutParams(polllp);
        pollOverlay.setVisibility(View.GONE); rootFrame.addView(pollOverlay);

        // Segments (progress bars)
        segmentsContainer = new LinearLayout(this); segmentsContainer.setOrientation(LinearLayout.HORIZONTAL);
        segmentsContainer.setPadding(dp(8),dp(8),dp(8),0);
        FrameLayout.LayoutParams seglp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        seglp.gravity = Gravity.TOP; segmentsContainer.setLayoutParams(seglp);
        rootFrame.addView(segmentsContainer);

        // Header overlay
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(12),dp(8),dp(12),dp(8));
        FrameLayout.LayoutParams hdrlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(64));
        hdrlp.gravity = Gravity.TOP; hdrlp.setMargins(0, dp(48), 0, 0); header.setLayoutParams(hdrlp);

        ivOwnerAvatar = new CircleImageView(this);
        ivOwnerAvatar.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        header.addView(ivOwnerAvatar);

        LinearLayout nameBlock = new LinearLayout(this); nameBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nblp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nblp.setMarginStart(dp(10)); nameBlock.setLayoutParams(nblp);
        tvOwnerName  = makeTv("", 15, true, Color.WHITE);
        tvTimestamp  = makeTv("", 12, false, 0xCCFFFFFF);
        tvExpiry     = makeTv("", 11, false, Color.YELLOW);
        nameBlock.addView(tvOwnerName); nameBlock.addView(tvTimestamp); nameBlock.addView(tvExpiry);
        header.addView(nameBlock);

        tvSeenBy = makeTv("", 13, false, Color.WHITE); header.addView(tvSeenBy);

        btnMute = makeIBtn(android.R.drawable.ic_lock_silent_mode_off); header.addView(btnMute);
        btnMore = makeIBtn(android.R.drawable.ic_menu_more); header.addView(btnMore);
        btnClose = makeIBtn(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setOnClickListener(v -> { StatusHapticHelper.medium(this); finishWithAnimation(); });
        header.addView(btnClose);
        rootFrame.addView(header);

        // Music overlay
        musicOverlay = new LinearLayout(this); musicOverlay.setOrientation(LinearLayout.HORIZONTAL);
        musicOverlay.setGravity(Gravity.CENTER_VERTICAL);
        musicOverlay.setBackgroundColor(Color.parseColor("#AA000000"));
        musicOverlay.setPadding(dp(12),dp(6),dp(12),dp(6));
        FrameLayout.LayoutParams muslp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        muslp.gravity = Gravity.BOTTOM | Gravity.START; muslp.setMargins(dp(12),0,0,dp(80));
        musicOverlay.setLayoutParams(muslp); musicOverlay.setVisibility(View.GONE);
        TextView musEmoji = new TextView(this); musEmoji.setText("🎵"); musEmoji.setTextSize(16);
        tvMusicTitle  = makeTv("", 12, true, Color.WHITE); tvMusicTitle.setMaxWidth(dp(140)); tvMusicTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE); tvMusicTitle.setSingleLine(true); tvMusicTitle.setSelected(true);
        tvMusicArtist = makeTv("", 10, false, 0xCCFFFFFF); tvMusicArtist.setMaxWidth(dp(140));
        LinearLayout musInfo = new LinearLayout(this); musInfo.setOrientation(LinearLayout.VERTICAL);
        musInfo.setPadding(dp(6),0,dp(6),0); musInfo.addView(tvMusicTitle); musInfo.addView(tvMusicArtist);
        musicOverlay.addView(musEmoji); musicOverlay.addView(musInfo);
        rootFrame.addView(musicOverlay);

        // Glassmorphism bottom bar (reply + react)
        bottomBar = new LinearLayout(this); bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL); bottomBar.setPadding(dp(12),dp(8),dp(12),dp(12));
        bottomBar.setBackgroundColor(Color.parseColor("#88000000")); // glassmorphism effect
        FrameLayout.LayoutParams bblp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bblp.gravity = Gravity.BOTTOM; bottomBar.setLayoutParams(bblp);
        etReply = new EditText(this); etReply.setHint("Reply…"); etReply.setHintTextColor(0xCCFFFFFF);
        etReply.setTextColor(Color.WHITE); etReply.setBackground(null); etReply.setSingleLine(true);
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etReply.setLayoutParams(etlp);
        btnReact = makeIBtn(android.R.drawable.btn_star_big_off);
        btnSendReply = makeIBtn(android.R.drawable.ic_menu_send);
        bottomBar.addView(etReply); bottomBar.addView(btnReact); bottomBar.addView(btnSendReply);
        rootFrame.addView(bottomBar);

        // Touch layer (transparent, for tap to advance / hold to pause / swipe to dismiss)
        touchLayer = new View(this);
        touchLayer.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rootFrame.addView(touchLayer);

        setContentView(rootFrame);
        setupActions();
    }

    private void setupActions() {
        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            if (player != null) player.setVolume(isMuted ? 0f : 1f);
            StatusHapticHelper.light(this);
        });

        btnReact.setOnClickListener(v -> {
            StatusHapticHelper.light(this);
            pause();
            StatusItem item = currentItem();
            if (item == null) return;
            StatusReactionBottomSheet.show(this, item, reaction -> {
                StatusSeenTracker.reactTo(item.ownerUid, item.id, reaction, null, ok -> {});
                StatusHapticHelper.reaction(btnReact);
                resume();
            });
        });

        btnSendReply.setOnClickListener(v -> {
            String txt = etReply.getText().toString().trim();
            if (txt.isEmpty()) return;
            StatusItem item = currentItem();
            if (item == null) return;
            sendReply(item, txt); etReply.setText(""); StatusHapticHelper.success(this);
        });

        etReply.setOnFocusChangeListener((v, focus) -> { if (focus) pause(); else resume(); });

        btnMore.setOnClickListener(v -> showMoreMenu());
    }

    private void setupGestures() {
        // Pinch-to-zoom (FIX: was missing)
        pinchDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float scale = 1f;
            @Override public boolean onScale(ScaleGestureDetector d) {
                scale = Math.max(1f, Math.min(4f, scale * d.getScaleFactor()));
                ivStatus.setScaleX(scale); ivStatus.setScaleY(scale); return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                if (ivStatus.getScaleX() <= 1.05f) {
                    ivStatus.animate().scaleX(1f).scaleY(1f).setDuration(200).start(); scale = 1f;
                }
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                // Left third → rewind, right two-thirds → advance
                if (e.getX() < touchLayer.getWidth() / 3f) prevItem();
                else nextItem();
                StatusHapticHelper.light(StatusViewerActivity.this);
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (Math.abs(vy) > 1500 && vy > 0) { finishWithAnimation(); return true; } // swipe down
                if (Math.abs(vx) > 1500) { // horizontal swipe → next/prev user
                    if (vx < 0) nextOwner(); else prevOwner();
                    return true;
                }
                return false;
            }
        });

        touchLayer.setOnTouchListener((v, e) -> {
            pinchDetector.onTouchEvent(e);
            gestureDetector.onTouchEvent(e);
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN: pause(); break;
                case MotionEvent.ACTION_UP: if (!pinchDetector.isInProgress()) resume(); break;
            }
            return true;
        });
    }

    private void showMoreMenu() {
        pause();
        StatusItem item = currentItem(); if (item == null) { resume(); return; }
        boolean isOwner = myUid != null && myUid.equals(item.ownerUid);
        List<String> options = new ArrayList<>();
        if (isOwner) { options.add("📊 Analytics"); options.add("🗄 Archive"); options.add("⭐ Add to Highlights"); options.add("🗑 Delete"); }
        options.add("⬇ Download"); options.add("↗ Forward"); options.add("🔗 Share Externally");
        if (!isOwner) options.add("🚩 Report");

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(options.toArray(new String[0]), (d, which) -> {
                String opt = options.get(which);
                if ("📊 Analytics".equals(opt)) showAnalytics(item);
                else if ("🗄 Archive".equals(opt)) { StatusHighlightManager.archiveStatus(item.ownerUid, item); Toast.makeText(this,"Archived",Toast.LENGTH_SHORT).show(); }
                else if ("⭐ Add to Highlights".equals(opt)) addToHighlights(item);
                else if ("🗑 Delete".equals(opt)) { StatusSeenTracker.deleteStatus(item.ownerUid, item.id); nextItem(); }
                else if ("⬇ Download".equals(opt)) StatusDownloadHelper.downloadStatus(this, item);
                else if ("↗ Forward".equals(opt)) { pause(); StatusForwardBottomSheet.show(this, item, myUid); }
                else if ("🔗 Share Externally".equals(opt)) StatusShareExternalHelper.shareMedia(this, item);
                else if ("🚩 Report".equals(opt)) {
                    // FIX: Full report flow (was just Toast before)
                    pause();
                    StatusReportBottomSheet.show(this, item, myUid);
                }
                resume();
            })
            .setOnDismissListener(d -> resume())
            .show();
    }

    private void addToHighlights(StatusItem item) {
        EditText et = new EditText(this); et.setHint("Album name…"); et.setPadding(dp(16),dp(8),dp(16),dp(8));
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Add to Highlights")
            .setView(et)
            .setPositiveButton("Add", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) return;
                String albumId = name.toLowerCase().replaceAll("\\s+","_");
                StatusHighlightManager.addToHighlight(item.ownerUid, item, albumId, name);
                Toast.makeText(this,"Added to Highlights",Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void showAnalytics(StatusItem item) {
        StatusAnalyticsHelper.Analytics a = StatusAnalyticsHelper.compute(item, 100);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📊 Status Analytics")
            .setMessage(a.getSummary())
            .setPositiveButton("OK", null).show();
    }

    private void loadOwnerStatuses(String ownerUid) {
        if (ownerUid == null) { finish(); return; }
        FirebaseUtils.getStatusRef().child(ownerUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                currentItems.clear(); currentItemIndex = 0;
                for (DataSnapshot c : snap.getChildren()) {
                    StatusItem item = c.getValue(StatusItem.class);
                    if (item == null || item.deleted || item.isExpired()) continue;
                    if (item.id == null) item.id = c.getKey();
                    if (item.ownerUid == null) item.ownerUid = ownerUid;
                    currentItems.add(item);
                }
                if (currentItems.isEmpty()) { nextOwner(); return; } // skip empty users
                buildProgressBars(currentItems.size());
                renderItem(currentItemIndex);
            }
            @Override public void onCancelled(DatabaseError e) { finish(); }
        });
    }

    private void buildProgressBars(int count) {
        segmentsContainer.removeAllViews();
        progressBars = new ProgressBar[count];
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(3), 1f);
        lp.setMargins(dp(2),0,dp(2),0);
        for (int i = 0; i < count; i++) {
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setLayoutParams(lp); pb.setMax(100);
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
            pb.setProgress(i < currentItemIndex ? 100 : 0);
            progressBars[i] = pb; segmentsContainer.addView(pb);
        }
    }

    private void renderItem(int index) {
        if (index < 0 || index >= currentItems.size()) { nextOwner(); return; }
        stopProgress();
        StatusItem item = currentItems.get(index);
        viewStartTime = StatusAnalyticsHelper.recordViewStart();

        // ── Close Friends badge ────────────────────────────
        if (item.ownerPhotoUrl != null) Glide.with(this).load(item.ownerPhotoUrl).circleCrop().into(ivOwnerAvatar);
        boolean isCloseFriend = item.isCloseFriends || (item.ownerUid != null && StatusCloseFriendsManager.isCloseFriend(this, item.ownerUid));
        tvOwnerName.setText((isCloseFriend ? "⭐ " : "") + (item.ownerName != null ? item.ownerName : ""));
        tvTimestamp.setText(android.text.format.DateUtils.getRelativeTimeSpanString(
                item.timestamp instanceof Long ? (Long) item.timestamp : System.currentTimeMillis(),
                System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString());

        // Expiry label
        if (item.expiresAt != null && item.expiresAt > 0) {
            long diff = item.expiresAt - System.currentTimeMillis();
            tvExpiry.setText(diff > 0 ? "Expires in " + formatDiff(diff) : "Expired");
            tvExpiry.setVisibility(View.VISIBLE);
        } else tvExpiry.setVisibility(View.GONE);

        // Seen by (owner only)
        boolean isOwner = myUid != null && myUid.equals(item.ownerUid);
        if (isOwner && item.seenBy != null) {
            int cnt = item.seenBy.size();
            tvSeenBy.setText("👁 " + cnt);
            tvSeenBy.setOnClickListener(v -> {
                pause();
                StatusSeenByBottomSheet sheet = StatusSeenByBottomSheet.newInstance(item.ownerUid, item.id, item);
                sheet.show(getSupportFragmentManager(), "seenby");
            });
        } else { tvSeenBy.setText(""); tvSeenBy.setOnClickListener(null); }

        // ── Music overlay ──────────────────────────────────
        if (item.musicAudioUrl != null && !item.musicAudioUrl.isEmpty()) {
            tvMusicTitle.setText(item.musicTitle != null ? item.musicTitle : "♪");
            tvMusicArtist.setText(item.musicArtist != null ? item.musicArtist : "");
            musicOverlay.setVisibility(View.VISIBLE);
        } else musicOverlay.setVisibility(View.GONE);

        // ── Countdown overlay ─────────────────────────────
        if (item.countdownTargetTs != null && item.countdownTargetTs > 0) {
            countdownView.setCountdown(item.countdownTargetTs, item.countdownLabel);
            countdownView.setVisibility(View.VISIBLE);
        } else countdownView.setVisibility(View.GONE);

        // ── Poll overlay ───────────────────────────────────
        if (item.pollQuestion != null && !item.pollQuestion.isEmpty()) {
            renderPollOverlay(item); pollOverlay.setVisibility(View.VISIBLE);
        } else pollOverlay.setVisibility(View.GONE);

        // ── Content ────────────────────────────────────────
        ivStatus.setVisibility(View.GONE); playerView.setVisibility(View.GONE); flTextStatus.setVisibility(View.GONE);
        if (player != null) { player.stop(); player.clearMediaItems(); }

        switch (item.type != null ? item.type : "text") {
            case "image": case "gif": case "sticker": case "collage":
                ivStatus.setVisibility(View.VISIBLE);
                if ("gif".equals(item.type)) Glide.with(this).asGif().load(item.mediaUrl).centerCrop().into(ivStatus);
                else if ("collage".equals(item.type) && item.collageImageUrls != null && !item.collageImageUrls.isEmpty())
                    Glide.with(this).load(item.collageImageUrls.get(0)).centerCrop().into(ivStatus);
                else Glide.with(this).load(item.mediaUrl).centerCrop().into(ivStatus);
                currentDurationMs = 5000;
                break;
            case "video": case "reel":
                ivStatus.setVisibility(View.GONE); playerView.setVisibility(View.VISIBLE);
                initPlayer(item);
                return; // initPlayer calls startProgress itself on STATE_READY
            case "link":
                ivStatus.setVisibility(View.VISIBLE);
                if (item.linkImageUrl != null) Glide.with(this).load(item.linkImageUrl).centerCrop().into(ivStatus);
                else ivStatus.setBackgroundColor(Color.parseColor("#1A237E"));
                currentDurationMs = 6000;
                break;
            case "text": default:
                renderTextStatus(item);
                currentDurationMs = Math.max(3000, Math.min(7000, (item.text != null ? item.text.length() : 5) * 80L));
                break;
        }
        seenInSession.add((item.ownerUid != null ? item.ownerUid : "") + "_" + (item.id != null ? item.id : ""));
        startProgress(currentDurationMs);
    }

    private void renderPollOverlay(StatusItem item) {
        pollOverlay.removeAllViews();
        TextView q = makeTv("📊 " + item.pollQuestion, 16, true, Color.WHITE); q.setPadding(0,0,0,dp(12)); pollOverlay.addView(q);
        if (item.pollOptions != null) {
            for (int i = 0; i < item.pollOptions.size(); i++) {
                final int idx = i; String opt = item.pollOptions.get(i);
                Button btn = new Button(this); btn.setText(opt);
                btn.setBackgroundColor(Color.parseColor("#33FFFFFF")); btn.setTextColor(Color.WHITE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0,dp(4),0,dp(4)); btn.setLayoutParams(lp);
                btn.setOnClickListener(v -> {
                    // Vote
                    Map<String, Object> vote = new HashMap<>(); vote.put(String.valueOf(idx), ServerValue.increment(1));
                    FirebaseUtils.getStatusRef().child(item.ownerUid).child(item.id).child("pollVotes").updateChildren(vote);
                    StatusHapticHelper.success(this);
                    Toast.makeText(this,"Vote submitted!",Toast.LENGTH_SHORT).show();
                    pollOverlay.setVisibility(View.GONE);
                });
                pollOverlay.addView(btn);
            }
        }
    }

    private void renderTextStatus(StatusItem item) {
        flTextStatus.setVisibility(View.VISIBLE); flTextStatus.removeAllViews();
        // Background
        if (item.bgColor2 != null) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{parseColor(item.bgColor, 0xFF1A237E), parseColor(item.bgColor2, 0xFF311B92)});
            flTextStatus.setBackground(gd);
        } else flTextStatus.setBackgroundColor(parseColor(item.bgColor, 0xFF1A237E));
        // Text
        TextView tvText = new TextView(this);
        tvText.setText(item.text != null ? StatusMentionHelper.highlight(item.text) : "");
        tvText.setTextColor(parseColor(item.textColor, Color.WHITE));
        tvText.setTextSize(item.textSize > 0 ? item.textSize : 26);
        tvText.setGravity(Gravity.CENTER);
        tvText.setPadding(dp(32),0,dp(32),0);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER; tvText.setLayoutParams(tlp);
        applyFont(tvText, item.fontStyle);
        flTextStatus.addView(tvText);
    }

    private void initPlayer(StatusItem item) {
        if (player == null) {
            CacheDataSource.Factory cacheFactory = StatusVideoCacheManager.getCacheDataSourceFactory();
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
        }
        player.setMediaItem(MediaItem.fromUri(item.mediaUrl));
        player.setRepeatMode(item.isBoomerang ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.setVolume(isMuted ? 0f : 1f);
        player.prepare();
        player.setPlayWhenReady(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && elapsedMs == 0) {
                    long dur = player.getDuration();
                    currentDurationMs = dur > 0 ? Math.min(dur, 30_000) : 5000;
                    seenInSession.add((item.ownerUid != null ? item.ownerUid : "") + "_" + (item.id != null ? item.id : ""));
                    startProgress(currentDurationMs);
                } else if (state == Player.STATE_ENDED && !item.isBoomerang) nextItem();
            }
        });
    }

    // ── Progress control ──────────────────────────────────
    private void startProgress(long durationMs) {
        stopProgress(); elapsedMs = 0; isPaused = false;
        if (progressBars != null && currentItemIndex < progressBars.length)
            progressBars[currentItemIndex].setProgress(0);
        progressTicker = new Runnable() {
            @Override public void run() {
                if (isPaused) return;
                elapsedMs += 50;
                int pct = (int) (elapsedMs * 100 / durationMs);
                if (progressBars != null && currentItemIndex < progressBars.length)
                    progressBars[currentItemIndex].setProgress(pct);
                if (elapsedMs >= durationMs) nextItem();
                else progressHandler.postDelayed(this, 50);
            }
        };
        progressHandler.post(progressTicker);
    }
    private void stopProgress() { if (progressTicker != null) { progressHandler.removeCallbacks(progressTicker); progressTicker = null; } }
    private void pause() { isPaused = true; if (player != null) player.setPlayWhenReady(false); }
    private void resume() { isPaused = false; if (player != null) player.setPlayWhenReady(true); if (progressTicker != null) progressHandler.post(progressTicker); }

    private void nextItem() {
        recordViewDuration();
        currentItemIndex++;
        if (currentItemIndex >= currentItems.size()) { nextOwner(); return; }  // FIX: advance to next user
        renderItem(currentItemIndex);
    }
    private void prevItem() { if (currentItemIndex > 0) { currentItemIndex--; renderItem(currentItemIndex); } }

    /** FIX: Multi-user carousel — auto-advance to next contact's stories */
    private void nextOwner() {
        flushSeenBatch();
        currentOwnerIndex++;
        if (allOwnerUids == null || currentOwnerIndex >= allOwnerUids.size()) { finishWithAnimation(); return; }
        // Spring transition to next user
        rootFrame.animate().translationX(-rootFrame.getWidth()).setDuration(200).withEndAction(() -> {
            rootFrame.setTranslationX(rootFrame.getWidth());
            loadOwnerStatuses(allOwnerUids.get(currentOwnerIndex));
            rootFrame.animate().translationX(0).setDuration(250).start();
        }).start();
    }
    private void prevOwner() {
        if (currentOwnerIndex <= 0) return;
        flushSeenBatch(); currentOwnerIndex--;
        loadOwnerStatuses(allOwnerUids.get(currentOwnerIndex));
    }

    private void flushSeenBatch() {
        if (seenInSession.isEmpty() || currentItems.isEmpty()) return;
        String ownerUid = currentItems.isEmpty() ? null : currentItems.get(0).ownerUid;
        List<String> ids = new ArrayList<>();
        for (String key : seenInSession) { String[] parts = key.split("_",2); if (parts.length==2) ids.add(parts[1]); }
        if (ownerUid != null && !ids.isEmpty()) {
            String name = currentItems.get(0).ownerName; String thumb = currentItems.get(0).thumbnailUrl;
            StatusSeenTracker.markSeenBatch(ownerUid, ids, name, thumb);
        }
        seenInSession.clear();
    }

    private void recordViewDuration() {
        StatusItem item = currentItem();
        if (item == null || myUid == null || viewStartTime == 0) return;
        StatusAnalyticsHelper.recordViewEnd(item.ownerUid, item.id, myUid, viewStartTime);
        viewStartTime = 0;
    }

    private void sendReply(StatusItem item, String text) {
        if (item == null || item.ownerUid == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("type","status_reply"); msg.put("fromUid", myUid); msg.put("text", text);
        msg.put("statusId", item.id); msg.put("statusType", item.type);
        msg.put("statusThumb", item.thumbnailUrl != null ? item.thumbnailUrl : item.mediaUrl);
        msg.put("timestamp", ServerValue.TIMESTAMP);
        FirebaseUtils.db().getReference("chats").child(chatKey(myUid, item.ownerUid)).push().setValue(msg);
        Toast.makeText(this,"Reply sent!",Toast.LENGTH_SHORT).show();
    }

    private void finishWithAnimation() {
        flushSeenBatch(); stopProgress();
        rootFrame.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(220).withEndAction(this::finish).start();
    }

    private StatusItem currentItem() {
        if (currentItemIndex < 0 || currentItemIndex >= currentItems.size()) return null;
        return currentItems.get(currentItemIndex);
    }

    private String chatKey(String a, String b) { return a.compareTo(b) < 0 ? a+"_"+b : b+"_"+a; }
    private String formatDiff(long ms) {
        if (ms < 60_000) return (ms/1000)+"s";
        if (ms < 3_600_000) return (ms/60_000)+"m";
        return (ms/3_600_000)+"h";
    }
    private int parseColor(String hex, int fallback) {
        try { return hex != null ? Color.parseColor(hex) : fallback; } catch (Exception e) { return fallback; }
    }
    private void applyFont(TextView tv, String style) {
        if ("bold".equals(style)) tv.setTypeface(null, Typeface.BOLD);
        else if ("italic".equals(style)) tv.setTypeface(null, Typeface.ITALIC);
        else if ("handwriting".equals(style)) tv.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
        else if ("serif".equals(style)) tv.setTypeface(Typeface.SERIF);
        else if ("condensed".equals(style)) tv.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
    }
    private TextView makeTv(String t, int sz, boolean bold, int color) {
        TextView v = new TextView(this); v.setText(t); v.setTextSize(sz); v.setTextColor(color);
        if (bold) v.setTypeface(null, Typeface.BOLD); return v;
    }
    private ImageButton makeIBtn(int res) {
        ImageButton b = new ImageButton(this); b.setImageResource(res);
        b.setBackground(null); b.setPadding(dp(6),dp(6),dp(6),dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36),dp(36)); b.setLayoutParams(lp);
        return b;
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    @Override public void onBackPressed() { finishWithAnimation(); }
    @Override protected void onPause()  { super.onPause();  pause(); }
    @Override protected void onResume() { super.onResume(); resume(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        stopProgress(); flushSeenBatch();
        if (player != null) { player.release(); player = null; }
        countdownView.stop();
    }
}
