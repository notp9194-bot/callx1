package com.callx.app.profile;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.View;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.GridLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
  import com.google.android.material.tabs.TabLayout;

  import com.bumptech.glide.Glide;
  import com.callx.app.reels.R;
  import com.callx.app.models.ReelModel;
  import com.callx.app.player.SingleReelPlayerActivity;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.database.*;
  import de.hdodenhof.circleimageview.CircleImageView;

  import java.util.*;

  /**
   * AllReelsFullActivity - Instagram-style full reels grid.
   * 3 tabs: Reels / Liked / Saved with infinite scroll (18 per page).
   *
   * ROOT CAUSE FIX: activity_all_reels_full.xml referenced ic_star_outline
   * (non-existent drawable) causing an immediate crash on launch.
   * Replaced with ic_heart in the layout.
   */
  public class AllReelsFullActivity extends AppCompatActivity {

      public static final String EXTRA_UID   = "uid";
      public static final String EXTRA_NAME  = "name";
      public static final String EXTRA_PHOTO = "photo";
      public static final String EXTRA_TAB   = "tab";

      private static final int PAGE_SIZE = 18;
      private static final int TAB_REELS = 0;
      private static final int TAB_LIKED = 1;
      private static final int TAB_SAVED = 2;

      // Views
      private ImageButton        btnBack;
      private TextView           tvTitle;
      private CircleImageView    ivAvatar;
      private TabLayout          tabLayout;
      private RecyclerView       rvReels;
      private ProgressBar        progressBar;
      private ProgressBar        progressBarBottom;
      private View               layoutEmpty;
      private TextView           tvEmptyTitle;
      private TextView           tvEmptySub;
      private SwipeRefreshLayout swipeRefresh;

      // State
      private String  targetUid, targetName, targetPhoto;
      private int     activeTab     = TAB_REELS;
      private boolean isLoadingMore = false;

      // Per-tab caches
      private final List<ReelModel> reelsCache = new ArrayList<>();
      private final List<ReelModel> likedCache = new ArrayList<>();
      private final List<ReelModel> savedCache = new ArrayList<>();
      private final List<ReelModel> currentData = new ArrayList<>();

      // Pagination cursors
      private String  reelsLastKey = null, likedLastKey = null, savedLastKey = null;
      private boolean reelsHasMore = true,  likedHasMore = true,  savedHasMore = true;

      private ReelGridAdapter   adapter;
      private GridLayoutManager layoutManager;
      private ReelModel         pinnedReel = null;

      // Advance #2 — predictive prefetch: set true for one loader cycle
      // right after a ReelGridPrefetchCache hit so showLoader() doesn't
      // wipe the just-primed grid.
      private boolean suppressNextLoaderFlash = false;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_all_reels_full);

          targetUid   = getIntent().getStringExtra(EXTRA_UID);
          targetName  = getIntent().getStringExtra(EXTRA_NAME);
          targetPhoto = getIntent().getStringExtra(EXTRA_PHOTO);
          activeTab   = getIntent().getIntExtra(EXTRA_TAB, TAB_REELS);

          if (targetUid == null || targetUid.isEmpty()) { finish(); return; }

          bindViews();
          setupToolbar();
          setupGrid();
          setupTabs();
          setupSwipeRefresh();
          setupScrollPagination();
          loadPinnedReel();
          primeFromPrefetchCache();
          loadCurrentTab(true);
      }

      // Advance #2 — predictive prefetch: if UserReelsActivity already warmed
      // this exact (uid, tab) first page in ReelGridPrefetchCache, render it
      // immediately (no spinner) while loadCurrentTab(true) still refreshes
      // silently underneath for correctness.
      private void primeFromPrefetchCache() {
          List<ReelModel> prefetched = com.callx.app.cache.ReelGridPrefetchCache.get(targetUid, activeTab);
          if (prefetched == null || prefetched.isEmpty()) return;
          com.callx.app.cache.ReelGridPrefetchCache.consume(targetUid, activeTab);
          suppressNextLoaderFlash = true;
          List<ReelModel> target = tabCacheFor(activeTab);
          target.clear();
          target.addAll(prefetched);
          currentData.clear();
          currentData.addAll(target);
          adapter.notifyDataSetChanged();
          if (progressBar != null) progressBar.setVisibility(View.GONE);
          if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
      }

      private List<ReelModel> tabCacheFor(int tab) {
          switch (tab) {
              case TAB_LIKED: return likedCache;
              case TAB_SAVED: return savedCache;
              default:        return reelsCache;
          }
      }

      // Bind views
      private void bindViews() {
          btnBack           = findViewById(R.id.btn_back);
          tvTitle           = findViewById(R.id.tv_title);
          ivAvatar          = findViewById(R.id.iv_avatar);
          tabLayout         = findViewById(R.id.tab_layout);
          rvReels           = findViewById(R.id.rv_reels);
          progressBar       = findViewById(R.id.progress_bar);
          progressBarBottom = findViewById(R.id.progress_bar_bottom);
          layoutEmpty       = findViewById(R.id.layout_empty);
          tvEmptyTitle      = findViewById(R.id.tv_empty_title);
          tvEmptySub        = findViewById(R.id.tv_empty_sub);
          swipeRefresh      = findViewById(R.id.swipe_refresh);
      }

      // Toolbar
      private void setupToolbar() {
          if (btnBack != null) btnBack.setOnClickListener(v -> finish());
          if (tvTitle != null)
              tvTitle.setText((targetName != null && !targetName.isEmpty())
                  ? targetName + "'s Reels" : "All Reels");
          if (ivAvatar != null && targetPhoto != null && !targetPhoto.isEmpty())
              Glide.with(this).load(targetPhoto).circleCrop()
                  .placeholder(R.drawable.ic_person).error(R.drawable.ic_person).into(ivAvatar);
      }

      // Grid
      private void setupGrid() {
          adapter = new ReelGridAdapter(this, currentData, pos -> openPlayerAt(pos));
          String myUid;
          try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { myUid = null; }
          adapter.setShowViewsOverlay(targetUid.equals(myUid));

          layoutManager = new GridLayoutManager(this, 3);
          layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
              @Override public int getSpanSize(int p) {
                  return adapter.getItemViewType(p) == ReelGridAdapter.TYPE_PINNED ? 3 : 1;
              }
          });
          rvReels.setLayoutManager(layoutManager);
          rvReels.setAdapter(adapter);
        rvReels.addItemDecoration(new ReelGridAdapter.WhiteGridDecoration(this));
          rvReels.setHasFixedSize(false);
      }

      // Tabs
      private void setupTabs() {
          if (tabLayout == null) return;
          if (activeTab > 0 && activeTab < tabLayout.getTabCount()) {
              TabLayout.Tab t = tabLayout.getTabAt(activeTab);
              if (t != null) t.select();
          }
          tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
              @Override public void onTabSelected(TabLayout.Tab tab) {
                  activeTab = tab.getPosition();
                  if (adapter != null)
                      adapter.setPinnedReel(activeTab == TAB_REELS ? pinnedReel : null);
                  syncCurrentData();
                  if (adapter != null) adapter.notifyDataSetChanged();
                  if (activeTabCache().isEmpty()) loadCurrentTab(true);
                  else refreshEmptyState();
              }
              @Override public void onTabUnselected(TabLayout.Tab tab) {}
              @Override public void onTabReselected(TabLayout.Tab tab) {
                  if (rvReels != null) rvReels.smoothScrollToPosition(0);
              }
          });
      }

      private void syncCurrentData() {
          currentData.clear();
          currentData.addAll(activeTabCache());
      }

      // Swipe refresh
      private void setupSwipeRefresh() {
          if (swipeRefresh == null) return;
          swipeRefresh.setColorSchemeResources(android.R.color.holo_blue_bright);
          swipeRefresh.setOnRefreshListener(() -> {
              loadCurrentTab(true);
              if (activeTab == TAB_REELS) loadPinnedReel();
          });
      }

      // Infinite scroll
      private void setupScrollPagination() {
          if (rvReels == null) return;
          rvReels.addOnScrollListener(new RecyclerView.OnScrollListener() {
              @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                  if (swipeRefresh != null && !swipeRefresh.isRefreshing())
                      swipeRefresh.setEnabled(!rv.canScrollVertically(-1));
                  if (isLoadingMore || !currentTabHasMore()) return;
                  int total = layoutManager.getItemCount();
                  int last  = layoutManager.findLastVisibleItemPosition();
                  if (total > 0 && last >= total - 6) loadCurrentTab(false);
              }
          });
      }

      private boolean currentTabHasMore() {
          if (activeTab == TAB_LIKED) return likedHasMore;
          if (activeTab == TAB_SAVED) return savedHasMore;
          return reelsHasMore;
      }

      private List<ReelModel> activeTabCache() {
          if (activeTab == TAB_LIKED) return likedCache;
          if (activeTab == TAB_SAVED) return savedCache;
          return reelsCache;
      }

      private void loadCurrentTab(boolean refresh) {
          if (activeTab == TAB_LIKED)      loadLikedReels(refresh);
          else if (activeTab == TAB_SAVED) loadSavedReels(refresh);
          else                             loadUserReels(refresh);
      }

      // Pinned reel
      private void loadPinnedReel() {
          FirebaseDatabase.getInstance().getReference("reelPinned").child(targetUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      String id = snap.getValue(String.class);
                      if (id == null || id.isEmpty()) return;
                      FirebaseUtils.getReelsRef().child(id)
                          .addListenerForSingleValueEvent(new ValueEventListener() {
                              @Override public void onDataChange(@NonNull DataSnapshot s) {
                                  ReelModel r = s.getValue(ReelModel.class);
                                  if (r != null && activeTab == TAB_REELS && adapter != null) {
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

      // Load user reels
      private void loadUserReels(boolean refresh) {
          if (isLoadingMore && !refresh) return;
          isLoadingMore = true;
          if (refresh) { reelsLastKey = null; reelsHasMore = true; reelsCache.clear(); showLoader(true); }
          else showBottomLoader(true);
          buildQuery(FirebaseUtils.getReelsByUserRef(targetUid), reelsLastKey)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (isFinishing() || isDestroyed()) return;
                      if (snap.getChildrenCount() < PAGE_SIZE) reelsHasMore = false;
                      if (snap.getChildrenCount() == 0) { finishLoad(refresh, TAB_REELS); return; }
                      List<String> ids = extractIds(snap);
                      if (!ids.isEmpty()) reelsLastKey = ids.get(ids.size() - 1);
                      fetchAndAppend(ids, reelsCache, refresh, TAB_REELS);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      isLoadingMore = false; finishLoad(refresh, TAB_REELS);
                  }
              });
      }

      // Load liked reels
      private void loadLikedReels(boolean refresh) {
          if (isLoadingMore && !refresh) return;
          isLoadingMore = true;
          String myUid;
          try { myUid = FirebaseUtils.getCurrentUid(); }
          catch (Exception e) { isLoadingMore = false; return; }
          if (refresh) { likedLastKey = null; likedHasMore = true; likedCache.clear(); showLoader(true); }
          else showBottomLoader(true);
          buildQuery(FirebaseUtils.getReelLikedByUserRef(myUid), likedLastKey)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (isFinishing() || isDestroyed()) return;
                      if (snap.getChildrenCount() < PAGE_SIZE) likedHasMore = false;
                      if (snap.getChildrenCount() == 0) { finishLoad(refresh, TAB_LIKED); return; }
                      List<String> ids = extractIds(snap);
                      if (!ids.isEmpty()) likedLastKey = ids.get(ids.size() - 1);
                      fetchAndAppend(ids, likedCache, refresh, TAB_LIKED);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      isLoadingMore = false; finishLoad(refresh, TAB_LIKED);
                  }
              });
      }

      // Load saved reels
      private void loadSavedReels(boolean refresh) {
          if (isLoadingMore && !refresh) return;
          isLoadingMore = true;
          String myUid;
          try { myUid = FirebaseUtils.getCurrentUid(); }
          catch (Exception e) { isLoadingMore = false; return; }
          if (refresh) { savedLastKey = null; savedHasMore = true; savedCache.clear(); showLoader(true); }
          else showBottomLoader(true);
          buildQuery(FirebaseUtils.getReelSavesRef(myUid), savedLastKey)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (isFinishing() || isDestroyed()) return;
                      if (snap.getChildrenCount() < PAGE_SIZE) savedHasMore = false;
                      if (snap.getChildrenCount() == 0) { finishLoad(refresh, TAB_SAVED); return; }
                      List<String> ids = extractIds(snap);
                      if (!ids.isEmpty()) savedLastKey = ids.get(ids.size() - 1);
                      fetchAndAppend(ids, savedCache, refresh, TAB_SAVED);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      isLoadingMore = false; finishLoad(refresh, TAB_SAVED);
                  }
              });
      }

      private List<String> extractIds(DataSnapshot snap) {
          List<String> ids = new ArrayList<>();
          for (DataSnapshot c : snap.getChildren()) if (c.getKey() != null) ids.add(c.getKey());
          return ids;
      }

      private Query buildQuery(DatabaseReference ref, String lastKey) {
          return lastKey != null
              ? ref.orderByKey().startAfter(lastKey).limitToFirst(PAGE_SIZE)
              : ref.orderByKey().limitToFirst(PAGE_SIZE);
      }

      private void fetchAndAppend(List<String> ids, List<ReelModel> cache,
                                  boolean refresh, int tab) {
          if (ids.isEmpty()) { finishLoad(refresh, tab); return; }
          final int[] done = {0};
          final List<ReelModel> fetched = new ArrayList<>();
          for (String id : ids) {
              FirebaseUtils.getReelsRef().child(id)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
                      @Override public void onDataChange(@NonNull DataSnapshot s) {
                          if (s.exists()) {
                              ReelModel r = s.getValue(ReelModel.class);
                              if (r != null) {
                                  if (r.reelId == null) r.reelId = s.getKey();
                                  fetched.add(r);
                              }
                          }
                          done[0]++;
                          if (done[0] == ids.size()) { cache.addAll(fetched); finishLoad(refresh, tab); }
                      }
                      @Override public void onCancelled(@NonNull DatabaseError e) {
                          done[0]++;
                          if (done[0] == ids.size()) finishLoad(refresh, tab);
                      }
                  });
          }
      }

      private void finishLoad(boolean refresh, int tab) {
          if (isFinishing() || isDestroyed()) return;
          isLoadingMore = false;
          showBottomLoader(false);
          if (progressBar  != null) progressBar.setVisibility(View.GONE);
          if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
          if (tab == activeTab) {
              syncCurrentData();
              if (adapter != null) adapter.notifyDataSetChanged();
              refreshEmptyState();
          }
      }

      private void showLoader(boolean show) {
          if (show && suppressNextLoaderFlash) {
              // Advance #2 — grid was already warm-started from
              // ReelGridPrefetchCache; don't flash it back to empty while
              // the silent background refresh is in flight.
              suppressNextLoaderFlash = false;
              if (progressBar != null) progressBar.setVisibility(View.GONE);
              if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
              return;
          }
          if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
          if (layoutEmpty  != null) layoutEmpty.setVisibility(View.GONE);
          if (show) { currentData.clear(); if (adapter != null) adapter.notifyDataSetChanged(); }
      }

      private void showBottomLoader(boolean show) {
          if (progressBarBottom != null)
              progressBarBottom.setVisibility(show ? View.VISIBLE : View.GONE);
      }

      private void refreshEmptyState() {
          boolean empty = currentData.isEmpty();
          if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
          if (rvReels     != null) rvReels.setVisibility(empty ? View.GONE : View.VISIBLE);
          if (empty && tvEmptyTitle != null && tvEmptySub != null) {
              if (activeTab == TAB_LIKED) {
                  tvEmptyTitle.setText("No Liked Reels");
                  tvEmptySub.setText("Reels you like will appear here");
              } else if (activeTab == TAB_SAVED) {
                  tvEmptyTitle.setText("No Saved Reels");
                  tvEmptySub.setText("Reels you save will appear here");
              } else {
                  tvEmptyTitle.setText("No Reels Yet");
                  tvEmptySub.setText("This user has no reels");
              }
          }
      }

      // Open reel player
      private void openPlayerAt(int pos) {
          int reelIdx = (adapter != null && adapter.hasPinned()) ? pos - 1 : pos;
          if (reelIdx < 0) reelIdx = 0;
          if (currentData.isEmpty()) return;
          int safeIdx = Math.min(reelIdx, currentData.size() - 1);
          ArrayList<String> ids = new ArrayList<>();
          for (ReelModel r : currentData) if (r != null && r.reelId != null) ids.add(r.reelId);
          if (ids.isEmpty()) return;
          Intent i = new Intent(this, SingleReelPlayerActivity.class);
          i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
          i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, safeIdx);
          i.putExtra(SingleReelPlayerActivity.EXTRA_TITLE,
              (targetName != null && !targetName.isEmpty()) ? targetName + "'s Reels" : "Reels");
          startActivity(i);
      }
  }