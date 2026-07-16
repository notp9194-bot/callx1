package com.callx.app.followers;

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
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.profile.ReelUserProfileSheet;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.reels.R;
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
    private static final int TAB_COUNT = 3;

    @SuppressWarnings("unchecked")
    private final List<UserItem>[] allItems      = new List[TAB_COUNT];
    @SuppressWarnings("unchecked")
    private final List<UserItem>[] filteredItems = new List[TAB_COUNT];
    private final UserListAdapter[] adapters     = new UserListAdapter[TAB_COUNT];
    private final RecyclerView[]    rvs          = new RecyclerView[TAB_COUNT];
    private final ProgressBar[]     progresses   = new ProgressBar[TAB_COUNT];
    private final LinearLayout[]    empties      = new LinearLayout[TAB_COUNT];

    private final int[] counts = {0, 0, 0};   // follower / following / mutual count

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
        String[] defaultLabels = {"Followers", "Following", "Mutual"};
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
        String[] titles = {"No followers yet", "Not following anyone", "No mutual followers"};
        title.setText(titles[Math.min(tabIdx, 2)]);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextColor(0xFFFFE082);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(14);
        ll.addView(title, titleLp);

        String[] subs = {
            "Followers will appear here.",
            "This user hasn't followed anyone.",
            "You and this user have no followers in common."
        };
        TextView sub = new TextView(ctx);
        sub.setText(subs[Math.min(tabIdx, 2)]);
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setTextColor(0xFF888888);
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
                    for (UserListAdapter a : adapters) if (a != null) a.notifyDataSetChanged();
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

        if (adapters[tab] != null) adapters[tab].notifyDataSetChanged();

        boolean empty = filteredItems[tab].isEmpty();
        showEmpty(tab, empty && allItems[tab].isEmpty() && query.isEmpty());

        // Cross-tab search hint — works from ANY tab (Followers / Following / Mutual)
        if (empty && !query.isEmpty()) {
            checkCrossTabs(tab, query);
        }
    }

    private static final String[] TAB_LABELS = {"Followers", "Following", "Mutual Followers"};

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
        cardBg.setColor(0xFFFFFFFF);
        root.setBackground(cardBg);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                (int) (260 * density), ViewGroup.LayoutParams.WRAP_CONTENT));

        // Avatar
        CircleImageView iv = new CircleImageView(ctx);
        int avSz = (int) (52 * density);
        iv.setImageResource(R.drawable.ic_person);
        if (user.photo != null && !user.photo.isEmpty()) {
            Glide.with(ctx).load(user.photo)
                .apply(RequestOptions.circleCropTransform())
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
        tvName.setTextColor(0xFF111111);
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
        foundLabel.setTextColor(0xFF888888);
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
        int[] chipColors = {0xFF6C5CE7, 0xFF00B894, 0xFFFF7675}; // Followers / Following / Mutual
        chipBg.setColor(chipColors[foundTabIdx]);
        chip.setBackground(chipBg);
        chip.setTypeface(null, android.graphics.Typeface.BOLD);
        chipRow.addView(chip);

        // Divider
        View divider = new View(ctx);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (density));
        divLp.topMargin = (int) (16 * density);
        divider.setBackgroundColor(0xFFEDEDED);
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

        dialog.show();
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
            bg.setColor(0xFFF2F2F2);
            btn.setTextColor(0xFF444444);
        }
        btn.setBackground(bg);
    }

    // ══════════════════════════════════════════════════════════════════════
    // RecyclerView adapter (shared across all tabs)
    // ══════════════════════════════════════════════════════════════════════

    private class UserListAdapter extends RecyclerView.Adapter<UserListAdapter.VH> {

        private final List<UserItem> data;
        private final int            tabIdx;

        UserListAdapter(List<UserItem> data, int tabIdx) {
            this.data   = data;
            this.tabIdx = tabIdx;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            View v = getLayoutInflater().inflate(R.layout.item_follow_user, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            UserItem u = data.get(pos);

            // Avatar
            h.ivAvatar.setImageResource(R.drawable.ic_person);
            if (u.photo != null && !u.photo.isEmpty()) {
                Glide.with(FollowConnectionsActivity.this)
                    .load(u.photo)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .into(h.ivAvatar);
            }

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

            // Clicks
            h.ivAvatar.setOnClickListener(v ->
                ReelUserProfileSheet.show(FollowConnectionsActivity.this, u.uid, u.name, u.photo));

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(FollowConnectionsActivity.this, UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID,   u.uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME,  u.name != null ? u.name : "");
                i.putExtra(UserReelsActivity.EXTRA_PHOTO, u.photo != null ? u.photo : "");
                startActivity(i);
            });
        }

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
                        h.btnAction.setOnClickListener(v -> toggleFollowFromBtn(u, h, pos, true));
                    } else {
                        styleBtn(h.btnAction, "Follow", true);
                        h.btnAction.setOnClickListener(v -> toggleFollowFromBtn(u, h, pos, false));
                    }
                    break;

                case TAB_FOLLOWING:
                    // "Following" / "Unfollow" only for own profile
                    if (isSelf && myUid != null && !u.uid.equals(myUid)) {
                        h.btnAction.setVisibility(View.VISIBLE);
                        styleBtn(h.btnAction, "Following", false);
                        h.btnAction.setOnClickListener(v -> unfollowUser(u, pos));
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
                        h.btnAction.setOnClickListener(v -> toggleFollowFromBtn(u, h, pos, true));
                    } else {
                        styleBtn(h.btnAction, "Follow Back", true);
                        h.btnAction.setOnClickListener(v -> toggleFollowFromBtn(u, h, pos, false));
                    }
                    break;
            }
        }

        private void styleBtn(Button btn, String text, boolean filled) {
            btn.setText(text);
            android.util.TypedValue tv = new android.util.TypedValue();
            if (filled) {
                btn.setBackgroundColor(getResources().getColor(R.color.brand_primary, null));
                btn.setTextColor(0xFFFFFFFF);
            } else {
                btn.setBackgroundColor(0xFF333333);
                btn.setTextColor(0xFFCCCCCC);
            }
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView        tvName, tvBio;
            Button          btnAction;
            VH(View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_avatar);
                tvName    = v.findViewById(R.id.tv_name);
                tvBio     = v.findViewById(R.id.tv_bio);
                btnAction = v.findViewById(R.id.btn_follow_action);
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
        // Refresh the row
        for (UserListAdapter a : adapters) if (a != null) a.notifyDataSetChanged();
    }

    private void unfollowUser(UserItem u, int pos) {
        String myUid = safeMyUid(); if (myUid == null) return;
        new AlertDialog.Builder(this)
            .setTitle("Unfollow " + (u.name != null ? u.name : "this user") + "?")
            .setPositiveButton("Unfollow", (d, w) -> {
                FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).removeValue();
                FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).removeValue();
                allItems[TAB_FOLLOWING].remove(u);
                filteredItems[TAB_FOLLOWING].remove(u);
                counts[TAB_FOLLOWING] = Math.max(0, counts[TAB_FOLLOWING] - 1);
                updateTabLabel(TAB_FOLLOWING);
                if (adapters[TAB_FOLLOWING] != null) adapters[TAB_FOLLOWING].notifyDataSetChanged();
                showEmpty(TAB_FOLLOWING, filteredItems[TAB_FOLLOWING].isEmpty());
            })
            .setNegativeButton("Cancel", null)
            .show();
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
            String[] labels = {"Followers", "Following", "Mutual"};
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
        String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
        return new UserItem(uid, name != null ? name : uid, p != null ? p : "", bio != null ? bio : "");
    }

    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Data class
    // ══════════════════════════════════════════════════════════════════════

    static class UserItem {
        String uid, name, photo, bio;
        UserItem(String uid, String name, String photo, String bio) {
            this.uid = uid; this.name = name; this.photo = photo; this.bio = bio;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
