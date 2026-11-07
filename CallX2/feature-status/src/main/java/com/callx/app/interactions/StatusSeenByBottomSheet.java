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
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
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
 *  v36: swipe the list left / right = next / previous story's viewers (SwipeFrame + swipeStory).
 *  v37 swipe polish: (1) quick double swipe = jump, nothing is cancelled; (2) new list is painted before it slides in (no skeleton flash);
 *   (3) TalkBack actions Next / Previous story; (4) "2/5" counter + one-time hint; (5) haptic tick on the first / last story;
 *   (6) RTL: swipe direction mirrored; (7) switching a story resets search + reaction filter and closes the keyboard;
 *   (8) swipe also works on the empty / skeleton / limit-hint area; (9) selected card is scrolled fully into view.
 *  v34 polish: tap-to-retry bar + first-page error state (#2), search-limit hint (#4), follower chip counts (#6),
 *   one shared viewer-total rule with the viewer's bar (#8), spoken row descriptions (#9), light theme (#10).
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
    private static final long PROFILE_TTL_MS = 6 * 60 * 60 * 1000L;   // v33: stale profiles are shown instantly, re-read only when bound AND older than this
    private static final String PROF_PREFS = "status_seenby_profiles", PROF_KEY = "v1";
    private static final int PROF_DISK_MAX = 800;
    private static volatile boolean DISK_LOADED;
    private static final int  MAX_INFLIGHT   = 6;
    /** v32 Instagram-style paging: newest viewers first, PAGE per read; older pages load as the list is scrolled. */
    private static final int  PAGE = 40, LOAD_AHEAD = 12, REPLY_WINDOW = 300;
    /** Search / follower filters need more than the visible page: bounded auto-load + faster profile pump. */
    private static final int  SEARCH_LOAD_CAP = 2000, SEARCH_INFLIGHT = 12, FOLLOW_INFLIGHT = 8;

    private static final String PREFS = "status_seenby_prefs";
    private static final String KEY_FILTER = "filter", KEY_RELEVANCE = "relevance_sort", KEY_SWIPE_HINT = "swipe_hint_shown";
    private static final int SW_IDLE = 0, SW_OUT = 1, SW_WAIT = 2, SW_IN = 3;   // v37 swipe state machine

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
    private static final class Style { int primary, secondary, divider, onPrimary, accent, surface, bg, pill, segDim; boolean dark; }

    /** Everything the bg thread needs, copied on main. */
    private static final class Snap {
        HashMap<String, Long> seenAt; HashMap<String, String> reactions; ArrayList<StatusItem.ReplyPreview> replies;
        HashMap<String, Profile> profiles; Map<String, Profile> seeds;
        Set<String> followers, followerChecked, contacts, closeFriends, removed, except, only;
        String mode, query, emoji; boolean relevance; int filter; int counter; int barTotal; boolean exhausted;
    }

    private static final class Result {
        List<Row> rows; int totalRows, views, likes, replies; int[] counts; boolean followPartial;
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
        // v32: follower status is checked per loaded viewer (only while a follower filter is on), never as one bulk read of ALL followers
        final Set<String> followerChecked = new HashSet<>(), fInFlight = new HashSet<>();

        // v32 paging state (main thread only)
        long cursorTs = Long.MAX_VALUE; String cursorKey;     // oldest (value,key) loaded from seenBy
        boolean exhausted, loadingMore, loadFailed, initialFailed; int pageGen;   // v34: loadFailed = visible retry bar (no silent retry)
        // v33: first-load skeleton, bottom loader, "N new viewers" pill, lazy seen-time lookup, disk profile cache
        boolean initialLoaded, profilesDirty, saveQueued; long topBaseTs;
        LinearLayout skeleton; android.animation.ObjectAnimator skelAnim; FrameLayout footer; TextView newPill;
        // v34: footer retry bar, search-limit hint, first-page retry, follower chip counts
        ProgressBar footerSpin; TextView footerErr, tvLimitHint, btnEmptyRetry;
        boolean followCountWanted, followPartial;
        SwipeFrame listWrap;   // v36: swipe left/right on the list = next / previous story
        // v37: swipe state machine (main thread). OUT = list sliding out, WAIT = hidden until the new rows are painted, IN = sliding in
        int swipeState = SW_IDLE, pendingIdx = -1, swipeVis = -1; boolean slideInPending;
        final Runnable slideInRun = new Runnable() { @Override public void run() { startSlideIn(Env.this); } };
        TextView tvCounter, tvSwipeHint;
        final int[] a11yTitle = {-1, -1}, a11yList = {-1, -1};   // ids of the Next / Previous story actions
        final HashSet<String> tsTried = new HashSet<>(), tsInFlight = new HashSet<>();
        final ArrayDeque<String> tsQueue = new ArrayDeque<>();

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
        ChildEventListener seenCL, reactCL, replyCL; DatabaseReference seenRef, reactRef, replyRef; Query seenQ, replyQ;

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

    /**
     * v34 (#8): the ONE rule for the viewer total, used by the sheet title / "N viewers" AND the viewer's bottom bar.
     * @param counter        server-side viewCount of the story (0 = unknown / legacy story)
     * @param loadedNet      distinct viewers known locally (seenBy + reactors + repliers), blocked ones already removed
     * @param removedAmongLoaded blocked viewers that were part of the loaded set (they are also inside {@code counter})
     */
    static int totalOf(int counter, int loadedNet, int removedAmongLoaded) {
        return Math.max(counter > 0 ? counter - removedAmongLoaded : 0, loadedNet);
    }

    /** Bar-side entry point of {@link #totalOf}: same viewer set as {@link #viewerUids}, plus the story's server counter. */
    public static int totalViewers(StatusItem item, Set<String> removed, int counter) {
        if (item == null) return 0;
        Set<String> all = viewerUids(item, null);
        int removedSeen = 0;
        if (removed != null) for (String r : removed) if (all.contains(r)) removedSeen++;
        return totalOf(counter, all.size() - removedSeen, removedSeen);
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
        env.pageGen++; env.cursorTs = Long.MAX_VALUE; env.cursorKey = null;
        env.exhausted = false; env.loadingMore = false; env.loadFailed = false; env.initialFailed = false;
        env.initialLoaded = false; env.topBaseTs = 0L;
        env.tsTried.clear(); env.tsInFlight.clear(); env.tsQueue.clear();
        // v32: seed only the newest PAGE viewers from the snapshot (the rest arrive page by page), so a huge seenBy never becomes a huge list.
        if (item.seenBy != null && !item.seenBy.isEmpty()) {
            ArrayList<Map.Entry<String, Long>> es = new ArrayList<>(item.seenBy.entrySet());
            Collections.sort(es, (a, b) -> Long.compare(b.getValue() != null ? b.getValue() : 0L, a.getValue() != null ? a.getValue() : 0L));
            for (int i = 0; i < es.size() && i < PAGE; i++) {
                Map.Entry<String, Long> e = es.get(i);
                if (e.getKey() == null) continue;
                long ts = e.getValue() != null ? e.getValue() : 0L;
                env.seenAt.put(e.getKey(), ts);
                noteCursor(env, e.getKey(), ts);
            }
        }
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
        // v718: rounded top corners. A square setBackgroundColor on root used to cover the sheet's rounded corners -> now root is
        // rounded too and clips its children (rv/header) to the same outline.
        final float cornerPx = dp(ctx, SHEET_CORNER_DP);
        {
            GradientDrawable rbg = new GradientDrawable();
            rbg.setColor(env.style.bg);
            rbg.setCornerRadii(new float[]{cornerPx, cornerPx, cornerPx, cornerPx, 0, 0, 0, 0});
            root.setBackground(rbg);
            root.setClipToOutline(true);
            root.setOutlineProvider(topRoundOutline(cornerPx));
        }
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
        // v37 (#4): "2/5" story counter (also keeps the title centred)
        env.tvCounter = new TextView(ctx);
        env.tvCounter.setTextSize(12);
        env.tvCounter.setTextColor(env.style.secondary);
        env.tvCounter.setGravity(Gravity.CENTER);
        env.tvCounter.setSingleLine(true);
        env.tvCounter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        titleRow.addView(env.tvCounter, new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44)));
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

        // v34: shown when search / a follower filter only covers the viewers loaded so far (cap reached)
        env.tvLimitHint = new TextView(ctx);
        env.tvLimitHint.setTextSize(12);
        env.tvLimitHint.setTextColor(env.style.secondary);
        env.tvLimitHint.setPadding(dp(ctx, 16), 0, dp(ctx, 16), dp(ctx, 8));
        env.tvLimitHint.setVisibility(View.GONE);
        // v37 (#8): limit hint / progress / empty state / skeleton / list share ONE swipe area, so a swipe works on blank + loading states too
        final LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(env.tvLimitHint);

        env.progress = new ProgressBar(ctx);
        env.progress.setVisibility(View.GONE);
        column.addView(env.progress);

        column.addView(buildEmptyState(env));
        column.addView(buildSkeleton(env));

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
            @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dy > 0) maybeLoadMore(env);
                LinearLayoutManager llm = (LinearLayoutManager) r.getLayoutManager();
                if (llm != null && llm.findFirstCompletelyVisibleItemPosition() == 0) markAtTop(env);
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView r, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING && env.search != null && env.search.hasFocus()) {
                    hideKeyboard(env, env.search);
                    root.requestFocus();           // takes focus off the EditText, keeps the query
                }
            }
        });
        // list + floating "N new viewers" pill
        final SwipeFrame listWrap = new SwipeFrame(ctx, dir -> swipeStory(env, dir));
        env.listWrap = listWrap;
        column.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listWrap.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        env.newPill = new TextView(ctx);
        env.newPill.setTextSize(13);
        env.newPill.setTextColor(env.style.onPrimary);
        env.newPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        env.newPill.setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8));
        GradientDrawable npbg = new GradientDrawable();
        npbg.setCornerRadius(dp(ctx, 20));
        npbg.setColor(env.style.primary);
        env.newPill.setBackground(npbg);
        env.newPill.setElevation(dp(ctx, 6));
        env.newPill.setClickable(true);
        env.newPill.setVisibility(View.GONE);
        env.newPill.setOnClickListener(v -> env.rv.smoothScrollToPosition(0));
        FrameLayout.LayoutParams nplp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nplp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        nplp.topMargin = dp(ctx, 10);
        listWrap.addView(env.newPill, nplp);
        // v37 (#4): one-time hint
        env.tvSwipeHint = new TextView(ctx);
        env.tvSwipeHint.setText(R.string.seenby_swipe_hint);
        env.tvSwipeHint.setTextSize(13);
        env.tvSwipeHint.setTextColor(env.style.primary);
        env.tvSwipeHint.setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9));
        GradientDrawable shbg = new GradientDrawable();
        shbg.setCornerRadius(dp(ctx, 20));
        shbg.setColor(env.style.pill);
        env.tvSwipeHint.setBackground(shbg);
        env.tvSwipeHint.setElevation(dp(ctx, 4));
        env.tvSwipeHint.setAlpha(0f);
        env.tvSwipeHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams shlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        shlp.bottomMargin = dp(ctx, 24);
        listWrap.addView(env.tvSwipeHint, shlp);
        updateStoryChrome(env);   // counter + TalkBack actions
        root.addView(listWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        // bottom loader while an older page is being read
        env.footer = new FrameLayout(ctx);
        ProgressBar fpb = new ProgressBar(ctx);
        fpb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(env.style.secondary));
        FrameLayout.LayoutParams fplp = new FrameLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26));
        fplp.gravity = Gravity.CENTER;
        env.footer.addView(fpb, fplp);
        env.footerSpin = fpb;
        env.footerErr = new TextView(ctx);
        env.footerErr.setText(R.string.seenby_err_load_more);
        env.footerErr.setTextSize(13);
        env.footerErr.setTextColor(env.style.primary);
        env.footerErr.setGravity(Gravity.CENTER);
        env.footerErr.setClickable(true);
        env.footerErr.setBackground(ripple(env.style.primary));
        env.footerErr.setVisibility(View.GONE);
        env.footerErr.setOnClickListener(v -> retryLoad(env));
        env.footer.addView(env.footerErr, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        env.footer.setVisibility(View.GONE);
        root.addView(env.footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 44)));
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
            if (env.skelAnim != null) env.skelAnim.cancel();
            if (env.profilesDirty) saveProfilesToDisk(ctx.getApplicationContext());
            if (onClosed != null) onClosed.onClosed(new HashSet<>(env.removed));
        });
        sheet.setOnShowListener(d -> {
            View sh = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sh == null) sh = (View) root.getParent();
            if (sh != null) {
                ViewGroup.LayoutParams lp = sh.getLayoutParams();
                if (lp != null) { lp.height = ViewGroup.LayoutParams.MATCH_PARENT; sh.setLayoutParams(lp); }
                GradientDrawable g = new GradientDrawable();
                float r = cornerPx;
                g.setColor(env.style.bg);
                g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                sh.setBackground(g);
                sh.setClipToOutline(true);
                sh.setOutlineProvider(topRoundOutline(r));
                BottomSheetBehavior<View> b = BottomSheetBehavior.from(sh);
                b.setSkipCollapsed(true);
                b.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            if (sheet.getWindow() != null) {
                sheet.getWindow().setNavigationBarColor(env.style.bg);
                try {   // v34: bar icons follow the palette (dark icons on the light sheet, light icons on the dark one)
                    androidx.core.view.WindowInsetsControllerCompat wc =
                            new androidx.core.view.WindowInsetsControllerCompat(sheet.getWindow(), root);
                    wc.setAppearanceLightStatusBars(!env.style.dark);
                    wc.setAppearanceLightNavigationBars(!env.style.dark);
                } catch (Exception ignored) {}
            }
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
        maybeShowSwipeHint(env);

        // 1) Paint instantly (cached profiles + local data), 2) go live, 3) lazy-load the rest.
        paintSortPill(env);
        refreshNow(env);
        attachLive(env);
        attachCardCounts(env);
        loadRelationsAsync(env, ctx.getApplicationContext());
        loadProfilesFromDisk(env, ctx.getApplicationContext());
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
            g.setColor(i <= env.selIdx ? env.style.primary : env.style.segDim);
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
        t.setBackground(rippleBorderless(env.style.primary));
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
        searchBtn.setBackground(rippleBorderless(env.style.primary));
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

    private static final int SHEET_CORNER_DP = 24;

    /** Outline with only the TOP corners rounded (the rect is extended below the view so the bottom corners stay square). */
    private static ViewOutlineProvider topRoundOutline(final float r) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight() + (int) r, r);
            }
        };
    }

    private static final int CARD_AREA_DP = 148, CARD_W_DP = 76, CARD_H_DP = 128, CARD_SEL_W_DP = 92, CARD_SEL_H_DP = 140;
    private static final Object PAYLOAD_COUNT = "count";

    /** Selected story = live (block-aware) count; the others = their own live seenBy size (falls back to the snapshot). */
    private static int countFor(Env env, int pos) {
        if (pos == env.selIdx) return env.liveViews;
        StatusItem st = env.stories.get(pos);
        Integer c = st.id != null ? env.cardCounts.get(st.id) : null;
        int snap = st.getViewCount();
        return c != null ? Math.max(c, snap) : snap;
    }

    /** Server-side viewCount of the selected story (0 = unknown / legacy story without a counter). */
    private static int selCounter(Env env) {
        Integer c = env.statusId != null ? env.cardCounts.get(env.statusId) : null;
        return c != null ? c : 0;
    }

    /**
     * v32: one listener on the tiny {@code viewCount} node per story (a single number) — never on the whole seenBy node, which for a
     * popular story is thousands of entries re-downloaded on every new viewer. Stories without a counter keep their snapshot count.
     */
    private static void attachCardCounts(final Env env) {
        for (int i = 0; i < env.stories.size(); i++) {
            final StatusItem st = env.stories.get(i);
            final String sid = st.id;
            final String owner = st.ownerUid != null ? st.ownerUid : env.myUid;
            if (sid == null || owner == null) continue;
            try {
                final DatabaseReference ref = FirebaseUtils.getStatusRef().child(owner).child(sid).child("viewCount");
                ValueEventListener l = new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (env.dismissed) return;
                        Long v;
                        try { v = snap.getValue(Long.class); } catch (Exception e) { return; }
                        if (v == null) return;
                        final int n = (int) Math.min(Math.max(v, 0L), Integer.MAX_VALUE);
                        Integer prev = env.cardCounts.put(sid, n);
                        if (prev != null && prev == n) return;
                        if (sid.equals(env.statusId)) scheduleRefresh(env);   // title / "N viewers" of the open story
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
        env.pendingIdx = -1;
        // v37 (#7): ONE rule on a story change: the per-story look-ups (search text, reaction filter) reset and the keyboard closes;
        // the remembered sort + "Show" filter stay (they are visible in the Sort pill).
        if (env.searchBox != null && env.searchBox.getVisibility() == View.VISIBLE) toggleSearch(env);
        else if (env.search != null) { hideKeyboard(env, env.search); if (env.search.hasFocus() && env.root != null) env.root.requestFocus(); }
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
        ensureCardVisible(env, pos);
        env.tvTitle.setText(env.ctx.getString(R.string.seenby_title, env.liveViews));
        env.tvViewers.setText(env.ctx.getResources().getQuantityString(R.plurals.seenby_viewers,
                env.liveViews, env.liveViews));
        paintSortPill(env);
        updateStoryChrome(env);
        refreshNow(env);
        attachLive(env);
    }

    /**
     * v37: swipe the viewers list = next / previous story. {@code dir}: +1 = next, -1 = previous (already mirrored for RTL by the caller).
     * <p>State machine, so nothing is cancelled and nothing flashes:
     * OUT (slides away; further swipes only move the target = a jump) -> switch -> WAIT (list stays hidden until the new rows are
     * painted, max 260 ms) -> IN (slides in). A swipe during WAIT / IN switches again immediately.
     * Returns false at the first / last story (haptic tick, the list just bounces back).
     */
    private static boolean swipeStory(final Env env, final int dir) {
        if (env.dismissed || env.listWrap == null) return false;
        final SwipeFrame w = env.listWrap;
        final int n = env.stories.size();
        final int base = env.swipeState == SW_OUT && env.pendingIdx >= 0 ? env.pendingIdx : env.selIdx;
        final int target = base + dir;
        if (target < 0 || target >= n) {
            w.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);   // v37 (#5)
            return false;
        }
        final boolean rtl = w.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final int vis = rtl ? dir : -dir;   // -1 = content leaves to the left, +1 = to the right
        hideSwipeHint(env);
        switch (env.swipeState) {
            case SW_OUT:
                env.pendingIdx = target; env.swipeVis = vis;   // quick second swipe: jump straight to the new target when the slide-out ends
                return true;
            case SW_WAIT: {
                env.main.removeCallbacks(env.slideInRun);
                final float shift = w.getWidth() * 0.25f;
                switchStory(env, target);
                env.swipeVis = vis;
                w.setTranslationX(-vis * shift);
                armSlideIn(env);
                return true;
            }
            case SW_IN:
                w.animate().cancel();   // the end action of the slide-in is not needed, state is reset below
                // fall through
            default:
                env.pendingIdx = target;
                swipeOut(env, vis);
                return true;
        }
    }

    private static void swipeOut(final Env env, final int vis) {
        final SwipeFrame w = env.listWrap;
        final float shift = w.getWidth() * 0.25f;
        env.swipeState = SW_OUT; env.swipeVis = vis; w.busy = true;
        w.animate().cancel();
        w.animate().translationX(vis * shift).alpha(0f).setDuration(110).withEndAction(() -> {
            if (env.dismissed) return;
            final int t = env.pendingIdx;
            env.pendingIdx = -1;
            w.setTranslationX(-env.swipeVis * shift);   // enters from the opposite side
            if (t >= 0 && t < env.stories.size() && t != env.selIdx) {
                switchStory(env, t);
                armSlideIn(env);
            } else {
                env.swipeState = SW_WAIT;
                startSlideIn(env);
            }
        }).start();
    }

    /** List stays invisible until apply() has painted the new story's rows (or 260 ms), then slides in. */
    private static void armSlideIn(Env env) {
        env.swipeState = SW_WAIT;
        env.slideInPending = true;
        env.listWrap.busy = true;
        env.main.removeCallbacks(env.slideInRun);
        env.main.postDelayed(env.slideInRun, 260);
    }

    private static void startSlideIn(final Env env) {
        if (env.dismissed || env.listWrap == null) return;
        env.main.removeCallbacks(env.slideInRun);
        env.slideInPending = false;
        if (env.swipeState != SW_WAIT) return;
        final SwipeFrame w = env.listWrap;
        env.swipeState = SW_IN;
        w.animate().translationX(0f).alpha(1f).setDuration(170).withEndAction(() -> {
            if (env.swipeState == SW_IN) { env.swipeState = SW_IDLE; w.busy = false; }
        }).start();
    }

    // ── v37: story chrome (counter, TalkBack actions), one-time hint, card visibility ─────────────────

    /** "2/5" counter + spoken position + Next / Previous story actions (only the ones that are possible). */
    private static void updateStoryChrome(final Env env) {
        final int n = env.stories.size();
        if (env.tvCounter != null) {
            if (n > 1) {
                env.tvCounter.setText(env.ctx.getString(R.string.seenby_story_counter, env.selIdx + 1, n));
                env.tvCounter.setContentDescription(env.ctx.getString(R.string.seenby_cd_story_position, env.selIdx + 1, n));
                env.tvCounter.setVisibility(View.VISIBLE);
            } else env.tvCounter.setVisibility(View.INVISIBLE);
        }
        setStoryActions(env, env.tvTitle, env.a11yTitle);
        setStoryActions(env, env.listWrap, env.a11yList);
    }

    private static void setStoryActions(final Env env, View v, int[] ids) {
        if (v == null) return;
        for (int i = 0; i < 2; i++) {
            if (ids[i] != -1) { ViewCompat.removeAccessibilityAction(v, ids[i]); ids[i] = -1; }
        }
        if (env.stories.size() <= 1) return;
        if (env.selIdx + 1 < env.stories.size())
            ids[0] = ViewCompat.addAccessibilityAction(v, env.ctx.getString(R.string.seenby_action_next_story),
                    (view, args) -> { swipeStory(env, +1); return true; });
        if (env.selIdx > 0)
            ids[1] = ViewCompat.addAccessibilityAction(v, env.ctx.getString(R.string.seenby_action_prev_story),
                    (view, args) -> { swipeStory(env, -1); return true; });
    }

    /** First time the sheet opens with 2+ stories: a short hint pill + a small nudge of the list. Shown once. */
    private static void maybeShowSwipeHint(final Env env) {
        if (env.stories.size() <= 1 || env.tvSwipeHint == null) return;
        final SharedPreferences sp = env.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_SWIPE_HINT, false)) return;
        env.main.postDelayed(() -> {
            if (env.dismissed || env.swipeState != SW_IDLE) return;
            sp.edit().putBoolean(KEY_SWIPE_HINT, true).apply();
            env.tvSwipeHint.setVisibility(View.VISIBLE);
            env.tvSwipeHint.animate().alpha(1f).setDuration(200).start();
            final boolean rtl = env.listWrap.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            final float peek = (rtl ? 1 : -1) * dp(env.ctx, 18);   // hints at the direction of "next"
            env.listWrap.animate().translationX(peek).setDuration(220).withEndAction(() -> {
                if (!env.dismissed && env.swipeState == SW_IDLE) env.listWrap.animate().translationX(0f).setDuration(260).start();
            }).start();
            env.main.postDelayed(() -> hideSwipeHint(env), 2800);
        }, 700);
    }

    private static void hideSwipeHint(Env env) {
        if (env.tvSwipeHint == null || env.tvSwipeHint.getVisibility() != View.VISIBLE) return;
        env.tvSwipeHint.animate().cancel();
        env.tvSwipeHint.animate().alpha(0f).setDuration(200).withEndAction(() -> env.tvSwipeHint.setVisibility(View.GONE)).start();
    }

    /** v37 (#9): scrolls the (just resized) selected card until it is FULLY inside the carousel, not just "somewhere near". */
    private static void ensureCardVisible(final Env env, final int pos) {
        final RecyclerView c = env.carousel;
        if (c == null) return;
        c.post(() -> {
            if (env.dismissed) return;
            // wait for the layout pass that applies the new card size, then scroll with the real bounds
            c.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    c.getViewTreeObserver().removeOnPreDrawListener(this);
                    RecyclerView.LayoutManager lm = c.getLayoutManager();
                    if (env.dismissed || lm == null || pos >= env.stories.size()) return true;
                    androidx.recyclerview.widget.LinearSmoothScroller sc = new androidx.recyclerview.widget.LinearSmoothScroller(c.getContext()) {
                        @Override protected int getHorizontalSnapPreference() { return SNAP_TO_ANY; }   // only as far as needed to fit the card
                    };
                    sc.setTargetPosition(pos);
                    lm.startSmoothScroll(sc);
                    return true;
                }
            });
            c.requestLayout();
        });
    }

    /** List container that turns a mostly-horizontal drag into a story swipe; vertical scroll / sheet drag / row taps are untouched. */
    private static final class SwipeFrame extends FrameLayout {
        interface Listener { boolean onSwipe(int dir); }   // +1 = next story, -1 = previous (RTL already applied)
        private final Listener listener;
        private final int slop, minDist, flingDist;
        private float downX, downY; private long downTime;
        private boolean swiping, tracking;
        boolean busy;   // v37: a slide animation is running -> the view must not follow the finger or spring back on its own

        SwipeFrame(Context ctx, Listener l) {
            super(ctx);
            listener = l;
            slop = android.view.ViewConfiguration.get(ctx).getScaledTouchSlop();
            minDist = dp(ctx, 72);
            flingDist = dp(ctx, 40);
            setClickable(true);   // also receives the gesture when no row consumed the touch (empty / blank area)
        }

        // raw coordinates: the view itself moves with the finger while dragging
        private void begin(android.view.MotionEvent ev) {
            downX = ev.getRawX(); downY = ev.getRawY(); downTime = ev.getEventTime();
            swiping = false; tracking = true;
        }

        private boolean checkStart(android.view.MotionEvent ev) {
            if (swiping) return true;
            float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                swiping = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);   // sheet drag / RecyclerView stay out
            }
            return swiping;
        }

        private void finish(android.view.MotionEvent ev, boolean cancel) {
            if (!tracking) return;
            tracking = false;
            boolean handled = false;
            if (swiping && !cancel) {
                float dx = ev.getRawX() - downX;
                boolean far = Math.abs(dx) > minDist;
                boolean fast = Math.abs(dx) > flingDist && ev.getEventTime() - downTime < 250;
                if (far || fast) {
                    int vis = dx < 0 ? -1 : 1;   // finger moved left / right
                    boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                    handled = listener.onSwipe(rtl ? vis : -vis);   // v37 (#6): RTL = mirrored (swipe right = next)
                }
            }
            if (!handled && !busy && getTranslationX() != 0f) animate().translationX(0f).setDuration(150).start();
            swiping = false;
        }

        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: begin(ev); return false;
                case android.view.MotionEvent.ACTION_MOVE: return tracking && checkStart(ev);
                case android.view.MotionEvent.ACTION_UP: finish(ev, false); return false;
                case android.view.MotionEvent.ACTION_CANCEL: finish(ev, true); return false;
                default: return false;
            }
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: begin(ev); return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (tracking && checkStart(ev) && !busy) setTranslationX((ev.getRawX() - downX) * 0.4f);   // follows the finger, with resistance
                    return true;
                case android.view.MotionEvent.ACTION_UP: finish(ev, false); return true;
                case android.view.MotionEvent.ACTION_CANCEL: finish(ev, true); return true;
                default: return true;
            }
        }
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
        // v34 (#6): opening the menu starts the (tiny, per-viewer) follower checks so the chips can show counts
        if (!env.followCountWanted) { env.followCountWanted = true; pumpFollowerChecks(env); }
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
            if (i == F_FOLLOWERS || i == F_NOT_FOLLOWING) {
                if (n >= 0) label = ctx.getString(env.followPartial ? R.string.seenby_chip_count_partial : R.string.seenby_chip_count, label, n);
                else if (env.followPartial) label = ctx.getString(R.string.seenby_chip_count_loading, label);
            } else if (n >= 0) label = ctx.getString(R.string.seenby_chip_count, label, n);
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
        @Override public void onBindViewHolder(@NonNull CardVH h, int position) { h.bind(position); preloadAround(position); }
        @Override public void onBindViewHolder(@NonNull CardVH h, int position, @NonNull List<Object> payloads) {
            if (!payloads.isEmpty() && payloads.contains(PAYLOAD_COUNT)) h.bindCount(position);
            else { h.bind(position); preloadAround(position); }
        }
        /** v716: warm the thumbs just outside the visible cards (and the selected story's neighbours) once each, so a card scrolled in
         *  or tapped is already a Glide memory hit. Same request as the Status tab cards, so StatusCardPrewarm hits are reused. */
        private final java.util.HashSet<String> preloaded = new java.util.HashSet<>();
        private void preloadAround(int pos) {
            final int n = env.stories.size();
            for (int k = -3; k <= 3; k++) {
                final int i = pos + k;
                if (k == 0 || i < 0 || i >= n) continue;
                final String t = StatusListAdapter.cardThumb(env.stories.get(i));
                if (t != null && preloaded.add(t)) StatusListAdapter.preloadCardShared(env.ctx, t);
            }
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
        String boundKey;   // thumb@size currently shown in img
        boolean imgReady;  // real image decoded into img (blur placeholder must never overwrite it)
        final android.graphics.drawable.ColorDrawable ph = new android.graphics.drawable.ColorDrawable(0xFF1C1C1C);
        final com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> imgListener =
                new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
            @Override public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t, boolean first) { return false; }
            @Override public boolean onResourceReady(android.graphics.drawable.Drawable r, Object model,
                    com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> t,
                    com.bumptech.glide.load.DataSource ds, boolean first) { imgReady = true; return false; }
        };

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
                inner.setBackgroundColor(0xFF1C1C1C);
                txt.setVisibility(View.GONE);
                img.setVisibility(View.VISIBLE);
                // Same image already showing in this holder (selection frame / count change rebinds) -> no URL work, no Glide request.
                final String key = thumb;   // one size for every card now, so the thumb alone identifies the image
                if (!key.equals(boundKey)) {
                    boundKey = key;
                    imgReady = false;
                    // Identical to the Status tab card request (URL, px, format): shares its Glide cache + StatusCardPrewarm.
                    StatusListAdapter.cardRequestShared(ctx, thumb)
                            .placeholder(ph)
                            .listener(imgListener)
                            .into(img);
                    // v716: ThumbHash blur-up until the real image lands (a memory-cache hit is already applied synchronously above).
                    if (s.thumbHash != null && !imgReady) {
                        final String hashKey = key;
                        com.callx.app.utils.ThumbHashPlaceholder.getAsync(s.thumbHash, 24, 38, bmp -> {
                            if (bmp == null || imgReady || !hashKey.equals(boundKey)) return;
                            img.setImageDrawable(new android.graphics.drawable.BitmapDrawable(img.getResources(), bmp));
                        });
                    }
                }
            } else {
                boundKey = null; imgReady = false;
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
            boundKey = null; imgReady = false;
            try { com.bumptech.glide.Glide.with(env.ctx).clear(img); } catch (Exception ignored) {}
        }
    }

    // ── Live data (#1) ────────────────────────────────────────────────────────

    /**
     * v32 live data, Instagram style:
     *  - seenBy: ONLY the newest PAGE viewers (orderByValue = timestamp, limitToLast). The child listener gives the first page and then
     *    just the delta (new viewer / changed time) — the whole node is never re-downloaded. Older pages come from {@link #loadMore}.
     *  - reactions: child listener (a reaction change is one small event, not a full snapshot + full rebuild).
     *  - replies: newest REPLY_WINDOW only (indexed on timestamp).
     */
    private static void attachLive(final Env env) {
        if (env.statusId == null || env.ownerUid == null) return;
        final int pg = env.pageGen;
        try {
            env.seenRef  = FirebaseUtils.getStatusSeenByRef(env.ownerUid, env.statusId);
            env.reactRef = FirebaseUtils.getStatusRef().child(env.ownerUid).child(env.statusId).child("reactions");
            env.replyRef = FirebaseUtils.getStatusRepliesRef(env.ownerUid, env.statusId);
            env.seenQ  = env.seenRef.orderByValue().limitToLast(PAGE);
            env.replyQ = env.replyRef.orderByChild("timestamp").limitToLast(REPLY_WINDOW);

            env.seenCL = new ChildEventListener() {
                @Override public void onChildAdded(@NonNull DataSnapshot c, String prev) { onSeen(env, pg, c); }
                @Override public void onChildChanged(@NonNull DataSnapshot c, String prev) { onSeen(env, pg, c); }
                // The window slides when a new viewer arrives and reports the oldest entry as "removed" — that is NOT a deletion, so ignore it.
                @Override public void onChildRemoved(@NonNull DataSnapshot c) {}
                @Override public void onChildMoved(@NonNull DataSnapshot c, String prev) {}
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            env.reactCL = new ChildEventListener() {
                @Override public void onChildAdded(@NonNull DataSnapshot c, String prev) { onReaction(env, pg, c, false); }
                @Override public void onChildChanged(@NonNull DataSnapshot c, String prev) { onReaction(env, pg, c, false); }
                @Override public void onChildRemoved(@NonNull DataSnapshot c) { onReaction(env, pg, c, true); }
                @Override public void onChildMoved(@NonNull DataSnapshot c, String prev) {}
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            env.replyCL = new ChildEventListener() {
                @Override public void onChildAdded(@NonNull DataSnapshot c, String prev) { onReply(env, pg, c, false); }
                @Override public void onChildChanged(@NonNull DataSnapshot c, String prev) { onReply(env, pg, c, false); }
                @Override public void onChildRemoved(@NonNull DataSnapshot c) { onReply(env, pg, c, true); }
                @Override public void onChildMoved(@NonNull DataSnapshot c, String prev) {}
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            };
            env.seenQ.addChildEventListener(env.seenCL);
            env.reactRef.addChildEventListener(env.reactCL);
            env.replyQ.addChildEventListener(env.replyCL);
            env.seenQ.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (env.dismissed || pg != env.pageGen) return;
                    env.initialLoaded = true; scheduleRefresh(env);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (env.dismissed || pg != env.pageGen) return;
                    env.initialFailed = true;   // v34: "Couldn't load" + Retry instead of a false "No viewers yet"
                    env.initialLoaded = true; scheduleRefresh(env);
                }
            });
        } catch (Exception ignored) { /* static snapshot still works */ }
    }

    private static void onSeen(Env env, int pg, DataSnapshot c) {
        if (env.dismissed || pg != env.pageGen || c.getKey() == null) return;
        Object v = c.getValue();
        long ts = v instanceof Number ? ((Number) v).longValue() : 0L;
        env.seenAt.put(c.getKey(), ts);
        noteCursor(env, c.getKey(), ts);
        scheduleRefresh(env);
    }

    private static void onReaction(Env env, int pg, DataSnapshot c, boolean removed) {
        if (env.dismissed || pg != env.pageGen || c.getKey() == null) return;
        if (removed) env.reactions.remove(c.getKey());
        else {
            Object v = c.getValue();
            String e = v instanceof String ? (String) v : (Boolean.TRUE.equals(v) ? "\u2764\uFE0F" : null);
            if (e != null && !e.isEmpty()) env.reactions.put(c.getKey(), e); else env.reactions.remove(c.getKey());
        }
        scheduleRefresh(env);
    }

    private static void onReply(Env env, int pg, DataSnapshot c, boolean removed) {
        if (env.dismissed || pg != env.pageGen || c.getKey() == null) return;
        if (removed) {
            // Only a real delete while the window is not full (a full window also "removes" its oldest entry as it slides).
            if (env.replies.size() < REPLY_WINDOW) env.replies.remove(c.getKey());
        } else {
            try {
                StatusItem.ReplyPreview rp = c.getValue(StatusItem.ReplyPreview.class);
                if (rp != null) env.replies.put(c.getKey(), rp);
            } catch (Exception ignored) { return; }
        }
        scheduleRefresh(env);
    }

    private static void noteCursor(Env env, String key, long ts) {
        if (ts < env.cursorTs || (ts == env.cursorTs && (env.cursorKey == null || key.compareTo(env.cursorKey) < 0))) {
            env.cursorTs = ts; env.cursorKey = key;
        }
    }

    /** Older viewers: one page per call, only when the list is scrolled near its end (or a search / filter needs more). */
    private static void maybeLoadMore(Env env) {
        if (env.dismissed || env.rv == null || env.adapter == null) return;
        LinearLayoutManager lm = (LinearLayoutManager) env.rv.getLayoutManager();
        if (lm == null) return;
        int total = env.adapter.getItemCount();
        if (total == 0 || lm.findLastVisibleItemPosition() >= total - LOAD_AHEAD) loadMore(env);
    }

    private static void loadMore(final Env env) {
        if (env.dismissed || env.loadingMore || env.exhausted || env.seenRef == null || env.cursorKey == null) return;
        if (env.loadFailed) return;   // v34: waits for the user's tap on the retry bar
        env.loadingMore = true;
        updateFooter(env);
        final int pg = env.pageGen;
        // Inclusive cursor -> PAGE + 1 rows, the first of which (the cursor itself) is already loaded.
        env.seenRef.orderByValue().endAt((double) env.cursorTs, env.cursorKey).limitToLast(PAGE + 1).get()
                .addOnSuccessListener(snap -> BG.execute(() -> {
                    final HashMap<String, Long> m = new HashMap<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        if (c.getKey() == null) continue;
                        Object v = c.getValue();
                        m.put(c.getKey(), v instanceof Number ? ((Number) v).longValue() : 0L);
                    }
                    env.main.post(() -> {
                        if (env.dismissed || pg != env.pageGen) return;
                        env.loadingMore = false;
                        updateFooter(env);
                        for (Map.Entry<String, Long> e : m.entrySet()) {
                            env.seenAt.put(e.getKey(), e.getValue());
                            noteCursor(env, e.getKey(), e.getValue());
                        }
                        if (m.size() < PAGE + 1) env.exhausted = true;
                        scheduleRefresh(env);
                    });
                }))
                .addOnFailureListener(e -> env.main.post(() -> {
                    if (env.dismissed || pg != env.pageGen) return;
                    env.loadingMore = false;
                    env.loadFailed = true;      // v34: visible retry bar, not a silent retry
                    updateFooter(env);
                    updateProgress(env);
                    scheduleRefresh(env);
                }));
    }

    /** Tap on the footer retry bar. */
    private static void retryLoad(Env env) {
        if (env.dismissed) return;
        env.loadFailed = false;
        updateFooter(env);
        loadMore(env);
    }

    /** Retry button of the first-page error state: re-attach the live listeners. */
    private static void retryInitial(Env env) {
        if (env.dismissed) return;
        env.initialFailed = false; env.initialLoaded = false;
        detachLive(env);
        attachLive(env);
        scheduleRefresh(env);
    }

    private static void detachLive(Env env) {
        try {
            if (env.seenQ != null && env.seenCL != null) env.seenQ.removeEventListener(env.seenCL);
            if (env.reactRef != null && env.reactCL != null) env.reactRef.removeEventListener(env.reactCL);
            if (env.replyQ != null && env.replyCL != null) env.replyQ.removeEventListener(env.replyCL);
        } catch (Exception ignored) {}
        env.seenCL = null; env.reactCL = null; env.replyCL = null;
    }

    /** Chat partners / cached names from Room (no Firebase read). Followers are checked per viewer later (see pumpFollowerChecks). */
    private static void loadRelationsAsync(final Env env, final Context app) {
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

    /** Per-viewer follower check, ONLY while a follower filter is on (replaces the old read of the owner's entire follower list). */
    private static void pumpFollowerChecks(final Env env) {
        if (env.dismissed || env.myUid == null || (env.filter != F_FOLLOWERS && env.filter != F_NOT_FOLLOWING && !env.followCountWanted)) return;
        for (final String uid : universe(env)) {
            if (env.fInFlight.size() >= FOLLOW_INFLIGHT) break;
            if (env.followerChecked.contains(uid) || env.fInFlight.contains(uid)) continue;
            env.fInFlight.add(uid);
            FirebaseUtils.getReelFollowersRef(env.myUid).child(uid).get()
                    .addOnSuccessListener(snap -> {
                        env.fInFlight.remove(uid);
                        if (env.dismissed) return;
                        env.followerChecked.add(uid);
                        if (snap.exists()) env.followers.add(uid);
                        scheduleRefresh(env);
                        pumpFollowerChecks(env);
                    })
                    .addOnFailureListener(e -> {
                        env.fInFlight.remove(uid);
                        if (env.dismissed) return;
                        env.followerChecked.add(uid);   // unknown -> treated as "not a follower"; never retried in a loop
                        scheduleRefresh(env);
                        pumpFollowerChecks(env);
                    });
        }
        updateProgress(env);
    }

    // ── Refresh pipeline: snapshot (main) → compute (bg) → apply (main) ───────

    private static void scheduleRefresh(Env env) {
        if (env.refreshQueued || env.dismissed) return;
        env.refreshQueued = true;
        // v32: a busy story (many live events) recomputes less often; a small one stays instant
        env.main.postDelayed(env.refreshRunnable, env.seenAt.size() > 400 ? 250 : 60);
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
        s.followerChecked = new HashSet<>(env.followerChecked);
        s.counter = selCounter(env);
        s.exhausted = env.exhausted;
        s.barTotal = totalViewers(env.item, env.removed, s.counter);   // v34 (#8): never below what the viewer's bar shows
        s.contacts = new HashSet<>(env.contacts);
        s.closeFriends = StatusCloseFriendsManager.getLocalList(env.ctx);
        s.removed = new HashSet<>(env.removed);
        s.mode = StatusPrivacyManager.getPrivacyMode(env.ctx);
        s.except = StatusPrivacyManager.getExceptList(env.ctx);
        s.only = StatusPrivacyManager.getOnlyList(env.ctx);
        s.query = env.query; s.emoji = env.emojiFilter; s.filter = env.filter;
        s.relevance = env.relevanceSort;
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
        int removedSeen = 0;
        for (String u : s.removed) if (all.contains(u)) removedSeen++;
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
        int nReact = 0, nReply = 0, nClose = 0, nFollow = 0, nNotFollow = 0, nChecked = 0;
        for (String uid : all) {
            Profile p = s.profiles.get(uid);
            if (p == null) p = s.seeds.get(uid);
            String[] rprof = replyProf.get(uid);
            String name = p != null && p.name != null ? p.name : (rprof != null ? rprof[0] : null);
            String photo = p != null && p.photo != null && !p.photo.isEmpty() ? p.photo : (rprof != null ? rprof[1] : null);
            Long ts = s.seenAt.get(uid);
            if (ts == null) ts = at.get(uid);   // v33: reactor/replier whose seenBy page is not loaded yet -> use the reply time
            Integer rc = replyCnt.get(uid);
            Row r = new Row(uid, name, photo, ts != null ? ts : 0L, s.reactions.get(uid), replyOf.get(uid),
                    rc != null ? rc : 0, s.followers.contains(uid), s.contacts.contains(uid),
                    s.closeFriends.contains(uid), isHiddenFrom(s.mode, s.except, s.only, uid));
            every.add(r);
            if (r.reaction != null) nReact++;
            if (r.reply != null) nReply++;
            if (r.closeFriend) nClose++;
            if (s.followerChecked.contains(uid)) { nChecked++; if (r.follower) nFollow++; else nNotFollow++; }
        }

        ArrayList<Row> out = new ArrayList<>(every.size());
        for (Row r : every) {
            if (!s.query.isEmpty() && (r.nameLower == null || !r.nameLower.contains(s.query))) continue;
            if (s.emoji != null && !s.emoji.equals(r.reaction)) continue;
            boolean ok;
            switch (s.filter) {
                case F_FOLLOWERS:     ok = s.followerChecked.contains(r.uid) && r.follower; break;
                case F_NOT_FOLLOWING: ok = s.followerChecked.contains(r.uid) && !r.follower; break;
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
        // v32: total = server counter (minus viewers blocked here) when it is larger than what is loaded so far; the list itself is paged.
        res.views = Math.max(totalOf(s.counter, every.size(), removedSeen), s.barTotal);   // v34 (#8): same rule as the viewer's bar
        res.likes = likes; res.replies = replyStat;
        res.emoji = emojiOut;
        // v34 (#6): follower chips count the viewers checked so far ("12+" while checks / pages are still coming)
        res.counts = new int[]{res.views, nChecked > 0 ? nFollow : -1, nChecked > 0 ? nNotFollow : -1, nReact, nReply, nClose};
        res.followPartial = nChecked < every.size() || !s.exhausted;
        return res;
    }

    private static void apply(final Env env, Result r) {
        final Context ctx = env.ctx;
        System.arraycopy(r.counts, 0, env.chipCounts, 0, env.chipCounts.length);
        env.followPartial = r.followPartial;
        if (env.emojiFilter != null && !r.emoji.containsKey(env.emojiFilter)) {
            env.emojiFilter = null;
            scheduleRefresh(env);
        }
        env.lastEmoji = r.emoji;
        paintSortPill(env);
        env.tvTitle.setText(ctx.getString(R.string.seenby_title, r.views));
        final boolean filtered = !env.query.isEmpty() || env.filter != F_ALL || env.emojiFilter != null;
        env.tvViewers.setText(!filtered
                ? ctx.getResources().getQuantityString(R.plurals.seenby_viewers, r.views, r.views)
                : ctx.getString(R.string.seenby_viewers_of, r.rows.size(), r.views));
        if (env.liveViews != r.views) {
            env.liveViews = r.views;
            env.cardAdapter.notifyItemChanged(env.selIdx, PAYLOAD_COUNT);
        }

        final LinearLayoutManager lm = (LinearLayoutManager) env.rv.getLayoutManager();
        final boolean atTop = lm == null || lm.findFirstCompletelyVisibleItemPosition() <= 0;
        updateFooter(env);
        if (atTop) { env.topBaseTs = maxSeen(r.rows); env.newPill.setVisibility(View.GONE); }
        else if (!filtered && !env.relevanceSort) {
            int n = 0;
            for (Row x : r.rows) { if (x.seenAt > env.topBaseTs) n++; else break; }   // sorted newest first
            if (n <= 0) env.newPill.setVisibility(View.GONE);
            else {
                env.newPill.setText(ctx.getResources().getQuantityString(R.plurals.seenby_new_viewers, n, n));
                env.newPill.setVisibility(View.VISIBLE);
            }
        }
        env.adapter.submitList(r.rows, () -> { if (atTop && !env.dismissed) env.rv.scrollToPosition(0); });

        // v32: keep the pipeline moving — more pages / profiles / follower checks when the current view needs them.
        if (!env.query.isEmpty()) loadAllProfiles(env);
        pumpFollowerChecks(env);
        final boolean needsAll = !env.query.isEmpty() || env.filter == F_FOLLOWERS
                || env.filter == F_NOT_FOLLOWING || env.filter == F_CLOSE;
        if (needsAll && r.rows.size() < PAGE && env.seenAt.size() < SEARCH_LOAD_CAP) loadMore(env);
        else if (!filtered) env.rv.post(() -> maybeLoadMore(env));   // unfiltered list shorter than the screen
        final boolean checking = !env.fInFlight.isEmpty()
                || (needsAll && !env.loadFailed && (env.loadingMore || (!env.exhausted && env.seenAt.size() < SEARCH_LOAD_CAP)));
        // v34 (#4): tell the user when search / follower filters only cover the viewers loaded so far
        if (env.tvLimitHint != null) {
            final boolean capHit = needsAll && !env.exhausted && env.seenAt.size() >= SEARCH_LOAD_CAP;
            if (capHit) {
                env.tvLimitHint.setText(ctx.getString(R.string.seenby_search_limit,
                        env.seenAt.size(), Math.max(r.views, env.seenAt.size())));
                env.tvLimitHint.setVisibility(View.VISIBLE);
            } else env.tvLimitHint.setVisibility(View.GONE);
        }

        final boolean loadingFirst = !env.initialLoaded && r.rows.isEmpty();
        setSkeleton(env, loadingFirst);
        if (loadingFirst || (r.rows.isEmpty() && checking && filtered)) {
            env.emptyBox.setVisibility(View.GONE);   // still loading / searching / checking: no "no results" flash
        } else if (r.rows.isEmpty()) {
            boolean none = r.totalRows == 0;
            boolean failed = none && env.initialFailed;   // v34 (#2): first page could not be read
            env.tvEmptyTitle.setText(failed ? R.string.seenby_err_load_title
                    : none ? R.string.seenby_empty_none_title : R.string.seenby_empty_match_title);
            env.tvEmptySub.setText(failed ? R.string.seenby_err_load_sub
                    : none ? R.string.seenby_empty_none_sub : R.string.seenby_empty_match_sub);
            env.btnEmptyClear.setVisibility(none ? View.GONE : View.VISIBLE);
            env.btnEmptyRetry.setVisibility(failed ? View.VISIBLE : View.GONE);
            env.emptyBox.setVisibility(View.VISIBLE);
        } else {
            env.emptyBox.setVisibility(View.GONE);
        }
        if (env.slideInPending) startSlideIn(env);   // v37 (#2): new story's rows are painted -> now slide the list in
    }

    // ── v33 helpers ───────────────────────────────────────────────────────────

    private static long maxSeen(List<Row> rows) {
        long m = 0L;
        for (Row r : rows) if (r.seenAt > m) m = r.seenAt;
        return m;
    }

    /** Back at the top of the list: the pill is cleared and "new" is counted from here on. */
    private static void markAtTop(Env env) {
        if (env.adapter == null || env.newPill == null) return;
        env.topBaseTs = Math.max(env.topBaseTs, maxSeen(env.adapter.getCurrentList()));
        env.newPill.setVisibility(View.GONE);
    }

    private static void updateFooter(Env env) {
        if (env.footer == null) return;
        final boolean spin = env.loadingMore && env.query.isEmpty() && !env.loadFailed;
        final boolean err = env.loadFailed && !env.loadingMore;   // v34 (#2): visible "couldn't load, retry" bar
        if (env.footerSpin != null) env.footerSpin.setVisibility(spin ? View.VISIBLE : View.GONE);
        if (env.footerErr != null) env.footerErr.setVisibility(err ? View.VISIBLE : View.GONE);
        env.footer.setVisibility(spin || err ? View.VISIBLE : View.GONE);
    }

    /** Pulsing placeholder rows while the very first page is still on its way. */
    private static View buildSkeleton(Env env) {
        final Context ctx = env.ctx;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(View.GONE);
        for (int i = 0; i < 6; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9));
            View av = new View(ctx);
            GradientDrawable ovl = new GradientDrawable();
            ovl.setShape(GradientDrawable.OVAL);
            ovl.setColor(env.style.divider);
            av.setBackground(ovl);
            row.addView(av, new LinearLayout.LayoutParams(dp(ctx, RowVH.RING_DP), dp(ctx, RowVH.RING_DP)));   // skeleton circle = row ring size
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(ctx, 14), 0, 0, 0);
            LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(dp(ctx, i % 3 == 0 ? 110 : 150), dp(ctx, 12));
            l1.bottomMargin = dp(ctx, 7);
            col.addView(skelBar(env), l1);
            col.addView(skelBar(env), new LinearLayout.LayoutParams(dp(ctx, 80), dp(ctx, 10)));
            row.addView(col);
            box.addView(row);
        }
        env.skeleton = box;
        return box;
    }

    private static View skelBar(Env env) {
        View v = new View(env.ctx);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(env.ctx, 6));
        g.setColor(env.style.divider);
        v.setBackground(g);
        return v;
    }

    private static void setSkeleton(Env env, boolean show) {
        if (env.skeleton == null) return;
        if (show) {
            if (env.skeleton.getVisibility() == View.VISIBLE) return;
            env.skeleton.setVisibility(View.VISIBLE);
            env.skelAnim = android.animation.ObjectAnimator.ofFloat(env.skeleton, "alpha", 1f, 0.45f);
            env.skelAnim.setDuration(750);
            env.skelAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            env.skelAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            env.skelAnim.start();
        } else if (env.skeleton.getVisibility() != View.GONE) {
            if (env.skelAnim != null) env.skelAnim.cancel();
            env.skeleton.setAlpha(1f);
            env.skeleton.setVisibility(View.GONE);
        }
    }

    /** A reactor / replier whose seenBy page is not loaded yet: read just seenBy/{uid} (tiny) so the row gets its real time and place. */
    private static void requestSeenTs(Env env, String uid) {
        if (env.dismissed || env.seenAt.containsKey(uid) || !env.tsTried.add(uid)) return;
        env.tsQueue.addLast(uid);
        pumpSeenTs(env);
    }

    private static void pumpSeenTs(final Env env) {
        while (!env.dismissed && env.seenRef != null && env.tsInFlight.size() < 4 && !env.tsQueue.isEmpty()) {
            final String uid = env.tsQueue.pollLast();   // latest-bound first
            env.tsInFlight.add(uid);
            final int pg = env.pageGen;
            env.seenRef.child(uid).get()
                    .addOnSuccessListener(snap -> {
                        env.tsInFlight.remove(uid);
                        if (env.dismissed || pg != env.pageGen) return;
                        Object v = snap.getValue();
                        // NOT noteCursor: this is a point lookup, it must not move the paging cursor.
                        if (v instanceof Number) { env.seenAt.put(uid, ((Number) v).longValue()); scheduleRefresh(env); }
                        pumpSeenTs(env);
                    })
                    .addOnFailureListener(e -> { env.tsInFlight.remove(uid); if (!env.dismissed) pumpSeenTs(env); });
        }
    }

    // ── v33: profile cache that survives app restarts (stale-while-revalidate) ──

    private static void loadProfilesFromDisk(final Env env, final Context app) {
        if (DISK_LOADED) return;
        BG.execute(() -> {
            if (!DISK_LOADED) {
                try {
                    String raw = app.getSharedPreferences(PROF_PREFS, Context.MODE_PRIVATE).getString(PROF_KEY, null);
                    if (raw != null) for (String line : raw.split("\n")) {
                        String[] f = line.split("\t", -1);
                        if (f.length < 4 || f[0].isEmpty() || PROFILES.get(f[0]) != null) continue;   // fresher in-memory copy wins
                        long at;
                        try { at = Long.parseLong(f[3]); } catch (NumberFormatException e) { continue; }
                        PROFILES.put(f[0], new Profile(f[1], f[2].isEmpty() ? null : f[2], at));
                    }
                } catch (Exception ignored) {}
                DISK_LOADED = true;
            }
            env.main.post(() -> { if (!env.dismissed) scheduleRefresh(env); });
        });
    }

    private static void saveProfilesToDisk(final Context app) {
        BG.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (Map.Entry<String, Profile> e : PROFILES.snapshot().entrySet()) {
                    Profile p = e.getValue();
                    if (p == null || p.at <= 0L) continue;
                    if (n++ >= PROF_DISK_MAX) break;
                    sb.append(clean(e.getKey())).append('\t').append(clean(p.name)).append('\t')
                      .append(clean(p.photo)).append('\t').append(p.at).append('\n');
                }
                app.getSharedPreferences(PROF_PREFS, Context.MODE_PRIVATE).edit().putString(PROF_KEY, sb.toString()).apply();
            } catch (Exception ignored) {}
        });
    }

    private static String clean(String x) {
        return x == null ? "" : x.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
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
        final int cap = env.query.isEmpty() ? MAX_INFLIGHT : SEARCH_INFLIGHT;
        while (!env.dismissed && env.inFlight.size() < cap && !env.pending.isEmpty()) {
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
        if (p != null) {
            env.profiles.put(uid, p); scheduleRefresh(env);
            env.profilesDirty = true;
            if (!env.saveQueued) {
                env.saveQueued = true;
                env.main.postDelayed(() -> { env.saveQueued = false; env.profilesDirty = false; saveProfilesToDisk(env.ctx.getApplicationContext()); }, 4000L);
            }
        }
        pumpProfiles(env);
    }

    /** Search needs names: pull every missing profile in (throttled, visible rows still first). */
    private static void loadAllProfiles(Env env) {
        for (String uid : universe(env))
            if (needsFetch(env, uid) && env.queued.add(uid)) env.pending.addFirst(uid);
        pumpProfiles(env);
    }

    private static void updateProgress(Env env) {
        boolean busy = (!env.query.isEmpty() && (!env.inFlight.isEmpty() || !env.pending.isEmpty() || env.loadingMore))
                || (!env.fInFlight.isEmpty() && (env.filter == F_FOLLOWERS || env.filter == F_NOT_FOLLOWING));
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
        env.clearBtn.setBackground(rippleBorderless(env.style.primary));
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
        env.btnEmptyRetry = chip(env, ctx.getString(R.string.seenby_retry));   // v34 (#2)
        paintChip(env, env.btnEmptyRetry, true);
        env.btnEmptyRetry.setVisibility(View.GONE);
        env.btnEmptyRetry.setOnClickListener(v -> retryInitial(env));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32));
        rlp.topMargin = dp(ctx, 12);
        box.addView(env.btnEmptyRetry, rlp);
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
        static final int RING_DP = 52, AVATAR_DP = 44;   // v35: avatar = ring - 8dp (1.5dp ring stroke + ~2.5dp gap each side); was 36/29
        final Env env;
        final CircleImageView avatar;
        final ImageView ring, btnMsg, btnMore;
        final LinearLayout line;   // v34: carries the row's spoken description
        final TextView tvName, tvMeta, tvReply, tvReact;
        final Drawable replyIcon;
        Row row;
        String boundAvatarKey;   // photo#version currently shown in avatar (null = needs a load)

        RowVH(Env env) {
            super(new LinearLayout(env.ctx));
            this.env = env;
            final Context ctx = env.ctx;
            LinearLayout box = (LinearLayout) itemView;
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(ctx, 16), dp(ctx, 7), dp(ctx, 8), dp(ctx, 7));
            line.setBackground(ripple(env.style.primary));

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
            tvReply.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);   // the row + message button already cover it
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
            t.setBackground(rippleBorderless(tint));
            return t;
        }

        void bind(Row r) {
            row = r;
            env.bound.add(r.uid);
            tvName.setText(r.name != null ? r.name : env.ctx.getString(R.string.seenby_name_loading));
            bindMeta(r);
            final String nm = r.name != null ? r.name : env.ctx.getString(R.string.seenby_someone);
            btnMsg.setContentDescription(env.ctx.getString(R.string.seenby_cd_message_to, nm));
            btnMore.setContentDescription(env.ctx.getString(R.string.seenby_cd_more_for, nm));
            ring.setContentDescription(env.ctx.getString(R.string.seenby_cd_story_of, nm));
            if (r.reply == null) tvReply.setVisibility(View.GONE);
            else {
                tvReply.setVisibility(View.VISIBLE);
                tvReply.setText(r.replyCount > 1
                        ? env.ctx.getString(R.string.seenby_reply_more, r.reply, r.replyCount - 1) : r.reply);
            }
            if (r.reaction == null) tvReact.setVisibility(View.GONE);
            else { tvReact.setVisibility(View.VISIBLE); tvReact.setText(r.reaction); }
            // v717: same photo + same avatar version already in this holder (reaction/reply/name rebind) -> no URL work, no Glide request.
            final long av = StatusSheetAvatar.versionOf(env.ctx, r.uid);
            final String avKey = (r.photo == null || r.photo.isEmpty()) ? null : r.photo + "#" + av;
            if (avKey == null || !avKey.equals(boundAvatarKey)) {
                boundAvatarKey = avKey;
                StatusSheetAvatar.load(env.ctx, avatar, r.photo, R.drawable.ic_person, StatusSheetAvatar.SHEET_ROW_TIER, av);   // v35: 44dp avatar -> SMALL (48dp) so it stays sharp
            }
            // Gradient = unseen story, gray = all seen, hidden = none; tap opens the story viewer.
            com.callx.app.utils.StoryRingApplier.applyWithClick(env.ctx, ring, r.uid, r.name);
            requestProfile(env, r.uid);      // lazy: only rows that are really bound fetch their profile
            if (r.seenAt == 0L && (r.reaction != null || r.reply != null)) requestSeenTs(env, r.uid);
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
            updateDescription(r);
        }

        /** v34 (#9): one spoken line per row — name, time, badges, reaction, reply. */
        private void updateDescription(Row r) {
            final Context ctx = env.ctx;
            StringBuilder d = new StringBuilder(r.name != null ? r.name : ctx.getString(R.string.seenby_someone));
            if (r.seenAt > 0) d.append(", ").append(formatTime(env, r.seenAt));
            if (r.closeFriend) d.append(", ").append(ctx.getString(R.string.seenby_cd_close_friend));
            if (r.hidden) d.append(", ").append(ctx.getString(R.string.seenby_badge_hidden));
            if (r.reaction != null) d.append(", ").append(ctx.getString(R.string.seenby_cd_reacted, r.reaction));
            if (r.reply != null) d.append(", ").append(ctx.getString(R.string.seenby_cd_replied, r.reply));
            line.setContentDescription(d);
        }

        void release() {
            boundAvatarKey = null;
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
     * Context whose resources resolve in the SAME mode as the sheet (v34: the sheet follows the system theme now), so the shared rounded
     * dialog (bg_rounded_alert_dialog -> @color/dialog_surface) matches it.
     */
    private static Context dialogCtx(Context ctx, boolean dark) {
        try {
            android.view.ContextThemeWrapper w = new android.view.ContextThemeWrapper(ctx,
                    dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
            android.content.res.Configuration c = new android.content.res.Configuration(ctx.getResources().getConfiguration());
            c.uiMode = (c.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    | (dark ? android.content.res.Configuration.UI_MODE_NIGHT_YES : android.content.res.Configuration.UI_MODE_NIGHT_NO);
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
        final android.app.AlertDialog hideDlg = new android.app.AlertDialog.Builder(dialogCtx(env.ctx, env.style.dark))
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
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(dialogCtx(env.ctx, env.style.dark))
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

    /** v34 (#10): follows the system / app theme (dark = Instagram dark, light = Instagram light). */
    private static Style resolveStyle(Context ctx) {
        Style s = new Style();
        int night = ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        s.dark = night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (s.dark) {
            s.bg        = 0xFF0F0F0F;
            s.surface   = 0xFF262626;   // popup menu card (opaque)
            s.pill      = 0xFF262626;   // "Sort by" pill
            s.primary   = 0xFFFFFFFF;
            s.secondary = 0xFFA8A8A8;
            s.divider   = 0x26FFFFFF;
            s.onPrimary = 0xFF000000;
            s.accent    = 0xFF3DDC84;   // close-friend green / ✓
            s.segDim    = 0x59FFFFFF;
        } else {
            s.bg        = 0xFFFFFFFF;
            s.surface   = 0xFFF0F0F0;
            s.pill      = 0xFFEFEFEF;
            s.primary   = 0xFF111111;
            s.secondary = 0xFF6B6B6B;
            s.divider   = 0x1F000000;
            s.onPrimary = 0xFFFFFFFF;
            s.accent    = 0xFF1E9E55;   // darker green: readable on white
            s.segDim    = 0x33000000;
        }
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

    /** v34: ripple coloured from the sheet palette (the host activity theme is always dark, so the theme ripple was invisible on the light sheet). */
    private static Drawable ripple(int base) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((base & 0x00FFFFFF) | 0x24000000), null,
                new android.graphics.drawable.ColorDrawable(0xFF000000));   // mask = bounded ripple
    }
    private static Drawable rippleBorderless(int base) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((base & 0x00FFFFFF) | 0x24000000), null, null);
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
