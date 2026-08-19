package com.callx.app.activities;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.viewpager2.widget.ViewPager2;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
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
import com.callx.app.notifications.ReelNotificationsActivity;
import com.callx.app.workers.StoryNotificationWorker;
import com.callx.app.feed.ReelsFragment;
import com.callx.app.utils.AppUpdateManager;
import android.animation.ObjectAnimator;
import com.callx.app.upload.ReelUploadActivity;
import com.callx.app.profile.UserReelsActivity;
  import android.view.View;
  import android.widget.TextView;
  import android.widget.ImageButton;
  import com.bumptech.glide.Glide;
  import de.hdodenhof.circleimageview.CircleImageView;
  import com.callx.app.feed.XActivity;
  import com.callx.app.notifications.XNotificationWorker;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.home.YouTubeActivity;
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
import com.callx.app.group.NewGroupActivity;
import com.callx.app.services.CallForegroundService;
import com.callx.app.compose.NewStatusActivity;
import com.callx.app.hub.GamesHubActivity;
import com.callx.app.feed.ReelChatDockedPlayer;
import com.callx.app.feed.ReelDisplayModeListener;
import com.callx.app.social.ReelDisplayModeBottomSheet;
import com.callx.app.utils.ReelDisplayModePrefs;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import com.callx.app.audio.GlobalVoicePlaybackManager;
import com.callx.app.conversation.ChatActivity;

public class MainActivity extends AppCompatActivity
        implements com.callx.app.feed.ReelDisplayModeListener,
                   com.callx.app.social.ReelDisplayModeBottomSheet.OnModeSelectedListener {

    private ActivityMainBinding binding;

    // My profile cache — for UserReelsActivity launch
    private String myName     = "";
    private String myPhotoUrl = "";

    // ── Chat-tab docked reel player ──────────────────────────────────────────
    /** Manages the mini floating reel overlay shown when user leaves Reels for Chat. */
    private ReelChatDockedPlayer dockedPlayer;
    /** Tracks the ViewPager2 page position BEFORE the current switch. */
    private int prevTabPosition = TAB_CHATS;

    // ── Voice-note mini player (WhatsApp-style persistent playback) ──────
    private final GlobalVoicePlaybackManager.Listener voiceMiniPlayerListener =
            new GlobalVoicePlaybackManager.Listener() {
        @Override public void onPlaybackStarted(String messageId, String chatId, String partnerUid,
                                                  String displayName, String avatarUrl, boolean outgoing) {
            runOnUiThread(() -> updateVoiceMiniPlayer());
        }
        @Override public void onPlaybackToggled(String messageId, boolean playing) {
            runOnUiThread(() -> updateVoiceMiniPlayer());
        }
        @Override public void onPlaybackStopped(String messageId) {
            runOnUiThread(() -> updateVoiceMiniPlayer());
        }
    };
    // PERF: resolved once in setupVoiceMiniPlayer() instead of re-running
    // findViewById() down the view tree on every updateVoiceMiniPlayer()
    // call (which fires on every toggle/start/stop AND every onResume).
    private View miniPlayerBanner;
    private ImageButton miniPlayerBtnPlay;
    private TextView miniPlayerTvName;
    private CircleImageView miniPlayerIvAvatar;
    // PERF: last avatar URL actually loaded into the mini player — skips
    // re-issuing a Glide request when a toggle/resume refresh has nothing
    // new to show (Glide's own memory cache makes a repeat load cheap, but
    // skipping the call entirely avoids even that lookup on a path that
    // can run several times a second while scrubbing play/pause).
    private String miniPlayerLoadedAvatarUrl;


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

    // ── Tab history for Instagram-style back navigation ──────────────────
    private final java.util.Deque<Integer> tabHistoryStack = new java.util.ArrayDeque<>();
    private static final int TAB_CHATS  = 0;
    private static final int TAB_REELS  = 1;
    private static final int TAB_STATUS = 2;
    private static final int TAB_GROUPS = 3;
    private static final int TAB_CALLS  = 4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ── FASTEST-OPEN FIX: install the system splash screen FIRST ──────
        // Must be called before super.onCreate(). This makes the OS keep
        // showing the Theme.CallX.Splash icon/background (already on
        // screen since before Application.onCreate even ran) instead of
        // tearing it down for a blank window while the rest of onCreate
        // below does its work. Default dismiss condition is "this
        // Activity's first frame is drawn", which — since we now gate
        // everything behind the auth check right below — means the splash
        // stays up either through the brief auth-check-and-redirect (not
        // logged in) or straight through to the real UI being ready to
        // paint (logged in), never a blank/white flash in between.
        androidx.core.splashscreen.SplashScreen.installSplashScreen(this);

        // MUST be called before super.onCreate / setContentView so the window
        // is configured for edge-to-edge before any layout pass happens.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Cutout mode set ONCE here (not toggled per tab-switch) — repeatedly
        // calling getWindow().setAttributes() on every Reels<->tab switch was
        // forcing a window relayout race that intermittently left nav_container
        // mismeasured/invisible after returning from Reels.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                            ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }

        super.onCreate(savedInstanceState);

        // ── FASTEST-OPEN FIX: auth check BEFORE inflating anything ────────
        // Old order inflated the ENTIRE ActivityMainBinding (toolbar,
        // ViewPager2 host, bottom nav, FAB, return-to-call banner, etc.)
        // and ran a full layout+draw pass EVERY cold start, even for a
        // logged-out user who was just about to get redirected straight to
        // AuthActivity anyway — pure wasted inflate + an extra Activity
        // hop on top. Checking first means the not-logged-in path (rare —
        // fresh install / after logout) redirects immediately with zero
        // wasted work, and the logged-in path (the common one) goes
        // straight into the real inflate below with nothing in front of it.
        // FirebaseAuth.getInstance().getCurrentUser() is a synchronous read
        // of the already-cached local auth state (no network call), so
        // this check itself costs effectively nothing.
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, AuthActivity.class));
            overridePendingTransition(0, 0); // no slide — this isn't a real tab/screen transition
            finish();
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        requestPermissions();

        // ── Handle tap from system reel notification (Doze / killed state) ─────
        handleReelNotifIntent(getIntent());
        handleDeepLinkIntent(getIntent());  // Tab deep link
        // ──────────────────────────────────────────────────────────────────────

        setSupportActionBar(binding.toolbar);

          // v241: old toolbar X / YouTube / Games entry-strip buttons removed
          // (setupXEntryButton() / setupYouTubeEntryButton() / setupGamesEntryButton()
          // no longer called — those cards now live in the Chats tab quick-access
          // header instead). YouTube's background notification worker still needs
          // to run regardless of the button, so that one scheduling call is kept.
          YouTubeNotificationWorker.schedule(this);

        binding.btnSearchToolbar.setOnClickListener(v -> {
            startActivity(new Intent(this, SearchActivity.class));
            overridePendingTransition(0, 0); // Tab switch — instant 0ms
        });

        binding.btnNotificationsToolbar.setOnClickListener(v -> {
            startActivity(new Intent(this, AllNotificationsActivity.class));
            overridePendingTransition(0, 0); // Tab switch — instant 0ms
        });

        setupVoiceMiniPlayer();

        // v244: avatar removed from toolbar — Settings (AccountMenuActivity)
        // now opens from the 3-dot overflow menu instead (see onOptionsItemSelected).

        binding.viewPager.setAdapter(new ViewPagerAdapter(this));
        // FIX #LAZY: offscreenPageLimit 2 → 1 kiya gaya.
        // Pehle: Tab 0 open hone par Tab 1 + Tab 2 dono immediately load hote the.
        // Ab:    Sirf Tab 1 (Status) pre-load hoga — Tab 2 (Groups), Tab 3 (Reels),
        //        Tab 4 (Calls) tab par tap karne par hi load honge.
        // Faida: ~15% less memory on startup, Reels ExoPlayer init tab switch pe hoga.
        binding.viewPager.setOffscreenPageLimit(1);
        
        // Initialize tab history with starting tab (chats)
        tabHistoryStack.push(TAB_CHATS);
        // Bottom nav tap par instant switch (no scroll animation) — WhatsApp jaisa
        binding.viewPager.setUserInputEnabled(true);
        // FIX: multi-photo reel ke left/right swipe se tab switch ho jaata tha —
        // Reels feature (different module) is deterministic backstop ke through
        // is pager ka swipe on/off karta hai jab tak koi multi-photo reel screen
        // par visible hai. See ReelTabSwipeLock for the full explanation.
        com.callx.app.utils.ReelTabSwipeLock.setController(
            enabled -> binding.viewPager.setUserInputEnabled(enabled));
        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                // Add to history stack if different from current top
                if (tabHistoryStack.isEmpty() || tabHistoryStack.peek() != position) {
                    tabHistoryStack.push(position);
                }
                
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
                // First time ever landing on the Reels tab: ask the user which
                // display mode they want (Immersive vs Normal) before applying
                // any chrome visibility change for this tab.
                if (position == TAB_REELS && !com.callx.app.utils.ReelDisplayModePrefs.hasBeenAsked(MainActivity.this)) {
                    showReelDisplayModeFirstVisitChooser();
                }
                // Hide app's own header + bottom nav when Reels tab is active
                // (always, in both display modes); phone status/nav bar
                // visibility depends on the chosen Reels display mode.
                applyReelsTabChrome(position == TAB_REELS);
                // WhatsApp-style: the app header (title/search/bell/avatar +
                // X/YouTube/Games entry) only belongs on the Chats tab. Status,
                // Groups and Calls each have their own top area (or none) —
                // Reels is skipped here since it already fully owns its chrome.
                updateHeaderVisibilityForTab(position);
                // ── Reel playback: pause when leaving, resume when entering ──
                // Track previous tab so we know whether user is going TO or FROM Reels
                notifyReelsTabVisibility(position == TAB_REELS, position);
                prevTabPosition = position;
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
        loadReelsAvatarIntoNavTab();
        int currentTab = binding.viewPager.getCurrentItem();
        boolean isReelsTab = currentTab == TAB_REELS;

        // v8: pick the docked mini reel player back up first — e.g. the user
        // just returned from ChatActivity with one still playing. Same
        // ExoPlayer instance, no reload, only the rendering surface moves.
        if (dockedPlayer != null && dockedPlayer.isActive() && !dockedPlayer.isShowing()) {
            dockedPlayer.attachToActivity(this);
        }

        // onResume doesn't represent a tab switch. If a docked session is
        // already active (just reattached above, or was already showing
        // before this pause/resume cycle) skip the normal dock/undock
        // transition below entirely — re-running it here would immediately
        // tear down the session we just reattached and rebuild a brand new
        // one from whatever ReelsFragment's internal pager happens to be on.
        boolean dockedAlreadyActive = dockedPlayer != null && dockedPlayer.isActive();
        if (!dockedAlreadyActive) {
            // Pass current tab as destination — onResume doesn't represent a "switch",
            // so we treat it as a no-op dock transition (normal resume path).
            notifyReelsTabVisibility(isReelsTab, currentTab);
        }
        applyReelsTabChrome(isReelsTab);
        updateHeaderVisibilityForTab(currentTab);
        // Feature 1: Return to Call Banner
        updateReturnToCallBanner();
        // Voice-note mini player — re-sync in case a clip started/stopped
        // while this Activity wasn't the one on screen (e.g. was paused
        // behind ChatActivity when playback state last changed).
        updateVoiceMiniPlayer();
    }

    // ── Voice-note mini player ────────────────────────────────────────────
    // WhatsApp-style green strip — shown above the toolbar whenever
    // GlobalVoicePlaybackManager has an active voice note (playing OR
    // paused-but-still-active), which happens once the user leaves the
    // ChatActivity screen it was started from. See MessagePagingAdapter's
    // onDetachedFromRecyclerView for the hand-off that keeps it alive.
    private void setupVoiceMiniPlayer() {
        View banner = binding.getRoot().findViewById(R.id.banner_voice_mini_player);
        if (banner == null) return;
        // PERF: resolve every child view once here; updateVoiceMiniPlayer()
        // (the hot path — runs on every play/pause/start/stop and every
        // onResume) then just reads these fields instead of walking the
        // view tree again.
        miniPlayerBanner = banner;
        miniPlayerBtnPlay = banner.findViewById(R.id.btn_mini_player_play);
        miniPlayerTvName = banner.findViewById(R.id.tv_mini_player_name);
        miniPlayerIvAvatar = banner.findViewById(R.id.iv_mini_player_avatar);

        GlobalVoicePlaybackManager.getInstance().addListener(voiceMiniPlayerListener);

        miniPlayerBtnPlay.setOnClickListener(v ->
                GlobalVoicePlaybackManager.getInstance().togglePlayPause());

        banner.findViewById(R.id.btn_mini_player_close).setOnClickListener(v ->
                GlobalVoicePlaybackManager.getInstance().stopAndClear());

        // Tap anywhere else on the strip → reopen the chat this clip came
        // from, same as tapping the return-to-call banner reopens CallActivity.
        banner.setOnClickListener(v -> {
            String partnerUid = GlobalVoicePlaybackManager.getInstance().getCurrentPartnerUid();
            if (partnerUid == null || partnerUid.isEmpty()) return;
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("partnerUid", partnerUid);
            if (!GlobalVoicePlaybackManager.getInstance().isOutgoing()) {
                String name = GlobalVoicePlaybackManager.getInstance().getDisplayName();
                String avatar = GlobalVoicePlaybackManager.getInstance().getAvatarUrl();
                if (name != null) i.putExtra("partnerName", name);
                if (avatar != null) i.putExtra("partnerPhoto", avatar);
            }
            startActivity(i);
            overridePendingTransition(0, 0);
        });
    }

    private void updateVoiceMiniPlayer() {
        if (miniPlayerBanner == null) return;

        GlobalVoicePlaybackManager mgr = GlobalVoicePlaybackManager.getInstance();
        if (!mgr.hasActiveMessage()) {
            if (miniPlayerBanner.getVisibility() != View.GONE) {
                miniPlayerBanner.setVisibility(View.GONE);
            }
            miniPlayerLoadedAvatarUrl = null;
            return;
        }

        if (miniPlayerBanner.getVisibility() != View.VISIBLE) {
            miniPlayerBanner.setVisibility(View.VISIBLE);
        }

        miniPlayerBtnPlay.setImageResource(mgr.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        String name = mgr.getDisplayName();
        miniPlayerTvName.setText(name != null && !name.isEmpty() ? name : "Voice message");

        // PERF: only touch Glide when the avatar actually changed — a
        // play/pause toggle re-renders this method without a new avatar,
        // so re-issuing the same load request every time is wasted work.
        String avatarUrl = mgr.getAvatarUrl();
        if (!java.util.Objects.equals(avatarUrl, miniPlayerLoadedAvatarUrl)) {
            miniPlayerLoadedAvatarUrl = avatarUrl;
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                Glide.with(this).load(avatarUrl).placeholder(R.drawable.ic_person)
                        .into(miniPlayerIvAvatar);
            } else {
                miniPlayerIvAvatar.setImageResource(R.drawable.ic_person);
            }
        }
    }

    // ── Feature 1: Return to Call Banner ─────────────────────────────────
    // WhatsApp-style green strip — tab dikhata hai jab user call mein ho aur
    // alag screen pe chala jaye. Tap karne par CallActivity wapas khul jaati hai.
    private final Handler bannerTickHandler = new Handler(Looper.getMainLooper());
    private Runnable bannerTickRunnable;

    private void updateReturnToCallBanner() {
        // CallForegroundService ke static field se check karo
        boolean callActive;
        try {
            Class<?> cls = Class.forName(
                "com.callx.app.services.CallForegroundService");
            callActive = (boolean) cls.getField("isRunning").get(null);
        } catch (Exception e) {
            callActive = false;
        }

        View banner = binding.getRoot().findViewById(R.id.banner_return_to_call);
        if (banner == null) return;

        if (!callActive) {
            banner.setVisibility(View.GONE);
            bannerTickHandler.removeCallbacksAndMessages(null);
            return;
        }

        // Banner dikhao
        banner.setVisibility(View.VISIBLE);

        // Name set karo
        android.widget.TextView tvName =
            banner.findViewById(R.id.tv_return_to_call_name);
        android.widget.TextView tvTimer =
            banner.findViewById(R.id.tv_return_to_call_timer);

        try {
            Class<?> cls = Class.forName(
                "com.callx.app.services.CallForegroundService");
            String name = (String) cls.getField("activePartnerName").get(null);
            if (tvName != null && name != null && !name.isEmpty())
                tvName.setText(name + " · Tap to return");
        } catch (Exception ignored) {}

        // Live timer tick karo
        bannerTickHandler.removeCallbacksAndMessages(null);
        bannerTickRunnable = new Runnable() {
            @Override public void run() {
                boolean still;
                try {
                    Class<?> cls = Class.forName(
                        "com.callx.app.services.CallForegroundService");
                    still = (boolean) cls.getField("isRunning").get(null);
                } catch (Exception ex) { still = false; }
                if (!still) {
                    banner.setVisibility(View.GONE);
                    return;
                }
                if (tvTimer != null) {
                    // Duration from startedAt not exposed — show animated dots instead
                    String[] dots = {"●○○", "○●○", "○○●"};
                    tvTimer.setText(dots[(int)((System.currentTimeMillis() / 500) % 3)]);
                }
                bannerTickHandler.postDelayed(this, 500);
            }
        };
        bannerTickHandler.post(bannerTickRunnable);

        // Tap → CallActivity wapas kholo with isRestore=true
        banner.setOnClickListener(v -> {
            try {
                Class<?> cls = Class.forName(
                    "com.callx.app.services.CallForegroundService");
                String uid   = (String) cls.getField("activePartnerUid").get(null);
                String name  = (String) cls.getField("activePartnerName").get(null);
                String photo = (String) cls.getField("activePartnerPhoto").get(null);
                String thumb = (String) cls.getField("activePartnerThumb").get(null);
                String cid   = (String) cls.getField("activeCallId").get(null);
                boolean vid  = (boolean) cls.getField("activeIsVideo").get(null);
                boolean iCal = (boolean) cls.getField("activeIsCaller").get(null);

                Intent i = new Intent();
                i.setClassName(this,
                    "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid",   uid);
                i.putExtra("partnerName",  name);
                i.putExtra("partnerPhoto", photo);
                i.putExtra("partnerThumb", thumb);
                i.putExtra("callId",       cid);
                i.putExtra("video",        vid);
                i.putExtra("isCaller",     iCal);
                i.putExtra("isRestore",    true);
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            } catch (Exception ex) {
                android.util.Log.w("MainActivity", "Banner tap failed", ex);
            }
        });
    }

    @Override protected void onPause() {
        super.onPause();
        bannerTickHandler.removeCallbacksAndMessages(null);
    }

    // ── Feature 5: Picture-in-Picture support ─────────────────────────────
    //
    // Three-layer PiP trigger strategy (most → least reliable):
    //
    //  Layer 1 — API 31+  setAutoEnterEnabled(true): system handles gesture-home
    //             nav automatically; onUserLeaveHint is NOT needed on these devices.
    //  Layer 2 — onUserLeaveHint(): fires on back-button / power-button home.
    //             Covers API 26-30 and older gesture models.
    //  Layer 3 — onStop(): last-resort fallback for ROM variants that skip both
    //             of the above (observed on some Xiaomi / MIUI builds).
    //
    // expandForPip() is called INSIDE enterPipIfSupported() BEFORE the system call
    // so the video surface is already full-window when PiP clips it — this prevents
    // the jarring "mini player → PiP" size jump.

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Layer 2 fallback — covers hardware home button + API 26-30 gesture
        if (dockedPlayer != null && dockedPlayer.isShowing()
                && !dockedPlayer.isInPipMode()) {
            dockedPlayer.enterPipIfSupported();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Layer 3 fallback — some OEM ROMs (MIUI, ColorOS) skip onUserLeaveHint.
        // If we reach onStop() with a visible mini player and NOT already in PiP,
        // attempt to enter PiP now.
        if (dockedPlayer != null && dockedPlayer.isShowing()
                && !dockedPlayer.isInPipMode()
                && !isFinishing()) {
            dockedPlayer.enterPipIfSupported();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPipMode,
                                              android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig);

        // NOTE: do NOT guard with dockedPlayer.isShowing() here.
        // expandForPip() sets inPipMode=true, which means isShowing() may behave
        // differently depending on implementation. Track PiP state via isInPipMode flag.
        if (dockedPlayer == null) return;

        if (isInPipMode) {
            // Safety net: expand again in case the system triggered PiP via autoEnter
            // before our enterPipIfSupported() could call expandForPip() first.
            dockedPlayer.expandForPip();
        } else {
            // User swiped PiP overlay away or returned to the app → restore corner
            if (dockedPlayer.isInPipMode()) {
                dockedPlayer.restoreFromPip();
            }
        }
    }

    @Override protected void onDestroy() {
        // FIX: detach so a destroyed Activity's binding.viewPager can't be
        // touched by a stray lock()/unlock() call from a Reels fragment
        // still finishing off its own lifecycle.
        com.callx.app.utils.ReelTabSwipeLock.setController(null);
        GlobalVoicePlaybackManager.getInstance().removeListener(voiceMiniPlayerListener);
        // Clean up any active docked reel player to release the ExoPlayer surface
        if (dockedPlayer != null) {
            dockedPlayer.dismiss(false);
            dockedPlayer = null;
        }
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
            ? null : FirebaseUtils.getCurrentUid();
    }

    // ── Overlay permission callback — auto-retry SmallWindow ─────────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == com.callx.app.smallwindow.PrivacyDirectDialog.REQ_OVERLAY_PERMISSION) {
            // User returned from overlay permission settings
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    && android.provider.Settings.canDrawOverlays(this)) {
                // Permission granted — auto-launch if pending data exists
                String uid    = getIntent().getStringExtra("_sw_pending_uid");
                String name   = getIntent().getStringExtra("_sw_pending_name");
                String status = getIntent().getStringExtra("_sw_pending_status");
                String photo  = getIntent().getStringExtra("_sw_pending_photo");

                if (uid != null || name != null) {
                    // Clean up pending extras
                    getIntent().removeExtra("_sw_pending_uid");
                    getIntent().removeExtra("_sw_pending_name");
                    getIntent().removeExtra("_sw_pending_status");
                    getIntent().removeExtra("_sw_pending_photo");

                    // Launch service
                    android.content.Intent svc = new android.content.Intent(
                        this, com.callx.app.smallwindow.SmallWindowService.class);
                    svc.putExtra(com.callx.app.smallwindow.SmallWindowService.EXTRA_USER_ID, uid);
                    svc.putExtra(com.callx.app.smallwindow.SmallWindowService.EXTRA_NAME,
                        name != null ? name : "");
                    svc.putExtra(com.callx.app.smallwindow.SmallWindowService.EXTRA_STATUS,
                        status != null ? status : "");
                    svc.putExtra(com.callx.app.smallwindow.SmallWindowService.EXTRA_PHOTO,
                        photo != null ? photo : "");

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }

                    android.widget.Toast.makeText(this,
                        "Small window open!", android.widget.Toast.LENGTH_SHORT).show();
                }
            } else {
                android.widget.Toast.makeText(this,
                    "Permission nahi mili — Settings mein jaake ON karo",
                    android.widget.Toast.LENGTH_LONG).show();
            }
        }
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
                // v244: toolbar avatar (ivAvatarMenu) removed — Settings now lives
                // in the 3-dot overflow menu instead. myName/myPhotoUrl fetched
                // above are still used elsewhere (e.g. openMyReelsProfile()), so
                // this method stays; only the Glide-into-view call is gone.
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void openMyReelsProfile() {
        String uid = currentUid();
        if (uid == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.profile.UserReelsActivity");
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

    // v242: setupXEntryButton() / setupYouTubeEntryButton() / setupGamesEntryButton()
    // removed entirely — the old toolbar strip buttons (include_x_entry / include_yt_entry /
    // include_games_entry) no longer exist in activity_main.xml, so these methods (and their
    // R.id references) would fail to compile. That functionality now lives in the Chats-tab
    // quick-access card row instead. YouTubeNotificationWorker.schedule(this) — the one
    // non-UI side effect these methods had — is still called from onCreate() above.

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
     * Show or hide the main app header and bottom nav (the app's OWN tab
     * bar — Chats/Reels/Status/Groups/Calls), independent of the phone's
     * system status bar / navigation bar. See applyReelsTabChrome() below
     * for how these combine on the Reels tab.
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

          // Force an explicit relayout so nav_container/appBar visibility
          // changes are always re-measured immediately (avoids the view
          // staying visually collapsed after a window inset toggle).
          binding.getRoot().requestLayout();
      }

    /**
     * WhatsApp-level behaviour: the app's own top header — title, search,
     * notification bell, avatar menu, and the X / YouTube / Games entry
     * chips — is Chats-specific chrome, not a generic app-wide bar. In
     * WhatsApp itself each tab (Chats, Updates/Status, Communities, Calls)
     * owns its own top area; here that means the shared header only shows
     * on the Chats tab and is hidden everywhere else (Status, Groups,
     * Calls each already render their own in-fragment top bar where
     * needed — e.g. Status's search field).
     *
     * The Reels tab is intentionally skipped: it already fully owns its
     * chrome (header AND bottom nav both hidden) via applyReelsTabChrome()
     * / setMainNavVisible(), so re-touching the header here would fight
     * that logic. Bottom nav is never touched by this method — it must
     * stay visible on every non-Reels tab so the user can still switch tabs.
     */
    private void updateHeaderVisibilityForTab(int position) {
        if (position == TAB_REELS) return; // Reels owns its own chrome fully

        boolean showHeader = (position == TAB_CHATS);
        int vis = showHeader ? android.view.View.VISIBLE : android.view.View.GONE;

        android.view.View appBar = binding.getRoot().findViewById(R.id.app_bar_layout);
        if (appBar != null) appBar.setVisibility(vis);

        // Only the top margin changes here — bottom margin (bottom nav
        // space) is left alone since the nav bar stays visible on all
        // non-Reels tabs.
        float density = getResources().getDisplayMetrics().density;
        ViewGroup.MarginLayoutParams lp =
            (ViewGroup.MarginLayoutParams) binding.viewPager.getLayoutParams();
        lp.topMargin = showHeader ? (int) (56 * density) : 0;
        binding.viewPager.setLayoutParams(lp);

        binding.getRoot().requestLayout();
    }

    /**
     * Applies the correct combination of chrome for the current tab.
     *
     *  Non-Reels tabs: app header + app bottom nav VISIBLE, phone status bar
     *                  + phone navigation bar VISIBLE (fully normal).
     *
     *  Reels tab:      app header + app bottom nav ALWAYS HIDDEN — Reels is
     *                  always full-bleed edge-to-edge for its own chrome,
     *                  in BOTH display modes.
     *                    - Immersive mode: phone status bar + nav bar HIDDEN too
     *                      (true full-screen, swipe-to-reveal).
     *                    - Normal mode:    phone status bar + nav bar VISIBLE
     *                      (only the app's own header/tab-bar stay hidden).
     */
    private void applyReelsTabChrome(boolean isReelsTab) {
        if (!isReelsTab) {
            setMainNavVisible(true);
            setImmersiveMode(false);
            return;
        }
        setMainNavVisible(false);
        boolean showSystemBars = com.callx.app.utils.ReelDisplayModePrefs.isNormalMode(this);
        setReelsSystemBarsVisible(showSystemBars);
    }

    /**
     * Reels-tab-only system bar toggle (used by applyReelsTabChrome). Unlike
     * setImmersiveMode(), this always keeps status/nav bar ICONS light —
     * Reels' background is black in both display modes, so dark icons would
     * be invisible against it even when the bars themselves are visible.
     */
    private void setReelsSystemBarsVisible(boolean visible) {
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (visible) {
            // Normal mode: phone status bar + nav bar VISIBLE, light (white)
            // icons/buttons so they read over the black Reels background.
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
            controller.show(WindowInsetsCompat.Type.statusBars()
                | WindowInsetsCompat.Type.navigationBars());
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
            controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
            getWindow().setStatusBarColor(android.graphics.Color.BLACK);
            getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        } else {
            // Immersive mode: both bars HIDDEN, edge-to-edge, swipe to reveal.
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            controller.hide(WindowInsetsCompat.Type.statusBars()
                | WindowInsetsCompat.Type.navigationBars());
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
            controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        }
        // ✅ FIX: reel_bottom_nav (inside ReelsFragment) was rendering BEHIND
        // the phone's own navigation bar after switching Normal ⇄ Immersive
        // from the Display Mode sheet (no tab-switch happens here, so the
        // already-inflated fragment view never got a fresh WindowInsets
        // dispatch). Toggling setDecorFitsSystemWindows()/show()/hide() above
        // changes the WINDOW's inset state, but does NOT by itself re-dispatch
        // insets down to already-laid-out child views — reel_bottom_nav's own
        // OnApplyWindowInsetsListener (in ReelsFragment) kept using its stale
        // padding from before the mode switch, so it sat exactly where the
        // now-visible/repositioned system nav bar draws on top of it.
        // Explicitly requesting insets on the root forces that listener to
        // recompute with the new state so the tab always ends up ABOVE the
        // phone's nav bar. Purely a layout/insets fix — does not touch
        // reel_bottom_nav's separate scroll show/hide (translationY) logic.
        androidx.core.view.ViewCompat.requestApplyInsets(binding.getRoot());
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

            // Status bar: HIDDEN (same as ChatActivity) — swipe from top to reveal temporarily
            controller.hide(WindowInsetsCompat.Type.statusBars());
            // White icons on status bar so they are readable over dark video (on swipe-reveal)
            controller.setAppearanceLightStatusBars(false);
            // Navigation bar: hide so video extends to bottom
            controller.hide(WindowInsetsCompat.Type.navigationBars());
            controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
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
     * Called once — the very first time the user ever lands on the Reels tab.
     * Shown non-cancelable so the user picks a real answer; MainActivity itself
     * is the listener since it shows the sheet on its own FragmentManager.
     */
    private void showReelDisplayModeFirstVisitChooser() {
        ReelDisplayModeBottomSheet sheet = ReelDisplayModeBottomSheet.newInstance(
            ReelDisplayModePrefs.MODE_IMMERSIVE, true);
        sheet.show(getSupportFragmentManager(), ReelDisplayModeBottomSheet.TAG);
    }

    /** ReelDisplayModeBottomSheet.OnModeSelectedListener — first-visit chooser answered. */
    @Override
    public void onModeSelected(String mode) {
        ReelDisplayModePrefs.setMode(this, mode);
        ReelDisplayModePrefs.markAsked(this);
        applyReelDisplayModeIfOnReelsTab();
    }

    /**
     * ReelDisplayModeListener — the user changed their mind from the Reels
     * 3-dot menu while still sitting on the Reels tab (no tab-switch to
     * trigger the usual re-check), so re-apply chrome visibility right now.
     */
    @Override
    public void onReelDisplayModeChanged(String mode) {
        applyReelDisplayModeIfOnReelsTab();
    }

    private void applyReelDisplayModeIfOnReelsTab() {
        if (binding.viewPager.getCurrentItem() == TAB_REELS) {
            applyReelsTabChrome(true);
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
     *
     * When the user navigates AWAY from Reels to the Chat tab specifically,
     * the current reel's ExoPlayer surface is transferred to a small floating
     * overlay (ReelChatDockedPlayer) so playback continues uninterrupted while
     * the user reads/sends messages.
     *
     * When the user returns to Reels, the surface is transferred back and
     * playback resumes seamlessly.
     *
     * For any other tab switch (Status, Groups, Calls), normal pause applies.
     *
     * @param isReelsTabActive true when the Reels tab is the newly selected tab.
     * @param newTabPosition   the ViewPager2 position that was just selected.
     */
    @OptIn(markerClass = UnstableApi.class)
    private void notifyReelsTabVisibility(boolean isReelsTabActive, int newTabPosition) {
        androidx.fragment.app.Fragment f = getSupportFragmentManager()
                .findFragmentByTag("f" + binding.viewPager.getAdapter().getItemId(TAB_REELS));
        if (!(f instanceof ReelsFragment)) return;
        ReelsFragment reelsFragment = (ReelsFragment) f;

        if (isReelsTabActive) {
            // ── Returning to Reels ────────────────────────────────────────────
            if (dockedPlayer != null && dockedPlayer.isActive()) {
                // Transfer ExoPlayer surface back to fragment BEFORE resuming playback.
                // collapseBack() calls originalFragmentPlayerView.setPlayer(player),
                // so when onTabResumed() → startPlayback() runs, the view is ready.
                // v11: isActive() (not isShowing()) — must still hand the surface
                // back even if the docked overlay happens to be mid cross-Activity
                // handoff (detached but session still alive) right now.
                dockedPlayer.collapseBack();
                dockedPlayer = null;
            }
            reelsFragment.onTabResumed();

        } else {
            // ── Leaving Reels ─────────────────────────────────────────────────
            boolean isGoingToChat = (newTabPosition == TAB_CHATS)
                && com.callx.app.docked.DockedPlayerSettings.isEnabled(this);

            if (isGoingToChat) {
                // ── Chat-tab docking: keep reel playing in mini overlay ────────
                // Dismiss any stale docked player first (e.g. from a previous switch)
                if (dockedPlayer != null && dockedPlayer.isActive()) {
                    dockedPlayer.dismiss(false);
                }
                dockedPlayer = new ReelChatDockedPlayer(this);
                final ReelChatDockedPlayer dockedRef = dockedPlayer;

                reelsFragment.onTabPausedForChat((player, fragmentPlayerView, thumbUrl) -> {
                    // Safety: activity might have been destroyed by the time the
                    // callback fires (synchronous in practice, but guard anyway)
                    if (isDestroyed() || isFinishing()) return;

                    dockedRef.show(player, fragmentPlayerView, thumbUrl,
                            new ReelChatDockedPlayer.Callback() {

                        @Override
                        public void onDockedPlayerDismissed() {
                            // User closed the mini-player → disable PiP then release.
                            dockedRef.disableAutoEnterPip();
                            if (dockedPlayer == dockedRef) dockedPlayer = null;
                            reelsFragment.onTabPaused();
                        }

                        @Override
                        public void onDockedPlayerExpandRequested() {
                            // Feature 2: Double-tap → collapse surface back + switch to Reels.
                            dockedRef.disableAutoEnterPip();
                            dockedRef.collapseBack();
                            if (dockedPlayer == dockedRef) dockedPlayer = null;
                            binding.viewPager.setCurrentItem(TAB_REELS, false);
                        }

                        @Override
                        public void onDockedPlayerNextReel() {
                            // Feature 4: Swipe-up → advance to next reel in mini player.
                            reelsFragment.advanceToNextForDockedPlayer(dockedRef);
                        }
                    });

                    // Feature 5 — register PiP params immediately after show().
                    // API 31+: setAutoEnterEnabled(true) handles gesture-home nav
                    //          without needing onUserLeaveHint() at all.
                    // API 26-30: params pre-registered for reliable onUserLeaveHint.
                    dockedRef.enableAutoEnterPip();
                });

            } else {
                // ── Other tab (Status, Groups, Calls): normal pause ───────────
                // If a docked player is showing (user switches from Chat → Groups),
                // dismiss it properly so we don't leak the player surface.
                if (dockedPlayer != null && dockedPlayer.isActive()) {
                    dockedPlayer.dismiss(false);
                    dockedPlayer = null;
                    // Normal pause handles player release
                }
                reelsFragment.onTabPaused();
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

    @Override
    public void onBackPressed() {
        // Instagram-style tab history navigation:
        // If we have more than one tab in history, pop the current and go to previous
        if (!tabHistoryStack.isEmpty()) {
            tabHistoryStack.pop();  // Remove current tab
        }
        
        if (!tabHistoryStack.isEmpty()) {
            // Navigate to the previous tab without adding to history again
            int previousTab = tabHistoryStack.peek();
            binding.viewPager.setCurrentItem(previousTab, false);
        } else {
            // No history, fall back to default behavior (close app)
            super.onBackPressed();
        }
    }

    // ── v21: Overflow menu — Delete All Chats ────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        int pos = binding.viewPager.getCurrentItem();
        // "Delete All Chats" sirf Chat tab pe visible ho
        android.view.MenuItem deleteAll = menu.findItem(R.id.action_delete_all_chats);
        if (deleteAll != null) deleteAll.setVisible(pos == TAB_CHATS);
        // "Broadcast List" sirf Chat tab pe visible ho
        android.view.MenuItem broadcast = menu.findItem(R.id.action_broadcast_list);
        if (broadcast != null) broadcast.setVisible(pos == TAB_CHATS);
        // "Performance" report sirf Chat tab pe visible ho
        android.view.MenuItem performance = menu.findItem(R.id.action_performance_report);
        if (performance != null) performance.setVisible(pos == TAB_CHATS);
        // "Ultra Advanced Diagnostics" sirf Chat tab pe visible ho
        android.view.MenuItem ultraDiag = menu.findItem(R.id.action_ultra_diagnostics);
        if (ultraDiag != null) ultraDiag.setVisible(pos == TAB_CHATS);
        // v251: X / YouTube / Games — moved here from the Chats tab quick-access
        // row, so sirf Chat tab pe hi visible ho (same as the row used to be).
        android.view.MenuItem openX = menu.findItem(R.id.action_open_x);
        if (openX != null) openX.setVisible(pos == TAB_CHATS);
        android.view.MenuItem openYoutube = menu.findItem(R.id.action_open_youtube);
        if (openYoutube != null) openYoutube.setVisible(pos == TAB_CHATS);
        android.view.MenuItem openGames = menu.findItem(R.id.action_open_games);
        if (openGames != null) openGames.setVisible(pos == TAB_CHATS);
        // "About" sirf Chat tab pe visible ho
        android.view.MenuItem about = menu.findItem(R.id.action_about);
        if (about != null) about.setVisible(pos == TAB_CHATS);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();

        // ── Broadcast List entry point ──────────────────────────────────────
        if (id == R.id.action_broadcast_list) {
            startActivity(new Intent(this,
                com.callx.app.broadcast.BroadcastListsActivity.class));
            return true;
        }

        if (id == R.id.action_performance_report) {
            startActivity(new Intent(this, PerformanceReportActivity.class));
            return true;
        }

        if (id == R.id.action_ultra_diagnostics) {
            startActivity(new Intent(this, UltraDiagnosticsActivity.class));
            return true;
        }

        // v251: X / YouTube / Games — moved here from the Chats tab
        // quick-access row (header_quick_access removed from
        // fragment_chats.xml). Same target Activities the row used to open.
        if (id == R.id.action_open_x) {
            try {
                startActivity(new Intent(this, com.callx.app.feed.XActivity.class));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "X not available",
                    android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        if (id == R.id.action_open_youtube) {
            try {
                startActivity(new Intent(this, com.callx.app.home.YouTubeActivity.class));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "YouTube not available",
                    android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        if (id == R.id.action_open_games) {
            try {
                startActivity(new Intent(this, com.callx.app.hub.GamesHubActivity.class));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "Games coming soon!",
                    android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        if (id == R.id.action_about) {
            // Kotlin AboutActivity — app module ka pehla Kotlin screen
            startActivity(new Intent(this, com.callx.app.activities.AboutActivity.class));
            return true;
        }

        if (id == R.id.action_settings) {
            startActivity(new Intent(this, AccountMenuActivity.class));
            overridePendingTransition(0, 0);
            return true;
        }

        if (id == R.id.action_payments) {
            startActivity(new Intent(this,
                    com.callx.app.payments.ui.PaymentsHomeActivity.class));
            overridePendingTransition(0, 0);
            return true;
        }

        if (id == R.id.action_delete_all_chats) {
            // ChatsFragment ko reflect karo aur confirmDeleteAll() call karo
            try {
                androidx.fragment.app.Fragment frag =
                    getSupportFragmentManager()
                        .findFragmentByTag("f" + binding.viewPager.getAdapter().getItemId(TAB_CHATS));
                if (frag != null) {
                    java.lang.reflect.Method m = frag.getClass().getMethod("confirmDeleteAll");
                    m.invoke(frag);
                }
            } catch (Exception e) {
                android.widget.Toast.makeText(this,
                    "Delete All not available", android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
