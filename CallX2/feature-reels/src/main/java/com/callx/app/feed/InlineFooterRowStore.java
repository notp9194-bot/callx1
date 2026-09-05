package com.callx.app.feed;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * ★ v334: cross-session persistence for the 4 "ex-footer" inline feed rows —
 * Trending / (Top) Suggested Creators / Friends Activity / Continue Watching
 * (see HomeFragment.ROW_TRENDING_REELS class doc).
 *
 * Before this class, "the user ✕-dismissed this row" and "this row has
 * already been shown N times today" lived only in plain in-memory fields on
 * HomeFragment — meaning both facts were forgotten the instant the process
 * died (app swiped away, low-memory kill, phone restart). Real Instagram
 * doesn't re-show you a shelf you just swiped away the moment you reopen the
 * app a minute later, and it doesn't reset its own "how many times have I
 * shown you this today" bookkeeping on every cold start either — both of
 * those need to survive past this process's lifetime, hence
 * SharedPreferences instead of a field.
 *
 * Deliberately tiny and dependency-free (no Room table/migration risk) —
 * this is a handful of small keyed values per row, not a data model.
 */
public final class InlineFooterRowStore {

    public static final String ROW_TRENDING          = "trending";
    public static final String ROW_TOP_CREATORS      = "top_creators";
    public static final String ROW_FRIENDS_ACTIVITY  = "friends_activity";
    public static final String ROW_CONTINUE_WATCHING = "continue_watching";

    private static final String PREFS_NAME = "callx_inline_footer_rows";

    private InlineFooterRowStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Calendar-day key (year + day-of-year) — used so the shown-count cap
     *  rolls over automatically at midnight without needing a background
     *  job to reset it. */
    private static String todayKey() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    /** True while this row is inside its ✕-dismiss cooldown window — the
     *  drip-feed should skip inserting it entirely while this is true, even
     *  across app restarts (see HomeFragment.canShowInlineFooterRow()). */
    public static boolean isDismissed(Context ctx, String rowKey) {
        long until = prefs(ctx).getLong(rowKey + "_dismissed_until", 0L);
        return System.currentTimeMillis() < until;
    }

    /** Called from a row's own ✕ button — hides it from the drip-feed for
     *  `durationMs`, persisted immediately. */
    public static void dismissFor(Context ctx, String rowKey, long durationMs) {
        prefs(ctx).edit()
            .putLong(rowKey + "_dismissed_until", System.currentTimeMillis() + durationMs)
            .apply();
    }

    /** How many times this row has already been shown TODAY. Returns 0 the
     *  first time it's checked on a new calendar day (see recordShown()'s
     *  day-rollover) — no separate reset job needed. */
    public static int getShownCountToday(Context ctx, String rowKey) {
        SharedPreferences p = prefs(ctx);
        String day = p.getString(rowKey + "_shown_day", null);
        if (!todayKey().equals(day)) return 0; // stale day => today's count is effectively 0
        return p.getInt(rowKey + "_shown_count", 0);
    }

    /** Records one impression of this row, rolling the counter over to 1 if
     *  the last recorded impression was on an earlier calendar day. */
    public static void recordShown(Context ctx, String rowKey) {
        SharedPreferences p = prefs(ctx);
        String today = todayKey();
        String day = p.getString(rowKey + "_shown_day", null);
        int count = today.equals(day) ? p.getInt(rowKey + "_shown_count", 0) : 0;
        p.edit()
            .putString(rowKey + "_shown_day", today)
            .putInt(rowKey + "_shown_count", count + 1)
            .putLong(rowKey + "_last_shown_at", System.currentTimeMillis())
            .apply();
    }

    // ── Engagement-based dynamic repositioning ──────────────────────────
    //
    // ★ Instagram-level gap fix: the drip-feed cadence used to be pure
    // hardcoded static ranges (fixed AFTER_POST/REPEAT windows in
    // HomeFragment) — every user saw Trending at post 3-4 whether they
    // tapped into it every time or scrolled past it without a glance.
    // Real IG promotes shelves a person actually engages with (shown
    // sooner, shown more often) and quietly backs off ones they ignore
    // (shown later, shown less), per person, per shelf — not a single
    // global A/B arm. This section is that per-(user, row) signal: a
    // lifetime tap-through rate, smoothed against a neutral prior so one
    // early tap or one early skip can't swing the whole cadence, and
    // periodically halved so it stays a RECENT rate rather than a
    // lifetime average that a behavior change from months ago can never
    // be corrected. HomeFragment turns this rate into a multiplier that
    // scales its existing base ranges/caps — the static ranges stay as
    // the floor/ceiling-defining baseline, engagement only moves you
    // within (and slightly beyond) them.

    /** Assumed neutral tap-through rate for these rail types — the point
     *  where a row is neither promoted nor demoted. Not derived from real
     *  aggregate data (no analytics backend to compute it from here); a
     *  reasonable fixed reference point is what a client-side heuristic
     *  can do, same honest framing as FeedRankingEngine's tunable weights. */
    private static final float BASELINE_ENGAGEMENT_RATE = 0.12f;

    /** Virtual prior impressions/taps blended into the real counts so a
     *  row with 0-2 real impressions doesn't get a wild multiplier off a
     *  single lucky/unlucky tap — same Laplace-smoothing idea as any
     *  small-sample CTR estimate. */
    private static final int PRIOR_WEIGHT = 8;

    /** Once lifetime impressions cross this, both counters are halved —
     *  keeps the rate responsive to how the user behaves NOW instead of
     *  averaging in taps from months ago forever. */
    private static final int DECAY_IMPRESSION_CAP = 60;

    private static final float MIN_ENGAGEMENT_MULTIPLIER = 0.5f;
    private static final float MAX_ENGAGEMENT_MULTIPLIER = 2.0f;

    /** Called once per impression of this row (separate from recordShown's
     *  day-scoped cap counter — this one is lifetime-with-decay, for the
     *  engagement rate, not "how many times today"). */
    public static void recordImpression(Context ctx, String rowKey) {
        SharedPreferences p = prefs(ctx);
        int impressions = p.getInt(rowKey + "_eng_impressions", 0) + 1;
        int taps = p.getInt(rowKey + "_eng_taps", 0);
        if (impressions > DECAY_IMPRESSION_CAP) {
            impressions /= 2;
            taps /= 2;
        }
        p.edit()
            .putInt(rowKey + "_eng_impressions", impressions)
            .putInt(rowKey + "_eng_taps", taps)
            .apply();
    }

    /** Called whenever the user taps into a card belonging to this row
     *  (opens a reel/profile from it, follows from it, etc.) — the
     *  positive engagement signal that eventually pulls the row's cadence
     *  closer and raises its daily cap. */
    public static void recordTap(Context ctx, String rowKey) {
        SharedPreferences p = prefs(ctx);
        p.edit().putInt(rowKey + "_eng_taps", p.getInt(rowKey + "_eng_taps", 0) + 1).apply();
    }

    /** Smoothed tap-through rate for this row, blended with the neutral
     *  prior above — always in (0, 1), never exactly 0 even with zero
     *  taps so a demoted row can still claw its way back with future
     *  taps instead of hitting a permanent floor. */
    private static float smoothedEngagementRate(Context ctx, String rowKey) {
        SharedPreferences p = prefs(ctx);
        int impressions = p.getInt(rowKey + "_eng_impressions", 0);
        int taps = p.getInt(rowKey + "_eng_taps", 0);
        return (taps + PRIOR_WEIGHT * BASELINE_ENGAGEMENT_RATE) / (impressions + PRIOR_WEIGHT);
    }

    /** This row's engagement vs. the neutral baseline, clamped to
     *  [MIN_ENGAGEMENT_MULTIPLIER, MAX_ENGAGEMENT_MULTIPLIER]. &gt;1 means
     *  this specific user engages with this specific row more than
     *  baseline (promote — sooner, more often); &lt;1 means they mostly
     *  scroll past it (demote — later, less often). Never 0 — a row is
     *  spaced out further, not switched off, so it keeps a real (if rare)
     *  chance to re-earn engagement instead of dying permanently off one
     *  bad stretch. */
    public static float getEngagementMultiplier(Context ctx, String rowKey) {
        float rate = smoothedEngagementRate(ctx, rowKey);
        float multiplier = rate / BASELINE_ENGAGEMENT_RATE;
        return Math.max(MIN_ENGAGEMENT_MULTIPLIER, Math.min(MAX_ENGAGEMENT_MULTIPLIER, multiplier));
    }
}
