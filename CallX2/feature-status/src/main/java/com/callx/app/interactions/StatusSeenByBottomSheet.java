package com.callx.app.interactions;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.feed.StatusListAdapter;
import com.callx.app.models.StatusItem;
import com.callx.app.status.R;
import com.callx.app.utils.AlertDialogStyler;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusCloseFriendsManager;
import com.callx.app.utils.StatusPrivacyManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * StatusSeenByBottomSheet v30 — Instagram "Seen by N" screen (dark, full height).
 *
 *  v30 UI rewrite (logic of v29 untouched — live listeners, lazy profiles, compute(), menu, hide/block):
 *   - top bar: back + "Seen by N" + close; story thumbnail carousel with 👁 counts (tap = switch story)
 *   - "N viewers" + "Sort by" pill (sort + filters + reaction filter live in its popup), search behind the 🔍 icon
 *   - rows: story-ring avatar, name, "2h ago", ⋯ menu, paper-plane (message)
 *   - stats strip / emoji strip / filter chips removed (their data moved into the Sort by popup)
 *
 * (v29 notes below)
 *
 *  v29 polish (on top of v28):
 *   1. LIVE: seenBy / reactions / replies are listened to while the sheet is open — new viewers,
 *      reactions and replies appear without re-opening.
 *   2. LAZY profiles: only rows that are actually bound (visible) fetch users/{uid}; at most
 *      {@link #MAX_INFLIGHT} reads in flight, latest-bound first, fresh-cached profiles are never
 *      re-read, chat partners are painted instantly from the local Room cache. Searching pulls the
 *      remaining profiles in (throttled) because names are needed to match.
 *   3. SEARCH + KEYBOARD: stats / emoji header collapses while searching, list height is derived from
 *      what is really left on screen, ✕ clear button, keyboard closes on scroll / IME search.
 *   4. TIME: "just now", "12 min ago", "3h ago", "today, 7:05 AM", "yesterday, 9:10 PM", "4 Nov, 9:10 PM".
 *   5. FOLLOW TAGS: "Not following" → "Not a follower", and chat contacts get a "Contact" tag.
 *   6. HIDE / BLOCK: Undo snackbar, "Hidden" marker on the row, Unhide in the menu, stats follow blocks.
 *   7. Chip counts + tappable emoji chips (filter by that reaction).
 *   8. Vector icons, red destructive Block, icon menu, proper empty states.
 *   9. Reply snippet shows "+N more" and tapping it opens the chat with that viewer.
 *  10. Every label lives in strings_status_seenby.xml (+ values-hi).
 *  11. Filter + sort are remembered between openings.
 */
public class StatusSeenByBottomSheet {

    /** Single bg thread (ordered): parsing, filtering, sorting. */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "status-seenby-bg"); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });

    /** uid -> profile. Survives sheet re-opens; fresh entries (< TTL) are shown without any Firebase read. */
    private static final LruCache<String, Profile> PROFILES = new LruCache<>(600);
    private static final long PROFILE_TTL_MS = 5 * 60 * 1000L;
    private static final int  MAX_INFLIGHT   = 6;

    private static final String PREFS = "status_seenby_prefs";
    private static final String KEY_FILTER = "filter", KEY_RELEVANCE = "relevance_sort";

    private static final Object PAYLOAD_TIME = "time";
    private static final int RED = 0xFFE53935;

    private static final int F_ALL = 0, F_FOLLOWERS = 1, F_NOT_FOLLOWING = 2, F_REACTIONS = 3, F_REPLIES = 4, F_CLOSE = 5;
    private static final int[] FILTER_LABELS = {
            R.string.seenby_filter_all, R.string.seenby_filter_followers, R.string.seenby_filter_not_followers,
            R.string.seenby_filter_reactions, R.string.seenby_filter_replies, R.string.seenby_filter_close };

    /** Immutable profile (name never null once fetched — falls back to the uid like before). */
    static final class Profile {
        final String name, photo; final long at;
        Profile(String name, String photo, long at) { this.name = name; this.photo = photo; this.at = at; }
    }

    /** Immutable row model (DiffUtil compares by value). */
    static final class Row {
        final String uid, name, nameLower, photo, reaction, reply;
        final long seenAt;
        final int replyCount;
        final boolean follower, contact, closeFriend, hidden;
        Row(String uid, String name, String photo, long seenAt, String reaction, String reply, int replyCount,
            boolean follower, boolean contact, boolean closeFriend, boolean hidden) {
            this.uid = uid; this.name = name; this.photo = photo; this.seenAt = seenAt;
            this.nameLower = name == null ? null : name.toLowerCase(Locale.getDefault());
            this.reaction = reaction; this.reply = reply; this.replyCount = replyCount;
            this.follower = follower; this.contact = contact; this.closeFriend = closeFriend; this.hidden = hidden;
        }
        static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
        boolean sameContent(Row o) {
            return seenAt == o.seenAt && eq(name, o.name) && eq(photo, o.photo)
                    && eq(reaction, o.reaction) && eq(reply, o.reply) && replyCount == o.replyCount
                    && follower == o.follower && contact == o.contact
                    && closeFriend == o.closeFriend && hidden == o.hidden;
        }
        /** Higher = nearer the top in "Relevance" sort. */
        int score() {
            return (closeFriend ? 4 : 0) + (reply != null ? 3 : 0) + (reaction != null ? 2 : 0) + (follower ? 1 : 0);
        }
    }

    /** Resolved ONCE per sheet. */
    private static final class Style { int primary, secondary, divider, onPrimary, accent, surface, bg, pill; }

    /** Everything the bg thread needs, copied on main. */
    private static final class Snap {
        HashMap<String, Long> seenAt; HashMap<String, String> reactions; ArrayList<StatusItem.ReplyPreview> replies;
        HashMap<String, Profile> profiles; Map<String, Profile> seeds;
        Set<String> followers, contacts, closeFriends, removed, except, only;
        String mode, query, emoji; boolean followersLoaded, relevance; int filter;
    }

    private static final class Result {
        List<Row> rows; int totalRows, views, likes, replies; int[] counts;
        LinkedHashMap<String, Integer> emoji;
    }

    private static final class Env {
        Context ctx; Style style; StatusItem item; BottomSheetDialog sheet; LinearLayout root;
        String myUid, ownerUid, statusId;
        final Handler main = new Handler(Looper.getMainLooper());

        // live data (main thread only)
        final HashMap<String, Long> seenAt = new HashMap<>();
        final HashMap<String, String> reactions = new HashMap<>();
        final HashMap<String, StatusItem.ReplyPreview> replies = new HashMap<>();

        // profiles (main thread only)
        final HashMap<String, Profile> profiles = new HashMap<>();
        volatile Map<String, Profile> chatSeeds = Collections.emptyMap(); // replaced once, never mutated
        final Set<String> fetched = new HashSet<>(), inFlight = new HashSet<>(), queued = new HashSet<>(), bound = new HashSet<>();
        final ArrayDeque<String> pending = new ArrayDeque<>();

        // relations
        final Set<String> followers = new HashSet<>(), contacts = new HashSet<>(), removed = new HashSet<>();
        boolean followersLoaded;

        // time formatting (main thread only)
        final java.text.DateFormat timeFmt;
        final SimpleDateFormat dateFmt = new SimpleDateFormat("d MMM", Locale.getDefault());
        final SimpleDateFormat dateYearFmt = new SimpleDateFormat("d MMM yyyy", Locale.getDefault());
        final Calendar cal = Calendar.getInstance(), cal2 = Calendar.getInstance();
        Drawable hiddenIcon;

        boolean dismissed, refreshQueued; int gen;
        RowsAdapter adapter; RecyclerView rv; ProgressBar progress;
        LinearLayout headerGroup, emptyBox; TextView tvEmptyTitle, tvEmptySub, btnEmptyClear;
        LinkedHashMap<String, Integer> lastEmoji = new LinkedHashMap<>();
        EditText search; ImageView clearBtn;

        // v30: story carousel + header widgets
        final ArrayList<StatusItem> stories = new ArrayList<>();
        final HashMap<String, Integer> cardCounts = new HashMap<>();   // statusId -> live view count (carousel)
        final ArrayList<Object[]> cardWatch = new ArrayList<>();       // {DatabaseReference, ValueEventListener}
        int selIdx, liveViews;
        RecyclerView carousel; CardAdapter cardAdapter;
        TextView tvTitle, tvViewers, sortLabel; FrameLayout searchBox;
        LinearLayout segBar; int topInset;   // v31: story-progress segments + applied status-bar inset

        // query state (main thread)
        String query = ""; int filter = F_ALL; boolean relevanceSort = false; String emojiFilter = null;
        int[] chipCounts = {-1, -1, -1, -1, -1, -1};
        Runnable searchRunnable;
        final Runnable refreshRunnable = new Runnable() {
            @Override public void run() { refreshQueued = false; if (!dismissed) refreshNow(Env.this); }
        };
        ValueEventListener seenL, reactL, replyL; DatabaseReference seenRef, reactRef, replyRef;

        Env(Context ctx) {
            this.ctx = ctx;
            this.timeFmt = android.text.format.DateFormat.getTimeFormat(ctx);
        }
    }

    /** Called once when the sheet closes, with every viewer the owner blocked in it (net of Undo). */
    public interface OnClosed { void onClosed(Set<String> removedUids); }

    /**
     * THE viewer set both the sheet and the viewer's bottom bar count: seenBy + reactors + repliers (with text),
     * minus blocked. One definition -> the bar's "Seen by N" always equals the sheet's title.
     */
    public static Set<String> viewerUids(StatusItem item, Set<String> removed) {
        HashSet<String> u = new HashSet<>();
        if (item == null) return u;
        if (item.seenBy != null) u.addAll(item.seenBy.keySet());
        if (item.reactions != null) for (Map.Entry<String, String> e : item.reactions.entrySet())
            if (e.getKey() != null && e.getValue() != null) u.add(e.getKey());
        if (item.replies != null) for (StatusItem.ReplyPreview rp : item.replies.values())
            if (rp != null && rp.uid != null && rp.text != null && !rp.text.isEmpty()) u.add(rp.uid);
        u.remove(null);
        if (removed != null) u.removeAll(removed);
        return u;
    }

    /** Shared profile cache (the sheet's rows use it too): cached photo of a viewer, or null. */
    public static String peekPhoto(String uid) {
        Profile p = uid != null ? PROFILES.get(uid) : null;
        return p != null && p.photo != null && !p.photo.isEmpty() ? p.photo : null;
    }

    /** Lets the viewer's bar pre-warm the sheet: a profile it already fetched is not read again when the sheet opens. */
    public static void putProfile(String uid, String name, String photo) {
        if (uid != null) PROFILES.put(uid, new Profile(name != null ? name : uid, photo, System.currentTimeMillis()));
    }

    /** Legacy overload — no dismiss callback (used when caller doesn't need resume). */
    public static void show(Context ctx, StatusItem item) {
        show(ctx, item, null, null);
    }

    /** Legacy overload — single story (carousel shows just this one). */
    public static void show(Context ctx, StatusItem item, Runnable onDismiss) {
        show(ctx, item, null, onDismiss);
    }

    /** Points the Env at one story: owner / id + a local seed of seenBy, reactions, replies. */
    private static void bindStory(Env env, StatusItem item) {
        env.item = item;
        env.ownerUid = item.ownerUid != null ? item.ownerUid : env.myUid;
        env.statusId = item.id;
        env.seenAt.clear(); env.reactions.clear(); env.replies.clear();
        if (item.seenBy != null) env.seenAt.putAll(item.seenBy);
        if (item.reactions != null) for (Map.Entry<String, String> e : item.reactions.entrySet())
            if (e.getKey() != null && e.getValue() != null) env.reactions.put(e.getKey(), e.getValue());
        if (item.replies != null) for (Map.Entry<String, StatusItem.ReplyPreview> e : item.replies.entrySet())
            if (e.getKey() != null && e.getValue() != null) env.replies.put(e.getKey(), e.getValue());
        env.liveViews = viewerUids(item, env.removed).size();   // same rule as compute() / the viewer's bar
    }

    /**
     * @param all every story of the owner (carousel on top); the entry matching {@code item} is selected.
     *            null / empty = carousel shows only {@code item}.
     */
    public static void show(Context ctx, StatusItem item, List<StatusItem> all, final Runnable onDismiss) {
        show(ctx, item, all, null, onDismiss == null ? null : r -> onDismiss.run());
    }

    /**
     * @param initialRemoved viewers already blocked from this sheet earlier (so a re-open matches the viewer's bar); may be null.
     * @param onClosed       receives the final blocked set when the sheet closes; may be null.
     */
    public static void show(Context ctx, StatusItem item, List<StatusItem> all,
                            Set<String> initialRemoved, final OnClosed onClosed) {
        final Env env = new Env(ctx);
        if (initialRemoved != null) env.removed.addAll(initialRemoved);
        env.style = resolveStyle(ctx);
        env.myUid = FirebaseUtils.getCurrentUid();
        int at = -1;
        if (all != null) for (StatusItem s : all) {
            if (s == null) continue;
            if (s == item || (s.id != null && s.id.equals(item.id))) at = env.stories.size();
            env.stories.add(s);
        }
        if (at < 0) { env.stories.add(item); at = env.stories.size() - 1; }
        env.selIdx = at;
        bindStory(env, env.stories.get(at));

        // remembered filter + sort (#11)
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int savedFilter = sp.getInt(KEY_FILTER, F_ALL);
        env.filter = savedFilter >= F_ALL && savedFilter <= F_CLOSE ? savedFilter : F_ALL;
        env.relevanceSort = sp.getBoolean(KEY_RELEVANCE, false);

        final BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        env.sheet = sheet;
        if (sheet.getWindow() != null)
            sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        final LinearLayout root = new LinearLayout(ctx);
        env.root = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(env.style.bg);
        root.setPadding(0, dp(ctx, 8), 0, 0);
        root.setFocusableInTouchMode(true); // lets us take focus away from the search box
        // status bar / gesture bar insets (sheet is full height)
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, ins) -> {
            Insets sb = ins.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = ins.getInsets(WindowInsetsCompat.Type.ime());
            env.topInset = Math.max(env.topInset, sb.top);
            v.setPadding(0, env.topInset + dp(ctx, 4), 0, Math.max(sb.bottom, ime.bottom));
            return ins;
        });

        // ── Top bar: ‹  Seen by N  ✕ ──
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 4));
        ImageView back = topIcon(env, R.drawable.ic_chevron_left, R.string.seenby_cd_back);
        // back: closes the search first (if open), otherwise the sheet; the redundant ✕ is gone
        back.setOnClickListener(v -> {
            if (env.searchBox != null && env.searchBox.getVisibility() == View.VISIBLE) toggleSearch(env);
            else sheet.dismiss();
        });
        titleRow.addView(back, new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)));
        env.tvTitle = new TextView(ctx);
        env.tvTitle.setText(ctx.getString(R.string.seenby_title, env.liveViews));
        env.tvTitle.setTextSize(18);
        env.tvTitle.setTextColor(env.style.primary);
        env.tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        env.tvTitle.setGravity(Gravity.CENTER);
        titleRow.addView(env.tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(new View(ctx), new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44))); // keeps the title centred
        // v31: segmented story-progress bars (one segment per story) above the top bar
        env.segBar = new LinearLayout(ctx);
        env.segBar.setOrientation(LinearLayout.HORIZONTAL);
        env.segBar.setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4));
        env.segBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        buildSegments(env);
        root.addView(env.segBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(titleRow);

        // ── Collapsible header: story carousel (hidden while searching) ──
        env.headerGroup = new LinearLayout(ctx);
        env.headerGroup.setOrientation(LinearLayout.VERTICAL);
        env.carousel = new RecyclerView(ctx);
        env.carousel.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
        env.carousel.setItemAnimator(null);
        env.carousel.setHorizontalScrollBarEnabled(false);
        env.carousel.setClipToPadding(false);
        env.carousel.setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4));
        env.carousel.setOverScrollMode(View.OVER_SCROLL_NEVER);
        env.cardAdapter = new CardAdapter(env);
        env.carousel.setAdapter(env.cardAdapter);
        env.headerGroup.addView(env.carousel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, CARD_AREA_DP)));
        View sep = new View(ctx);
        sep.setBackgroundColor(env.style.divider);
        LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        seplp.topMargin = dp(ctx, 8);
        env.headerGroup.addView(sep, seplp);
        root.addView(env.headerGroup);
        if (env.selIdx > 0) env.carousel.scrollToPosition(env.selIdx);

        // ── "👁 48 viewers"            🔍  [Sort by ⌄] ──
        root.addView(buildViewersRow(env));

        // ── Search (hidden until 🔍 is tapped) ──
        View searchView = buildSearch(env);
        env.searchBox = (FrameLayout) searchView;
        searchView.setVisibility(View.GONE);
        root.addView(searchView);

        env.progress = new ProgressBar(ctx);
        env.progress.setVisibility(View.GONE);
        root.addView(env.progress);

        root.addView(buildEmptyState(env));

        // Viewer list: takes all the height that is left (full-height sheet).
        final RecyclerView rv = new RecyclerView(ctx);
        env.rv = rv;
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        rv.setItemAnimator(null);
        rv.setHasFixedSize(false);
        rv.setItemViewCacheSize(6);
        env.adapter = new RowsAdapter(env);
        env.adapter.setHasStableIds(true);
        rv.setAdapter(env.adapter);
        rv.setClipToPadding(false);
        rv.setPadding(0, 0, 0, dp(ctx, 16));
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView r, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING && env.search != null && env.search.hasFocus()) {
                    hideKeyboard(env, env.search);
                    root.requestFocus();           // takes focus off the EditText, keeps the query
                }
            }
        });
        root.addView(rv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        sheet.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (env.dismissed) return;
                int n = env.adapter.getItemCount();
                if (n > 0) env.adapter.notifyItemRangeChanged(0, n, PAYLOAD_TIME);
                env.main.postDelayed(this, 60_000L);
            }
        };
        sheet.setOnDismissListener(d -> {
            env.dismissed = true;
            env.main.removeCallbacksAndMessages(null);
            detachLive(env);
            detachCardCounts(env);
            if (onClosed != null) onClosed.onClosed(new HashSet<>(env.removed));
        });
        sheet.setOnShowListener(d -> {
            View sh = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sh == null) sh = (View) root.getParent();
            if (sh != null) {
                ViewGroup.LayoutParams lp = sh.getLayoutParams();
                if (lp != null) { lp.height = ViewGroup.LayoutParams.MATCH_PARENT; sh.setLayoutParams(lp); }
                GradientDrawable g = new GradientDrawable();
                float r = dp(ctx, 20);
                g.setColor(env.style.bg);
                g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                sh.setBackground(g);
                BottomSheetBehavior<View> b = BottomSheetBehavior.from(sh);
                b.setSkipCollapsed(true);
                b.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            if (sheet.getWindow() != null) sheet.getWindow().setNavigationBarColor(env.style.bg);
            ViewCompat.requestApplyInsets(root);
            // Fallback: if the window is edge-to-edge but no inset reached us, pad by what is really overlapped.
            root.post(() -> {
                if (env.dismissed) return;
                int[] loc = new int[2];
                root.getLocationOnScreen(loc);
                int sbId = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
                int sbh = sbId > 0 ? ctx.getResources().getDimensionPixelSize(sbId) : 0;
                int need = Math.max(0, sbh - loc[1]);
                if (need > env.topInset) {
                    env.topInset = need;
                    root.setPadding(0, need + dp(ctx, 4), 0, root.getPaddingBottom());
                }
            });
        });
        // system back: close search first, then the sheet
        sheet.setOnKeyListener((d, keyCode, ev) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && ev.getAction() == android.view.KeyEvent.ACTION_UP
                    && env.searchBox != null && env.searchBox.getVisibility() == View.VISIBLE) {
                toggleSearch(env);
                return true;
            }
            return false;
        });
        sheet.show();
        env.main.postDelayed(tick, 60_000L);

        // 1) Paint instantly (cached profiles + local data), 2) go live, 3) lazy-load the rest.
        paintSortPill(env);
        refreshNow(env);
        attachLive(env);
        if (env.stories.size() > 1) attachCardCounts(env);
        loadRelationsAsync(env, ctx.getApplicationContext());
    }

    // ── v30: header widgets ───────────────────────────────────────────────────

    /** One thin rounded segment per story; stories up to the selected one are solid, the rest dimmed. Tap = switch. */
    private static void buildSegments(final Env env) {
        if (env.segBar == null) return;
        env.segBar.removeAllViews();
        final int n = env.stories.size();
        env.segBar.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        if (n <= 1) return;
        final Context ctx = env.ctx;
        for (int i = 0; i < n; i++) {
            final int pos = i;
            View seg = new View(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(ctx, 3), 1f);
            if (i > 0) lp.setMarginStart(dp(ctx, 3));
            lp.topMargin = lp.bottomMargin = dp(ctx, 6);   // taller touch target than the 3dp bar
            seg.setOnClickListener(v -> switchStory(env, pos));
            env.segBar.addView(seg, lp);
        }
        paintSegments(env);
    }

    private static void paintSegments(Env env) {
        if (env.segBar == null) return;
        for (int i = 0; i < env.segBar.getChildCount(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(env.ctx, 2));
            g.setColor(i <= env.selIdx ? env.style.primary : 0x59FFFFFF);
            env.segBar.getChildAt(i).setBackground(g);
        }
    }

    private static ImageView topIcon(Env env, int res, int cdRes) {
        ImageView t = new ImageView(env.ctx);
        t.setImageResource(res);
        t.setColorFilter(env.style.primary);
        t.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        t.setPadding(dp(env.ctx, 10), dp(env.ctx, 10), dp(env.ctx, 10), dp(env.ctx, 10));
        t.setContentDescription(env.ctx.getString(cdRes));
        t.setClickable(true);
        t.setBackground(rippleBorderless(env.ctx));
        return t;
    }

    /** "👁 48 viewers" on the left, 🔍 and the "Sort by ⌄" pill on the right. */
    private static View buildViewersRow(final Env env) {
        final Context ctx = env.ctx;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));

        ImageView eye = new ImageView(ctx);
        eye.setImageResource(R.drawable.ic_seenby_eye);
        eye.setColorFilter(env.style.primary);
        row.addView(eye, new LinearLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26)));

        env.tvViewers = new TextView(ctx);
        env.tvViewers.setText(ctx.getResources().getQuantityString(R.plurals.seenby_viewers,
                env.liveViews, env.liveViews));
        env.tvViewers.setTextSize(18);
        env.tvViewers.setTextColor(env.style.primary);
        env.tvViewers.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        env.tvViewers.setSingleLine(true);
        env.tvViewers.setEllipsize(TextUtils.TruncateAt.END);
        env.tvViewers.setPadding(dp(ctx, 12), 0, dp(ctx, 8), 0);
        row.addView(env.tvViewers, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView searchBtn = new ImageView(ctx);
        searchBtn.setImageResource(R.drawable.ic_seenby_search);
        searchBtn.setColorFilter(env.style.primary);
        searchBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        searchBtn.setPadding(dp(ctx, 9), dp(ctx, 9), dp(ctx, 9), dp(ctx, 9));
        searchBtn.setContentDescription(ctx.getString(R.string.seenby_search_hint));
        searchBtn.setClickable(true);
        searchBtn.setBackground(rippleBorderless(ctx));
        searchBtn.setOnClickListener(v -> toggleSearch(env));
        row.addView(searchBtn, new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)));

        LinearLayout pill = new LinearLayout(ctx);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(ctx, 16), 0, dp(ctx, 10), 0);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setCornerRadius(dp(ctx, 22));
        pbg.setColor(env.style.pill);
        pill.setBackground(pbg);
        pill.setClickable(true);
        env.sortLabel = new TextView(ctx);
        env.sortLabel.setText(R.string.seenby_sort_by);
        env.sortLabel.setTextSize(15);
        env.sortLabel.setTextColor(env.style.primary);
        env.sortLabel.setSingleLine(true);
        env.sortLabel.setEllipsize(TextUtils.TruncateAt.END);
        env.sortLabel.setMaxWidth(dp(ctx, 140));
        pill.addView(env.sortLabel);
        ImageView chev = new ImageView(ctx);
        chev.setImageResource(R.drawable.ic_chevron_down);
        chev.setColorFilter(env.style.primary);
        pill.addView(chev, new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)));
        pill.setOnClickListener(v -> showSortMenu(env, v));
        row.addView(pill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 40)));
        return row;
    }

    private static void toggleSearch(Env env) {
        if (env.searchBox == null) return;
        if (env.searchBox.getVisibility() != View.VISIBLE) {
            env.searchBox.setVisibility(View.VISIBLE);
            env.search.requestFocus();
            InputMethodManager imm = (InputMethodManager) env.ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(env.search, 0);
        } else {
            env.search.setText("");
            hideKeyboard(env, env.search);
            env.searchBox.setVisibility(View.GONE);
            env.root.requestFocus();
            updateHeader(env);
        }
    }

    /** Pill label: "Sort by", or the active filter / reaction so the user can see the list is filtered. */
    private static void paintSortPill(Env env) {
        if (env.sortLabel == null) return;
        if (env.emojiFilter != null) env.sortLabel.setText(env.emojiFilter);
        else if (env.filter != F_ALL) env.sortLabel.setText(FILTER_LABELS[env.filter]);
        else env.sortLabel.setText(R.string.seenby_sort_by);
    }

    private static final int CARD_AREA_DP = 148, CARD_W_DP = 76, CARD_H_DP = 128, CARD_SEL_W_DP = 92, CARD_SEL_H_DP = 140;
    private static final Object PAYLOAD_COUNT = "count";

    /** Selected story = live (block-aware) count; the others = their own live seenBy size (falls back to the snapshot). */
    private static int countFor(Env env, int pos) {
        if (pos == env.selIdx) return env.liveViews;
        StatusItem st = env.stories.get(pos);
        Integer c = st.id != null ? env.cardCounts.get(st.id) : null;
        return c != null ? c : st.getViewCount();
    }

    /** Count-only listeners (one per story): the carousel's 👁 numbers stay live for every card, not just the selected one. */
    private static void attachCardCounts(final Env env) {
        for (int i = 0; i < env.stories.size(); i++) {
            final StatusItem st = env.stories.get(i);
            final String sid = st.id;
            final String owner = st.ownerUid != null ? st.ownerUid : env.myUid;
            if (sid == null || owner == null) continue;
            try {
                final DatabaseReference ref = FirebaseUtils.getStatusSeenByRef(owner, sid);
                ValueEventListener l = new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (env.dismissed) return;
                        int cnt = 0;
                        for (DataSnapshot c : snap.getChildren())
                            if (c.getKey() != null && !env.removed.contains(c.getKey())) cnt++;
                        final int n = cnt;
                        Integer prev = env.cardCounts.put(sid, n);
                        if (prev != null && prev == n) return;
                        for (int p = 0; p < env.stories.size(); p++)
                            if (p != env.selIdx && sid.equals(env.stories.get(p).id))
                                env.cardAdapter.notifyItemChanged(p, PAYLOAD_COUNT);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                };
                ref.addValueEventListener(l);
                env.cardWatch.add(new Object[]{ref, l});
            } catch (Exception ignored) { /* snapshot count stays */ }
        }
    }

    private static void detachCardCounts(Env env) {
        for (Object[] w : env.cardWatch) {
            try { ((DatabaseReference) w[0]).removeEventListener((ValueEventListener) w[1]); } catch (Exception ignored) {}
        }
        env.cardWatch.clear();
    }

    private static void switchStory(Env env, int pos) {
        if (env.dismissed || pos < 0 || pos >= env.stories.size() || pos == env.selIdx) return;
        final int old = env.selIdx;
        detachLive(env);
        env.selIdx = pos;
        bindStory(env, env.stories.get(pos));
        env.emojiFilter = null;
        env.lastEmoji = new LinkedHashMap<>();
        env.gen++;
        // Drop the previous story's rows + queued profile reads right away (no stale list while compute() runs).
        env.adapter.submitList(Collections.<Row>emptyList());
        env.emptyBox.setVisibility(View.GONE);
        env.rv.scrollToPosition(0);
        env.pending.clear(); env.queued.clear();
        java.util.Arrays.fill(env.chipCounts, -1);
        env.cardAdapter.notifyItemChanged(old);
        env.cardAdapter.notifyItemChanged(pos);
        paintSegments(env);
        env.carousel.smoothScrollToPosition(pos);
        env.tvTitle.setText(env.ctx.getString(R.string.seenby_title, env.liveViews));
        env.tvViewers.setText(env.ctx.getResources().getQuantityString(R.plurals.seenby_viewers,
                env.liveViews, env.liveViews));
        paintSortPill(env);
        refreshNow(env);
        attachLive(env);
    }

    // ── Sort by popup ─────────────────────────────────────────────────────────

    private static final class SortOpt {
        final String label; final boolean header, checked; final Runnable action;
        SortOpt(String label, boolean header, boolean checked, Runnable action) {
            this.label = label; this.header = header; this.checked = checked; this.action = action;
        }
    }

    private static void showSortMenu(final Env env, View anchor) {
        final Context ctx = env.ctx;
        final List<SortOpt> opts = new ArrayList<>();
        opts.add(new SortOpt(ctx.getString(R.string.seenby_sort_hdr_order), true, false, null));
        opts.add(new SortOpt(ctx.getString(R.string.seenby_sort_opt_recent), false, !env.relevanceSort,
                () -> { env.relevanceSort = false; persist(env); refreshNow(env); }));
        opts.add(new SortOpt(ctx.getString(R.string.seenby_sort_opt_relevance), false, env.relevanceSort,
                () -> { env.relevanceSort = true; persist(env); refreshNow(env); }));
        opts.add(new SortOpt(ctx.getString(R.string.seenby_sort_hdr_show), true, false, null));
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            final int f = i;
            int n = i < env.chipCounts.length ? env.chipCounts[i] : -1;
            String label = ctx.getString(FILTER_LABELS[i]);
            if (n >= 0) label = ctx.getString(R.string.seenby_chip_count, label, n);
            opts.add(new SortOpt(label, false, env.filter == f && env.emojiFilter == null,
                    () -> { env.filter = f; env.emojiFilter = null; persist(env); paintSortPill(env); refreshNow(env); }));
        }
        if (!env.lastEmoji.isEmpty()) {
            opts.add(new SortOpt(ctx.getString(R.string.seenby_sort_hdr_reaction), true, false, null));
            for (Map.Entry<String, Integer> e : env.lastEmoji.entrySet()) {
                final String emoji = e.getKey();
                opts.add(new SortOpt(emoji + "  " + e.getValue(), false, emoji.equals(env.emojiFilter),
                        () -> { env.emojiFilter = emoji.equals(env.emojiFilter) ? null : emoji; paintSortPill(env); refreshNow(env); }));
            }
        }

        final ListPopupWindow pop = new ListPopupWindow(ctx);
        pop.setAnchorView(anchor);
        pop.setModal(true);
        pop.setWidth(dp(ctx, 250));
        pop.setDropDownGravity(Gravity.END);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setCornerRadius(dp(ctx, 14));
        pbg.setColor(env.style.surface);
        pop.setBackgroundDrawable(pbg);
        pop.setAdapter(new ArrayAdapter<SortOpt>(ctx, 0, opts) {
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return !opts.get(position).header; }
            @NonNull @Override public View getView(int position, View convert, @NonNull ViewGroup parent) {
                SortOpt o = opts.get(position);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView tv = new TextView(ctx);
                tv.setText(o.label);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                if (o.header) {
                    row.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 4));
                    tv.setTextSize(12);
                    tv.setTextColor(env.style.secondary);
                    tv.setTypeface(null, Typeface.BOLD);
                    row.addView(tv);
                } else {
                    row.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
                    tv.setTextSize(15);
                    tv.setTextColor(env.style.primary);
                    row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    if (o.checked) {
                        TextView ck = new TextView(ctx);
                        ck.setText("\u2713");
                        ck.setTextSize(16);
                        ck.setTextColor(env.style.accent);
                        ck.setTypeface(null, Typeface.BOLD);
                        row.addView(ck);
                    }
                }
                return row;
            }
        });
        pop.setOnItemClickListener((p, v, pos, id) -> {
            SortOpt o = opts.get(pos);
            if (o.header || o.action == null) return;
            pop.dismiss();
            o.action.run();
        });
        pop.show();
    }

    // ── Story carousel ────────────────────────────────────────────────────────

    private static final class CardAdapter extends RecyclerView.Adapter<CardVH> {
        private final Env env;
        CardAdapter(Env env) { this.env = env; }
        @NonNull @Override public CardVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) { return new CardVH(env); }
        @Override public int getItemCount() { return env.stories.size(); }
        @Override public void onBindViewHolder(@NonNull CardVH h, int position) { h.bind(position); }
        @Override public void onBindViewHolder(@NonNull CardVH h, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty() && payloads.contains(PAYLOAD_COUNT)) h.bindCount(position);
            else h.bind(position);
        }
        @Override public void onViewRecycled(@NonNull CardVH h) { h.release(); }
    }

    /** One story card: thumbnail (or text-story colours), bottom shade, 👁 count. Selected = white frame + bigger. */
    private static final class CardVH extends RecyclerView.ViewHolder {
        final Env env;
        final FrameLayout inner;
        final ImageView img;
        final TextView txt, count;
        float radiusPx;

        CardVH(final Env env) {
            super(new FrameLayout(env.ctx));
            this.env = env;
            final Context ctx = env.ctx;
            FrameLayout outer = (FrameLayout) itemView;
            outer.setClickable(true);
            outer.setOnClickListener(v -> {
                int p = getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) switchStory(env, p);
            });

            inner = new FrameLayout(ctx);
            inner.setClipToOutline(true);
            inner.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx); }
            });
            outer.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            img = new ImageView(ctx);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            inner.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            txt = new TextView(ctx);
            txt.setGravity(Gravity.CENTER);
            txt.setTextSize(11);
            txt.setMaxLines(5);
            txt.setEllipsize(TextUtils.TruncateAt.END);
            txt.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 30));
            inner.addView(txt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            View shade = new View(ctx);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x99000000}));
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48));
            slp.gravity = Gravity.BOTTOM;
            inner.addView(shade, slp);

            LinearLayout cnt = new LinearLayout(ctx);
            cnt.setOrientation(LinearLayout.HORIZONTAL);
            cnt.setGravity(Gravity.CENTER);
            ImageView eye = new ImageView(ctx);
            eye.setImageResource(R.drawable.ic_seenby_eye);
            eye.setColorFilter(0xFFFFFFFF);
            cnt.addView(eye, new LinearLayout.LayoutParams(dp(ctx, 15), dp(ctx, 15)));
            count = new TextView(ctx);
            count.setTextSize(14);
            count.setTextColor(0xFFFFFFFF);
            count.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            count.setPadding(dp(ctx, 5), 0, 0, 0);
            cnt.addView(count);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.BOTTOM;
            clp.bottomMargin = dp(ctx, 8);
            inner.addView(cnt, clp);
        }

        private static int color(String hex, int fallback) {
            if (hex == null || hex.isEmpty()) return fallback;
            try { return Color.parseColor(hex); } catch (Exception e) { return fallback; }
        }

        void bind(int pos) {
            final Context ctx = env.ctx;
            final StatusItem s = env.stories.get(pos);
            final boolean sel = pos == env.selIdx;
            final int w = dp(ctx, sel ? CARD_SEL_W_DP : CARD_W_DP), h = dp(ctx, sel ? CARD_SEL_H_DP : CARD_H_DP);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, h);
            lp.setMarginEnd(dp(ctx, 8));
            lp.topMargin = lp.bottomMargin = (dp(ctx, CARD_AREA_DP) - dp(ctx, 8) - h) / 2;
            itemView.setLayoutParams(lp);

            // selected: 2dp white frame, content inset 4dp inside it
            FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) inner.getLayoutParams();
            int inset = sel ? dp(ctx, 4) : 0;
            ilp.setMargins(inset, inset, inset, inset);
            inner.setLayoutParams(ilp);
            if (sel) {
                GradientDrawable fr = new GradientDrawable();
                fr.setCornerRadius(dp(ctx, 16));
                fr.setStroke(dp(ctx, 2), env.style.primary);
                itemView.setBackground(fr);
            } else itemView.setBackground(null);
            radiusPx = dp(ctx, sel ? 12 : 14);
            inner.invalidateOutline();

            // Same thumb rule + exact-size CDN crop as the Status tab cards (one pipeline).
            String thumb = StatusListAdapter.cardThumb(s);
            if (thumb != null) {
                String url = thumb;
                try {
                    String u = com.callx.app.utils.AvatarUrlBuilder.buildCard(ctx, thumb, CARD_SEL_W_DP, CARD_SEL_H_DP);
                    if (u != null && !u.isEmpty()) url = u;
                } catch (Exception ignored) {}
                inner.setBackgroundColor(0xFF1C1C1C);
                txt.setVisibility(View.GONE);
                img.setVisibility(View.VISIBLE);
                com.bumptech.glide.Glide.with(ctx).load(url)
                        .apply(new com.bumptech.glide.request.RequestOptions()
                                .override(dp(ctx, CARD_SEL_W_DP) * 2, dp(ctx, CARD_SEL_H_DP) * 2)
                                .centerCrop()
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE))
                        .into(img);
            } else {
                com.bumptech.glide.Glide.with(ctx).clear(img);
                img.setImageDrawable(null);
                img.setVisibility(View.GONE);
                int c1 = color(s.bgColor, 0xFF1C1C1C), c2 = color(s.bgColor2, c1);
                inner.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2}));
                String t = s.text != null && !s.text.isEmpty() ? s.text : s.caption;
                txt.setText(t);
                txt.setTextColor(color(s.textColor, 0xFFFFFFFF));
                txt.setVisibility(View.VISIBLE);
            }
            bindCount(pos);
        }

        void bindCount(int pos) {
            int n = countFor(env, pos);
            count.setText(String.valueOf(n));
            itemView.setContentDescription(env.ctx.getString(R.string.seenby_cd_story,
                    pos + 1, env.ctx.getResources().getQuantityString(R.plurals.seenby_views_cd, n, n)));
        }

        void release() {
            try { com.bumptech.glide.Glide.with(env.ctx).clear(img); } catch (Exception ignored) {}
        }
    }

    // ── Live data (#1) ────────────────────────────────────────────────────────

    private static void attachLive(final Env env) {
        if (env.statusId == null || env.ownerUid == null) return;
        try {
            env.seenRef  = FirebaseUtils.getStatusSeenByRef(env.ownerUid, env.statusId);
            env.reactRef = FirebaseUtils.getStatusRef().child(env.ownerUid).child(env.statusId).child("reactions");
            env.replyRef = FirebaseUtils.getStatusRepliesRef(env.ownerUid, env.statusId);

            env.seenL = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    BG.execute(() -> {
                        final HashMap<String, Long> m = new HashMap<>();
                        for (DataSnapshot c : snap.getChildren()) {
                            if (c.getKey() == null) continue;
                            Object v = c.getValue();
                            m.put(c.getKey(), v instanceof Number ? ((Number) v).longValue() : 0L);
                        }
                        env.main.post(() -> {
                            if (env.dismissed) return;
                            env.seenAt.clear(); env.seenAt.putAll(m);
                            scheduleRefresh(env);
                        });
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            env.reactL = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    BG.execute(() -> {
                        final HashMap<String, String> m = new HashMap<>();
                        for (DataSnapshot c : snap.getChildren()) {
                            if (c.getKey() == null) continue;
                            Object v = c.getValue();
                            String e = v instanceof String ? (String) v : (Boolean.TRUE.equals(v) ? "\u2764\uFE0F" : null);
                            if (e != null && !e.isEmpty()) m.put(c.getKey(), e);
                        }
                        env.main.post(() -> {
                            if (env.dismissed) return;
                            env.reactions.clear(); env.reactions.putAll(m);
                            scheduleRefresh(env);
                        });
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            env.replyL = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    BG.execute(() -> {
                        final HashMap<String, StatusItem.ReplyPreview> m = new HashMap<>();
                        for (DataSnapshot c : snap.getChildren()) {
                            if (c.getKey() == null) continue;
                            try {
                                StatusItem.ReplyPreview rp = c.getValue(StatusItem.ReplyPreview.class);
                                if (rp != null) m.put(c.getKey(), rp);
                            } catch (Exception ignored) {}
                        }
                        env.main.post(() -> {
                            if (env.dismissed) return;
                            env.replies.clear(); env.replies.putAll(m);
                            scheduleRefresh(env);
                        });
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            env.seenRef.addValueEventListener(env.seenL);
            env.reactRef.addValueEventListener(env.reactL);
            env.replyRef.addValueEventListener(env.replyL);
        } catch (Exception ignored) { /* static snapshot still works */ }
    }

    private static void detachLive(Env env) {
        try {
            if (env.seenRef != null && env.seenL != null) env.seenRef.removeEventListener(env.seenL);
            if (env.reactRef != null && env.reactL != null) env.reactRef.removeEventListener(env.reactL);
            if (env.replyRef != null && env.replyL != null) env.replyRef.removeEventListener(env.replyL);
        } catch (Exception ignored) {}
    }

    /** Followers (one read) + chat partners / cached names from Room (no Firebase read). */
    private static void loadRelationsAsync(final Env env, final Context app) {
        if (env.myUid != null) {
            FirebaseUtils.getReelFollowersRef(env.myUid).get().addOnSuccessListener(snap -> {
                if (env.dismissed) return;
                for (DataSnapshot c : snap.getChildren()) if (c.getKey() != null) env.followers.add(c.getKey());
                env.followersLoaded = true;
                refreshNow(env);
            }).addOnFailureListener(e -> { if (!env.dismissed) { env.followersLoaded = true; refreshNow(env); } });
        }
        BG.execute(() -> {
            final HashSet<String> contacts = new HashSet<>();
            final HashMap<String, Profile> seeds = new HashMap<>();
            try {
                List<ChatEntity> chats = AppDatabase.getInstance(app).chatDao().getAllChatsSync();
                if (chats != null) for (ChatEntity c : chats) {
                    if (c == null || c.partnerUid == null || c.partnerUid.isEmpty()) continue;
                    contacts.add(c.partnerUid);
                    if (c.partnerName != null && !c.partnerName.isEmpty()) {
                        String ph = c.partnerPhoto != null && !c.partnerPhoto.isEmpty() ? c.partnerPhoto : c.partnerThumb;
                        seeds.put(c.partnerUid, new Profile(c.partnerName, ph, 0L));
                    }
                }
            } catch (Exception ignored) {}
            env.main.post(() -> {
                if (env.dismissed) return;
                env.contacts.addAll(contacts);
                env.chatSeeds = seeds;
                scheduleRefresh(env);
            });
        });
    }

    // ── Refresh pipeline: snapshot (main) → compute (bg) → apply (main) ───────

    private static void scheduleRefresh(Env env) {
        if (env.refreshQueued || env.dismissed) return;
        env.refreshQueued = true;
        env.main.postDelayed(env.refreshRunnable, 60);
    }

    private static Set<String> universe(Env env) {
        HashSet<String> u = new HashSet<>(env.seenAt.keySet());
        u.addAll(env.reactions.keySet());
        for (StatusItem.ReplyPreview rp : env.replies.values())
            if (rp != null && rp.uid != null && rp.text != null && !rp.text.isEmpty()) u.add(rp.uid);
        return u;
    }

    private static Profile profileOf(Env env, String uid) {
        Profile p = env.profiles.get(uid);
        if (p == null) {
            p = PROFILES.get(uid);
            if (p != null) env.profiles.put(uid, p);
        }
        return p;
    }

    private static void refreshNow(final Env env) {
        if (env.dismissed) return;
        final Snap s = new Snap();
        for (String uid : universe(env)) profileOf(env, uid); // pull fresh-cached profiles into env
        s.seenAt = new HashMap<>(env.seenAt);
        s.reactions = new HashMap<>(env.reactions);
        s.replies = new ArrayList<>(env.replies.values());
        s.profiles = new HashMap<>(env.profiles);
        s.seeds = env.chatSeeds;
        s.followers = new HashSet<>(env.followers);
        s.contacts = new HashSet<>(env.contacts);
        s.closeFriends = StatusCloseFriendsManager.getLocalList(env.ctx);
        s.removed = new HashSet<>(env.removed);
        s.mode = StatusPrivacyManager.getPrivacyMode(env.ctx);
        s.except = StatusPrivacyManager.getExceptList(env.ctx);
        s.only = StatusPrivacyManager.getOnlyList(env.ctx);
        s.query = env.query; s.emoji = env.emojiFilter; s.filter = env.filter;
        s.relevance = env.relevanceSort; s.followersLoaded = env.followersLoaded;
        final int gen = ++env.gen;
        BG.execute(() -> {
            final Result r = compute(s);
            env.main.post(() -> {
                if (env.dismissed || gen != env.gen) return;
                apply(env, r);
            });
        });
    }

    private static boolean isHiddenFrom(String mode, Set<String> except, Set<String> only, String uid) {
        if (StatusPrivacyManager.PRIVACY_EXCEPT.equals(mode)) return except.contains(uid);
        if (StatusPrivacyManager.PRIVACY_ONLY.equals(mode)) return !only.contains(uid);
        return false;
    }

    /** Pure function of the snapshot — runs on the bg thread. */
    private static Result compute(Snap s) {
        // latest reply / reply count per viewer (+ name/avatar carried by the reply itself)
        HashMap<String, Long> at = new HashMap<>();
        HashMap<String, String> replyOf = new HashMap<>();
        HashMap<String, Integer> replyCnt = new HashMap<>();
        HashMap<String, String[]> replyProf = new HashMap<>();
        int replyStat = 0;
        for (StatusItem.ReplyPreview rp : s.replies) {
            if (rp == null || rp.uid == null || s.removed.contains(rp.uid)) continue;
            replyStat++;
            if (rp.text == null || rp.text.isEmpty()) continue;
            long ts = rp.timestamp != null ? rp.timestamp : 0L;
            Long prev = at.get(rp.uid);
            if (prev == null || ts >= prev) {
                at.put(rp.uid, ts); replyOf.put(rp.uid, rp.text);
                replyProf.put(rp.uid, new String[]{rp.name, rp.avatarUrl});
            }
            Integer c = replyCnt.get(rp.uid);
            replyCnt.put(rp.uid, c == null ? 1 : c + 1);
        }
        HashSet<String> all = new HashSet<>(s.seenAt.keySet());
        all.addAll(s.reactions.keySet());
        all.addAll(replyOf.keySet());
        all.removeAll(s.removed);

        int likes = 0;
        HashMap<String, Integer> emojiN = new HashMap<>();
        for (Map.Entry<String, String> e : s.reactions.entrySet()) {
            if (s.removed.contains(e.getKey()) || e.getValue() == null) continue;
            if (e.getValue().startsWith("\u2764")) likes++;
            Integer c = emojiN.get(e.getValue());
            emojiN.put(e.getValue(), c == null ? 1 : c + 1);
        }
        ArrayList<Map.Entry<String, Integer>> es = new ArrayList<>(emojiN.entrySet());
        Collections.sort(es, (a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        LinkedHashMap<String, Integer> emojiOut = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : es) emojiOut.put(e.getKey(), e.getValue());

        ArrayList<Row> every = new ArrayList<>(all.size());
        int nFollow = 0, nNot = 0, nReact = 0, nReply = 0, nClose = 0;
        for (String uid : all) {
            Profile p = s.profiles.get(uid);
            if (p == null) p = s.seeds.get(uid);
            String[] rprof = replyProf.get(uid);
            String name = p != null && p.name != null ? p.name : (rprof != null ? rprof[0] : null);
            String photo = p != null && p.photo != null && !p.photo.isEmpty() ? p.photo : (rprof != null ? rprof[1] : null);
            Long ts = s.seenAt.get(uid);
            Integer rc = replyCnt.get(uid);
            Row r = new Row(uid, name, photo, ts != null ? ts : 0L, s.reactions.get(uid), replyOf.get(uid),
                    rc != null ? rc : 0, s.followers.contains(uid), s.contacts.contains(uid),
                    s.closeFriends.contains(uid), isHiddenFrom(s.mode, s.except, s.only, uid));
            every.add(r);
            if (r.follower) nFollow++; else nNot++;
            if (r.reaction != null) nReact++;
            if (r.reply != null) nReply++;
            if (r.closeFriend) nClose++;
        }

        ArrayList<Row> out = new ArrayList<>(every.size());
        for (Row r : every) {
            if (!s.query.isEmpty() && (r.nameLower == null || !r.nameLower.contains(s.query))) continue;
            if (s.emoji != null && !s.emoji.equals(r.reaction)) continue;
            boolean ok;
            switch (s.filter) {
                case F_FOLLOWERS:     ok = !s.followersLoaded || r.follower; break;
                case F_NOT_FOLLOWING: ok = !s.followersLoaded || !r.follower; break;
                case F_REACTIONS:     ok = r.reaction != null; break;
                case F_REPLIES:       ok = r.reply != null; break;
                case F_CLOSE:         ok = r.closeFriend; break;
                default:              ok = true;
            }
            if (ok) out.add(r);
        }
        final boolean relevance = s.relevance;
        Collections.sort(out, (a, b) -> {
            if (relevance) {
                int sc = Integer.compare(b.score(), a.score());
                if (sc != 0) return sc;
            }
            int c = Long.compare(b.seenAt, a.seenAt); // most recent viewer first
            return c != 0 ? c : a.uid.compareTo(b.uid);
        });

        Result res = new Result();
        res.rows = out; res.totalRows = every.size();
        res.views = every.size(); res.likes = likes; res.replies = replyStat;   // v31: same number as the title
        res.emoji = emojiOut;
        res.counts = new int[]{every.size(), s.followersLoaded ? nFollow : -1, s.followersLoaded ? nNot : -1,
                nReact, nReply, nClose};
        return res;
    }

    private static void apply(final Env env, Result r) {
        final Context ctx = env.ctx;
        System.arraycopy(r.counts, 0, env.chipCounts, 0, env.chipCounts.length);
        if (env.emojiFilter != null && !r.emoji.containsKey(env.emojiFilter)) {
            env.emojiFilter = null;
            scheduleRefresh(env);
        }
        env.lastEmoji = r.emoji;
        paintSortPill(env);
        env.tvTitle.setText(ctx.getString(R.string.seenby_title, r.totalRows));
        env.tvViewers.setText(r.rows.size() == r.totalRows
                ? ctx.getResources().getQuantityString(R.plurals.seenby_viewers, r.totalRows, r.totalRows)
                : ctx.getString(R.string.seenby_viewers_of, r.rows.size(), r.totalRows));
        if (env.liveViews != r.views) {
            env.liveViews = r.views;
            env.cardAdapter.notifyItemChanged(env.selIdx, PAYLOAD_COUNT);
        }

        final LinearLayoutManager lm = (LinearLayoutManager) env.rv.getLayoutManager();
        final boolean atTop = lm == null || lm.findFirstCompletelyVisibleItemPosition() <= 0;
        env.adapter.submitList(r.rows, () -> { if (atTop && !env.dismissed) env.rv.scrollToPosition(0); });

        if (r.rows.isEmpty()) {
            boolean none = r.totalRows == 0;
            env.tvEmptyTitle.setText(none ? R.string.seenby_empty_none_title : R.string.seenby_empty_match_title);
            env.tvEmptySub.setText(none ? R.string.seenby_empty_none_sub : R.string.seenby_empty_match_sub);
            env.btnEmptyClear.setVisibility(none ? View.GONE : View.VISIBLE);
            env.emptyBox.setVisibility(View.VISIBLE);
        } else {
            env.emptyBox.setVisibility(View.GONE);
        }
    }

    // ── Lazy profiles (#2) ────────────────────────────────────────────────────

    private static boolean needsFetch(Env env, String uid) {
        if (env.fetched.contains(uid) || env.inFlight.contains(uid)) return false;
        Profile p = profileOf(env, uid);
        return p == null || System.currentTimeMillis() - p.at > PROFILE_TTL_MS;
    }

    /** Called when a row is bound (i.e. visible). Latest-bound rows are fetched first. */
    private static void requestProfile(Env env, String uid) {
        if (env.dismissed || !needsFetch(env, uid)) return;
        if (env.queued.add(uid)) env.pending.addLast(uid);
        pumpProfiles(env);
    }

    private static void pumpProfiles(Env env) {
        while (!env.dismissed && env.inFlight.size() < MAX_INFLIGHT && !env.pending.isEmpty()) {
            String uid = env.pending.pollLast();
            env.queued.remove(uid);
            if (!needsFetch(env, uid)) continue;
            env.inFlight.add(uid);
            fetchProfile(env, uid);
        }
        updateProgress(env);
    }

    private static void fetchProfile(final Env env, final String uid) {
        FirebaseUtils.db().getReference("users").child(uid).get()
                .addOnSuccessListener(snap -> BG.execute(() -> {
                    Profile parsed = null;
                    try {
                        // DataSnapshot is immutable -> safe on the bg thread.
                        String name = snap.child("name").getValue(String.class);
                        // Full photoUrl first (buildResponsive builds the TINY variant from it) -> shares the
                        // cache key with the reply sheet / reels comments; thumbUrl only as fallback.
                        String photo = snap.child("photoUrl").getValue(String.class);
                        if (photo == null || photo.isEmpty()) photo = snap.child("thumbUrl").getValue(String.class);
                        parsed = new Profile(name != null ? name : uid, photo, System.currentTimeMillis());
                        PROFILES.put(uid, parsed);
                    } catch (Exception ignored) { /* malformed profile node -> treated as a failed read */ }
                    final Profile p = parsed;
                    env.main.post(() -> onProfileDone(env, uid, p));
                }))
                .addOnFailureListener(e -> env.main.post(() -> onProfileDone(env, uid, null)));
    }

    private static void onProfileDone(Env env, String uid, Profile p) {
        if (env.dismissed) return;
        env.inFlight.remove(uid);
        env.fetched.add(uid);
        if (p != null) { env.profiles.put(uid, p); scheduleRefresh(env); }
        pumpProfiles(env);
    }

    /** Search needs names: pull every missing profile in (throttled, visible rows still first). */
    private static void loadAllProfiles(Env env) {
        for (String uid : universe(env))
            if (needsFetch(env, uid) && env.queued.add(uid)) env.pending.addFirst(uid);
        pumpProfiles(env);
    }

    private static void updateProgress(Env env) {
        boolean busy = !env.query.isEmpty() && (!env.inFlight.isEmpty() || !env.pending.isEmpty());
        env.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private static View buildSearch(final Env env) {
        final Context ctx = env.ctx;
        FrameLayout box = new FrameLayout(ctx);
        final EditText search = new EditText(ctx);
        env.search = search;
        search.setHint(R.string.seenby_search_hint);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(env.style.primary);
        search.setHintTextColor(env.style.secondary);
        search.setPadding(dp(ctx, 12), 0, dp(ctx, 40), 0);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(dp(ctx, 12));
        sbg.setColor(env.style.divider);
        search.setBackground(sbg);
        Drawable mag = tinted(ctx, R.drawable.ic_seenby_search, env.style.secondary, 18);
        if (mag != null) search.setCompoundDrawablesRelative(mag, null, null, null);
        search.setCompoundDrawablePadding(dp(ctx, 8));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(env, search); return true; }
            return false;
        });
        search.setOnFocusChangeListener((v, has) -> updateHeader(env));
        box.addView(search, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 40)));

        env.clearBtn = new ImageView(ctx);
        env.clearBtn.setImageResource(R.drawable.ic_seenby_clear);
        env.clearBtn.setColorFilter(env.style.secondary);
        env.clearBtn.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));
        env.clearBtn.setBackground(rippleBorderless(ctx));
        env.clearBtn.setContentDescription(ctx.getString(R.string.seenby_search_clear));
        env.clearBtn.setVisibility(View.GONE);
        env.clearBtn.setOnClickListener(v -> search.setText(""));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40));
        clp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        box.addView(env.clearBtn, clp);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                env.clearBtn.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
                env.query = s == null ? "" : s.toString().trim().toLowerCase(Locale.getDefault());
                updateHeader(env);
                if (env.searchRunnable != null) env.main.removeCallbacks(env.searchRunnable);
                env.searchRunnable = () -> {
                    if (env.dismissed) return;
                    if (!env.query.isEmpty()) loadAllProfiles(env);
                    else {
                        // search closed: drop the bulk queue, keep only the rows that are really on screen
                        env.pending.clear(); env.queued.clear();
                        for (String uid : new ArrayList<>(env.bound)) requestProfile(env, uid);
                    }
                    updateProgress(env);
                    refreshNow(env);
                };
                env.main.postDelayed(env.searchRunnable, 150);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(ctx, 10);
        slp.setMarginStart(dp(ctx, 16));
        slp.setMarginEnd(dp(ctx, 16));
        box.setLayoutParams(slp);
        return box;
    }

    /** Stats + emoji header only while NOT searching, so the list gets the space. */
    private static void updateHeader(Env env) {
        if (env.headerGroup == null || env.search == null) return;
        boolean show = !env.search.hasFocus() && env.query.isEmpty();
        env.headerGroup.setVisibility(show ? View.VISIBLE : View.GONE);
        if (env.rv != null) env.rv.requestLayout();
    }

    private static void hideKeyboard(Env env, View v) {
        InputMethodManager imm = (InputMethodManager) env.ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private static void persist(Env env) {
        env.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_FILTER, env.filter).putBoolean(KEY_RELEVANCE, env.relevanceSort).apply();
    }

    private static View buildEmptyState(final Env env) {
        final Context ctx = env.ctx;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(ctx, 16), dp(ctx, 24), dp(ctx, 16), dp(ctx, 24));
        box.setVisibility(View.GONE);
        ImageView ic = new ImageView(ctx);
        ic.setImageResource(R.drawable.ic_seenby_eye_off);
        ic.setColorFilter(env.style.secondary);
        ic.setAlpha(0.7f);
        box.addView(ic, new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)));
        env.tvEmptyTitle = new TextView(ctx);
        env.tvEmptyTitle.setTextSize(15);
        env.tvEmptyTitle.setTypeface(null, Typeface.BOLD);
        env.tvEmptyTitle.setTextColor(env.style.primary);
        env.tvEmptyTitle.setGravity(Gravity.CENTER);
        env.tvEmptyTitle.setPadding(0, dp(ctx, 10), 0, dp(ctx, 2));
        box.addView(env.tvEmptyTitle);
        env.tvEmptySub = new TextView(ctx);
        env.tvEmptySub.setTextSize(13);
        env.tvEmptySub.setTextColor(env.style.secondary);
        env.tvEmptySub.setGravity(Gravity.CENTER);
        box.addView(env.tvEmptySub);
        env.btnEmptyClear = chip(env, ctx.getString(R.string.seenby_empty_clear));
        paintChip(env, env.btnEmptyClear, true);
        env.btnEmptyClear.setOnClickListener(v -> {
            env.filter = F_ALL; env.emojiFilter = null;
            persist(env);
            paintSortPill(env);
            env.search.setText("");   // also refreshes (debounced)
            refreshNow(env);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32));
        blp.topMargin = dp(ctx, 12);
        box.addView(env.btnEmptyClear, blp);
        env.emptyBox = box;
        return box;
    }

    private static LinearLayout.LayoutParams chipLp(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32));
        lp.setMarginEnd(dp(ctx, 8));
        return lp;
    }

    private static TextView chip(Env env, String text) {
        TextView t = new TextView(env.ctx);
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(env.ctx, 14), 0, dp(env.ctx, 14), 0);
        t.setClickable(true);
        return t;
    }

    private static void paintChip(Env env, TextView t, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(env.ctx, 16));
        bg.setColor(on ? env.style.primary : env.style.divider);
        t.setBackground(bg);
        t.setTextColor(on ? env.style.onPrimary : env.style.primary);
        t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ── Time (#4) ─────────────────────────────────────────────────────────────

    /** Main thread only. "just now" · "12 min ago" · "3h ago" · "today, 7:05 AM" · "yesterday, 9:10 PM" · "4 Nov, 9:10 PM". */
    private static String formatTime(Env env, long ts) {
        final Context c = env.ctx;
        final long now = System.currentTimeMillis();
        long diff = Math.max(0L, now - ts);
        if (diff < 60_000L) return c.getString(R.string.seenby_time_just_now);
        if (diff < 3_600_000L) return c.getString(R.string.seenby_time_min, (int) (diff / 60_000L));
        if (diff < 6 * 3_600_000L) return c.getString(R.string.seenby_time_hour, (int) (diff / 3_600_000L));
        String t = env.timeFmt.format(new Date(ts));
        env.cal.setTimeInMillis(now);
        env.cal.set(Calendar.HOUR_OF_DAY, 0); env.cal.set(Calendar.MINUTE, 0);
        env.cal.set(Calendar.SECOND, 0); env.cal.set(Calendar.MILLISECOND, 0);
        long todayStart = env.cal.getTimeInMillis();
        env.cal.add(Calendar.DAY_OF_YEAR, -1);
        long yesterdayStart = env.cal.getTimeInMillis();
        if (ts >= todayStart) return c.getString(R.string.seenby_time_today, t);
        if (ts >= yesterdayStart) return c.getString(R.string.seenby_time_yesterday, t);
        env.cal.setTimeInMillis(now);
        env.cal2.setTimeInMillis(ts);
        boolean sameYear = env.cal.get(Calendar.YEAR) == env.cal2.get(Calendar.YEAR);
        String d = (sameYear ? env.dateFmt : env.dateYearFmt).format(new Date(ts));
        return c.getString(R.string.seenby_time_date, d, t);
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static final DiffUtil.ItemCallback<Row> DIFF = new DiffUtil.ItemCallback<Row>() {
        @Override public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) { return a.uid.equals(b.uid); }
        @Override public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) { return a.sameContent(b); }
    };

    private static final class RowsAdapter extends ListAdapter<Row, RowVH> {
        private final Env env;
        RowsAdapter(Env env) { super(DIFF); this.env = env; }
        @Override public long getItemId(int position) { return getItem(position).uid.hashCode(); }
        @NonNull @Override public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowVH(env);
        }
        @Override public void onBindViewHolder(@NonNull RowVH h, int position) { h.bind(getItem(position)); }
        @Override public void onBindViewHolder(@NonNull RowVH h, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty() && payloads.contains(PAYLOAD_TIME) && h.row != null
                    && h.row.uid.equals(getItem(position).uid)) {
                h.bindMeta(getItem(position));   // time tick only: no avatar / ring work
            } else {
                h.bind(getItem(position));
            }
        }
        @Override public void onViewRecycled(@NonNull RowVH h) { h.release(); }
    }

    /** One viewer row. Views built ONCE per holder. */
    private static final class RowVH extends RecyclerView.ViewHolder {
        static final int RING_DP = 36, AVATAR_DP = 32;   // same as the reel comment rows (32dp avatar / 36dp ring)
        final Env env;
        final CircleImageView avatar;
        final ImageView ring, btnMsg, btnMore;
        final TextView tvName, tvMeta, tvReply, tvReact;
        final Drawable replyIcon;
        Row row;

        RowVH(Env env) {
            super(new LinearLayout(env.ctx));
            this.env = env;
            final Context ctx = env.ctx;
            LinearLayout box = (LinearLayout) itemView;
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(ctx, 16), dp(ctx, 7), dp(ctx, 8), dp(ctx, 7));
            line.setBackground(ripple(ctx));

            // Story ring (same StoryRingApplier every other avatar list uses) around the avatar.
            FrameLayout avatarBox = new FrameLayout(ctx);
            int ringSz = dp(ctx, RING_DP);
            ring = new ImageView(ctx);
            ring.setVisibility(View.GONE);
            avatarBox.addView(ring, new FrameLayout.LayoutParams(ringSz, ringSz));
            avatar = new CircleImageView(ctx);
            int size = dp(ctx, AVATAR_DP);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(size, size);
            alp.gravity = Gravity.CENTER;
            avatarBox.addView(avatar, alp);
            line.addView(avatarBox, new LinearLayout.LayoutParams(ringSz, ringSz));

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(ctx, 14), 0, dp(ctx, 4), 0);
            line.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            tvName = new TextView(ctx);
            tvName.setTextSize(16);
            tvName.setTextColor(env.style.primary);
            tvName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tvName.setSingleLine(true);
            tvName.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(tvName);

            // "2h ago" (+ close-friend / hidden marker)
            tvMeta = new TextView(ctx);
            tvMeta.setTextSize(15);
            tvMeta.setTextColor(env.style.secondary);
            tvMeta.setMaxLines(2);
            tvMeta.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(tvMeta);

            tvReply = new TextView(ctx);
            tvReply.setTextSize(13);
            tvReply.setTextColor(env.style.primary);
            tvReply.setTypeface(null, Typeface.ITALIC);
            tvReply.setMaxLines(2);
            tvReply.setEllipsize(TextUtils.TruncateAt.END);
            tvReply.setPadding(0, dp(ctx, 3), 0, 0);
            tvReply.setCompoundDrawablePadding(dp(ctx, 6));
            replyIcon = tinted(ctx, R.drawable.ic_seenby_comment, env.style.secondary, 13);
            if (replyIcon != null) tvReply.setCompoundDrawablesRelative(replyIcon, null, null, null);
            tvReply.setClickable(true);
            tvReply.setOnClickListener(v -> { if (row != null) openChat(env, row); }); // #9: tap reply → chat
            info.addView(tvReply);

            tvReact = new TextView(ctx);
            tvReact.setTextSize(22);
            tvReact.setPadding(dp(ctx, 6), 0, dp(ctx, 2), 0);
            line.addView(tvReact);

            // ⋯ (existing vertical-dots drawable, rotated) — same menu as before
            btnMore = iconBtn(ctx, R.drawable.ic_seenby_more, env.style.primary, ctx.getString(R.string.seenby_cd_more));
            btnMore.setRotation(90f);
            btnMore.setOnClickListener(v -> { if (row != null) showMenu(env, v, row); });
            line.addView(btnMore, new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 44)));

            // paper-plane (existing Instagram share icon) = message this viewer
            btnMsg = iconBtn(ctx, R.drawable.ic_status_share, env.style.primary, ctx.getString(R.string.seenby_cd_message));
            btnMsg.setOnClickListener(v -> { if (row != null) openChat(env, row); });
            line.addView(btnMsg, new LinearLayout.LayoutParams(dp(ctx, 46), dp(ctx, 46)));

            line.setOnClickListener(v -> { if (row != null) openProfile(env, row); }); // tap row → profile
            box.addView(line);
        }

        private static ImageView iconBtn(Context ctx, int res, int tint, String desc) {
            ImageView t = new ImageView(ctx);
            t.setImageResource(res);
            t.setColorFilter(tint);
            t.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            t.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));
            t.setContentDescription(desc);
            t.setClickable(true);
            t.setBackground(rippleBorderless(ctx));
            return t;
        }

        void bind(Row r) {
            row = r;
            env.bound.add(r.uid);
            tvName.setText(r.name != null ? r.name : env.ctx.getString(R.string.seenby_name_loading));
            bindMeta(r);
            if (r.reply == null) tvReply.setVisibility(View.GONE);
            else {
                tvReply.setVisibility(View.VISIBLE);
                tvReply.setText(r.replyCount > 1
                        ? env.ctx.getString(R.string.seenby_reply_more, r.reply, r.replyCount - 1) : r.reply);
            }
            if (r.reaction == null) tvReact.setVisibility(View.GONE);
            else { tvReact.setVisibility(View.VISIBLE); tvReact.setText(r.reaction); }
            StatusSheetAvatar.load(env.ctx, avatar, r.photo, R.drawable.ic_person, StatusSheetAvatar.ROW_TIER);
            // Gradient = unseen story, gray = all seen, hidden = none; tap opens the story viewer.
            com.callx.app.utils.StoryRingApplier.applyWithClick(env.ctx, ring, r.uid, r.name);
            requestProfile(env, r.uid);      // lazy: only rows that are really bound fetch their profile
        }

        /** "2h ago" · "★ Close friend" · "👁 Hidden" */
        void bindMeta(Row r) {
            final Context ctx = env.ctx;
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (r.seenAt > 0) sb.append(formatTime(env, r.seenAt));
            if (r.closeFriend) {
                if (sb.length() > 0) sb.append("  \u00B7  ");
                int s = sb.length();
                sb.append(ctx.getString(R.string.seenby_badge_close));
                sb.setSpan(new ForegroundColorSpan(env.style.accent), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (r.hidden) {
                if (sb.length() > 0) sb.append("  \u00B7  ");
                if (env.hiddenIcon == null) env.hiddenIcon = tinted(ctx, R.drawable.ic_seenby_eye_off, env.style.secondary, 14);
                if (env.hiddenIcon != null) {
                    int s = sb.length();
                    sb.append(" ");
                    sb.setSpan(new ImageSpan(env.hiddenIcon, ImageSpan.ALIGN_BASELINE), s, s + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.append(" ");
                }
                sb.append(ctx.getString(R.string.seenby_badge_hidden));
            }
            if (sb.length() == 0) tvMeta.setVisibility(View.GONE);
            else { tvMeta.setVisibility(View.VISIBLE); tvMeta.setText(sb); }
        }

        void release() {
            try { com.bumptech.glide.Glide.with(env.ctx).clear(avatar); } catch (Exception ignored) {}
            ring.setOnClickListener(null);
            if (row != null) {
                env.bound.remove(row.uid);
                // not searching: a row that scrolled away no longer needs its queued profile read
                if (env.query.isEmpty() && env.queued.remove(row.uid)) env.pending.remove(row.uid);
            }
            row = null;
        }
    }

    // ── Per-viewer actions ────────────────────────────────────────────────────

    private static final int M_MESSAGE = 1, M_PROFILE = 2, M_CLOSE = 3, M_HIDE = 4, M_BLOCK = 5;

    private static final class MenuSpec {
        final int id, icon; final String label; final boolean destructive;
        MenuSpec(int id, int icon, String label, boolean destructive) {
            this.id = id; this.icon = icon; this.label = label; this.destructive = destructive;
        }
    }

    private static String nameOf(Env env, Row r) {
        return r.name != null ? r.name : env.ctx.getString(R.string.seenby_someone);
    }

    private static void showMenu(final Env env, View anchor, final Row r) {
        final Context ctx = env.ctx;
        final String nm = nameOf(env, r);
        boolean cf = StatusCloseFriendsManager.isCloseFriend(ctx, r.uid);
        final List<MenuSpec> items = new ArrayList<>();
        items.add(new MenuSpec(M_MESSAGE, R.drawable.ic_seenby_message, ctx.getString(R.string.seenby_menu_message), false));
        items.add(new MenuSpec(M_PROFILE, R.drawable.ic_seenby_person, ctx.getString(R.string.seenby_menu_view_profile), false));
        items.add(new MenuSpec(M_CLOSE, cf ? R.drawable.ic_seenby_star : R.drawable.ic_seenby_star_outline,
                ctx.getString(cf ? R.string.seenby_menu_cf_remove : R.string.seenby_menu_cf_add), false));
        items.add(new MenuSpec(M_HIDE, r.hidden ? R.drawable.ic_seenby_eye : R.drawable.ic_seenby_eye_off,
                ctx.getString(r.hidden ? R.string.seenby_menu_unhide : R.string.seenby_menu_hide, nm), false));
        items.add(new MenuSpec(M_BLOCK, R.drawable.ic_seenby_block, ctx.getString(R.string.seenby_menu_block, nm), true));

        final ListPopupWindow pop = new ListPopupWindow(ctx);
        pop.setAnchorView(anchor);
        pop.setModal(true);
        pop.setWidth(dp(ctx, 270));
        pop.setDropDownGravity(Gravity.END);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setCornerRadius(dp(ctx, 12));
        pbg.setColor(env.style.surface);
        pop.setBackgroundDrawable(pbg);
        pop.setAdapter(new ArrayAdapter<MenuSpec>(ctx, 0, items) {
            @NonNull @Override public View getView(int position, View convert, @NonNull ViewGroup parent) {
                MenuSpec m = items.get(position);
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13));
                int color = m.destructive ? RED : env.style.primary;
                ImageView ic = new ImageView(ctx);
                ic.setImageResource(m.icon);
                ic.setColorFilter(color);
                row.addView(ic, new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)));
                TextView tv = new TextView(ctx);
                tv.setText(m.label);
                tv.setTextSize(14);
                tv.setTextColor(color);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(dp(ctx, 14), 0, 0, 0);
                row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
        });
        pop.setOnItemClickListener((p, v, pos, id) -> {
            pop.dismiss();
            switch (items.get(pos).id) {
                case M_MESSAGE: openChat(env, r); break;
                case M_PROFILE: openProfile(env, r); break;
                case M_CLOSE:
                    StatusCloseFriendsManager.toggle(ctx, env.myUid, r.uid);
                    refreshNow(env);
                    break;
                case M_HIDE:
                    if (r.hidden) doUnhide(env, r); else confirmHide(env, r);
                    break;
                case M_BLOCK: confirmBlock(env, r); break;
                default: break;
            }
        });
        pop.show();
    }

    private static void openChat(Env env, Row r) {
        try {
            Intent i = new Intent(env.ctx, com.callx.app.conversation.ChatActivity.class);
            i.putExtra("partnerUid",   r.uid);
            i.putExtra("partnerName",  r.name != null ? r.name : "");
            i.putExtra("partnerPhoto", r.photo != null ? r.photo : "");
            i.putExtra("partnerThumb", r.photo != null ? r.photo : "");
            if (!(env.ctx instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            env.ctx.startActivity(i);
            if (env.sheet != null) env.sheet.dismiss();
        } catch (Exception e) {
            Toast.makeText(env.ctx, R.string.seenby_err_chat, Toast.LENGTH_SHORT).show();
        }
    }

    private static void openProfile(Env env, Row r) {
        try {
            Intent i = new Intent(env.ctx, Class.forName("com.callx.app.activities.UserProfileActivity"));
            i.putExtra("uid", r.uid);
            i.putExtra("name", r.name != null ? r.name : "");
            if (r.photo != null) i.putExtra("photo", r.photo);
            if (!(env.ctx instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            env.ctx.startActivity(i);
            if (env.sheet != null) env.sheet.dismiss();
        } catch (Exception e) {
            Toast.makeText(env.ctx, R.string.seenby_err_profile, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Context whose resources resolve in NIGHT mode: the sheet is always dark, so the shared rounded dialog
     * (bg_rounded_alert_dialog -> @color/dialog_surface) must be the dark one even when the phone is in light mode.
     */
    private static Context darkDialogCtx(Context ctx) {
        try {
            android.view.ContextThemeWrapper w = new android.view.ContextThemeWrapper(ctx,
                    android.R.style.Theme_DeviceDefault_Dialog_Alert);
            android.content.res.Configuration c = new android.content.res.Configuration(ctx.getResources().getConfiguration());
            c.uiMode = (c.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    | android.content.res.Configuration.UI_MODE_NIGHT_YES;
            w.applyOverrideConfiguration(c);
            return w;
        } catch (Exception e) {
            return ctx;
        }
    }

    private static void undoSnack(Env env, String msg, View.OnClickListener undo) {
        try {
            Snackbar sb = Snackbar.make(env.root, msg, Snackbar.LENGTH_LONG).setAction(R.string.seenby_undo, undo);
            sb.setBackgroundTint(env.style.surface);
            sb.setTextColor(env.style.primary);
            sb.setActionTextColor(env.style.accent);
            sb.show();
        } catch (Exception e) {
            Toast.makeText(env.ctx, msg, Toast.LENGTH_SHORT).show();
        }
    }

    /** Captures the privacy state a hide / unhide is about to change, so Undo can put it back. */
    private static Runnable captureRestore(final Env env, final String uid) {
        final Context ctx = env.ctx;
        final String prevMode = StatusPrivacyManager.getPrivacyMode(ctx);
        final Set<String> prevExcept = StatusPrivacyManager.getExceptList(ctx);
        final Set<String> prevOnly = StatusPrivacyManager.getOnlyList(ctx);
        final boolean wasCf = StatusCloseFriendsManager.isCloseFriend(ctx, uid);
        return () -> {
            StatusPrivacyManager.setPrivacyMode(ctx, prevMode);
            StatusPrivacyManager.setExceptList(ctx, prevExcept);
            StatusPrivacyManager.setOnlyList(ctx, prevOnly);
            if (wasCf) StatusCloseFriendsManager.addCloseFriend(ctx, env.myUid, uid);
            if (!env.dismissed) refreshNow(env);
        };
    }

    /** Instagram "Hide story from": future stories stop reaching this person (uses the Status privacy lists). */
    private static void confirmHide(final Env env, final Row r) {
        final String nm = nameOf(env, r);
        final android.app.AlertDialog hideDlg = new android.app.AlertDialog.Builder(darkDialogCtx(env.ctx))
                .setTitle(env.ctx.getString(R.string.seenby_hide_title, nm))
                .setMessage(R.string.seenby_hide_msg)
                .setNegativeButton(R.string.seenby_cancel, null)
                .setPositiveButton(R.string.seenby_hide_confirm, (d, w) -> {
                    final Runnable restore = captureRestore(env, r.uid);
                    try {
                        String mode = StatusPrivacyManager.getPrivacyMode(env.ctx);
                        if (StatusPrivacyManager.PRIVACY_ONLY.equals(mode)) {
                            Set<String> only = new HashSet<>(StatusPrivacyManager.getOnlyList(env.ctx));
                            only.remove(r.uid);
                            StatusPrivacyManager.setOnlyList(env.ctx, only);
                        } else if (StatusPrivacyManager.PRIVACY_EXCEPT.equals(mode)
                                || StatusPrivacyManager.PRIVACY_EVERYONE.equals(mode)
                                || StatusPrivacyManager.PRIVACY_CONTACTS.equals(mode)) {
                            Set<String> ex = new HashSet<>(StatusPrivacyManager.getExceptList(env.ctx));
                            ex.add(r.uid);
                            StatusPrivacyManager.setExceptList(env.ctx, ex);
                            if (!StatusPrivacyManager.PRIVACY_EXCEPT.equals(mode))
                                StatusPrivacyManager.setPrivacyMode(env.ctx, StatusPrivacyManager.PRIVACY_EXCEPT);
                        }
                        // close-friends-only mode: they only see it if they are a close friend
                        if (StatusCloseFriendsManager.isCloseFriend(env.ctx, r.uid))
                            StatusCloseFriendsManager.removeCloseFriend(env.ctx, env.myUid, r.uid);
                        refreshNow(env);
                        undoSnack(env, env.ctx.getString(R.string.seenby_hidden_done), v -> restore.run());
                    } catch (Exception e) {
                        Toast.makeText(env.ctx, R.string.seenby_err_privacy, Toast.LENGTH_SHORT).show();
                    }
                }).create();
        AlertDialogStyler.showRounded(hideDlg);
    }

    /** Reverse of hide: they get future stories again (EXCEPT list: remove; ONLY list: add). */
    private static void doUnhide(final Env env, final Row r) {
        final Runnable restore = captureRestore(env, r.uid);
        try {
            String mode = StatusPrivacyManager.getPrivacyMode(env.ctx);
            if (StatusPrivacyManager.PRIVACY_EXCEPT.equals(mode)) {
                Set<String> ex = new HashSet<>(StatusPrivacyManager.getExceptList(env.ctx));
                ex.remove(r.uid);
                StatusPrivacyManager.setExceptList(env.ctx, ex);
            } else if (StatusPrivacyManager.PRIVACY_ONLY.equals(mode)) {
                Set<String> only = new HashSet<>(StatusPrivacyManager.getOnlyList(env.ctx));
                only.add(r.uid);
                StatusPrivacyManager.setOnlyList(env.ctx, only);
            }
            refreshNow(env);
            undoSnack(env, env.ctx.getString(R.string.seenby_unhidden_done, nameOf(env, r)), v -> restore.run());
        } catch (Exception e) {
            Toast.makeText(env.ctx, R.string.seenby_err_privacy, Toast.LENGTH_SHORT).show();
        }
    }

    private static void confirmBlock(final Env env, final Row r) {
        final String nm = nameOf(env, r);
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(darkDialogCtx(env.ctx))
                .setTitle(env.ctx.getString(R.string.seenby_block_title, nm))
                .setMessage(R.string.seenby_block_msg)
                .setNegativeButton(R.string.seenby_cancel, null)
                .setPositiveButton(R.string.seenby_block_confirm, (d, w) -> {
                    if (env.myUid == null) return;
                    FirebaseUtils.getBlocksRef(env.myUid).child(r.uid).setValue(true)
                            .addOnSuccessListener(u -> {
                                env.removed.add(r.uid);
                                refreshNow(env);   // list, title count AND the stats strip drop this viewer
                                undoSnack(env, env.ctx.getString(R.string.seenby_blocked, nm), v ->
                                        FirebaseUtils.getBlocksRef(env.myUid).child(r.uid).removeValue()
                                                .addOnSuccessListener(x -> {
                                                    env.removed.remove(r.uid);
                                                    if (!env.dismissed) refreshNow(env);
                                                    Toast.makeText(env.ctx, env.ctx.getString(R.string.seenby_unblocked, nm),
                                                            Toast.LENGTH_SHORT).show();
                                                })
                                                .addOnFailureListener(e -> Toast.makeText(env.ctx,
                                                        R.string.seenby_err_unblock, Toast.LENGTH_SHORT).show()));
                            })
                            .addOnFailureListener(e -> Toast.makeText(env.ctx,
                                    R.string.seenby_err_block, Toast.LENGTH_SHORT).show());
                }).create();
        AlertDialogStyler.showRounded(dlg);   // shared rounded style; "Block" button is red there (destructive)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Fixed dark palette (the screen is always dark, like Instagram's viewers screen). */
    private static Style resolveStyle(Context ctx) {
        Style s = new Style();
        s.bg        = 0xFF0F0F0F;
        s.surface   = 0xFF262626;   // popup menu card (opaque)
        s.pill      = 0xFF262626;   // "Sort by" pill
        s.primary   = 0xFFFFFFFF;
        s.secondary = 0xFFA8A8A8;
        s.divider   = 0x26FFFFFF;
        s.onPrimary = 0xFF000000;
        s.accent    = 0xFF3DDC84;   // close-friend green / ✓
        return s;
    }

    /** Vector tinted + sized for compound drawables / spans. */
    private static Drawable tinted(Context ctx, int res, int color, int sizeDp) {
        Drawable d = ContextCompat.getDrawable(ctx, res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        int px = dp(ctx, sizeDp);
        d.setBounds(0, 0, px, px);
        return d;
    }

    private static Drawable ripple(Context ctx) { return themeDrawable(ctx, android.R.attr.selectableItemBackground); }
    private static Drawable rippleBorderless(Context ctx) { return themeDrawable(ctx, android.R.attr.selectableItemBackgroundBorderless); }

    private static Drawable themeDrawable(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        if (!ctx.getTheme().resolveAttribute(attr, tv, true) || tv.resourceId == 0) return null;
        return ContextCompat.getDrawable(ctx, tv.resourceId);
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
