package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.activities.YouTubeChannelActivity;
import com.callx.app.activities.YouTubeCommentsActivity;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeShortsFragment — Real YouTube Shorts experience
 *
 * Full-screen vertical swipe player with:
 * - ExoPlayer per item (current item auto-plays, others pause)
 * - Right-side action buttons: Like, Dislike, Comment, Share, More
 * - Channel avatar + follow button overlay
 * - Title + description overlay at bottom
 * - Smooth PagerSnapHelper vertical swipe
 */
public class YouTubeShortsFragment extends Fragment {

    private RecyclerView    rvShorts;
    private ShortsAdapter   adapter;
    private ValueEventListener shortsListener;
    private LinearLayoutManager llm;
    private int currentPlayingPos = 0;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_shorts, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        rvShorts = view.findViewById(R.id.rv_yt_shorts);
        llm = new LinearLayoutManager(requireContext());
        rvShorts.setLayoutManager(llm);

        PagerSnapHelper snap = new PagerSnapHelper();
        snap.attachToRecyclerView(rvShorts);

        adapter = new ShortsAdapter(requireActivity());
        rvShorts.setAdapter(adapter);

        // Jab scroll ruk jaye tab current item play karo, baaki pause karo
        rvShorts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    int pos = llm.findFirstCompletelyVisibleItemPosition();
                    if (pos == RecyclerView.NO_ID) pos = llm.findFirstVisibleItemPosition();
                    if (pos >= 0 && pos != currentPlayingPos) {
                        adapter.pausePlayer(currentPlayingPos);
                        currentPlayingPos = pos;
                        adapter.playPlayer(currentPlayingPos);
                    }
                }
            }
        });

        loadShorts();
    }

    private void loadShorts() {
        shortsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v != null && v.isShort && "public".equals(v.visibility)
                            && v.videoUrl != null && !v.videoUrl.trim().isEmpty())
                        list.add(0, v);
                }
                adapter.setData(list);
                if (!list.isEmpty()) {
                    adapter.playPlayer(0);
                    currentPlayingPos = 0;
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(50)
            .addValueEventListener(shortsListener);
    }

    @Override public void onPause() {
        super.onPause();
        adapter.pausePlayer(currentPlayingPos);
    }

    @Override public void onResume() {
        super.onResume();
        adapter.playPlayer(currentPlayingPos);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        adapter.releaseAll();
        if (shortsListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(shortsListener);
    }

    // ── Shorts Adapter ────────────────────────────────────────────────────────

    static class ShortsAdapter extends RecyclerView.Adapter<ShortsAdapter.VH> {

        private final androidx.fragment.app.FragmentActivity ctx;
        private List<YouTubeVideo> data = new ArrayList<>();
        private final android.util.SparseArray<ExoPlayer> players = new android.util.SparseArray<>();

        ShortsAdapter(androidx.fragment.app.FragmentActivity ctx) { this.ctx = ctx; }

        void setData(List<YouTubeVideo> d) {
            releaseAll();
            data = d;
            notifyDataSetChanged();
        }

        void playPlayer(int pos) {
            ExoPlayer p = players.get(pos);
            if (p != null) p.play();
        }

        void pausePlayer(int pos) {
            ExoPlayer p = players.get(pos);
            if (p != null) p.pause();
        }

        void releaseAll() {
            for (int i = 0; i < players.size(); i++) players.valueAt(i).release();
            players.clear();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(ctx).inflate(R.layout.item_youtube_short, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            YouTubeVideo video = data.get(pos);

            // Release old player at this position
            ExoPlayer old = players.get(pos);
            if (old != null) { old.release(); players.remove(pos); }

            // Create new ExoPlayer
            ExoPlayer player = new ExoPlayer.Builder(ctx).build();
            players.put(pos, player);
            h.playerView.setPlayer(player);
            h.playerView.setUseController(false); // controls hidden — gesture based

            String url = video.videoUrl != null ? video.videoUrl : "";
            // Cloudinary f_mp4 fix
            if (url.contains("cloudinary.com") && !url.contains("f_mp4"))
                url = url.replaceFirst("/upload/", "/upload/f_mp4/");
            player.setMediaItem(MediaItem.fromUri(url));
            player.setRepeatMode(Player.REPEAT_MODE_ONE); // loop
            player.prepare();

            // Thumbnail while loading
            if (video.thumbnailUrl != null)
                Glide.with(ctx).load(video.thumbnailUrl).centerCrop().into(h.ivThumbnail);

            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) h.ivThumbnail.setVisibility(View.GONE);
                }
            });

            // Title & channel
            h.tvTitle.setText(video.title != null ? video.title : "");
            h.tvChannel.setText("@" + (video.uploaderName != null ? video.uploaderName : ""));

            // Avatar
            if (video.uploaderPhotoUrl != null && !video.uploaderPhotoUrl.isEmpty())
                Glide.with(ctx).load(video.uploaderPhotoUrl).circleCrop().into(h.ivAvatar);

            // Like count
            h.tvLikes.setText(formatCount(video.likeCount));

            // Subscribe/Follow button
            String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
            String uploaderUid = video.uploaderUid != null ? video.uploaderUid : "";

            if (!uploaderUid.isEmpty() && !myUid.isEmpty()) {
                YouTubeFirebaseUtils.subscriptionsRef(myUid).child(uploaderUid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            boolean subscribed = snap.exists();
                            h.btnFollow.setText(subscribed ? "Subscribed" : "Subscribe");
                            h.btnFollow.setBackgroundTintList(
                                android.content.res.ColorStateList.valueOf(
                                    subscribed ? 0xFF606060 : 0xFFFF0000));
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
            }

            // Like button
            boolean[] liked = {false};
            if (!myUid.isEmpty() && !video.videoId.isEmpty()) {
                FirebaseDatabase.getInstance(YouTubeFirebaseUtils.DB_URL)
                    .getReference("youtube/liked_videos").child(myUid).child(video.videoId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            liked[0] = s.exists();
                            h.btnLike.setImageResource(liked[0]
                                ? R.drawable.ic_yt_like_filled : R.drawable.ic_yt_like);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
            }

            h.btnLike.setOnClickListener(v -> {
                if (myUid.isEmpty()) { Toast.makeText(ctx, "Login karo", Toast.LENGTH_SHORT).show(); return; }
                liked[0] = !liked[0];
                h.btnLike.setImageResource(liked[0] ? R.drawable.ic_yt_like_filled : R.drawable.ic_yt_like);
                DatabaseReference likeRef = FirebaseDatabase.getInstance(YouTubeFirebaseUtils.DB_URL)
                    .getReference("youtube/liked_videos").child(myUid).child(video.videoId);
                DatabaseReference countRef = FirebaseDatabase.getInstance(YouTubeFirebaseUtils.DB_URL)
                    .getReference("youtube/videos").child(video.videoId).child("likeCount");
                if (liked[0]) {
                    likeRef.setValue(true);
                    countRef.runTransaction(new Transaction.Handler() {
                        @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                            Long c = d.getValue(Long.class); d.setValue(c == null ? 1 : c + 1); return Transaction.success(d);
                        }
                        @Override public void onComplete(@Nullable DatabaseError e, boolean b, @Nullable DataSnapshot s) {}
                    });
                    video.likeCount++;
                } else {
                    likeRef.removeValue();
                    countRef.runTransaction(new Transaction.Handler() {
                        @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                            Long c = d.getValue(Long.class); d.setValue(c == null ? 0 : Math.max(0, c - 1)); return Transaction.success(d);
                        }
                        @Override public void onComplete(@Nullable DatabaseError e, boolean b, @Nullable DataSnapshot s) {}
                    });
                    video.likeCount = Math.max(0, video.likeCount - 1);
                }
                h.tvLikes.setText(formatCount(video.likeCount));
            });

            // Comment button
            h.btnComment.setOnClickListener(v ->
                ctx.startActivity(new Intent(ctx, YouTubeCommentsActivity.class)
                    .putExtra("video_id", video.videoId)));

            // Share button
            h.btnShare.setOnClickListener(v -> {
                android.content.Intent share = new android.content.Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, "CallX YouTube pe dekho: " + video.title);
                ctx.startActivity(Intent.createChooser(share, "Share karo"));
            });

            // Avatar + channel tap → channel page
            h.ivAvatar.setOnClickListener(v ->
                ctx.startActivity(new Intent(ctx, YouTubeChannelActivity.class)
                    .putExtra("uid", uploaderUid)));
            h.tvChannel.setOnClickListener(v ->
                ctx.startActivity(new Intent(ctx, YouTubeChannelActivity.class)
                    .putExtra("uid", uploaderUid)));

            // Follow button
            boolean[] subbed = {false};
            h.btnFollow.setOnClickListener(v -> {
                if (myUid.isEmpty()) return;
                subbed[0] = !subbed[0];
                if (subbed[0]) {
                    YouTubeFirebaseUtils.subscriptionsRef(myUid).child(uploaderUid).setValue(true);
                    YouTubeFirebaseUtils.subscribersRef(uploaderUid).child(myUid).setValue(true);
                    h.btnFollow.setText("Subscribed");
                    h.btnFollow.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF606060));
                } else {
                    YouTubeFirebaseUtils.subscriptionsRef(myUid).child(uploaderUid).removeValue();
                    YouTubeFirebaseUtils.subscribersRef(uploaderUid).child(myUid).removeValue();
                    h.btnFollow.setText("Subscribe");
                    h.btnFollow.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFFFF0000));
                }
            });

            // Double-tap to like
            h.playerView.setOnClickListener(null);
            h.playerView.setOnTouchListener(new android.view.View.OnTouchListener() {
                private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                private boolean isDoubleClick = false;
                @Override public boolean onTouch(View view, android.view.MotionEvent e) {
                    if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                        if (isDoubleClick) {
                            isDoubleClick = false;
                            handler.removeCallbacksAndMessages(null);
                            // Double tap — like
                            if (!liked[0]) h.btnLike.performClick();
                            showHeartAnimation(h);
                        } else {
                            isDoubleClick = true;
                            handler.postDelayed(() -> {
                                isDoubleClick = false;
                                // Single tap — pause/play
                                if (player.isPlaying()) player.pause();
                                else player.play();
                            }, 250);
                        }
                    }
                    return true;
                }
            });
        }

        private void showHeartAnimation(VH h) {
            h.ivHeartAnim.setVisibility(View.VISIBLE);
            h.ivHeartAnim.setScaleX(0f);
            h.ivHeartAnim.setScaleY(0f);
            h.ivHeartAnim.setAlpha(1f);
            h.ivHeartAnim.animate().scaleX(1.3f).scaleY(1.3f).setDuration(200)
                .withEndAction(() ->
                    h.ivHeartAnim.animate().scaleX(1f).scaleY(1f).setDuration(100)
                        .withEndAction(() ->
                            h.ivHeartAnim.animate().alpha(0f).setDuration(400)
                                .withEndAction(() ->
                                    h.ivHeartAnim.setVisibility(View.GONE)).start()).start()).start();
        }

        @Override public void onViewRecycled(@NonNull VH h) {
            super.onViewRecycled(h);
            ExoPlayer p = players.get(h.getAdapterPosition());
            if (p != null) p.pause();
        }

        @Override public int getItemCount() { return data == null ? 0 : data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            PlayerView playerView;
            ImageView ivThumbnail, ivHeartAnim;
            CircleImageView ivAvatar;
            TextView tvTitle, tvChannel, tvLikes, tvComments;
            ImageButton btnLike, btnDislike, btnComment, btnShare, btnMore;
            android.widget.Button btnFollow;

            VH(@NonNull View v) {
                super(v);
                playerView  = v.findViewById(R.id.pv_short_player);
                ivThumbnail = v.findViewById(R.id.iv_short_thumb);
                ivHeartAnim = v.findViewById(R.id.iv_short_heart_anim);
                ivAvatar    = v.findViewById(R.id.iv_short_avatar);
                tvTitle     = v.findViewById(R.id.tv_short_title);
                tvChannel   = v.findViewById(R.id.tv_short_channel);
                tvLikes     = v.findViewById(R.id.tv_short_likes);
                tvComments  = v.findViewById(R.id.tv_short_comments);
                btnLike     = v.findViewById(R.id.btn_short_like);
                btnDislike  = v.findViewById(R.id.btn_short_dislike);
                btnComment  = v.findViewById(R.id.btn_short_comment);
                btnShare    = v.findViewById(R.id.btn_short_share);
                btnMore     = v.findViewById(R.id.btn_short_more);
                btnFollow   = v.findViewById(R.id.btn_short_follow);
            }
        }

        private String formatCount(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }
    }
}
