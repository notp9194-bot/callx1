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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.ArrayList;
import java.util.List;

/**
 * Full channel page with tabs:
 * Home / Videos / Shorts / Playlists / Community
 * Subscribe + notification tier bell
 * Verified badge
 */
public class YouTubeChannelActivity extends AppCompatActivity {

    private String channelUid, myUid;
    private boolean isSubscribed = false;

    private CircleImageView ivAvatar;
    private ImageView       ivBanner, ivVerifiedBadge;
    private TextView        tvChannelName, tvHandle, tvSubs, tvBio,
                            tvVideoCount, tvTotalViews;
    private Button          btnSubscribe, btnEditChannel;
    private TabLayout       tabLayout;
    private RecyclerView    rvContent;
    private YouTubeVideoAdapter videoAdapter;

    private ValueEventListener channelListener, subsListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_channel);

        channelUid = getIntent().getStringExtra("uid");
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        if (channelUid == null) channelUid = myUid;

        View btnBack = findViewById(R.id.btn_yt_channel_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        ivAvatar       = findViewById(R.id.iv_yt_channel_avatar);
        ivBanner       = findViewById(R.id.iv_yt_channel_banner);
        ivVerifiedBadge= findViewById(R.id.iv_yt_verified_badge);
        tvChannelName  = findViewById(R.id.tv_yt_channel_name);
        tvHandle       = findViewById(R.id.tv_yt_channel_handle);
        tvSubs         = findViewById(R.id.tv_yt_channel_subs);
        tvBio          = findViewById(R.id.tv_yt_channel_bio);
        tvVideoCount   = findViewById(R.id.tv_yt_video_count);
        tvTotalViews   = findViewById(R.id.tv_yt_total_views);
        btnSubscribe   = findViewById(R.id.btn_yt_channel_subscribe);
        btnEditChannel = findViewById(R.id.btn_yt_edit_channel);
        tabLayout      = findViewById(R.id.tab_yt_channel);
        rvContent      = findViewById(R.id.rv_yt_channel_content);

        boolean isMyChannel = channelUid.equals(myUid);
        if (btnEditChannel != null) btnEditChannel.setVisibility(isMyChannel ? View.VISIBLE : View.GONE);
        if (btnSubscribe   != null) btnSubscribe.setVisibility(isMyChannel ? View.GONE : View.VISIBLE);

        if (btnEditChannel != null)
            btnEditChannel.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeEditChannelActivity.class)));
        if (btnSubscribe != null)
            btnSubscribe.setOnClickListener(v -> handleSubscribe());

        setupTabs();

        // Subscribers count tap → open subscribers list
        if (tvSubs != null)
            tvSubs.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeSubscribersActivity.class)
                    .putExtra("uid", channelUid)));

        videoAdapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvContent.setLayoutManager(new GridLayoutManager(this, 2));
        rvContent.setAdapter(videoAdapter);

        loadChannel();
        loadVideos();
        if (!isMyChannel) loadSubscribeState();
    }

    private void setupTabs() {
        if (tabLayout == null) return;
        String[] tabs = {"Home", "Videos", "Shorts", "Playlists", "Community"};
        for (String t : tabs) tabLayout.addTab(tabLayout.newTab().setText(t));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0: loadFeatured(); break;
                    case 1: loadVideos(); break;
                    case 2: loadShorts(); break;
                    case 3: openPlaylists(); break;
                    case 4: openCommunity(); break;
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void loadChannel() {
        channelListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String name    = snap.child("channelName").getValue(String.class);
                String handle  = snap.child("handle").getValue(String.class);
                String photo   = snap.child("photoUrl").getValue(String.class);
                String banner  = snap.child("bannerUrl").getValue(String.class);
                String bio     = snap.child("bio").getValue(String.class);
                Long subs      = snap.child("subscriberCount").getValue(Long.class);
                Long videos    = snap.child("videoCount").getValue(Long.class);
                Long views     = snap.child("totalViews").getValue(Long.class);
                Boolean verified = snap.child("isVerified").getValue(Boolean.class);

                if (tvChannelName != null) tvChannelName.setText(name != null ? name : "Channel");
                if (tvHandle      != null) tvHandle.setText(handle != null ? "@" + handle : "");
                if (tvBio         != null) tvBio.setText(bio != null ? bio : "");
                if (tvSubs        != null) tvSubs.setText(formatCount(subs != null ? subs : 0) + " subscribers");
                if (tvVideoCount  != null) tvVideoCount.setText((videos != null ? videos : 0) + " videos");
                if (tvTotalViews  != null) tvTotalViews.setText(formatCount(views != null ? views : 0) + " views");
                if (ivVerifiedBadge != null)
                    ivVerifiedBadge.setVisibility(Boolean.TRUE.equals(verified) ? View.VISIBLE : View.GONE);
                if (photo != null)
                    Glide.with(YouTubeChannelActivity.this).load(photo).circleCrop().into(ivAvatar);
                if (banner != null && ivBanner != null)
                    Glide.with(YouTubeChannelActivity.this).load(banner).centerCrop().into(ivBanner);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.channelRef(channelUid).addValueEventListener(channelListener);
    }

    private void loadVideos() {
        rvContent.setLayoutManager(new GridLayoutManager(this, 2));
        YouTubeFirebaseUtils.userVideosRef(channelUid)
            .orderByValue().limitToLast(30)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) ids.add(0, ds.getKey());
                    fetchVideoList(ids, false);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadShorts() {
        YouTubeFirebaseUtils.userShortsRef(channelUid)
            .orderByValue().limitToLast(30)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) ids.add(0, ds.getKey());
                    fetchVideoList(ids, true);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadFeatured() {
        loadVideos();
    }

    private void openPlaylists() {
        startActivity(new Intent(this, YouTubePlaylistActivity.class)
            .putExtra("uid", channelUid));
    }

    private void openCommunity() {
        startActivity(new Intent(this, YouTubeCommunityActivity.class)
            .putExtra("uid", channelUid));
    }

    private void fetchVideoList(List<String> ids, boolean isShort) {
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
                        if (count[0] == ids.size()) {
                            videos.sort((a, b) -> Long.compare(b.uploadedAt, a.uploadedAt));
                            videoAdapter.setData(videos);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        count[0]++;
                        if (count[0] == ids.size()) videoAdapter.setData(videos);
                    }
                });
        }
    }

    private void loadSubscribeState() {
        if (myUid.isEmpty()) return;
        subsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isSubscribed = snap.hasChild(channelUid);
                if (btnSubscribe != null) {
                    btnSubscribe.setSelected(isSubscribed);
                    btnSubscribe.setText(isSubscribed ? "Subscribed" : "Subscribe");
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.subscriptionsRef(myUid).addValueEventListener(subsListener);
    }

    private void handleSubscribe() {
        if (isSubscribed) {
            String[] opts = {"All notifications", "Personalised", "None", "Unsubscribe"};
            new android.app.AlertDialog.Builder(this)
                .setTitle("Notifications from this channel")
                .setItems(opts, (dlg, which) -> {
                    if (which == 3) toggleSubscribe();
                    else {
                        String[] tiers = {"all", "personalized", "none"};
                        YouTubeFirebaseUtils.notifTierRef(myUid, channelUid).setValue(tiers[which]);
                    }
                }).show();
        } else {
            toggleSubscribe();
        }
    }

    private void toggleSubscribe() {
        if (myUid.isEmpty()) return;
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
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (channelListener != null)
            YouTubeFirebaseUtils.channelRef(channelUid).removeEventListener(channelListener);
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
