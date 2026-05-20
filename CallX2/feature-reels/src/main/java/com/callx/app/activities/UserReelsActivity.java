package com.callx.app.activities;

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
import com.callx.app.adapters.ReelGridAdapter;
import com.callx.app.activities.ReelCreatorDashboardActivity;
import com.callx.app.activities.ReelCreatorHubActivity;
import com.callx.app.fragments.ReelAnalyticsBottomSheet;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

import java.util.*;

/**
 * UserReelsActivity — Full production reel profile screen.
 *
 * SCROLLING FIX v2:
 *  - Removed NestedScrollView wrapping the entire content.
 *  - Profile header is a fixed top section; RecyclerView takes all remaining height.
 *  - SwipeRefreshLayout wraps only the RecyclerView.
 *  - Pagination uses RecyclerView.OnScrollListener — no NestedScrollView conflict.
 *  - rvReels.setNestedScrollingEnabled(true) — RecyclerView owns its scrolling.
 */
public class UserReelsActivity extends AppCompatActivity
        implements ReelGridAdapter.LongPressListener,
                   ReelGridAdapter.MultiSelectChangeListener {

    public static final String EXTRA_UID   = "uid";
    public static final String EXTRA_NAME  = "name";
    public static final String EXTRA_PHOTO = "photo";

    private static final int PAGE_SIZE = 12;
    private static final int TAB_REELS  = 0;
    private static final int TAB_LIKED  = 1;
    private static final int TAB_SAVED  = 2;
    private static final int TAB_REPOST = 3;

    // ── Views ─────────────────────────────────────────────────────────────
    private CircleImageView ivAvatar;
    private ImageView       ivVerified;
    private View            viewStoryRing;
    private TextView        tvName, tvReelCount, tvFollowers, tvFollowing, tvBio;
    private TextView        tvMutualFollowers;
    private TextView        tvEmptyTitle, tvEmptySubtitle;
    private Button          btnFollow;
    private ImageButton     btnBack, btnMore, btnShareProfile, btnCreatorHub, btnSettings;
    private ImageButton     btnMessage, btnAudioCall, btnVideoCall, btnViewStatus;
    private LinearLayout    layoutActions;
    private TabLayout       tabLayout;
    private RecyclerView    rvReels;
    private ReelGridAdapter adapter;
    private ProgressBar     progressBar;
    private View            layoutEmpty;
    private SwipeRefreshLayout swipeRefresh;

    private View        layoutMultiSelectBar;
    private TextView    tvSelectedCount;
    private ImageButton btnShareSelected, btnDeleteSelected, btnCancelSelect;

    // ── State ─────────────────────────────────────────────────────────────
    private String  targetUid, targetName, targetPhoto;
    private boolean isFollowing   = false;
    private boolean isMultiSelect = false;
    private boolean isSelf           = false;
    private boolean isAccountPrivate = false;
    private View    layoutPrivateAccount;
    private View    layoutFollowersClick;
    private View    layoutFollowingClick;
    private int     activeTab     = TAB_REELS;

    private final List<ReelModel> reelsTabData   = new ArrayList<>();
    private final List<ReelModel> likedTabData   = new ArrayList<>();
    private final List<ReelModel> savedTabData   = new ArrayList<>();
    private final List<ReelModel> repostsTabData = new ArrayList<>();
    private final Set<String>     selectedReelIds = new HashSet<>();

    private String  reelsLastKey = null, likedLastKey = null, savedLastKey = null, repostsLastKey = null;
    private boolean reelsHasMore = true, likedHasMore = true, savedHasMore = true, repostsHasMore = true;
    private boolean isLoadingMore = false;

    private ReelModel pinnedReel   = null;
    private Dialog    previewDialog;
    private ExoPlayer previewPlayer;
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
        btnViewStatus        = findViewById(R.id.btn_view_status);
        layoutActions        = findViewById(R.id.layout_actions);
        tabLayout            = findViewById(R.id.tab_layout);
        rvReels              = findViewById(R.id.rv_reels);
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
    }

    // ── Header setup ──────────────────────────────────────────────────────

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
                try {
                    Class<?> cls = Class.forName("com.callx.app.activities.AccountMenuActivity");
                    startActivity(new Intent(UserReelsActivity.this, cls));
                } catch (ClassNotFoundException e) {
                    Toast.makeText(UserReelsActivity.this, "Not available", Toast.LENGTH_SHORT).show();
                }
            });
        }

        adapter = new ReelGridAdapter(
            this, activeTabData(),
            pos -> {
                if (isMultiSelect) { toggleSelection(pos); return; }
                openPlayerAt(pos);
            },
            this, this
        );
        adapter.setShowViewsOverlay(isSelf);

        gridLayoutManager = new GridLayoutManager(this, 3);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == ReelGridAdapter.TYPE_PINNED ? 3 : 1;
            }
        });
        rvReels.setLayoutManager(gridLayoutManager);
        rvReels.setAdapter(adapter);

        // KEY FIX: RecyclerView must own scrolling — do NOT disable nested scrolling
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

    // ── Private/Public Account Logic ──────────────────────────────────────

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
        if (layoutPrivateAccount != null) layoutPrivateAccount.setVisibility(blocked ? View.VISIBLE : View.GONE);
        if (rvReels   != null) rvReels.setVisibility(blocked ? View.GONE : View.VISIBLE);
        if (tabLayout != null) { tabLayout.setAlpha(blocked ? 0.4f : 1f); tabLayout.setEnabled(!blocked); }
        if (swipeRefresh != null) swipeRefresh.setEnabled(!blocked);
    }

    // ── Followers / Following stats tap ───────────────────────────────────

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

    // ── Feature 14: Story Ring ────────────────────────────────────────────

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
                            Class<?> cls = Class.forName("com.callx.app.activities.StatusViewerActivity");
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

    // ── Share Profile ─────────────────────────────────────────────────────

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

    // ── Tabs ──────────────────────────────────────────────────────────────

    private void setupTabs() {
        if (tabLayout == null) return;
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                activeTab = tab.getPosition();
                exitMultiSelectMode();
                adapter.notifyDataSetChanged();
                if (activeTabData().isEmpty()) loadCurrentTab(true);
                else refreshEmptyState();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) { rvReels.smoothScrollToPosition(0); }
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

    // ── Verified Badge ────────────────────────────────────────────────────

    private void loadVerifiedStatus() {
        FirebaseUtils.getUserRef(targetUid).child("isVerified")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (ivVerified != null)
                        ivVerified.setVisibility(Boolean.TRUE.equals(snap.getValue(Boolean.class)) ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Mutual Followers ──────────────────────────────────────────────────

    private void loadMutualFollowers() {
        String myUid = safeMyUid();
        if (myUid == null || isSelf) return;
        FirebaseUtils.getReelFollowersRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot mySnap) {
                Set<String> mine = new HashSet<>();
                for (DataSnapshot s : mySnap.getChildren()) mine.add(s.getKey());
                FirebaseUtils.getReelFollowersRef(targetUid).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot tSnap) {
                        int mutual = 0;
                        for (DataSnapshot s : tSnap.getChildren()) if (mine.contains(s.getKey())) mutual++;
                        showMutualFollowers(mutual);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void showMutualFollowers(int count) {
        if (tvMutualFollowers == null || isFinishing() || isDestroyed()) return;
        if (count <= 0) { tvMutualFollowers.setVisibility(View.GONE); return; }
        tvMutualFollowers.setText(count == 1 ? "Followed by 1 person you know"
            : "Followed by " + count + " people you know");
        tvMutualFollowers.setVisibility(View.VISIBLE);
    }

    // ── Pinned Reel ───────────────────────────────────────────────────────

    private void loadPinnedReel() {
        FirebaseDatabase.getInstance().getReference("reelPinned").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String pinnedId = snap.getValue(String.class);
                    if (pinnedId == null || pinnedId.isEmpty()) return;
                    FirebaseUtils.getReelsRef().child(pinnedId).addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            ReelModel r = s.getValue(ReelModel.class);
                            if (r != null && activeTab == TAB_REELS) { pinnedReel = r; adapter.setPinnedReel(r); }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void pinReel(String reelId) {
        FirebaseDatabase.getInstance().getReference("reelPinned").child(targetUid).setValue(reelId)
            .addOnSuccessListener(v -> { Toast.makeText(this, "Reel pinned!", Toast.LENGTH_SHORT).show(); loadPinnedReel(); });
    }

    private void unpinReel() {
        FirebaseDatabase.getInstance().getReference("reelPinned").child(targetUid).removeValue()
            .addOnSuccessListener(v -> {
                pinnedReel = null; adapter.setPinnedReel(null);
                Toast.makeText(this, "Pinned reel removed", Toast.LENGTH_SHORT).show();
            });
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
    }

    // ── SCROLLING FIX: Pagination via RecyclerView.OnScrollListener ───────

    private void setupScrollPagination() {
        rvReels.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // Enable SwipeRefresh only when RecyclerView is fully at top
                if (swipeRefresh != null) {
                    swipeRefresh.setEnabled(!rv.canScrollVertically(-1));
                }

                // Trigger next page when within 6 items of bottom
                if (isLoadingMore) return;
                if (!getCurrentTabHasMore()) return;
                int totalItems  = gridLayoutManager.getItemCount();
                int lastVisible = gridLayoutManager.findLastVisibleItemPosition();
                if (lastVisible >= totalItems - 6) {
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

    // ── Load dispatcher ───────────────────────────────────────────────────

    private void loadCurrentTab(boolean refresh) {
        switch (activeTab) {
            case TAB_LIKED:  loadLikedReels(refresh);    break;
            case TAB_SAVED:  loadSavedReels(refresh);    break;
            case TAB_REPOST: loadRepostedReels(refresh); break;
            default:         loadUserReels(refresh);     break;
        }
    }

    // ── Load: Reels tab ───────────────────────────────────────────────────

    private void loadUserReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) {
            reelsLastKey = null; reelsHasMore = true; reelsTabData.clear();
            adapter.setSkeletonMode(true); adapter.notifyDataSetChanged();
            if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
        }
        Query q = reelsLastKey == null
            ? FirebaseUtils.getReelsByUserRef(targetUid).orderByKey().limitToLast(PAGE_SIZE)
            : FirebaseUtils.getReelsByUserRef(targetUid).orderByKey().endBefore(reelsLastKey).limitToLast(PAGE_SIZE);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) reelsHasMore = false;
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_REELS); return; }
                List<String> ids = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) ids.add(s.getKey());
                Collections.reverse(ids);
                if (!ids.isEmpty()) reelsLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, reelsTabData, refresh, TAB_REELS);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { if (!isFinishing()) finishLoading(refresh, TAB_REELS); }
        });
    }

    // ── Load: Liked tab ───────────────────────────────────────────────────

    private void loadLikedReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) {
            likedLastKey = null; likedHasMore = true; likedTabData.clear();
            adapter.setSkeletonMode(true); adapter.notifyDataSetChanged();
            if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
        }
        Query q = likedLastKey == null
            ? FirebaseUtils.getReelLikedByUserRef(targetUid).orderByKey().limitToLast(PAGE_SIZE)
            : FirebaseUtils.getReelLikedByUserRef(targetUid).orderByKey().endBefore(likedLastKey).limitToLast(PAGE_SIZE);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) likedHasMore = false;
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_LIKED); return; }
                List<String> ids = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) ids.add(s.getKey());
                Collections.reverse(ids);
                if (!ids.isEmpty()) likedLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, likedTabData, refresh, TAB_LIKED);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { if (!isFinishing()) finishLoading(refresh, TAB_LIKED); }
        });
    }

    // ── Load: Saved tab ───────────────────────────────────────────────────

    private void loadSavedReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) {
            savedLastKey = null; savedHasMore = true; savedTabData.clear();
            adapter.setSkeletonMode(true); adapter.notifyDataSetChanged();
            if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
        }
        Query q = savedLastKey == null
            ? FirebaseUtils.getReelSavesRef(targetUid).orderByKey().limitToLast(PAGE_SIZE)
            : FirebaseUtils.getReelSavesRef(targetUid).orderByKey().endBefore(savedLastKey).limitToLast(PAGE_SIZE);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) savedHasMore = false;
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_SAVED); return; }
                List<String> ids = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) ids.add(s.getKey());
                Collections.reverse(ids);
                if (!ids.isEmpty()) savedLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, savedTabData, refresh, TAB_SAVED);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { if (!isFinishing()) finishLoading(refresh, TAB_SAVED); }
        });
    }

    // ── Load: Repost tab ──────────────────────────────────────────────────

    private void loadRepostedReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) {
            repostsLastKey = null; repostsHasMore = true; repostsTabData.clear();
            adapter.setSkeletonMode(true); adapter.notifyDataSetChanged();
            if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
        }
        Query q = repostsLastKey == null
            ? FirebaseUtils.getReelRepostsByUserRef(targetUid).orderByKey().limitToLast(PAGE_SIZE)
            : FirebaseUtils.getReelRepostsByUserRef(targetUid).orderByKey().endBefore(repostsLastKey).limitToLast(PAGE_SIZE);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) repostsHasMore = false;
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_REPOST); return; }
                List<String> ids = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) ids.add(s.getKey());
                Collections.reverse(ids);
                if (!ids.isEmpty()) repostsLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, repostsTabData, refresh, TAB_REPOST);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { if (!isFinishing()) finishLoading(refresh, TAB_REPOST); }
        });
    }

    // ── Shared fetch ──────────────────────────────────────────────────────

    private void fetchAndAppend(List<String> ids, List<ReelModel> target, boolean refresh, int tab) {
        final int[] remaining = {ids.size()};
        final List<ReelModel> fetched = new ArrayList<>();
        for (String id : ids) {
            FirebaseUtils.getReelsRef().child(id).addListenerForSingleValueEvent(new ValueEventListener() {
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
        boolean empty = activeTabData().isEmpty() && !adapter.hasPinned();
        if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvReels.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tvEmptyTitle != null) {
            switch (activeTab) {
                case TAB_LIKED:  tvEmptyTitle.setText("No Liked Reels");
                    if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Liked reels will appear here."); break;
                case TAB_SAVED:  tvEmptyTitle.setText("No Saved Reels");
                    if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Saved reels will appear here."); break;
                case TAB_REPOST: tvEmptyTitle.setText("No Reposted Reels");
                    if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Reposted reels will appear here."); break;
                default:         tvEmptyTitle.setText("No Reels Yet");
                    if (tvEmptySubtitle != null) tvEmptySubtitle.setText("This creator hasn't posted any reels yet.");
            }
        }
    }

    // ── Open player ───────────────────────────────────────────────────────

    private void openPlayerAt(int adapterPos) {
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;
        if (reelIdx < 0) reelIdx = 0;
        Intent intent = new Intent(this, SingleReelPlayerActivity.class);
        intent.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, reelIdx);
        intent.putExtra(SingleReelPlayerActivity.EXTRA_TITLE,
            targetName != null ? targetName + "'s Reels" : "Reels");
        if (activeTab == TAB_REELS) {
            intent.putExtra(SingleReelPlayerActivity.EXTRA_UID, targetUid);
        } else {
            ArrayList<String> ids = new ArrayList<>();
            for (ReelModel r : activeTabData()) if (r != null && r.reelId != null) ids.add(r.reelId);
            intent.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
        }
        startActivity(intent);
    }

    // ── Long-press ────────────────────────────────────────────────────────

    @Override
    public void onLongPress(int adapterPos) {
        List<ReelModel> data = activeTabData();
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;
        if (isSelf && activeTab == TAB_REELS && reelIdx >= 0 && reelIdx < data.size()) {
            showAnalyticsSheet(data.get(reelIdx), adapterPos); return;
        }
        if (reelIdx < 0 || reelIdx >= data.size()) { enterMultiSelectMode(adapterPos); return; }
        ReelModel reel = data.get(reelIdx);
        if (reel.videoUrl == null || reel.videoUrl.isEmpty()) { enterMultiSelectMode(adapterPos); return; }
        showVideoPreviewDialog(reel, adapterPos);
    }

    // ── Analytics sheet ───────────────────────────────────────────────────

    private void showAnalyticsSheet(ReelModel reel, int adapterPos) {
        new AlertDialog.Builder(this)
            .setTitle("Reel Options")
            .setItems(new String[]{"View Insights", "Pin Reel", "Share", "Delete"}, (d, which) -> {
                switch (which) {
                    case 0: ReelAnalyticsBottomSheet.newInstance(reel).show(getSupportFragmentManager(), "analytics"); break;
                    case 1: pinReel(reel.reelId); break;
                    case 2: shareProfile(); break;
                    case 3: confirmDeleteSingleReel(reel); break;
                }
            }).show();
    }

    private void confirmDeleteSingleReel(ReelModel reel) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Reel").setMessage("This reel will be permanently deleted.")
            .setPositiveButton("Delete", (d, w) -> {
                FirebaseUtils.getReelsRef().child(reel.reelId).removeValue();
                FirebaseUtils.getReelsByUserRef(targetUid).child(reel.reelId).removeValue();
                if (pinnedReel != null && reel.reelId.equals(pinnedReel.reelId)) unpinReel();
                reelsTabData.remove(reel);
                adapter.notifyDataSetChanged();
                refreshEmptyState();
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Cancel", null).show();
    }

    // ── Video preview dialog ──────────────────────────────────────────────

    private void showVideoPreviewDialog(ReelModel reel, int adapterPos) {
        dismissPreviewDialog();
        previewDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        previewDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        previewDialog.setContentView(R.layout.dialog_reel_preview);
        Window w = previewDialog.getWindow();
        if (w != null) w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

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
        if (btnSelect != null) btnSelect.setOnClickListener(v -> { dismissPreviewDialog(); enterMultiSelectMode(adapterPos); });
        if (btnPlay   != null) btnPlay.setOnClickListener(v   -> { dismissPreviewDialog(); openPlayerAt(adapterPos); });
        previewDialog.setOnDismissListener(d -> dismissPreviewDialog());
        previewDialog.show();
    }

    private void dismissPreviewDialog() {
        if (previewPlayer != null) { previewPlayer.release(); previewPlayer = null; }
        if (previewDialog != null && previewDialog.isShowing()) previewDialog.dismiss();
        previewDialog = null;
    }

    // ── Multi-select ──────────────────────────────────────────────────────

    private void setupMultiSelectBar() {
        if (layoutMultiSelectBar == null) return;
        if (btnCancelSelect   != null) btnCancelSelect.setOnClickListener(v -> exitMultiSelectMode());
        if (btnShareSelected  != null) btnShareSelected.setOnClickListener(v -> shareSelectedReels());
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
            if (selectedReelIds.contains(r.reelId) && r.videoUrl != null) sb.append(r.videoUrl).append("\n");
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
            }).setNegativeButton("Cancel", null).show();
    }

    // ── Action buttons ────────────────────────────────────────────────────

    private void setupActionButtons() {
        if (btnMessage != null) btnMessage.setOnClickListener(v ->
            launchActivity("com.callx.app.activities.ChatActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto"},
                new String[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto)}));
        if (btnAudioCall != null) btnAudioCall.setOnClickListener(v -> {
            String cid = FirebaseDatabase.getInstance().getReference("calls").push().getKey();
            launchActivity("com.callx.app.activities.CallActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto","isCaller","video","callId"},
                new Object[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto), true, false, orEmpty(cid)});
        });
        if (btnVideoCall != null) btnVideoCall.setOnClickListener(v -> {
            String cid = FirebaseDatabase.getInstance().getReference("calls").push().getKey();
            launchActivity("com.callx.app.activities.CallActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto","isCaller","video","callId"},
                new Object[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto), true, true, orEmpty(cid)});
        });
        if (btnViewStatus != null) btnViewStatus.setOnClickListener(v -> openStatusIfAvailable());
        if (btnFollow     != null) btnFollow.setOnClickListener(v -> toggleFollow());
    }

    private void launchActivity(String className, String[] keys, String[] values) {
        try {
            Class<?> cls = Class.forName(className);
            Intent i = new Intent(this, cls);
            for (int x = 0; x < keys.length; x++) i.putExtra(keys[x], values[x]);
            startActivity(i);
        } catch (ClassNotFoundException e) { Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show(); }
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
        } catch (ClassNotFoundException e) { Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show(); }
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
            btnFollow.setText("Following"); btnFollow.setBackgroundColor(0xFF333333); btnFollow.setTextColor(0xFFCCCCCC);
        } else {
            try { btnFollow.setBackgroundColor(getResources().getColor(R.color.brand_primary, null)); }
            catch (Exception e) { btnFollow.setBackgroundColor(0xFF6C5CE7); }
            btnFollow.setTextColor(0xFFFFFFFF); btnFollow.setText("Follow");
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
        FirebaseUtils.getUserRef(targetUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String name  = snap.child("name").getValue(String.class);
                String photo = snap.child("photoUrl").getValue(String.class);
                String bio   = snap.child("bio").getValue(String.class);
                if (name  != null) { targetName = name; if (tvName != null) tvName.setText(name); }
                if (photo != null && !photo.isEmpty()) {
                    targetPhoto = photo;
                    Glide.with(UserReelsActivity.this).load(photo).circleCrop()
                        .placeholder(R.drawable.ic_person).into(ivAvatar);
                }
                if (tvBio != null) {
                    tvBio.setText(bio != null ? bio : "");
                    tvBio.setVisibility(bio != null && !bio.isEmpty() ? View.VISIBLE : View.GONE);
                }
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

    private void loadReelCount() {
        FirebaseUtils.getReelsByUserRef(targetUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                long n = snap.getChildrenCount();
                if (tvReelCount != null) tvReelCount.setText(n + (n == 1 ? " Reel" : " Reels"));
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
            if (isSelf) menu.getMenu().add(0, 5, 0, "Creator Dashboard");
            if (isSelf && pinnedReel != null) menu.getMenu().add(0, 4, 0, "Remove Pinned Reel");
            if (!isSelf) menu.getMenu().add(0, 3, 0, "Report User");
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: shareProfile(); break;
                    case 2:
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("Link",
                                com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/profile/" + targetUid));
                            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
                        }
                        break;
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

    // ── Avatar Zoom Dialog ────────────────────────────────────────────────

    private void showAvatarZoom(String photoUrl, String name) {
        if (isFinishing() || isDestroyed()) return;
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xEE000000);
        com.github.chrisbanes.photoview.PhotoView photoView = new com.github.chrisbanes.photoview.PhotoView(this);
        android.widget.FrameLayout.LayoutParams ivLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        photoView.setLayoutParams(ivLp);
        photoView.setMinimumScale(1f); photoView.setMediumScale(2f); photoView.setMaximumScale(5f);
        photoView.setOnOutsidePhotoTapListener(v -> dialog.dismiss());
        android.widget.ImageButton btnClose = new android.widget.ImageButton(this);
        int dp40 = (int)(40 * getResources().getDisplayMetrics().density);
        int dp16 = (int)(16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout.LayoutParams closeLp = new android.widget.FrameLayout.LayoutParams(dp40, dp40);
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.topMargin = dp40; closeLp.rightMargin = dp16;
        btnClose.setLayoutParams(closeLp);
        btnClose.setImageResource(R.drawable.ic_close);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        android.widget.TextView tvNameZoom = new android.widget.TextView(this);
        tvNameZoom.setText(name != null ? name : "");
        tvNameZoom.setTextColor(0xFFFFFFFF); tvNameZoom.setTextSize(15f);
        tvNameZoom.setGravity(Gravity.CENTER);
        tvNameZoom.setPadding(0, 0, 0, (int)(32 * getResources().getDisplayMetrics().density));
        android.widget.FrameLayout.LayoutParams nameLp = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        nameLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tvNameZoom.setLayoutParams(nameLp);
        if (photoUrl != null && !photoUrl.isEmpty())
            Glide.with(this).load(photoUrl).placeholder(R.drawable.ic_person).error(R.drawable.ic_person).into(photoView);
        else photoView.setImageResource(R.drawable.ic_person);
        root.addView(photoView); root.addView(tvNameZoom); root.addView(btnClose);
        dialog.setContentView(root);
        android.view.Window w = dialog.getWindow();
        if (w != null) w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        dialog.show();
    }

    @Override protected void onPause()   { super.onPause();   dismissPreviewDialog(); }
    @Override protected void onDestroy() { super.onDestroy(); dismissPreviewDialog(); }
}
