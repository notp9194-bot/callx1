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

        // #3 fix — Save to gallery (previously only Share was available)
        binding.btnSave.setOnClickListener(v -> saveCurrentToGallery());

        // #4/#2 fix — More options now opens a real menu (per-item delete/
        // star/caption-edit for grouped media, or just Save+Share fallback
        // for single-media mode) instead of being a dead placeholder.
        binding.btnMoreOptions.setOnClickListener(v -> showMoreOptionsMenu());

        // #1 fix — selection-mode toolbar wiring
        binding.btnSelectClose.setOnClickListener(v -> exitSelectMode());
        binding.btnSelectForward.setOnClickListener(v -> forwardSelection());
        binding.btnSelectDelete.setOnClickListener(v -> deleteSelection());
        binding.btnSelectStar.setOnClickListener(v -> starSelection());

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
        galleryAdapter.setLongPressListener(pos -> enterSelectMode(pos));
        galleryAdapter.setSelectionToggleListener(pos -> updateSelectToolbar());
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
        if (galleryItems == null || galleryItems.isEmpty()
                || (galleryAdapter != null && galleryAdapter.isSelectMode())) {
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
                    // #6 fix — quote the SPECIFIC tapped image/video, not
                    // just the group as a whole.
                    if (replyChatId != null && replyMessageId != null) {
                        com.callx.app.conversation.GalleryReplyBridge
                                .requestReply(replyChatId, replyMessageId, galleryActivePos);
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

    @Override
    public void onBackPressed() {
        if (galleryAdapter != null && galleryAdapter.isSelectMode()) {
            exitSelectMode();
            return;
        }
        super.onBackPressed();
    }

    private static int dp(android.content.Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }


    // ── #1 — Multi-select / forward from gallery ─────────────────────────
    private void enterSelectMode(int startPos) {
        if (galleryAdapter == null) return;
        galleryAdapter.setSelectMode(true);
        galleryAdapter.toggleSelected(startPos);
        binding.llTopBar.setVisibility(View.GONE);
        binding.llSelectToolbar.setVisibility(View.VISIBLE);
        updateSelectToolbar();
    }

    private void exitSelectMode() {
        if (galleryAdapter == null) return;
        galleryAdapter.setSelectMode(false);
        binding.llSelectToolbar.setVisibility(View.GONE);
        binding.llTopBar.setVisibility(View.VISIBLE);
    }

    private void updateSelectToolbar() {
        if (galleryAdapter == null) return;
        int count = galleryAdapter.getSelectedCount();
        if (count == 0) { exitSelectMode(); return; }
        binding.tvSelectCount.setText(count + " selected");
    }

    private void forwardSelection() {
        if (galleryAdapter == null || replyChatId == null || replyMessageId == null) {
            android.widget.Toast.makeText(this, "Can't forward — not opened from a chat", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<Integer> selected = galleryAdapter.getSelectedPositions();
        com.callx.app.conversation.GalleryForwardBridge.requestForward(replyChatId, replyMessageId, selected);
        exitSelectMode();
        finish();
        overridePendingTransition(0, 0);
    }

    private void deleteSelection() {
        if (galleryAdapter == null || replyChatId == null || replyMessageId == null) return;
        java.util.List<Integer> selected = new ArrayList<>(galleryAdapter.getSelectedPositions());
        if (selected.isEmpty()) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(selected.size() == 1 ? "Delete this item?" : "Delete " + selected.size() + " items?")
                .setMessage("This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    // Highest index first so earlier indices stay valid as
                    // each delete request is queued (bridge is one-shot, so
                    // queue them with small delays — same pattern used for
                    // multi-forward sends elsewhere in this codebase).
                    java.util.Collections.sort(selected, java.util.Collections.reverseOrder());
                    for (int idx = 0; idx < selected.size(); idx++) {
                        final int pos = selected.get(idx);
                        binding.getRoot().postDelayed(() ->
                            com.callx.app.conversation.GalleryItemActionBridge.request(
                                replyChatId, replyMessageId, pos,
                                com.callx.app.conversation.GalleryItemActionBridge.ACTION_DELETE_ITEM, null),
                            idx * 50L);
                    }
                    exitSelectMode();
                    finish();
                    overridePendingTransition(0, 0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void starSelection() {
        if (galleryAdapter == null || replyChatId == null || replyMessageId == null) return;
        java.util.List<Integer> selected = galleryAdapter.getSelectedPositions();
        for (int idx = 0; idx < selected.size(); idx++) {
            final int pos = selected.get(idx);
            binding.getRoot().postDelayed(() ->
                com.callx.app.conversation.GalleryItemActionBridge.request(
                    replyChatId, replyMessageId, pos,
                    com.callx.app.conversation.GalleryItemActionBridge.ACTION_STAR_ITEM, null),
                idx * 50L);
        }
        android.widget.Toast.makeText(this, "Starred", android.widget.Toast.LENGTH_SHORT).show();
        exitSelectMode();
    }

    // ── #4/#2 — single-item more-options menu (delete / star / edit caption) ─
    private void showMoreOptionsMenu() {
        boolean isGalleryMode = galleryItems != null && !galleryItems.isEmpty();
        java.util.List<String> labels = new ArrayList<>();
        labels.add("Save to gallery");
        labels.add("Share");
        if (isGalleryMode && replyChatId != null && replyMessageId != null) {
            labels.add("Select multiple");
            labels.add("Remove this item from group");
            labels.add("Star this item");
            labels.add("Edit caption for this item");
        }
        new android.app.AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String chosen = labels.get(which);
                    switch (chosen) {
                        case "Save to gallery": saveCurrentToGallery(); break;
                        case "Share": shareMedia(sharedUrl); break;
                        case "Select multiple": enterSelectMode(galleryActivePos); break;
                        case "Remove this item from group": deleteSingleActiveItem(); break;
                        case "Star this item": starSingleActiveItem(); break;
                        case "Edit caption for this item": editCaptionForActiveItem(); break;
                        default: break;
                    }
                })
                .show();
    }

    private void deleteSingleActiveItem() {
        if (galleryActivePos < 0 || replyChatId == null || replyMessageId == null) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Delete this item?")
                .setMessage("This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    com.callx.app.conversation.GalleryItemActionBridge.request(
                            replyChatId, replyMessageId, galleryActivePos,
                            com.callx.app.conversation.GalleryItemActionBridge.ACTION_DELETE_ITEM, null);
                    finish();
                    overridePendingTransition(0, 0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void starSingleActiveItem() {
        if (galleryActivePos < 0 || replyChatId == null || replyMessageId == null) return;
        com.callx.app.conversation.GalleryItemActionBridge.request(
                replyChatId, replyMessageId, galleryActivePos,
                com.callx.app.conversation.GalleryItemActionBridge.ACTION_STAR_ITEM, null);
        android.widget.Toast.makeText(this, "Starred", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void editCaptionForActiveItem() {
        if (galleryActivePos < 0 || replyChatId == null || replyMessageId == null) return;
        final android.widget.EditText input = new android.widget.EditText(this);
        Object existing = galleryItems.get(galleryActivePos).get("caption");
        if (existing instanceof String) input.setText((String) existing);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Caption for this photo")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String newCaption = input.getText() != null ? input.getText().toString().trim() : "";
                    com.callx.app.conversation.GalleryItemActionBridge.request(
                            replyChatId, replyMessageId, galleryActivePos,
                            com.callx.app.conversation.GalleryItemActionBridge.ACTION_EDIT_CAPTION, newCaption);
                    // Reflect immediately in this still-open viewer too.
                    galleryItems.get(galleryActivePos).put("caption", newCaption);
                    if (galleryAdapter != null) galleryAdapter.notifyItemChanged(galleryActivePos);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── #3 — Save current media to device gallery ────────────────────────
    private void saveCurrentToGallery() {
        if (sharedUrl == null || sharedUrl.isEmpty()) return;
        boolean isVideo = isGalleryActiveVideo();
        android.widget.Toast.makeText(this, "Saving…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File cached = MediaCache.getCached(this, sharedUrl);
                File source;
                if (cached != null) {
                    source = cached;
                } else {
                    // Blocking download fallback (off main thread already).
                    java.io.InputStream in = new java.net.URL(sharedUrl).openStream();
                    File tmp = File.createTempFile("save_", isVideo ? ".mp4" : ".jpg", getCacheDir());
                    try (java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    in.close();
                    source = tmp;
                }
                String displayName = "CallX2_" + System.currentTimeMillis() + (isVideo ? ".mp4" : ".jpg");
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
                Uri collection;
                if (isVideo) {
                    values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/CallX2");
                    collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else {
                    values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CallX2");
                    collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                Uri dest = getContentResolver().insert(collection, values);
                if (dest != null) {
                    try (java.io.InputStream in = new java.io.FileInputStream(source);
                         java.io.OutputStream out = getContentResolver().openOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    runOnUiThread(() -> android.widget.Toast.makeText(this, "Saved to gallery", android.widget.Toast.LENGTH_SHORT).show());
                } else {
                    runOnUiThread(() -> android.widget.Toast.makeText(this, "Save failed", android.widget.Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this, "Save failed", android.widget.Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private boolean isGalleryActiveVideo() {
        if (galleryItems != null && galleryActivePos >= 0 && galleryActivePos < galleryItems.size()) {
            return "video".equals(galleryItems.get(galleryActivePos).get("mediaType"));
        }
        return "video".equals(getIntent().getStringExtra("type"));
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
