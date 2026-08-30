package com.callx.app.followers;
import com.callx.app.utils.AlertDialogStyler;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import androidx.viewpager2.widget.ViewPager2;
import com.bumptech.glide.Glide;
import com.callx.app.profile.ReelUserProfileSheet;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * FollowConnectionsActivity — Instagram-style unified screen.
 *
 * Shows Followers / Following / Mutual Followers in one screen with:
 *  ✅ Swipeable tabs (ViewPager2 + TabLayout) — left/right swipe switches tab
 *  ✅ Shared search bar — filters whichever tab is active in real-time
 *  ✅ Count labels on tabs (e.g. "487 Followers", "132 Following", "3 Mutual")
 *  ✅ Follow-back / Unfollow / Remove actions per tab
 *  ✅ Tap row → opens UserReelsActivity
 *  ✅ Avatar tap → ReelUserProfileSheet
 *  ✅ Cross-tab search: when query has no result in Following tab, checks
 *     Followers + Mutual and shows a "Found in Followers" hint dialog
 *
 * Replaces: FollowersListActivity, FollowingListActivity, MutualFollowersActivity.
 */
public class FollowConnectionsActivity extends AppCompatActivity {

    // ── Extras ────────────────────────────────────────────────────────────
    public static final String EXTRA_UID         = "uid";
    public static final String EXTRA_NAME        = "name";
    public static final String EXTRA_IS_SELF     = "is_self";
    public static final String EXTRA_START_TAB   = "start_tab";
    public static final String EXTRA_MUTUAL_UIDS = "mutual_uids";

    public static final int TAB_FOLLOWERS = 0;
    public static final int TAB_FOLLOWING = 1;
    public static final int TAB_MUTUAL    = 2;
    public static final int TAB_SUGGESTED = 3;

    // ── Views ─────────────────────────────────────────────────────────────
    private TabLayout    tabLayout;
    private ViewPager2   viewPager;
    private EditText     etSearch;
    private ImageButton  btnBack;
    private TextView     tvUsername;

    // ── State ─────────────────────────────────────────────────────────────
    private String           targetUid, targetName;
    private boolean          isSelf;
    private int              startTab = TAB_FOLLOWERS;
    private ArrayList<String> mutualUidsArg = new ArrayList<>();

    // ── Per-tab data ──────────────────────────────────────────────────────
    private static final int TAB_COUNT = 4;

    @SuppressWarnings("unchecked")
    private final List<UserItem>[] allItems      = new List[TAB_COUNT];
    @SuppressWarnings("unchecked")
    private final List<UserItem>[] filteredItems = new List[TAB_COUNT];
    private final UserListAdapter[] adapters     = new UserListAdapter[TAB_COUNT];
    private final RecyclerView[]    rvs          = new RecyclerView[TAB_COUNT];
    private final ProgressBar[]     progresses   = new ProgressBar[TAB_COUNT];
    private final LinearLayout[]    empties      = new LinearLayout[TAB_COUNT];

    private final int[] counts = {0, 0, 0, 0};   // follower / following / mutual / suggested count

    private final Set<String> myFollowing = new HashSet<>();
    private boolean myFollowingLoaded = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_follow_connections);

        // Read extras
        targetUid  = getIntent().getStringExtra(EXTRA_UID);
        targetName = getIntent().getStringExtra(EXTRA_NAME);
        isSelf     = getIntent().getBooleanExtra(EXTRA_IS_SELF, false);
        startTab   = getIntent().getIntExtra(EXTRA_START_TAB, TAB_FOLLOWERS);
        ArrayList<String> mu = getIntent().getStringArrayListExtra(EXTRA_MUTUAL_UIDS);
        if (mu != null) mutualUidsArg.addAll(mu);

        if (targetUid == null) { finish(); return; }

        // Bind views
        btnBack    = findViewById(R.id.btn_back);
        tvUsername = findViewById(R.id.tv_username);
        tabLayout  = findViewById(R.id.tab_layout);
        viewPager  = findViewById(R.id.view_pager);
        etSearch   = findViewById(R.id.et_search);

        if (btnBack    != null) btnBack.setOnClickListener(v -> finish());
        if (tvUsername != null) tvUsername.setText(targetName != null ? targetName : "");

        // Initialise per-tab lists
        for (int i = 0; i < TAB_COUNT; i++) {
            allItems[i]      = new ArrayList<>();
            filteredItems[i] = new ArrayList<>();
        }

        // Setup ViewPager2
        viewPager.setAdapter(new PageAdapter());
        viewPager.setOffscreenPageLimit(TAB_COUNT);

        // Connect TabLayout
        String[] defaultLabels = {"Followers", "Following", "Mutual", "Suggested"};
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, pos) -> tab.setText(defaultLabels[pos])).attach();

        // Jump to requested start tab
        viewPager.setCurrentItem(startTab, false);

        // Search watcher — filters active tab
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    filterTab(viewPager.getCurrentItem(), s.toString().trim());
                }
            });
        }

        // Re-filter when tab changes
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int pos) {
                String q = etSearch != null ? etSearch.getText().toString().trim() : "";
                filterTab(pos, q);
            }
        });

        // Load data
        loadMyFollowing();
        loadFollowers();
        loadFollowing();
        loadMutual();
        loadSuggested();
    }

    // ══════════════════════════════════════════════════════════════════════
    // ViewPager2 adapter — each page is an inner RecyclerView
    // ══════════════════════════════════════════════════════════════════════

    private class PageAdapter extends RecyclerView.Adapter<PageAdapter.PageVH> {

        @NonNull
        @Override
        public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout page = new FrameLayout(parent.getContext());
            page.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new PageVH(page);
        }

        @Override
        public void onBindViewHolder(@NonNull PageVH h, int pos) {
            buildPage(h.page, pos);
        }

        @Override public int getItemCount() { return TAB_COUNT; }

        class PageVH extends RecyclerView.ViewHolder {
            final FrameLayout page;
            PageVH(FrameLayout f) { super(f); page = f; }
        }
    }

    /**
     * Build one ViewPager page: RecyclerView + ProgressBar + empty state.
     * Stores references so we can update them when data loads.
     */
    private void buildPage(FrameLayout container, int tabIdx) {
        Context ctx = container.getContext();
        container.removeAllViews();

        // RecyclerView
        RecyclerView rv = new RecyclerView(ctx);
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        rv.setHasFixedSize(true);
        FrameLayout.LayoutParams rvLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        container.addView(rv, rvLp);
        rvs[tabIdx] = rv;

        // Create adapter for this tab
        UserListAdapter adapter = new UserListAdapter(filteredItems[tabIdx], tabIdx);
        adapters[tabIdx] = adapter;
        rv.setAdapter(adapter);

        // FIX (velocity-based prefetch): was missing entirely on this
        // consolidated screen (the old per-tab FollowersListActivity/
        // FollowingListActivity had it — see FollowAvatarBinder's class
        // doc). Same velocity measurement + depth thresholds as every
        // other avatar list in the app: fast fling skips prefetch
        // entirely, slow/deliberate scroll warms several rows ahead via
        // DiskCacheStrategy.DATA (bytes only, decode deferred to a real
        // bind once the row is actually visible).
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private long lastTimeMs = 0L;

            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) return;
                int lastVisible = lm.findLastVisibleItemPosition();
                if (lastVisible < 0) return;

                long now = android.os.SystemClock.elapsedRealtime();
                long dt = lastTimeMs == 0L ? 0L : (now - lastTimeMs);
                float velocity = (dt > 0) ? Math.abs(dy) / (float) dt : 0f;
                lastTimeMs = now;

                FollowAvatarBinder.prefetch(FollowConnectionsActivity.this, followAvatarSource(tabIdx), lastVisible + 1, velocity);
            }
        });

        // ProgressBar
        ProgressBar pb = new ProgressBar(ctx);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.gravity = android.view.Gravity.CENTER;
        container.addView(pb, pbLp);
        pb.setIndeterminate(true);
        try { pb.setIndeterminateTintList(
            android.content.res.ColorStateList.valueOf(getResources().getColor(R.color.brand_primary, null)));
        } catch (Exception ignored) {}
        progresses[tabIdx] = pb;

        // Empty state
        LinearLayout emptyLayout = buildEmptyView(ctx, tabIdx);
        emptyLayout.setVisibility(View.GONE);
        container.addView(emptyLayout);
        empties[tabIdx] = emptyLayout;
    }

    private LinearLayout buildEmptyView(Context ctx, int tabIdx) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(android.view.Gravity.CENTER);
        int pad = dp(40);
        ll.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ll.setLayoutParams(lp);

        ImageView iv = new ImageView(ctx);
        iv.setImageResource(R.drawable.ic_person);
        iv.setAlpha(0.25f);
        ll.addView(iv, dp(56), dp(56));

        TextView title = new TextView(ctx);
        String[] titles = {"No followers yet", "Not following anyone", "No mutual followers", "No suggestions"};
        title.setText(titles[Math.min(tabIdx, 3)]);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurface));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(14);
        ll.addView(title, titleLp);

        String[] subs = {
            "Followers will appear here.",
            "This user hasn't followed anyone.",
            "You and this user have no followers in common.",
            "No new accounts to suggest."
        };
        TextView sub = new TextView(ctx);
        sub.setText(subs[Math.min(tabIdx, 3)]);
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        ll.addView(sub, subLp);

        return ll;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Data loading
    // ══════════════════════════════════════════════════════════════════════

    private void loadMyFollowing() {
        String myUid = safeMyUid();
        if (myUid == null) return;
        FirebaseUtils.getReelFollowsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        if (s.getKey() != null) myFollowing.add(s.getKey());
                    }
                    myFollowingLoaded = true;
                    // PERF: payload-only refresh — every row's follow-state
                    // button repaints, but avatars/bio/row-click listeners
                    // are left untouched (see
                    // UserListAdapter#refreshAllFollowStates), instead of a
                    // full notifyDataSetChanged() that re-decodes every
                    // visible avatar just because the button state changed.
                    for (UserListAdapter a : adapters) if (a != null) a.refreshAllFollowStates();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    myFollowingLoaded = true;
                }
            });
    }

    /** Load reelFollowers/{targetUid} */
    private void loadFollowers() {
        showProgress(TAB_FOLLOWERS, true);
        FirebaseUtils.getReelFollowersRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    allItems[TAB_FOLLOWERS].clear();
                    long total = snap.getChildrenCount();
                    counts[TAB_FOLLOWERS] = (int) total;
                    updateTabLabel(TAB_FOLLOWERS);
                    if (total == 0) { showProgress(TAB_FOLLOWERS, false); showEmpty(TAB_FOLLOWERS, true); return; }
                    final long[] done = {0};
                    for (DataSnapshot child : snap.getChildren()) {
                        String uid = child.getKey(); if (uid == null) { done[0]++; checkDone(TAB_FOLLOWERS, done, total); continue; }
                        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot us) {
                                UserItem item = parseUser(uid, us);
                                synchronized (allItems[TAB_FOLLOWERS]) { allItems[TAB_FOLLOWERS].add(item); }
                                done[0]++;
                                checkDone(TAB_FOLLOWERS, done, total);
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) { done[0]++; checkDone(TAB_FOLLOWERS, done, total); }
                        });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { showProgress(TAB_FOLLOWERS, false); }
            });
    }

    /** Load reelFollows/{targetUid} */
    private void loadFollowing() {
        showProgress(TAB_FOLLOWING, true);
        FirebaseUtils.getReelFollowsRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    allItems[TAB_FOLLOWING].clear();
                    long total = snap.getChildrenCount();
                    counts[TAB_FOLLOWING] = (int) total;
                    updateTabLabel(TAB_FOLLOWING);
                    if (total == 0) { showProgress(TAB_FOLLOWING, false); showEmpty(TAB_FOLLOWING, true); return; }
                    final long[] done = {0};
                    for (DataSnapshot child : snap.getChildren()) {
                        String uid = child.getKey(); if (uid == null) { done[0]++; checkDone(TAB_FOLLOWING, done, total); continue; }
                        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot us) {
                                UserItem item = parseUser(uid, us);
                                synchronized (allItems[TAB_FOLLOWING]) { allItems[TAB_FOLLOWING].add(item); }
                                done[0]++;
                                checkDone(TAB_FOLLOWING, done, total);
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) { done[0]++; checkDone(TAB_FOLLOWING, done, total); }
                        });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { showProgress(TAB_FOLLOWING, false); }
            });
    }

    /** Load mutual — from pre-passed UIDs or compute on the fly */
    private void loadMutual() {
        showProgress(TAB_MUTUAL, true);
        if (!mutualUidsArg.isEmpty()) {
            fetchUsersForMutual(mutualUidsArg);
            return;
        }
        // Compute mutual: intersection of my followers & target's followers
        String myUid = safeMyUid();
        if (myUid == null || isSelf) { showProgress(TAB_MUTUAL, false); showEmpty(TAB_MUTUAL, true); return; }
        FirebaseUtils.getReelFollowersRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot mySnap) {
                    Set<String> mine = new HashSet<>();
                    for (DataSnapshot s : mySnap.getChildren()) if (s.getKey() != null) mine.add(s.getKey());
                    FirebaseUtils.getReelFollowersRef(targetUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot tSnap) {
                                ArrayList<String> mutuals = new ArrayList<>();
                                for (DataSnapshot s : tSnap.getChildren())
                                    if (s.getKey() != null && mine.contains(s.getKey())) mutuals.add(s.getKey());
                                if (mutuals.isEmpty()) { showProgress(TAB_MUTUAL, false); showEmpty(TAB_MUTUAL, true); return; }
                                fetchUsersForMutual(mutuals);
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) { showProgress(TAB_MUTUAL, false); }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { showProgress(TAB_MUTUAL, false); }
            });
    }

    private void fetchUsersForMutual(List<String> uids) {
        allItems[TAB_MUTUAL].clear();
        counts[TAB_MUTUAL] = uids.size();
        updateTabLabel(TAB_MUTUAL);
        final long total = uids.size();
        final long[] done = {0};
        for (String uid : uids) {
            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot us) {
                    synchronized (allItems[TAB_MUTUAL]) { allItems[TAB_MUTUAL].add(parseUser(uid, us)); }
                    done[0]++; checkDone(TAB_MUTUAL, done, total);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { done[0]++; checkDone(TAB_MUTUAL, done, total); }
            });
        }
    }

    private void checkDone(int tab, long[] done, long total) {
        if (done[0] < total) return;
        runOnUiThread(() -> {
            // Sort by name
            synchronized (allItems[tab]) {
                allItems[tab].sort((a, b) -> {
                    String na = a.name != null ? a.name : "";
                    String nb = b.name != null ? b.name : "";
                    return na.compareToIgnoreCase(nb);
                });
            }
            showProgress(tab, false);
            String q = etSearch != null ? etSearch.getText().toString().trim() : "";
            filterTab(tab, q);
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Filter + cross-search
    // ══════════════════════════════════════════════════════════════════════

    private void filterTab(int tab, String query) {
        filteredItems[tab].clear();
        if (query.isEmpty()) {
            filteredItems[tab].addAll(allItems[tab]);
        } else {
            String lq = query.toLowerCase(Locale.getDefault());
            for (UserItem u : allItems[tab]) {
                String name = u.name != null ? u.name.toLowerCase(Locale.getDefault()) : "";
                String bio  = u.bio  != null ? u.bio.toLowerCase(Locale.getDefault())  : "";
                if (name.contains(lq) || bio.contains(lq)) filteredItems[tab].add(u);
            }
        }

        // PERF: submitList() runs AsyncListDiffer's old-list/new-list diff on
        // a background thread and dispatches minimal insert/remove/move
        // calls — was notifyDataSetChanged(), which force-rebound every row
        // (full avatar Glide re-decode included) on every single keystroke
        // while typing in the search box. Same fix as the reel comment list.
        if (adapters[tab] != null) adapters[tab].submitList(filteredItems[tab]);

        boolean empty = filteredItems[tab].isEmpty();
        showEmpty(tab, empty && allItems[tab].isEmpty() && query.isEmpty());

        // Cross-tab search hint — works from ANY tab (Followers / Following / Mutual)
        if (empty && !query.isEmpty()) {
            checkCrossTabs(tab, query);
        }
    }

    private static final String[] TAB_LABELS = {"Followers", "Following", "Mutual Followers", "Suggested"};

    /**
     * Cross-tab search: if query found in a DIFFERENT tab than the one currently
     * being searched, show a compact hint dialog: avatar + name + highlighted
     * "Found in <Tab>" chip, regardless of which tab (Followers/Following/Mutual)
     * the user was searching in.
     */
    private void checkCrossTabs(int currentTab, String query) {
        String lq = query.toLowerCase(Locale.getDefault());
        UserItem found = null;
        int foundTab = -1;

        outer:
        for (int t = 0; t < TAB_COUNT; t++) {
            if (t == currentTab) continue;
            for (UserItem u : allItems[t]) {
                String name = u.name != null ? u.name.toLowerCase(Locale.getDefault()) : "";
                if (name.contains(lq)) { found = u; foundTab = t; break outer; }
            }
        }
        if (found == null || isFinishing() || isDestroyed()) return;

        final UserItem target   = found;
        final int      foundIdx = foundTab;

        // Debounce — only show after search settles
        viewPager.postDelayed(() -> {
            String current = etSearch != null ? etSearch.getText().toString().trim() : "";
            if (!current.equalsIgnoreCase(query) || isFinishing() || isDestroyed()) return;
            showCrossTabDialog(target, foundIdx);
        }, 600);
    }

    private void showCrossTabDialog(UserItem user, int foundTabIdx) {
        if (isFinishing() || isDestroyed()) return;
        Context ctx = this;
        float density = getResources().getDisplayMetrics().density;
        String listName = TAB_LABELS[foundTabIdx];

        // ── Compact card, rounded on all 4 corners ──────────────────────────
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER);
        int padH = (int) (20 * density), padTop = (int) (20 * density), padBottom = (int) (10 * density);
        root.setPadding(padH, padTop, padH, padBottom);

        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(22 * density);
        cardBg.setColor(resolveAttrColor(com.google.android.material.R.attr.colorSurface));
        root.setBackground(cardBg);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                (int) (260 * density), ViewGroup.LayoutParams.WRAP_CONTENT));

        // Avatar
        CircleImageView iv = new CircleImageView(ctx);
        int avSz = (int) (52 * density);
        iv.setImageResource(R.drawable.ic_person);
        if (user.photo != null && !user.photo.isEmpty()) {
            String resizedUrl = AvatarUrlBuilder.build(ctx, user.photo, AvatarSizeTier.MEDIUM);
            Glide.with(ctx).load(resizedUrl)
                .placeholder(R.drawable.ic_person)
                .into(iv);
        }
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(avSz, avSz);
        root.addView(iv, avLp);

        // Name
        TextView tvName = new TextView(ctx);
        tvName.setText(user.name != null ? user.name : user.uid);
        tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvName.setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurface));
        tvName.setGravity(android.view.Gravity.CENTER);
        tvName.setMaxLines(1);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = (int) (8 * density);
        root.addView(tvName, nameLp);

        // "Found in: [chip]" row
        LinearLayout chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = (int) (6 * density);
        root.addView(chipRow, crLp);

        TextView foundLabel = new TextView(ctx);
        foundLabel.setText("Found in  ");
        foundLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        foundLabel.setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        chipRow.addView(foundLabel);

        // Highlighted chip — one colour per tab
        TextView chip = new TextView(ctx);
        chip.setText(listName);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        chip.setTextColor(0xFFFFFFFF);
        int hPad = (int) (10 * density), vPad = (int) (3 * density);
        chip.setPadding(hPad, vPad, hPad, vPad);
        android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
        chipBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        chipBg.setCornerRadius(20 * density);
        int[] chipColors = {0xFF6C5CE7, 0xFF00B894, 0xFFFF7675, 0xFFFF9F43}; // Followers / Following / Mutual / Suggested
        chipBg.setColor(chipColors[foundTabIdx]);
        chip.setBackground(chipBg);
        chip.setTypeface(null, android.graphics.Typeface.BOLD);
        chipRow.addView(chip);

        // Divider
        View divider = new View(ctx);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (density));
        divLp.topMargin = (int) (16 * density);
        divider.setBackgroundColor(getResources().getColor(R.color.divider, null));
        root.addView(divider, divLp);

        // ── Compact action row (2 buttons, no default AlertDialog chrome) ───
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = (int) (10 * density);
        root.addView(btnRow, btnRowLp);

        AlertDialog dialog = new AlertDialog.Builder(ctx).setView(root).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        TextView btnView = new TextView(ctx);
        btnView.setText("View Profile");
        styleCompactDialogBtn(btnView, false, density);
        btnView.setOnClickListener(v -> {
            dialog.dismiss();
            Intent i = new Intent(FollowConnectionsActivity.this, UserReelsActivity.class);
            i.putExtra(UserReelsActivity.EXTRA_UID,   user.uid);
            i.putExtra(UserReelsActivity.EXTRA_NAME,  user.name);
            i.putExtra(UserReelsActivity.EXTRA_PHOTO, user.photo);
            startActivity(i);
        });
        btnRow.addView(btnView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnOpen = new TextView(ctx);
        btnOpen.setText("Open " + listName);
        styleCompactDialogBtn(btnOpen, true, density);
        btnOpen.setOnClickListener(v -> {
            dialog.dismiss();
            if (viewPager != null) viewPager.setCurrentItem(foundTabIdx, true);
            if (etSearch  != null) etSearch.setText(user.name);
        });
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        openLp.leftMargin = (int) (8 * density);
        btnRow.addView(btnOpen, openLp);

        AlertDialogStyler.showRounded(dialog);
    }

    private void styleCompactDialogBtn(TextView btn, boolean filled, float density) {
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        btn.setPadding(0, (int) (11 * density), 0, (int) (11 * density));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(14 * density);
        if (filled) {
            bg.setColor(getResources().getColor(R.color.brand_primary, null));
            btn.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(resolveAttrColor(com.google.android.material.R.attr.colorSurfaceVariant));
            btn.setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        }
        btn.setBackground(bg);
    }

    // ══════════════════════════════════════════════════════════════════════
    // RecyclerView adapter (shared across all tabs)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * UserListAdapter — shared list adapter for Followers / Following /
     * Mutual / Suggested tabs.
     *
     * PERF (reused from ReelCommentsAdapter's advanced optimization pass):
     *  ✅ AsyncListDiffer instead of raw notifyDataSetChanged() — every
     *     filter keystroke, follow/unfollow, and initial-load refresh used
     *     to force-rebind (and Glide re-decode) every visible row; the
     *     diff now runs off the main thread and only touches rows that
     *     actually changed.
     *  ✅ Payload-based partial bind for follow-state-only changes — skips
     *     avatar reload, name/bio rebind, and listener reattachment; only
     *     the action button repaints (mirrors PAYLOAD_LIKE in the comment
     *     adapter).
     *  ✅ Stable IDs (uid hash) so RecyclerView's default animator matches
     *     rows by identity, not position, across diff-driven updates.
     *  ✅ All click listeners (avatar / row / action button) attached ONCE
     *     in the ViewHolder constructor and read the live bound item +
     *     getAdapterPosition() at click time, instead of being reassigned
     *     on every onBindViewHolder call.
     */
    private class UserListAdapter extends RecyclerView.Adapter<UserListAdapter.VH> {

        /** Payload marker for a "follow-state only" partial rebind. */
        private static final String PAYLOAD_FOLLOW_STATE = "follow_state";

        // NOTE: instance field, not static — UserListAdapter is itself a
        // non-static inner class of the Activity, and Java only allows
        // compile-time-constant static members (like the String/int
        // constants above) inside a non-static inner class, not an object
        // instance like this callback.
        private final AsyncListDiffer<UserItem> differ =
            new AsyncListDiffer<>(this, new DiffUtil.ItemCallback<UserItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull UserItem a, @NonNull UserItem b) {
                    return a.uid != null && a.uid.equals(b.uid);
                }

                @Override
                public boolean areContentsTheSame(@NonNull UserItem a, @NonNull UserItem b) {
                    return Objects.equals(a.name, b.name)
                        && Objects.equals(a.photo, b.photo)
                        && Objects.equals(a.bio, b.bio)
                        && a.avatarVersion == b.avatarVersion;
                }
            });
        private final int tabIdx;

        UserListAdapter(List<UserItem> initial, int tabIdx) {
            this.tabIdx = tabIdx;
            setHasStableIds(true);
            if (initial != null && !initial.isEmpty()) differ.submitList(new ArrayList<>(initial));
        }

        /** Submit a new filtered/refreshed list — AsyncListDiffer diffs it
         *  against the current list on a background thread and dispatches
         *  minimal insert/remove/move calls. */
        void submitList(List<UserItem> list) {
            differ.submitList(list != null ? new ArrayList<>(list) : new ArrayList<>());
        }

        UserItem getItem(int pos) { return differ.getCurrentList().get(pos); }

        @Override
        public long getItemId(int position) {
            UserItem u = getItem(position);
            return (u != null && u.uid != null) ? u.uid.hashCode() : RecyclerView.NO_ID;
        }

        /** Follow/unfollow toggled elsewhere (e.g. from a different tab, or
         *  the "my following" bulk load) — repaint just this uid's action
         *  button instead of the whole row. */
        void notifyFollowStateChanged(String uid) {
            List<UserItem> items = differ.getCurrentList();
            for (int i = 0; i < items.size(); i++) {
                if (uid != null && uid.equals(items.get(i).uid)) {
                    notifyItemChanged(i, PAYLOAD_FOLLOW_STATE);
                    break;
                }
            }
        }

        /** Bulk version of notifyFollowStateChanged — used once after the
         *  initial "my following" set finishes loading. */
        void refreshAllFollowStates() {
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_FOLLOW_STATE);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            View v = getLayoutInflater().inflate(R.layout.item_follow_user, parent, false);
            return new VH(v);
        }

        /** Payload-aware partial bind — PERF: when only the follow state
         *  changed, skip the full bind (avatar Glide load, name/bio text)
         *  and touch only the action button. Falls back to a full bind for
         *  any other payload or a cold bind. */
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty() && payloads.contains(PAYLOAD_FOLLOW_STATE)) {
                UserItem u = getItem(pos);
                h.boundItem = u;
                bindActionButton(h, u, pos);
                return;
            }
            super.onBindViewHolder(h, pos, payloads);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            UserItem u = getItem(pos);
            h.boundItem = u;

            // FIX (deep avatar pipeline): flat AvatarUrlBuilder.build() +
            // manual URL-tag dedupe replaced with FollowAvatarBinder.bind()
            // — the SAME shared pipeline the reel owner avatar, comment
            // sheet, Home Stories tray, chat list, search, profile, and
            // status tray already use: density-aware tier sizing, L2/L3
            // bitmap reuse (survives TRIM_MEMORY_MODERATE), and analytics
            // wiring. See FollowAvatarBinder's own dedupe-by-URL-tag inside
            // bind() for why the redundant-load guard moved there.
            FollowAvatarBinder.bind(FollowConnectionsActivity.this, h.ivAvatar, u.photo, u.avatarVersion, R.drawable.ic_person);

            // Name + bio
            h.tvName.setText(u.name != null ? u.name : u.uid);
            if (u.bio != null && !u.bio.isEmpty()) {
                h.tvBio.setText(u.bio);
                h.tvBio.setVisibility(View.VISIBLE);
            } else {
                h.tvBio.setVisibility(View.GONE);
            }

            // Action button per tab
            bindActionButton(h, u, pos);
        }

        /**
         * FIX (Lifecycle-aware cancel): call from onViewRecycled() below.
         * Stops an in-flight request for a row that just scrolled off
         * screen instead of letting it keep competing for bandwidth/decode
         * time against whatever's now actually visible — same as
         * ChatListAdapter/StatusAvatarBinder consumers already do.
         */
        @Override
        public void onViewRecycled(@NonNull VH h) {
            super.onViewRecycled(h);
            FollowAvatarBinder.cancel(FollowConnectionsActivity.this, h.ivAvatar);
        }

        /** Sets visibility/text/style only — click behavior for btnAction is
         *  attached ONCE in the VH constructor (see below), so this never
         *  touches a listener. */
        private void bindActionButton(VH h, UserItem u, int pos) {
            String myUid = safeMyUid();

            switch (tabIdx) {
                case TAB_FOLLOWERS:
                    // "Follow Back" if I don't follow them; "Following" if I do
                    h.btnAction.setVisibility(View.VISIBLE);
                    boolean iFollow = myFollowing.contains(u.uid);
                    if (u.uid.equals(myUid)) {
                        h.btnAction.setVisibility(View.GONE);
                    } else if (iFollow) {
                        styleBtn(h.btnAction, "Following", false);
                    } else {
                        styleBtn(h.btnAction, "Follow", true);
                    }
                    break;

                case TAB_FOLLOWING:
                    // "Following" / "Unfollow" only for own profile
                    if (isSelf && myUid != null && !u.uid.equals(myUid)) {
                        h.btnAction.setVisibility(View.VISIBLE);
                        styleBtn(h.btnAction, "Following", false);
                    } else {
                        h.btnAction.setVisibility(View.GONE);
                    }
                    break;

                case TAB_MUTUAL:
                    // "Follow Back" if I don't follow them
                    h.btnAction.setVisibility(View.VISIBLE);
                    boolean iFollowMutual = myFollowing.contains(u.uid);
                    if (u.uid.equals(myUid)) {
                        h.btnAction.setVisibility(View.GONE);
                    } else if (iFollowMutual) {
                        styleBtn(h.btnAction, "Following", false);
                    } else {
                        styleBtn(h.btnAction, "Follow Back", true);
                    }
                    break;

                case TAB_SUGGESTED:
                    // Suggested candidates are always users I don't yet follow.
                    if (u.uid.equals(myUid)) {
                        h.btnAction.setVisibility(View.GONE);
                    } else {
                        h.btnAction.setVisibility(View.VISIBLE);
                        styleBtn(h.btnAction, "Follow", true);
                    }
                    break;
            }
        }

        // NOTE: a fresh GradientDrawable per call (not cached/shared) —
        // sharing one Drawable *instance* across multiple recycled rows is
        // unsafe here since each row's Button has a different wrap_content
        // width ("Follow" vs "Following" vs "Follow Back"); a shared
        // instance's bounds get overwritten by whichever row bound last,
        // stretching the pill on the others. Same pattern already used by
        // styleCompactDialogBtn()/cardBg/chipBg elsewhere in this file — a
        // plain shape GradientDrawable is cheap to allocate (no bitmap),
        // so this isn't the kind of per-bind cost the avatar/list-diff
        // fixes above target.
        private void styleBtn(Button btn, String text, boolean filled) {
            btn.setText(text);
            float r = 8f * btn.getResources().getDisplayMetrics().density; // rounded-rect, not a full pill
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(r);
            if (filled) {
                bg.setColor(getResources().getColor(R.color.brand_primary, null));
                btn.setTextColor(0xFFFFFFFF);
            } else {
                // In light mode colorSurfaceVariant is forced to pure white
                // (same as the row/window background — see app's
                // Theme.CallX), so a plain fill-only pill was invisible
                // here, just floating text with no visible button. A
                // divider-colored stroke keeps it visible in both modes
                // (divider is off-white in light, dark gray in night).
                bg.setColor(resolveAttrColor(com.google.android.material.R.attr.colorSurfaceVariant));
                bg.setStroke((int) (1 * btn.getResources().getDisplayMetrics().density),
                        getResources().getColor(com.callx.app.core.R.color.divider, null));
                btn.setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
            }
            btn.setBackground(bg);
        }

        @Override public int getItemCount() { return differ.getCurrentList().size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView        tvName, tvBio;
            Button          btnAction;

            /** Which user this recycled row currently displays — refreshed
             *  on every bind, read by every listener below (all attached
             *  once, here in the constructor, instead of freshly per bind)
             *  — same pattern as ReelCommentsAdapter.VH. */
            UserItem boundItem;

            VH(View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_avatar);
                tvName    = v.findViewById(R.id.tv_name);
                tvBio     = v.findViewById(R.id.tv_bio);
                btnAction = v.findViewById(R.id.btn_follow_action);

                // PERF: every listener below is attached ONCE here rather
                // than reassigned on every onBindViewHolder call — during a
                // fast fling a recycled row rebinds constantly, so a fresh
                // lambda per bind per listener adds up fast. Each one reads
                // the LIVE boundItem / getAdapterPosition() at click time.
                ivAvatar.setOnClickListener(v2 -> {
                    if (boundItem != null)
                        ReelUserProfileSheet.show(FollowConnectionsActivity.this,
                                boundItem.uid, boundItem.name, boundItem.photo);
                });

                itemView.setOnClickListener(v2 -> {
                    if (boundItem == null) return;
                    Intent i = new Intent(FollowConnectionsActivity.this, UserReelsActivity.class);
                    i.putExtra(UserReelsActivity.EXTRA_UID,   boundItem.uid);
                    i.putExtra(UserReelsActivity.EXTRA_NAME,  boundItem.name != null ? boundItem.name : "");
                    i.putExtra(UserReelsActivity.EXTRA_PHOTO, boundItem.photo != null ? boundItem.photo : "");
                    startActivity(i);
                });

                // Follow-state action button — behavior depends on which
                // tab this row lives in and the LIVE myFollowing membership
                // at click time (not whatever it was when this row was
                // bound), so one listener covers every tab/state.
                btnAction.setOnClickListener(v2 -> {
                    if (boundItem == null) return;
                    int pos = getAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    switch (tabIdx) {
                        case TAB_FOLLOWERS:
                        case TAB_MUTUAL:
                            boolean currentlyFollowing = myFollowing.contains(boundItem.uid);
                            toggleFollowFromBtn(boundItem, this, pos, currentlyFollowing);
                            break;
                        case TAB_FOLLOWING:
                            unfollowUser(boundItem, pos);
                            break;
                        case TAB_SUGGESTED:
                            toggleFollowFromBtn(boundItem, this, pos, false);
                            break;
                    }
                });
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Follow actions
    // ══════════════════════════════════════════════════════════════════════

    private void toggleFollowFromBtn(UserItem u, UserListAdapter.VH h, int pos, boolean currentlyFollowing) {
        String myUid = safeMyUid(); if (myUid == null) return;
        if (currentlyFollowing) {
            myFollowing.remove(u.uid);
            FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).removeValue();
            FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).removeValue();
        } else {
            myFollowing.add(u.uid);
            FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).setValue(true);
            FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).setValue(true);
        }
        // PERF: was a full notifyDataSetChanged() across all 4 tab adapters
        // — rebuilt every visible row (avatar Glide reload, bio text,
        // listener churn) just to flip one button's follow state. Payload-
        // based partial rebind touches only the one row (in each tab) that
        // actually shows this uid.
        for (UserListAdapter a : adapters) if (a != null) a.notifyFollowStateChanged(u.uid);
    }

    private void unfollowUser(UserItem u, int pos) {
        String myUid = safeMyUid(); if (myUid == null) return;
        AlertDialogStyler.showReusableConfirm(this, "unfollow_user_connections",
            AlertDialogStyler.DialogSize.DEFAULT,
            "Unfollow " + (u.name != null ? u.name : "this user") + "?", null,
            "Unfollow", () -> {
                FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).removeValue();
                FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).removeValue();
                allItems[TAB_FOLLOWING].remove(u);
                filteredItems[TAB_FOLLOWING].remove(u);
                counts[TAB_FOLLOWING] = Math.max(0, counts[TAB_FOLLOWING] - 1);
                updateTabLabel(TAB_FOLLOWING);
                // PERF: submitList lets AsyncListDiffer compute a minimal
                // diff (one row removed) instead of notifyDataSetChanged()
                // rebuilding every remaining visible row.
                if (adapters[TAB_FOLLOWING] != null) adapters[TAB_FOLLOWING].submitList(filteredItems[TAB_FOLLOWING]);
                showEmpty(TAB_FOLLOWING, filteredItems[TAB_FOLLOWING].isEmpty());
            },
            null, null,
            "Cancel");
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI helpers
    // ══════════════════════════════════════════════════════════════════════

    private void showProgress(int tab, boolean show) {
        runOnUiThread(() -> {
            if (progresses[tab] != null)
                progresses[tab].setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    private void showEmpty(int tab, boolean show) {
        if (empties[tab] != null)
            empties[tab].setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * Update tab label to show count, e.g. "487 Followers", "132 Following", "3 Mutual".
     */
    private void updateTabLabel(int tab) {
        runOnUiThread(() -> {
            if (tabLayout == null) return;
            TabLayout.Tab t = tabLayout.getTabAt(tab);
            if (t == null) return;
            String[] labels = {"Followers", "Following", "Mutual", "Suggested"};
            int c = counts[tab];
            t.setText(c > 0 ? formatCount(c) + " " + labels[tab] : labels[tab]);
        });
    }

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Firebase helpers
    // ══════════════════════════════════════════════════════════════════════

    private UserItem parseUser(String uid, DataSnapshot snap) {
        String name  = snap.child("name").getValue(String.class);
        String thumb = snap.child("thumbUrl").getValue(String.class);
        String photo = snap.child("photoUrl").getValue(String.class);
        String bio   = snap.child("bio").getValue(String.class);
        // FIX (deep avatar pipeline): denormalized for FollowAvatarBinder.url()'s
        // responsive/version-tagged URL — same field every other avatar-bearing
        // screen (chat list, profile, search, status) already reads.
        Long avatarVer = snap.child("avatarVersion").getValue(Long.class);
        String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
        return new UserItem(uid, name != null ? name : uid, p != null ? p : "", bio != null ? bio : "",
                avatarVer != null ? avatarVer : 0L);
    }

    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }

    /** AvatarSource view over a single tab's currently-filtered list, for FollowAvatarBinder.prefetch(). Mirrors the old FollowersListActivity#followAvatarSource(). */
    private FollowAvatarBinder.AvatarSource followAvatarSource(int tabIdx) {
        List<UserItem> items = filteredItems[tabIdx];
        return new FollowAvatarBinder.AvatarSource() {
            @Override public String photo(int index) { return items.get(index).photo; }
            @Override public long avatarVersion(int index) { return items.get(index).avatarVersion; }
            @Override public int size() { return items.size(); }
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // Data class
    // ══════════════════════════════════════════════════════════════════════

    static class UserItem {
        String uid, name, photo, bio;
        long avatarVersion; // FIX: denormalized for FollowAvatarBinder.url()'s responsive/version-tagged URL
        UserItem(String uid, String name, String photo, String bio, long avatarVersion) {
            this.uid = uid; this.name = name; this.photo = photo; this.bio = bio;
            this.avatarVersion = avatarVersion;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Resolves a theme attribute (e.g. R.attr.colorSurface) to its current color,
     *  so dynamically-built views follow light/dark mode instead of hardcoded ARGB. */
    private int resolveAttrColor(int attrResId) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attrResId, tv, true);
        return tv.data;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Suggested tab — users targetUid follows but currentUser doesn't
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Load Suggested tab:
     * Candidates = users that targetUid follows AND currentUser does NOT follow yet.
     * Self is always excluded.
     */
    private void loadSuggested() {
        String myUid = safeMyUid();
        if (myUid == null || isSelf) {
            showProgress(TAB_SUGGESTED, false);
            showEmpty(TAB_SUGGESTED, true);
            return;
        }
        showProgress(TAB_SUGGESTED, true);

        // First load MY following list to know whom to exclude
        FirebaseUtils.getReelFollowsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot mySnap) {
                    final Set<String> iFollow = new HashSet<>();
                    iFollow.add(myUid); // exclude self
                    for (DataSnapshot s : mySnap.getChildren())
                        if (s.getKey() != null) iFollow.add(s.getKey());

                    // Now load who targetUid follows
                    FirebaseUtils.getReelFollowsRef(targetUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(@NonNull DataSnapshot tSnap) {
                                List<String> candidates = new ArrayList<>();
                                for (DataSnapshot child : tSnap.getChildren()) {
                                    String uid = child.getKey();
                                    if (uid != null && !iFollow.contains(uid))
                                        candidates.add(uid);
                                }

                                if (candidates.isEmpty()) {
                                    showProgress(TAB_SUGGESTED, false);
                                    showEmpty(TAB_SUGGESTED, true);
                                    counts[TAB_SUGGESTED] = 0;
                                    updateTabLabel(TAB_SUGGESTED);
                                    return;
                                }

                                allItems[TAB_SUGGESTED].clear();
                                counts[TAB_SUGGESTED] = candidates.size();
                                updateTabLabel(TAB_SUGGESTED);

                                final long total = candidates.size();
                                final long[] done = {0};
                                for (String uid : candidates) {
                                    FirebaseUtils.getUserRef(uid)
                                        .addListenerForSingleValueEvent(new ValueEventListener() {
                                            @Override
                                            public void onDataChange(@NonNull DataSnapshot us) {
                                                synchronized (allItems[TAB_SUGGESTED]) {
                                                    allItems[TAB_SUGGESTED].add(parseUser(uid, us));
                                                }
                                                done[0]++;
                                                checkDone(TAB_SUGGESTED, done, total);
                                            }
                                            @Override
                                            public void onCancelled(@NonNull DatabaseError e) {
                                                done[0]++;
                                                checkDone(TAB_SUGGESTED, done, total);
                                            }
                                        });
                                }
                            }
                            @Override
                            public void onCancelled(@NonNull DatabaseError e) {
                                showProgress(TAB_SUGGESTED, false);
                            }
                        });
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    showProgress(TAB_SUGGESTED, false);
                }
            });
    }
}
