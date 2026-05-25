package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

public class YouTubeChannelActivity extends AppCompatActivity {

    private String channelUid, myUid;
    private boolean isSubscribed = false;
    private long subscriberCount = 0;

    private CircleImageView ivAvatar;
    private ImageView ivBanner;
    private TextView tvChannelName, tvHandle, tvSubs, tvBio, tvVideoCount;
    private Button btnSubscribe, btnEditChannel;
    private RecyclerView rvVideos;
    private YouTubeVideoAdapter videoAdapter;

    private ValueEventListener channelListener, subsListener, videosListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_channel);

        channelUid = getIntent().getStringExtra("uid");
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        if (channelUid == null) channelUid = myUid;

        View btnBack = findViewById(R.id.btn_yt_channel_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Settings button in profile header
        View btnSettings = findViewById(R.id.btn_yt_profile_settings);
        if (btnSettings != null)
            btnSettings.setOnClickListener(v ->
                startActivityForResult(new Intent(this, YouTubeSettingsActivity.class), 1001));

        ivAvatar      = findViewById(R.id.iv_yt_channel_avatar);
        ivBanner      = findViewById(R.id.iv_yt_channel_banner);
        tvChannelName = findViewById(R.id.tv_yt_channel_name);
        tvHandle      = findViewById(R.id.tv_yt_channel_handle);
        tvSubs        = findViewById(R.id.tv_yt_channel_subs);
        tvBio         = findViewById(R.id.tv_yt_channel_bio);
        tvVideoCount  = findViewById(R.id.tv_yt_video_count);
        btnSubscribe  = findViewById(R.id.btn_yt_channel_subscribe);
        btnEditChannel= findViewById(R.id.btn_yt_edit_channel);
        rvVideos      = findViewById(R.id.rv_yt_channel_videos);

        // Show/hide edit vs subscribe
        boolean isMyChannel = channelUid.equals(myUid);
        if (btnEditChannel != null) btnEditChannel.setVisibility(isMyChannel ? View.VISIBLE : View.GONE);
        if (btnSubscribe   != null) btnSubscribe.setVisibility(isMyChannel ? View.GONE : View.VISIBLE);

        if (btnEditChannel != null)
            btnEditChannel.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeEditChannelActivity.class)));

        // ── 3 Profile Section Cards ─────────────────────────────────────────
        // Load avatar photo into all 3 card avatars
        String myPhotoUrl = ""; // will be loaded via loadChannel
        CircleImageView ivCardReelAvatar = findViewById(R.id.iv_card_reel_avatar);
        CircleImageView ivCardXAvatar    = findViewById(R.id.iv_card_x_avatar);
        CircleImageView ivCardChatAvatar = findViewById(R.id.iv_card_chat_avatar);

        // Card 1: Reel — opens UserReelsActivity
        View cardReel = findViewById(R.id.card_yt_profile_reel);
        if (cardReel != null) {
            cardReel.setOnClickListener(v -> {
                Intent i = new Intent(this,
                    com.callx.app.activities.UserReelsActivity.class);
                i.putExtra("uid", channelUid);
                i.putExtra("name", tvChannelName.getText() != null
                    ? tvChannelName.getText().toString() : "");
                startActivity(i);
            });
        }

        // Card 2: X — opens XProfileActivity
        View cardX = findViewById(R.id.card_yt_profile_x);
        if (cardX != null) {
            cardX.setOnClickListener(v -> {
                Intent i = new Intent(this,
                    com.callx.app.activities.XProfileActivity.class);
                i.putExtra("uid", channelUid);
                startActivity(i);
            });
        }

        // Card 3: Chat — opens MainActivity on Chats tab
        View cardChat = findViewById(R.id.card_yt_profile_chat);
        if (cardChat != null) {
            cardChat.setOnClickListener(v -> {
                Intent i = new Intent(this,
                    com.callx.app.activities.MainActivity.class);
                i.putExtra("tab", "chats");
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            });
        }

        videoAdapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvVideos.setLayoutManager(new GridLayoutManager(this, 2));
        rvVideos.setAdapter(videoAdapter);

        loadChannel();
        loadVideos();
        if (!isMyChannel) loadSubscribeState();
    }

    private void loadChannel() {
        channelListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String name  = snap.child("channelName").getValue(String.class);
                String handle= snap.child("handle").getValue(String.class);
                String photo = snap.child("photoUrl").getValue(String.class);
                String banner= snap.child("bannerUrl").getValue(String.class);
                String bio   = snap.child("bio").getValue(String.class);
                Long subs    = snap.child("subscriberCount").getValue(Long.class);
                Long vids    = snap.child("videoCount").getValue(Long.class);

                tvChannelName.setText(name != null ? name : "Channel");
                tvHandle.setText(handle != null ? "@" + handle : "");
                tvBio.setText(bio != null ? bio : "");
                subscriberCount = subs != null ? subs : 0;
                tvSubs.setText(formatCount(subscriberCount) + " subscribers");
                tvVideoCount.setText((vids != null ? vids : 0) + " videos");

                Glide.with(YouTubeChannelActivity.this).load(photo).circleCrop().into(ivAvatar);
                if (banner != null && !banner.isEmpty())
                    Glide.with(YouTubeChannelActivity.this).load(banner)
                        .centerCrop().into(ivBanner);

                // Load avatar into 3 section cards
                if (photo != null && !photo.isEmpty()) {
                    CircleImageView ivR = findViewById(R.id.iv_card_reel_avatar);
                    CircleImageView ivX = findViewById(R.id.iv_card_x_avatar);
                    CircleImageView ivC = findViewById(R.id.iv_card_chat_avatar);
                    if (ivR != null) Glide.with(YouTubeChannelActivity.this).load(photo).circleCrop().into(ivR);
                    if (ivX != null) Glide.with(YouTubeChannelActivity.this).load(photo).circleCrop().into(ivX);
                    if (ivC != null) Glide.with(YouTubeChannelActivity.this).load(photo).circleCrop().into(ivC);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.channelRef(channelUid).addValueEventListener(channelListener);
    }

    private void loadVideos() {
        videosListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<String> ids = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) ids.add(0, ds.getKey());
                fetchVideoDetails(ids);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.userVideosRef(channelUid)
            .orderByValue().limitToLast(30)
            .addValueEventListener(videosListener);
    }

    private void fetchVideoDetails(List<String> ids) {
        List<YouTubeVideo> videos = new ArrayList<>();
        if (ids.isEmpty()) { videoAdapter.setData(videos); return; }
        final int[] count = {0};
        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                        if (v != null) videos.add(v);
                        count[0]++;
                        if (count[0] == ids.size()) videoAdapter.setData(videos);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        count[0]++;
                        if (count[0] == ids.size()) videoAdapter.setData(videos);
                    }
                });
        }
    }

    private void loadSubscribeState() {
        subsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isSubscribed = snap.hasChild(channelUid);
                if (btnSubscribe != null) {
                    btnSubscribe.setText(isSubscribed ? "Subscribed" : "Subscribe");
                    btnSubscribe.setOnClickListener(v -> toggleSubscribe());
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        if (!myUid.isEmpty())
            YouTubeFirebaseUtils.subscriptionsRef(myUid).addValueEventListener(subsListener);
    }

    private void toggleSubscribe() {
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
        }
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) recreate();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (channelListener != null)
            YouTubeFirebaseUtils.channelRef(channelUid).removeEventListener(channelListener);
        if (videosListener != null)
            YouTubeFirebaseUtils.userVideosRef(channelUid).removeEventListener(videosListener);
        if (subsListener != null && !myUid.isEmpty())
            YouTubeFirebaseUtils.subscriptionsRef(myUid).removeEventListener(subsListener);
    }
}
