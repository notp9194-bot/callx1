package com.callx.app.activities;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
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
import com.callx.app.sheets.YouTubeVideoOptionsSheet;
import com.callx.app.utils.YouTubeDownloadManager;
import com.callx.app.utils.YouTubePrefs;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.common.TrackSelectionParameters;
import android.os.Build;
import android.app.PictureInPictureParams;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * YouTubePlayerActivity — Full Debug Version
 *
 * FIX #1: ExoPlayer Cloudinary URL play fix — f_mp4 transformation inject karo
 * FIX #2: DefaultHttpDataSource with redirect support
 * FIX #3: onResume — sirf tab play karo jab STATE_READY ho
 * FIX #4: subsListener onDestroy me properly remove karo
 * FIX #5: toggleLike — liked_videos node bhi update karo
 * FIX #6: Watch Later button
 * DEBUG : Har step pe Toast + Log — kya ho raha hai seedha screen pe dikhega
 */
public class YouTubePlayerActivity extends AppCompatActivity {

    private static final String TAG = "YT_PLAYER_DEBUG";

    private ExoPlayer    player;
    private PlayerView   playerView;
    private ProgressBar  pbPlayer;
    private View         llPlayerError;
    private TextView     tvPlayerError;
    private TextView     tvTitle, tvChannelName, tvViews, tvLikes, tvDesc;
    private ImageButton  btnLike, btnDislike, btnShare;
    private android.widget.Button btnSubscribe;
    private android.widget.ImageButton btnDownload;
    private CircleImageView ivChannelAvatar;
    private RecyclerView rvRelated;
    private YouTubeVideoAdapter relatedAdapter;

    private String  videoId;
    private String  myUid;
    private boolean isLiked            = false;
    private boolean isDisliked         = false;
    private boolean isSubscribed       = false;
    private boolean playerReadyToPlay  = false;
    private boolean playerInitialized  = false; // FIX: ek baar hi initPlayer() chalega
    private boolean isDescExpanded     = false;  // FIX #3: description expand state
    private TextView tvShowMore;                  // FIX #3: show more/less button
    private YouTubePrefs ytPrefs;
    private String  channelUidForUnsub = null;

    private ValueEventListener videoListener;
    private ValueEventListener likeListener;
    private ValueEventListener subsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_player);

        videoId = getIntent().getStringExtra("video_id");
        ytPrefs = new YouTubePrefs(this);
        applyAppearanceTheme();
        String localPath = getIntent().getStringExtra("local_path"); // offline play
        boolean openComments = getIntent().getBooleanExtra("open_comments", false);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG, "▶ YouTubePlayerActivity onCreate");
        Log.d(TAG, "  videoId : " + videoId);
        Log.d(TAG, "  myUid   : " + (myUid.isEmpty() ? "EMPTY (not logged in)" : myUid));

        if (videoId == null) {
            Log.e(TAG, "❌ videoId NULL — finish()");
            Toast.makeText(this, "❌ Video ID nahi mila! Intent check karo.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Toast.makeText(this, "🎬 Player open: videoId=" + videoId, Toast.LENGTH_SHORT).show();

        playerView      = findViewById(R.id.yt_player_view);
        pbPlayer        = findViewById(R.id.pb_yt_player);
        llPlayerError   = findViewById(R.id.ll_yt_player_error);
        tvPlayerError   = findViewById(R.id.tv_yt_player_error);
        tvTitle         = findViewById(R.id.tv_yt_video_title);
        tvChannelName   = findViewById(R.id.tv_yt_channel_name);
        tvViews         = findViewById(R.id.tv_yt_views);
        tvLikes         = findViewById(R.id.tv_yt_likes);
        tvDesc          = findViewById(R.id.tv_yt_description);
        tvShowMore      = findViewById(R.id.tv_yt_show_more); // FIX #3
        ivChannelAvatar = findViewById(R.id.iv_yt_channel_avatar);
        btnLike         = findViewById(R.id.btn_yt_like);
        btnDislike      = findViewById(R.id.btn_yt_dislike);
        btnSubscribe    = findViewById(R.id.btn_yt_subscribe);
        btnShare        = findViewById(R.id.btn_yt_share);
        btnDownload     = findViewById(R.id.btn_yt_download); // FIX #1: Was missing — download button ab kaam karega
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

        View btnWatchLater = findViewById(R.id.btn_yt_watch_later);
        if (btnWatchLater != null)
            btnWatchLater.setOnClickListener(v -> addToWatchLater());

        // REAL YOUTUBE STYLE: "...more" tap karne pe BottomSheet open hogi
        android.view.View llDescTrigger = findViewById(R.id.ll_yt_desc_trigger);
        if (llDescTrigger != null) {
            llDescTrigger.setOnClickListener(v -> openDescriptionSheet());
        }
        if (tvShowMore != null) {
            tvShowMore.setOnClickListener(v -> openDescriptionSheet());
        }

        playLocalIfAvailable();
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
        if (btnDownload  != null) {
            btnDownload.setOnClickListener(v -> downloadCurrentVideo());
            // Already downloaded? Icon change karo
            if (YouTubeDownloadManager.isDownloaded(this, videoId)) {
                btnDownload.setImageResource(R.drawable.ic_yt_download_done);
            }
        }

        // 3-dot more options button
        View btnPlayerMore = findViewById(R.id.btn_yt_player_more);
        if (btnPlayerMore != null)
            btnPlayerMore.setOnClickListener(v -> showPlayerOptionsSheet());
    }

    // ── 3-dot Options Sheet ───────────────────────────────────────────────────

    private YouTubeVideo currentVideo = null; // hold current video for sheet

    private void showPlayerOptionsSheet() {
        if (currentVideo == null) {
            YouTubeVideo v = new YouTubeVideo();
            v.videoId     = videoId;
            v.title       = tvTitle != null ? tvTitle.getText().toString() : "";
            v.uploaderName= tvChannelName != null ? tvChannelName.getText().toString() : "";
            v.uploaderUid = channelUidForUnsub != null ? channelUidForUnsub : "";
            currentVideo  = v;
        }
        YouTubeVideoOptionsSheet sheet = YouTubeVideoOptionsSheet.newInstance(currentVideo);
        sheet.setCallback(new YouTubeVideoOptionsSheet.OptionsCallback() {
            @Override
            public void onNotInterested(String vid) { /* no-op on player screen */ }
            @Override
            public void onVideoDeleted(String vid) {
                // Video delete ho gaya — player band karo
                finish();
            }
        });
        sheet.show(getSupportFragmentManager(), "yt_player_options");
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
        Log.e(TAG, "PLAYER ERROR SHOWN: " + message);
        Toast.makeText(this, "❌ Player Error:\n" + message, Toast.LENGTH_LONG).show();
    }

    // ── Load video ────────────────────────────────────────────────────────────

    private void playLocalIfAvailable() {
        // Agar Downloads screen se aaye hain local_path ke saath
        String lp = getIntent().getStringExtra("local_path");
        if (lp != null && new java.io.File(lp).exists()) {
            Log.d(TAG, "Local path se directly play: " + lp);
            showPlayerLoading(false);
            if (!playerInitialized) { playerInitialized = true; initPlayer(lp); }
            return;
        }
        loadVideo();
    }

    private void loadVideo() {
        Log.d(TAG, "loadVideo() — Firebase se video data fetch kar raha hai...");
        Log.d(TAG, "  Firebase path: youtube/videos/" + videoId);
        Toast.makeText(this, "🔄 Firebase se video data le raha hai...", Toast.LENGTH_SHORT).show();

        // FIX: SingleValueEvent use karo — addValueEventListener viewCount/likeCount
        // change hone par bhi fire hota hai, jisse initPlayer() baar baar call hota tha
        // aur ExoPlayer baar baar restart hota tha → video kabhi play nahi hoti thi.
        YouTubeFirebaseUtils.videoRef(videoId).addListenerForSingleValueEvent(
            new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Log.d(TAG, "Firebase onDataChange — snap exists: " + snap.exists());

                YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                if (v == null) {
                    String msg = "❌ Firebase me video data null — videoId=" + videoId
                        + "\nShayad delete ho gaya ya videoId galat hai.";
                    Log.e(TAG, msg);
                    showPlayerError(msg);
                    return;
                }

                Log.d(TAG, "✅ Video data mila!");
                Log.d(TAG, "  title       : " + v.title);
                Log.d(TAG, "  uploaderName: " + v.uploaderName);
                Log.d(TAG, "  videoUrl    : " + v.videoUrl);
                Log.d(TAG, "  viewCount   : " + v.viewCount);
                Log.d(TAG, "  likeCount   : " + v.likeCount);

                currentVideo = v;
                tvTitle.setText(v.title);
                tvChannelName.setText(v.uploaderName);

                // Views + ago — real YouTube jaisa: "4,452 views · 1h ago"
                String agoStr = formatTimeAgo(v.uploadedAt);
                tvViews.setText(formatCount(v.viewCount) + " views  ·  " + agoStr);

                tvLikes.setText(formatCount(v.likeCount));
                tvDesc.setText(v.description); // hidden TextView — data store

                // "...more" show karo agar description hai
                if (tvShowMore != null) {
                    if (v.description != null && !v.description.trim().isEmpty()) {
                        tvShowMore.setVisibility(android.view.View.VISIBLE);
                    } else {
                        tvShowMore.setVisibility(android.view.View.GONE);
                    }
                }

                Glide.with(YouTubePlayerActivity.this)
                    .load(v.uploaderPhotoUrl).circleCrop().into(ivChannelAvatar);

                ivChannelAvatar.setOnClickListener(cv ->
                    startActivity(new Intent(YouTubePlayerActivity.this,
                        YouTubeChannelActivity.class).putExtra("uid", v.uploaderUid)));

                if (v.videoUrl != null && !v.videoUrl.trim().isEmpty()) {
                    String rawUrl  = v.videoUrl.trim();
                    String playUrl = ensureMp4Delivery(rawUrl);
                    Log.d(TAG, "  rawUrl     : " + rawUrl);
                    Log.d(TAG, "  playUrl    : " + playUrl);
                    Toast.makeText(YouTubePlayerActivity.this,
                        "🎬 Video URL ready!\n" + playUrl, Toast.LENGTH_LONG).show();

                    // FIX: guard — sirf pehli baar initPlayer() chalao
                    // Check local offline path first
                    String offlinePath = YouTubeDownloadManager.getOfflinePath(
                        YouTubePlayerActivity.this, videoId);
                    String finalUrl = (offlinePath != null) ? offlinePath : playUrl;
                    if (offlinePath != null) {
                        Log.d(TAG, "▶ Offline file milgi — local se play: " + offlinePath);
                        Toast.makeText(YouTubePlayerActivity.this,
                            "📱 Offline se play ho raha hai", Toast.LENGTH_SHORT).show();
                    }
                    if (!playerInitialized) {
                        playerInitialized = true;
                        initPlayer(finalUrl);
                    } else {
                        Log.d(TAG, "initPlayer() skip — already initialized");
                    }
                } else {
                    String msg = "❌ videoUrl NULL/EMPTY — Firebase me save nahi hua\nvideoId: " + videoId;
                    Log.e(TAG, msg);
                    showPlayerError("Video file abhi available nahi hai.\n\nURL save nahi hua.\nThodi der baad try karo.");
                    Toast.makeText(YouTubePlayerActivity.this,
                        "❌ videoUrl empty!\nFirebase me URL check karo:\nyoutube/videos/" + videoId,
                        Toast.LENGTH_LONG).show();
                }

                incrementViews(v.viewCount);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                String msg = "❌ Firebase onCancelled: " + e.getMessage()
                    + " (code: " + e.getCode() + ")";
                Log.e(TAG, msg);
                showPlayerError("Network error: " + e.getMessage());
            }
        });
    }

    /**
     * Cloudinary URL ensure karo ki MP4 deliver ho.
     *
     * Input:  .../video/upload/v123/youtube/{uid}/abc.mp4
     * Output: .../video/upload/f_mp4/v123/youtube/{uid}/abc.mp4
     */
    private String ensureMp4Delivery(String url) {
        if (url == null || url.trim().isEmpty()) return url;

        if (url.contains("cloudinary.com") && url.contains("/video/upload/")) {
            Log.d(TAG, "[ensureMp4] Cloudinary URL detect hua — f_mp4 inject kar raha hai");
            if (!url.contains("/f_mp4") && !url.contains("/f_auto") && !url.contains("/f_")) {
                url = url.replace("/video/upload/", "/video/upload/f_mp4/");
                Log.d(TAG, "[ensureMp4] f_mp4 inject kiya");
            } else {
                Log.d(TAG, "[ensureMp4] Already f_ transformation hai — skip");
            }
            String urlNoQuery = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            if (!urlNoQuery.toLowerCase().endsWith(".mp4")) {
                url = urlNoQuery + ".mp4" +
                      (url.contains("?") ? "?" + url.substring(url.indexOf('?') + 1) : "");
                Log.d(TAG, "[ensureMp4] .mp4 extension add kiya");
            }
            return url;
        }

        Log.d(TAG, "[ensureMp4] Non-Cloudinary URL — as-is return");
        return url;
    }

    private void initPlayer(String url) {
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        Log.d(TAG, "▶ initPlayer()");
        Log.d(TAG, "  URL: " + url);

        if (url == null || url.trim().isEmpty()) {
            Log.e(TAG, "❌ initPlayer — URL null/empty");
            showPlayerError("Video URL unavailable");
            return;
        }

        if (player != null) {
            Log.d(TAG, "  Purana player release kar raha hai");
            player.release();
            player = null;
        }
        playerReadyToPlay = false;

        Toast.makeText(this, "⚙️ ExoPlayer initialize ho raha hai...", Toast.LENGTH_SHORT).show();

        // ── FIX: Local file path ko properly Uri me convert karo ──────────────
        // Agar url local file path hai (starts with "/") to file:// scheme lagao.
        // Bina iske DefaultHttpDataSource HTTP request karta hai → Timeout Error 2001.
        android.net.Uri mediaUri;
        boolean isLocalFile = url.startsWith("/");
        if (isLocalFile) {
            mediaUri = android.net.Uri.fromFile(new java.io.File(url));
            Log.d(TAG, "  LOCAL FILE detected — file:// URI: " + mediaUri);
        } else {
            mediaUri = android.net.Uri.parse(url);
            Log.d(TAG, "  REMOTE URL — URI: " + mediaUri);
        }

        // DataSource factory: local file ke liye FileDataSource, remote ke liye Http
        androidx.media3.datasource.DataSource.Factory dataSourceFactory;
        if (isLocalFile) {
            // Local file — sirf FileDataSource chahiye, no HTTP needed
            dataSourceFactory = new androidx.media3.datasource.FileDataSource.Factory();
            Log.d(TAG, "  FileDataSource.Factory use ho rahi hai (local playback)");
        } else {
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setUserAgent("ExoPlayer/2.0 (Linux;Android " + android.os.Build.VERSION.RELEASE + ")");
            dataSourceFactory = httpFactory;
            Log.d(TAG, "  HttpDataSource.Factory use ho rahi hai (remote playback), connect=15s, read=20s");
        }

        long seekMs = ytPrefs.getSeekIncrementMs();
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
            .setSeekForwardIncrementMs(seekMs)
            .setSeekBackIncrementMs(seekMs)
            .build();

        // Apply zoom-to-fill setting
        playerView.setResizeMode(ytPrefs.isZoomToFill()
            ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            : AspectRatioFrameLayout.RESIZE_MODE_FIT);

        // Apply video quality setting
        boolean onWifi = isOnWifi();
        int maxHeight = ytPrefs.getEffectiveMaxHeight(onWifi);
        if (maxHeight < Integer.MAX_VALUE) {
            player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                    .setMaxVideoSize(Integer.MAX_VALUE, maxHeight)
                    .build());
        }

        player.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        playerView.setPlayer(player);
        playerView.setUseController(true);

        player.setMediaItem(MediaItem.fromUri(mediaUri));
        player.prepare();
        player.setPlayWhenReady(true);

        Log.d(TAG, "  player.prepare() + setPlayWhenReady(true) — buffering shuru...");

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                String stateStr;
                switch (state) {
                    case Player.STATE_BUFFERING:
                        stateStr = "BUFFERING";
                        showPlayerLoading(true);
                        Log.d(TAG, "▶▶ STATE: BUFFERING");
                        break;
                    case Player.STATE_READY:
                        stateStr = "READY ✅";
                        playerReadyToPlay = true;
                        showPlayerLoading(false);
                        Log.d(TAG, "▶▶ STATE: READY — video play ho raha hai!");
                        Toast.makeText(YouTubePlayerActivity.this,
                            "✅ Video READY — play ho raha hai!", Toast.LENGTH_SHORT).show();
                        break;
                    case Player.STATE_ENDED:
                        stateStr = "ENDED";
                        playerReadyToPlay = false;
                        showPlayerLoading(false);
                        Log.d(TAG, "▶▶ STATE: ENDED");
                        if (ytPrefs.isAutoplay()) {
                            loadNextRecommendedVideo();
                            Log.d(TAG, "Autoplay ON — next video load ho raha hai");
                        } else {
                            Log.d(TAG, "Autoplay OFF — next video nahi chalega");
                        }
                        break;
                    case Player.STATE_IDLE:
                        stateStr = "IDLE";
                        Log.d(TAG, "▶▶ STATE: IDLE");
                        break;
                    default:
                        stateStr = "UNKNOWN (" + state + ")";
                }
                Log.d(TAG, "  Player state → " + stateStr);
            }

            @Override public void onPlayerError(@NonNull PlaybackException error) {
                playerReadyToPlay = false;
                int errorCode = error.errorCode;
                String cause  = error.getCause() != null ? error.getCause().toString() : "null";
                String errMsg = "❌ PLAYER ERROR!\n"
                    + "Code    : " + errorCode + "\n"
                    + "Message : " + error.getMessage() + "\n"
                    + "Cause   : " + cause;
                Log.e(TAG, errMsg);

                String userMsg;
                // ExoPlayer error code groups: 1xxx=remote, 2xxx=timeout, 3xxx=parsing
                if (errorCode >= 1000 && errorCode < 2000) {
                    userMsg = "❌ Server Error (code " + errorCode + ")\nURL galat hai ya Cloudinary pe file nahi hai.\nURL: " + url;
                } else if (errorCode >= 2000 && errorCode < 3000) {
                    userMsg = "❌ Timeout Error (code " + errorCode + ")\nInternet slow hai ya URL unreachable.\nURL: " + url;
                } else if (errorCode >= 3000 && errorCode < 4000) {
                    userMsg = "❌ Format Error (code " + errorCode + ")\nVideo format ExoPlayer support nahi karta.\nf_mp4 transformation check karo.\nURL: " + url;
                } else {
                    userMsg = "❌ Player Error (code " + errorCode + ")\n" + error.getMessage();
                }

                Log.e(TAG, "  URL jis pe error aaya: " + url);
                showPlayerError(userMsg);
                Toast.makeText(YouTubePlayerActivity.this,
                    "❌ Playback Error!\nCode: " + errorCode + "\n" + error.getMessage()
                    + "\n\nURL: " + url, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadNextRecommendedVideo() {
        if (relatedAdapter != null && !relatedAdapter.isEmpty()) {
            YouTubeVideo next = relatedAdapter.getFirst();
            if (next != null) {
                Log.d(TAG, "Next video auto-play: " + next.videoId);
                startActivity(new Intent(this, YouTubePlayerActivity.class)
                    .putExtra("video_id", next.videoId));
            }
        }
    }

    // ── Like / Subscribe ──────────────────────────────────────────────────────

    private void loadLikeState() {
        if (myUid.isEmpty()) return;
        likeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isLiked = snap.hasChild(myUid);
                Log.d(TAG, "Like state: isLiked=" + isLiked);
                if (btnLike != null)
                    btnLike.setImageResource(isLiked
                        ? R.drawable.ic_yt_like_filled : R.drawable.ic_yt_like);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Log.e(TAG, "Like state load fail: " + e.getMessage());
            }
        };
        YouTubeFirebaseUtils.videoLikesRef(videoId).addValueEventListener(likeListener);
    }

    private void loadSubscribeState() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.videoRef(videoId)
            .child("uploaderUid").addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String channelUid = snap.getValue(String.class);
                    if (channelUid == null) {
                        Log.w(TAG, "loadSubscribeState: uploaderUid null");
                        return;
                    }
                    channelUidForUnsub = channelUid;
                    subsListener = new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap2) {
                            isSubscribed = snap2.hasChild(channelUid);
                            Log.d(TAG, "Subscribe state: isSubscribed=" + isSubscribed);
                            if (btnSubscribe != null) {
                                btnSubscribe.setSelected(isSubscribed);
                                btnSubscribe.setText(isSubscribed ? "Subscribed" : "Subscribe");
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            Log.e(TAG, "Subscribe state fail: " + e.getMessage());
                        }
                    };
                    YouTubeFirebaseUtils.subscriptionsRef(myUid)
                        .addValueEventListener(subsListener);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    Log.e(TAG, "uploaderUid fetch fail: " + e.getMessage());
                }
            });
    }

    /**
     * FIX #5: toggleLike ab do jagah save karta hai:
     *   1. youtube/video_likes/{videoId}/{myUid}
     *   2. youtube/liked_videos/{myUid}/{videoId}
     */
    private void toggleLike() {
        if (myUid.isEmpty()) {
            Toast.makeText(this, "⚠️ Like karne ke liye login karo", Toast.LENGTH_SHORT).show();
            return;
        }
        DatabaseReference videoLikeRef   = YouTubeFirebaseUtils.videoLikesRef(videoId).child(myUid);
        DatabaseReference likedVideosRef = YouTubeFirebaseUtils.likedVideosRef(myUid).child(videoId);

        if (isLiked) {
            videoLikeRef.removeValue();
            likedVideosRef.removeValue();
            YouTubeFirebaseUtils.videoRef(videoId).child("likeCount")
                .setValue(ServerValue.increment(-1));
            Log.d(TAG, "Like removed");
        } else {
            videoLikeRef.setValue(true);
            likedVideosRef.setValue(System.currentTimeMillis());
            YouTubeFirebaseUtils.videoRef(videoId).child("likeCount")
                .setValue(ServerValue.increment(1));
            if (isDisliked) {
                YouTubeFirebaseUtils.videoDislikesRef(videoId).child(myUid).removeValue();
                isDisliked = false;
            }
            Log.d(TAG, "Like added");
            // FCM push — video owner ko like notification
            sendLikeNotification();
        }
    }

    private void sendLikeNotification() {
        YouTubeFirebaseUtils.videoRef(videoId).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String ownerUid   = snap.child("uploaderUid").getValue(String.class);
                    String videoTitle = snap.child("title").getValue(String.class);
                    if (ownerUid == null || ownerUid.equals(myUid)) return;

                    String myDisplayName = snap.child("channelName") != null ? myUid : myUid;
                    // Get my channel name from Firebase
                    YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
                        new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot cSnap) {
                                String fromName  = cSnap.child("channelName").getValue(String.class);
                                String fromPhoto = cSnap.child("photoUrl").getValue(String.class);
                                if (fromName == null) fromName = "Someone";
                                String vTitle = videoTitle != null ? videoTitle : "";
                                String fn = fromName, fp = fromPhoto != null ? fromPhoto : "";

                                // Firebase DB save
                                String nKey = YouTubeFirebaseUtils.notificationsRef(ownerUid).push().getKey();
                                if (nKey != null) {
                                    YouTubeNotification n = new YouTubeNotification(
                                        nKey, ownerUid, myUid, fn, fp,
                                        "like", videoId, null, null);
                                    YouTubeFirebaseUtils.notificationsRef(ownerUid).child(nKey).setValue(n);
                                }

                                // FCM push
                                java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                                    try {
                                        String json = "{"
                                            + "\"toUid\":\"" + ownerUid + "\","
                                            + "\"fromUid\":\"" + myUid + "\","
                                            + "\"fromName\":\"" + escapeJson(fn) + "\","
                                            + "\"fromPhoto\":\"" + escapeJson(fp) + "\","
                                            + "\"type\":\"like_milestone\","
                                            + "\"videoId\":\"" + videoId + "\","
                                            + "\"videoTitle\":\"" + escapeJson(vTitle) + "\""
                                            + "}";
                                        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                                        okhttp3.Request req = new okhttp3.Request.Builder()
                                            .url(com.callx.app.utils.Constants.SERVER_URL + "/notify/youtube")
                                            .post(okhttp3.RequestBody.create(json,
                                                okhttp3.MediaType.parse("application/json")))
                                            .build();
                                        client.newCall(req).execute().close();
                                    } catch (Exception ignored) {}
                                });
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
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
        if (myUid.isEmpty()) {
            Toast.makeText(this, "⚠️ Subscribe ke liye login karo", Toast.LENGTH_SHORT).show();
            return;
        }
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
                        Log.d(TAG, "Unsubscribed from: " + channelUid);
                    } else {
                        YouTubeFirebaseUtils.subscriptionsRef(myUid).child(channelUid).setValue(true);
                        YouTubeFirebaseUtils.subscribersRef(channelUid).child(myUid).setValue(true);
                        YouTubeFirebaseUtils.channelRef(channelUid).child("subscriberCount")
                            .setValue(ServerValue.increment(1));
                        postSubscribeNotif(channelUid);
                        Log.d(TAG, "Subscribed to: " + channelUid);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    Log.e(TAG, "toggleSubscribe fail: " + e.getMessage());
                }
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

    private void addToWatchLater() {
        if (myUid.isEmpty()) {
            Toast.makeText(this, "⚠️ Watch Later ke liye login karo", Toast.LENGTH_SHORT).show();
            return;
        }
        YouTubeFirebaseUtils.watchLaterRef(myUid).child(videoId)
            .setValue(System.currentTimeMillis())
            .addOnSuccessListener(v -> {
                Log.d(TAG, "Watch Later me add hua: " + videoId);
                Toast.makeText(this, "✅ Watch Later me add ho gaya", Toast.LENGTH_SHORT).show();
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Watch Later fail: " + e.getMessage());
                Toast.makeText(this, "❌ Watch Later fail: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
    }

    private void downloadCurrentVideo() {
        if (currentVideo == null) {
            // Build minimal video object
            com.callx.app.models.YouTubeVideo v = new com.callx.app.models.YouTubeVideo();
            v.videoId   = videoId;
            v.title     = tvTitle != null ? tvTitle.getText().toString() : videoId;
            v.videoUrl  = null; // will fail gracefully
            currentVideo = v;
        }
        YouTubeDownloadManager.startDownload(this, currentVideo,
            new YouTubeDownloadManager.DownloadCallback() {
                @Override public void onStarted() {
                    if (btnDownload != null) btnDownload.setAlpha(0.5f);
                }
                @Override public void onProgress(int pct) { /* notif handles it */ }
                @Override public void onCompleted(String path) {
                    if (btnDownload != null) {
                        btnDownload.setAlpha(1f);
                        btnDownload.setImageResource(R.drawable.ic_yt_download_done);
                    }
                }
                @Override public void onAlreadyDownloaded(String path) {
                    if (btnDownload != null)
                        btnDownload.setImageResource(R.drawable.ic_yt_download_done);
                }
                @Override public void onError(String err) {
                    if (btnDownload != null) btnDownload.setAlpha(1f);
                }
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
        YouTubeFirebaseUtils.watchHistoryRef(myUid).child(videoId)
            .setValue(System.currentTimeMillis());
        Log.d(TAG, "Watch history recorded: " + videoId);
    }

    private void incrementViews(long current) {
        YouTubeFirebaseUtils.videoRef(videoId).child("viewCount")
            .setValue(ServerValue.increment(1));
        YouTubeFirebaseUtils.videoViewsRef(videoId)
            .child(myUid.isEmpty() ? "anon" : myUid)
            .setValue(System.currentTimeMillis());
        Log.d(TAG, "View count increment kiya (was " + current + ")");
    }

    private void loadRelated() {
        Log.d(TAG, "Related videos load kar raha hai...");
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
                    Log.d(TAG, "Related videos mile: " + list.size());
                    relatedAdapter.setData(list);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    Log.e(TAG, "Related load fail: " + e.getMessage());
                }
            });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (ytPrefs.isPip() && player != null && player.isPlaying()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                enterPictureInPictureMode(pipBuilder.build());
                Log.d(TAG, "PiP mode entered (setting ON)");
            }
        }
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

    @Override protected void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
            Log.d(TAG, "onPause — player paused");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (player != null && playerReadyToPlay) {
            player.play();
            Log.d(TAG, "onResume — player resumed");
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
        // videoListener ab SingleValueEvent hai — remove ki zarurat nahi, but safe guard
        if (videoListener != null)
            YouTubeFirebaseUtils.videoRef(videoId).removeEventListener(videoListener);
        if (likeListener != null)
            YouTubeFirebaseUtils.videoLikesRef(videoId).removeEventListener(likeListener);
        if (subsListener != null && !myUid.isEmpty())
            YouTubeFirebaseUtils.subscriptionsRef(myUid).removeEventListener(subsListener);
        Log.d(TAG, "onDestroy — sab cleanup ho gaya");
    }

    /** Check if device is currently on Wi-Fi */
    private boolean isOnWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network nw = cm.getActiveNetwork();
                if (nw == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return false;
        }
    }

    /** Apply dark/light/system theme based on General settings */
    private void applyAppearanceTheme() {
        int mode = ytPrefs.getThemeMode();
        int nightMode;
        switch (mode) {
            case 1: nightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;  break;
            case 2: nightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES; break;
            default: nightMode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ── Real YouTube-style Description Bottom Sheet ───────────────────────────

    private void openDescriptionSheet() {
        if (currentVideo == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        android.view.View view = getLayoutInflater().inflate(
            R.layout.bottom_sheet_yt_description, null);
        sheet.setContentView(view);

        // Title
        TextView sheetTitle = view.findViewById(R.id.tv_desc_sheet_title);
        if (sheetTitle != null) sheetTitle.setText(currentVideo.title);

        // Stats boxes
        TextView sheetLikes = view.findViewById(R.id.tv_desc_sheet_likes);
        TextView sheetViews = view.findViewById(R.id.tv_desc_sheet_views);
        TextView sheetDate  = view.findViewById(R.id.tv_desc_sheet_date);
        if (sheetLikes != null) sheetLikes.setText(formatCount(currentVideo.likeCount));
        if (sheetViews != null) sheetViews.setText(formatCount(currentVideo.viewCount));
        if (sheetDate  != null) sheetDate.setText(formatTimeAgo(currentVideo.uploadedAt));

        // Description body
        TextView sheetBody = view.findViewById(R.id.tv_desc_sheet_body);
        if (sheetBody != null) {
            sheetBody.setText(
                (currentVideo.description != null && !currentVideo.description.isEmpty())
                ? currentVideo.description
                : "No description available.");
        }

        // Video details section
        TextView detailDate  = view.findViewById(R.id.tv_desc_detail_date);
        TextView detailViews = view.findViewById(R.id.tv_desc_detail_views);
        TextView detailLikes = view.findViewById(R.id.tv_desc_detail_likes);
        if (detailDate  != null && currentVideo.uploadedAt > 0) {
            detailDate.setText(new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(new Date(currentVideo.uploadedAt)));
        }
        if (detailViews != null)
            detailViews.setText(String.format(Locale.getDefault(),
                "%,d", currentVideo.viewCount));
        if (detailLikes != null)
            detailLikes.setText(String.format(Locale.getDefault(),
                "%,d", currentVideo.likeCount));

        // Channel info
        TextView sheetChannelName = view.findViewById(R.id.tv_desc_sheet_channel_name);
        TextView sheetSubs        = view.findViewById(R.id.tv_desc_sheet_subs);
        de.hdodenhof.circleimageview.CircleImageView sheetAvatar =
            view.findViewById(R.id.iv_desc_sheet_avatar);
        if (sheetChannelName != null) sheetChannelName.setText(currentVideo.uploaderName);
        if (sheetAvatar != null) {
            com.bumptech.glide.Glide.with(this)
                .load(currentVideo.uploaderPhotoUrl).circleCrop().into(sheetAvatar);
        }
        // Load subscriber count for channel
        if (sheetSubs != null && currentVideo.uploaderUid != null) {
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("youtube_channels")
                .child(currentVideo.uploaderUid)
                .child("subscriberCount")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@androidx.annotation.NonNull
                            com.google.firebase.database.DataSnapshot snap) {
                        long subs = snap.exists() ? snap.getValue(Long.class) : 0;
                        sheetSubs.setText(formatCount(subs) + " subscribers");
                    }
                    @Override public void onCancelled(@androidx.annotation.NonNull
                            com.google.firebase.database.DatabaseError e) {}
                });
        }

        // Channel row tap → open channel page
        android.view.View channelRow = view.findViewById(R.id.ll_desc_sheet_channel);
        if (channelRow != null && currentVideo.uploaderUid != null) {
            channelRow.setOnClickListener(cv -> {
                sheet.dismiss();
                startActivity(new Intent(this, YouTubeChannelActivity.class)
                    .putExtra("uid", currentVideo.uploaderUid));
            });
        }

        // Videos button → open channel
        android.view.View btnVideos = view.findViewById(R.id.btn_desc_sheet_videos);
        if (btnVideos != null && currentVideo.uploaderUid != null) {
            btnVideos.setOnClickListener(cv -> {
                sheet.dismiss();
                startActivity(new Intent(this, YouTubeChannelActivity.class)
                    .putExtra("uid", currentVideo.uploaderUid));
            });
        }

        // Close button
        android.view.View btnClose = view.findViewById(R.id.btn_yt_desc_close);
        if (btnClose != null) btnClose.setOnClickListener(cv -> sheet.dismiss());

        // Bottom sheet ko puri height pe open karo (screenshot jaisa)
        sheet.setOnShowListener(d -> {
            com.google.android.material.bottomsheet.BottomSheetBehavior<?> behavior =
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(
                    (android.view.View) view.getParent());
            behavior.setState(
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        });

        sheet.show();
    }

    // "1h ago", "2 days ago", "3 weeks ago" format — real YouTube jaisa
    private String formatTimeAgo(long timestampMs) {
        if (timestampMs <= 0) return "";
        long diff = System.currentTimeMillis() - timestampMs;
        long mins  = diff / 60_000;
        long hours = diff / 3_600_000;
        long days  = diff / 86_400_000;
        long weeks = days / 7;
        long months= days / 30;
        long years = days / 365;
        if (mins  < 60)  return mins  + (mins  == 1 ? " minute ago"  : " minutes ago");
        if (hours < 24)  return hours + (hours == 1 ? " hour ago"    : " hours ago");
        if (days  < 7)   return days  + (days  == 1 ? " day ago"     : " days ago");
        if (weeks < 5)   return weeks + (weeks == 1 ? " week ago"    : " weeks ago");
        if (months< 12)  return months+ (months== 1 ? " month ago"   : " months ago");
        return years + (years == 1 ? " year ago" : " years ago");
    }
}
