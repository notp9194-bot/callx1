package com.callx.app.compose;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.cache.AvatarBinderCore;
import com.callx.app.cache.AvatarL2MemoryCache;
import com.callx.app.cache.AvatarL3DiskCache;
import com.callx.app.cache.StatusAvatarBinder;
import com.callx.app.cache.StatusAvatarL2Cache;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.interactions.StatusSeenByBottomSheet;
import com.callx.app.interactions.StatusSheetAvatar;
import com.callx.app.models.StatusItem;
import com.callx.app.status.R;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StoryRingApplier;
import com.callx.app.viewer.StatusViewerActivity;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v749: the template OWNER's "who used it" list — statusTemplateUses/{me}/{statusId} (uid → time), newest first.
 * Tap a row / "View" → that person's status in the normal viewer (the viewer applies its own privacy rules).
 *
 * v758: "Used This Template" screen (activity_template_uses.xml / item_template_use.xml) with the SAME optimizations as the
 * Status "Seen by" sheet (StatusSeenByBottomSheet v723/v725/v733):
 *  1. PAGING: newest 40 only (orderByValue().limitToLast(40) child listener = first page + live deltas); older pages
 *     on scroll (endAt(ts,key), inclusive cursor), footer spinner while loading, tap-to-retry bar on failure.
 *  2. LAZY profiles: only rows that are really bound fetch users/{uid}; max 6 in flight, latest-bound first.
 *  3. Profile cache: static LruCache(600) that survives re-opens + persisted to disk (800 entries); stale profiles are
 *     painted instantly and re-read only when bound AND older than 6 h. Chat partners are seeded from the Room cache.
 *     Fetched profiles are also handed to the Seen-by sheet cache (shared pre-warm).
 *  4. One bg thread builds/sorts the rows; debounced refresh (60 ms, 250 ms on busy lists); DiffUtil ListAdapter with a
 *     time-only payload (the "2m ago" ticker never rebinds avatar / ring).
 *  5. Pulsing skeleton rows until the first page arrives (no "empty" flash), proper first-page error state.
 *  6. Avatar = core AvatarBinderCore (StatusAvatarL2Cache L2+L3, versioned URL) with a same-photo skip, cancel on recycle,
 *     velocity prefetch; story ring = StoryRingApplier (avatar 30dp, ring 1.5dp, gap 2dp).
 *  Online dot: tiny users/{uid}/online read, only for bound rows, 4 in flight (never persisted — it is live data).
 * Deploy the {@code .indexOn [".value"]} rule for statusTemplateUses (firebase_template_rules.json) so paging is server-side.
 */
public class TemplateUsesActivity extends AppCompatActivity {

    public static final String EXTRA_STATUS_ID = "templateStatusId";

    // ── tuning (same values as the Seen-by sheet) ──
    private static final int PAGE = 40, LOAD_AHEAD = 12, MAX_INFLIGHT = 6, ONLINE_INFLIGHT = 4;
    private static final long PROFILE_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final String PROF_PREFS = "template_uses_profiles", PROF_KEY = "v1";
    private static final int PROF_DISK_MAX = 800;
    private static final Object PAYLOAD_TIME = "time";

    /** Single bg thread (ordered): disk load/save, Room seeds, row building + sorting. */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "template-uses-bg"); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });

    private static final class Prof {
        final String name, photo, username; final long at;
        Prof(String n, String p, String u, long at) { name = n; photo = p; username = u; this.at = at; }
    }
    /** uid -> profile; survives re-opens, persisted to disk, shown instantly even when stale. */
    private static final LruCache<String, Prof> PROFILES = new LruCache<>(600);
    private static volatile boolean DISK_LOADED;

    /** Immutable row (built on the bg thread, diffed by DiffUtil). name == null = profile not known yet. */
    private static final class Row {
        final String uid, name, username, photo; final long ts; final boolean online;
        Row(String uid, long ts, Prof p, boolean online) {
            this.uid = uid; this.ts = ts; this.online = online;
            name = p != null ? p.name : null;
            username = p != null && p.username != null ? p.username : "";
            photo = p != null && p.photo != null ? p.photo : "";
        }
        boolean sameContent(Row o) {
            return ts == o.ts && online == o.online && eq(name, o.name) && username.equals(o.username) && photo.equals(o.photo);
        }
        static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
    }

    private static final AvatarSizeTier AVATAR_TIER = AvatarSizeTier.forViewSizeDp(30);   // TINY (32dp)
    private static final DecodeFormat AVATAR_FORMAT = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            ? DecodeFormat.PREFER_ARGB_8888 : DecodeFormat.PREFER_RGB_565;
    /** Same L2/L3 pair the Status tab avatars use, handed to the core pipeline (AvatarBinderCore). */
    private static final AvatarBinderCore.CacheProvider AVATAR_CACHE = new AvatarBinderCore.CacheProvider() {
        @Override public AvatarL2MemoryCache l2(Context c) { return StatusAvatarL2Cache.get(c); }
        @Override public AvatarL3DiskCache l3(Context c) { return StatusAvatarL2Cache.l3(c); }
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private String me, statusId;
    private RecyclerView rv;
    private RowsAdapter adapter;
    private TextView usedCount, peopleTotal, empty, templateName, saveText, footerRetry;
    private ImageView thumb, saveIcon;
    private View saveBtn, skeleton, footerProgress;
    private android.animation.ObjectAnimator skelAnim;

    // header (status/{me}/{statusId}) — used by Save
    private String tplJson, tplStickers, tplOwnerName;
    private boolean saved;

    // paging / live state (main thread only)
    private DatabaseReference usesRef;
    private Query usesQ;
    private ChildEventListener usesCL;
    private final HashMap<String, Long> seenAt = new HashMap<>();
    private long cursorTs = Long.MAX_VALUE; private String cursorKey;
    private boolean initialLoaded, initialFailed, exhausted, loadingMore, loadFailed, refreshQueued;
    private int gen;   // bumps on retry so late callbacks of an old attach are ignored

    // lazy profiles
    private final HashMap<String, Prof> profiles = new HashMap<>();          // session copy (main thread)
    private volatile Map<String, Prof> chatSeeds = Collections.emptyMap();    // replaced once, never mutated
    private final Set<String> fetched = new HashSet<>(), inFlight = new HashSet<>(), queued = new HashSet<>();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Map<String, Boolean> online = new ConcurrentHashMap<>();
    private final Set<String> onlineTried = new HashSet<>(), onlineInFlight = new HashSet<>();
    private final ArrayDeque<String> onlinePending = new ArrayDeque<>();
    private boolean profilesDirty, saveQueued;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() { refreshQueued = false; if (!dead()) refreshNow(); }
    };
    private final Runnable timeTick = new Runnable() {
        @Override public void run() {
            if (dead()) return;
            final RecyclerView.LayoutManager lm = rv.getLayoutManager();
            if (lm instanceof LinearLayoutManager && adapter.getItemCount() > 0) {
                final int a = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
                final int z = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                if (a >= 0 && z >= a) adapter.notifyItemRangeChanged(a, z - a + 1, PAYLOAD_TIME);   // time text only
            }
            main.postDelayed(this, 60_000L);
        }
    };

    private boolean dead() { return isFinishing() || isDestroyed(); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        me = FirebaseUtils.getCurrentUid();
        statusId = getIntent().getStringExtra(EXTRA_STATUS_ID);
        setContentView(R.layout.activity_template_uses);

        usedCount = findViewById(R.id.tu_used_count);
        peopleTotal = findViewById(R.id.tu_people_total);
        empty = findViewById(R.id.tu_empty);
        templateName = findViewById(R.id.tu_template_name);
        thumb = findViewById(R.id.tu_thumb);
        saveBtn = findViewById(R.id.tu_save);
        saveText = findViewById(R.id.tu_save_text);
        saveIcon = findViewById(R.id.tu_save_icon);
        skeleton = findViewById(R.id.tu_skeleton);
        footerProgress = findViewById(R.id.tu_footer_progress);
        footerRetry = findViewById(R.id.tu_footer_retry);
        findViewById(R.id.tu_back).setOnClickListener(v -> finish());

        rv = findViewById(R.id.tu_list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemAnimator(null);   // rows only change content, never reorder visibly — skip the per-row animation cost
        adapter = new RowsAdapter();
        rv.setAdapter(adapter);
        final AvatarBinderCore.AvatarSource src = new AvatarBinderCore.AvatarSource() {
            @Override public String photo(int i) { final List<Row> l = adapter.getCurrentList(); return i >= 0 && i < l.size() ? l.get(i).photo : null; }
            @Override public long avatarVersion(int i) { final List<Row> l = adapter.getCurrentList(); return i >= 0 && i < l.size() ? StatusSheetAvatar.versionOf(TemplateUsesActivity.this, l.get(i).uid) : 0L; }
            @Override public int size() { return adapter.getItemCount(); }
        };
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                final RecyclerView.LayoutManager lm = r.getLayoutManager();
                if (!(lm instanceof LinearLayoutManager) || dy == 0) return;
                final int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                AvatarBinderCore.prefetch(TemplateUsesActivity.this, src, last + 1, Math.abs(dy) / 16f, AVATAR_TIER, AVATAR_CACHE);   // ~px/ms at 60fps
                maybeLoadMore();
            }
        });
        footerRetry.setOnClickListener(v -> { loadFailed = false; updateFooter(); loadMore(); });
        empty.setOnClickListener(v -> { if (initialFailed) retryInitial(); });

        if (me == null || me.isEmpty() || statusId == null || statusId.isEmpty()) { finish(); return; }

        findViewById(R.id.tu_more).setOnClickListener(v ->
                TemplateActions.showOwnerSheet(this, statusId, seenAt.size(), null, null, this::finish));
        bindCounts();
        setSkeleton(true);
        loadTemplateHeader();
        loadDiskAndRelationsAsync();
        attachLive();
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(timeTick);
        main.postDelayed(timeTick, 60_000L);
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(timeTick);
    }

    @Override protected void onDestroy() {
        detachLive();
        main.removeCallbacksAndMessages(null);
        if (skelAnim != null) skelAnim.cancel();
        if (profilesDirty) saveProfilesToDisk(getApplicationContext());
        super.onDestroy();
    }

    // ── header ───────────────────────────────────────────────────────────────

    /** "12.8K"-style count; "40+" while older pages still exist. */
    private String countText() {
        final int n = seenAt.size();
        final String c;
        if (n < 1000) c = String.valueOf(n);
        else {
            final String k = String.format(Locale.US, "%.1f", n / 1000f);
            c = (k.endsWith(".0") ? k.substring(0, k.length() - 2) : k) + "K";
        }
        return exhausted || n == 0 ? c : c + "+";
    }

    private void bindCounts() {
        final String c = countText();
        final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(c);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(getString(R.string.tpl_uses_people_used));
        usedCount.setText(sb);
        peopleTotal.setText(getString(R.string.tpl_uses_people_count, c));
    }

    /** Template summary card: thumbnail, name, Save — from status/{me}/{statusId} (gone/expired = placeholder + no Save). */
    private void loadTemplateHeader() {
        FirebaseUtils.db().getReference("status").child(me).child(statusId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (dead()) return;
                StatusItem it = null;
                try { it = s.getValue(StatusItem.class); } catch (Exception ignored) { }
                if (it == null) return;
                // Thumbnail (with the play icon) plays the template's own story in the normal viewer.
                findViewById(R.id.tu_thumb_wrap).setOnClickListener(v -> playTemplate());
                final String name = it.caption != null && !it.caption.trim().isEmpty() ? it.caption.trim()
                        : (it.text != null && !it.text.trim().isEmpty() ? it.text.trim() : null);
                if (name != null) templateName.setText(name);
                final String img = it.thumbnailUrl != null && !it.thumbnailUrl.isEmpty() ? it.thumbnailUrl : it.mediaUrl;
                if (img != null && !img.isEmpty()) {
                    final int r = Math.round(12 * getResources().getDisplayMetrics().density);
                    Glide.with(TemplateUsesActivity.this).load(img)
                            .apply(new RequestOptions().transform(new CenterCrop(), new RoundedCorners(r)))
                            .into(thumb);
                }
                tplJson = it.templateJson; tplStickers = it.stickersJson; tplOwnerName = it.ownerName;
                if (tplJson != null && !tplJson.isEmpty()) {
                    saveBtn.setVisibility(View.VISIBLE);
                    TemplateActions.isSaved(me, statusId, v -> { saved = v; paintSave(); });
                    saveBtn.setOnClickListener(v -> toggleSave());
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    /** Plays this template's story (status/{me}/{statusId}) alone in the normal status viewer; Back returns here. */
    private void playTemplate() {
        final Intent i = new Intent(this, StatusViewerActivity.class);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, me);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, tplOwnerName != null ? tplOwnerName : "");
        i.putExtra(StatusViewerActivity.EXTRA_TARGET_STATUS_ID, statusId);
        i.putExtra(StatusViewerActivity.EXTRA_SINGLE_STATUS_ONLY, true);
        startActivity(i);
    }

    private void toggleSave() {
        if (saved) { TemplateActions.unsave(me, statusId); saved = false; }
        else saved = TemplateActions.save(me, statusId, tplOwnerName, tplJson, tplStickers);
        paintSave();
    }

    private void paintSave() {
        saveIcon.setImageResource(saved ? com.callx.app.core.R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        saveText.setText(saved ? R.string.tpl_uses_saved : R.string.tpl_uses_save);
    }

    // ── live + paging ────────────────────────────────────────────────────────

    private void attachLive() {
        final int g = gen;
        try {
            usesRef = FirebaseUtils.db().getReference("statusTemplateUses").child(me).child(statusId);
            usesQ = usesRef.orderByValue().limitToLast(PAGE);
            usesCL = new ChildEventListener() {
                @Override public void onChildAdded(@NonNull DataSnapshot c, String prev) { onUse(g, c); }
                @Override public void onChildChanged(@NonNull DataSnapshot c, String prev) { onUse(g, c); }
                // The window slides when a new user arrives and reports the oldest entry as "removed" — NOT a deletion, ignore it.
                @Override public void onChildRemoved(@NonNull DataSnapshot c) { }
                @Override public void onChildMoved(@NonNull DataSnapshot c, String prev) { }
                @Override public void onCancelled(@NonNull DatabaseError e) { }
            };
            usesQ.addChildEventListener(usesCL);
            usesQ.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (dead() || g != gen) return;
                    initialLoaded = true;
                    if (snap.getChildrenCount() < PAGE) exhausted = true;
                    scheduleRefresh();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (dead() || g != gen) return;
                    initialFailed = true; initialLoaded = true;   // "couldn't load" + tap-to-retry instead of a false "no one yet"
                    scheduleRefresh();
                }
            });
        } catch (Exception ignored) { initialFailed = true; initialLoaded = true; scheduleRefresh(); }
    }

    private void detachLive() {
        try { if (usesQ != null && usesCL != null) usesQ.removeEventListener(usesCL); } catch (Exception ignored) { }
        usesCL = null;
    }

    private void retryInitial() {
        gen++;
        initialFailed = false; initialLoaded = false;
        detachLive();
        setSkeleton(true);
        empty.setVisibility(View.GONE);
        attachLive();
    }

    private void onUse(int g, DataSnapshot c) {
        if (dead() || g != gen || c.getKey() == null) return;
        final Object v = c.getValue();
        final long ts = v instanceof Number ? ((Number) v).longValue() : 0L;
        seenAt.put(c.getKey(), ts);
        noteCursor(c.getKey(), ts);
        scheduleRefresh();
    }

    private void noteCursor(String key, long ts) {
        if (ts < cursorTs || (ts == cursorTs && (cursorKey == null || key.compareTo(cursorKey) < 0))) { cursorTs = ts; cursorKey = key; }
    }

    /** Older users: one page per call, only when the list is scrolled near its end. */
    private void maybeLoadMore() {
        if (dead() || adapter == null) return;
        final LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        if (lm == null) return;
        final int total = adapter.getItemCount();
        if (total > 0 && lm.findLastVisibleItemPosition() >= total - LOAD_AHEAD) loadMore();
    }

    private void loadMore() {
        if (dead() || loadingMore || exhausted || usesRef == null || cursorKey == null || loadFailed || !initialLoaded) return;
        loadingMore = true;
        updateFooter();
        final int g = gen;
        // Inclusive cursor -> PAGE + 1 rows, the first of which (the cursor itself) is already loaded.
        usesRef.orderByValue().endAt((double) cursorTs, cursorKey).limitToLast(PAGE + 1).get()
                .addOnSuccessListener(snap -> BG.execute(() -> {
                    final HashMap<String, Long> m = new HashMap<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        if (c.getKey() == null) continue;
                        final Object v = c.getValue();
                        m.put(c.getKey(), v instanceof Number ? ((Number) v).longValue() : 0L);
                    }
                    main.post(() -> {
                        if (dead() || g != gen) return;
                        loadingMore = false;
                        for (Map.Entry<String, Long> e : m.entrySet()) { seenAt.put(e.getKey(), e.getValue()); noteCursor(e.getKey(), e.getValue()); }
                        if (m.size() < PAGE + 1) exhausted = true;
                        updateFooter();
                        scheduleRefresh();
                    });
                }))
                .addOnFailureListener(e -> main.post(() -> {
                    if (dead() || g != gen) return;
                    loadingMore = false; loadFailed = true;   // visible retry bar, not a silent retry
                    updateFooter();
                }));
    }

    private void updateFooter() {
        footerProgress.setVisibility(loadingMore ? View.VISIBLE : View.GONE);
        footerRetry.setVisibility(loadFailed ? View.VISIBLE : View.GONE);
    }

    // ── refresh (bg build + DiffUtil) ────────────────────────────────────────

    private void scheduleRefresh() {
        if (refreshQueued || dead()) return;
        refreshQueued = true;
        main.postDelayed(refreshRunnable, seenAt.size() > 400 ? 250 : 60);   // busy list recomputes less often; a small one stays instant
    }

    private Prof profileOf(String uid) {
        Prof p = profiles.get(uid);
        if (p == null) { p = PROFILES.get(uid); if (p != null) profiles.put(uid, p); }
        return p != null ? p : chatSeeds.get(uid);
    }

    private void refreshNow() {
        final HashMap<String, Long> snapshot = new HashMap<>(seenAt);
        final HashMap<String, Prof> profs = new HashMap<>(snapshot.size() * 2);
        for (String uid : snapshot.keySet()) { final Prof p = profileOf(uid); if (p != null) profs.put(uid, p); }
        final HashMap<String, Boolean> on = new HashMap<>(online);
        final boolean loaded = initialLoaded, failed = initialFailed;
        final int g = gen;
        BG.execute(() -> {
            final ArrayList<Row> out = new ArrayList<>(snapshot.size());
            for (Map.Entry<String, Long> e : snapshot.entrySet())
                out.add(new Row(e.getKey(), e.getValue(), profs.get(e.getKey()), Boolean.TRUE.equals(on.get(e.getKey()))));
            Collections.sort(out, (x, y) -> x.ts != y.ts ? Long.compare(y.ts, x.ts) : x.uid.compareTo(y.uid));
            main.post(() -> {
                if (dead() || g != gen) return;
                adapter.submitList(out);
                bindCounts();
                final boolean none = out.isEmpty();
                setSkeleton(!loaded && none);
                if (loaded && none) {
                    empty.setText(failed ? R.string.tpl_uses_error : R.string.tpl_uses_empty);
                    empty.setVisibility(View.VISIBLE);
                } else empty.setVisibility(View.GONE);
            });
        });
    }

    private void setSkeleton(boolean show) {
        if (skeleton == null) return;
        if (show) {
            if (skeleton.getVisibility() == View.VISIBLE) return;
            skeleton.setVisibility(View.VISIBLE);
            skelAnim = android.animation.ObjectAnimator.ofFloat(skeleton, "alpha", 1f, 0.45f);
            skelAnim.setDuration(750);
            skelAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            skelAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            skelAnim.start();
        } else if (skeleton.getVisibility() != View.GONE) {
            if (skelAnim != null) skelAnim.cancel();
            skeleton.setAlpha(1f);
            skeleton.setVisibility(View.GONE);
        }
    }

    // ── profile cache: disk + Room seeds ─────────────────────────────────────

    private void loadDiskAndRelationsAsync() {
        final Context app = getApplicationContext();
        BG.execute(() -> {
            if (!DISK_LOADED) {
                try {
                    final String raw = app.getSharedPreferences(PROF_PREFS, Context.MODE_PRIVATE).getString(PROF_KEY, null);
                    if (raw != null) for (String line : raw.split("\n")) {
                        final String[] f = line.split("\t", -1);
                        if (f.length < 5 || f[0].isEmpty() || PROFILES.get(f[0]) != null) continue;   // fresher in-memory copy wins
                        long at;
                        try { at = Long.parseLong(f[4]); } catch (NumberFormatException e) { continue; }
                        PROFILES.put(f[0], new Prof(f[1], f[2].isEmpty() ? null : f[2], f[3], at));
                    }
                } catch (Exception ignored) { }
                DISK_LOADED = true;
            }
            // Chat partners / cached names from Room (no Firebase read).
            final HashMap<String, Prof> seeds = new HashMap<>();
            try {
                final List<ChatEntity> chats = AppDatabase.getInstance(app).chatDao().getAllChatsSync();
                if (chats != null) for (ChatEntity c : chats) {
                    if (c == null || c.partnerUid == null || c.partnerUid.isEmpty()) continue;
                    if (c.partnerName != null && !c.partnerName.isEmpty()) {
                        final String ph = c.partnerPhoto != null && !c.partnerPhoto.isEmpty() ? c.partnerPhoto : c.partnerThumb;
                        seeds.put(c.partnerUid, new Prof(c.partnerName, ph, "", 0L));
                    }
                }
            } catch (Exception ignored) { }
            main.post(() -> { if (dead()) return; chatSeeds = seeds; scheduleRefresh(); });
        });
    }

    private static void saveProfilesToDisk(final Context app) {
        BG.execute(() -> {
            try {
                final StringBuilder sb = new StringBuilder();
                int n = 0;
                for (Map.Entry<String, Prof> e : PROFILES.snapshot().entrySet()) {
                    final Prof p = e.getValue();
                    if (p == null || p.at <= 0L) continue;
                    if (n++ >= PROF_DISK_MAX) break;
                    sb.append(clean(e.getKey())).append('\t').append(clean(p.name)).append('\t').append(clean(p.photo)).append('\t')
                      .append(clean(p.username)).append('\t').append(p.at).append('\n');
                }
                app.getSharedPreferences(PROF_PREFS, Context.MODE_PRIVATE).edit().putString(PROF_KEY, sb.toString()).apply();
            } catch (Exception ignored) { }
        });
    }

    private static String clean(String x) { return x == null ? "" : x.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    // ── lazy profile fetch (only bound rows) ─────────────────────────────────

    private boolean needsFetch(String uid) {
        if (fetched.contains(uid) || inFlight.contains(uid)) return false;
        final Prof p = PROFILES.get(uid) != null ? PROFILES.get(uid) : profiles.get(uid);
        return p == null || System.currentTimeMillis() - p.at > PROFILE_TTL_MS;
    }

    /** Called when a row is bound (i.e. visible). Latest-bound rows are fetched first. */
    private void requestProfile(String uid) {
        if (dead()) return;
        if (needsFetch(uid) && queued.add(uid)) pending.addLast(uid);
        pumpProfiles();
        // Fresh cached profile -> no users/{uid} read, but the live online dot still needs its tiny read (once per session).
        if (!needsFetch(uid) && !inFlight.contains(uid) && !queued.contains(uid) && !online.containsKey(uid) && onlineTried.add(uid)) { onlinePending.addLast(uid); pumpOnline(); }
    }

    private void pumpProfiles() {
        while (!dead() && inFlight.size() < MAX_INFLIGHT && !pending.isEmpty()) {
            final String uid = pending.pollLast();
            queued.remove(uid);
            if (!needsFetch(uid)) continue;
            inFlight.add(uid);
            fetchProfile(uid);
        }
    }

    private void fetchProfile(final String uid) {
        FirebaseUtils.db().getReference("users").child(uid).get()
                .addOnSuccessListener(snap -> BG.execute(() -> {
                    Prof parsed = null;
                    try {
                        final String name = snap.child("name").getValue(String.class);
                        // Full photoUrl first (buildResponsive builds the TINY variant from it) -> shares the cache key with the other
                        // status avatars; thumbUrl only as fallback.
                        String photo = snap.child("photoUrl").getValue(String.class);
                        if (photo == null || photo.isEmpty()) photo = snap.child("thumbUrl").getValue(String.class);
                        final String un = snap.child("username").getValue(String.class);
                        final Boolean on = snap.child("online").getValue(Boolean.class);
                        online.put(uid, on != null && on);
                        parsed = new Prof(name != null ? name : uid, photo, un != null ? un : "", System.currentTimeMillis());
                        PROFILES.put(uid, parsed);
                        StatusSeenByBottomSheet.putProfile(uid, parsed.name, photo);   // pre-warm the Seen-by sheet's cache too
                    } catch (Exception ignored) { /* malformed profile node -> treated as a failed read */ }
                    final Prof p = parsed;
                    main.post(() -> onProfileDone(uid, p));
                }))
                .addOnFailureListener(e -> main.post(() -> onProfileDone(uid, null)));
    }

    private void onProfileDone(String uid, Prof p) {
        if (dead()) return;
        inFlight.remove(uid);
        fetched.add(uid);
        if (p != null) {
            profiles.put(uid, p);
            scheduleRefresh();
            profilesDirty = true;
            if (!saveQueued) {
                saveQueued = true;
                main.postDelayed(() -> { saveQueued = false; profilesDirty = false; saveProfilesToDisk(getApplicationContext()); }, 4000L);
            }
        }
        pumpProfiles();
    }

    private void pumpOnline() {
        while (!dead() && onlineInFlight.size() < ONLINE_INFLIGHT && !onlinePending.isEmpty()) {
            final String uid = onlinePending.pollLast();   // latest-bound first
            onlineInFlight.add(uid);
            FirebaseUtils.db().getReference("users").child(uid).child("online").get()
                    .addOnSuccessListener(s -> { onlineInFlight.remove(uid); if (dead()) return;
                        online.put(uid, Boolean.TRUE.equals(s.getValue(Boolean.class))); scheduleRefresh(); pumpOnline(); })
                    .addOnFailureListener(e -> { onlineInFlight.remove(uid); if (!dead()) pumpOnline(); });
        }
    }

    // ── actions ──────────────────────────────────────────────────────────────

    private void openStory(Row r) {
        final Intent i = new Intent(this, StatusViewerActivity.class);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, r.uid);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, r.name != null ? r.name : "");
        if (!r.photo.isEmpty()) i.putExtra(StatusViewerActivity.EXTRA_OWNER_PHOTO, r.photo);
        startActivity(i);
    }

    // ── adapter ──────────────────────────────────────────────────────────────

    private static final DiffUtil.ItemCallback<Row> DIFF = new DiffUtil.ItemCallback<Row>() {
        @Override public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) { return a.uid.equals(b.uid); }
        @Override public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) { return a.sameContent(b); }
    };

    private final class RowsAdapter extends ListAdapter<Row, RowsAdapter.VH> {
        RowsAdapter() { super(DIFF); }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView avatar, ring; final View online, viewBtn; final TextView name, username, time;
            Row row; String boundAvatarKey;
            VH(View v) {
                super(v);
                avatar = v.findViewById(R.id.tu_avatar);
                ring = v.findViewById(R.id.tu_ring);
                online = v.findViewById(R.id.tu_online);
                viewBtn = v.findViewById(R.id.tu_view);
                name = v.findViewById(R.id.tu_name);
                username = v.findViewById(R.id.tu_username);
                time = v.findViewById(R.id.tu_time);
                final View.OnClickListener open = x -> { if (row != null) openStory(row); };
                v.setOnClickListener(open);
                viewBtn.setOnClickListener(open);
            }

            void bindTime(Row r) {
                time.setText(android.text.format.DateUtils.getRelativeTimeSpanString(
                        r.ts, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
            }

            void bind(Row r) {
                row = r;
                name.setText(r.name != null ? (r.name.isEmpty() ? getString(R.string.tpl_uses_user) : r.name) : getString(R.string.tpl_uses_name_loading));
                username.setText(r.username.isEmpty() ? "" : "@" + r.username);
                username.setVisibility(r.username.isEmpty() ? View.GONE : View.VISIBLE);
                bindTime(r);
                online.setVisibility(r.online ? View.VISIBLE : View.GONE);
                // Same photo + same avatar version already in this holder (name / online rebind) -> no URL work, no Glide request.
                final long av = StatusSheetAvatar.versionOf(TemplateUsesActivity.this, r.uid);
                final String key = r.photo.isEmpty() ? null : r.photo + "#" + av;
                if (key == null || !key.equals(boundAvatarKey)) {
                    boundAvatarKey = key;
                    AvatarBinderCore.bind(getApplicationContext(), avatar, r.photo.isEmpty() ? null : r.photo, av, AVATAR_CACHE,
                            new AvatarBinderCore.BindOptions(AVATAR_TIER, AVATAR_FORMAT, /*circleCrop=*/true,
                                    /*dontAnimate=*/true, /*recordDashboardStats=*/true, R.drawable.ic_person));
                    StatusAvatarBinder.setInitialsIfNoPhoto(avatar, r.photo, r.name, r.uid);
                }
                // Gradient = unseen story, gray = all seen, hidden = none; live via StoryRingRegistry; tap opens the story.
                StoryRingApplier.applyWithClick(TemplateUsesActivity.this, ring, r.uid, r.name);
                requestProfile(r.uid);   // lazy: only rows that are really bound fetch their profile
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_template_use, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int position) { h.bind(getItem(position)); }

        @Override public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty() && payloads.contains(PAYLOAD_TIME) && h.row != null && h.row.uid.equals(getItem(position).uid)) {
                h.bindTime(getItem(position));   // time tick only: no avatar / ring work
            } else h.bind(getItem(position));
        }

        @Override public void onViewRecycled(@NonNull VH h) {
            AvatarBinderCore.cancel(getApplicationContext(), h.avatar);
            h.boundAvatarKey = null;
            h.row = null;
        }
    }
}
