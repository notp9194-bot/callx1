package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.bumptech.glide.Glide;
import com.callx.app.fragments.XExploreFragment;
import com.callx.app.fragments.XHomeFragment;
import com.callx.app.fragments.XMessagesFragment;
import com.callx.app.fragments.XNotificationsFragment;
import com.callx.app.notifications.XNotificationChannelManager;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

public class XActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;
    private View vNotifDot;
    private ValueEventListener xNotifBadgeListener;
    private ValueEventListener xDmBadgeListener;
    private ValueEventListener xProfileListener;
    private String myUid;

    private final XHomeFragment          homeFragment          = new XHomeFragment();
    private final XExploreFragment       exploreFragment       = new XExploreFragment();
    private final XNotificationsFragment notificationsFragment = new XNotificationsFragment();
    private final XMessagesFragment      messagesFragment      = new XMessagesFragment();

    private Fragment activeFragment = homeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        XNotificationChannelManager.ensureChannels(this);

        // Profile avatar in header — tap opens own profile
        CircleImageView ivMyAvatar = findViewById(R.id.iv_x_header_avatar);
        if (ivMyAvatar != null) {
            ivMyAvatar.setOnClickListener(v ->
                startActivity(new Intent(this, XProfileActivity.class).putExtra("uid", myUid)));
            loadMyAvatar(ivMyAvatar);
        }

        // Back to CallX (fallback if avatar not in layout)
        View btnBack = findViewById(R.id.btn_x_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Compose button in header
        View btnCompose = findViewById(R.id.btn_x_header_compose);
        if (btnCompose != null)
            btnCompose.setOnClickListener(v ->
                startActivity(new Intent(this, XComposeActivity.class)));

        // Add all fragments
        getSupportFragmentManager().beginTransaction()
            .add(R.id.x_fragment_container, messagesFragment,      "x_msg").hide(messagesFragment)
            .add(R.id.x_fragment_container, notificationsFragment, "x_notif").hide(notificationsFragment)
            .add(R.id.x_fragment_container, exploreFragment,       "x_explore").hide(exploreFragment)
            .add(R.id.x_fragment_container, homeFragment,          "x_home")
            .commit();

        bottomNav  = findViewById(R.id.x_bottom_nav);
        vNotifDot  = findViewById(R.id.v_x_notif_dot);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment target = homeFragment;
            if      (id == R.id.x_nav_home)    target = homeFragment;
            else if (id == R.id.x_nav_explore) target = exploreFragment;
            else if (id == R.id.x_nav_notif)  { target = notificationsFragment; clearNotifBadge(); }
            else if (id == R.id.x_nav_dm)     { target = messagesFragment;      clearDmBadge(); }
            switchFragment(target);
            return true;
        });

        startBadgeListeners();
    }

    private void loadMyAvatar(CircleImageView iv) {
        if (myUid.isEmpty()) return;
        xProfileListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String photoUrl = snap.child("photoUrl").getValue(String.class);
                if (photoUrl != null && !photoUrl.isEmpty()) {
                    Glide.with(XActivity.this).load(photoUrl).circleCrop()
                        .placeholder(R.drawable.ic_person).into(iv);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        XFirebaseUtils.xUserRef(myUid).addValueEventListener(xProfileListener);
    }

    private void switchFragment(Fragment target) {
        if (target == activeFragment) return;
        getSupportFragmentManager().beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .hide(activeFragment).show(target)
            .commit();
        activeFragment = target;
    }

    private void startBadgeListeners() {
        if (myUid.isEmpty()) return;

        // ── Notifications badge ──────────────────────────────────────────────
        xNotifBadgeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Long count = snap.getValue(Long.class);
                if (count != null && count > 0) {
                    BadgeDrawable badge = bottomNav.getOrCreateBadge(R.id.x_nav_notif);
                    badge.setBackgroundColor(getColor(R.color.x_badge_notif));
                    badge.setBadgeTextColor(getColor(R.color.x_badge_text));
                    badge.setVisible(true);
                    // Cap at 99; Material BadgeDrawable shows "99+" automatically above 99
                    badge.setNumber((int) Math.min(count, 99));
                    if (vNotifDot != null) vNotifDot.setVisibility(android.view.View.VISIBLE);
                } else {
                    bottomNav.removeBadge(R.id.x_nav_notif);
                    if (vNotifDot != null) vNotifDot.setVisibility(android.view.View.GONE);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        XFirebaseUtils.xUnreadNotifCountRef(myUid)
            .addValueEventListener(xNotifBadgeListener);

        // ── DM badge — FIX: check "unread" field (not "seen") ───────────────
        xDmBadgeListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                int unread = 0;
                for (DataSnapshot ds : snap.getChildren()) {
                    // FIX: field is "unread" (boolean true) not "seen"
                    Boolean isUnread = ds.child("unread").getValue(Boolean.class);
                    String lastSender = ds.child("lastSenderUid").getValue(String.class);
                    // Only count as unread if the last message was FROM the other person
                    if (Boolean.TRUE.equals(isUnread) && !myUid.equals(lastSender)) unread++;
                }
                if (unread > 0) {
                    BadgeDrawable badge = bottomNav.getOrCreateBadge(R.id.x_nav_dm);
                    badge.setBackgroundColor(getColor(R.color.x_badge_dm));
                    badge.setBadgeTextColor(getColor(R.color.x_badge_text));
                    badge.setVisible(true);
                    badge.setNumber(Math.min(unread, 99));
                } else {
                    bottomNav.removeBadge(R.id.x_nav_dm);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        XFirebaseUtils.xDmConversationsRef(myUid)
            .addValueEventListener(xDmBadgeListener);
    }

    private void clearNotifBadge() {
        bottomNav.removeBadge(R.id.x_nav_notif);
        if (vNotifDot != null) vNotifDot.setVisibility(android.view.View.GONE);
        // Firebase reset is handled inside XNotificationsFragment.load()
        // but also reset here as a safety net
        if (!myUid.isEmpty())
            XFirebaseUtils.xUnreadNotifCountRef(myUid).setValue(0);
    }

    private void clearDmBadge() {
        bottomNav.removeBadge(R.id.x_nav_dm);
        // FIX: actually reset "unread" to false for all conversations in Firebase
        if (myUid.isEmpty()) return;
        XFirebaseUtils.xDmConversationsRef(myUid).get().addOnSuccessListener(snap -> {
            for (DataSnapshot ds : snap.getChildren()) {
                Boolean isUnread = ds.child("unread").getValue(Boolean.class);
                String lastSender = ds.child("lastSenderUid").getValue(String.class);
                if (Boolean.TRUE.equals(isUnread) && !myUid.equals(lastSender)) {
                    ds.getRef().child("unread").setValue(false);
                }
            }
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (myUid.isEmpty()) return;
        if (xNotifBadgeListener != null)
            XFirebaseUtils.xUnreadNotifCountRef(myUid).removeEventListener(xNotifBadgeListener);
        if (xDmBadgeListener != null)
            XFirebaseUtils.xDmConversationsRef(myUid).removeEventListener(xDmBadgeListener);
        if (xProfileListener != null)
            XFirebaseUtils.xUserRef(myUid).removeEventListener(xProfileListener);
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, android.R.anim.slide_out_right);
    }
}
