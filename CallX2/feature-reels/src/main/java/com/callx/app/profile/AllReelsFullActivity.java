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
   * AllReelsFullActivity — Instagram-style full reels grid screen.
   *
   * Shows ALL reels of a user / contact with infinite scroll.
   * Has 3 tabs: Reels | Liked | Saved.
   * Launched from UserReelsActivity "View All Reels" button.
   *
   * Pattern: adapter holds a reference to 'currentData'. On tab switch,
   * currentData is cleared + refilled from the correct cache list.
   */
  public class AllReelsFullActivity extends AppCompatActivity {

      public static final String EXTRA_UID   = "uid";
      public static final String EXTRA_NAME  = "name";
      public static final String EXTRA_PHOTO = "photo";
      public static final String EXTRA_TAB   = "tab";

      private static final int PAGE_SIZE  = 18;
      private static final int TAB_REELS  = 0;
      private static final int TAB_LIKED  = 1;
      private static final int TAB_SAVED  = 2;

      // Views
      private ImageButton        btnBack;
      private TextView           tvTitle;
      private CircleImageView    ivAvatar;
      private TabLayout          tabLayout;
      private RecyclerView       rvReels;
      private ProgressBar        progressBar;
      private View               layoutEmpty;
      private SwipeRefreshLayout swipeRefresh;

      // State
      private String  targetUid, targetName, targetPhoto;
      private int     activeTab     = TAB_REELS;
      private boolean isLoadingMore = false;

      // Cached data per tab
      private final List<ReelModel> reelsCache = new ArrayList<>();
      private final List<ReelModel> likedCache = new ArrayList<>();
      private final List<ReelModel> savedCache = new ArrayList<>();

      // Adapter's live list — always synced to the active tab cache
      private final List<ReelModel> currentData = new ArrayList<>();

      private String  reelsLastKey = null, likedLastKey = null, savedLastKey = null;
      private boolean reelsHasMore = true, likedHasMore = true, savedHasMore = true;

      private ReelGridAdapter   adapter;
      private GridLayoutManager gridLayoutManager;
      private ReelModel         pinnedReel = null;

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
          loadCurrentTab(true);
      }

      // ── Bind ──────────────────────────────────────────────────────────────

      private void bindViews() {
          btnBack     = findViewById(R.id.btn_back);
          tvTitle     = findViewById(R.id.tv_title);
          ivAvatar    = findViewById(R.id.iv_avatar);
          tabLayout   = findViewById(R.id.tab_layout);
          rvReels     = findViewById(R.id.rv_reels);
          progressBar = findViewById(R.id.progress_bar);
          layoutEmpty = findViewById(R.id.layout_empty);
          swipeRefresh= findViewById(R.id.swipe_refresh);
      }

      // ── Toolbar ───────────────────────────────────────────────────────────

      private void setupToolbar() {
          if (btnBack != null) btnBack.setOnClickListener(v -> finish());
          if (tvTitle != null)
              tvTitle.setText(targetName != null && !targetName.isEmpty()
                  ? targetName + "'s Reels" : "All Reels");
          if (ivAvatar != null && targetPhoto != null && !targetPhoto.isEmpty()) {
              Glide.with(this)
                  .load(targetPhoto)
                  .circleCrop()
                  .placeholder(R.drawable.ic_person)
                  .into(ivAvatar);
          }
      }

      // ── Grid setup ────────────────────────────────────────────────────────

      private void setupGrid() {
          // adapter always references 'currentData' — we swap contents on tab switch
          adapter = new ReelGridAdapter(
              this, currentData,
              pos -> openPlayerAt(pos)
          );

          String myUid;
          try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { myUid = null; }
          adapter.setShowViewsOverlay(targetUid.equals(myUid));

          gridLayoutManager = new GridLayoutManager(this, 3);
          gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
              @Override public int getSpanSize(int pos) {
                  return adapter.getItemViewType(pos) == ReelGridAdapter.TYPE_PINNED ? 3 : 1;
              }
          });
          rvReels.setLayoutManager(gridLayoutManager);
          rvReels.setAdapter(adapter);
          rvReels.setNestedScrollingEnabled(true);
          rvReels.setHasFixedSize(false);
      }

      // ── Tabs ──────────────────────────────────────────────────────────────

      private void setupTabs() {
          if (tabLayout == null) return;

          // Select initial tab from intent
          if (activeTab > 0 && tabLayout.getTabCount() > activeTab) {
              TabLayout.Tab t = tabLayout.getTabAt(activeTab);
              if (t != null) t.select();
          }

          tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
              @Override public void onTabSelected(TabLayout.Tab tab) {
                  activeTab = tab.getPosition();
                  // Sync pinned reel visibility
                  if (activeTab != TAB_REELS && adapter != null) adapter.setPinnedReel(null);
                  else if (activeTab == TAB_REELS && pinnedReel != null && adapter != null)
                      adapter.setPinnedReel(pinnedReel);
                  // Show cached data or load
                  syncCurrentData();
                  adapter.notifyDataSetChanged();
                  if (activeTabCache().isEmpty()) loadCurrentTab(true);
                  else refreshEmptyState();
              }
              @Override public void onTabUnselected(TabLayout.Tab tab) {}
              @Override public void onTabReselected(TabLayout.Tab tab) {
                  if (rvReels != null) rvReels.smoothScrollToPosition(0);
              }
          });
      }

      /** Clear currentData and fill from the active tab's cache. */
      private void syncCurrentData() {
          currentData.clear();
          currentData.addAll(activeTabCache());
      }

      // ── Swipe Refresh ─────────────────────────────────────────────────────

      private void setupSwipeRefresh() {
          if (swipeRefresh == null) return;
          swipeRefresh.setColorSchemeResources(R.color.brand_primary);
          swipeRefresh.setOnRefreshListener(() -> {
              loadCurrentTab(true);
              if (activeTab == TAB_REELS) loadPinnedReel();
          });
      }

      // ── Scroll Pagination ─────────────────────────────────────────────────

      private void setupScrollPagination() {
          rvReels.addOnScrollListener(new RecyclerView.OnScrollListener() {
              @Override
              public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                  if (swipeRefresh != null && !swipeRefresh.isRefreshing()) {
                      swipeRefresh.setEnabled(!rv.canScrollVertically(-1));
                  }
                  if (isLoadingMore || !getCurrentTabHasMore()) return;
                  int total       = gridLayoutManager.getItemCount();
                  int lastVisible = gridLayoutManager.findLastVisibleItemPosition();
                  if (total > 0 && lastVisible >= total - 6) loadCurrentTab(false);
              }
          });
      }

      private boolean getCurrentTabHasMore() {
          switch (activeTab) {
              case TAB_LIKED: return likedHasMore;
              case TAB_SAVED: return savedHasMore;
              default:        return reelsHasMore;
          }
      }

      // ── Active tab cache ──────────────────────────────────────────────────

      private List<ReelModel> activeTabCache() {
          switch (activeTab) {
              case TAB_LIKED: return likedCache;
              case TAB_SAVED: return savedCache;
              default:        return reelsCache;
          }
      }

      // ── Data loading ──────────────────────────────────────────────────────

      private void loadCurrentTab(boolean refresh) {
          switch (activeTab) {
              case TAB_LIKED: loadLikedReels(refresh);  break;
              case TAB_SAVED: loadSavedReels(refresh);  break;
              default:        loadUserReels(refresh);   break;
          }
      }

      // ── Pinned Reel ───────────────────────────────────────────────────────

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

      // ── Load: User's Reels ────────────────────────────────────────────────

      private void loadUserReels(boolean refresh) {
          if (isLoadingMore && !refresh) return;
          isLoadingMore = true;
          if (refresh) {
              reelsLastKey = null; reelsHasMore = true;
              reelsCache.clear(); showSkeleton();
          }
          Query q = buildQuery(FirebaseUtils.getReelsByUserRef(targetUid), reelsLastKey);
          q.addListenerForSingleValueEvent(new ValueEventListener() {
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

      // ── Load: Liked Reels ─────────────────────────────────────────────────

      private void loadLikedReels(boolean refresh) {
          if (isLoadingMore && !refresh) return;
          isLoadingMore = true;
          String myUid;
          try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { isLoadingMore = false; return; }
          if (refresh) {
              likedLastKey = null; likedHasMore = true;
              likedCache.clear(); showSkeleton();
          }
          Query q = buildQuery(FirebaseUtils.getReelLikedByUserRef(myUid), likedLastKey);
          q.addListenerForSingleValueEvent(new ValueEventListener() {
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

      // ── Load: Saved Reels ─────────────────────────────────────────────────

      private void loadSavedReels(boolean refresh) {
          if (isLoadingMore && !refresh) return;
          isLoadingMore = true;
          String myUid;
          try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { isLoadingMore = false; return; }
          if (refresh) {
              savedLastKey = null; savedHasMore = true;
              savedCache.clear(); showSkeleton();
          }
          Query q = buildQuery(FirebaseUtils.getReelSavesRef(myUid), savedLastKey);
          q.addListenerForSingleValueEvent(new ValueEventListener() {
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

      // ── Helpers ───────────────────────────────────────────────────────────

      private List<String> extractIds(DataSnapshot snap) {
          List<String> ids = new ArrayList<>();
          for (DataSnapshot c : snap.getChildren()) if (c.getKey() != null) ids.add(c.getKey());
          return ids;
      }

      private Query buildQuery(DatabaseReference ref, String lastKey) {
          if (lastKey != null) return ref.orderByKey().startAfter(lastKey).limitToFirst(PAGE_SIZE);
          return ref.orderByKey().limitToFirst(PAGE_SIZE);
      }

      private void fetchAndAppend(List<String> ids, List<ReelModel> cache, boolean refresh, int tab) {
          if (ids.isEmpty()) { finishLoad(refresh, tab); return; }
          final int[]           done    = {0};
          final List<ReelModel> fetched = new ArrayList<>();
          for (String id : ids) {
              FirebaseUtils.getReelsRef().child(id)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
                      @Override public void onDataChange(@NonNull DataSnapshot s) {
                          if (s.exists()) {
                              ReelModel r = s.getValue(ReelModel.class);
                              if (r != null) { if (r.reelId == null) r.reelId = s.getKey(); fetched.add(r); }
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
          if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
          if (progressBar  != null) progressBar.setVisibility(View.GONE);
          if (tab == activeTab) {
              // Sync currentData from the active cache and notify adapter
              syncCurrentData();
              adapter.notifyDataSetChanged();
              refreshEmptyState();
          }
      }

      private void showSkeleton() {
          if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
          if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
          currentData.clear();
          adapter.notifyDataSetChanged();
      }

      private void refreshEmptyState() {
          boolean empty = currentData.isEmpty();
          if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
          if (rvReels     != null) rvReels.setVisibility(empty ? View.GONE : View.VISIBLE);
      }

      // ── Player ────────────────────────────────────────────────────────────

      private void openPlayerAt(int pos) {
          if (pos < 0 || pos >= currentData.size()) return;
          ReelModel reel = currentData.get(pos);
          Intent i = new Intent(this, SingleReelPlayerActivity.class);
          i.putExtra("reel_id", reel.reelId != null ? reel.reelId : "");
          startActivity(i);
      }
  }
  