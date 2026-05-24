package com.callx.app.activities;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
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
 * YouTubePlayerActivity — Full-screen video player with:
 * – ExoPlayer video playback (FIXED: custom HttpDataSource for Cloudinary)
 * – Like / Dislike / Subscribe / Share actions
 * – Comments section
 * – Related videos feed
 * – Fullscreen rotate support
 * – Watch history tracking
 * – Loading spinner + error state
 *
 * FIX LOG:
 *   1. ExoPlayer ab DefaultHttpDataSource use karta hai — Cloudinary ke
 *      redirect/content-type headers properly handle hote hain.
 *   2. onResume() me player null-check add kiya + sirf tab play karo jab
 *      player ready ho (STATE_READY).
 *   3. subsListener onDestroy() me properly remove hota hai — memory leak fix.
 *   4. videoUrl me ".mp4" suffix ensure kiya taaki ExoPlayer format detect kare.
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

    private String videoId;
    private String myUid;
    private boolean isLiked      = false;
    private boolean isDisliked   = false;
    private boolean isSubscribed = false;

    // Track whether player is ready to resume
    private boolean playerReadyToPlay = false;
    // Track channel UID for cleanup
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

        playerView     = findViewById(R.id.yt_player_view);
        pbPlayer       = findViewById(R.id.pb_yt_player);
        llPlayerError  = findViewById(R.id.ll_yt_player_error);
        tvPlayerError  = findViewById(R.id.tv_yt_player_error);
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

        loadVideo();
        loadLikeState();
        loadSubscribeState();
        loadRelated();
        recordWatchHistory();

        View btnComments = findViewById(R.id.btn_yt_comments);
        if (btnComments != null)
            btnComments.setOnClickListener(v -> openComments());
        if (openComments) openComments();

        if (btnLike     != null) btnLike.setOnClickListener(v -> toggleLike());
        if (btnDislike  != null) btnDislike.setOnClickListener(v -> toggleDislike());
        if (btnSubscribe!= null) btnSubscribe.setOnClickListener(v -> toggleSubscribe());
        if (btnShare    != null) btnShare.setOnClickListener(v -> shareVideo());
    }

    // ── Player state helpers ──────────────────────────────────────────────────

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
                    // FIX #1: Ensure Cloudinary URL has .mp4 so ExoPlayer detects format
                    String playUrl = ensureMp4Extension(v.videoUrl.trim());
                    initPlayer(playUrl);
                } else {
                    showPlayerError("Video file abhi available nahi hai.\n\n"
                        + "Uploader ne video upload ki hai lekin URL save nahi hua.\n"
                        + "Thodi der baad try karo ya uploader se contact karo.");
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
     * FIX #1 — Cloudinary URLs sometimes lack .mp4 extension.
     * ExoPlayer needs file extension OR Content-Type header to pick decoder.
     * We append /so_0/video.mp4 style suffix only if no extension present.
     *
     * Simpler approach: if URL already ends with .mp4 → keep it.
     * Otherwise insert ".mp4" before any query string, or append it.
     */
    private String ensureMp4Extension(String url) {
        // Already has extension
        if (url.contains(".mp4") || url.contains(".webm") || url.contains(".m3u8")) {
            return url;
        }
        // Cloudinary: insert format transformation to force mp4 delivery
        // e.g. .../video/upload/v123/abc  →  .../video/upload/f_mp4/v123/abc
        if (url.contains("cloudinary.com") && url.contains("/video/upload/")) {
            // Check if transformation already present (contains f_)
            if (!url.contains("/f_")) {
                url = url.replace("/video/upload/", "/video/upload/f_mp4/");
            }
            return url;
        }
        // Generic fallback: append .mp4 if no extension at all
        int qIdx = url.indexOf('?');
        if (qIdx > 0) {
            return url.substring(0, qIdx) + ".mp4" + url.substring(qIdx);
        }
        return url + ".mp4";
    }

    private void initPlayer(String url) {
        if (url == null || url.trim().isEmpty()) {
            showPlayerError("Video URL unavailable");
            return;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        playerReadyToPlay = false;

        // FIX #2 — DefaultHttpDataSource with longer timeouts for Cloudinary
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)   // Cloudinary uses redirects
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
                        // handled by onPlayerError
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
            if (next != null) {
                startActivity(new Intent(this, YouTubePlayerActivity.class)
                    .putExtra("video_id", next.videoId));
            }
        }
    }

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
                    channelUidForUnsub = channelUid; // FIX #3: save for cleanup
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

    // FIX #4 — onPause/onResume: null-check + only resume when player ready
    @Override protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override protected void onResume() {
        super.onResume();
        // Only call play() if player exists AND was in ready state before pause
        if (player != null && playerReadyToPlay) player.play();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }

        if (videoListener != null)
            YouTubeFirebaseUtils.videoRef(videoId).removeEventListener(videoListener);

        if (likeListener != null)
            YouTubeFirebaseUtils.videoLikesRef(videoId).removeEventListener(likeListener);

        // FIX #3 — subsListener bhi remove karo (was missing before)
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
