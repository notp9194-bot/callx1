package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.activities.YouTubeCommentsActivity;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immersive full-screen Shorts adapter — one ExoPlayer per visible item.
 * Overlay buttons: like, dislike, comment, share, follow.
 */
public class YouTubeShortsAdapter extends RecyclerView.Adapter<YouTubeShortsAdapter.ShortsVH> {

    private final Context ctx;
    private List<YouTubeVideo> data;
    private final String myUid;
    private final Map<Integer, ExoPlayer> playerMap = new HashMap<>();
    private int currentPlayingPos = -1;

    public YouTubeShortsAdapter(Context ctx, List<YouTubeVideo> data) {
        this.ctx   = ctx;
        this.data  = data;
        this.myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
    }

    public void setData(List<YouTubeVideo> d) { data = d; notifyDataSetChanged(); }

    public void playAt(int pos) {
        if (pos < 0 || data == null || pos >= data.size()) return;
        if (currentPlayingPos != -1 && playerMap.containsKey(currentPlayingPos)) {
            ExoPlayer prev = playerMap.get(currentPlayingPos);
            if (prev != null) prev.pause();
        }
        currentPlayingPos = pos;
        ExoPlayer p = playerMap.get(pos);
        if (p != null) p.play();
    }

    public void pauseAll() {
        for (ExoPlayer p : playerMap.values()) if (p != null) p.pause();
    }

    public void releaseAll() {
        for (ExoPlayer p : playerMap.values()) if (p != null) p.release();
        playerMap.clear();
    }

    @NonNull @Override
    public ShortsVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx)
            .inflate(R.layout.item_yt_short_player, parent, false);
        return new ShortsVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ShortsVH h, int pos) {
        YouTubeVideo v = data.get(pos);

        if (h.tvTitle   != null) h.tvTitle.setText(v.title);
        if (h.tvChannel != null) h.tvChannel.setText(v.uploaderName);
        if (h.tvLikes   != null) h.tvLikes.setText(formatCount(v.likeCount));
        if (h.tvComments!= null) h.tvComments.setText(formatCount(v.commentCount));

        // Init or reuse player
        ExoPlayer player = playerMap.get(pos);
        if (player == null) {
            player = new ExoPlayer.Builder(ctx).build();
            player.setMediaItem(MediaItem.fromUri(v.videoUrl != null ? v.videoUrl : ""));
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            player.prepare();
            playerMap.put(pos, player);
        }
        h.playerView.setPlayer(player);
        h.playerView.setUseController(false);
        if (pos == currentPlayingPos) player.play();

        final ExoPlayer fp = player;

        // Like
        if (h.btnLike    != null) h.btnLike.setOnClickListener(x    -> toggleLike(v, h));
        if (h.btnDislike != null) h.btnDislike.setOnClickListener(x -> toggleDislike(v, h));
        if (h.btnComment != null) h.btnComment.setOnClickListener(x ->
            ctx.startActivity(new Intent(ctx, YouTubeCommentsActivity.class)
                .putExtra("video_id", v.videoId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        if (h.btnShare != null) h.btnShare.setOnClickListener(x -> {
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT,
                v.title + "\ncallx://youtube/video/" + v.videoId);
            ctx.startActivity(Intent.createChooser(share, "Share Short")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        if (h.btnFollow != null) h.btnFollow.setOnClickListener(x -> followChannel(v.uploaderUid));

        // Tap to pause/resume
        h.playerView.setOnClickListener(x -> {
            if (fp.isPlaying()) fp.pause(); else fp.play();
        });
    }

    @Override public void onViewRecycled(@NonNull ShortsVH h) {
        super.onViewRecycled(h);
        h.playerView.setPlayer(null);
    }

    @Override public int getItemCount() { return data == null ? 0 : data.size(); }

    private void toggleLike(YouTubeVideo v, ShortsVH h) {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.videoLikesRef(v.videoId).child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (s.exists()) {
                        YouTubeFirebaseUtils.videoLikesRef(v.videoId).child(myUid).removeValue();
                        YouTubeFirebaseUtils.videoRef(v.videoId).child("likeCount")
                            .setValue(ServerValue.increment(-1));
                        if (h.btnLike != null) h.btnLike.setSelected(false);
                    } else {
                        YouTubeFirebaseUtils.videoLikesRef(v.videoId).child(myUid).setValue(true);
                        YouTubeFirebaseUtils.videoRef(v.videoId).child("likeCount")
                            .setValue(ServerValue.increment(1));
                        if (h.btnLike != null) h.btnLike.setSelected(true);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void toggleDislike(YouTubeVideo v, ShortsVH h) {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.videoDislikesRef(v.videoId).child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (s.exists()) {
                        YouTubeFirebaseUtils.videoDislikesRef(v.videoId).child(myUid).removeValue();
                        YouTubeFirebaseUtils.videoRef(v.videoId).child("dislikeCount")
                            .setValue(ServerValue.increment(-1));
                        if (h.btnDislike != null) h.btnDislike.setSelected(false);
                    } else {
                        YouTubeFirebaseUtils.videoDislikesRef(v.videoId).child(myUid).setValue(true);
                        YouTubeFirebaseUtils.videoRef(v.videoId).child("dislikeCount")
                            .setValue(ServerValue.increment(1));
                        if (h.btnDislike != null) h.btnDislike.setSelected(true);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void followChannel(String channelUid) {
        if (myUid.isEmpty() || channelUid == null || channelUid.equals(myUid)) return;
        YouTubeFirebaseUtils.subscriptionsRef(myUid).child(channelUid).setValue(true);
        YouTubeFirebaseUtils.subscribersRef(channelUid).child(myUid).setValue(true);
        YouTubeFirebaseUtils.channelRef(channelUid).child("subscriberCount")
            .setValue(ServerValue.increment(1));
        Toast.makeText(ctx, "Following!", Toast.LENGTH_SHORT).show();
    }

    static class ShortsVH extends RecyclerView.ViewHolder {
        PlayerView  playerView;
        TextView    tvTitle, tvChannel, tvLikes, tvComments;
        ImageButton btnLike, btnDislike, btnComment, btnShare, btnFollow;
        ShortsVH(View v) {
            super(v);
            playerView  = v.findViewById(R.id.pv_yt_short);
            tvTitle     = v.findViewById(R.id.tv_yt_short_title);
            tvChannel   = v.findViewById(R.id.tv_yt_short_channel);
            tvLikes     = v.findViewById(R.id.tv_yt_short_likes);
            tvComments  = v.findViewById(R.id.tv_yt_short_comments);
            btnLike     = v.findViewById(R.id.btn_yt_short_like);
            btnDislike  = v.findViewById(R.id.btn_yt_short_dislike);
            btnComment  = v.findViewById(R.id.btn_yt_short_comment);
            btnShare    = v.findViewById(R.id.btn_yt_short_share);
            btnFollow   = v.findViewById(R.id.btn_yt_short_follow);
        }
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
