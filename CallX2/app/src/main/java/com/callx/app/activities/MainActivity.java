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
    private ValueEventListener chatsListener, callsListener, statusListener, groupsListener;

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
        binding.viewPager.setUserInputEnabled(true);
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
            if      (id == R.id.nav_chats)  binding.viewPager.setCurrentItem(0);
            else if (id == R.id.nav_status) { binding.viewPager.setCurrentItem(1); clearBadge(R.id.nav_status); }
            else if (id == R.id.nav_groups) { binding.viewPager.setCurrentItem(2); clearBadge(R.id.nav_groups); }
            else if (id == R.id.nav_calls)  {
                binding.viewPager.setCurrentItem(3);
                getSharedPreferences("cx_prefs", MODE_PRIVATE).edit()
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

    @Override protected void onResume() { super.onResume(); loadMyAvatar(); }

    @Override protected void onDestroy() {
        String uid = uid();
        if (uid != null) {
            if (chatsListener  != null) FirebaseUtils.getContactsRef(uid).removeEventListener(chatsListener);
            if (callsListener  != null) FirebaseUtils.getCallsRef(uid).removeEventListener(callsListener);
            if (statusListener != null) FirebaseUtils.getStatusRef().removeEventListener(statusListener);
            if (groupsListener != null) FirebaseUtils.getUserGroupsRef(uid).removeEventListener(groupsListener);
        }
        super.onDestroy();
    }

    private String uid() {
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
            try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try { startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
            }
        }
    }

    private void updateFab(int p) {
        switch (p) {
            case 0: binding.fabAction.setImageResource(R.drawable.ic_status_add); break;
            case 1: binding.fabAction.setImageResource(R.drawable.ic_camera);     break;
            case 2: binding.fabAction.setImageResource(R.drawable.ic_group);      break;
            case 3: binding.fabAction.setImageResource(R.drawable.ic_phone);      break;
        }
    }

    private void loadMyAvatar() {
        String uid = uid(); if (uid == null) return;
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

    // ── 4-tab badge system ─────────────────────────────────────────────────
    private void startBadgeListeners() {
        String uid = uid(); if (uid == null) return;

        // 1. Chats — unread count
        chatsListener = new ValueEventListener() {
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
        FirebaseUtils.getContactsRef(uid).addValueEventListener(chatsListener);

        // 2. Calls — missed calls since last seen
        callsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                long seenTs = getSharedPreferences("cx_prefs", MODE_PRIVATE)
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
        FirebaseUtils.getCallsRef(uid).addValueEventListener(callsListener);

        // 3. Status — unseen status items from contacts
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                long seenTs = getSharedPreferences("cx_prefs", MODE_PRIVATE)
                    .getLong("last_seen_status_ts", 0L);
                int count = 0;
                for (DataSnapshot ownerSnap : snap.getChildren()) {
                    if (uid.equals(ownerSnap.getKey())) continue; // skip own
                    for (DataSnapshot item : ownerSnap.getChildren()) {
                        Long ts = item.child("timestamp").getValue(Long.class);
                        if (ts != null && ts > seenTs) count++;
                    }
                }
                setBadge(R.id.nav_status, count);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getStatusRef().addValueEventListener(statusListener);

        // 4. Groups — unread group messages
        groupsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                int total = 0;
                for (DataSnapshot g : snap.getChildren()) {
                    Long u = g.child("unread").getValue(Long.class);
                    if (u != null) total += u;
                }
                setBadge(R.id.nav_groups, total);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getUserGroupsRef(uid).addValueEventListener(groupsListener);
    }

    private void setBadge(int navId, int count) {
        if (count > 0) {
            BadgeDrawable badge = binding.bottomNav.getOrCreateBadge(navId);
            badge.setVisible(true);
            badge.setNumber(count);
            badge.setBackgroundColor(0xFFEF4444);
            badge.setBadgeTextColor(0xFFFFFFFF);
        } else {
            clearBadge(navId);
        }
    }

    private void clearBadge(int navId) { binding.bottomNav.removeBadge(navId); }

    private void refreshFcmToken() {
        String uid = uid(); if (uid == null) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            if (token == null) return;
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("users").child(uid).child("fcmToken").setValue(token);
        });
    }
}
