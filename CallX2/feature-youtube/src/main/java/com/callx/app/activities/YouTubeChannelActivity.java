package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeChannel;
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
 * Full Channel Page — 4 tabs: Videos | Shorts | Playlists | About
 */
public class YouTubeChannelActivity extends AppCompatActivity {

    private String channelUid, myUid;
    private boolean isSubscribed = false;

    // Header views
    private ImageView        ivBanner;
    private CircleImageView  ivAvatar;
    private TextView         tvName, tvHandle, tvSubs, tvVideoCount, tvAboutBio;
    private Button           btnSubscribe;
    private View             btnEdit;

    // Tab content
    private RecyclerView     rvVideos, rvShorts, rvPlaylists;
    private YouTubeVideoAdapter videosAdapter, shortsAdapter;
    private LinearLayout     llAbout, llPlaylistsList;

    // Active tab state
    private static final int TAB_VIDEOS = 0, TAB_SHORTS = 1, TAB_PLAYLISTS = 2, TAB_ABOUT = 3;
    private int currentTab = TAB_VIDEOS;

    private View[] tabContents = new View[4];
    private View[] tabButtons  = new View[4];

    private ValueEventListener subListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_channel);

        channelUid = getIntent().getStringExtra("uid");
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        if (channelUid == null) { finish(); return; }

        View btnBack = findViewById(R.id.btn_yt_channel_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Header
        ivBanner    = findViewById(R.id.iv_yt_channel_banner);
        ivAvatar    = findViewById(R.id.iv_yt_channel_avatar);
        tvName      = findViewById(R.id.tv_yt_channel_name);
        tvHandle    = findViewById(R.id.tv_yt_channel_handle);
        tvSubs      = findViewById(R.id.tv_yt_channel_subs);
        tvVideoCount= findViewById(R.id.tv_yt_video_count);
        btnSubscribe= findViewById(R.id.btn_yt_channel_subscribe);
        btnEdit     = findViewById(R.id.btn_yt_edit_channel);

        if (btnEdit != null) {
            btnEdit.setVisibility(myUid.equals(channelUid) ? View.VISIBLE : View.GONE);
            btnEdit.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeEditChannelActivity.class)));
        }
        if (btnSubscribe != null) btnSubscribe.setOnClickListener(v -> toggleSubscribe());

        // Tab buttons
        tabButtons[TAB_VIDEOS]    = findViewById(R.id.tab_yt_videos);
        tabButtons[TAB_SHORTS]    = findViewById(R.id.tab_yt_shorts);
        tabButtons[TAB_PLAYLISTS] = findViewById(R.id.tab_yt_playlists);
        tabButtons[TAB_ABOUT]     = findViewById(R.id.tab_yt_about);

        // Tab content containers
        tabContents[TAB_VIDEOS]    = findViewById(R.id.v_yt_tab_videos);
        tabContents[TAB_SHORTS]    = findViewById(R.id.v_yt_tab_shorts);
        tabContents[TAB_PLAYLISTS] = findViewById(R.id.v_yt_tab_playlists);
        tabContents[TAB_ABOUT]     = findViewById(R.id.v_yt_tab_about);

        llAbout       = findViewById(R.id.ll_yt_channel_about);
        tvAboutBio    = findViewById(R.id.tv_yt_channel_bio);
        llPlaylistsList = findViewById(R.id.ll_yt_channel_playlists);

        // RecyclerViews
        rvVideos = findViewById(R.id.rv_yt_channel_videos);
        rvShorts = findViewById(R.id.rv_yt_channel_shorts);

        videosAdapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        shortsAdapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));

        if (rvVideos != null) {
            rvVideos.setLayoutManager(new GridLayoutManager(this, 2));
            rvVideos.setAdapter(videosAdapter);
        }
        if (rvShorts != null) {
            rvShorts.setLayoutManager(new GridLayoutManager(this, 3));
            rvShorts.setAdapter(shortsAdapter);
        }

        for (int i = 0; i < 4; i++) {
            final int tab = i;
            if (tabButtons[i] != null)
                tabButtons[i].setOnClickListener(v -> switchTab(tab));
        }

        switchTab(TAB_VIDEOS);
        loadChannelData();
        loadSubscribeState();
    }

    private void switchTab(int tab) {
        currentTab = tab;
        for (int i = 0; i < 4; i++) {
            if (tabContents[i] != null)
                tabContents[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
            if (tabButtons[i] != null)
                tabButtons[i].setSelected(i == tab);
        }
        switch (tab) {
            case TAB_VIDEOS:    loadChannelVideos(); break;
            case TAB_SHORTS:    loadChannelShorts(); break;
            case TAB_PLAYLISTS: loadChannelPlaylists(); break;
            case TAB_ABOUT:     /* bio already loaded */ break;
        }
    }

    private void loadChannelData() {
        YouTubeFirebaseUtils.channelRef(channelUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    YouTubeChannel ch = snap.getValue(YouTubeChannel.class);
                    if (ch == null) return;
                    if (tvName   != null) tvName.setText(ch.channelName);
                    if (tvHandle != null) tvHandle.setText("@" + (ch.handle != null ? ch.handle : ""));
                    if (tvSubs   != null) tvSubs.setText(fmt(ch.subscriberCount) + " subscribers");
                    if (tvVideoCount != null) tvVideoCount.setText(ch.videoCount + " videos");
                    if (tvAboutBio   != null) tvAboutBio.setText(ch.bio != null ? ch.bio : "");

                    if (ivBanner != null && ch.bannerUrl != null)
                        Glide.with(YouTubeChannelActivity.this).load(ch.bannerUrl)
                            .centerCrop().into(ivBanner);
                    if (ivAvatar != null)
                        Glide.with(YouTubeChannelActivity.this).load(ch.photoUrl)
                            .circleCrop().placeholder(R.drawable.ic_person).into(ivAvatar);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadChannelVideos() {
        if (rvVideos == null) return;
        YouTubeFirebaseUtils.userVideosRef(channelUid)
            .orderByValue().limitToLast(24)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) ids.add(0, ds.getKey());
                    fetchAndBind(ids, videosAdapter, false);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadChannelShorts() {
        if (rvShorts == null) return;
        YouTubeFirebaseUtils.userShortsRef(channelUid)
            .orderByValue().limitToLast(18)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) ids.add(0, ds.getKey());
                    fetchAndBind(ids, shortsAdapter, true);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadChannelPlaylists() {
        if (llPlaylistsList == null) return;
        llPlaylistsList.removeAllViews();
        YouTubeFirebaseUtils.playlistsRef(channelUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!snap.hasChildren()) {
                        TextView tv = new TextView(YouTubeChannelActivity.this);
                        tv.setText("No public playlists.");
                        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
                        llPlaylistsList.addView(tv);
                        return;
                    }
                    for (DataSnapshot ds : snap.getChildren()) {
                        String pid     = ds.getKey();
                        String title   = ds.child("title").getValue(String.class);
                        String privacy = ds.child("privacy").getValue(String.class);
                        if (!"public".equals(privacy) && !myUid.equals(channelUid)) continue;
                        Long count = ds.child("videoCount").getValue(Long.class);

                        View row = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, llPlaylistsList, false);
                        TextView t1 = row.findViewById(android.R.id.text1);
                        TextView t2 = row.findViewById(android.R.id.text2);
                        if (t1 != null) t1.setText(title != null ? title : "Untitled");
                        if (t2 != null) t2.setText((count != null ? count : 0) + " videos");
                        final String playlistId = pid;
                        row.setOnClickListener(v -> startActivity(
                            new Intent(YouTubeChannelActivity.this, YouTubePlaylistActivity.class)
                                .putExtra("owner_uid", channelUid)
                                .putExtra("playlist_id", playlistId)));
                        llPlaylistsList.addView(row);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void fetchAndBind(List<String> ids, YouTubeVideoAdapter adp, boolean shortsOnly) {
        List<YouTubeVideo> videos = new ArrayList<>();
        if (ids.isEmpty()) { adp.setData(videos); return; }
        final int[] count = {0};
        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                    if (v != null && (myUid.equals(channelUid) || "public".equals(v.visibility)))
                        videos.add(v);
                    count[0]++;
                    if (count[0] == ids.size()) adp.setData(videos);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    count[0]++;
                    if (count[0] == ids.size()) adp.setData(videos);
                }
            });
        }
    }

    // ── Subscribe ─────────────────────────────────────────────────────────────

    private void loadSubscribeState() {
        if (myUid.isEmpty() || myUid.equals(channelUid)) {
            if (btnSubscribe != null) btnSubscribe.setVisibility(View.GONE);
            return;
        }
        subListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isSubscribed = snap.hasChild(channelUid);
                updateSubBtn();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.subscriptionsRef(myUid).addValueEventListener(subListener);
    }

    private void toggleSubscribe() {
        if (myUid.isEmpty()) { Toast.makeText(this,"Sign in first",Toast.LENGTH_SHORT).show(); return; }
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
            sendSubscribeNotif();
        }
        isSubscribed = !isSubscribed;
        updateSubBtn();
    }

    private void updateSubBtn() {
        if (btnSubscribe != null) btnSubscribe.setText(isSubscribed ? "Subscribed" : "Subscribe");
    }

    private void sendSubscribeNotif() {
        YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String myName  = snap.child("channelName").getValue(String.class);
                    String myPhoto = snap.child("photoUrl").getValue(String.class);
                    String nKey = YouTubeFirebaseUtils.notificationsRef(channelUid).push().getKey();
                    if (nKey == null) return;
                    YouTubeNotification n = new YouTubeNotification(nKey, channelUid,
                        myUid, myName, myPhoto, "subscribe", null, null, null);
                    YouTubeFirebaseUtils.notificationsRef(channelUid).child(nKey).setValue(n);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (subListener != null && !myUid.isEmpty())
            YouTubeFirebaseUtils.subscriptionsRef(myUid).removeEventListener(subListener);
    }

    private String fmt(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density);
    }
}
