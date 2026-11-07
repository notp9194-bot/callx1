package com.callx.app.interactions;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusCloseFriendsManager;
import com.callx.app.utils.StatusPrivacyManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DataSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * StatusSeenByBottomSheet v28 — Instagram-style "story viewers" screen.
 *
 *  - Stats strip: views / likes / replies / shares (+ average watch time).
 *  - Search bar (name), filter chips (All · Followers · Not following · Reactions · Replies · Close friends)
 *    and a Sort toggle (Recent ⇄ Relevance: close friends, repliers and reactors first).
 *  - Every row: story ring avatar, name, "Seen <time>", Close-friend star + Follower / Not-following tag,
 *    the viewer's reply snippet, their reaction, a one-tap Message button and a "⋮" menu
 *    (Message, View profile, Add/Remove close friend, Hide my stories from X, Block X).
 *  - Same perf design as v27: RecyclerView + DiffUtil + stable ids, parsing / filtering / sorting on one bg
 *    thread with one list submit per change, process-wide profile cache.
 */
public class StatusSeenByBottomSheet {

    /** Single bg thread (ordered): parsing, SimpleDateFormat (not thread-safe -> bg only), sorting. */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "status-seenby-bg"); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });

    /** uid -> {name, photo}. Survives sheet re-opens; always refreshed from Firebase afterwards. */
    private static final LruCache<String, String[]> PROFILES = new LruCache<>(300);

    private static final int F_ALL = 0, F_FOLLOWERS = 1, F_NOT_FOLLOWING = 2, F_REACTIONS = 3, F_REPLIES = 4, F_CLOSE = 5;
    private static final String[] FILTER_LABELS = {"All", "Followers", "Not following", "Reactions", "Replies", "Close friends"};

    /** Immutable row model (DiffUtil compares by value). */
    static final class Row {
        final String uid, name, photo, time, reaction, reply;
        final long seenAt;
        final boolean follower, closeFriend;
        Row(String uid, String name, String photo, long seenAt, String time, String reaction,
            String reply, boolean follower, boolean closeFriend) {
            this.uid = uid; this.name = name; this.photo = photo;
            this.seenAt = seenAt; this.time = time; this.reaction = reaction;
            this.reply = reply; this.follower = follower; this.closeFriend = closeFriend;
        }
        static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
        boolean sameContent(Row o) {
            return seenAt == o.seenAt && eq(name, o.name) && eq(photo, o.photo)
                    && eq(time, o.time) && eq(reaction, o.reaction) && eq(reply, o.reply)
                    && follower == o.follower && closeFriend == o.closeFriend;
        }
        /** Higher = nearer the top in "Relevance" sort. */
        int score() {
            return (closeFriend ? 4 : 0) + (reply != null ? 3 : 0) + (reaction != null ? 2 : 0) + (follower ? 1 : 0);
        }
    }

    /** Resolved ONCE per sheet. */
    private static final class Style { int primary, secondary, divider, onPrimary, accent; }

    private static final class Env {
        Context ctx; Style style; StatusItem item; BottomSheetDialog sheet;
        String myUid;
        final Handler main = new Handler(Looper.getMainLooper());
        final HashMap<String, Row> rows = new HashMap<>();
        final HashMap<String, String[]> profiles = new HashMap<>();   // uid -> {name, photo} (loaded so far)
        final HashMap<String, String> replyOf = new HashMap<>();      // uid -> latest reply text
        final Set<String> followers = new HashSet<>();
        final Set<String> removed = new HashSet<>();                  // blocked from inside the sheet
        boolean followersLoaded;
        final SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault()); // bg thread only
        boolean dismissed, flushQueued; int gen, loaded, total;
        RowsAdapter adapter; ProgressBar progress; TextView tvCount, tvEmpty;
        // query state (main thread)
        String query = ""; int filter = F_ALL; boolean relevanceSort = false;
        final List<TextView> chips = new ArrayList<>();
        TextView sortChip;
        Runnable searchRunnable;
    }

    /** Legacy overload — no dismiss callback (used when caller doesn't need resume). */
    public static void show(Context ctx, StatusItem item) {
        show(ctx, item, null);
    }

    public static void show(Context ctx, StatusItem item, Runnable onDismiss) {
        if (item.seenBy == null || item.seenBy.isEmpty()) {
            Toast.makeText(ctx, "No viewers yet", Toast.LENGTH_SHORT).show();
            if (onDismiss != null) onDismiss.run();
            return;
        }
        final Env env = new Env();
        env.ctx = ctx; env.item = item; env.style = resolveStyle(ctx);
        env.myUid = FirebaseUtils.getCurrentUid();
        final List<String> uids = new ArrayList<>(item.seenBy.keySet());
        env.total = uids.size();
        if (item.replies != null) { // latest reply per viewer
            Map<String, Long> at = new HashMap<>();
            for (StatusItem.ReplyPreview rp : item.replies.values()) {
                if (rp == null || rp.uid == null || rp.text == null || rp.text.isEmpty()) continue;
                long ts = rp.timestamp != null ? rp.timestamp : 0L;
                Long prev = at.get(rp.uid);
                if (prev == null || ts >= prev) { at.put(rp.uid, ts); env.replyOf.put(rp.uid, rp.text); }
            }
        }

        final BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        env.sheet = sheet;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8));

        // ── Title ──
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, dp(ctx, 6), 0, dp(ctx, 8));
        TextView header = new TextView(ctx);
        header.setText("Viewers");
        header.setTextSize(18);
        header.setTextColor(env.style.primary);
        header.setTypeface(null, Typeface.BOLD);
        titleRow.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        env.tvCount = new TextView(ctx);
        env.tvCount.setTextSize(13);
        env.tvCount.setTextColor(env.style.secondary);
        env.tvCount.setText(String.valueOf(item.seenBy.size()));
        titleRow.addView(env.tvCount);
        root.addView(titleRow);

        // ── Stats strip (Instagram insights row) ──
        root.addView(buildStats(env));

        // ── Emoji reaction summary ──
        if (item.reactions != null && !item.reactions.isEmpty()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String e : item.reactions.values()) counts.merge(e, 1, Integer::sum);
            LinearLayout strip = new LinearLayout(ctx);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(0, 0, 0, dp(ctx, 10));
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                TextView chip = new TextView(ctx);
                chip.setText(e.getKey() + " " + e.getValue());
                chip.setTextSize(14);
                chip.setTextColor(env.style.primary);
                chip.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(ctx, 16));
                bg.setColor(env.style.divider);
                chip.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(dp(ctx, 8));
                strip.addView(chip, lp);
            }
            HorizontalScrollView hs = new HorizontalScrollView(ctx);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(strip);
            root.addView(hs);
        }

        // ── Search ──
        final EditText search = new EditText(ctx);
        search.setHint("Search viewers");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(env.style.primary);
        search.setHintTextColor(env.style.secondary);
        search.setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(dp(ctx, 12));
        sbg.setColor(env.style.divider);
        search.setBackground(sbg);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                env.query = s == null ? "" : s.toString().trim().toLowerCase(Locale.getDefault());
                if (env.searchRunnable != null) env.main.removeCallbacks(env.searchRunnable);
                env.searchRunnable = () -> { if (!env.dismissed) flush(env); };
                env.main.postDelayed(env.searchRunnable, 150);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 40));
        slp.bottomMargin = dp(ctx, 10);
        root.addView(search, slp);

        // ── Filter chips + sort ──
        root.addView(buildChips(env));

        env.progress = new ProgressBar(ctx);
        root.addView(env.progress);

        env.tvEmpty = new TextView(ctx);
        env.tvEmpty.setText("No viewers match");
        env.tvEmpty.setTextColor(env.style.secondary);
        env.tvEmpty.setGravity(Gravity.CENTER);
        env.tvEmpty.setPadding(0, dp(ctx, 28), 0, dp(ctx, 28));
        env.tvEmpty.setVisibility(View.GONE);
        root.addView(env.tvEmpty);

        // Height-capped RecyclerView (same cap logic as the replies sheet).
        final RecyclerView rv = new RecyclerView(ctx) {
            @Override protected void onMeasure(int w, int h) {
                int screen = getResources().getDisplayMetrics().heightPixels;
                int rootH = getRootView() != null ? getRootView().getHeight() : screen;
                if (rootH <= 0) rootH = screen;
                int max = Math.max(dp(ctx, 160), Math.min((int) (screen * 0.55f), rootH - dp(ctx, 260)));
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        rv.setLayoutManager(new LinearLayoutManager(ctx));
        rv.setItemAnimator(null);
        rv.setHasFixedSize(false);
        rv.setItemViewCacheSize(6);
        env.adapter = new RowsAdapter(env);
        env.adapter.setHasStableIds(true);
        rv.setAdapter(env.adapter);
        rv.setClipToPadding(false);
        rv.setPadding(0, 0, 0, dp(ctx, 16));
        root.addView(rv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sheet.setContentView(root);

        sheet.setOnDismissListener(d -> {
            env.dismissed = true;
            env.main.removeCallbacksAndMessages(null);
            if (onDismiss != null) onDismiss.run();
        });
        sheet.setOnShowListener(d -> {
            View parentView = (View) root.getParent();
            if (parentView != null) {
                BottomSheetBehavior<View> b = BottomSheetBehavior.from(parentView);
                b.setSkipCollapsed(true);
                b.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        sheet.show();

        // 1) Paint instantly from the profile cache, 2) then refresh every viewer from Firebase.
        for (String uid : uids) {
            String[] cached = PROFILES.get(uid);
            if (cached != null) { env.profiles.put(uid, cached); env.rows.put(uid, makeRow(env, uid, cached[0], cached[1])); }
        }
        if (!env.rows.isEmpty()) flush(env);

        // Who follows me (drives Follower / Not following tags + filters).
        if (env.myUid != null) {
            FirebaseUtils.getReelFollowersRef(env.myUid).get().addOnSuccessListener(snap -> {
                if (env.dismissed) return;
                for (DataSnapshot c : snap.getChildren()) if (c.getKey() != null) env.followers.add(c.getKey());
                env.followersLoaded = true;
                rebuildRows(env);
            }).addOnFailureListener(e -> { env.followersLoaded = true; });
        }

        for (final String uid : uids) {
            FirebaseUtils.db().getReference("users").child(uid).get()
                .addOnSuccessListener(snap -> {
                    if (env.dismissed) return;
                    onProfile(env, uid, snap);
                })
                .addOnFailureListener(e -> {
                    if (env.dismissed) return;
                    markLoaded(env);
                });
        }
    }

    // ── Header widgets ────────────────────────────────────────────────────────

    private static View buildStats(Env env) {
        final Context ctx = env.ctx;
        StatusItem it = env.item;
        int likes = 0;
        if (it.reactions != null) for (String e : it.reactions.values()) if (e != null && e.startsWith("\u2764")) likes++;
        int replies = it.replies != null ? it.replies.size() : 0;
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 4), 0, dp(ctx, 4));
        String[][] cells = {
                {"\uD83D\uDC41", String.valueOf(it.seenBy.size()), "Views"},
                {"\u2764\uFE0F", String.valueOf(likes), "Likes"},
                {"\uD83D\uDCAC", String.valueOf(replies), "Replies"},
                {"\u2197", String.valueOf(Math.max(0, it.forwardCount)), "Shares"},
        };
        for (String[] c : cells) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView n = new TextView(ctx);
            n.setText(c[0] + " " + c[1]);
            n.setTextSize(16);
            n.setTextColor(env.style.primary);
            n.setTypeface(null, Typeface.BOLD);
            TextView l = new TextView(ctx);
            l.setText(c[2]);
            l.setTextSize(11);
            l.setTextColor(env.style.secondary);
            cell.addView(n);
            cell.addView(l);
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        col.addView(row);
        double avg = it.getAvgViewDurationSec();
        if (avg > 0) {
            TextView t = new TextView(ctx);
            t.setText(String.format(Locale.getDefault(), "Avg. watch time %.1fs", avg));
            t.setTextSize(12);
            t.setTextColor(env.style.secondary);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setPadding(0, 0, 0, dp(ctx, 6));
            col.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 14));
        bg.setColor(env.style.divider);
        col.setBackground(bg);
        col.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 10);
        col.setLayoutParams(lp);
        return col;
    }

    private static View buildChips(Env env) {
        final Context ctx = env.ctx;
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, 0, 0, dp(ctx, 6));
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            final int f = i;
            TextView chip = chip(env, FILTER_LABELS[i]);
            chip.setOnClickListener(v -> { env.filter = f; paintChips(env); flush(env); });
            env.chips.add(chip);
            line.addView(chip, chipLp(ctx));
        }
        env.sortChip = chip(env, "Sort: Recent");
        env.sortChip.setOnClickListener(v -> {
            env.relevanceSort = !env.relevanceSort;
            paintChips(env);
            flush(env);
        });
        line.addView(env.sortChip, chipLp(ctx));
        paintChips(env);
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(line);
        return hs;
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

    private static void paintChips(Env env) {
        for (int i = 0; i < env.chips.size(); i++) paintChip(env, env.chips.get(i), i == env.filter);
        if (env.sortChip != null) {
            env.sortChip.setText(env.relevanceSort ? "Sort: Relevance" : "Sort: Recent");
            paintChip(env, env.sortChip, env.relevanceSort);
        }
    }

    private static void paintChip(Env env, TextView t, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(env.ctx, 16));
        bg.setColor(on ? env.style.primary : env.style.divider);
        t.setBackground(bg);
        t.setTextColor(on ? env.style.onPrimary : env.style.primary);
        t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private static void onProfile(Env env, String uid, DataSnapshot snap) {
        // DataSnapshot is immutable -> safe on the bg thread; result applied on main, in order.
        BG.execute(() -> {
            String name  = snap.child("name").getValue(String.class);
            // Full photoUrl first (buildResponsive builds the TINY variant from it) -> shares the
            // cache key with the reply sheet / reels comments; thumbUrl only as fallback.
            String photo = snap.child("photoUrl").getValue(String.class);
            if (photo == null || photo.isEmpty()) photo = snap.child("thumbUrl").getValue(String.class);
            final String fname = name != null ? name : uid;
            final String fphoto = photo;
            PROFILES.put(uid, new String[]{fname, fphoto});
            final Row r = makeRow(env, uid, fname, fphoto); // formats time -> bg thread only
            env.main.post(() -> {
                if (env.dismissed) return;
                env.profiles.put(uid, new String[]{fname, fphoto});
                env.rows.put(uid, r);
                markLoaded(env);
                scheduleFlush(env);
            });
        });
    }

    /** Rebuilds every row (follower / close-friend flags changed) from the profiles loaded so far. */
    private static void rebuildRows(Env env) {
        final HashMap<String, String[]> prof = new HashMap<>(env.profiles);
        BG.execute(() -> {
            final HashMap<String, Row> fresh = new HashMap<>();
            for (Map.Entry<String, String[]> e : prof.entrySet())
                fresh.put(e.getKey(), makeRow(env, e.getKey(), e.getValue()[0], e.getValue()[1]));
            env.main.post(() -> {
                if (env.dismissed) return;
                env.rows.clear();
                env.rows.putAll(fresh);
                scheduleFlush(env);
            });
        });
    }

    /** Safe on bg (SimpleDateFormat) and on main only before any bg work is queued (cache seed). */
    private static Row makeRow(Env env, String uid, String name, String photo) {
        Long ts = env.item.seenBy.get(uid);
        long seenAt = ts != null ? ts : 0L;
        String time;
        synchronized (env.fmt) { time = seenAt > 0 ? env.fmt.format(new Date(seenAt)) : ""; }
        return new Row(uid, name, photo, seenAt, time, env.item.getReaction(uid), env.replyOf.get(uid),
                env.followers.contains(uid), StatusCloseFriendsManager.isCloseFriend(env.ctx, uid));
    }

    private static void markLoaded(Env env) {
        env.loaded++;
        if (env.loaded >= env.total) env.progress.setVisibility(View.GONE);
    }

    /** Coalesces the burst of per-viewer results into one submit per frame. */
    private static void scheduleFlush(Env env) {
        if (env.flushQueued) return;
        env.flushQueued = true;
        env.main.post(() -> { env.flushQueued = false; if (!env.dismissed) flush(env); });
    }

    /** Applies search + filter + sort on the bg thread and submits ONE list. */
    private static void flush(Env env) {
        final List<Row> snapshot = new ArrayList<>(env.rows.values());
        final String q = env.query;
        final int filter = env.filter;
        final boolean relevance = env.relevanceSort;
        final Set<String> removed = new HashSet<>(env.removed);
        final int gen = ++env.gen;
        BG.execute(() -> {
            List<Row> out = new ArrayList<>(snapshot.size());
            for (Row r : snapshot) {
                if (removed.contains(r.uid)) continue;
                if (!q.isEmpty() && (r.name == null || !r.name.toLowerCase(Locale.getDefault()).contains(q))) continue;
                boolean ok;
                switch (filter) {
                    case F_FOLLOWERS:     ok = r.follower; break;
                    case F_NOT_FOLLOWING: ok = !r.follower; break;
                    case F_REACTIONS:     ok = r.reaction != null; break;
                    case F_REPLIES:       ok = r.reply != null; break;
                    case F_CLOSE:         ok = r.closeFriend; break;
                    default:              ok = true;
                }
                if (ok) out.add(r);
            }
            Collections.sort(out, (a, b) -> {
                if (relevance) {
                    int s = Integer.compare(b.score(), a.score());
                    if (s != 0) return s;
                }
                int c = Long.compare(b.seenAt, a.seenAt); // most recent viewer first
                return c != 0 ? c : a.uid.compareTo(b.uid);
            });
            final int shown = out.size();
            env.main.post(() -> {
                if (env.dismissed || gen != env.gen) return;
                env.adapter.submitList(out);
                env.tvEmpty.setVisibility(shown == 0 && env.loaded > 0 ? View.VISIBLE : View.GONE);
                int total = env.item.seenBy.size() - env.removed.size();
                env.tvCount.setText(shown == total ? String.valueOf(total) : shown + " of " + total);
            });
        });
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
        @Override public void onViewRecycled(@NonNull RowVH h) { h.release(); }
    }

    /** One viewer row. Views built ONCE per holder. */
    private static final class RowVH extends RecyclerView.ViewHolder {
        final Env env;
        final CircleImageView avatar;
        final ImageView ring;
        final TextView tvName, tvBadge, tvTime, tvReply, tvReact, btnMsg, btnMore;
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
            line.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));

            // Story ring (same StoryRingApplier every other avatar list uses) around the 32dp avatar.
            FrameLayout avatarBox = new FrameLayout(ctx);
            int ringSz = dp(ctx, StatusSheetAvatar.SIZE_DP + 10);
            ring = new ImageView(ctx);
            ring.setVisibility(View.GONE);
            avatarBox.addView(ring, new FrameLayout.LayoutParams(ringSz, ringSz));
            avatar = new CircleImageView(ctx);
            int size = dp(ctx, StatusSheetAvatar.SIZE_DP + 4);
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(size, size);
            alp.gravity = Gravity.CENTER;
            avatarBox.addView(avatar, alp);
            line.addView(avatarBox, new LinearLayout.LayoutParams(ringSz, ringSz));

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(ctx, 12), 0, dp(ctx, 4), 0);
            line.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout nameLine = new LinearLayout(ctx);
            nameLine.setOrientation(LinearLayout.HORIZONTAL);
            nameLine.setGravity(Gravity.CENTER_VERTICAL);
            tvName = new TextView(ctx);
            tvName.setTextSize(15);
            tvName.setTextColor(env.style.primary);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setSingleLine(true);
            tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nameLine.addView(tvName, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            tvBadge = new TextView(ctx);
            tvBadge.setTextSize(12);
            tvBadge.setPadding(dp(ctx, 6), 0, 0, 0);
            nameLine.addView(tvBadge);
            info.addView(nameLine);

            tvTime = new TextView(ctx);
            tvTime.setTextSize(12);
            tvTime.setTextColor(env.style.secondary);
            tvTime.setSingleLine(true);
            tvTime.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(tvTime);

            tvReply = new TextView(ctx);
            tvReply.setTextSize(12);
            tvReply.setTextColor(env.style.primary);
            tvReply.setTypeface(null, Typeface.ITALIC);
            tvReply.setMaxLines(2);
            tvReply.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(tvReply);

            tvReact = new TextView(ctx);
            tvReact.setTextSize(22);
            tvReact.setPadding(dp(ctx, 6), 0, dp(ctx, 4), 0);
            line.addView(tvReact);

            btnMsg = iconBtn(ctx, "\uD83D\uDCAC", "Message");
            btnMsg.setOnClickListener(v -> { if (row != null) openChat(env, row); });
            line.addView(btnMsg, new LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 38)));

            btnMore = iconBtn(ctx, "\u22EE", "More");
            btnMore.setTextSize(20);
            btnMore.setTypeface(null, Typeface.BOLD);
            btnMore.setOnClickListener(v -> { if (row != null) showMenu(env, v, row); });
            line.addView(btnMore, new LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 38)));

            line.setOnClickListener(v -> { if (row != null) openProfile(env, row); }); // tap row → profile
            box.addView(line);
            View divider = new View(ctx);
            divider.setBackgroundColor(env.style.divider);
            box.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }

        private static TextView iconBtn(Context ctx, String glyph, String desc) {
            TextView t = new TextView(ctx);
            t.setText(glyph);
            t.setTextSize(18);
            t.setGravity(Gravity.CENTER);
            t.setContentDescription(desc);
            t.setClickable(true);
            return t;
        }

        void bind(Row r) {
            row = r;
            tvName.setText(r.name);
            // badge: ⭐ Close friend wins over Follower / Not following
            if (r.closeFriend) { tvBadge.setText("\u2B50 Close friend"); tvBadge.setTextColor(0xFF2E9E5B); tvBadge.setVisibility(View.VISIBLE); }
            else if (env.followersLoaded) {
                tvBadge.setText(r.follower ? "Follower" : "Not following");
                tvBadge.setTextColor(env.style.secondary);
                tvBadge.setVisibility(View.VISIBLE);
            } else tvBadge.setVisibility(View.GONE);
            if (r.time == null || r.time.isEmpty()) tvTime.setVisibility(View.GONE);
            else { tvTime.setVisibility(View.VISIBLE); tvTime.setText("Seen " + r.time); }
            if (r.reply == null) tvReply.setVisibility(View.GONE);
            else { tvReply.setVisibility(View.VISIBLE); tvReply.setText("\uD83D\uDCAC " + r.reply); }
            if (r.reaction == null) tvReact.setVisibility(View.GONE);
            else { tvReact.setVisibility(View.VISIBLE); tvReact.setText(r.reaction); }
            StatusSheetAvatar.load(env.ctx, avatar, r.photo, com.callx.app.status.R.drawable.ic_person);
            // Gradient = unseen story, gray = all seen, hidden = none; tap opens the story viewer.
            com.callx.app.utils.StoryRingApplier.applyWithClick(env.ctx, ring, r.uid, r.name);
        }

        void release() {
            try { com.bumptech.glide.Glide.with(env.ctx).clear(avatar); } catch (Exception ignored) {}
            ring.setOnClickListener(null);
            row = null;
        }
    }

    // ── Per-viewer actions ────────────────────────────────────────────────────

    private static void showMenu(final Env env, View anchor, final Row r) {
        PopupMenu m = new PopupMenu(env.ctx, anchor);
        m.getMenu().add(0, 1, 0, "Message");
        m.getMenu().add(0, 2, 1, "View profile");
        m.getMenu().add(0, 3, 2, StatusCloseFriendsManager.isCloseFriend(env.ctx, r.uid)
                ? "Remove from close friends" : "Add to close friends");
        m.getMenu().add(0, 4, 3, "Hide my stories from " + r.name);
        m.getMenu().add(0, 5, 4, "Block " + r.name);
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openChat(env, r); return true;
                case 2: openProfile(env, r); return true;
                case 3:
                    StatusCloseFriendsManager.toggle(env.ctx, env.myUid, r.uid);
                    rebuildRows(env);
                    return true;
                case 4: confirmHide(env, r); return true;
                case 5: confirmBlock(env, r); return true;
                default: return false;
            }
        });
        m.show();
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
            Toast.makeText(env.ctx, "Couldn\u2019t open chat", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(env.ctx, "Couldn\u2019t open profile", Toast.LENGTH_SHORT).show();
        }
    }

    /** Instagram "Hide story from": future stories stop reaching this person (uses the Status privacy lists). */
    private static void confirmHide(final Env env, final Row r) {
        new android.app.AlertDialog.Builder(env.ctx)
                .setTitle("Hide your stories from " + r.name + "?")
                .setMessage("They won\u2019t see your future stories. They won\u2019t be told.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Hide", (d, w) -> {
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
                        Toast.makeText(env.ctx, "Hidden from future stories", Toast.LENGTH_SHORT).show();
                        rebuildRows(env);
                    } catch (Exception e) {
                        Toast.makeText(env.ctx, "Couldn\u2019t update privacy", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private static void confirmBlock(final Env env, final Row r) {
        new android.app.AlertDialog.Builder(env.ctx)
                .setTitle("Block " + r.name + "?")
                .setMessage("They won\u2019t be able to message or call you, and won\u2019t see your stories.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Block", (d, w) -> {
                    if (env.myUid == null) return;
                    FirebaseUtils.getBlocksRef(env.myUid).child(r.uid).setValue(true)
                            .addOnSuccessListener(u -> {
                                env.removed.add(r.uid);
                                Toast.makeText(env.ctx, r.name + " blocked", Toast.LENGTH_SHORT).show();
                                flush(env);
                            })
                            .addOnFailureListener(e -> Toast.makeText(env.ctx,
                                    "Couldn\u2019t block. Try again.", Toast.LENGTH_SHORT).show());
                }).show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Style resolveStyle(Context ctx) {
        Style s = new Style();
        android.content.res.TypedArray a = ctx.getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.textColorPrimary, android.R.attr.textColorSecondary});
        try {
            s.primary   = a.getColor(0, Color.BLACK);
            s.secondary = a.getColor(1, Color.GRAY);
        } finally { a.recycle(); }
        s.divider = (s.secondary & 0x00FFFFFF) | 0x22000000;
        // readable text on a primary-coloured chip
        double lum = (0.299 * Color.red(s.primary) + 0.587 * Color.green(s.primary) + 0.114 * Color.blue(s.primary)) / 255.0;
        s.onPrimary = lum > 0.5 ? Color.BLACK : Color.WHITE;
        s.accent = 0xFF2E9E5B;
        return s;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
