package com.callx.app.home;

import com.callx.app.notifications.YouTubeNotificationsActivity;
import com.callx.app.notifications.YouTubeNotificationChannelManager;
import com.callx.app.notifications.YouTubeNotificationWorker;

import com.callx.app.search.YouTubeSearchActivity;
import com.callx.app.channel.YouTubeChannelActivity;
import com.callx.app.upload.YouTubeUploadActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.bumptech.glide.Glide;
import com.callx.app.search.YouTubeExploreFragment;
import com.callx.app.home.YouTubeHomeFragment;
import com.callx.app.library.YouTubeLibraryFragment;
import com.callx.app.player.YouTubeShortsFragment;
import com.callx.app.channel.YouTubeSubscriptionsFragment;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.utils.YouTubePrefs;
import com.callx.app.youtube.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * YouTubeActivity — The root activity of the YouTube module.
 * Full YouTube-like experience: header, bottom nav (Home/Shorts/Subscriptions/Library),
 * channel management, video upload, search, settings.
 */
public class YouTubeActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;
    private TextView             tvYtUnread;
    private ValueEventListener   ytNotifBadgeListener;
    private ValueEventListener   ytProfileListener;
    private String myUid;

    private final YouTubeHomeFragment          homeFragment    = new YouTubeHomeFragment();
    private final YouTubeShortsFragment        shortsFragment  = new YouTubeShortsFragment();
    private final YouTubeSubscriptionsFragment subsFragment    = new YouTubeSubscriptionsFragment();
    private final YouTubeLibraryFragment       libraryFragment = new YouTubeLibraryFragment();
    private final YouTubeExploreFragment       exploreFragment = new YouTubeExploreFragment();

    private Fragment activeFragment = homeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Apply appearance theme from settings BEFORE setContentView
        YouTubePrefs ytPrefs = new YouTubePrefs(this);
        int themeMode = ytPrefs.getThemeMode();
        switch (themeMode) {
            case 1:
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
        setContentView(R.layout.activity_youtube);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        YouTubeNotificationChannelManager.ensureChannels(this);
        YouTubeNotificationWorker.schedule(this);

        // ── Header buttons ───────────────────────────────────────────────────
        CircleImageView ivAvatar = findViewById(R.id.iv_yt_header_avatar);
        if (ivAvatar != null) {
            ivAvatar.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeChannelActivity.class)
                    .putExtra("uid", myUid)));
            loadMyAvatar(ivAvatar);
        }

        View btnSearch = findViewById(R.id.btn_yt_search);
        if (btnSearch != null)
            btnSearch.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeSearchActivity.class)));

        View btnNotifs = findViewById(R.id.btn_yt_notifications);
        if (btnNotifs != null)
            btnNotifs.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeNotificationsActivity.class)));

        View btnGamesYt = findViewById(R.id.btn_yt_games);
        if (btnGamesYt != null)
            btnGamesYt.setOnClickListener(v -> openGamesHub());

        View btnUpload = findViewById(R.id.btn_yt_upload);
        if (btnUpload != null)
            btnUpload.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeUploadActivity.class)));

        tvYtUnread = findViewById(R.id.tv_yt_notif_badge);

        // ── Fragments ─────────────────────────────────────────────────────────
        getSupportFragmentManager().beginTransaction()
            .add(R.id.yt_fragment_container, libraryFragment, "yt_library").hide(libraryFragment)
            .add(R.id.yt_fragment_container, subsFragment,    "yt_subs").hide(subsFragment)
            .add(R.id.yt_fragment_container, shortsFragment,  "yt_shorts").hide(shortsFragment)
            .add(R.id.yt_fragment_container, exploreFragment, "yt_explore").hide(exploreFragment)
            .add(R.id.yt_fragment_container, homeFragment,    "yt_home")
            .commit();

        // ── Bottom Navigation ─────────────────────────────────────────────────
        bottomNav = findViewById(R.id.yt_bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if      (id == R.id.yt_nav_home)   switchFragment(homeFragment);
            else if (id == R.id.yt_nav_explore) switchFragment(exploreFragment);
            else if (id == R.id.yt_nav_shorts)  switchFragment(shortsFragment);
            else if (id == R.id.yt_nav_subs)    switchFragment(subsFragment);
            else if (id == R.id.yt_nav_library) switchFragment(libraryFragment);
            return true;
        });

        // Start badge listener
        startNotifBadgeListener();
    }

    private void openGamesHub() {
        try {
            Class<?> cls = Class.forName("com.callx.app.hub.GamesHubActivity");
            startActivity(new Intent(this, cls));
        } catch (ClassNotFoundException e) {
            android.widget.Toast.makeText(this, "Games coming soon!", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void switchFragment(Fragment target) {
        getSupportFragmentManager().beginTransaction()
            .hide(activeFragment)
            .show(target)
            .commit();
        activeFragment = target;
    }

    private void loadMyAvatar(CircleImageView iv) {
        if (myUid.isEmpty()) return;
        ytProfileListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String photo = snap.child("photoUrl").getValue(String.class);
                if (photo != null && !photo.isEmpty())
                    Glide.with(YouTubeActivity.this).load(photo)
                        .override(96, 96)
                        .circleCrop().into(iv);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.channelRef(myUid).addValueEventListener(ytProfileListener);
    }

    private void startNotifBadgeListener() {
        if (myUid.isEmpty()) return;
        ytNotifBadgeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                long unread = 0;
                for (DataSnapshot ds : snap.getChildren()) {
                    Boolean r = ds.child("read").getValue(Boolean.class);
                    if (r == null || !r) unread++;
                }
                updateNotifBadge(unread);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.notificationsRef(myUid)
            .addValueEventListener(ytNotifBadgeListener);
    }

    private void updateNotifBadge(long count) {
        if (tvYtUnread == null) return;
        if (count > 0) {
            tvYtUnread.setVisibility(View.VISIBLE);
            tvYtUnread.setText(count > 99 ? "99+" : String.valueOf(count));
        } else {
            tvYtUnread.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            // Settings screen se wapas aaye — theme change reflect karne ke liye recreate karo
            recreate();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (!myUid.isEmpty()) {
            if (ytNotifBadgeListener != null)
                YouTubeFirebaseUtils.notificationsRef(myUid)
                    .removeEventListener(ytNotifBadgeListener);
            if (ytProfileListener != null)
                YouTubeFirebaseUtils.channelRef(myUid)
                    .removeEventListener(ytProfileListener);
        }
    }
}
