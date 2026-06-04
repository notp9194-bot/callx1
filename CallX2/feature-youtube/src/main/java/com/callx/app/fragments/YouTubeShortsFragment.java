package com.callx.app.fragments;

import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.activities.YouTubeAddToPlaylistSheet;
import com.callx.app.activities.YouTubeChannelActivity;
import com.callx.app.activities.YouTubeCommentsActivity;
import com.callx.app.activities.YouTubeSearchActivity;
import com.callx.app.activities.YouTubeUploadActivity;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeShortsFragment — Production-level YouTube Shorts player
 *
 * Features:
 * ▸ Full-screen vertical paging (PagerSnapHelper)
 * ▸ ExoPlayer pool with preload (prev / current / next)
 * ▸ Like / Dislike with Firebase transactions
 * ▸ Subscribe / Unsubscribe per channel
 * ▸ Comments → YouTubeCommentsActivity
 * ▸ Share, Copy Link
 * ▸ Save to Watch Later
 * ▸ Add to Playlist (existing sheet)
 * ▸ Download (DownloadManager)
 * ▸ Not Interested / Report
 * ▸ Mute toggle (persisted in SharedPrefs)
 * ▸ Progress bar synced to playback
 * ▸ Buffering spinner
 * ▸ Pause/Play overlay on single tap
 * ▸ Double-tap to like with heart animation
 * ▸ Expandable description
 * ▸ Music strip with marquee
 * ▸ View count increment after 5 s watch
 * ▸ Infinite scroll with paginated Firebase loads
 */
public class YouTubeShortsFragment extends Fragment {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final String PREFS_NAME   = "yt_shorts_prefs";
    private static final String PREF_MUTED   = "shorts_muted";
    private static final int    PAGE_SIZE    = 20;
    private static final long   VIEW_THRESHOLD_MS = 5000L;

    // ── Views ──────────────────────────────────────────────────────────────
    private RecyclerView     rvShorts;
    private ProgressBar      pbLoading;
    private ShortsAdapter    adapter;
    private LinearLayoutManager llm;

    // ── State ──────────────────────────────────────────────────────────────
    private int  currentPos = 0;
    private boolean muted   = false;
    private boolean isLoading = false;
    private String  lastKey  = null;          // Firebase pagination cursor
    private ValueEventListener shortsListener;
    private SharedPreferences prefs;
    private String myUid = "";

    @Override
    public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        muted = prefs.getBoolean(PREF_MUTED, false);
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u != null) myUid = u.getUid();
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_shorts, parent, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        pbLoading = view.findViewById(R.id.pb_shorts_loading);
        rvShorts  = view.findViewById(R.id.rv_yt_shorts);

        llm = new LinearLayoutManager(requireContext());
        rvShorts.setLayoutManager(llm);
        rvShorts.setItemAnimator(null);

        new PagerSnapHelper().attachToRecyclerView(rvShorts);

        adapter = new ShortsAdapter();
        rvShorts.setAdapter(adapter);

        // Top bar buttons
        View btnBack = view.findViewById(R.id.btn_shorts_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> {
            if (getActivity() != null) getActivity().onBackPressed();
        });
        View btnSearch = view.findViewById(R.id.btn_shorts_search);
        if (btnSearch != null) btnSearch.setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeSearchActivity.class)));
        View btnCamera = view.findViewById(R.id.btn_shorts_camera);
        if (btnCamera != null) btnCamera.setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeUploadActivity.class)
                .putExtra("mode", "short")));

        // Infinite scroll — load more when reaching last 3 items
        rvShorts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int pos = llm.findFirstCompletelyVisibleItemPosition();
                    if (pos == RecyclerView.NO_ID) pos = llm.findFirstVisibleItemPosition();
                    if (pos >= 0 && pos != currentPos) {
                        adapter.pauseAt(currentPos);
                        currentPos = pos;
                        adapter.playAt(currentPos);
                    }
                    // Load more near end
                    if (pos >= adapter.getItemCount() - 3 && !isLoading) {
                        loadMoreShorts();
                    }
                }
            }
        });

        showLoading(true);
        loadInitialShorts();
    }

    // ── Firebase loading ────────────────────────────────────────────────────

    private void loadInitialShorts() {
        isLoading = true;
        YouTubeFirebaseUtils.shortsRef()
            .orderByChild("uploadedAt")
            .limitToLast(PAGE_SIZE)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded()) return;
                    List<YouTubeVideo> list = new ArrayList<>();
                    String firstKey = null;
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v != null && isValidShort(v)) {
                            if (firstKey == null) firstKey = ds.getKey();
                            list.add(0, v); // newest first
                        }
                    }
                    lastKey = firstKey;
                    adapter.setData(list);
                    showLoading(false);
                    isLoading = false;
                    if (!list.isEmpty()) {
                        adapter.playAt(0);
                        currentPos = 0;
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    isLoading = false; showLoading(false);
                }
            });
    }

    private void loadMoreShorts() {
        if (lastKey == null || isLoading) return;
        isLoading = true;
        YouTubeFirebaseUtils.shortsRef()
            .orderByChild("uploadedAt")
            .endAt(null, lastKey)
            .limitToLast(PAGE_SIZE + 1)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded()) return;
                    List<YouTubeVideo> list = new ArrayList<>();
                    String newLastKey = null;
                    for (DataSnapshot ds : snap.getChildren()) {
                        if (ds.getKey() != null && ds.getKey().equals(lastKey)) continue;
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v != null && isValidShort(v)) {
                            if (newLastKey == null) newLastKey = ds.getKey();
                            list.add(0, v);
                        }
                    }
                    if (!list.isEmpty()) {
                        lastKey = newLastKey;
                        adapter.appendData(list);
                    }
                    isLoading = false;
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { isLoading = false; }
            });
    }

    private boolean isValidShort(YouTubeVideo v) {
        return v.isShort
            && "public".equals(v.visibility)
            && v.videoUrl != null
            && !v.videoUrl.trim().isEmpty();
    }

    private void showLoading(boolean show) {
        if (pbLoading != null)
            pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Override public void onPause()  { super.onPause();  adapter.pauseAt(currentPos); }
    @Override public void onResume() { super.onResume(); adapter.playAt(currentPos); }
    @Override public void onDestroyView() { super.onDestroyView(); adapter.releaseAll(); }

    // ═══════════════════════════════════════════════════════════════════════
    //  ShortsAdapter
    // ═══════════════════════════════════════════════════════════════════════

    class ShortsAdapter extends RecyclerView.Adapter<ShortsAdapter.VH> {

        private final List<YouTubeVideo>                   data    = new ArrayList<>();
        private final android.util.SparseArray<ExoPlayer>  players = new android.util.SparseArray<>();
        private final android.util.SparseArray<Handler>    progressHandlers = new android.util.SparseArray<>();

        void setData(List<YouTubeVideo> d) {
            releaseAll();
            data.clear();
            data.addAll(d);
            notifyDataSetChanged();
        }

        void appendData(List<YouTubeVideo> more) {
            int start = data.size();
            data.addAll(more);
            notifyItemRangeInserted(start, more.size());
        }

        void playAt(int pos) {
            ExoPlayer p = players.get(pos);
            if (p != null) { p.setVolume(muted ? 0f : 1f); p.play(); }
            startProgressUpdater(pos);
            preloadNeighbours(pos);
        }

        void pauseAt(int pos) {
            ExoPlayer p = players.get(pos);
            if (p != null) p.pause();
            stopProgressUpdater(pos);
        }

        void releaseAll() {
            for (int i = 0; i < players.size(); i++) {
                try { players.valueAt(i).release(); } catch (Exception ignored) {}
            }
            players.clear();
            for (int i = 0; i < progressHandlers.size(); i++) {
                progressHandlers.valueAt(i).removeCallbacksAndMessages(null);
            }
            progressHandlers.clear();
        }

        // Preload prev + next players so swipe feels instant
        private void preloadNeighbours(int pos) {
            int[] neighbours = {pos - 1, pos + 1};
            for (int n : neighbours) {
                if (n < 0 || n >= data.size()) continue;
                if (players.get(n) != null) continue;
                ExoPlayer p = buildPlayer(n);
                p.prepare();
                p.pause();
            }
            // Release players far from current to save memory
            List<Integer> toRelease = new ArrayList<>();
            for (int i = 0; i < players.size(); i++) {
                int k = players.keyAt(i);
                if (Math.abs(k - pos) > 2) toRelease.add(k);
            }
            for (int k : toRelease) {
                players.get(k).release();
                players.remove(k);
            }
        }

        private ExoPlayer buildPlayer(int pos) {
            ExoPlayer p = new ExoPlayer.Builder(requireContext()).build();
            String url = resolveUrl(data.get(pos).videoUrl);
            p.setMediaItem(MediaItem.fromUri(url));
            p.setRepeatMode(Player.REPEAT_MODE_ONE);
            p.setVolume(muted ? 0f : 1f);
            players.put(pos, p);
            return p;
        }

        private String resolveUrl(String url) {
            if (url == null) return "";
            if (url.contains("cloudinary.com") && !url.contains("f_mp4"))
                return url.replaceFirst("/upload/", "/upload/f_mp4/");
            return url;
        }

        private void startProgressUpdater(int pos) {
            stopProgressUpdater(pos);
            Handler h = new Handler(Looper.getMainLooper());
            progressHandlers.put(pos, h);
            // Find VH and update progress bar
            Runnable tick = new Runnable() {
                @Override public void run() {
                    ExoPlayer p = players.get(pos);
                    if (p == null || !isAdded()) return;
                    RecyclerView.ViewHolder vh = rvShorts.findViewHolderForAdapterPosition(pos);
                    if (vh instanceof VH) {
                        VH v = (VH) vh;
                        long dur = p.getDuration();
                        long cur = p.getCurrentPosition();
                        if (dur > 0 && v.pbProgress != null) {
                            v.pbProgress.setProgress((int)(cur * 1000 / dur));
                        }
                    }
                    h.postDelayed(this, 200);
                }
            };
            h.post(tick);
        }

        private void stopProgressUpdater(int pos) {
            Handler h = progressHandlers.get(pos);
            if (h != null) { h.removeCallbacksAndMessages(null); progressHandlers.remove(pos); }
        }

        // ── RecyclerView callbacks ─────────────────────────────────────────

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_youtube_short, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            if (pos < 0 || pos >= data.size()) return;
            YouTubeVideo video = data.get(pos);

            // ── Player setup ──────────────────────────────────────────────
            ExoPlayer old = players.get(pos);
            if (old == null) old = buildPlayer(pos);
            final ExoPlayer player = old;
            h.playerView.setPlayer(player);
            player.prepare();

            // Buffering / thumbnail visibility
            h.pbBuffer.setVisibility(View.VISIBLE);
            h.ivThumb.setVisibility(View.VISIBLE);
            if (video.thumbnailUrl != null && !video.thumbnailUrl.isEmpty())
                Glide.with(requireContext()).load(video.thumbnailUrl).centerCrop().into(h.ivThumb);

            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (!isAdded()) return;
                    boolean ready = state == Player.STATE_READY || state == Player.STATE_ENDED;
                    h.pbBuffer.setVisibility(ready ? View.GONE : View.VISIBLE);
                    if (state == Player.STATE_READY) h.ivThumb.setVisibility(View.GONE);
                }
                @Override public void onPlayerError(@NonNull PlaybackException error) {
                    h.pbBuffer.setVisibility(View.GONE);
                }
            });

            // ── Mute button ───────────────────────────────────────────────
            updateMuteIcon(h.btnMute, muted);
            h.btnMute.setOnClickListener(v -> {
                muted = !muted;
                prefs.edit().putBoolean(PREF_MUTED, muted).apply();
                // Apply to ALL active players
                for (int i = 0; i < players.size(); i++)
                    players.valueAt(i).setVolume(muted ? 0f : 1f);
                updateMuteIcon(h.btnMute, muted);
            });

            // ── Channel info ──────────────────────────────────────────────
            h.tvChannel.setText("@" + safe(video.uploaderName));
            h.tvTitle.setText(safe(video.title));
            h.tvViews.setText(formatCount(video.viewCount) + " views");
            h.tvDuration.setText(formatDuration(video.duration));
            h.tvLikes.setText(formatCount(video.likeCount));
            h.tvComments.setText(formatCount(video.commentCount));

            if (video.uploaderPhotoUrl != null && !video.uploaderPhotoUrl.isEmpty())
                Glide.with(requireContext()).load(video.uploaderPhotoUrl)
                    .circleCrop().into(h.ivAvatar);

            // Description expand/collapse
            String desc = safe(video.description);
            boolean hasDesc = !desc.isEmpty();
            if (hasDesc) {
                h.tvMoreDesc.setVisibility(View.VISIBLE);
                h.tvMoreDesc.setText("more...");
                h.tvMoreDesc.setOnClickListener(v -> {
                    boolean shown = h.tvDescription.getVisibility() == View.VISIBLE;
                    h.tvDescription.setVisibility(shown ? View.GONE : View.VISIBLE);
                    h.tvTitle.setMaxLines(shown ? 2 : 5);
                    h.tvMoreDesc.setText(shown ? "more..." : "less");
                });
                h.tvDescription.setText(desc);
            } else {
                h.tvMoreDesc.setVisibility(View.GONE);
                h.tvDescription.setVisibility(View.GONE);
            }

            // Music strip (use title as music info if it has # or ♪)
            String musicInfo = extractMusicInfo(video);
            if (musicInfo != null) {
                h.llMusicStrip.setVisibility(View.VISIBLE);
                h.tvMusic.setText(musicInfo);
                h.tvMusic.setSelected(true); // start marquee
            } else {
                h.llMusicStrip.setVisibility(View.GONE);
            }

            // ── Subscription state ────────────────────────────────────────
            String uploaderUid = safe(video.uploaderUid);
            final boolean[] subbed = {false};
            if (!myUid.isEmpty() && !uploaderUid.isEmpty()) {
                YouTubeFirebaseUtils.subscriptionsRef(myUid).child(uploaderUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            subbed[0] = s.exists();
                            updateFollowBtn(h.btnFollow, subbed[0]);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
            }
            h.btnFollow.setOnClickListener(v -> {
                if (myUid.isEmpty()) { toast("Pehle login karo"); return; }
                subbed[0] = !subbed[0];
                updateFollowBtn(h.btnFollow, subbed[0]);
                if (subbed[0]) {
                    YouTubeFirebaseUtils.subscriptionsRef(myUid).child(uploaderUid).setValue(true);
                    YouTubeFirebaseUtils.subscribersRef(uploaderUid).child(myUid).setValue(true);
                    toast("Subscribe ho gaye!");
                } else {
                    YouTubeFirebaseUtils.subscriptionsRef(myUid).child(uploaderUid).removeValue();
                    YouTubeFirebaseUtils.subscribersRef(uploaderUid).child(myUid).removeValue();
                    toast("Unsubscribe ho gaye");
                }
            });

            // Avatar / channel name → open channel
            h.ivAvatar.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), YouTubeChannelActivity.class)
                    .putExtra("uid", uploaderUid)));
            h.tvChannel.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), YouTubeChannelActivity.class)
                    .putExtra("uid", uploaderUid)));

            // ── Like / Dislike ────────────────────────────────────────────
            final boolean[] liked   = {false};
            final boolean[] disliked = {false};
            if (!myUid.isEmpty() && video.videoId != null) {
                YouTubeFirebaseUtils.likedVideosRef(myUid).child(video.videoId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            liked[0] = s.exists();
                            h.btnLike.setImageResource(liked[0]
                                ? R.drawable.ic_yt_like_filled : R.drawable.ic_yt_like);
                            h.btnLike.setColorFilter(liked[0] ? 0xFFFF0000 : 0xFFFFFFFF);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
            }

            h.btnLike.setOnClickListener(v -> {
                if (myUid.isEmpty()) { toast("Pehle login karo"); return; }
                if (video.videoId == null) return;
                if (disliked[0]) { // undo dislike first
                    disliked[0] = false;
                    h.btnDislike.setColorFilter(0xFFFFFFFF);
                }
                liked[0] = !liked[0];
                h.btnLike.setImageResource(liked[0] ? R.drawable.ic_yt_like_filled : R.drawable.ic_yt_like);
                h.btnLike.setColorFilter(liked[0] ? 0xFFFF0000 : 0xFFFFFFFF);
                DatabaseReference likeRef  = YouTubeFirebaseUtils.likedVideosRef(myUid).child(video.videoId);
                DatabaseReference countRef = YouTubeFirebaseUtils.videoRef(video.videoId).child("likeCount");
                if (liked[0]) {
                    likeRef.setValue(true);
                    video.likeCount++;
                    incrementCounter(countRef, 1);
                } else {
                    likeRef.removeValue();
                    video.likeCount = Math.max(0, video.likeCount - 1);
                    incrementCounter(countRef, -1);
                }
                h.tvLikes.setText(formatCount(video.likeCount));
            });

            h.btnDislike.setOnClickListener(v -> {
                if (myUid.isEmpty()) { toast("Pehle login karo"); return; }
                if (liked[0]) { // undo like
                    liked[0] = false;
                    h.btnLike.setImageResource(R.drawable.ic_yt_like);
                    h.btnLike.setColorFilter(0xFFFFFFFF);
                    video.likeCount = Math.max(0, video.likeCount - 1);
                    h.tvLikes.setText(formatCount(video.likeCount));
                    if (video.videoId != null) {
                        YouTubeFirebaseUtils.likedVideosRef(myUid).child(video.videoId).removeValue();
                        incrementCounter(YouTubeFirebaseUtils.videoRef(video.videoId).child("likeCount"), -1);
                    }
                }
                disliked[0] = !disliked[0];
                h.btnDislike.setColorFilter(disliked[0] ? 0xFFFF0000 : 0xFFFFFFFF);
                toast(disliked[0] ? "Dislike kiya" : "Dislike hataya");
            });

            // ── Comments ──────────────────────────────────────────────────
            h.btnComment.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), YouTubeCommentsActivity.class)
                    .putExtra("video_id", video.videoId)));

            // ── Share ─────────────────────────────────────────────────────
            h.btnShare.setOnClickListener(v -> shareVideo(video));

            // ── Save to Watch Later ───────────────────────────────────────
            h.btnSave.setOnClickListener(v -> {
                if (myUid.isEmpty()) { toast("Pehle login karo"); return; }
                if (video.videoId == null) return;
                YouTubeFirebaseUtils.watchLaterRef(myUid).child(video.videoId)
                    .setValue(System.currentTimeMillis())
                    .addOnSuccessListener(u -> toast("Watch Later mein save hua!"))
                    .addOnFailureListener(e -> toast("Error, dobara try karo"));
            });

            // ── Remix (upload with this audio) ────────────────────────────
            h.btnRemix.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), YouTubeUploadActivity.class)
                    .putExtra("mode", "short")
                    .putExtra("remix_url", video.videoUrl)
                    .putExtra("remix_title", video.title)));

            // ── More options sheet ────────────────────────────────────────
            h.btnMore.setOnClickListener(v -> showMoreSheet(video));

            // ── View count after 5s ───────────────────────────────────────
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isAdded() || players.get(pos) == null) return;
                ExoPlayer p = players.get(pos);
                if (p != null && p.isPlaying() && video.videoId != null) {
                    incrementCounter(
                        YouTubeFirebaseUtils.videoRef(video.videoId).child("viewCount"), 1);
                    video.viewCount++;
                }
            }, VIEW_THRESHOLD_MS);

            // ── Tap gestures: single tap = pause/play, double tap = like ──
            h.playerView.setOnTouchListener(buildTapListener(h, player, video, liked));
        }

        @Override public void onViewRecycled(@NonNull VH h) {
            super.onViewRecycled(h);
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_ID && pos != currentPos) {
                ExoPlayer p = players.get(pos);
                if (p != null) { p.pause(); }
            }
        }

        @Override public int getItemCount() { return data.size(); }

        // ── Tap listener builder ──────────────────────────────────────────
        private View.OnTouchListener buildTapListener(VH h, ExoPlayer player,
                YouTubeVideo video, boolean[] liked) {
            return new View.OnTouchListener() {
                private final Handler tapHandler = new Handler(Looper.getMainLooper());
                private boolean pendingSingle = false;
                private float touchX, touchY;

                @Override public boolean onTouch(View view, MotionEvent e) {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        touchX = e.getX(); touchY = e.getY();
                    }
                    if (e.getAction() == MotionEvent.ACTION_UP) {
                        if (pendingSingle) {
                            // double tap
                            pendingSingle = false;
                            tapHandler.removeCallbacksAndMessages(null);
                            if (!liked[0]) h.btnLike.performClick();
                            showHeartAt(h, touchX, touchY);
                        } else {
                            pendingSingle = true;
                            tapHandler.postDelayed(() -> {
                                pendingSingle = false;
                                // single tap = pause/play with overlay
                                if (player.isPlaying()) {
                                    player.pause();
                                    showPauseOverlay(h, false);
                                } else {
                                    player.play();
                                    showPauseOverlay(h, true);
                                }
                            }, 220);
                        }
                    }
                    return true;
                }
            };
        }

        // ── UI helpers ────────────────────────────────────────────────────

        private void showPauseOverlay(VH h, boolean playingNow) {
            if (h.flPauseOverlay == null) return;
            h.ivPauseIcon.setImageResource(playingNow ? R.drawable.ic_yt_play : R.drawable.ic_yt_play);
            h.flPauseOverlay.setAlpha(1f);
            h.flPauseOverlay.setVisibility(View.VISIBLE);
            h.flPauseOverlay.animate().alpha(0f).setDuration(700).withEndAction(() ->
                h.flPauseOverlay.setVisibility(View.GONE)).start();
        }

        private void showHeartAt(VH h, float x, float y) {
            if (h.ivHeartAnim == null) return;
            h.ivHeartAnim.setX(x - 45);
            h.ivHeartAnim.setY(y - 45);
            h.ivHeartAnim.setScaleX(0f); h.ivHeartAnim.setScaleY(0f); h.ivHeartAnim.setAlpha(1f);
            h.ivHeartAnim.setVisibility(View.VISIBLE);
            h.ivHeartAnim.animate().scaleX(1.4f).scaleY(1.4f).setDuration(180).withEndAction(() ->
                h.ivHeartAnim.animate().scaleX(1f).scaleY(1f).setDuration(100).withEndAction(() ->
                    h.ivHeartAnim.animate().alpha(0f).setDuration(500).withEndAction(() ->
                        h.ivHeartAnim.setVisibility(View.GONE)).start()).start()).start();
        }

        private void updateMuteIcon(ImageButton btn, boolean muted) {
            btn.setImageResource(muted ? R.drawable.ic_yt_mute : R.drawable.ic_yt_volume);
        }

        private void updateFollowBtn(Button btn, boolean subscribed) {
            btn.setText(subscribed ? "Subscribed" : "Subscribe");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                subscribed ? 0xFF606060 : 0xFFFF0000));
        }

        @Nullable private String extractMusicInfo(YouTubeVideo v) {
            if (v.tags != null && v.tags.contains("music")) return v.title + " · Original audio";
            return null;
        }

        // ── More options bottom sheet ──────────────────────────────────────

        private void showMoreSheet(YouTubeVideo video) {
            BottomSheetDialog sheet = new BottomSheetDialog(requireContext(),
                com.google.android.material.R.style.Theme_Material3_BottomSheetDialog);
            View sv = LayoutInflater.from(requireContext())
                .inflate(R.layout.bottom_sheet_yt_shorts_more, null);
            sheet.setContentView(sv);

            sv.findViewById(R.id.item_shorts_more_watch_later).setOnClickListener(v -> {
                sheet.dismiss();
                if (myUid.isEmpty()) { toast("Pehle login karo"); return; }
                if (video.videoId == null) return;
                YouTubeFirebaseUtils.watchLaterRef(myUid).child(video.videoId)
                    .setValue(System.currentTimeMillis())
                    .addOnSuccessListener(u -> toast("Watch Later mein save hua!"));
            });

            sv.findViewById(R.id.item_shorts_more_add_playlist).setOnClickListener(v -> {
                sheet.dismiss();
                if (video.videoId != null)
                    YouTubeAddToPlaylistSheet.newInstance(video.videoId)
                        .show(getChildFragmentManager(), "add_playlist");
            });

            sv.findViewById(R.id.item_shorts_more_download).setOnClickListener(v -> {
                sheet.dismiss(); downloadVideo(video);
            });

            sv.findViewById(R.id.item_shorts_more_share).setOnClickListener(v -> {
                sheet.dismiss(); shareVideo(video);
            });

            sv.findViewById(R.id.item_shorts_more_copy_link).setOnClickListener(v -> {
                sheet.dismiss();
                ClipboardManager cm = (ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("link",
                    "https://callx.app/shorts/" + video.videoId));
                toast("Link copy ho gaya!");
            });

            sv.findViewById(R.id.item_shorts_more_not_interested).setOnClickListener(v -> {
                sheet.dismiss();
                if (myUid.isEmpty() || video.videoId == null) return;
                YouTubeFirebaseUtils.notInterestedRef(myUid, video.videoId).setValue(true);
                // Remove from current list
                int idx = data.indexOf(video);
                if (idx >= 0) { data.remove(idx); notifyItemRemoved(idx); }
                toast("Yeh video aapko aur nahi dikhega");
            });

            sv.findViewById(R.id.item_shorts_more_report).setOnClickListener(v -> {
                sheet.dismiss();
                if (myUid.isEmpty() || video.videoId == null) return;
                YouTubeFirebaseUtils.reportsRef(video.videoId, myUid).setValue(true);
                toast("Report bhej di gayi, shukriya!");
            });

            sheet.show();
        }

        // ── Actions ───────────────────────────────────────────────────────

        private void shareVideo(YouTubeVideo video) {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT,
                (safe(video.title).isEmpty() ? "Yeh dekho" : safe(video.title))
                + "\nhttps://callx.app/shorts/" + video.videoId);
            startActivity(Intent.createChooser(i, "Share karo"));
        }

        private void downloadVideo(YouTubeVideo video) {
            if (video.videoUrl == null || video.videoUrl.isEmpty()) {
                toast("Download link available nahi hai"); return;
            }
            try {
                DownloadManager dm = (DownloadManager)
                    requireContext().getSystemService(Context.DOWNLOAD_SERVICE);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(video.videoUrl));
                String fname = "CallX_Short_" + (video.videoId != null ? video.videoId : "video") + ".mp4";
                req.setTitle(safe(video.title));
                req.setDescription("CallX Shorts se download ho raha hai");
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, fname);
                dm.enqueue(req);
                toast("Download shuru ho gaya!");
            } catch (Exception e) {
                toast("Download nahi hua: " + e.getMessage());
            }
        }

        // ── Firebase transaction helper ───────────────────────────────────
        private void incrementCounter(DatabaseReference ref, int delta) {
            ref.runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long c = d.getValue(Long.class);
                    d.setValue(c == null ? Math.max(0, delta) : Math.max(0, c + delta));
                    return Transaction.success(d);
                }
                @Override
                public void onComplete(@Nullable DatabaseError e, boolean b, @Nullable DataSnapshot s) {}
            });
        }

        // ── Format helpers ────────────────────────────────────────────────
        private String formatCount(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }
        private String formatDuration(long sec) {
            if (sec <= 0) return "";
            return String.format("%d:%02d", sec / 60, sec % 60);
        }
        private String safe(@Nullable String s) { return s != null ? s : ""; }

        // ── ViewHolder ────────────────────────────────────────────────────
        class VH extends RecyclerView.ViewHolder {
            PlayerView      playerView;
            ImageView       ivThumb, ivHeartAnim, ivPauseIcon;
            FrameLayout     flPauseOverlay; // referenced via View cast
            View            flPauseOverlayView;
            ProgressBar     pbBuffer, pbProgress;
            ImageButton     btnMute, btnLike, btnDislike, btnComment, btnShare,
                            btnSave, btnRemix, btnMore;
            Button          btnFollow;
            CircleImageView ivAvatar;
            TextView        tvChannel, tvTitle, tvDescription, tvMoreDesc,
                            tvLikes, tvComments, tvViews, tvDuration, tvMusic;
            LinearLayout    llMusicStrip;
            android.widget.FrameLayout flPause;

            VH(@NonNull View v) {
                super(v);
                playerView    = v.findViewById(R.id.pv_short_player);
                ivThumb       = v.findViewById(R.id.iv_short_thumb);
                ivHeartAnim   = v.findViewById(R.id.iv_short_heart_anim);
                flPause       = v.findViewById(R.id.fl_short_pause_overlay);
                ivPauseIcon   = v.findViewById(R.id.iv_short_pause_icon);
                flPauseOverlay = flPause;
                pbBuffer      = v.findViewById(R.id.pb_short_buffer);
                pbProgress    = v.findViewById(R.id.pb_short_progress);
                btnMute       = v.findViewById(R.id.btn_short_mute);
                btnLike       = v.findViewById(R.id.btn_short_like);
                btnDislike    = v.findViewById(R.id.btn_short_dislike);
                btnComment    = v.findViewById(R.id.btn_short_comment);
                btnShare      = v.findViewById(R.id.btn_short_share);
                btnSave       = v.findViewById(R.id.btn_short_save);
                btnRemix      = v.findViewById(R.id.btn_short_remix);
                btnMore       = v.findViewById(R.id.btn_short_more);
                btnFollow     = v.findViewById(R.id.btn_short_follow);
                ivAvatar      = v.findViewById(R.id.iv_short_avatar);
                tvChannel     = v.findViewById(R.id.tv_short_channel);
                tvTitle       = v.findViewById(R.id.tv_short_title);
                tvDescription = v.findViewById(R.id.tv_short_description);
                tvMoreDesc    = v.findViewById(R.id.tv_short_more_desc);
                tvLikes       = v.findViewById(R.id.tv_short_likes);
                tvComments    = v.findViewById(R.id.tv_short_comments);
                tvViews       = v.findViewById(R.id.tv_short_views);
                tvDuration    = v.findViewById(R.id.tv_short_duration);
                tvMusic       = v.findViewById(R.id.tv_short_music);
                llMusicStrip  = v.findViewById(R.id.ll_short_music_strip);
            }

            // Helper so inner adapter can access it
            android.widget.FrameLayout getFlPauseOverlay() { return flPause; }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private void toast(String msg) {
        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
