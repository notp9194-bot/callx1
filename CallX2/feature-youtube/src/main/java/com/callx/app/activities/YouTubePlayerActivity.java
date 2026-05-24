package com.callx.app.activities;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.ImageButton;
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
import com.callx.app.models.YouTubeChapter;
import com.callx.app.models.YouTubeNotification;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.utils.YouTubeWatchProgressUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

public class YouTubePlayerActivity extends AppCompatActivity {

    private static final String PREFS        = "yt_player_prefs";
    private static final String KEY_AUTOPLAY = "autoplay";
    private static final String KEY_SPEED    = "speed";

    private ExoPlayer  player;
    private PlayerView playerView;

    private TextView       tvTitle, tvChannelName, tvViews, tvLikes,
                           tvDislikes, tvDesc, tvSubscribeBell;
    private ImageButton    btnLike, btnDislike, btnShare, btnMore;
    private android.widget.Button btnSubscribe;
    private CircleImageView ivChannelAvatar;
    private RecyclerView   rvRelated, rvChapters;
    private YouTubeVideoAdapter relatedAdapter;

    private View   btnBack, btnWatchLater, btnAddPlaylist, btnComments,
                   btnPip, btnSpeed, btnQuality, layoutChapters;
    private CheckBox cbLoop, cbAutoplay;

    private String  videoId, myUid, channelUid;
    private boolean isLiked = false, isDisliked = false,
                    isSubscribed = false, isLooping = false, autoplay = true;
    private float   playbackSpeed = 1.0f;
    private long    savedPosition = 0;

    private ValueEventListener videoListener, likeListener, dislikeListener, subsListener;
    private SharedPreferences  prefs;
    private Handler            progressHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_player);

        videoId = getIntent().getStringExtra("video_id");
        boolean openComments = getIntent().getBooleanExtra("open_comments", false);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        if (videoId == null) { finish(); return; }

        prefs         = getSharedPreferences(PREFS, MODE_PRIVATE);
        autoplay      = prefs.getBoolean(KEY_AUTOPLAY, true);
        playbackSpeed = prefs.getFloat(KEY_SPEED, 1.0f);
        savedPosition = YouTubeWatchProgressUtils.load(this, videoId);

        bindViews();
        setupRelated();
        setupInteractions();
        loadVideo();
        loadLikeState();
        loadDislikeState();
        loadSubscribeState();
        loadWatchLaterState();
        recordWatchHistory();
        if (openComments) openComments();
    }

    private void bindViews() {
        playerView      = findViewById(R.id.yt_player_view);
        tvTitle         = findViewById(R.id.tv_yt_video_title);
        tvChannelName   = findViewById(R.id.tv_yt_channel_name);
        tvViews         = findViewById(R.id.tv_yt_views);
        tvLikes         = findViewById(R.id.tv_yt_likes);
        tvDislikes      = findViewById(R.id.tv_yt_dislikes);
        tvDesc          = findViewById(R.id.tv_yt_description);
        tvSubscribeBell = findViewById(R.id.tv_yt_subscribe_bell);
        ivChannelAvatar = findViewById(R.id.iv_yt_channel_avatar);
        btnLike         = findViewById(R.id.btn_yt_like);
        btnDislike      = findViewById(R.id.btn_yt_dislike);
        btnSubscribe    = findViewById(R.id.btn_yt_subscribe);
        btnShare        = findViewById(R.id.btn_yt_share);
        btnMore         = findViewById(R.id.btn_yt_more);
        btnBack         = findViewById(R.id.btn_yt_back);
        btnWatchLater   = findViewById(R.id.btn_yt_watch_later);
        btnAddPlaylist  = findViewById(R.id.btn_yt_add_playlist);
        btnComments     = findViewById(R.id.btn_yt_comments);
        btnPip          = findViewById(R.id.btn_yt_pip);
        btnSpeed        = findViewById(R.id.btn_yt_speed);
        btnQuality      = findViewById(R.id.btn_yt_quality);
        rvRelated       = findViewById(R.id.rv_yt_related);
        rvChapters      = findViewById(R.id.rv_yt_chapters);
        layoutChapters  = findViewById(R.id.layout_yt_chapters);
        cbLoop          = findViewById(R.id.cb_yt_loop);
        cbAutoplay      = findViewById(R.id.cb_yt_autoplay);

        if (btnBack != null) btnBack.setOnClickListener(v -> onBackPressed());
        if (tvDesc  != null) tvDesc.setOnClickListener(v -> toggleDescription());
    }

    private void setupRelated() {
        relatedAdapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video -> {
            if (player != null)
                YouTubeWatchProgressUtils.save(this, videoId, player.getCurrentPosition());
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId));
            finish();
        });
        rvRelated.setLayoutManager(new LinearLayoutManager(this));
        rvRelated.setAdapter(relatedAdapter);
    }

    private void setupInteractions() {
        if (btnLike       != null) btnLike.setOnClickListener(v      -> toggleLike());
        if (btnDislike    != null) btnDislike.setOnClickListener(v   -> toggleDislike());
        if (btnSubscribe  != null) btnSubscribe.setOnClickListener(v -> handleSubscribeClick());
        if (btnShare      != null) btnShare.setOnClickListener(v     -> shareVideo());
        if (btnMore       != null) btnMore.setOnClickListener(v      -> showMoreMenu());
        if (btnComments   != null) btnComments.setOnClickListener(v  -> openComments());
        if (btnWatchLater != null) btnWatchLater.setOnClickListener(v-> toggleWatchLater());
        if (btnAddPlaylist!= null) btnAddPlaylist.setOnClickListener(v->openAddToPlaylist());
        if (btnPip        != null) btnPip.setOnClickListener(v       -> enterPiP());
        if (btnSpeed      != null) btnSpeed.setOnClickListener(v     -> showSpeedPicker());
        if (btnQuality    != null) btnQuality.setOnClickListener(v   -> showQualityPicker());

        if (cbLoop != null) {
            cbLoop.setChecked(isLooping);
            cbLoop.setOnCheckedChangeListener((b, c) -> {
                isLooping = c;
                if (player != null)
                    player.setRepeatMode(c ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            });
        }
        if (cbAutoplay != null) {
            cbAutoplay.setChecked(autoplay);
            cbAutoplay.setOnCheckedChangeListener((b, c) -> {
                autoplay = c;
                prefs.edit().putBoolean(KEY_AUTOPLAY, c).apply();
            });
        }
    }

    private void loadVideo() {
        videoListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                if (v == null) return;
                channelUid = v.uploaderUid;
                tvTitle.setText(v.title);
                tvChannelName.setText(v.uploaderName);
                tvViews.setText(formatCount(v.viewCount) + " views");
                tvLikes.setText(formatCount(v.likeCount));
                if (tvDislikes != null) tvDislikes.setText(formatCount(v.dislikeCount));
                if (tvDesc != null) tvDesc.setText(v.description);
                Glide.with(YouTubePlayerActivity.this)
                    .load(v.uploaderPhotoUrl).circleCrop().into(ivChannelAvatar);
                ivChannelAvatar.setOnClickListener(cv ->
                    startActivity(new Intent(YouTubePlayerActivity.this,
                        YouTubeChannelActivity.class).putExtra("uid", v.uploaderUid)));
                if (v.videoUrl != null && !v.videoUrl.trim().isEmpty()) initPlayer(v.videoUrl);
                parseChapters(v.description);
                incrementViews(v.uploaderUid);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.videoRef(videoId).addValueEventListener(videoListener);
    }

    private void initPlayer(String url) {
        if (player != null) player.release();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setRepeatMode(isLooping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.setPlaybackSpeed(playbackSpeed);
        if (savedPosition > 5000) player.seekTo(savedPosition);
        player.play();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && autoplay && !isLooping)
                    loadNextRecommendedVideo();
            }
        });
        startProgressTracking();
    }

    private void startProgressTracking() {
        progressHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (player != null && player.isPlaying()) {
                    YouTubeWatchProgressUtils.save(
                        YouTubePlayerActivity.this, videoId, player.getCurrentPosition());
                    YouTubeWatchProgressUtils.trackWatchTime(
                        YouTubePlayerActivity.this, videoId, 5);
                }
                progressHandler.postDelayed(this, 5000);
            }
        }, 5000);
    }

    private void parseChapters(String description) {
        if (description == null || layoutChapters == null || rvChapters == null) return;
        List<YouTubeChapter> chapters = YouTubeChapter.parseFromDescription(description);
        if (chapters.isEmpty()) {
            layoutChapters.setVisibility(View.GONE);
            return;
        }
        layoutChapters.setVisibility(View.VISIBLE);
        com.callx.app.adapters.YouTubeChapterAdapter adapter =
            new com.callx.app.adapters.YouTubeChapterAdapter(chapters, ch -> {
                if (player != null) player.seekTo(ch.startMs);
            });
        rvChapters.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvChapters.setAdapter(adapter);
    }

    private void toggleDescription() {
        // Inline expand/collapse handled by two TextViews in the layout
    }

    // ── Playback controls ────────────────────────────────────────────────────

    private void showSpeedPicker() {
        float[]  speeds = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        String[] labels = {"0.25x","0.5x","0.75x","Normal","1.25x","1.5x","1.75x","2x"};
        int cur = 3;
        for (int i = 0; i < speeds.length; i++)
            if (Math.abs(speeds[i] - playbackSpeed) < 0.01f) { cur = i; break; }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(labels, cur, (dlg, which) -> {
                playbackSpeed = speeds[which];
                if (player != null) player.setPlaybackSpeed(playbackSpeed);
                prefs.edit().putFloat(KEY_SPEED, playbackSpeed).apply();
                dlg.dismiss();
            }).show();
    }

    private void showQualityPicker() {
        String[] qualities = {"Auto (Recommended)", "1080p HD", "720p HD",
                              "480p", "360p", "240p", "144p"};
        new android.app.AlertDialog.Builder(this)
            .setTitle("Video Quality")
            .setItems(qualities, (dlg, which) ->
                Toast.makeText(this, "Quality: " + qualities[which], Toast.LENGTH_SHORT).show())
            .show();
    }

    private void enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9)).build());
            } else {
                Toast.makeText(this, "PiP not supported on this device", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPiP,
                                               @NonNull Configuration cfg) {
        super.onPictureInPictureModeChanged(inPiP, cfg);
        if (playerView != null) playerView.setUseController(!inPiP);
    }

    // ── Social actions ────────────────────────────────────────────────────────

    private void loadLikeState() {
        if (myUid.isEmpty()) return;
        likeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isLiked = snap.hasChild(myUid);
                if (btnLike != null) btnLike.setImageResource(
                    isLiked ? R.drawable.ic_yt_like_filled : R.drawable.ic_yt_like);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.videoLikesRef(videoId).addValueEventListener(likeListener);
    }

    private void loadDislikeState() {
        if (myUid.isEmpty()) return;
        dislikeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isDisliked = snap.hasChild(myUid);
                if (btnDislike != null) btnDislike.setSelected(isDisliked);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.videoDislikesRef(videoId).addValueEventListener(dislikeListener);
    }

    private void loadSubscribeState() {
        if (myUid.isEmpty()) return;
        YouTubeFirebaseUtils.videoRef(videoId).child("uploaderUid")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    channelUid = snap.getValue(String.class);
                    if (channelUid == null) return;
                    subsListener = new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            isSubscribed = s.hasChild(channelUid);
                            if (btnSubscribe != null) {
                                btnSubscribe.setSelected(isSubscribed);
                                btnSubscribe.setText(isSubscribed ? "Subscribed" : "Subscribe");
                            }
                            loadNotifTier();
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    };
                    YouTubeFirebaseUtils.subscriptionsRef(myUid).addValueEventListener(subsListener);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadNotifTier() {
        if (channelUid == null || myUid.isEmpty() || tvSubscribeBell == null) return;
        YouTubeFirebaseUtils.notifTierRef(myUid, channelUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String tier = snap.getValue(String.class);
                    if (tier == null) tier = "personalized";
                    switch (tier) {
                        case "all":  tvSubscribeBell.setText("All \uD83D\uDD14"); break;
                        case "none": tvSubscribeBell.setText("None \uD83D\uDD15"); break;
                        default:     tvSubscribeBell.setText("Personalised \uD83D\uDD14"); break;
                    }
                    tvSubscribeBell.setVisibility(isSubscribed ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void handleSubscribeClick() {
        if (!isSubscribed) { toggleSubscribe(); return; }
        String[] opts = {"All notifications", "Personalised", "None", "Unsubscribe"};
        new android.app.AlertDialog.Builder(this)
            .setTitle("Notifications")
            .setItems(opts, (dlg, which) -> {
                if (which == 3) { toggleSubscribe(); return; }
                String[] tiers = {"all", "personalized", "none"};
                YouTubeFirebaseUtils.notifTierRef(myUid, channelUid).setValue(tiers[which]);
                loadNotifTier();
            }).show();
    }

    private void toggleSubscribe() {
        if (myUid.isEmpty() || channelUid == null || channelUid.equals(myUid)) return;
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
            YouTubeFirebaseUtils.notifTierRef(myUid, channelUid).setValue("personalized");
            sendSubscribeNotif(channelUid);
        }
    }

    private void sendSubscribeNotif(String toUid) {
        String notifId = YouTubeFirebaseUtils.notificationsRef(toUid).push().getKey();
        if (notifId == null) return;
        YouTubeFirebaseUtils.channelRef(myUid).child("channelName")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String myName = snap.getValue(String.class);
                    if (myName == null) myName = "Someone";
                    YouTubeNotification n = new YouTubeNotification(
                        notifId, toUid, myUid, myName, null,
                        "subscribe", null, null, null);
                    YouTubeFirebaseUtils.notificationsRef(toUid).child(notifId).setValue(n);
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
            YouTubeFirebaseUtils.likedVideosRef(myUid).child(videoId)
                .setValue(System.currentTimeMillis());
            if (isDisliked) {
                YouTubeFirebaseUtils.videoDislikesRef(videoId).child(myUid).removeValue();
                YouTubeFirebaseUtils.videoRef(videoId).child("dislikeCount")
                    .setValue(ServerValue.increment(-1));
                isDisliked = false;
            }
        }
    }

    private void toggleDislike() {
        if (myUid.isEmpty()) return;
        if (isDisliked) {
            YouTubeFirebaseUtils.videoDislikesRef(videoId).child(myUid).removeValue();
            YouTubeFirebaseUtils.videoRef(videoId).child("dislikeCount")
                .setValue(ServerValue.increment(-1));
        } else {
            YouTubeFirebaseUtils.videoDislikesRef(videoId).child(myUid).setValue(true);
            YouTubeFirebaseUtils.videoRef(videoId).child("dislikeCount")
                .setValue(ServerValue.increment(1));
            if (isLiked) {
                YouTubeFirebaseUtils.videoLikesRef(videoId).child(myUid).removeValue();
                YouTubeFirebaseUtils.videoRef(videoId).child("likeCount")
                    .setValue(ServerValue.increment(-1));
                isLiked = false;
                YouTubeFirebaseUtils.likedVideosRef(myUid).child(videoId).removeValue();
            }
        }
    }

    private void loadWatchLaterState() {
        if (myUid.isEmpty() || btnWatchLater == null) return;
        YouTubeFirebaseUtils.watchLaterRef(myUid).child(videoId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (btnWatchLater != null) btnWatchLater.setSelected(s.exists());
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void toggleWatchLater() {
        if (myUid.isEmpty()) return;
        DatabaseReference ref = YouTubeFirebaseUtils.watchLaterRef(myUid).child(videoId);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (snap.exists()) {
                    ref.removeValue();
                    if (btnWatchLater != null) btnWatchLater.setSelected(false);
                    Toast.makeText(YouTubePlayerActivity.this,
                        "Removed from Watch Later", Toast.LENGTH_SHORT).show();
                } else {
                    ref.setValue(System.currentTimeMillis());
                    if (btnWatchLater != null) btnWatchLater.setSelected(true);
                    Toast.makeText(YouTubePlayerActivity.this,
                        "Saved to Watch Later", Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void openAddToPlaylist() {
        startActivity(new Intent(this, YouTubePlaylistPickerActivity.class)
            .putExtra("video_id", videoId));
    }

    private void showMoreMenu() {
        PopupMenu popup = new PopupMenu(this, btnMore);
        popup.getMenu().add(0, 1, 0, "Report video");
        popup.getMenu().add(0, 2, 0, "Not interested");
        popup.getMenu().add(0, 3, 0, "Don't recommend channel");
        popup.getMenu().add(0, 4, 0, "Save to playlist");
        popup.getMenu().add(0, 5, 0, "Copy link");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: startActivity(new Intent(this, YouTubeReportActivity.class)
                    .putExtra("video_id", videoId).putExtra("type", "video")); break;
                case 2:
                    YouTubeFirebaseUtils.notInterestedRef(myUid).child(videoId).setValue(true);
                    Toast.makeText(this, "Noted — less like this", Toast.LENGTH_SHORT).show(); break;
                case 3:
                    if (channelUid != null)
                        YouTubeFirebaseUtils.blockedChannelsRef(myUid).child(channelUid).setValue(true);
                    Toast.makeText(this, "Channel won't appear in recommendations",
                        Toast.LENGTH_SHORT).show(); break;
                case 4: openAddToPlaylist(); break;
                case 5:
                    android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("link",
                        "callx://youtube/video/" + videoId));
                    Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show(); break;
            }
            return true;
        });
        popup.show();
    }

    private void shareVideo() {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "callx://youtube/video/" + videoId);
        startActivity(Intent.createChooser(share, "Share Video"));
    }

    private void openComments() {
        startActivity(new Intent(this, YouTubeCommentsActivity.class)
            .putExtra("video_id", videoId));
    }

    private void recordWatchHistory() {
        if (myUid.isEmpty()) return;
        long now = System.currentTimeMillis();
        YouTubeFirebaseUtils.watchHistoryRef(myUid).child(videoId).setValue(now);
        YouTubeFirebaseUtils.continueWatchingRef(myUid).child(videoId).setValue(now);
    }

    private void incrementViews(String uploaderUid) {
        YouTubeFirebaseUtils.videoRef(videoId).child("viewCount")
            .setValue(ServerValue.increment(1));
        if (!myUid.isEmpty())
            YouTubeFirebaseUtils.videoViewsRef(videoId).child(myUid)
                .setValue(System.currentTimeMillis());
        if (uploaderUid != null)
            YouTubeFirebaseUtils.channelRef(uploaderUid).child("totalViews")
                .setValue(ServerValue.increment(1));
    }

    private void loadNextRecommendedVideo() {
        if (relatedAdapter != null && !relatedAdapter.isEmpty()) {
            YouTubeVideo next = relatedAdapter.getFirst();
            if (next != null) {
                startActivity(new Intent(this, YouTubePlayerActivity.class)
                    .putExtra("video_id", next.videoId));
                finish();
            }
        }
    }

    private void setupRelatedFromVideo(YouTubeVideo v) {
        String cat  = v.category;
        String tags = v.tags;
        DatabaseReference ref = (cat != null && !cat.isEmpty())
            ? YouTubeFirebaseUtils.categoryFeedRef(cat)
            : YouTubeFirebaseUtils.globalFeedRef();
        ref.orderByChild("uploadedAt").limitToLast(20)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<YouTubeVideo> list = new ArrayList<>();
                    List<String> tagList = (tags != null && !tags.isEmpty())
                        ? java.util.Arrays.asList(tags.split(",")) : new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        YouTubeVideo vid = ds.getValue(YouTubeVideo.class);
                        if (vid == null || vid.videoId.equals(videoId)) continue;
                        if (!"public".equals(vid.visibility)) continue;
                        int score = 0;
                        if (cat != null && cat.equals(vid.category)) score += 3;
                        if (vid.tags != null)
                            for (String t : tagList) if (vid.tags.contains(t.trim())) score++;
                        vid.relevanceScore = score;
                        list.add(vid);
                    }
                    list.sort((a, b) -> Integer.compare(b.relevanceScore, a.relevanceScore));
                    relatedAdapter.setData(list);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override public void onConfigurationChanged(@NonNull Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (playerView == null) return;
        if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            playerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            playerView.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            playerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        playerView.requestLayout();
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null) {
            YouTubeWatchProgressUtils.save(this, videoId, player.getCurrentPosition());
            if (!isInPictureInPictureMode()) player.pause();
        }
    }
    @Override protected void onResume() {
        super.onResume();
        if (player != null) player.play();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        progressHandler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        if (videoListener    != null) YouTubeFirebaseUtils.videoRef(videoId).removeEventListener(videoListener);
        if (likeListener     != null) YouTubeFirebaseUtils.videoLikesRef(videoId).removeEventListener(likeListener);
        if (dislikeListener  != null) YouTubeFirebaseUtils.videoDislikesRef(videoId).removeEventListener(dislikeListener);
    }

    private String formatCount(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
