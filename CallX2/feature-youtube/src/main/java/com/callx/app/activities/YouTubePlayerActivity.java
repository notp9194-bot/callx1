package com.callx.app.activities;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
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
 * YouTubePlayerActivity — Fixed version
 *
 * FIX #1: ExoPlayer Cloudinary URL play fix — f_mp4 transformation inject karo
 * FIX #2: DefaultHttpDataSource with redirect support
 * FIX #3: onResume — sirf tab play karo jab STATE_READY ho
 * FIX #4: subsListener onDestroy me properly remove karo
 * FIX #5: toggleLike — liked_videos node bhi update karo taaki LikedVideosActivity kaam kare
 * FIX #6: Watch Later button add kiya player me
 */
public class YouTubePlayerActivity extends AppCompatActivity {

    private ExoPlayer    player;
    private PlayerView   playerView;
    private ProgressBar  pbPlayer;
    private View         llPlayerError;
    private TextView     tvPlayerError;
    private TextView     tvTitle, tvChannelName, tvViews, tvLikes, tvDesc;
    private ImageButton  btnLike, btnDislike, btnShare;
    private android.widget.Button btnSubscribe;
    private CircleImageView ivChannelAvatar;
    private RecyclerView rvRelated;
    private YouTubeVideoAdapter relatedAdapter;

    private String  videoId;
    private String  myUid;
    private boolean isLiked           = false;
    private boolean isDisliked        = false;
    private boolean isSubscribed      = false;
    private boolean playerReadyToPlay = false;
    private String  channelUidForUnsub = null;

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

        playerView      = findViewById(R.id.yt_player_view);
        pbPlayer        = findViewById(R.id.pb_yt_player);
        llPlayerError   = findViewById(R.id.ll_yt_player_error);
        tvPlayerError   = findViewById(R.id.tv_yt_player_error);
        tvTitle         = findViewById(R.id.tv_yt_video_title);
        tvChannelName   = findViewById(R.id.tv_yt_channel_name);
        tvViews         = findViewById(R.id.tv_yt_views);
        tvLikes         = findViewById(R.id.tv_yt_likes);
        tvDesc          = findViewById(R.id.tv_yt_description);
        ivChannelAvatar = findViewById(R.id.iv_yt_channel_avatar);
        btnLike         = findViewById(R.id.btn_yt_like);
        btnDislike      = findViewById(R.id.btn_yt_dislike);
        btnSubscribe    = findViewById(R.id.btn_yt_subscribe);
        btnShare        = findViewById(R.id.btn_yt_share);
        rvRelated       = findViewById(R.id.rv_yt_related);

        showPlayerLoading(true);

        relatedAdapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video -> {
            Intent i = new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId);
            startActivity(i);
        });
        rvRelated.setLayoutManager(new LinearLayoutManager(this));
        rvRelated.setAdapter(relatedAdapter);

        View btnBack = findViewById(R.id.btn_yt_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> onBackPressed());

        // FIX #6: Watch Later button
        View btnWatchLater = findViewById(R.id.btn_yt_watch_later);
        if (btnWatchLater != null)
            btnWatchLater.setOnClickListener(v -> addToWatchLater());

        loadVideo();
        loadLikeState();
        loadSubscribeState();
        loadRelated();
        recordWatchHistory();

        View btnComments = findViewById(R.id.btn_yt_comments);
        if (btnComments != null)
            btnComments.setOnClickListener(v -> openComments());
        if (openComments) openComments();

        if (btnLike      != null) btnLike.setOnClickListener(v -> toggleLike());
        if (btnDislike   != null) btnDislike.setOnClickListener(v -> toggleDislike());
        if (btnSubscribe != null) btnSubscribe.setOnClickListener(v -> toggleSubscribe());
        if (btnShare     != null) btnShare.setOnClickListener(v -> shareVideo());
    }

    // ── Player helpers ────────────────────────────────────────────────────────

    private void showPlayerLoading(boolean loading) {
        if (pbPlayer != null)
            pbPlayer.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (llPlayerError != null && loading)
            llPlayerError.setVisibility(View.GONE);
    }

    private void showPlayerError(String message) {
        showPlayerLoading(false);
        if (llPlayerError != null) llPlayerError.setVisibility(View.VISIBLE);
        if (tvPlayerError != null && message != null) tvPlayerError.setText(message);
    }

    // ── Load video ────────────────────────────────────────────────────────────

    private void loadVideo() {
        videoListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                if (v == null) {
                    showPlayerError("Video nahi mila. Shayad delete ho gaya.");
                    return;
                }

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

                if (v.videoUrl != null && !v.videoUrl.trim().isEmpty()) {
                    String playUrl = ensureMp4Delivery(v.videoUrl.trim());
                    initPlayer(playUrl);
                } else {
                    showPlayerError("Video file abhi available nahi hai.\n\n"
                        + "URL save nahi hua. Thodi der baad try karo.");
                }

                incrementViews(v.viewCount);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                showPlayerError("Network error: " + e.getMessage());
            }
        };
        YouTubeFirebaseUtils.videoRef(videoId).addValueEventListener(videoListener);
    }

    /**
     * Cloudinary URL ko MP4 delivery pe force karo.
     * ExoPlayer bina extension ke format detect nahi kar paata.
     *
     * Example:
     *   IN:  https://res.cloudinary.com/demo/video/upload/v123/myvideo
     *   OUT: https://res.cloudinary.com/demo/video/upload/f_mp4/v123/myvideo.mp4
     */
    private String ensureMp4Delivery(String url) {
        if (url == null) return url;

        // Already has a known playable extension
        if (url.matches(".*\\.(mp4|webm|m3u8|mkv)(\\?.*)?$")) return url;

        if (url.contains("cloudinary.com") && url.contains("/video/upload/")) {
            // Add f_mp4 format transformation if not already present
            if (!url.contains("/f_mp4") && !url.contains("/f_auto")) {
                url = url.replace("/video/upload/", "/video/upload/f_mp4/");
            }
            // Append .mp4 if URL has no extension at end
            String path = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            if (!path.endsWith(".mp4")) {
                url = url.contains("?")
                    ? path + ".mp4?" + url.substring(url.indexOf('?') + 1)
                    : url + ".mp4";
            }
            return url;
        }

        // Non-Cloudinary URL: try appending .mp4
        if (!url.contains(".")) {
            return url + ".mp4";
        }
        return url;
    }

    private void initPlayer(String url) {
        if (url == null || url.trim().isEmpty()) {
            showPlayerError("Video URL unavailable");
            return;
        }
        if (player != null) { player.release(); player = null; }
        playerReadyToPlay = false;

        // DefaultHttpDataSource — redirects + longer timeout for Cloudinary
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent("CallX/1.0 (Android)");

        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
            .build();

        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        showPlayerLoading(true);
                        break;
                    case Player.STATE_READY:
                        playerReadyToPlay = true;
                        showPlayerLoading(false);
                        break;
                    case Player.STATE_ENDED:
                        playerReadyToPlay = false;
                        showPlayerLoading(false);
                        loadNextRecommendedVideo();
                        break;
                    case Player.STATE_IDLE:
                        break;
                }
            }
            @Override public void onPlayerError(@NonNull PlaybackException error) {
                playerReadyToPlay = false;
                showPlayerError("Video play nahi ho raha.\n\n"
                    + error.getMessage()
                    + "\n\nInternet check karo ya baad mein try karo.");
            }
        });
    }

    private void loadNextRecommendedVideo() {
        if (relatedAdapter != null && !relatedAdapter.isEmpty()) {
            YouTubeVideo next = relatedAdapter.getFirst();
            if (next != null)
                startActivity(new Intent(this, YouTubePlayerActivity.class)
                    .putExtra("video_id", next.videoId));
        }
    }

    // ── Like / Subscribe ──────────────────────────────────────────────────────

    private void loadLikeState() {
        if (myUid.isEmpty()) return;
        likeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isLiked = snap.hasChild(myUid);
                if (btnLike != null)
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
                    channelUidForUnsub = channelUid;
                    subsListener = new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap2) {
                            isSubscribed = snap2.hasChild(channelUid);
                            if (btnSubscribe != null) {
                                btnSubscribe.setSelected(isSubscribed);
                                btnSubscribe.setText(isSubscribed ? "Subscribed" : "Subscribe");
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    };
                    YouTubeFirebaseUtils.subscriptionsRef(myUid)
                        .addValueEventListener(subsListener);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * FIX #5: toggleLike ab do jagah save karta hai:
     *   1. youtube/video_likes/{videoId}/{myUid}  — player like state ke liye
     *   2. youtube/liked_videos/{myUid}/{videoId} — LikedVideosActivity ke liye
     */
    private void toggleLike() {
        if (myUid.isEmpty()) return;
        DatabaseReference videoLikeRef   = YouTubeFirebaseUtils.videoLikesRef(videoId).child(myUid);
        DatabaseReference likedVideosRef = YouTubeFirebaseUtils.likedVideosRef(myUid).child(videoId);

        if (isLiked) {
            videoLikeRef.removeValue();
            likedVideosRef.removeValue();
            YouTubeFirebaseUtils.videoRef(videoId).child("likeCount")
                .setValue(ServerValue.increment(-1));
        } else {
            videoLikeRef.setValue(true);
            // liked_videos: value = timestamp for ordering
            likedVideosRef.setValue(System.currentTimeMillis());
            YouTubeFirebaseUtils.videoRef(videoId).child("likeCount")
                .setValue(ServerValue.increment(1));
            // remove dislike if active
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
            if (isLiked) {
                YouTubeFirebaseUtils.videoLikesRef(videoId).child(myUid).removeValue();
                YouTubeFirebaseUtils.likedVideosRef(myUid).child(videoId).removeValue();
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
                    channelUidForUnsub = channelUid;
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

    // ── Actions ───────────────────────────────────────────────────────────────

    /** FIX #6: Watch Later — save videoId to watchLaterRef */
    private void addToWatchLater() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.watchLaterRef(myUid).child(videoId)
            .setValue(System.currentTimeMillis())
            .addOnSuccessListener(v -> android.widget.Toast.makeText(
                this, "Watch Later me add ho gaya", android.widget.Toast.LENGTH_SHORT).show());
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
        YouTubeFirebaseUtils.watchHistoryRef(myUid).child(videoId)
            .setValue(System.currentTimeMillis());
    }

    private void incrementViews(long current) {
        YouTubeFirebaseUtils.videoRef(videoId).child("viewCount")
            .setValue(ServerValue.increment(1));
        YouTubeFirebaseUtils.videoViewsRef(videoId)
            .child(myUid.isEmpty() ? "anon" : myUid)
            .setValue(System.currentTimeMillis());
    }

    private void loadRelated() {
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(15)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v != null && !v.videoId.equals(videoId)
                                && v.videoUrl != null && !v.videoUrl.trim().isEmpty())
                            list.add(0, v);
                    }
                    relatedAdapter.setData(list);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            playerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            playerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (player != null && playerReadyToPlay) player.play();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
        if (videoListener != null)
            YouTubeFirebaseUtils.videoRef(videoId).removeEventListener(videoListener);
        if (likeListener != null)
            YouTubeFirebaseUtils.videoLikesRef(videoId).removeEventListener(likeListener);
        // FIX #4: subsListener cleanup
        if (subsListener != null && !myUid.isEmpty())
            YouTubeFirebaseUtils.subscriptionsRef(myUid).removeEventListener(subsListener);
    }

    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
