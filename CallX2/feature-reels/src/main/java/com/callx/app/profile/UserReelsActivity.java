package com.callx.app.profile;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.followers.FollowersListActivity;
import com.callx.app.followers.FollowingListActivity;
import com.callx.app.followers.MutualFollowersActivity;

import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.tabs.TabLayout;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.profile.ReelGridAdapter;
import com.callx.app.analytics.ReelCreatorDashboardActivity;
import com.callx.app.profile.ReelEditProfileActivity;
import com.callx.app.creator.ReelCreatorHubActivity;
import com.callx.app.analytics.ReelAnalyticsBottomSheet;
import com.callx.app.models.ReelModel;
  import com.callx.app.models.DuetSeriesModel;
  import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/**
 * UserReelsActivity — Full production reel profile screen.
 *
 * SCROLLING FIX v2:
 *  - Removed NestedScrollView entirely.
 *  - Profile header, tabs now live ABOVE the SwipeRefreshLayout + RecyclerView.
 *  - RecyclerView gets match_parent height and owns all scrolling.
 *  - Pagination uses RecyclerView.OnScrollListener (no NestedScrollView needed).
 *  - SwipeRefreshLayout enabled only when RV is at top (canScrollVertically(-1) == false).
 */
public class UserReelsActivity extends AppCompatActivity
        implements ReelGridAdapter.LongPressListener,
                   ReelGridAdapter.MultiSelectChangeListener {

    public static final String EXTRA_UID   = "uid";
    public static final String EXTRA_NAME  = "name";
    public static final String EXTRA_PHOTO = "photo";

    private static final int PAGE_SIZE  = 12;
    private static final int TAB_REELS  = 0;
    private static final int TAB_LIKED  = 1;
    private static final int TAB_SAVED  = 2;
    private static final int TAB_REPOST = 3;
      private static final int TAB_SERIES = 4;

    // Views
    private CircleImageView ivAvatar;
    private ImageView       ivVerified;
    private View            viewStoryRing;
    private TextView        tvName, tvReelCount, tvFollowers, tvFollowing, tvBio;
    private TextView        tvMutualFollowers;
    private LinearLayout    layoutMutualFollowers;
    private CircleImageView ivMutual1, ivMutual2, ivMutual3;
    private List<String>    mutualUidsList = new ArrayList<>();
    private TextView        tvPhone, tvWhatsapp, tvInstagram, tvYoutube, tvOtherLink;
    private View            layoutPhone, layoutWhatsapp, layoutInstagram, layoutYoutube, layoutOtherLink;
    private TextView        tvEmptyTitle, tvEmptySubtitle;
    private Button          btnFollow;
    private ImageButton     btnBack, btnMore, btnShareProfile, btnCreatorHub, btnSettings;
    private ImageButton     btnMessage, btnAudioCall, btnVideoCall, btnOpenX, btnOpenYoutube;
    private LinearLayout    layoutActions;

    // ── Avatar peek animation fields ──────────────────────────────────────
    private CircleImageView ivAnimChat, ivAnimX, ivAnimYoutube;
    private final Handler   animHandler    = new Handler(Looper.getMainLooper());
    private Runnable        animRunnable;
    private boolean         animRunning    = false;
    private TabLayout       tabLayout;
    private RecyclerView    rvReels;
      private RecyclerView    rvSeries;
    private ReelGridAdapter       adapter;
      private UserSeriesGridAdapter seriesAdapter;
    private ProgressBar     progressBar;
    private View            layoutEmpty;
    private SwipeRefreshLayout swipeRefresh;
    private View            layoutMultiSelectBar;
    private TextView        tvSelectedCount;
    private ImageButton     btnShareSelected, btnDeleteSelected, btnCancelSelect;
    private View            layoutPrivateAccount;
    private View            layoutFollowersClick;
    private View            layoutFollowingClick;

    // State
    private String  targetUid, targetName, targetPhoto;
    private boolean isFollowing      = false;
    private boolean isMultiSelect    = false;
    private boolean isSelf           = false;
    private boolean isAccountPrivate = false;
    private int     activeTab        = TAB_REELS;

    private final List<ReelModel> reelsTabData   = new ArrayList<>();
    private final List<ReelModel> likedTabData   = new ArrayList<>();
    private final List<ReelModel> savedTabData   = new ArrayList<>();
    private final List<ReelModel> repostsTabData = new ArrayList<>();
    private final Set<String>     selectedReelIds = new HashSet<>();
      private final java.util.List<DuetSeriesModel> seriesTabData = new ArrayList<>();
      private boolean seriesLoaded = false;

    private String  reelsLastKey = null, likedLastKey = null,
                    savedLastKey = null, repostsLastKey = null;
    private boolean reelsHasMore = true, likedHasMore = true,
                    savedHasMore = true, repostsHasMore = true;
    private boolean isLoadingMore = false;

    private ReelModel         pinnedReel = null;
    private Dialog            previewDialog;
    private ExoPlayer         previewPlayer;
    private GridLayoutManager gridLayoutManager;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_reels);

        targetUid   = getIntent().getStringExtra(EXTRA_UID);
        targetName  = getIntent().getStringExtra(EXTRA_NAME);
        targetPhoto = getIntent().getStringExtra(EXTRA_PHOTO);
        if (targetUid == null || targetUid.isEmpty()) { finish(); return; }

        isSelf = targetUid.equals(safeMyUid());

        bindViews();
        setupHeader();
        setupSwipeRefresh();
        setupScrollPagination();
        setupTabs();
        setupMultiSelectBar();
        loadUserProfile();
        loadFollowState();
        loadVerifiedStatus();
        loadMutualFollowers();
        loadPinnedReel();
        loadCurrentTab(true);
        loadReelCount();
        checkActiveStory();
        loadAccountPrivacy();
        setupStatsClicks();
        loadAvatarAndStartAnimation();
    }

    // ── Bind views ────────────────────────────────────────────────────────

    private void bindViews() {
        ivAvatar             = findViewById(R.id.iv_avatar);
        ivVerified           = findViewById(R.id.iv_verified);
        viewStoryRing        = findViewById(R.id.view_story_ring);
        tvName               = findViewById(R.id.tv_name);
        tvReelCount          = findViewById(R.id.tv_reel_count);
        tvFollowers          = findViewById(R.id.tv_followers);
        tvFollowing          = findViewById(R.id.tv_following);
        tvBio                = findViewById(R.id.tv_bio);
        tvMutualFollowers    = findViewById(R.id.tv_mutual_followers);
        layoutMutualFollowers= findViewById(R.id.layout_mutual_followers);
        ivMutual1            = findViewById(R.id.iv_mutual_1);
        ivMutual2            = findViewById(R.id.iv_mutual_2);
        ivMutual3            = findViewById(R.id.iv_mutual_3);
        tvEmptyTitle         = findViewById(R.id.tv_empty_title);
        tvEmptySubtitle      = findViewById(R.id.tv_empty_subtitle);
        btnFollow            = findViewById(R.id.btn_follow);
        btnBack              = findViewById(R.id.btn_back);
        btnShareProfile      = findViewById(R.id.btn_share_profile);
        btnCreatorHub        = findViewById(R.id.btn_creator_hub);
        btnSettings          = findViewById(R.id.btn_settings);
        btnMore              = findViewById(R.id.btn_more);
        btnMessage           = findViewById(R.id.btn_message);
        btnAudioCall         = findViewById(R.id.btn_audio_call);
        btnVideoCall         = findViewById(R.id.btn_video_call);
        btnOpenX             = findViewById(R.id.btn_open_x);
        btnOpenYoutube       = findViewById(R.id.btn_open_youtube);
        ivAnimChat           = findViewById(R.id.iv_anim_chat);
        ivAnimX              = findViewById(R.id.iv_anim_x);
        ivAnimYoutube        = findViewById(R.id.iv_anim_youtube);
        layoutActions        = findViewById(R.id.layout_actions);
        tabLayout            = findViewById(R.id.tab_layout);
        rvReels              = findViewById(R.id.rv_reels);
          rvSeries             = findViewById(R.id.rv_series);
        progressBar          = findViewById(R.id.progress_bar);
        layoutEmpty          = findViewById(R.id.layout_empty);
        swipeRefresh         = findViewById(R.id.swipe_refresh);
        layoutMultiSelectBar = findViewById(R.id.layout_multi_select_bar);
        tvSelectedCount      = findViewById(R.id.tv_selected_count);
        btnShareSelected     = findViewById(R.id.btn_share_selected);
        layoutPrivateAccount = findViewById(R.id.layout_private_account);
        layoutFollowersClick = findViewById(R.id.layout_followers_click);
        layoutFollowingClick = findViewById(R.id.layout_following_click);
        btnDeleteSelected    = findViewById(R.id.btn_delete_selected);
        btnCancelSelect      = findViewById(R.id.btn_cancel_select);
        tvPhone          = findViewById(R.id.tv_phone);
        tvWhatsapp       = findViewById(R.id.tv_whatsapp);
        tvInstagram      = findViewById(R.id.tv_instagram);
        tvYoutube        = findViewById(R.id.tv_youtube);
        tvOtherLink      = findViewById(R.id.tv_other_link);
        layoutPhone      = findViewById(R.id.layout_phone);
        layoutWhatsapp   = findViewById(R.id.layout_whatsapp);
        layoutInstagram  = findViewById(R.id.layout_instagram);
        layoutYoutube    = findViewById(R.id.layout_youtube);
        layoutOtherLink  = findViewById(R.id.layout_other_link);
    }

    // ── Header ────────────────────────────────────────────────────────────

    private void setupHeader() {
        btnBack.setOnClickListener(v -> {
            if (isMultiSelect) { exitMultiSelectMode(); return; }
            finish();
        });

        if (targetName  != null) tvName.setText(targetName);
        if (targetPhoto != null && !targetPhoto.isEmpty())
            Glide.with(this).load(targetPhoto).circleCrop()
                .placeholder(R.drawable.ic_person).into(ivAvatar);

        if (btnShareProfile != null) btnShareProfile.setOnClickListener(v -> shareProfile());

        if (btnCreatorHub != null) {
            btnCreatorHub.setVisibility(isSelf ? View.VISIBLE : View.GONE);
            if (isSelf) btnCreatorHub.setOnClickListener(v ->
                startActivity(new Intent(this, ReelCreatorHubActivity.class)));
        }

        if (btnSettings != null) {
            btnSettings.setVisibility(isSelf ? View.VISIBLE : View.GONE);
            if (isSelf) btnSettings.setOnClickListener(v -> {
                // Reel profile edit — reels/users/{uid} node
                startActivity(new Intent(this, ReelEditProfileActivity.class));
            });
        }

        adapter = new ReelGridAdapter(
              this, activeTabData(),
            pos -> { if (isMultiSelect) toggleSelection(pos); else openPlayerAt(pos); },
            this, this
        );

        // Series tab setup
        seriesAdapter = new UserSeriesGridAdapter(this);
        if (rvSeries != null) {
            rvSeries.setLayoutManager(new GridLayoutManager(this, 2));
            rvSeries.setAdapter(seriesAdapter);
            seriesAdapter.setOnSeriesClickListener(series -> {
                Intent si = new Intent(this, com.callx.app.social.DuetSeriesActivity.class);
                si.putExtra(com.callx.app.social.DuetSeriesActivity.EXTRA_SERIES_ID, series.seriesId);
                startActivity(si);
            });
        }
        adapter.setShowViewsOverlay(isSelf);

        gridLayoutManager = new GridLayoutManager(this, 3);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == ReelGridAdapter.TYPE_PINNED ? 3 : 1;
            }
        });

        rvReels.setLayoutManager(gridLayoutManager);
        rvReels.setAdapter(adapter);
        // KEY FIX: RecyclerView must NOT have nested scrolling disabled.
        // It lives directly inside SwipeRefreshLayout (no NestedScrollView wrapper),
        // so it scrolls normally on its own.
        rvReels.setNestedScrollingEnabled(true);
        rvReels.setHasFixedSize(false);

        if (layoutActions != null) layoutActions.setVisibility(isSelf ? View.GONE : View.VISIBLE);
        if (btnFollow     != null) btnFollow.setVisibility(isSelf ? View.GONE : View.VISIBLE);

        setupActionButtons();
        setupMoreMenu();

        if (ivAvatar != null) {
            ivAvatar.setOnClickListener(v -> openStatusIfAvailable());
            ivAvatar.setOnLongClickListener(v -> { showAvatarZoom(targetPhoto, targetName); return true; });
        }
    }

    // ── SwipeRefresh ──────────────────────────────────────────────────────

    private void setupSwipeRefresh() {
        if (swipeRefresh == null) return;
        swipeRefresh.setColorSchemeResources(R.color.brand_primary);
        swipeRefresh.setOnRefreshListener(() -> {
            exitMultiSelectMode();
            loadCurrentTab(true);
            if (activeTab == TAB_REELS) loadPinnedReel();
        });
        // Initially enabled; scroll listener will toggle it
        swipeRefresh.setEnabled(true);
    }

    // ── Scroll listener for pagination + SwipeRefresh guard ───────────────

    /**
     * SCROLLING FIX: RecyclerView.OnScrollListener handles both:
     *  1. Disabling SwipeRefresh when not at top (prevents gesture conflict)
     *  2. Triggering pagination when near the bottom
     *
     * No NestedScrollView needed — RecyclerView scrolls freely.
     */
    private void setupScrollPagination() {
        rvReels.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // Allow pull-to-refresh only when list is fully at top
                if (swipeRefresh != null) {
                    swipeRefresh.setEnabled(!rv.canScrollVertically(-1));
                }

                // Pagination trigger: load next page when 6 items from end
                if (isLoadingMore) return;
                if (!getCurrentTabHasMore()) return;
                int total       = gridLayoutManager.getItemCount();
                int lastVisible = gridLayoutManager.findLastVisibleItemPosition();
                if (lastVisible >= total - 6) {
                    loadCurrentTab(false);
                }
            }
        });
    }

    private boolean getCurrentTabHasMore() {
        switch (activeTab) {
            case TAB_LIKED:  return likedHasMore;
            case TAB_SAVED:  return savedHasMore;
            case TAB_REPOST: return repostsHasMore;
            default:         return reelsHasMore;
        }
    }

    // ── Tabs ──────────────────────────────────────────────────────────────

    private void setupTabs() {
        if (tabLayout == null) return;
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                activeTab = tab.getPosition();
                exitMultiSelectMode();
                // Show/hide rvSeries vs rvReels depending on active tab
                boolean isSeries = (activeTab == TAB_SERIES);
                if (rvSeries != null) rvSeries.setVisibility(isSeries ? android.view.View.VISIBLE : android.view.View.GONE);
                if (rvReels  != null) rvReels.setVisibility(isSeries ? android.view.View.GONE : android.view.View.VISIBLE);
                if (isSeries) {
                    loadSeriesTab(false);
                    return;
                }
                adapter.notifyDataSetChanged();
                if (activeTabData().isEmpty()) loadCurrentTab(true);
                else refreshEmptyState();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {
                rvReels.smoothScrollToPosition(0);
            }
        });
    }

    private List<ReelModel> activeTabData() {
        switch (activeTab) {
            case TAB_LIKED:  return likedTabData;
            case TAB_SAVED:  return savedTabData;
            case TAB_REPOST: return repostsTabData;
            default:         return reelsTabData;
        }
    }

    // ── Privacy ───────────────────────────────────────────────────────────

    private void loadAccountPrivacy() {
        if (isSelf) return;
        FirebaseUtils.getUserRef(targetUid).child("isPrivate")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    isAccountPrivate = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                    applyPrivacyState();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void applyPrivacyState() {
        if (isFinishing() || isDestroyed()) return;
        boolean blocked = isAccountPrivate && !isFollowing && !isSelf;
        if (layoutPrivateAccount != null)
            layoutPrivateAccount.setVisibility(blocked ? View.VISIBLE : View.GONE);
        if (rvReels    != null) rvReels.setVisibility(blocked ? View.GONE : View.VISIBLE);
        if (tabLayout  != null) { tabLayout.setAlpha(blocked ? 0.4f : 1f); tabLayout.setEnabled(!blocked); }
        if (swipeRefresh != null) swipeRefresh.setEnabled(!blocked);
    }

    // ── Stats clicks ──────────────────────────────────────────────────────

    private void setupStatsClicks() {
        if (layoutFollowersClick != null)
            layoutFollowersClick.setOnClickListener(v -> openFollowersList());
        else if (tvFollowers != null)
            tvFollowers.setOnClickListener(v -> openFollowersList());

        if (layoutFollowingClick != null)
            layoutFollowingClick.setOnClickListener(v -> openFollowingList());
        else if (tvFollowing != null)
            tvFollowing.setOnClickListener(v -> openFollowingList());
    }

    private void openFollowersList() {
        if (isAccountPrivate && !isFollowing && !isSelf) {
            Toast.makeText(this, "This account is private", Toast.LENGTH_SHORT).show(); return;
        }
        Intent i = new Intent(this, FollowersListActivity.class);
        i.putExtra(FollowersListActivity.EXTRA_UID,  targetUid);
        i.putExtra(FollowersListActivity.EXTRA_NAME, targetName != null ? targetName : "");
        startActivity(i);
    }

    private void openFollowingList() {
        if (isAccountPrivate && !isFollowing && !isSelf) {
            Toast.makeText(this, "This account is private", Toast.LENGTH_SHORT).show(); return;
        }
        Intent i = new Intent(this, FollowingListActivity.class);
        i.putExtra(FollowingListActivity.EXTRA_UID,     targetUid);
        i.putExtra(FollowingListActivity.EXTRA_NAME,    targetName != null ? targetName : "");
        i.putExtra(FollowingListActivity.EXTRA_IS_SELF, isSelf);
        startActivity(i);
    }

    // ── Story Ring (Feature 14) ───────────────────────────────────────────

    private void checkActiveStory() {
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        FirebaseUtils.getUserStatusRef(targetUid)
            .orderByChild("timestamp").startAt((double) cutoff).limitToFirst(1)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    showStoryRing(snap.exists() && snap.getChildrenCount() > 0);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void showStoryRing(boolean show) {
        if (isFinishing() || isDestroyed() || viewStoryRing == null) return;
        viewStoryRing.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(viewStoryRing, "alpha", 0.6f, 1f);
            pulse.setDuration(900);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.setRepeatMode(ObjectAnimator.REVERSE);
            pulse.start();
        }
    }

    private void openStatusIfAvailable() {
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        FirebaseUtils.getUserStatusRef(targetUid)
            .orderByChild("timestamp").startAt((double) cutoff).limitToFirst(1)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (snap.exists() && snap.getChildrenCount() > 0) {
                        try {
                            Class<?> cls = Class.forName("com.callx.app.viewer.StatusViewerActivity");
                            Intent i = new Intent(UserReelsActivity.this, cls);
                            i.putExtra("ownerUid",  targetUid);
                            i.putExtra("ownerName", targetName != null ? targetName : "");
                            startActivity(i);
                        } catch (ClassNotFoundException e) {
                            Toast.makeText(UserReelsActivity.this, "Status viewer unavailable", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Share Profile (Feature 8) ─────────────────────────────────────────

    private void shareProfile() {
        String deepLink = com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/profile/" + targetUid;
        String name     = targetName != null ? targetName : "a creator";
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "Check out " + name + "'s Reels on CallX!\n" + deepLink);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Profile link", deepLink));
        startActivity(Intent.createChooser(share, "Share Profile"));
    }

    // ── Verified Badge (Feature 9) ────────────────────────────────────────

    private void loadVerifiedStatus() {
        FirebaseUtils.getUserRef(targetUid).child("isVerified")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (ivVerified != null)
                        ivVerified.setVisibility(
                            Boolean.TRUE.equals(snap.getValue(Boolean.class))
                                ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Mutual Followers (Feature 10) ─────────────────────────────────────

    private void loadMutualFollowers() {
        String myUid = safeMyUid();
        if (myUid == null || isSelf) return;
        FirebaseUtils.getReelFollowersRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot mySnap) {
                    Set<String> mine = new HashSet<>();
                    for (DataSnapshot s : mySnap.getChildren()) mine.add(s.getKey());
                    FirebaseUtils.getReelFollowersRef(targetUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot tSnap) {
                                mutualUidsList.clear();
                                for (DataSnapshot s : tSnap.getChildren())
                                    if (mine.contains(s.getKey())) mutualUidsList.add(s.getKey());
                                if (mutualUidsList.isEmpty()) {
                                    showMutualFollowers(new ArrayList<>(), new ArrayList<>());
                                    return;
                                }
                                // Fetch name + photo of first 3 mutual users
                                int fetchCount = Math.min(3, mutualUidsList.size());
                                List<String> names  = new ArrayList<>();
                                List<String> photos = new ArrayList<>();
                                final int[] done = {0};
                                for (int i = 0; i < fetchCount; i++) {
                                    String uid = mutualUidsList.get(i);
                                    FirebaseUtils.getUserRef(uid)
                                        .addListenerForSingleValueEvent(new ValueEventListener() {
                                            @Override public void onDataChange(@NonNull DataSnapshot us) {
                                                String n = us.child("name").getValue(String.class);
                                                String thumb = us.child("thumbUrl").getValue(String.class);
                                                String photo = us.child("photoUrl").getValue(String.class);
                                                String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                                                names.add(n != null ? n : "User");
                                                photos.add(p != null ? p : "");
                                                done[0]++;
                                                if (done[0] >= fetchCount)
                                                    showMutualFollowers(names, photos);
                                            }
                                            @Override public void onCancelled(@NonNull DatabaseError e) {
                                                names.add("User"); photos.add("");
                                                done[0]++;
                                                if (done[0] >= fetchCount)
                                                    showMutualFollowers(names, photos);
                                            }
                                        });
                                }
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void showMutualFollowers(List<String> names, List<String> photos) {
        if (layoutMutualFollowers == null || isFinishing() || isDestroyed()) return;
        int count = mutualUidsList.size();
        if (count <= 0) {
            layoutMutualFollowers.setVisibility(View.GONE);
            return;
        }

        // ── Load avatars (up to 3, overlapping: avatar1=front, avatar3=back) ──
        CircleImageView[] ivs = {ivMutual1, ivMutual2, ivMutual3};
        for (int i = 0; i < 3; i++) {
            if (ivs[i] == null) continue;
            if (i < photos.size() && !photos.get(i).isEmpty()) {
                ivs[i].setVisibility(View.VISIBLE);
                Glide.with(this).load(photos.get(i))
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .circleCrop()
                    .into(ivs[i]);
            } else if (i < names.size()) {
                ivs[i].setVisibility(View.VISIBLE);
                ivs[i].setImageResource(R.drawable.ic_person);
            } else {
                ivs[i].setVisibility(View.GONE);
            }
        }

        // ── Build text: "Followed by name1, name2 and X others" ──
        String text;
        if (count == 1) {
            text = "Followed by " + names.get(0);
        } else if (count == 2) {
            text = "Followed by " + names.get(0) + " and " + names.get(1);
        } else {
            int others = count - 2;
            text = "Followed by " + names.get(0) + ", " + names.get(1)
                + " and " + others + (others == 1 ? " other" : " others");
        }

        if (tvMutualFollowers != null) tvMutualFollowers.setText(text);
        layoutMutualFollowers.setVisibility(View.VISIBLE);
        layoutMutualFollowers.setOnClickListener(v -> openMutualFollowers());
    }

    private void openMutualFollowers() {
        if (mutualUidsList.isEmpty()) return;
        Intent i = new Intent(this, MutualFollowersActivity.class);
        i.putStringArrayListExtra(MutualFollowersActivity.EXTRA_UIDS,
                new ArrayList<>(mutualUidsList));
        i.putExtra(MutualFollowersActivity.EXTRA_TARGET_NAME,
                targetName != null ? targetName : "");
        startActivity(i);
    }

    // ── Pinned Reel (Feature 6) ───────────────────────────────────────────

    private void loadPinnedReel() {
        FirebaseDatabase.getInstance().getReference("reelPinned").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String pinnedId = snap.getValue(String.class);
                    if (pinnedId == null || pinnedId.isEmpty()) return;
                    FirebaseUtils.getReelsRef().child(pinnedId)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot s) {
                                ReelModel r = s.getValue(ReelModel.class);
                                if (r != null && activeTab == TAB_REELS) {
                                    pinnedReel = r;
                                    adapter.setPinnedReel(r);
                                }
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void pinReel(String reelId) {
        FirebaseDatabase.getInstance().getReference("reelPinned")
            .child(targetUid).setValue(reelId)
            .addOnSuccessListener(v -> {
                Toast.makeText(this, "Reel pinned!", Toast.LENGTH_SHORT).show();
                loadPinnedReel();
            });
    }

    private void unpinReel() {
        FirebaseDatabase.getInstance().getReference("reelPinned")
            .child(targetUid).removeValue()
            .addOnSuccessListener(v -> {
                pinnedReel = null;
                adapter.setPinnedReel(null);
                Toast.makeText(this, "Pinned reel removed", Toast.LENGTH_SHORT).show();
            });
    }

    // ── Data loading ──────────────────────────────────────────────────────

    private void loadCurrentTab(boolean refresh) {
        switch (activeTab) {
            case TAB_LIKED:  loadLikedReels(refresh);    break;
            case TAB_SAVED:  loadSavedReels(refresh);    break;
            case TAB_REPOST: loadRepostedReels(refresh); break;
            case TAB_SERIES: loadSeriesTab(refresh);     break;
            default:         loadUserReels(refresh);     break;
        }
    }

    private void loadUserReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { reelsLastKey = null; reelsHasMore = true; reelsTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getReelsByUserRef(targetUid), reelsLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) reelsHasMore = false;
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_REELS); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) reelsLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, reelsTabData, refresh, TAB_REELS);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_REELS); }
        });
    }

    private void loadLikedReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { likedLastKey = null; likedHasMore = true; likedTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getReelLikedByUserRef(targetUid), likedLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) likedHasMore = false;
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_LIKED); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) likedLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, likedTabData, refresh, TAB_LIKED);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_LIKED); }
        });
    }

    private void loadSavedReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { savedLastKey = null; savedHasMore = true; savedTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getReelSavesRef(targetUid), savedLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) savedHasMore = false;
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_SAVED); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) savedLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, savedTabData, refresh, TAB_SAVED);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_SAVED); }
        });
    }

    
      // ── Duet Series tab ────────────────────────────────────────────────────
      private void loadSeriesTab(boolean refresh) {
          if (seriesLoaded && !refresh) return;
          if (rvSeries == null) return;
          seriesLoaded = false;
          seriesTabData.clear();
          seriesAdapter.setItems(seriesTabData);

          if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

          com.google.firebase.database.FirebaseDatabase.getInstance(Constants.DB_URL)
              .getReference("userDuetSeries")
              .child(targetUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override
                  public void onDataChange(@NonNull DataSnapshot titlesSnap) {
                      if (isFinishing() || isDestroyed()) return;
                      if (!titlesSnap.exists() || titlesSnap.getChildrenCount() == 0) {
                          if (progressBar != null) progressBar.setVisibility(View.GONE);
                          if (layoutEmpty != null) {
                              tvEmptyTitle.setText("No Series Yet");
                              tvEmptySubtitle.setText("This creator hasn't started a Duet Series");
                              layoutEmpty.setVisibility(View.VISIBLE);
                          }
                          seriesLoaded = true;
                          return;
                      }
                      if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);

                      // Fetch each seriesId's full DuetSeriesModel
                      java.util.List<DuetSeriesModel> fetched = new java.util.ArrayList<>();
                      long[] remaining = {titlesSnap.getChildrenCount()};

                      for (DataSnapshot s : titlesSnap.getChildren()) {
                          String seriesId = s.getKey();
                          if (seriesId == null) { remaining[0]--; continue; }
                          com.google.firebase.database.FirebaseDatabase.getInstance(Constants.DB_URL)
                              .getReference("duetSeries").child(seriesId)
                              .addListenerForSingleValueEvent(new ValueEventListener() {
                                  @Override
                                  public void onDataChange(@NonNull DataSnapshot seriesSnap) {
                                      if (isFinishing() || isDestroyed()) return;
                                      DuetSeriesModel m = seriesSnap.getValue(DuetSeriesModel.class);
                                      if (m != null) fetched.add(m);
                                      remaining[0]--;
                                      if (remaining[0] <= 0) {
                                          // Sort by newest first
                                          fetched.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
                                          seriesTabData.addAll(fetched);
                                          seriesAdapter.setItems(seriesTabData);
                                          if (progressBar != null) progressBar.setVisibility(View.GONE);
                                          if (layoutEmpty != null)
                                              layoutEmpty.setVisibility(fetched.isEmpty() ? View.VISIBLE : View.GONE);
                                          seriesLoaded = true;
                                      }
                                  }
                                  @Override
                                  public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                                      remaining[0]--;
                                  }
                              });
                      }
                  }
                  @Override
                  public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                      if (progressBar != null) progressBar.setVisibility(View.GONE);
                  }
              });
      }

  private void loadRepostedReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { repostsLastKey = null; repostsHasMore = true; repostsTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getReelRepostsByUserRef(targetUid), repostsLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) repostsHasMore = false;
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_REPOST); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) repostsLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, repostsTabData, refresh, TAB_REPOST);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_REPOST); }
        });
    }

    private Query buildQuery(DatabaseReference ref, String lastKey) {
        return lastKey == null
            ? ref.orderByKey().limitToLast(PAGE_SIZE)
            : ref.orderByKey().endBefore(lastKey).limitToLast(PAGE_SIZE);
    }

    private List<String> extractIds(DataSnapshot snap) {
        List<String> ids = new ArrayList<>();
        for (DataSnapshot s : snap.getChildren()) ids.add(s.getKey());
        Collections.reverse(ids);
        return ids;
    }

    private void showSkeleton() {
        adapter.setSkeletonMode(true);
        adapter.notifyDataSetChanged();
        if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
    }

    private void fetchAndAppend(List<String> ids, List<ReelModel> target,
                                boolean refresh, int tab) {
        final int[] remaining = {ids.size()};
        final List<ReelModel> fetched = new ArrayList<>();
        for (String id : ids) {
            FirebaseUtils.getReelsRef().child(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!isFinishing() && !isDestroyed()) {
                            ReelModel r = snap.getValue(ReelModel.class);
                            if (r != null) fetched.add(r);
                        }
                        if (--remaining[0] == 0) {
                            fetched.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                            target.addAll(fetched);
                            finishLoading(refresh, tab);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (--remaining[0] == 0) finishLoading(refresh, tab);
                    }
                });
        }
    }

    private void finishLoading(boolean refresh, int tab) {
        if (isFinishing() || isDestroyed()) return;
        isLoadingMore = false;
        adapter.setSkeletonMode(false);
        adapter.notifyDataSetChanged();
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        if (progressBar  != null) progressBar.setVisibility(View.GONE);
        if (tab == activeTab) refreshEmptyState();
    }

    private void refreshEmptyState() {
        if (activeTab == TAB_SERIES) {
            boolean empty = seriesTabData.isEmpty();
            if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (rvSeries != null) rvSeries.setVisibility(empty ? View.GONE : View.VISIBLE);
            rvReels.setVisibility(View.GONE);
            if (tvEmptyTitle != null) tvEmptyTitle.setText("No Series Yet");
            if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Create a Duet Series to post numbered episodes.");
            return;
        }
        boolean empty = activeTabData().isEmpty() && !adapter.hasPinned();
        if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvReels.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tvEmptyTitle == null) return;
        switch (activeTab) {
            case TAB_LIKED:
                tvEmptyTitle.setText("No Liked Reels");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Liked reels will appear here."); break;
            case TAB_SAVED:
                tvEmptyTitle.setText("No Saved Reels");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Saved reels will appear here."); break;
            case TAB_REPOST:
                tvEmptyTitle.setText("No Reposted Reels");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Reposted reels will appear here."); break;
            default:
                tvEmptyTitle.setText("No Reels Yet");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("This creator hasn't posted any reels yet.");
        }
    }

    // ── Open player ───────────────────────────────────────────────────────

    private void openPlayerAt(int adapterPos) {
        // Pinned reel occupies position 0 in adapter — skip it when calculating reel index
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;
        if (reelIdx < 0) reelIdx = 0;

        List<ReelModel> data = activeTabData();
        if (data.isEmpty()) return;

        // Clamp to valid range
        int safeIdx = Math.min(reelIdx, data.size() - 1);

        // Build ordered ID list from current sorted data (latest first already)
        ArrayList<String> ids = new ArrayList<>();
        for (ReelModel r : data)
            if (r != null && r.reelId != null) ids.add(r.reelId);

        Intent intent = new Intent(this, SingleReelPlayerActivity.class);
        intent.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
        // safeIdx ensures the tapped reel plays first — not position 0
        intent.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, safeIdx);
        intent.putExtra(SingleReelPlayerActivity.EXTRA_TITLE,
            targetName != null ? targetName + "'s Reels" : "Reels");
        startActivity(intent);
    }

    // ── Long press ────────────────────────────────────────────────────────

    @Override
    public void onLongPress(int adapterPos) {
        List<ReelModel> data = activeTabData();
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;

        if (isSelf && activeTab == TAB_REELS && reelIdx >= 0 && reelIdx < data.size()) {
            showAnalyticsSheet(data.get(reelIdx), adapterPos);
            return;
        }
        if (reelIdx < 0 || reelIdx >= data.size()) { enterMultiSelectMode(adapterPos); return; }
        ReelModel reel = data.get(reelIdx);
        if (reel.videoUrl == null || reel.videoUrl.isEmpty()) { enterMultiSelectMode(adapterPos); return; }
        showVideoPreviewDialog(reel, adapterPos);
    }

    // ── Analytics sheet (Feature 15) ──────────────────────────────────────

    private void showAnalyticsSheet(ReelModel reel, int adapterPos) {
        new AlertDialog.Builder(this)
            .setTitle("Reel Options")
            .setItems(new String[]{"View Insights", "Pin Reel", "Share", "Delete"}, (d, which) -> {
                switch (which) {
                    case 0:
                        ReelAnalyticsBottomSheet.newInstance(reel)
                            .show(getSupportFragmentManager(), "analytics"); break;
                    case 1: pinReel(reel.reelId); break;
                    case 2: shareProfile(); break;
                    case 3: confirmDeleteSingleReel(reel); break;
                }
            }).show();
    }

    private void confirmDeleteSingleReel(ReelModel reel) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Reel")
            .setMessage("This reel will be permanently deleted.")
            .setPositiveButton("Delete", (d, w) -> {
                FirebaseUtils.getReelsRef().child(reel.reelId).removeValue();
                FirebaseUtils.getReelsByUserRef(targetUid).child(reel.reelId).removeValue();
                if (pinnedReel != null && reel.reelId.equals(pinnedReel.reelId)) unpinReel();
                reelsTabData.remove(reel);
                adapter.notifyDataSetChanged();
                refreshEmptyState();
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ── Video preview dialog (Feature 4) ──────────────────────────────────

    private void showVideoPreviewDialog(ReelModel reel, int adapterPos) {
        dismissPreviewDialog();
        previewDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        previewDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        previewDialog.setContentView(R.layout.dialog_reel_preview);
        Window w = previewDialog.getWindow();
        if (w != null) w.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

        PlayerView playerView = previewDialog.findViewById(R.id.preview_player_view);
        TextView   tvCap      = previewDialog.findViewById(R.id.tv_preview_caption);
        TextView   tvDur      = previewDialog.findViewById(R.id.tv_preview_duration);
        View       btnSelect  = previewDialog.findViewById(R.id.btn_preview_select);
        View       btnPlay    = previewDialog.findViewById(R.id.btn_preview_play);

        if (tvCap != null && reel.caption != null && !reel.caption.isEmpty()) {
            tvCap.setText(reel.caption); tvCap.setVisibility(View.VISIBLE);
        }
        if (tvDur != null && reel.duration > 0) {
            int s = (reel.duration / 1000) % 60, m = reel.duration / 60000;
            tvDur.setText(String.format(Locale.getDefault(), "%d:%02d", m, s));
        }

        previewPlayer = new ExoPlayer.Builder(this).build();
        previewPlayer.setVolume(0f);
        previewPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        if (playerView != null) playerView.setPlayer(previewPlayer);
        previewPlayer.setMediaItem(MediaItem.fromUri(reel.videoUrl));
        previewPlayer.prepare();
        previewPlayer.setPlayWhenReady(true);

        if (btnSelect != null) btnSelect.setOnClickListener(v -> {
            dismissPreviewDialog(); enterMultiSelectMode(adapterPos);
        });
        if (btnPlay != null) btnPlay.setOnClickListener(v -> {
            dismissPreviewDialog(); openPlayerAt(adapterPos);
        });
        previewDialog.setOnDismissListener(d -> dismissPreviewDialog());
        previewDialog.show();
    }

    private void dismissPreviewDialog() {
        if (previewPlayer != null) { previewPlayer.release(); previewPlayer = null; }
        if (previewDialog != null && previewDialog.isShowing()) previewDialog.dismiss();
        previewDialog = null;
    }

    // ── Multi-select (Feature 5) ──────────────────────────────────────────

    private void setupMultiSelectBar() {
        if (layoutMultiSelectBar == null) return;
        if (btnCancelSelect  != null) btnCancelSelect.setOnClickListener(v -> exitMultiSelectMode());
        if (btnShareSelected != null) btnShareSelected.setOnClickListener(v -> shareSelectedReels());
        if (btnDeleteSelected != null) btnDeleteSelected.setOnClickListener(v -> deleteSelectedReels());
    }

    private void enterMultiSelectMode(int initialPos) {
        isMultiSelect = true;
        adapter.setMultiSelectMode(true);
        if (layoutMultiSelectBar != null) layoutMultiSelectBar.setVisibility(View.VISIBLE);
        toggleSelection(initialPos);
    }

    private void exitMultiSelectMode() {
        isMultiSelect = false;
        selectedReelIds.clear();
        adapter.setMultiSelectMode(false);
        adapter.clearSelections();
        if (layoutMultiSelectBar != null) layoutMultiSelectBar.setVisibility(View.GONE);
        if (tvSelectedCount != null) tvSelectedCount.setText("0 Selected");
    }

    private void toggleSelection(int adapterPos) {
        List<ReelModel> data = activeTabData();
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;
        if (reelIdx < 0 || reelIdx >= data.size()) return;
        String reelId = data.get(reelIdx).reelId;
        if (selectedReelIds.contains(reelId)) {
            selectedReelIds.remove(reelId); adapter.setSelected(adapterPos, false);
        } else {
            selectedReelIds.add(reelId); adapter.setSelected(adapterPos, true);
        }
        adapter.notifyItemChanged(adapterPos);
        if (tvSelectedCount != null) tvSelectedCount.setText(selectedReelIds.size() + " Selected");
        if (selectedReelIds.isEmpty()) exitMultiSelectMode();
    }

    @Override
    public void onSelectionChanged(int count) {
        if (tvSelectedCount != null) tvSelectedCount.setText(count + " Selected");
    }

    private void shareSelectedReels() {
        if (selectedReelIds.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (ReelModel r : activeTabData())
            if (selectedReelIds.contains(r.reelId) && r.videoUrl != null)
                sb.append(r.videoUrl).append("\n");
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, sb.toString().trim());
        startActivity(Intent.createChooser(share, "Share Reels"));
        exitMultiSelectMode();
    }

    private void deleteSelectedReels() {
        String myUid = safeMyUid();
        if (myUid == null || selectedReelIds.isEmpty()) return;
        if (!targetUid.equals(myUid)) {
            Toast.makeText(this, "You can only delete your own reels", Toast.LENGTH_SHORT).show(); return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Delete Reels")
            .setMessage("Delete " + selectedReelIds.size() + " reel(s)? This cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> {
                for (String id : new HashSet<>(selectedReelIds)) {
                    FirebaseUtils.getReelsRef().child(id).removeValue();
                    FirebaseUtils.getReelsByUserRef(myUid).child(id).removeValue();
                    if (pinnedReel != null && id.equals(pinnedReel.reelId)) unpinReel();
                }
                activeTabData().removeIf(r -> selectedReelIds.contains(r.reelId));
                exitMultiSelectMode();
                adapter.notifyDataSetChanged();
                refreshEmptyState();
                Toast.makeText(this, "Deleted successfully", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ── Action buttons ────────────────────────────────────────────────────

    private void setupActionButtons() {
        if (btnMessage != null) btnMessage.setOnClickListener(v ->
            launchActivity("com.callx.app.conversation.ChatActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto"},
                new String[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto)}));

        if (btnAudioCall != null) btnAudioCall.setOnClickListener(v -> {
            String cid = FirebaseDatabase.getInstance().getReference("calls").push().getKey();
            launchActivity("com.callx.app.call.CallActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto","isCaller","video","callId"},
                new Object[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto), true, false, orEmpty(cid)});
        });
        if (btnVideoCall != null) btnVideoCall.setOnClickListener(v -> {
            String cid = FirebaseDatabase.getInstance().getReference("calls").push().getKey();
            launchActivity("com.callx.app.call.CallActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto","isCaller","video","callId"},
                new Object[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto), true, true, orEmpty(cid)});
        });

        // X profile button
        if (btnOpenX != null) btnOpenX.setOnClickListener(v -> {
            if (targetUid == null || targetUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.profile.XProfileSheet");
                java.lang.reflect.Method method = cls.getMethod("showProfile",
                        androidx.fragment.app.FragmentManager.class, String.class);
                method.invoke(null, getSupportFragmentManager(), targetUid);
            } catch (Exception e) {
                Toast.makeText(this, "X profile not available", Toast.LENGTH_SHORT).show();
            }
        });

        // YouTube channel button
        if (btnOpenYoutube != null) btnOpenYoutube.setOnClickListener(v -> {
            if (targetUid == null || targetUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.channel.YouTubeChannelActivity");
                Intent i = new Intent(this, cls);
                i.putExtra("uid",  targetUid);
                i.putExtra("name", orEmpty(targetName));
                startActivity(i);
            } catch (ClassNotFoundException e) {
                Toast.makeText(this, "YouTube channel not available", Toast.LENGTH_SHORT).show();
            }
        });

        if (btnFollow != null) btnFollow.setOnClickListener(v -> toggleFollow());
    }

    // ── Avatar Peek Animation ─────────────────────────────────────────────
    /**
     * Teen alag avatars load karte hain — har button ke liye user ki respective profile image:
     *   - Chat button    → users/{uid}          (main CallX chat profile)
     *   - X button       → x/users/{uid}         (X / Twitter profile)
     *   - YouTube button → youtube/channels/{uid} (YouTube channel)
     * Agar koi platform profile nahi hai to ic_person placeholder rahega.
     * Phir teeno avatars pe loop animation start hoti hai (peek out → hold → peek in → repeat).
     */
    private void loadAvatarAndStartAnimation() {
        if (targetUid == null || isSelf) return;

        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";

        // 1) Chat avatar — users/{uid}
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("users").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb
                               : (photo != null && !photo.isEmpty()) ? photo : null;
                    if (ivAnimChat == null) return;
                    if (url != null) {
                        Glide.with(UserReelsActivity.this)
                            .load(url).circleCrop()
                            .placeholder(R.drawable.ic_person)
                            .into(ivAnimChat);
                    }
                    // Start animation only after first avatar loaded (others load in bg)
                    startAvatarPeekLoop();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    startAvatarPeekLoop(); // Still start loop even if load fails
                }
            });

        // 2) X avatar — x/users/{uid}
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("x/users").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb
                               : (photo != null && !photo.isEmpty()) ? photo : null;
                    if (ivAnimX == null || url == null) return;
                    Glide.with(UserReelsActivity.this)
                        .load(url).circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .into(ivAnimX);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        // 3) YouTube avatar — youtube/channels/{uid}
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("youtube/channels").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb
                               : (photo != null && !photo.isEmpty()) ? photo : null;
                    if (ivAnimYoutube == null || url == null) return;
                    Glide.with(UserReelsActivity.this)
                        .load(url).circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .into(ivAnimYoutube);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * Loop: Chat → X → YouTube → Chat → ...
     * Each cycle: peek out (600ms) → hold 3s → peek in (600ms) → wait 3s → next button
     */
    private void startAvatarPeekLoop() {
        if (animRunning) return;
        animRunning = true;

        CircleImageView[] views = {ivAnimChat, ivAnimX, ivAnimYoutube};

        // Initialize all: hidden, scaled to 0, centered on button
        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f);
            iv.setScaleY(0f);
            iv.setAlpha(0f);
        }

        animRunnable = new Runnable() {
            int idx = 0;

            @Override public void run() {
                if (!animRunning || isFinishing() || isDestroyed()) return;

                CircleImageView iv = views[idx % views.length];
                idx++;

                if (iv == null) {
                    animHandler.postDelayed(this, 500);
                    return;
                }

                // Reset to hidden/zero state
                iv.setScaleX(0f);
                iv.setScaleY(0f);
                iv.setAlpha(0f);
                iv.setVisibility(View.VISIBLE);

                // Zoom IN: scale 0 → 1.05 (subtle overshoot) then settle to 1.0, alpha 0 → 1
                ObjectAnimator scaleXIn  = ObjectAnimator.ofFloat(iv, "scaleX", 0f, 1.05f, 1.0f);
                ObjectAnimator scaleYIn  = ObjectAnimator.ofFloat(iv, "scaleY", 0f, 1.05f, 1.0f);
                ObjectAnimator alphaIn   = ObjectAnimator.ofFloat(iv, "alpha",  0f, 1f);
                scaleXIn.setDuration(450);
                scaleYIn.setDuration(450);
                alphaIn.setDuration(250);
                scaleXIn.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
                scaleYIn.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));

                AnimatorSet zoomIn = new AnimatorSet();
                zoomIn.playTogether(scaleXIn, scaleYIn, alphaIn);

                // Zoom OUT: scale 1.0 → 0, alpha 1 → 0  (after 3s hold)
                ObjectAnimator scaleXOut = ObjectAnimator.ofFloat(iv, "scaleX", 1.0f, 0f);
                ObjectAnimator scaleYOut = ObjectAnimator.ofFloat(iv, "scaleY", 1.0f, 0f);
                ObjectAnimator alphaOut  = ObjectAnimator.ofFloat(iv, "alpha",  1f, 0f);
                scaleXOut.setDuration(400);
                scaleYOut.setDuration(400);
                alphaOut.setDuration(400);
                scaleXOut.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
                scaleYOut.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));

                AnimatorSet zoomOut = new AnimatorSet();
                zoomOut.playTogether(scaleXOut, scaleYOut, alphaOut);
                zoomOut.setStartDelay(3000); // hold visible for 3 seconds

                AnimatorSet full = new AnimatorSet();
                full.playSequentially(zoomIn, zoomOut);
                full.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        iv.setVisibility(View.INVISIBLE);
                        iv.setScaleX(0f);
                        iv.setScaleY(0f);
                        iv.setAlpha(0f);
                        // 3 second gap then next button
                        if (animRunning && !isFinishing() && !isDestroyed())
                            animHandler.postDelayed(animRunnable, 3000);
                    }
                });
                full.start();
            }
        };

        animHandler.postDelayed(animRunnable, 1500);
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private void stopAvatarAnimation() {
        animRunning = false;
        animHandler.removeCallbacks(animRunnable);
        CircleImageView[] views = {ivAnimChat, ivAnimX, ivAnimYoutube};
        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f);
            iv.setScaleY(0f);
            iv.setAlpha(0f);
        }
    }

    private void launchActivity(String className, String[] keys, String[] values) {
        try {
            Class<?> cls = Class.forName(className);
            Intent i = new Intent(this, cls);
            for (int x = 0; x < keys.length; x++) i.putExtra(keys[x], values[x]);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchActivity(String className, String[] keys, Object[] values) {
        try {
            Class<?> cls = Class.forName(className);
            Intent i = new Intent(this, cls);
            for (int x = 0; x < keys.length; x++) {
                if (values[x] instanceof Boolean) i.putExtra(keys[x], (boolean) values[x]);
                else i.putExtra(keys[x], (String) values[x]);
            }
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Follow ────────────────────────────────────────────────────────────

    private void loadFollowState() {
        String myUid = safeMyUid();
        if (myUid == null || isSelf) return;
        FirebaseUtils.getReelFollowsRef(myUid).child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    isFollowing = snap.exists() && Boolean.TRUE.equals(snap.getValue(Boolean.class));
                    updateFollowButton();
                    applyPrivacyState();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void toggleFollow() {
        String myUid = safeMyUid();
        if (myUid == null) return;
        isFollowing = !isFollowing;
        updateFollowButton();
        applyPrivacyState();
        if (isFollowing) {
            FirebaseUtils.getReelFollowsRef(myUid).child(targetUid).setValue(true);
            FirebaseUtils.getReelFollowersRef(targetUid).child(myUid).setValue(true);
            updateFollowerCountUI(1);
        } else {
            FirebaseUtils.getReelFollowsRef(myUid).child(targetUid).removeValue();
            FirebaseUtils.getReelFollowersRef(targetUid).child(myUid).removeValue();
            updateFollowerCountUI(-1);
        }
    }

    private void updateFollowButton() {
        if (btnFollow == null) return;
        if (isFollowing) {
            btnFollow.setText("Following");
            btnFollow.setBackgroundColor(0xFF333333);
            btnFollow.setTextColor(0xFFCCCCCC);
        } else {
            try { btnFollow.setBackgroundColor(getResources().getColor(R.color.brand_primary, null)); }
            catch (Exception e) { btnFollow.setBackgroundColor(0xFF6C5CE7); }
            btnFollow.setTextColor(0xFFFFFFFF);
            btnFollow.setText("Follow");
        }
    }

    private void updateFollowerCountUI(int delta) {
        if (tvFollowers == null) return;
        try {
            int cur = Integer.parseInt(tvFollowers.getText().toString().split(" ")[0]);
            tvFollowers.setText(String.valueOf(cur + delta));
        } catch (Exception ignored) {}
    }

    // ── Profile data ──────────────────────────────────────────────────────

    private void loadUserProfile() {
        // Reels profile load karo (reels/users/{uid}) — chat profile nahi
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String name      = snap.child("displayName").getValue(String.class);
                String photo     = snap.child("photoUrl").getValue(String.class);
                String photoThumb = snap.child("thumbUrl").getValue(String.class);
                String bio       = snap.child("bio").getValue(String.class);
                String website   = snap.child("website").getValue(String.class);
                String instagram = snap.child("instagramHandle").getValue(String.class);
                String youtube   = snap.child("youtubeChannelUrl").getValue(String.class);
                String twitter   = snap.child("twitterHandle").getValue(String.class);

                if (name != null) { targetName = name; if (tvName != null) tvName.setText(name); }
                if (photo != null && !photo.isEmpty()) {
                    targetPhoto = photo;
                    String displayPhoto = (photoThumb != null && !photoThumb.isEmpty()) ? photoThumb : photo;
                    Glide.with(UserReelsActivity.this).load(displayPhoto).circleCrop()
                        .placeholder(R.drawable.ic_person).into(ivAvatar);
                }

                // Bio
                if (tvBio != null) {
                    tvBio.setText(bio != null ? bio : "");
                    tvBio.setVisibility(bio != null && !bio.isEmpty() ? View.VISIBLE : View.GONE);
                }

                // Website / social links from Reels profile
                bindSocialRow(layoutPhone, tvPhone, website,
                    !isEmpty(website) ? (website.startsWith("http") ? website : "https://" + website) : null,
                    website);

                String waNum = null; // Reels profile mein WhatsApp nahi hota
                bindSocialRow(layoutWhatsapp, tvWhatsapp, null, null, null);

                // Instagram
                String igHandle = !isEmpty(instagram)
                    ? (instagram.startsWith("http") ? instagram : "https://instagram.com/" + instagram.replace("@",""))
                    : null;
                bindSocialRow(layoutInstagram, tvInstagram, instagram, igHandle,
                    !isEmpty(instagram) ? (instagram.startsWith("@") ? instagram : "@" + instagram) : null);

                // YouTube
                bindSocialRow(layoutYoutube, tvYoutube, youtube, youtube, youtube);

                // Twitter/X
                String otherUrl = !isEmpty(twitter)
                    ? (twitter.startsWith("http") ? twitter : "https://x.com/" + twitter.replace("@",""))
                    : null;
                bindSocialRow(layoutOtherLink, tvOtherLink, twitter, otherUrl, twitter);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
        FirebaseUtils.getReelFollowersRef(targetUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (tvFollowers != null) tvFollowers.setText(String.valueOf(snap.getChildrenCount()));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
        FirebaseUtils.getReelFollowsRef(targetUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (tvFollowing != null) tvFollowing.setText(String.valueOf(snap.getChildrenCount()));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }


    // ── Social link helper ──────────────────────────────────────────────
    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    private void bindSocialRow(View rowLayout, TextView tv, String rawValue, String url, String displayText) {
        if (rowLayout == null || tv == null) return;
        if (isEmpty(rawValue)) {
            rowLayout.setVisibility(View.GONE);
            return;
        }
        rowLayout.setVisibility(View.VISIBLE);
        tv.setText(displayText != null ? displayText : rawValue);
        rowLayout.setOnClickListener(v -> {
            if (url == null) return;
            try {
                android.net.Uri uri = android.net.Uri.parse(url);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(UserReelsActivity.this, "Cannot open link", Toast.LENGTH_SHORT).show();
            }
        });
        rowLayout.setOnLongClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("copy", rawValue));
            Toast.makeText(UserReelsActivity.this, "Copied!", Toast.LENGTH_SHORT).show();
            return true;
        });
    }


    private void loadReelCount() {
        FirebaseUtils.getReelsByUserRef(targetUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                long n = snap.getChildrenCount();
                if (tvReelCount != null) tvReelCount.setText(String.valueOf(n));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ── More menu ─────────────────────────────────────────────────────────

    private void setupMoreMenu() {
        if (btnMore == null) return;
        btnMore.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, btnMore);
            menu.getMenu().add(0, 1, 0, "Share Profile");
            menu.getMenu().add(0, 2, 0, "Copy Profile Link");
            if (isSelf)  menu.getMenu().add(0, 5, 0, "Creator Dashboard");
            if (isSelf && pinnedReel != null) menu.getMenu().add(0, 4, 0, "Remove Pinned Reel");
            if (!isSelf) menu.getMenu().add(0, 3, 0, "Report User");
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: shareProfile(); break;
                    case 2:
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Link",
                            com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/profile/" + targetUid));
                        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show(); break;
                    case 3: Toast.makeText(this, "Report submitted. Thank you.", Toast.LENGTH_SHORT).show(); break;
                    case 4: unpinReel(); break;
                    case 5: startActivity(new Intent(this, ReelCreatorDashboardActivity.class)); break;
                }
                return true;
            });
            menu.show();
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }

    private String orEmpty(String s) { return s != null ? s : ""; }

    // ── Avatar zoom dialog ────────────────────────────────────────────────

    private void showAvatarZoom(String photoUrl, String name) {
        if (isFinishing() || isDestroyed()) return;
        android.app.Dialog dialog = new android.app.Dialog(
            this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xEE000000);

        com.github.chrisbanes.photoview.PhotoView photoView =
            new com.github.chrisbanes.photoview.PhotoView(this);
        android.widget.FrameLayout.LayoutParams ivLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        photoView.setLayoutParams(ivLp);
        photoView.setMinimumScale(1f); photoView.setMediumScale(2f); photoView.setMaximumScale(5f);
        photoView.setOnOutsidePhotoTapListener(v -> dialog.dismiss());

        android.widget.ImageButton btnClose = new android.widget.ImageButton(this);
        int dp40 = (int)(40 * getResources().getDisplayMetrics().density);
        int dp16 = (int)(16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout.LayoutParams closeLp =
            new android.widget.FrameLayout.LayoutParams(dp40, dp40);
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.topMargin = dp40; closeLp.rightMargin = dp16;
        btnClose.setLayoutParams(closeLp);
        btnClose.setImageResource(R.drawable.ic_close);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        android.widget.TextView tvZoomName = new android.widget.TextView(this);
        tvZoomName.setText(name != null ? name : "");
        tvZoomName.setTextColor(0xFFFFFFFF); tvZoomName.setTextSize(15f);
        tvZoomName.setGravity(Gravity.CENTER);
        int dp32 = (int)(32 * getResources().getDisplayMetrics().density);
        tvZoomName.setPadding(0, 0, 0, dp32);
        android.widget.FrameLayout.LayoutParams nameLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        nameLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tvZoomName.setLayoutParams(nameLp);

        if (photoUrl != null && !photoUrl.isEmpty())
            Glide.with(this).load(photoUrl).placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person).into(photoView);
        else photoView.setImageResource(R.drawable.ic_person);

        root.addView(photoView); root.addView(tvZoomName); root.addView(btnClose);
        dialog.setContentView(root);
        android.view.Window dw = dialog.getWindow();
        if (dw != null) dw.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        dialog.show();
    }

    @Override protected void onPause()   { super.onPause();   dismissPreviewDialog(); stopAvatarAnimation(); }
    @Override protected void onResume()  { super.onResume();  loadAvatarAndStartAnimation(); }
    @Override protected void onDestroy() { super.onDestroy(); dismissPreviewDialog(); stopAvatarAnimation(); }
}
