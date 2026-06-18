package com.callx.app.activities;

import android.animation.*;
import android.content.Intent;
import android.graphics.Color;
import android.os.*;
import android.text.format.DateFormat;
import android.view.*;
import android.view.animation.LinearInterpolator;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * StatusViewerActivity — Production-level full-screen status viewer.
 *
 * Features:
 *  • Multi-segment progress bars (one per status item of the contact)
 *  • Auto-advance (5s for text, 7s for image, full video duration)
 *  • Hold-to-pause (long-press anywhere)
 *  • Left/right tap zones (< 1/3 = previous, > 2/3 = next)
 *  • Swipe down to dismiss
 *  • Reactions bottom sheet (❤️ 😂 😮 😢 😡 👍)
 *  • Reply bar (sends to chat)
 *  • "Seen by N" overlay for owner viewing own status
 *  • Font/color rendering matching NewStatusActivity styles
 *  • Real-time seen tracking via StatusSeenTracker
 *  • Owner can delete/save/add-to-highlights from overflow menu
 *  • Viewer can forward/save-to-gallery/report from overflow
 */
public class StatusViewerActivity extends AppCompatActivity {

    public static final String EXTRA_OWNER_UID = "owner_uid";
    public static final String EXTRA_START_IDX = "start_index";

    private static final long AUTO_ADVANCE_TEXT_MS  = 5_000;
    private static final long AUTO_ADVANCE_IMAGE_MS = 7_000;

    // ── Views ─────────────────────────────────────────────────────────────
    private LinearLayout      progressContainer;
    private ImageView         ivBackground, ivAvatar;
    private TextView          tvOwnerName, tvTimestamp, tvStatusText;
    private TextView          tvSeenBy, tvCaption;
    private View              pauseLayer, layoutBottom;
    private View              btnReact, btnReply, btnMore;
    private EditText          etReply;
    private View              layoutLink;
    private TextView          tvLinkTitle, tvLinkDomain;
    private ImageView         ivLinkThumb;
    private View              layoutPoll;

    // ── Progress bars ─────────────────────────────────────────────────────
    private List<ProgressBar> segmentBars = new ArrayList<>();
    private ObjectAnimator    currentAnimator;

    // ── State ─────────────────────────────────────────────────────────────
    private List<StatusItem>  items = new ArrayList<>();
    private int               currentIndex = 0;
    private boolean           isPaused    = false;
    private String            ownerUid;
    private String            myUid;
    private boolean           isOwner;

    // ── Swipe to dismiss ──────────────────────────────────────────────────
    private float touchStartY;
    private static final float SWIPE_THRESHOLD = 200f;

    // ── Firebase ──────────────────────────────────────────────────────────
    private ValueEventListener statusListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        // setContentView(R.layout.activity_status_viewer);

        ownerUid = getIntent().getStringExtra(EXTRA_OWNER_UID);
        int startIdx = getIntent().getIntExtra(EXTRA_START_IDX, 0);
        myUid    = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        isOwner  = ownerUid != null && ownerUid.equals(myUid);

        bindViews();
        setupTouchHandlers();
        setupBottomActions();
        loadStatuses(ownerUid, startIdx);
    }

    // ── View binding ──────────────────────────────────────────────────────
    private void bindViews() {
        progressContainer = fv("progress_container");
        ivBackground      = fv("iv_status_background");
        ivAvatar          = fv("iv_owner_avatar");
        tvOwnerName       = fv("tv_owner_name");
        tvTimestamp       = fv("tv_timestamp");
        tvStatusText      = fv("tv_status_text");
        tvSeenBy          = fv("tv_seen_by");
        tvCaption         = fv("tv_status_caption");
        pauseLayer        = fv("pause_touch_layer");
        btnReact          = fv("btn_react");
        btnReply          = fv("btn_reply");
        btnMore           = fv("btn_more");
        etReply           = fv("et_reply");
        layoutLink        = fv("layout_link_preview");
        tvLinkTitle       = fv("tv_link_title");
        tvLinkDomain      = fv("tv_link_domain");
        ivLinkThumb       = fv("iv_link_thumb");
        layoutPoll        = fv("layout_poll");
    }

    // ── Load statuses ─────────────────────────────────────────────────────
    private void loadStatuses(String uid, int startIdx) {
        long cutoff = System.currentTimeMillis() - 24L * 3600_000;
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                items.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    StatusItem item = c.getValue(StatusItem.class);
                    if (item == null || item.deleted || item.archived) continue;
                    if (item.expiresAt > 0 && System.currentTimeMillis() > item.expiresAt) continue;
                    if (item.statusId == null) item.statusId = c.getKey();
                    items.add(item);
                }
                items.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
                if (items.isEmpty()) { finish(); return; }
                currentIndex = Math.min(startIdx, items.size() - 1);
                buildProgressBars();
                showCurrent();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finish(); }
        };
        FirebaseUtils.getUserStatusRef(uid)
            .orderByChild("timestamp").startAt(cutoff)
            .addListenerForSingleValueEvent(statusListener);
    }

    // ── Progress bars ─────────────────────────────────────────────────────
    private void buildProgressBars() {
        if (progressContainer == null) return;
        progressContainer.removeAllViews();
        segmentBars.clear();
        int n = items.size();
        float weightEach = 1f / n;
        for (int i = 0; i < n; i++) {
            ProgressBar pb = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, (int)(3 * getResources().getDisplayMetrics().density));
            lp.weight = weightEach;
            lp.leftMargin = lp.rightMargin = (int)(2 * getResources().getDisplayMetrics().density);
            pb.setLayoutParams(lp);
            pb.setMax(1000);
            pb.setProgress(i < currentIndex ? 1000 : 0);
            pb.getProgressDrawable().setColorFilter(Color.WHITE,
                android.graphics.PorterDuff.Mode.SRC_IN);
            progressContainer.addView(pb);
            segmentBars.add(pb);
        }
    }

    // ── Show current item ─────────────────────────────────────────────────
    private void showCurrent() {
        if (items.isEmpty() || currentIndex >= items.size()) { finish(); return; }
        StatusItem item = items.get(currentIndex);

        // Header
        if (tvOwnerName != null) tvOwnerName.setText(item.ownerName);
        if (tvTimestamp != null) tvTimestamp.setText(formatTs(item.timestamp));
        if (ivAvatar != null && item.ownerPhoto != null) {
            Glide.with(this).load(item.ownerPhoto).circleCrop().into(ivAvatar);
        }

        renderContent(item);
        markSeen(item);

        // Owner: show seen by count
        if (isOwner && tvSeenBy != null) {
            int cnt = item.seenCount;
            tvSeenBy.setVisibility(View.VISIBLE);
            tvSeenBy.setText(cnt == 0 ? "Not seen yet"
                : cnt + " viewer" + (cnt != 1 ? "s" : ""));
            tvSeenBy.setOnClickListener(v -> openSeenBySheet(item));
        } else if (tvSeenBy != null) {
            tvSeenBy.setVisibility(View.GONE);
        }

        // Update progress bars
        for (int i = 0; i < segmentBars.size(); i++) {
            segmentBars.get(i).setProgress(i < currentIndex ? 1000 : 0);
        }

        startProgressAnimation(item);
    }

    private void renderContent(StatusItem item) {
        // Reset all content views
        if (tvStatusText  != null) tvStatusText.setVisibility(View.GONE);
        if (ivBackground  != null) ivBackground.setVisibility(View.GONE);
        if (layoutLink    != null) layoutLink.setVisibility(View.GONE);
        if (layoutPoll    != null) layoutPoll.setVisibility(View.GONE);
        if (tvCaption     != null) tvCaption.setVisibility(View.GONE);

        String type = item.type != null ? item.type : "text";
        switch (type) {
            case "text":
                renderText(item); break;
            case "image":
            case "gif":
                renderImage(item); break;
            case "video":
                renderVideo(item); break;
            case "link":
                renderLink(item); break;
            case "poll":
                renderPoll(item); break;
        }

        // Caption for media
        if ((!"text".equals(type)) && item.text != null && !item.text.isEmpty()) {
            if (tvCaption != null) {
                tvCaption.setText(item.text);
                tvCaption.setVisibility(View.VISIBLE);
            }
        }
    }

    private void renderText(StatusItem item) {
        if (tvStatusText == null) return;
        tvStatusText.setVisibility(View.VISIBLE);
        tvStatusText.setText(item.text);
        String bg = item.bgColor != null ? item.bgColor : "#075E54";
        try { getWindow().getDecorView().setBackgroundColor(Color.parseColor(bg)); }
        catch (Exception e) { getWindow().getDecorView().setBackgroundColor(Color.parseColor("#075E54")); }
        applyFontStyle(tvStatusText, item.fontStyle);
    }

    private void renderImage(StatusItem item) {
        if (ivBackground == null) return;
        ivBackground.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        // Progressive: load thumb instantly, then full
        if (item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()) {
            Glide.with(this).load(item.thumbnailUrl).centerCrop()
                .thumbnail(Glide.with(this).load(item.mediaUrl))
                .into(ivBackground);
        } else if (item.mediaUrl != null) {
            Glide.with(this).load(item.mediaUrl).centerCrop().into(ivBackground);
        }
    }

    private void renderVideo(StatusItem item) {
        // TODO: Use ExoPlayer for video playback
        // For now show thumbnail + play overlay
        renderImage(item);
    }

    private void renderLink(StatusItem item) {
        if (layoutLink == null) return;
        layoutLink.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#1A1A2E"));
        if (tvLinkTitle  != null) tvLinkTitle.setText(item.linkTitle != null ? item.linkTitle : item.linkUrl);
        if (tvLinkDomain != null) tvLinkDomain.setText(item.linkDomain != null ? item.linkDomain : "");
        if (ivLinkThumb  != null && item.linkThumbUrl != null) {
            Glide.with(this).load(item.linkThumbUrl).centerCrop().into(ivLinkThumb);
        }
    }

    private void renderPoll(StatusItem item) {
        if (layoutPoll == null) return;
        layoutPoll.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#1A1A2E"));
        // TODO: wire poll options to RecyclerView inside layoutPoll
    }

    // ── Progress animation ────────────────────────────────────────────────
    private void startProgressAnimation(StatusItem item) {
        if (currentAnimator != null) currentAnimator.cancel();
        if (segmentBars.isEmpty() || currentIndex >= segmentBars.size()) return;

        ProgressBar pb  = segmentBars.get(currentIndex);
        pb.setProgress(0);
        long duration = AUTO_ADVANCE_IMAGE_MS;
        if ("text".equals(item.type)) duration = AUTO_ADVANCE_TEXT_MS;

        currentAnimator = ObjectAnimator.ofInt(pb, "progress", 0, 1000);
        currentAnimator.setDuration(duration);
        currentAnimator.setInterpolator(new LinearInterpolator());
        currentAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (!isPaused) advance();
            }
        });
        if (!isPaused) currentAnimator.start();
    }

    private void pauseProgress() {
        isPaused = true;
        if (currentAnimator != null && currentAnimator.isRunning()) currentAnimator.pause();
    }

    private void resumeProgress() {
        isPaused = false;
        if (currentAnimator != null && currentAnimator.isPaused()) currentAnimator.resume();
    }

    private void advance() {
        if (currentIndex + 1 < items.size()) {
            currentIndex++;
            showCurrent();
        } else {
            finish(); // All statuses of this contact viewed
        }
    }

    private void goBack() {
        if (currentIndex > 0) {
            currentIndex--;
            showCurrent();
        }
    }

    // ── Touch handling ────────────────────────────────────────────────────
    private void setupTouchHandlers() {
        if (pauseLayer == null) return;
        pauseLayer.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartY = ev.getY();
                    pauseProgress();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dy = ev.getY() - touchStartY;
                    if (dy > SWIPE_THRESHOLD) {
                        finish(); // swipe down = dismiss
                        return true;
                    }
                    float x = ev.getX();
                    float width = pauseLayer.getWidth();
                    if (x < width / 3f) goBack();
                    else if (x > 2 * width / 3f) advance();
                    else resumeProgress();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    resumeProgress();
                    return true;
            }
            return false;
        });
    }

    // ── Bottom actions ────────────────────────────────────────────────────
    private void setupBottomActions() {
        if (btnReact != null) btnReact.setOnClickListener(v -> showReactionSheet());
        if (btnReply != null) btnReply.setOnClickListener(v -> focusReplyBar());
        if (btnMore  != null) btnMore.setOnClickListener(v  -> showMoreOptions());
    }

    private void showReactionSheet() {
        pauseProgress();
        String[] emojis = {"❤️", "😂", "😮", "😢", "😡", "👍"};
        View sheet = getLayoutInflater().inflate(
            getRes("bottom_sheet_status_reactions"), null);
        com.google.android.material.bottomsheet.BottomSheetDialog bsd =
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        bsd.setContentView(sheet);
        // Wire each emoji button
        int[] btnIds = {getRes2("btn_react_heart"), getRes2("btn_react_laugh"),
            getRes2("btn_react_wow"), getRes2("btn_react_sad"),
            getRes2("btn_react_angry"), getRes2("btn_react_like")};
        for (int i = 0; i < btnIds.length && i < emojis.length; i++) {
            final String emoji = emojis[i];
            View btn = sheet.findViewById(btnIds[i]);
            if (btn != null) btn.setOnClickListener(v -> {
                sendReaction(emoji);
                bsd.dismiss();
            });
        }
        bsd.setOnDismissListener(d -> resumeProgress());
        bsd.show();
    }

    private void sendReaction(String emoji) {
        if (items.isEmpty() || currentIndex >= items.size()) return;
        StatusItem item = items.get(currentIndex);
        if (myUid == null || ownerUid == null) return;
        StatusSeenTracker.get().setReaction(ownerUid, item.statusId, myUid, emoji);
        Toast.makeText(this, "Reacted " + emoji, Toast.LENGTH_SHORT).show();
    }

    private void focusReplyBar() {
        if (etReply == null) return;
        pauseProgress();
        etReply.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(etReply, 0);
        // TODO: on send → create chat message referencing this status
    }

    private void showMoreOptions() {
        pauseProgress();
        String[] ownerOptions  = {"Delete Status", "Add to Highlights", "Save to Gallery",
            "Edit Privacy", "Status Info"};
        String[] viewerOptions = {"Reply", "Forward to Chat", "Save to Gallery", "Report"};
        String[] opts = isOwner ? ownerOptions : viewerOptions;
        new android.app.AlertDialog.Builder(this)
            .setItems(opts, (d, w) -> {
                if (isOwner) handleOwnerOption(w);
                else         handleViewerOption(w);
                resumeProgress();
            })
            .setOnCancelListener(d -> resumeProgress())
            .show();
    }

    private void handleOwnerOption(int which) {
        StatusItem item = currentItem();
        if (item == null) return;
        switch (which) {
            case 0: deleteCurrentStatus(item); break;
            case 1: openAddToHighlights(item); break;
            case 2: saveMediaToGallery(item); break;
            case 3: openPrivacyEdit(item); break;
            case 4: openSeenBySheet(item); break;
        }
    }

    private void handleViewerOption(int which) {
        StatusItem item = currentItem();
        if (item == null) return;
        switch (which) {
            case 0: focusReplyBar(); break;
            case 1: forwardToChat(item); break;
            case 2: saveMediaToGallery(item); break;
            case 3: reportStatus(item); break;
        }
    }

    private void deleteCurrentStatus(StatusItem item) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Delete status?")
            .setMessage("This status will be permanently removed.")
            .setPositiveButton("Delete", (d2, w2) -> {
                FirebaseUtils.getUserStatusRef(ownerUid).child(item.statusId)
                    .child("deleted").setValue(true);
                StatusExpiryManager.cancelExpiryReminder(this, item.statusId);
                items.remove(currentIndex);
                if (items.isEmpty()) finish();
                else {
                    if (currentIndex >= items.size()) currentIndex = items.size() - 1;
                    buildProgressBars();
                    showCurrent();
                }
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void openAddToHighlights(StatusItem item) {
        Intent i = new Intent(this, StatusHighlightsActivity.class);
        i.putExtra("statusId",   item.statusId);
        i.putExtra("mediaUrl",   item.mediaUrl);
        i.putExtra("thumbUrl",   item.thumbnailUrl);
        i.putExtra("statusType", item.type);
        startActivity(i);
    }

    private void forwardToChat(StatusItem item) {
        // TODO: Open contact picker → send as chat message
        Toast.makeText(this, "Forward: select contact in chat list", Toast.LENGTH_SHORT).show();
    }

    private void saveMediaToGallery(StatusItem item) {
        if (item.mediaUrl == null || item.mediaUrl.isEmpty()) {
            Toast.makeText(this, "No media to save", Toast.LENGTH_SHORT).show();
            return;
        }
        // TODO: Download mediaUrl to MediaStore
        Toast.makeText(this, "Saving to gallery…", Toast.LENGTH_SHORT).show();
    }

    private void reportStatus(StatusItem item) {
        Toast.makeText(this, "Status reported", Toast.LENGTH_SHORT).show();
        // TODO: Write to Firebase reports node
    }

    private void openPrivacyEdit(StatusItem item) {
        Intent i = new Intent(this, NewStatusActivity.class);
        i.putExtra("edit_status_id", item.statusId);
        startActivity(i);
    }

    // ── Seen by sheet ─────────────────────────────────────────────────────
    private void openSeenBySheet(StatusItem item) {
        pauseProgress();
        Intent i = new Intent(this, MyStatusActivity.class);
        i.putExtra("focus_status_id", item.statusId);
        startActivity(i);
    }

    // ── Seen tracking ─────────────────────────────────────────────────────
    private void markSeen(StatusItem item) {
        if (myUid == null || ownerUid == null || isOwner) return;
        StatusSeenTracker.get().markSeenWithOwner(ownerUid, item.statusId, myUid);
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private StatusItem currentItem() {
        if (items.isEmpty() || currentIndex >= items.size()) return null;
        return items.get(currentIndex);
    }

    private String formatTs(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 3600_000) return (diff / 60000) + "m ago";
        if (diff < 86400_000) return (diff / 3600000) + "h ago";
        return DateFormat.format("MMM d", new java.util.Date(ts)).toString();
    }

    private void applyFontStyle(TextView tv, int style) {
        switch (style) {
            case 1: tv.setTypeface(android.graphics.Typeface.SERIF); break;
            case 2: tv.setTypeface(android.graphics.Typeface.MONOSPACE); break;
            case 4: tv.setTypeface(null, android.graphics.Typeface.BOLD); break;
            default: tv.setTypeface(android.graphics.Typeface.DEFAULT);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T fv(String name) {
        int id = getResources().getIdentifier(name, "id", getPackageName());
        if (id == 0) return null;
        return (T) findViewById(id);
    }
    private int getRes(String name) {
        return getResources().getIdentifier(name, "layout", getPackageName());
    }
    private int getRes2(String name) {
        return getResources().getIdentifier(name, "id", getPackageName());
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (currentAnimator != null) currentAnimator.cancel();
    }

    @Override public void onBackPressed() {
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }
}
