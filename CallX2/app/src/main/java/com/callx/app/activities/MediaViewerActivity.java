package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.chrisbanes.photoview.PhotoView;
import com.callx.app.databinding.ActivityMediaViewerBinding;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.MediaCache;
import com.google.firebase.database.DatabaseReference;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MediaViewerActivity — Full-screen media viewer.
 *
 * Features:
 *  • Pinch-to-zoom for images (PhotoView)
 *  • Tap image → toggle top bar (WhatsApp style)
 *  • Swipe down gesture → dismiss (via PhotoView scale + back)
 *  • Video with ExoPlayer (cache-first)
 *  • Share button in top bar
 *  • Video playback presence — while a video opened FROM a chat is actually
 *    playing, publishes chatPlayback/{chatId}/{uid}=messageId (same node
 *    ChatPlaybackPresenceController watches) so the partner's chat list
 *    shows a live "▶ watching…" badge on that bubble. No-op if this viewer
 *    was opened without chatId/messageId extras (e.g. from a non-chat caller).
 */
public class MediaViewerActivity extends AppCompatActivity {

    private ActivityMediaViewerBinding binding;
    private ExoPlayer player;
    private boolean uiVisible = true;
    private String sharedUrl;

    // ── Gallery mode (swipeable grouped-media viewer) ────────────────────
    private GalleryPagerAdapter galleryAdapter;
    private List<Map<String, Object>> galleryItems;
    private int galleryActivePos = -1;

    // ── Swipe-down-to-close / swipe-up-to-reply (gallery mode only) ──────
    private String replyChatId;
    private String replyMessageId;
    private float  vSwipeStartX, vSwipeStartY;
    private boolean vSwipeDragging = false;
    private int touchSlop;

    // ── Video playback presence (see class doc above) ───────────────────
    private String playbackChatId;
    private String playbackMessageId;
    private DatabaseReference playbackRef;
    private boolean playbackPublished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full-screen immersive
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        binding = ActivityMediaViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        hideSystemUI();
        touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();

        String url  = getIntent().getStringExtra("url");
        String type = getIntent().getStringExtra("type");
        sharedUrl   = url;

        // Optional — only present when opened from a grouped-media message
        // tap. Used to send a swipe-up "reply" request back to the chat
        // screen via GalleryReplyBridge. Both null disables that gesture's
        // action (the swipe-down-to-close part always works regardless).
        replyChatId    = getIntent().getStringExtra("chatId");
        replyMessageId = getIntent().getStringExtra("messageId");

        // Optional — only present when opened from a chat bubble. Both
        // null is the normal/expected case for other callers (status
        // viewer, all-media grid, etc.) and simply disables presence.
        playbackChatId    = getIntent().getStringExtra("chatId");
        playbackMessageId = getIntent().getStringExtra("messageId");

        // Close button
        binding.btnClose.setOnClickListener(v -> finish());

        // Share button
        binding.btnShare.setOnClickListener(v -> shareMedia(sharedUrl));

        // More options (currently just a placeholder / could open bottom sheet)
        binding.btnMoreOptions.setOnClickListener(v -> {
            // optional: show save/info dialog
        });

        String mediaItemsJson = getIntent().getStringExtra("mediaItemsJson");
        if (mediaItemsJson != null && !mediaItemsJson.isEmpty()) {
            setupGalleryMode(mediaItemsJson, getIntent().getIntExtra("startIndex", 0));
            return;
        }

        if (url == null) { finish(); return; }

        if ("video".equals(type)) {
            binding.player.setVisibility(View.VISIBLE);
            binding.ivFull.setVisibility(View.GONE);
            playVideo(url);

            // For video — tap player toggles top bar
            binding.player.setOnClickListener(v -> toggleUI());

        } else {
            binding.ivFull.setVisibility(View.VISIBLE);
            binding.player.setVisibility(View.GONE);

            String thumbUrl = getIntent().getStringExtra("thumbUrl");
            loadImageProgressive(url, thumbUrl);

            // Tap image → toggle top bar
            binding.ivFull.setOnViewTapListener((view, x, y) -> toggleUI());
        }
    }

    // ── Gallery mode — swipeable multi-image/video viewer ────────────────
    private void setupGalleryMode(String json, int startIndex) {
        galleryItems = parseMediaItems(json);
        if (galleryItems.isEmpty()) { finish(); return; }
        int start = Math.max(0, Math.min(startIndex, galleryItems.size() - 1));

        binding.ivFull.setVisibility(View.GONE);
        binding.player.setVisibility(View.GONE);
        binding.mediaPager.setVisibility(View.VISIBLE);
        binding.tvPageCounter.setVisibility(galleryItems.size() > 1 ? View.VISIBLE : View.GONE);

        galleryAdapter = new GalleryPagerAdapter(galleryItems, this::toggleUI);
        binding.mediaPager.setAdapter(galleryAdapter);
        binding.mediaPager.setCurrentItem(start, false);
        updatePageCounter(start);
        sharedUrl = safeStr(galleryItems.get(start).get("url"));

        binding.mediaPager.registerOnPageChangeCallback(new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                pauseAllExcept(position);
                galleryActivePos = position;
                sharedUrl = safeStr(galleryItems.get(position).get("url"));
                updatePageCounter(position);
            }
        });
        galleryActivePos = start;
        // Slight delay so RecyclerView has a bound ViewHolder to play on first open
        binding.mediaPager.post(() -> pauseAllExcept(start));
    }

    /** Plays the video on `activePos` (if it's a video page) and pauses every other bound page. */
    private void pauseAllExcept(int activePos) {
        androidx.recyclerview.widget.RecyclerView rv =
                (androidx.recyclerview.widget.RecyclerView) binding.mediaPager.getChildAt(0);
        if (rv == null) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            android.view.View child = rv.getChildAt(i);
            androidx.recyclerview.widget.RecyclerView.ViewHolder vh = rv.getChildViewHolder(child);
            if (vh instanceof GalleryPagerAdapter.PageVH) {
                GalleryPagerAdapter.PageVH pvh = (GalleryPagerAdapter.PageVH) vh;
                galleryAdapter.setActive(pvh, pvh.getAdapterPosition() == activePos);
            }
        }
    }

    private void updatePageCounter(int position) {
        if (galleryItems == null) return;
        binding.tvPageCounter.setText((position + 1) + " / " + galleryItems.size());
    }

    private List<Map<String, Object>> parseMediaItems(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                Map<String, Object> item = new HashMap<>();
                java.util.Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    item.put(k, obj.opt(k));
                }
                result.add(item);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String safeStr(Object o) { return (o instanceof String) ? (String) o : ""; }

    // ── Swipe down (close) / swipe up (reply) — gallery mode only ────────
    // ViewPager2 owns horizontal touch handling for left/right paging, so
    // we watch for a predominantly-vertical drag at the Activity level and
    // only take over once it's clearly vertical (avoids fighting the pager
    // or breaking PhotoView pinch-zoom in single-media mode).
    private static final float SWIPE_DISMISS_THRESHOLD_DP = 100f;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (galleryItems == null || galleryItems.isEmpty()) {
            return super.dispatchTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                vSwipeStartX = ev.getRawX();
                vSwipeStartY = ev.getRawY();
                vSwipeDragging = false;
                return super.dispatchTouchEvent(ev);

            case android.view.MotionEvent.ACTION_MOVE: {
                float dx = ev.getRawX() - vSwipeStartX;
                float dy = ev.getRawY() - vSwipeStartY;
                if (!vSwipeDragging) {
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                        vSwipeDragging = true;
                        // Cancel the pager's in-progress touch so it doesn't
                        // also try to interpret this drag as a page-scroll.
                        android.view.MotionEvent cancel = android.view.MotionEvent.obtain(ev);
                        cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                    } else {
                        return super.dispatchTouchEvent(ev);
                    }
                }
                binding.mediaPager.setTranslationY(dy);
                float dragFraction = Math.min(1f, Math.abs(dy) / dp(this, 400));
                binding.mediaPager.setAlpha(1f - dragFraction * 0.6f);
                binding.getRoot().setBackgroundColor(
                        android.graphics.Color.argb(
                                (int) (255 * (1f - dragFraction)), 0, 0, 0));
                return true;
            }

            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                if (!vSwipeDragging) return super.dispatchTouchEvent(ev);
                float dy = ev.getRawY() - vSwipeStartY;
                float thresholdPx = dp(this, (int) SWIPE_DISMISS_THRESHOLD_DP);
                vSwipeDragging = false;
                if (dy <= -thresholdPx) {
                    // Swipe UP → reply, then close.
                    if (replyChatId != null && replyMessageId != null) {
                        com.callx.app.conversation.GalleryReplyBridge
                                .requestReply(replyChatId, replyMessageId);
                    }
                    finish();
                    overridePendingTransition(0, 0);
                } else if (dy >= thresholdPx) {
                    // Swipe DOWN → close.
                    finish();
                    overridePendingTransition(0, 0);
                } else {
                    // Not far enough — snap back.
                    binding.mediaPager.animate().translationY(0).alpha(1f).setDuration(180).start();
                    binding.getRoot().setBackgroundColor(android.graphics.Color.BLACK);
                }
                return true;
            }

            default:
                return super.dispatchTouchEvent(ev);
        }
    }

    private static int dp(android.content.Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }


    private void toggleUI() {
        uiVisible = !uiVisible;
        LinearLayout topBar = binding.llTopBar;
        if (uiVisible) {
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).setDuration(200).start();
        } else {
            topBar.animate().alpha(0f).setDuration(200).withEndAction(
                () -> topBar.setVisibility(View.GONE)
            ).start();
        }
    }

    // ── Progressive image load ────────────────────────────────────
    private void loadImageProgressive(String fullUrl, String thumbUrl) {
        PhotoView pv = binding.ivFull;
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            Glide.with(this)
                .load(thumbUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(400, 400)
                .into(pv);

            Glide.with(this)
                .load(fullUrl)
                .thumbnail(Glide.with(this)
                    .load(thumbUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .transition(com.bumptech.glide.load.resource.drawable
                    .DrawableTransitionOptions.withCrossFade(500))
                .into(pv);
        } else {
            File cachedImg = MediaCache.getCached(this, fullUrl);
            if (cachedImg != null) {
                Glide.with(this).load(cachedImg)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(pv);
            } else {
                Glide.with(this).load(fullUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(pv);
                MediaCache.get(this, fullUrl, new MediaCache.Callback() {
                    @Override public void onReady(File f) {}
                    @Override public void onError(String r) {}
                });
            }
        }
    }

    // ── Video playback (cache-first) ──────────────────────────────
    private void playVideo(String url) {
        File cached = MediaCache.getCached(this, url);
        if (cached != null) {
            startExoPlayer(Uri.fromFile(cached));
            return;
        }
        showLoading(true);
        MediaCache.get(this, url, new MediaCache.Callback() {
            @Override public void onReady(File file) {
                showLoading(false);
                startExoPlayer(Uri.fromFile(file));
            }
            @Override public void onError(String reason) {
                showLoading(false);
                startExoPlayer(Uri.parse(url));
            }
        });
    }

    private void startExoPlayer(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        player = new ExoPlayer.Builder(this).build();
        binding.player.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.setPlayWhenReady(true);

        // Mirror actual play/pause state into chatPlayback — onIsPlayingChanged
        // fires for user pause/resume AND for buffering stalls, which is
        // exactly the granularity we want for a "watching…" badge.
        if (playbackChatId != null && messageIdPresent()) {
            player.addListener(new Player.Listener() {
                @Override public void onIsPlayingChanged(boolean isPlaying) {
                    publishPlaybackPresence(isPlaying);
                }
            });
        }
    }

    private boolean messageIdPresent() {
        return playbackMessageId != null && !playbackMessageId.isEmpty();
    }

    private void publishPlaybackPresence(boolean playing) {
        if (playbackChatId == null || !messageIdPresent()) return;
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty()) return;
        if (playbackRef == null) {
            playbackRef = FirebaseUtils.getChatPlaybackRef(playbackChatId).child(uid);
        }
        if (playing == playbackPublished) return;
        playbackPublished = playing;
        if (playing) {
            playbackRef.setValue(playbackMessageId);
            // Safety net: if the app dies mid-playback, Firebase clears it for us.
            playbackRef.onDisconnect().removeValue();
        } else {
            playbackRef.removeValue();
            playbackRef.onDisconnect().cancel();
        }
    }

    // ── Share ─────────────────────────────────────────────────────
    private void shareMedia(String url) {
        if (url == null) return;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(shareIntent, "Share via"));
    }

    private void showLoading(boolean show) {
        runOnUiThread(() -> binding.pbLoading.setVisibility(show ? View.VISIBLE : View.GONE));
    }

    private void hideSystemUI() {
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        if (binding != null && binding.mediaPager.getChildCount() > 0
                && binding.mediaPager.getChildAt(0) instanceof androidx.recyclerview.widget.RecyclerView) {
            androidx.recyclerview.widget.RecyclerView rv =
                    (androidx.recyclerview.widget.RecyclerView) binding.mediaPager.getChildAt(0);
            for (int i = 0; i < rv.getChildCount(); i++) {
                androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
                        rv.getChildViewHolder(rv.getChildAt(i));
                if (vh instanceof GalleryPagerAdapter.PageVH && galleryAdapter != null) {
                    galleryAdapter.releasePlayer((GalleryPagerAdapter.PageVH) vh);
                }
            }
        }
        // Viewer is closing — clear the "watching…" badge immediately rather
        // than waiting on onDisconnect (that's only the crash/kill safety net).
        publishPlaybackPresence(false);
        super.onDestroy();
    }
}
