package com.callx.app.activities;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
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
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.sheets.YouTubeVideoOptionsSheet;
import com.callx.app.utils.YouTubeDownloadManager;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.utils.YouTubePrefs;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class YouTubePlayerActivity extends AppCompatActivity {

    private ExoPlayer    player;
    private PlayerView   playerView;
    private ProgressBar  pbPlayer;
    private View         llPlayerError;
    private TextView     tvPlayerError;
    private TextView     tvTitle, tvChannelName, tvViews, tvLikes, tvDesc;
    private ImageButton  btnLike, btnDislike, btnShare;
    private android.widget.Button  btnSubscribe;
    private android.widget.ImageButton btnDownload;
    private CircleImageView ivChannelAvatar;
    private RecyclerView rvRelated;
    private YouTubeVideoAdapter relatedAdapter;

    private String  videoId;
    private String  myUid;
    private boolean isLiked       = false;
    private boolean isDisliked    = false;
    private boolean isSubscribed  = false;
    private boolean playerReady   = false;
    private boolean playerInited  = false;
    private YouTubePrefs ytPrefs;
    private String  channelOwnerUid;
    private YouTubeVideo currentVideo;

    private ValueEventListener videoListener;
    private ValueEventListener likeListener;
    private ValueEventListener subsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_player);

        videoId  = getIntent().getStringExtra("video_id");
        ytPrefs  = new YouTubePrefs(this);
        applyTheme();

        if (videoId == null) { finish(); return; }

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        bindViews();

        relatedAdapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvRelated.setLayoutManager(new LinearLayoutManager(this));
        rvRelated.setAdapter(relatedAdapter);

        loadVideo();
        loadLikeState();
        loadSubscribeState();
        loadRelated();
        recordWatchHistory();

        // Download button
        if (btnDownload != null) {
            btnDownload.setOnClickListener(v -> downloadCurrentVideo());
            if (YouTubeDownloadManager.isDownloaded(this, videoId))
                btnDownload.setImageResource(R.drawable.ic_yt_download_done);
        }

        View btnBack     = findViewById(R.id.btn_yt_back);
        View btnComments = findViewById(R.id.btn_yt_comments);
        View btnWatchLater = findViewById(R.id.btn_yt_watch_later);
        View btnMore     = findViewById(R.id.btn_yt_player_more);

        if (btnBack != null) btnBack.setOnClickListener(v -> onBackPressed());
        if (btnComments != null) btnComments.setOnClickListener(v -> openComments());
        if (btnWatchLater != null) btnWatchLater.setOnClickListener(v -> addToWatchLater());
        if (btnMore != null) btnMore.setOnClickListener(v -> showOptionsSheet());

        if (btnLike    != null) btnLike.setOnClickListener(v -> toggleLike());
        if (btnDislike != null) btnDislike.setOnClickListener(v -> toggleDislike());
        if (btnShare   != null) btnShare.setOnClickListener(v -> shareVideo());
        if (btnSubscribe != null) btnSubscribe.setOnClickListener(v -> toggleSubscribe());

        boolean openComments = getIntent().getBooleanExtra("open_comments", false);
        if (openComments) openComments();
    }

    private void bindViews() {
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
        btnDownload     = findViewById(R.id.btn_yt_download);
        rvRelated       = findViewById(R.id.rv_yt_related);
        showPlayerLoading(true);
    }

    // ── Video loading ─────────────────────────────────────────────────────────

    private void loadVideo() {
        videoListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                currentVideo = snap.getValue(YouTubeVideo.class);
                if (currentVideo == null) { showPlayerError("Video not found."); return; }
                populateUI(currentVideo);
                String localPath = getIntent().getStringExtra("local_path");
                String url = (localPath != null && !localPath.isEmpty())
                    ? localPath
                    : toPlayableUrl(currentVideo.videoUrl);
                if (!playerInited) {
                    playerInited = true;
                    initPlayer(url);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                showPlayerError("Failed to load video.");
            }
        };
        YouTubeFirebaseUtils.videoRef(videoId).addValueEventListener(videoListener);
    }

    private void populateUI(YouTubeVideo v) {
        if (tvTitle       != null) tvTitle.setText(v.title);
        if (tvChannelName != null) tvChannelName.setText(v.uploaderName);
        if (tvViews       != null) tvViews.setText(formatCount(v.viewCount) + " views");
        if (tvLikes       != null) tvLikes.setText(formatCount(v.likeCount));
        if (tvDesc        != null) tvDesc.setText(v.description);

        channelOwnerUid = v.uploaderUid;

        if (ivChannelAvatar != null && v.uploaderPhotoUrl != null)
            Glide.with(this).load(v.uploaderPhotoUrl).circleCrop().into(ivChannelAvatar);
        if (ivChannelAvatar != null)
            ivChannelAvatar.setOnClickListener(x -> openChannelPage(v.uploaderUid));
        if (tvChannelName != null)
            tvChannelName.setOnClickListener(x -> openChannelPage(v.uploaderUid));
    }

    private void openChannelPage(String uid) {
        startActivity(new Intent(this, YouTubeChannelActivity.class).putExtra("uid", uid));
    }

    // ── ExoPlayer ─────────────────────────────────────────────────────────────

    private void initPlayer(String url) {
        DefaultHttpDataSource.Factory dsFactory = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory))
            .build();

        playerView.setPlayer(player);
        playerView.setResizeMode(ytPrefs.isZoomToFill()
            ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            : AspectRatioFrameLayout.RESIZE_MODE_FIT);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    showPlayerLoading(false);
                    playerReady = true;
                } else if (state == Player.STATE_BUFFERING) {
                    showPlayerLoading(true);
                } else if (state == Player.STATE_ENDED) {
                    if (ytPrefs.isAutoplay()) playNextRelated();
                }
            }
            @Override public void onPlayerError(@NonNull PlaybackException e) {
                showPlayerError("Playback error: " + e.getMessage());
            }
        });

        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();

        incrementViewCount();
    }

    private String toPlayableUrl(String url) {
        if (url == null) return "";
        // Cloudinary: inject f_mp4 transformation for cross-format compatibility
        if (url.contains("cloudinary.com") && url.contains("/upload/")) {
            return url.replace("/upload/", "/upload/f_mp4,q_auto/");
        }
        return url;
    }

    private void incrementViewCount() {
        YouTubeFirebaseUtils.videoRef(videoId)
            .child("viewCount").setValue(ServerValue.increment(1));
        // Update creator analytics daily snapshot
        if (currentVideo != null && currentVideo.uploaderUid != null) {
            String dateKey = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            YouTubeFirebaseUtils.dailyAnalyticsRef(currentVideo.uploaderUid, dateKey)
                .setValue(ServerValue.increment(1));
            YouTubeFirebaseUtils.channelRef(currentVideo.uploaderUid)
                .child("totalViews").setValue(ServerValue.increment(1));
        }
    }

    // ── Like / Dislike ────────────────────────────────────────────────────────

    private void loadLikeState() {
        if (myUid.isEmpty()) return;
        likeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isLiked    = snap.child(myUid).exists();
                isDisliked = false;
                updateLikeUI();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.videoLikesRef(videoId).addValueEventListener(likeListener);
    }

    private void toggleLike() {
        if (myUid.isEmpty()) { Toast.makeText(this,"Sign in to like",Toast.LENGTH_SHORT).show(); return; }
        if (isLiked) {
            YouTubeFirebaseUtils.videoLikesRef(videoId).child(myUid).removeValue();
            YouTubeFirebaseUtils.likedVideosRef(myUid).child(videoId).removeValue();
            YouTubeFirebaseUtils.videoRef(videoId).child("likeCount").setValue(ServerValue.increment(-1));
            if (currentVideo != null && currentVideo.uploaderUid != null)
                YouTubeFirebaseUtils.channelRef(currentVideo.uploaderUid)
                    .child("totalLikes").setValue(ServerValue.increment(-1));
        } else {
            YouTubeFirebaseUtils.videoLikesRef(videoId).child(myUid).setValue(true);
            YouTubeFirebaseUtils.likedVideosRef(myUid).child(videoId)
                .setValue(System.currentTimeMillis());
            YouTubeFirebaseUtils.videoRef(videoId).child("likeCount").setValue(ServerValue.increment(1));
            if (currentVideo != null && currentVideo.uploaderUid != null) {
                YouTubeFirebaseUtils.channelRef(currentVideo.uploaderUid)
                    .child("totalLikes").setValue(ServerValue.increment(1));
                sendLikeNotification();
            }
            // Remove dislike if active
            if (isDisliked) {
                YouTubeFirebaseUtils.videoDislikesRef(videoId).child(myUid).removeValue();
                YouTubeFirebaseUtils.videoRef(videoId).child("dislikeCount").setValue(ServerValue.increment(-1));
            }
        }
        isLiked    = !isLiked;
        if (isLiked) isDisliked = false;
        updateLikeUI();
    }

    private void toggleDislike() {
        if (myUid.isEmpty()) return;
        if (isDisliked) {
            YouTubeFirebaseUtils.videoDislikesRef(videoId).child(myUid).removeValue();
            YouTubeFirebaseUtils.videoRef(videoId).child("dislikeCount").setValue(ServerValue.increment(-1));
        } else {
            YouTubeFirebaseUtils.videoDislikesRef(videoId).child(myUid).setValue(true);
            YouTubeFirebaseUtils.videoRef(videoId).child("dislikeCount").setValue(ServerValue.increment(1));
            if (isLiked) {
                YouTubeFirebaseUtils.videoLikesRef(videoId).child(myUid).removeValue();
                YouTubeFirebaseUtils.likedVideosRef(myUid).child(videoId).removeValue();
                YouTubeFirebaseUtils.videoRef(videoId).child("likeCount").setValue(ServerValue.increment(-1));
                isLiked = false;
            }
        }
        isDisliked = !isDisliked;
        updateLikeUI();
    }

    private void updateLikeUI() {
        if (btnLike    != null) btnLike.setImageResource(
            isLiked    ? R.drawable.ic_yt_like_filled : R.drawable.ic_yt_like);
        if (btnDislike != null) btnDislike.setImageResource(
            isDisliked ? R.drawable.ic_yt_like_filled : R.drawable.ic_yt_dislike);
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────

    private void loadSubscribeState() {
        if (myUid.isEmpty()) return;
        subsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isSubscribed = channelOwnerUid != null && snap.hasChild(channelOwnerUid);
                updateSubscribeUI();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.subscriptionsRef(myUid).addValueEventListener(subsListener);
    }

    private void toggleSubscribe() {
        if (myUid.isEmpty() || channelOwnerUid == null) return;
        if (isSubscribed) {
            YouTubeFirebaseUtils.subscriptionsRef(myUid).child(channelOwnerUid).removeValue();
            YouTubeFirebaseUtils.subscribersRef(channelOwnerUid).child(myUid).removeValue();
            YouTubeFirebaseUtils.channelRef(channelOwnerUid).child("subscriberCount")
                .setValue(ServerValue.increment(-1));
        } else {
            YouTubeFirebaseUtils.subscriptionsRef(myUid).child(channelOwnerUid).setValue(true);
            YouTubeFirebaseUtils.subscribersRef(channelOwnerUid).child(myUid).setValue(true);
            YouTubeFirebaseUtils.channelRef(channelOwnerUid).child("subscriberCount")
                .setValue(ServerValue.increment(1));
            sendSubscribeNotification();
        }
        isSubscribed = !isSubscribed;
        updateSubscribeUI();
    }

    private void updateSubscribeUI() {
        if (btnSubscribe != null)
            btnSubscribe.setText(isSubscribed ? "Subscribed" : "Subscribe");
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void openComments() {
        startActivity(new Intent(this, YouTubeCommentsActivity.class)
            .putExtra("video_id", videoId));
    }

    private void addToWatchLater() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.watchLaterRef(myUid).child(videoId)
            .setValue(System.currentTimeMillis());
        YouTubeFirebaseUtils.videoRef(videoId).child("savedCount").setValue(ServerValue.increment(1));
        Toast.makeText(this, "Added to Watch Later", Toast.LENGTH_SHORT).show();
    }

    private void shareVideo() {
        if (currentVideo == null) return;
        String msg = currentVideo.title + "\nhttps://callx.app/watch?v=" + videoId;
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg);
        startActivity(Intent.createChooser(share, "Share video"));
        YouTubeFirebaseUtils.videoRef(videoId).child("shareCount").setValue(ServerValue.increment(1));
        if (!myUid.isEmpty())
            YouTubeFirebaseUtils.sharedVideosRef(myUid).child(videoId)
                .setValue(System.currentTimeMillis());
    }

    private void downloadCurrentVideo() {
        if (currentVideo == null) return;
        YouTubeDownloadManager.startDownload(this, currentVideo,
            new YouTubeDownloadManager.DownloadCallback() {
                @Override public void onStarted() {}
                @Override public void onProgress(int percent) {}
                @Override public void onCompleted(String localPath) {
                    runOnUiThread(() -> {
                        Toast.makeText(YouTubePlayerActivity.this,
                            "Download complete", Toast.LENGTH_SHORT).show();
                        if (btnDownload != null)
                            btnDownload.setImageResource(R.drawable.ic_yt_download_done);
                    });
                }
                @Override public void onAlreadyDownloaded(String localPath) {
                    runOnUiThread(() -> Toast.makeText(YouTubePlayerActivity.this,
                        "Already downloaded", Toast.LENGTH_SHORT).show());
                }
                @Override public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(YouTubePlayerActivity.this,
                        "Download failed: " + error, Toast.LENGTH_LONG).show());
                }
            });
    }

    private void showOptionsSheet() {
        if (currentVideo == null) {
            YouTubeVideo v = new YouTubeVideo();
            v.videoId      = videoId;
            v.title        = tvTitle != null ? tvTitle.getText().toString() : "";
            v.uploaderName = tvChannelName != null ? tvChannelName.getText().toString() : "";
            v.uploaderUid  = channelOwnerUid != null ? channelOwnerUid : "";
            currentVideo   = v;
        }
        YouTubeVideoOptionsSheet sheet = YouTubeVideoOptionsSheet.newInstance(currentVideo);
        sheet.setCallback(new YouTubeVideoOptionsSheet.OptionsCallback() {
            @Override public void onNotInterested(String vid) {}
            @Override public void onVideoDeleted(String vid) { finish(); }
        });
        sheet.show(getSupportFragmentManager(), "yt_player_options");
    }

    private void loadRelated() {
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                        if (v != null && !videoId.equals(v.videoId) && !v.isShort
                                && "public".equals(v.visibility))
                            list.add(0, v);
                    }
                    relatedAdapter.setData(list);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void recordWatchHistory() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.watchHistoryRef(myUid).child(videoId)
            .setValue(System.currentTimeMillis());
    }

    private void playNextRelated() {
        YouTubeVideo first = relatedAdapter.getFirst();
        if (first != null)
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", first.videoId));
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private void sendLikeNotification() {
        if (channelOwnerUid == null || channelOwnerUid.equals(myUid)) return;
        YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String myName  = snap.child("channelName").getValue(String.class);
                    String myPhoto = snap.child("photoUrl").getValue(String.class);
                    String nKey = YouTubeFirebaseUtils.notificationsRef(channelOwnerUid).push().getKey();
                    if (nKey == null) return;
                    YouTubeNotification n = new YouTubeNotification(nKey, channelOwnerUid,
                        myUid, myName, myPhoto, "like", videoId,
                        currentVideo != null ? currentVideo.title : "", null);
                    YouTubeFirebaseUtils.notificationsRef(channelOwnerUid).child(nKey).setValue(n);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void sendSubscribeNotification() {
        if (channelOwnerUid == null || channelOwnerUid.equals(myUid)) return;
        YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String myName  = snap.child("channelName").getValue(String.class);
                    String myPhoto = snap.child("photoUrl").getValue(String.class);
                    String nKey = YouTubeFirebaseUtils.notificationsRef(channelOwnerUid).push().getKey();
                    if (nKey == null) return;
                    YouTubeNotification n = new YouTubeNotification(nKey, channelOwnerUid,
                        myUid, myName, myPhoto, "subscribe", null, null, null);
                    YouTubeFirebaseUtils.notificationsRef(channelOwnerUid).child(nKey).setValue(n);
                    sendFcmSubscribeNotif(myName, myPhoto);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void sendFcmSubscribeNotif(String myName, String myPhoto) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String json = "{\"toUid\":\"" + channelOwnerUid + "\","
                    + "\"fromUid\":\"" + myUid + "\","
                    + "\"fromName\":\"" + esc(myName) + "\","
                    + "\"fromPhoto\":\"" + esc(myPhoto) + "\","
                    + "\"type\":\"subscribe\"}";
                new OkHttpClient().newCall(new Request.Builder()
                    .url(com.callx.app.utils.Constants.SERVER_URL + "/notify/youtube")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build()).execute().close();
            } catch (Exception ignored) {}
        });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override protected void onResume() {
        super.onResume();
        if (player != null && playerReady) player.play();
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
        if (ytPrefs.isPip() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().build());
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
        if (videoListener != null)
            YouTubeFirebaseUtils.videoRef(videoId).removeEventListener(videoListener);
        if (likeListener != null)
            YouTubeFirebaseUtils.videoLikesRef(videoId).removeEventListener(likeListener);
        if (subsListener != null && !myUid.isEmpty())
            YouTubeFirebaseUtils.subscriptionsRef(myUid).removeEventListener(subsListener);
    }

    @Override public void onConfigurationChanged(@NonNull Configuration cfg) {
        super.onConfigurationChanged(cfg);
        boolean landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        View appBar = findViewById(R.id.yt_player_appbar);
        if (appBar != null) appBar.setVisibility(landscape ? View.GONE : View.VISIBLE);
        if (rvRelated != null) rvRelated.setVisibility(landscape ? View.GONE : View.VISIBLE);
        if (playerView != null)
            playerView.setResizeMode(landscape
                ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void showPlayerLoading(boolean show) {
        if (pbPlayer    != null) pbPlayer.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showPlayerError(String msg) {
        showPlayerLoading(false);
        if (llPlayerError != null) llPlayerError.setVisibility(View.VISIBLE);
        if (tvPlayerError != null) tvPlayerError.setText(msg);
    }

    private void applyTheme() {
        int mode = ytPrefs.getThemeMode();
        switch (mode) {
            case 1: androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO); break;
            case 2: androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES); break;
            default: androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
