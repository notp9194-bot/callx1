package com.callx.app.interactions;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DataSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * StatusSeenByBottomSheet v27 — perf pass, same pattern as StatusRepliesBottomSheet v512/v513.
 *  1) RecyclerView + ListAdapter/DiffUtil + stable ids (was ScrollView + addView per row, rows
 *     appeared in Firebase-arrival order and could never be updated in place).
 *  2) Avatars via StatusSheetAvatar (AvatarUrlBuilder#buildResponsive(TINY) + tierPx override,
 *     32dp) -> same Glide cache key as the reply sheet / reels comments.
 *  3) Snapshot parsing, time formatting and sorting on a single bg thread; arrivals are coalesced
 *     into ONE list submit per frame (was one layout pass per viewer).
 *  4) Theme colors / typeface resolved ONCE per sheet.
 *  5) Height-capped list, item animator off, item view cache, sheet opens expanded.
 *  6) Small process-wide profile cache: re-opening the sheet paints instantly, then refreshes.
 *  7) Story ring on every avatar via core StoryRingApplier (live-updates through StoryRingRegistry).
 *  8) Failed profile reads no longer leave the progress bar spinning forever.
 */
public class StatusSeenByBottomSheet {

    /** Single bg thread (ordered): parsing, SimpleDateFormat (not thread-safe -> bg only), sorting. */
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "status-seenby-bg"); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });

    /** uid -> {name, photo}. Survives sheet re-opens; always refreshed from Firebase afterwards. */
    private static final LruCache<String, String[]> PROFILES = new LruCache<>(300);

    /** Immutable row model (DiffUtil compares by value). */
    static final class Row {
        final String uid, name, photo, time, reaction;
        final long seenAt;
        Row(String uid, String name, String photo, long seenAt, String time, String reaction) {
            this.uid = uid; this.name = name; this.photo = photo;
            this.seenAt = seenAt; this.time = time; this.reaction = reaction;
        }
        static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
        boolean sameContent(Row o) {
            return seenAt == o.seenAt && eq(name, o.name) && eq(photo, o.photo)
                    && eq(time, o.time) && eq(reaction, o.reaction);
        }
    }

    /** Resolved ONCE per sheet. */
    private static final class Style { int primary, secondary, divider; }

    private static final class Env {
        Context ctx; Style style; StatusItem item;
        final Handler main = new Handler(Looper.getMainLooper());
        final HashMap<String, Row> rows = new HashMap<>();
        final SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault()); // bg thread only
        boolean dismissed, flushQueued; int gen, loaded, total;
        RowsAdapter adapter; ProgressBar progress;
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
        final List<String> uids = new ArrayList<>(item.seenBy.keySet());
        env.total = uids.size();

        final BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8));

        TextView header = new TextView(ctx);
        header.setText("Seen by " + item.seenBy.size());
        header.setTextSize(17);
        header.setTextColor(env.style.primary);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
        root.addView(header);

        // Reaction summary strip
        if (item.reactions != null && !item.reactions.isEmpty()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String e : item.reactions.values()) counts.merge(e, 1, Integer::sum);
            LinearLayout strip = new LinearLayout(ctx);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(0, 0, 0, dp(ctx, 12));
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                TextView chip = new TextView(ctx);
                chip.setText(e.getKey() + " " + e.getValue());
                chip.setTextSize(14);
                chip.setTextColor(env.style.primary);
                chip.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(dp(ctx, 16));
                bg.setColor(env.style.divider);
                chip.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(dp(ctx, 8));
                strip.addView(chip, lp);
            }
            root.addView(strip);
        }

        env.progress = new ProgressBar(ctx);
        root.addView(env.progress);

        // Height-capped RecyclerView (same cap logic as the replies sheet).
        final RecyclerView rv = new RecyclerView(ctx) {
            @Override protected void onMeasure(int w, int h) {
                int screen = getResources().getDisplayMetrics().heightPixels;
                int rootH = getRootView() != null ? getRootView().getHeight() : screen;
                if (rootH <= 0) rootH = screen;
                int max = Math.max(dp(ctx, 160), Math.min((int) (screen * 0.7f), rootH - dp(ctx, 140)));
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
            if (cached != null) env.rows.put(uid, makeRow(env, uid, cached[0], cached[1]));
        }
        if (!env.rows.isEmpty()) flush(env);

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
                env.rows.put(uid, r);
                markLoaded(env);
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
        return new Row(uid, name, photo, seenAt, time, env.item.getReaction(uid));
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

    private static void flush(Env env) {
        final List<Row> snapshot = new ArrayList<>(env.rows.values());
        final int gen = ++env.gen;
        BG.execute(() -> {
            Collections.sort(snapshot, (a, b) -> {
                int c = Long.compare(b.seenAt, a.seenAt); // most recent viewer first
                return c != 0 ? c : a.uid.compareTo(b.uid);
            });
            env.main.post(() -> { if (!env.dismissed && gen == env.gen) env.adapter.submitList(snapshot); });
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

    /** One viewer row: 32dp avatar, name + "Seen <time>", optional reaction. Views built ONCE per holder. */
    private static final class RowVH extends RecyclerView.ViewHolder {
        final Env env;
        final CircleImageView avatar;
        final ImageView ring;
        final TextView tvName, tvTime, tvReact;

        RowVH(Env env) {
            super(new LinearLayout(env.ctx));
            this.env = env;
            final Context ctx = env.ctx;
            LinearLayout box = (LinearLayout) itemView;
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));

            // Story ring (same StoryRingApplier every other avatar list uses) around the 32dp avatar.
            FrameLayout avatarBox = new FrameLayout(ctx);
            int ringSz = dp(ctx, StatusSheetAvatar.SIZE_DP + 6);
            ring = new ImageView(ctx);
            ring.setVisibility(View.GONE);
            avatarBox.addView(ring, new FrameLayout.LayoutParams(ringSz, ringSz));
            avatar = new CircleImageView(ctx);
            int size = dp(ctx, StatusSheetAvatar.SIZE_DP); // 32dp = TINY tier
            FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(size, size);
            alp.gravity = Gravity.CENTER;
            avatarBox.addView(avatar, alp);
            row.addView(avatarBox, new LinearLayout.LayoutParams(ringSz, ringSz));

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(ctx, 12), 0, 0, 0);
            row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            tvName = new TextView(ctx);
            tvName.setTextSize(15);
            tvName.setTextColor(env.style.primary);
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            tvName.setSingleLine(true);
            tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(tvName);

            tvTime = new TextView(ctx);
            tvTime.setTextSize(12);
            tvTime.setTextColor(env.style.secondary);
            info.addView(tvTime);

            tvReact = new TextView(ctx);
            tvReact.setTextSize(22);
            tvReact.setPadding(dp(ctx, 8), 0, 0, 0);
            row.addView(tvReact);

            box.addView(row);
            View divider = new View(ctx);
            divider.setBackgroundColor(env.style.divider);
            box.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }

        void bind(Row r) {
            tvName.setText(r.name);
            if (r.time == null || r.time.isEmpty()) tvTime.setVisibility(View.GONE);
            else { tvTime.setVisibility(View.VISIBLE); tvTime.setText("Seen " + r.time); }
            if (r.reaction == null) tvReact.setVisibility(View.GONE);
            else { tvReact.setVisibility(View.VISIBLE); tvReact.setText(r.reaction); }
            StatusSheetAvatar.load(env.ctx, avatar, r.photo, com.callx.app.status.R.drawable.ic_person);
            // Gradient = unseen story, gray = all seen, hidden = none; tap opens the story viewer.
            com.callx.app.utils.StoryRingApplier.applyWithClick(env.ctx, ring, r.uid, r.name);
        }

        void release() {
            try { com.bumptech.glide.Glide.with(env.ctx).clear(avatar); } catch (Exception ignored) {}
            ring.setOnClickListener(null);
        }
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
        return s;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
