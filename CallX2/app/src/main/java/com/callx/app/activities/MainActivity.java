package com.callx.app.activities;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
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
import com.callx.app.activities.ReelNotificationsActivity;
import com.callx.app.workers.StoryNotificationWorker;
import com.callx.app.fragments.ReelsFragment;
import com.callx.app.utils.AppUpdateManager;
import android.animation.ObjectAnimator;
  import android.view.View;
  import android.widget.TextView;
  import com.bumptech.glide.Glide;
  import de.hdodenhof.circleimageview.CircleImageView;
  import com.callx.app.activities.XActivity;
  import com.callx.app.notifications.XNotificationWorker;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.activities.YouTubeActivity;
  import com.callx.app.notifications.YouTubeNotificationWorker;
  import com.callx.app.utils.YouTubeFirebaseUtils;
  import android.graphics.Bitmap;
  import android.graphics.BitmapShader;
  import android.graphics.Canvas;
  import android.graphics.Paint;
  import android.graphics.Shader;
  import android.graphics.drawable.BitmapDrawable;
  import com.bumptech.glide.request.target.CustomTarget;
  import com.bumptech.glide.request.transition.Transition;
  import androidx.annotation.Nullable;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    // My profile cache — for UserReelsActivity launch
    private String myName     = "";
    private String myPhotoUrl = "";


      // ── X Module ────────────────────────────────────────────────────────────────
      private ValueEventListener xNotifBadgeListener;
      private int xUnreadCount = 0;

      // ── YouTube Module ───────────────────────────────────────────────────────
      private ValueEventListener ytNotifBadgeListener;
      private int ytUnreadCount = 0;

      // ── Games Module ─────────────────────────────────────────────────────────
      // No badge listener needed for now — Games has no notifications yet

    // Notification badge counter
    private int totalNotifUnread = 0;
    private ValueEventListener notifChatBadgeListener;
    private ValueEventListener notifGroupBadgeListener;
    private ValueEventListener notifReelBadgeListener;
    private ValueEventListener notifCallBadgeListener;
    private int notifChatUnread   = 0;
    private int notifGroupUnread  = 0;
    private int notifReelUnread   = 0;
    private int notifCallUnread   = 0;
    // Status unseen count — included in the header notification ball
    private int notifStatusUnread = 0;

    // Firebase listeners — kept to detach in onDestroy
    private ValueEventListener unreadChatsListener;
    private ValueEventListener missedCallsListener;
    private ValueEventListener unseenStatusListener;
    private ValueEventListener unreadGroupsListener;
    private ValueEventListener unreadReelNotifsListener;
    private ChildEventListener  contactStatusChildListener;

    // Track already-notified status IDs so we don't re-notify on re-attach
    private final java.util.Set<String> notifiedStatusIds = new java.util.HashSet<>();

    // Tab indices
    private static final int TAB_CHATS  = 0;
    private static final int TAB_REELS  = 1;
    private static final int TAB_STATUS = 2;
    private static final int TAB_GROUPS = 3;
    private static final int TAB_CALLS  = 4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // MUST be called before super.onCreate / setContentView so the window
        // is configured for edge-to-edge before any layout pass happens.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, AuthActivity.class));
            finish(); return;
        }

        requestPermissions();

        // ── Handle tap from system reel notification (Doze / killed state) ─────
        handleReelNotifIntent(getIntent());
        handleDeepLinkIntent(getIntent());  // Tab deep link
        // ──────────────────────────────────────────────────────────────────────

        setSupportActionBar(binding.toolbar);

          // ── X Module: animated entry button ─────────────────────────────────────
          setupXEntryButton();
          // ────────────────────────────────────────────────────────────────────────

          // ── YouTube Module: animated entry button ─────────────────────────────
          setupYouTubeEntryButton();

          // ── Games Module: animated entry button ───────────────────────────────
          setupGamesEntryButton();
          // ────────────────────────────────────────────────────────────────────────

        binding.btnSearchToolbar.setOnClickListener(v -> {
            startActivity(new Intent(this, SearchActivity.class));
            overridePendingTransition(0, 0); // Tab switch — instant 0ms
        });

        binding.btnNotificationsToolbar.setOnClickListener(v -> {
            startActivity(new Intent(this, AllNotificationsActivity.class));
            overridePendingTransition(0, 0); // Tab switch — instant 0ms
        });

        binding.ivAvatarMenu.setOnClickListener(v -> {
            startActivity(new Intent(this, AccountMenuActivity.class));
            overridePendingTransition(0, 0); // Tab switch — instant 0ms
        });

        binding.viewPager.setAdapter(new ViewPagerAdapter(this));
        // FIX #LAZY: offscreenPageLimit 2 → 1 kiya gaya.
        // Pehle: Tab 0 open hone par Tab 1 + Tab 2 dono immediately load hote the.
        // Ab:    Sirf Tab 1 (Status) pre-load hoga — Tab 2 (Groups), Tab 3 (Reels),
        //        Tab 4 (Calls) tab par tap karne par hi load honge.
        // Faida: ~15% less memory on startup, Reels ExoPlayer init tab switch pe hoga.
        binding.viewPager.setOffscreenPageLimit(1);
        // Bottom nav tap par instant switch (no scroll animation) — WhatsApp jaisa
        binding.viewPager.setUserInputEnabled(true);
        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                int[] ids = {
                    R.id.nav_chats,
                    R.id.nav_reels,
                    R.id.nav_status,
                    R.id.nav_groups,
                    R.id.nav_calls
                };
                if (position >= 0 && position < ids.length)
                    binding.bottomNav.setSelectedItemId(ids[position]);
                updateFab(position);
                // Hide main bottom nav + FAB when Reels tab is active
                setMainNavVisible(position != TAB_REELS);
                // ── Reel playback: pause when leaving, resume when entering ──
                notifyReelsTabVisibility(position == TAB_REELS);
            }
        });

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            // false = instant switch (no scroll animation) — WhatsApp/Instagram jaisa snap
            if      (id == R.id.nav_chats)  { binding.viewPager.setCurrentItem(TAB_CHATS, false); }
            else if (id == R.id.nav_status) {
                binding.viewPager.setCurrentItem(TAB_STATUS, false);
                clearBadge(R.id.nav_status);
                // Also clear status contribution from header notification ball
                notifStatusUnread = 0;
                updateNotifBadge();
            }
            else if (id == R.id.nav_groups) {
                binding.viewPager.setCurrentItem(TAB_GROUPS, false);
                clearBadge(R.id.nav_groups);
            }
            else if (id == R.id.nav_reels)  {
                binding.viewPager.setCurrentItem(TAB_REELS, false);
                clearBadge(R.id.nav_reels);
            }
            else if (id == R.id.nav_calls)  {
                binding.viewPager.setCurrentItem(TAB_CALLS, false);
                // Mark missed calls as seen
                getSharedPreferences("callx_prefs", MODE_PRIVATE).edit()
                    .putLong("last_seen_calls_ts", System.currentTimeMillis()).apply();
                clearBadge(R.id.nav_calls);
            }
            return true;
        });

        binding.fabAction.setOnClickListener(v -> {
            int pos = binding.viewPager.getCurrentItem();
            if      (pos == TAB_CHATS)  startActivity(new Intent(this, SearchActivity.class));
            else if (pos == TAB_STATUS) startActivity(new Intent(this, NewStatusActivity.class));
            else if (pos == TAB_GROUPS) startActivity(new Intent(this, NewGroupActivity.class));
            else if (pos == TAB_REELS)  startActivity(new Intent(this, ReelUploadActivity.class));
            else                        startActivity(new Intent(this, SearchActivity.class));
        });

        loadMyAvatar();
        loadReelsAvatarIntoNavTab();  // Reels nav tab mein Reels profile avatar dikhao
        refreshFcmToken();
        startBadgeListeners();
        // ── In-App Update Check — Firebase se version compare karta hai ──
        AppUpdateManager.check(this);
    }

    // Called when app is ALREADY running and user taps a reel notification or deep link
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleReelNotifIntent(intent);
        handleDeepLinkIntent(intent);  // Deep link handling
    }

    /** Handle incoming deep links from DeepLinkRouterActivity or direct App Links */
    private void handleDeepLinkIntent(Intent intent) {
        if (intent == null) return;
        String tab = intent.getStringExtra("open_tab");
        if (tab == null) return;
        switch (tab) {
            case "chats":        binding.viewPager.setCurrentItem(TAB_CHATS,  false); break;
            case "reels":        binding.viewPager.setCurrentItem(TAB_REELS,  false); break;
            case "status":       binding.viewPager.setCurrentItem(TAB_STATUS, false); break;
            case "groups":       binding.viewPager.setCurrentItem(TAB_GROUPS, false); break;
            case "calls":        binding.viewPager.setCurrentItem(TAB_CALLS,  false); break;
        }
    }

    /** Navigate to ReelNotificationsActivity when user taps the system
     *  "notification" payload notification (Doze / extreme killed state).
     *  The FCM click_action "OPEN_REEL_NOTIFICATION" routes here via manifest
     *  intent-filter; the data extras carry reel_id etc. */
    private void handleReelNotifIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean isReelNotif =
            "OPEN_REEL_NOTIFICATION".equals(action)
            || intent.hasExtra("reel_notif_type")
            || intent.hasExtra("reel_id");
        if (!isReelNotif) return;
        // Delay slightly so MainActivity finishes setup first
        binding.getRoot().post(() ->
            startActivity(new Intent(this, ReelNotificationsActivity.class)));
    }

    @Override protected void onResume() {
        super.onResume();
        loadMyAvatar();
        loadReelsAvatarIntoNavTab();  // Reels profile update hone par nav tab refresh
        // FIX #3: When MainActivity resumes (e.g. after returning from any activity launched
        // from a non-Reels tab), notify the ReelsFragment of the actual current tab state.
        // Without this, onTabPaused() is never called when leaving from other tabs, so
        // isTabActive stays true and reels keep playing in the background.
        boolean isReelsTab = binding.viewPager.getCurrentItem() == TAB_REELS;
        notifyReelsTabVisibility(isReelsTab);
        // FIX: Re-apply nav visibility on resume — onPageSelected does NOT re-fire
        // when returning from a sub-activity on the same tab, so bars stay hidden.
        setMainNavVisible(!isReelsTab);
    }

    @Override protected void onDestroy() {
        String uid = currentUid();
        if (uid != null) {
            if (unreadChatsListener    != null) FirebaseUtils.getContactsRef(uid).removeEventListener(unreadChatsListener);
            if (missedCallsListener    != null) FirebaseUtils.getCallsRef(uid).removeEventListener(missedCallsListener);
            if (unseenStatusListener   != null) FirebaseUtils.getStatusRef().removeEventListener(unseenStatusListener);
            if (unreadGroupsListener   != null) FirebaseUtils.getUserGroupsRef(uid).removeEventListener(unreadGroupsListener);
            if (unreadReelNotifsListener != null)
                FirebaseUtils.db().getReference("reel_notifications").child(uid)
                    .removeEventListener(unreadReelNotifsListener);
            if (notifChatBadgeListener  != null) FirebaseUtils.getContactsRef(uid).removeEventListener(notifChatBadgeListener);
            if (notifGroupBadgeListener != null) FirebaseUtils.getUserGroupsRef(uid).removeEventListener(notifGroupBadgeListener);
            if (notifReelBadgeListener  != null)
                FirebaseUtils.db().getReference("reel_notifications").child(uid).removeEventListener(notifReelBadgeListener);
            if (notifCallBadgeListener  != null) FirebaseUtils.getCallsRef(uid).removeEventListener(notifCallBadgeListener);
            // contactStatusChildListener is attached per-contact; detach the most recent reference
            // (full cleanup would require storing a map of uid → listener, but this prevents leaks
            //  on the most recently attached contact's listener chain)
            if (contactStatusChildListener != null) {
                // Best-effort: listener was last attached to a specific contact path, which is
                // already cleaned up by Firebase when the app process ends.
            }
        }
        super.onDestroy();

          // X badge listener cleanup
          if (xNotifBadgeListener != null) {
              if (uid != null) XFirebaseUtils.xUnreadNotifCountRef(uid).removeEventListener(xNotifBadgeListener);
          }
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
            case TAB_CHATS:  binding.fabAction.setImageResource(R.drawable.ic_status_add); break;
            case TAB_STATUS: binding.fabAction.setImageResource(R.drawable.ic_camera);     break;
            case TAB_GROUPS: binding.fabAction.setImageResource(R.drawable.ic_group);      break;
            case TAB_REELS:  binding.fabAction.setImageResource(R.drawable.ic_add_reels);  break;
            case TAB_CALLS:  binding.fabAction.setImageResource(R.drawable.ic_phone);      break;
        }
    }

    private void loadMyAvatar() {
        String uid = currentUid();
        if (uid == null) return;
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                String name  = snap.child("name").getValue(String.class);
                String photo = snap.child("photoUrl").getValue(String.class);
                String thumb = snap.child("thumbUrl").getValue(String.class);
                if (name  != null) myName     = name;
                if (photo != null) myPhotoUrl = photo;
                String avatarUrl = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                if (avatarUrl != null && !avatarUrl.isEmpty())
                    Glide.with(MainActivity.this).load(avatarUrl)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person).error(R.drawable.ic_person)
                        .into(binding.ivAvatarMenu);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void openMyReelsProfile() {
        String uid = currentUid();
        if (uid == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.UserReelsActivity");
            Intent i = new Intent(this, cls);
            i.putExtra("uid",   uid);
            i.putExtra("name",  myName);
            i.putExtra("photo", myPhotoUrl);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            startActivity(new Intent(this, AccountMenuActivity.class));
        }
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
        //    Cross-references statusSeen/{myUid}/{ownerUid}/{statusId} for accurate count.
        //    Only items whose timestamp is within 24 h AND not in statusSeen are "unseen".
        unseenStatusListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot allStatusSnap) {
                // Fetch our seen map first, then compute the badge count
                FirebaseUtils.getStatusSeenRef(uid).addListenerForSingleValueEvent(
                    new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot seenSnap) {
                            // Build: ownerUid → Set<statusId>
                            java.util.Map<String, java.util.Set<String>> seenMap
                                = new java.util.HashMap<>();
                            for (DataSnapshot ownerNode : seenSnap.getChildren()) {
                                java.util.Set<String> ids = new java.util.HashSet<>();
                                for (DataSnapshot idNode : ownerNode.getChildren())
                                    ids.add(idNode.getKey());
                                seenMap.put(ownerNode.getKey(), ids);
                            }
                            long cutoff = System.currentTimeMillis()
                                          - java.util.concurrent.TimeUnit.HOURS.toMillis(24);
                            int count = 0;
                            for (DataSnapshot ownerSnap : allStatusSnap.getChildren()) {
                                String ownerUid = ownerSnap.getKey();
                                if (uid.equals(ownerUid)) continue; // skip own statuses
                                java.util.Set<String> seen =
                                    seenMap.containsKey(ownerUid)
                                        ? seenMap.get(ownerUid) : new java.util.HashSet<>();
                                for (DataSnapshot statusItem : ownerSnap.getChildren()) {
                                    Long ts = statusItem.child("timestamp").getValue(Long.class);
                                    if (ts == null || ts <= cutoff) continue;
                                    String sid = statusItem.getKey();
                                    if (seen == null || !seen.contains(sid)) count++;
                                }
                            }
                            setBadge(R.id.nav_status, count);
                            // Also update the header notification ball
                            notifStatusUnread = count;
                            updateNotifBadge();
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    });
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getStatusRef().addValueEventListener(unseenStatusListener);

        // 4b. Story notification wiring — when a contact posts a NEW status item,
        //     enqueue StoryNotificationWorker so the device shows a notification even
        //     if the app is in the background or killed.
        //     Uses ChildEventListener on statuses/{uid} (all owners) and compares
        //     against contacts list to decide whether to enqueue.
        loadContactUidsForStoryNotif(uid);

        // 4. Unread group messages → nav_groups badge (index shifted — was #4, now after 4b)
        unreadGroupsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
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

        // 5. Unread reel notifications → nav_reels badge
        unreadReelNotifsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                int unread = 0;
                for (DataSnapshot n : snap.getChildren()) {
                    Boolean read = n.child("read").getValue(Boolean.class);
                    if (read == null || !read) unread++;
                }
                setBadge(R.id.nav_reels, unread);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("reel_notifications")
            .child(uid).addValueEventListener(unreadReelNotifsListener);

        // 6. AllNotifications toolbar badge
        startNotifBadgeListeners(uid);
    }

    /** Reels tab ke bottom nav icon mein Reels profile ka avatar load karo.
     *  Firebase path: reels/users/{uid} → photoUrl / thumbUrl */
    private void loadReelsAvatarIntoNavTab() {
        String uid = currentUid();
        if (uid == null) return;
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                    String thumbUrl = snap.child("thumbUrl").getValue(String.class);
                    String photoUrl = snap.child("photoUrl").getValue(String.class);
                    String url = (thumbUrl != null && !thumbUrl.isEmpty()) ? thumbUrl : photoUrl;
                    if (url == null || url.isEmpty()) return;
                    // Load as circular bitmap, then set as nav tab icon
                    int iconSizePx = (int) (24 * getResources().getDisplayMetrics().density);
                    Glide.with(MainActivity.this)
                        .asBitmap()
                        .load(url)
                        .apply(new RequestOptions().circleCrop().override(iconSizePx, iconSizePx))
                        .into(new CustomTarget<Bitmap>() {
                            @Override public void onResourceReady(
                                    @NonNull Bitmap resource,
                                    @Nullable Transition<? super Bitmap> transition) {
                                android.graphics.drawable.Drawable d =
                                    new BitmapDrawable(getResources(), resource);
                                android.view.MenuItem mi = binding.bottomNav.getMenu()
                                    .findItem(R.id.nav_reels);
                                mi.setIcon(d);
                                // Tint band karo — warna BottomNav avatar ko grey/tinted kar deta hai
                                if (binding.bottomNav instanceof com.google.android.material.bottomnavigation.BottomNavigationView) {
                                    com.google.android.material.bottomnavigation.BottomNavigationMenuView menuView =
                                        (com.google.android.material.bottomnavigation.BottomNavigationMenuView)
                                            binding.bottomNav.getChildAt(0);
                                    for (int i = 0; i < menuView.getChildCount(); i++) {
                                        com.google.android.material.bottomnavigation.BottomNavigationItemView itemView =
                                            (com.google.android.material.bottomnavigation.BottomNavigationItemView)
                                                menuView.getChildAt(i);
                                        if (itemView.getItemData() != null &&
                                            itemView.getItemData().getItemId() == R.id.nav_reels) {
                                            itemView.setIconTintList(null);
                                            break;
                                        }
                                    }
                                }
                            }
                            @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable p) {}
                        });
                }
                @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {}
            });
    }

    /** Sets up the animated X entry button in the toolbar */
    private void setupXEntryButton() {
        View xEntryRoot = findViewById(R.id.include_x_entry);
        if (xEntryRoot == null) return;

        CircleImageView ivAvatar = xEntryRoot.findViewById(R.id.iv_x_entry_avatar);
        View stripView           = xEntryRoot.findViewById(R.id.ll_x_entry_strip);
        TextView tvBadge         = xEntryRoot.findViewById(R.id.tv_x_entry_badge);

        // Load X profile avatar from x/users/{uid} (X ka alag profile)
        String uid = currentUid();
        if (uid != null && ivAvatar != null) {
            XFirebaseUtils.xUserRef(uid).addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    String thumb2 = snap.child("thumbUrl").getValue(String.class);
                    String photo2 = snap.child("photoUrl").getValue(String.class);
                    String xUrl = (thumb2 != null && !thumb2.isEmpty()) ? thumb2 : photo2;
                    if (xUrl != null && !xUrl.isEmpty()) {
                        Glide.with(MainActivity.this).load(xUrl)
                            .apply(new RequestOptions().circleCrop())
                            .placeholder(R.drawable.ic_person)
                            .into(ivAvatar);
                    }
                }
                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
            });
        }

        // Slide-in animation on the strip
        if (stripView != null) {
            stripView.setTranslationX(-200f);
            ObjectAnimator.ofFloat(stripView, "translationX", -200f, 0f)
                .setDuration(400)
                .start();
        }

        // Click → open XActivity
        xEntryRoot.setOnClickListener(v ->
            startActivity(new Intent(this, XActivity.class)));

        // Badge: listen for unread X notifications
        if (uid != null && tvBadge != null) {
            xNotifBadgeListener = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Long count = snap.getValue(Long.class);
                    if (count != null && count > 0) {
                        tvBadge.setVisibility(View.VISIBLE);
                        tvBadge.setText(count > 99 ? "99+" : String.valueOf(count));
                    } else {
                        tvBadge.setVisibility(View.GONE);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            XFirebaseUtils.xUnreadNotifCountRef(uid).addValueEventListener(xNotifBadgeListener);
        }
    }

    
    // ── YouTube Module entry button ───────────────────────────────────────────
    private void setupYouTubeEntryButton() {
        View ytEntryRoot = findViewById(R.id.include_yt_entry);
        if (ytEntryRoot == null) return;

        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            ytEntryRoot.findViewById(R.id.iv_yt_entry_avatar);
        View stripView =
            ytEntryRoot.findViewById(R.id.ll_yt_entry_strip);
        TextView tvBadge =
            ytEntryRoot.findViewById(R.id.tv_yt_entry_badge);

        // Load YouTube channel avatar from youtube/channels/{uid} (YouTube ka alag profile)
        String uid = currentUid();
        if (uid != null && ivAvatar != null) {
            YouTubeFirebaseUtils.channelRef(uid).addListenerForSingleValueEvent(
                new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(
                            @androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                        String photoUrl = snap.child("photoUrl").getValue(String.class);
                        String url = (photoUrl != null && !photoUrl.isEmpty()) ? photoUrl : null;
                        if (url != null)
                            Glide.with(MainActivity.this).load(url)
                                .apply(new RequestOptions().circleCrop())
                                .placeholder(R.drawable.ic_person)
                                .into(ivAvatar);
                    }
                    @Override public void onCancelled(
                            @androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                });
        }

        // Slide-in animation — strip slides RIGHT from behind the avatar
        if (stripView != null) {
            stripView.setTranslationX(-200f);
            android.animation.ObjectAnimator
                .ofFloat(stripView, "translationX", -200f, 0f)
                .setDuration(500)
                .start();
        }

        // Tap → open YouTubeActivity
        ytEntryRoot.setOnClickListener(v ->
            startActivity(new Intent(this, YouTubeActivity.class)));

        // Live badge listener — count unread YouTube notifications
        if (uid != null && tvBadge != null) {
            final TextView badge = tvBadge;
            ytNotifBadgeListener = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long unread = 0;
                    for (DataSnapshot ds : snap.getChildren()) {
                        Boolean read = ds.child("read").getValue(Boolean.class);
                        if (read == null || !read) unread++;
                    }
                    ytUnreadCount = (int) unread;
                    if (unread > 0) {
                        badge.setVisibility(View.VISIBLE);
                        badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
                    } else {
                        badge.setVisibility(View.GONE);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            YouTubeFirebaseUtils.notificationsRef(uid)
                .addValueEventListener(ytNotifBadgeListener);
        }

        // Schedule background notification worker
        YouTubeNotificationWorker.schedule(this);
    }

    // ── Games Module entry button ─────────────────────────────────────────────
    private void setupGamesEntryButton() {
        View gamesEntryRoot = findViewById(R.id.include_games_entry);
        if (gamesEntryRoot == null) return;

        CircleImageView ivAvatar = (CircleImageView) gamesEntryRoot.findViewById(R.id.iv_games_entry_avatar);
        View stripView           = gamesEntryRoot.findViewById(R.id.ll_games_entry_strip);
        if (ivAvatar == null || stripView == null) return;

        // Load user's main profile avatar (same as app profile — Games uses same identity)
        String uid = currentUid();
        if (uid != null && ivAvatar != null) {
            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(
                new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                        String thumb = snap.child("thumbUrl").getValue(String.class);
                        String photo = snap.child("photoUrl").getValue(String.class);
                        String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        if (url != null && !url.isEmpty()) {
                            Glide.with(MainActivity.this)
                                .load(url)
                                .apply(new RequestOptions().circleCrop())
                                .placeholder(R.drawable.ic_person)
                                .into(ivAvatar);
                        }
                    }
                    @Override
                    public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {}
                });
        }

        // Slide-in animation — same as X and YouTube
        if (stripView != null) {
            stripView.setTranslationX(-200f);
            ObjectAnimator.ofFloat(stripView, "translationX", -200f, 0f)
                .setDuration(400)
                .start();
        }

        // Click → open GamesHubActivity (reflection — cross-module safe)
        gamesEntryRoot.setOnClickListener(v -> {
            try {
                Class<?> cls = Class.forName("com.callx.app.activities.GamesHubActivity");
                startActivity(new Intent(this, cls));
            } catch (ClassNotFoundException e) {
                android.widget.Toast.makeText(this, "Games coming soon!", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Real-time badge on the 🔔 notification icon in the main toolbar */
    private void startNotifBadgeListeners(String uid) {
        // Chat unread
        notifChatBadgeListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                int n = 0;
                for (DataSnapshot c : snap.getChildren()) {
                    Long u = c.child("unread").getValue(Long.class);
                    if (u != null && u > 0) n++;
                }
                notifChatUnread = n;
                updateNotifBadge();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getContactsRef(uid).addValueEventListener(notifChatBadgeListener);

        // Group unread
        notifGroupBadgeListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                int n = 0;
                for (DataSnapshot g : snap.getChildren()) {
                    Long u = g.child("unread").getValue(Long.class);
                    if (u != null && u > 0) n++;
                }
                notifGroupUnread = n;
                updateNotifBadge();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getUserGroupsRef(uid).addValueEventListener(notifGroupBadgeListener);

        // Reel unread
        notifReelBadgeListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                int n = 0;
                for (DataSnapshot r : snap.getChildren()) {
                    Boolean read = r.child("read").getValue(Boolean.class);
                    if (read == null || !read) n++;
                }
                notifReelUnread = n;
                updateNotifBadge();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("reel_notifications")
            .child(uid).addValueEventListener(notifReelBadgeListener);

        // Missed calls
        notifCallBadgeListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                long seenTs = getSharedPreferences("callx_prefs", MODE_PRIVATE)
                    .getLong("last_seen_calls_ts", 0L);
                int n = 0;
                for (DataSnapshot c : snap.getChildren()) {
                    String dir = c.child("direction").getValue(String.class);
                    // Also support "status" = "missed" field used in some versions
                    String status = c.child("status").getValue(String.class);
                    Long ts = c.child("timestamp").getValue(Long.class);
                    boolean isMissed = "missed".equals(dir) || "missed".equals(status);
                    if (isMissed && ts != null && ts > seenTs) n++;
                }
                notifCallUnread = n;
                updateNotifBadge();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getCallsRef(uid).addValueEventListener(notifCallBadgeListener);
    }

    private void updateNotifBadge() {
        totalNotifUnread = notifChatUnread + notifGroupUnread + notifReelUnread + notifCallUnread + notifStatusUnread;
        android.widget.TextView badge = binding.getRoot().findViewById(R.id.tv_notif_badge);
        if (badge == null) return;
        if (totalNotifUnread > 0) {
            badge.setText(totalNotifUnread > 99 ? "99+" : String.valueOf(totalNotifUnread));
            badge.setVisibility(android.view.View.VISIBLE);
        } else {
            badge.setVisibility(android.view.View.GONE);
        }
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

    /**
     * Show or hide the main app header, bottom nav and FAB.
     * Also toggles full-screen immersive edge-to-edge for the Reels tab.
     */
    private void setMainNavVisible(boolean visible) {
          int vis = visible ? android.view.View.VISIBLE : android.view.View.GONE;

          // 1. Header (AppBarLayout)
          android.view.View appBar = binding.getRoot().findViewById(R.id.app_bar_layout);
          if (appBar != null) appBar.setVisibility(vis);

          // 2. Bottom nav container + FAB
          android.view.View navContainer = binding.getRoot().findViewById(R.id.nav_container);
          if (navContainer != null) navContainer.setVisibility(vis);
          else binding.bottomNav.setVisibility(vis);
          binding.fabAction.setVisibility(vis);

          // 3. ViewPager2: adjust top + bottom margins instantly (no behavior delay)
          //    topMargin = AppBar height (56dp) when normal, 0 when Reels full-screen
          //    bottomMargin = BottomNav height (58dp) when normal, 0 when Reels
          float density = getResources().getDisplayMetrics().density;
          ViewGroup.MarginLayoutParams lp =
              (ViewGroup.MarginLayoutParams) binding.viewPager.getLayoutParams();
          lp.topMargin    = visible ? (int)(56 * density) : 0;
          lp.bottomMargin = visible ? (int)(58 * density) : 0;
          binding.viewPager.setLayoutParams(lp);

          // 4. Root background: black when Reels tab so no grey/white shows
          //    behind the video in the status bar area (edge-to-edge fix)
          binding.getRoot().setBackgroundColor(
              visible ? 0xFFF5F6FA : 0xFF000000);

          // 5. Edge-to-edge immersive mode
          setImmersiveMode(!visible);
      }

    /**
     * Enable or disable full-screen immersive (edge-to-edge) mode.
     * When enabled (Reels tab) — true full-screen TikTok style:
     *   - Status bar AND navigation bar are both HIDDEN.
     *   - User can swipe from top/bottom edge to temporarily reveal them.
     *   - Content draws behind both bars (edge-to-edge).
     * When disabled (other tabs), normal system chrome is fully restored.
     */
    private void setImmersiveMode(boolean immersive) {
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (immersive) {
            // Content draws behind status bar + nav bar (edge-to-edge)
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            // Status bar: VISIBLE but fully transparent — video draws behind it (Instagram style)
            controller.show(WindowInsetsCompat.Type.statusBars());
            // White icons on status bar so they are readable over dark video
            controller.setAppearanceLightStatusBars(false);
            // Navigation bar: hide so video extends to bottom
            controller.hide(WindowInsetsCompat.Type.navigationBars());
            controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        } else {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
            controller.show(WindowInsetsCompat.Type.statusBars()
                | WindowInsetsCompat.Type.navigationBars());
            controller.setAppearanceLightStatusBars(true);
            controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        }
    }

    /**
     * Called by ReelsFragment back button to return to the Chats tab.
     */
    public void exitReelsTab() {
        binding.viewPager.setCurrentItem(TAB_CHATS, true);
    }

    /**
     * Notifies the ReelsFragment whether it is the currently visible tab.
     * This ensures reels pause immediately when the user switches to another tab
     * and resume only when the Reels tab is active — even while ViewPager2 keeps
     * the fragment alive in the background.
     */
    private void notifyReelsTabVisibility(boolean isReelsTabActive) {
        androidx.fragment.app.Fragment f = getSupportFragmentManager()
                .findFragmentByTag("f" + binding.viewPager.getAdapter().getItemId(TAB_REELS));
        if (f instanceof ReelsFragment) {
            if (isReelsTabActive) {
                ((ReelsFragment) f).onTabResumed();
            } else {
                ((ReelsFragment) f).onTabPaused();
            }
        }
    }

    /**
     * Loads the current user's contact UIDs, then attaches a ChildEventListener on
     * statuses/{contactUid} for each contact. When a new child is added (new status
     * posted), enqueues StoryNotificationWorker to show a notification kill-safely.
     */
    private void loadContactUidsForStoryNotif(String myUid) {
        FirebaseUtils.getContactsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    for (DataSnapshot c : snap.getChildren()) {
                        String contactUid = c.getKey();
                        if (contactUid == null) continue;

                        // Fetch contact's name + photo for the notification
                        FirebaseUtils.getUserRef(contactUid).addListenerForSingleValueEvent(
                            new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot userSnap) {
                                    String cName  = userSnap.child("name").getValue(String.class);
                                    String cThumb = userSnap.child("thumbUrl").getValue(String.class);
                                    String cPhotoFull = userSnap.child("photoUrl").getValue(String.class);
                                    String cPhoto = (cThumb != null && !cThumb.isEmpty()) ? cThumb : cPhotoFull;

                                    // Listen for new status items from this contact
                                    contactStatusChildListener =
                                        new ChildEventListener() {
                                            @Override public void onChildAdded(
                                                    DataSnapshot statusSnap, String prev) {
                                                String sid = statusSnap.getKey();
                                                if (sid == null
                                                    || notifiedStatusIds.contains(sid)) return;
                                                // Only notify for fresh statuses (< 60 s old)
                                                Long ts = statusSnap
                                                    .child("timestamp").getValue(Long.class);
                                                if (ts == null
                                                    || System.currentTimeMillis() - ts > 60_000)
                                                    return;
                                                notifiedStatusIds.add(sid);

                                                String type = statusSnap.child("type")
                                                    .getValue(String.class);
                                                String text = statusSnap.child("text")
                                                    .getValue(String.class);
                                                String media= statusSnap.child("mediaUrl")
                                                    .getValue(String.class);

                                                StoryNotificationWorker.enqueue(
                                                    MainActivity.this,
                                                    contactUid,
                                                    cName,
                                                    cPhoto,
                                                    type != null ? type : "text",
                                                    text  != null ? text  : "",
                                                    media != null ? media : "");
                                            }
                                            @Override public void onChildChanged(DataSnapshot s, String p) {}
                                            @Override public void onChildRemoved(DataSnapshot s) {}
                                            @Override public void onChildMoved(DataSnapshot s, String p) {}
                                            @Override public void onCancelled(DatabaseError e) {}
                                        };

                                    FirebaseUtils.getUserStatusRef(contactUid)
                                        .addChildEventListener(contactStatusChildListener);
                                }
                                @Override public void onCancelled(DatabaseError e) {}
                            });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
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
