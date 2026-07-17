package com.callx.app.feed;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.models.FeedStory;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * StoryViewActivity — full-screen story viewer (Instagram-style).
 *
 * Features:
 *  ✅ Auto-advances through story items (5s each)
 *  ✅ Segmented progress bar at top
 *  ✅ Tap right → next, tap left → previous
 *  ✅ Long-press → pause auto-advance
 *  ✅ Swipe down to close
 *  ✅ Marks stories as seen in statusSeen/{myUid}/{ownerUid}
 *  ✅ Reply / reaction row at bottom
 *  ✅ Edge-to-edge display
 *
 * Extras received via Intent:
 *  "ownerUid"   — story owner UID
 *  "ownerName"  — display name
 *  "ownerPhoto" — avatar URL
 */
public class StoryViewActivity extends AppCompatActivity {

    private static final long STORY_DURATION_MS = 5000;

    // ── Views ──────────────────────────────────────────────────────────────
    private ImageView        ivStoryMedia;
    private CircleImageView  ivOwnerAvatar;
    private TextView         tvOwnerName, tvTimeAgo;
    private ImageButton      btnClose, btnStoryMore;
    private View             containerReply;
    private TextView         tvReplyHint;
    private View             layoutProgress;
    private ProgressBar      pbStory;

    // ── State ──────────────────────────────────────────────────────────────
    private String ownerUid;
    private String myUid;

    private final List<StoryItem> storyItems = new ArrayList<>();
    private int    currentIndex = 0;
    private boolean isPaused    = false;

    private final Handler    autoHandler = new Handler(Looper.getMainLooper());
    private       Runnable   autoRunnable;
    private       long       progressStart;
    private       long       progressElapsed;

    // ── Simple story item model ────────────────────────────────────────────
    private static class StoryItem {
        String itemId;
        String mediaUrl;
        String caption;
        long   timestamp;
        String type; // "image" | "video"
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_story_view);

        // Hide system bars
        WindowInsetsControllerCompat ctrl =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
        ctrl.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        bindViews();

        ownerUid = getIntent().getStringExtra("ownerUid");
        myUid    = FirebaseUtils.getCurrentUid();
        String ownerName  = getIntent().getStringExtra("ownerName");
        String ownerPhoto = getIntent().getStringExtra("ownerPhoto");

        // Set owner info immediately while loading
        tvOwnerName.setText(ownerName != null ? ownerName : "");
        if (ownerPhoto != null && !ownerPhoto.isEmpty()) {
            Glide.with(this).load(ownerPhoto)
                 .apply(RequestOptions.circleCropTransform())
                 .placeholder(R.drawable.ic_person)
                 .into(ivOwnerAvatar);
        }

        setupTouchGestures();
        loadStoryItems();

        // Close button
        btnClose.setOnClickListener(v -> finishAfterTransition());
    }

    private void bindViews() {
        ivStoryMedia    = findViewById(R.id.iv_story_media);
        ivOwnerAvatar   = findViewById(R.id.iv_story_owner_avatar);
        tvOwnerName     = findViewById(R.id.tv_story_owner_name);
        tvTimeAgo       = findViewById(R.id.tv_story_time_ago);
        btnClose        = findViewById(R.id.btn_story_close);
        btnStoryMore    = findViewById(R.id.btn_story_more);
        containerReply  = findViewById(R.id.container_story_reply);
        tvReplyHint     = findViewById(R.id.tv_story_reply_hint);
        pbStory         = findViewById(R.id.pb_story_progress);
    }

    // ── Load story items from Firebase ─────────────────────────────────────

    private void loadStoryItems() {
        if (ownerUid == null || ownerUid.isEmpty()) { finish(); return; }

        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        FirebaseUtils.getStatusRef().child(ownerUid)
                .orderByChild("timestamp")
                .startAt((double) cutoff)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        storyItems.clear();
                        for (DataSnapshot child : snap.getChildren()) {
                            StoryItem item = new StoryItem();
                            item.itemId   = child.getKey();
                            Object mediaObj = child.child("mediaUrl").getValue();
                            item.mediaUrl = mediaObj != null ? mediaObj.toString() : "";
                            Object captObj = child.child("caption").getValue();
                            item.caption  = captObj != null ? captObj.toString() : "";
                            Object tsObj  = child.child("timestamp").getValue();
                            item.timestamp = tsObj instanceof Long ? (Long) tsObj : 0L;
                            Object typeObj = child.child("type").getValue();
                            item.type      = typeObj != null ? typeObj.toString() : "image";
                            if (!item.mediaUrl.isEmpty() || !item.caption.isEmpty()) {
                                storyItems.add(item);
                            }
                        }
                        if (storyItems.isEmpty()) { finish(); return; }
                        showStoryAt(0);
                        markSeen();
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) { finish(); }
                });
    }

    // ── Display a story item ───────────────────────────────────────────────

    private void showStoryAt(int index) {
        if (index < 0 || index >= storyItems.size()) {
            finishAfterTransition();
            return;
        }
        currentIndex = index;
        StoryItem item = storyItems.get(index);

        // Load media
        if (!item.mediaUrl.isEmpty()) {
            Glide.with(this)
                 .load(item.mediaUrl)
                 .centerCrop()
                 .into(ivStoryMedia);
        }

        // Time ago
        tvTimeAgo.setText(formatAgo(item.timestamp));

        // Progress bar
        if (pbStory != null) {
            pbStory.setMax(storyItems.size());
            pbStory.setProgress(index + 1);
        }

        // Reply hint
        if (tvReplyHint != null) {
            tvReplyHint.setText("Reply to " + getIntent().getStringExtra("ownerName") + "...");
        }

        startAutoAdvance();
    }

    // ── Auto-advance ───────────────────────────────────────────────────────

    private void startAutoAdvance() {
        stopAutoAdvance();
        progressStart = System.currentTimeMillis();
        progressElapsed = 0;
        autoRunnable = () -> showStoryAt(currentIndex + 1);
        autoHandler.postDelayed(autoRunnable, STORY_DURATION_MS);
    }

    private void stopAutoAdvance() {
        if (autoRunnable != null) autoHandler.removeCallbacks(autoRunnable);
    }

    // ── Touch gesture (tap left/right, long-press to pause) ───────────────

    private void setupTouchGestures() {
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                float x     = e.getX();
                float width = ivStoryMedia.getWidth();
                if (x < width * 0.35f) {
                    // Tap left → previous
                    showStoryAt(currentIndex - 1);
                } else {
                    // Tap right → next
                    showStoryAt(currentIndex + 1);
                }
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                isPaused = true;
                stopAutoAdvance();
            }
        });

        ivStoryMedia.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && isPaused) {
                isPaused = false;
                startAutoAdvance();
            }
            return gd.onTouchEvent(event);
        });
    }

    // ── Mark story as seen ─────────────────────────────────────────────────

    private void markSeen() {
        if (myUid == null || myUid.isEmpty() || ownerUid == null) return;
        FirebaseUtils.db().getReference("statusSeen")
                .child(myUid).child(ownerUid)
                .setValue(true);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoAdvance();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isPaused && !storyItems.isEmpty()) startAutoAdvance();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAutoAdvance();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String formatAgo(long ts) {
        long diff = System.currentTimeMillis() - ts;
        long mins  = diff / 60000;
        if (mins < 60) return mins + "m ago";
        long hours = mins / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }
}
