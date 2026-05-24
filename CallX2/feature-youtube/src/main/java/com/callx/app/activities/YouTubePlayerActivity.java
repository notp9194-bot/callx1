package com.callx.app.activities;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubePlayerActivity — Full-screen video player with:
 * – ExoPlayer video playback with auto-quality
 * – Like / Dislike / Subscribe / Share actions
 * – Comments section
 * – Related videos feed
 * – Fullscreen rotate support
 * – Watch history tracking
 */
public class YouTubePlayerActivity extends AppCompatActivity {

    private ExoPlayer    player;
    private PlayerView   playerView;
    private TextView     tvTitle, tvChannelName, tvViews, tvLikes, tvDesc;
    private ImageButton  btnLike, btnDislike, btnShare;
    private android.widget.Button btnSubscribe;
    private CircleImageView ivChannelAvatar;
    private RecyclerView rvRelated;
    private YouTubeVideoAdapter relatedAdapter;

    private String videoId;
    private String myUid;
    private boolean isLiked      = false;
    private boolean isDisliked   = false;
    private boolean isSubscribed = false;

    private ValueEventListener videoListener;
    private ValueEventListener likeListener;
    private ValueEventListener subsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_player);

        videoId = getIntent().getStringExtra("video_id");
        boolean openComments = getIntent().getBooleanExtra("open_comments", false);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        if (videoId == null) { finish(); return; }

        // View refs
        playerView     = findViewById(R.id.yt_player_view);
        tvTitle        = findViewById(R.id.tv_yt_video_title);
        tvChannelName  = findViewById(R.id.tv_yt_channel_name);
        tvViews        = findViewById(R.id.tv_yt_views);
        tvLikes        = findViewById(R.id.tv_yt_likes);
        tvDesc         = findViewById(R.id.tv_yt_description);
        ivChannelAvatar= findViewById(R.id.iv_yt_channel_avatar);
        btnLike        = findViewById(R.id.btn_yt_like);
        btnDislike     = findViewById(R.id.btn_yt_dislike);
        btnSubscribe   = findViewById(R.id.btn_yt_subscribe);
        btnShare       = findViewById(R.id.btn_yt_share);
        rvRelated      = findViewById(R.id.rv_yt_related);

        // Related videos
        relatedAdapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video -> {
            Intent i = new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId);
            startActivity(i);
        });
        rvRelated.setLayoutManager(new LinearLayoutManager(this));
        rvRelated.setAdapter(relatedAdapter);

        // Back button
        View btnBack = findViewById(R.id.btn_yt_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> onBackPressed());

        // Load video data
        loadVideo();
        loadLikeState();
        loadSubscribeState();
        loadRelated();
        recordWatchHistory();

        // Comments
        View btnComments = findViewById(R.id.btn_yt_comments);
        if (btnComments != null)
            btnComments.setOnClickListener(v -> openComments());

        if (openComments) openComments();

        // Like / Dislike actions
        if (btnLike != null)
            btnLike.setOnClickListener(v -> toggleLike());
        if (btnDislike != null)
            btnDislike.setOnClickListener(v -> toggleDislike());
        if (btnSubscribe != null)
            btnSubscribe.setOnClickListener(v -> toggleSubscribe());
        if (btnShare != null)
            btnShare.setOnClickListener(v -> shareVideo());
    }

    private void loadVideo() {
        videoListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                if (v == null) return;

                tvTitle.setText(v.title);
                tvChannelName.setText(v.uploaderName);
                tvViews.setText(formatCount(v.viewCount) + " views");
                tvLikes.setText(formatCount(v.likeCount));
                tvDesc.setText(v.description);

                Glide.with(YouTubePlayerActivity.this)
                    .load(v.uploaderPhotoUrl).circleCrop().into(ivChannelAvatar);

                ivChannelAvatar.setOnClickListener(cv ->
                    startActivity(new Intent(YouTubePlayerActivity.this,
                        YouTubeChannelActivity.class).putExtra("uid", v.uploaderUid)));

                // Init ExoPlayer (only if URL is available)
                if (v.videoUrl != null && !v.videoUrl.trim().isEmpty()) {
                    initPlayer(v.videoUrl);
                } else {
                    Toast.makeText(YouTubePlayerActivity.this,
                        "Video file not available", Toast.LENGTH_SHORT).show();
                }

                // Increment view count
                incrementViews(v.viewCount);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.videoRef(videoId).addValueEventListener(videoListener);
    }

    private void initPlayer(String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "Video URL unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        if (player != null) player.release();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED)
                    loadNextRecommendedVideo();
            }
        });
    }

    private void loadNextRecommendedVideo() {
        // Auto-play next recommended video from relatedAdapter
        if (relatedAdapter != null && !relatedAdapter.isEmpty()) {
            YouTubeVideo next = relatedAdapter.getFirst();
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", next.videoId));
        }
    }

    private void loadLikeState() {
        if (myUid.isEmpty()) return;
        likeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isLiked = snap.hasChild(myUid);
                btnLike.setImageResource(isLiked
                    ? R.drawable.ic_yt_like_filled : R.drawable.ic_yt_like);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.videoLikesRef(videoId).addValueEventListener(likeListener);
    }

    private void loadSubscribeState() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.videoRef(videoId)
            .child("uploaderUid").addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String channelUid = snap.getValue(String.class);
                    if (channelUid == null) return;
                    subsListener = new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap2) {
                            isSubscribed = snap2.hasChild(channelUid);
                            btnSubscribe.setSelected(isSubscribed);
                            btnSubscribe.setText(isSubscribed ? "Subscribed" : "Subscribe");
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    };
                    YouTubeFirebaseUtils.subscriptionsRef(myUid)
                        .addValueEventListener(subsListener);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void toggleLike() {
        if (myUid.isEmpty()) return;
        DatabaseReference ref = YouTubeFirebaseUtils.videoLikesRef(videoId).child(myUid);
        if (isLiked) {
            ref.removeValue();
            YouTubeFirebaseUtils.videoRef(videoId).child("likeCount")
                .setValue(ServerValue.increment(-1));
        } else {
            ref.setValue(true);
            YouTubeFirebaseUtils.videoRef(videoId).child("likeCount")
                .setValue(ServerValue.increment(1));
            // Remove dislike if any
            if (isDisliked) {
                YouTubeFirebaseUtils.videoDislikesRef(videoId).child(myUid).removeValue();
                isDisliked = false;
            }
        }
    }

    private void toggleDislike() {
        if (myUid.isEmpty()) return;
        DatabaseReference ref = YouTubeFirebaseUtils.videoDislikesRef(videoId).child(myUid);
        if (isDisliked) {
            ref.removeValue();
            isDisliked = false;
        } else {
            ref.setValue(true);
            isDisliked = true;
            // Remove like if any
            if (isLiked) {
                YouTubeFirebaseUtils.videoLikesRef(videoId).child(myUid).removeValue();
                YouTubeFirebaseUtils.videoRef(videoId).child("likeCount")
                    .setValue(ServerValue.increment(-1));
                isLiked = false;
            }
        }
    }

    private void toggleSubscribe() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.videoRef(videoId).child("uploaderUid")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String channelUid = snap.getValue(String.class);
                    if (channelUid == null || channelUid.equals(myUid)) return;
                    if (isSubscribed) {
                        YouTubeFirebaseUtils.subscriptionsRef(myUid).child(channelUid).removeValue();
                        YouTubeFirebaseUtils.subscribersRef(channelUid).child(myUid).removeValue();
                        YouTubeFirebaseUtils.channelRef(channelUid).child("subscriberCount")
                            .setValue(ServerValue.increment(-1));
                    } else {
                        YouTubeFirebaseUtils.subscriptionsRef(myUid).child(channelUid).setValue(true);
                        YouTubeFirebaseUtils.subscribersRef(channelUid).child(myUid).setValue(true);
                        YouTubeFirebaseUtils.channelRef(channelUid).child("subscriberCount")
                            .setValue(ServerValue.increment(1));
                        postSubscribeNotif(channelUid);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void postSubscribeNotif(String channelUid) {
        String notifId = YouTubeFirebaseUtils.notificationsRef(channelUid).push().getKey();
        if (notifId == null) return;
        // Get my channel name first
        YouTubeFirebaseUtils.channelRef(myUid).child("channelName")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String myName = snap.getValue(String.class);
                    if (myName == null) myName = "Someone";
                    YouTubeNotification notif = new YouTubeNotification(
                        notifId, channelUid, myUid, myName, null,
                        "subscribe", null, null, null);
                    YouTubeFirebaseUtils.notificationsRef(channelUid)
                        .child(notifId).setValue(notif);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void shareVideo() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,
            "Watch this video on CallX YouTube: callx://youtube/video/" + videoId);
        startActivity(Intent.createChooser(share, "Share Video"));
    }

    private void openComments() {
        startActivity(new Intent(this, YouTubeCommentsActivity.class)
            .putExtra("video_id", videoId));
    }

    private void recordWatchHistory() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.watchHistoryRef(myUid).child(videoId).setValue(
            System.currentTimeMillis());
    }

    private void incrementViews(long current) {
        YouTubeFirebaseUtils.videoRef(videoId).child("viewCount")
            .setValue(ServerValue.increment(1));
        YouTubeFirebaseUtils.videoViewsRef(videoId).child(myUid.isEmpty() ? "anon" : myUid)
            .setValue(System.currentTimeMillis());
    }

    private void loadRelated() {
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt")
            .limitToLast(15)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v != null && !v.videoId.equals(videoId)) list.add(0, v);
                    }
                    relatedAdapter.setData(list);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            playerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            playerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override protected void onPause()   { super.onPause();   if (player != null) player.pause(); }
    @Override protected void onResume()  { super.onResume();  if (player != null) player.play(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
        if (videoListener != null)
            YouTubeFirebaseUtils.videoRef(videoId).removeEventListener(videoListener);
        if (likeListener != null)
            YouTubeFirebaseUtils.videoLikesRef(videoId).removeEventListener(likeListener);
    }

    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
