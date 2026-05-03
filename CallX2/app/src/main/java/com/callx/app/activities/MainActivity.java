package com.callx.app.activities;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.viewpager2.widget.ViewPager2;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.adapters.ViewPagerAdapter;
import com.callx.app.databinding.ActivityMainBinding;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.badge.BadgeDrawable;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    // Firebase listeners — kept to detach in onDestroy
    private ValueEventListener unreadChatsListener;
    private ValueEventListener missedCallsListener;
    private ValueEventListener unseenStatusListener;
    private ValueEventListener unreadGroupsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish(); return;
        }

        requestPermissions();

        setSupportActionBar(binding.toolbar);

        binding.btnSearchToolbar.setOnClickListener(v ->
            startActivity(new Intent(this, SearchActivity.class)));

        binding.ivAvatarMenu.setOnClickListener(v ->
            startActivity(new Intent(this, AccountMenuActivity.class)));

        binding.viewPager.setAdapter(new ViewPagerAdapter(this));
        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                int[] ids = {R.id.nav_chats, R.id.nav_status, R.id.nav_groups, R.id.nav_calls};
                if (position >= 0 && position < ids.length)
                    binding.bottomNav.setSelectedItemId(ids[position]);
                updateFab(position);
            }
        });

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if      (id == R.id.nav_chats)  { binding.viewPager.setCurrentItem(0); }
            else if (id == R.id.nav_status) { binding.viewPager.setCurrentItem(1); clearBadge(R.id.nav_status); }
            else if (id == R.id.nav_groups) { binding.viewPager.setCurrentItem(2); clearBadge(R.id.nav_groups); }
            else if (id == R.id.nav_calls)  {
                binding.viewPager.setCurrentItem(3);
                // Mark missed calls as seen
                getSharedPreferences("callx_prefs", MODE_PRIVATE).edit()
                    .putLong("last_seen_calls_ts", System.currentTimeMillis()).apply();
                clearBadge(R.id.nav_calls);
            }
            return true;
        });

        binding.fabAction.setOnClickListener(v -> {
            int pos = binding.viewPager.getCurrentItem();
            if      (pos == 0) startActivity(new Intent(this, SearchActivity.class));
            else if (pos == 1) startActivity(new Intent(this, NewStatusActivity.class));
            else if (pos == 2) startActivity(new Intent(this, NewGroupActivity.class));
            else               startActivity(new Intent(this, SearchActivity.class));
        });

        loadMyAvatar();
        refreshFcmToken();
        startBadgeListeners();
    }

    @Override protected void onResume() {
        super.onResume();
        loadMyAvatar();
    }

    @Override protected void onDestroy() {
        String uid = currentUid();
        if (uid != null) {
            if (unreadChatsListener  != null) FirebaseUtils.getContactsRef(uid).removeEventListener(unreadChatsListener);
            if (missedCallsListener  != null) FirebaseUtils.getCallsRef(uid).removeEventListener(missedCallsListener);
            if (unseenStatusListener != null) FirebaseUtils.getStatusRef().child(uid).removeEventListener(unseenStatusListener);
            if (unreadGroupsListener != null) FirebaseUtils.getUserGroupsRef(uid).removeEventListener(unreadGroupsListener);
        }
        super.onDestroy();
    }

    private String currentUid() {
        return FirebaseAuth.getInstance().getCurrentUser() == null
            ? null : FirebaseAuth.getInstance().getCurrentUser().getUid();
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !android.provider.Settings.canDrawOverlays(this)) {
            try { startActivity(new Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try { startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            }
        }
    }

    private void updateFab(int position) {
        switch (position) {
            case 0: binding.fabAction.setImageResource(R.drawable.ic_status_add); break;
            case 1: binding.fabAction.setImageResource(R.drawable.ic_camera);     break;
            case 2: binding.fabAction.setImageResource(R.drawable.ic_group);      break;
            case 3: binding.fabAction.setImageResource(R.drawable.ic_phone);      break;
        }
    }

    private void loadMyAvatar() {
        String uid = currentUid();
        if (uid == null) return;
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String photo = snap.child("photoUrl").getValue(String.class);
                if (photo != null && !photo.isEmpty())
                    Glide.with(MainActivity.this).load(photo)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person).error(R.drawable.ic_person)
                        .into(binding.ivAvatarMenu);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    // ── Badge System ────────────────────────────────────────────────────────
    private void startBadgeListeners() {
        String uid = currentUid();
        if (uid == null) return;

        // 1. Unread chats → nav_chats badge
        unreadChatsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                long total = 0;
                for (DataSnapshot c : snap.getChildren()) {
                    Long u = c.child("unread").getValue(Long.class);
                    if (u != null) total += u;
                }
                setBadge(R.id.nav_chats, (int) total);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getContactsRef(uid).addValueEventListener(unreadChatsListener);

        // 2. Missed calls → nav_calls badge
        missedCallsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                long seenTs = getSharedPreferences("callx_prefs", MODE_PRIVATE)
                    .getLong("last_seen_calls_ts", 0L);
                int missed = 0;
                for (DataSnapshot c : snap.getChildren()) {
                    String dir = c.child("direction").getValue(String.class);
                    Long ts    = c.child("timestamp").getValue(Long.class);
                    if ("missed".equals(dir) && ts != null && ts > seenTs) missed++;
                }
                setBadge(R.id.nav_calls, missed);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getCallsRef(uid).addValueEventListener(missedCallsListener);

        // 3. Unseen statuses → nav_status badge
        unseenStatusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                // Count contacts that have statuses the user hasn't seen
                // Simple approach: count total status items with timestamp > lastStatusSeen
                long seenTs = getSharedPreferences("callx_prefs", MODE_PRIVATE)
                    .getLong("last_seen_status_ts", 0L);
                int count = 0;
                for (DataSnapshot ownerSnap : snap.getChildren()) {
                    for (DataSnapshot statusItem : ownerSnap.getChildren()) {
                        Long ts = statusItem.child("timestamp").getValue(Long.class);
                        if (ts != null && ts > seenTs) count++;
                    }
                }
                // Don't badge own status
                setBadge(R.id.nav_status, count);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getStatusRef().addValueEventListener(unseenStatusListener);

        // 4. Unread group messages → nav_groups badge
        unreadGroupsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                // Count groups that have unread messages
                int unread = 0;
                for (DataSnapshot g : snap.getChildren()) {
                    Long u = g.child("unread").getValue(Long.class);
                    if (u != null && u > 0) unread += u;
                }
                setBadge(R.id.nav_groups, unread);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getUserGroupsRef(uid).addValueEventListener(unreadGroupsListener);
    }

    private void setBadge(int navItemId, int count) {
        if (count > 0) {
            BadgeDrawable badge = binding.bottomNav.getOrCreateBadge(navItemId);
            badge.setVisible(true);
            badge.setNumber(count);
            badge.setBackgroundColor(0xFFEF4444); // red badge
            badge.setBadgeTextColor(0xFFFFFFFF);
        } else {
            clearBadge(navItemId);
        }
    }

    private void clearBadge(int navItemId) {
        binding.bottomNav.removeBadge(navItemId);
    }

    private void refreshFcmToken() {
        String uid = currentUid();
        if (uid == null) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (token == null) return;
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("users").child(uid)
                .child("fcmToken").setValue(token);
        });
    }
}
