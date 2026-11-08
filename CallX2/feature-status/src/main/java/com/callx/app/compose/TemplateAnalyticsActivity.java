package com.callx.app.compose;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.NestedScrollView;

import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusTemplateUses;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * v771 — Screen 8 "Template Analytics" (owner only).
 *
 * Everything shown is real data from {@code templateStats/{me}/{statusId}} (v767): total uses and per-day buckets (UTC).
 *  - Total Uses + "+N today", This Week + % vs the week before, a smooth "uses over time" graph (7 / 30 days, tap a point),
 *    and a small summary (best day, average per day, today).
 *  - NOT shown on purpose: "Completion rate" and "Top regions" — the app collects no data for them (no fake numbers).
 * Daily buckets exist only for uses since v767; older uses are in the total only (a note says so).
 * Premium iOS look (white surface, purple accent, rounded cards); the only icon is an existing drawable.
 */
public class TemplateAnalyticsActivity extends AppCompatActivity {

    public static final String EXTRA_STATUS_ID = "taStatusId";
    public static final String EXTRA_TPL_JSON = "taTplJson";   // optional, for the small preview

    private static final int PURPLE = 0xFF6A3DE8, PURPLE_LIGHT = 0xFF9A7BFF, GREEN = 0xFF1FA971, RED = 0xFFE5484D;

    private boolean dark;
    private int bg, surface, textColor, subColor;
    private String me, statusId;

    private StatusTemplateUses.Stats stats;
    private int range = 7;

    private TextView totalVal, totalSub, weekVal, weekSub, noDaily, bestVal, avgVal, todayVal;
    private TextView[] rangeViews = new TextView[2];
    private ChartView chart;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        me = FirebaseUtils.getCurrentUid();
        statusId = getIntent().getStringExtra(EXTRA_STATUS_ID);
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        bg = tv.data;
        dark = ColorUtils.calculateLuminance(bg) < 0.5;
        surface = dark ? 0x22FFFFFF : 0xFFF2F0F8;
        textColor = dark ? 0xFFFFFFFF : 0xFF14142B;
        subColor = dark ? 0xAAFFFFFF : 0xFF8A8AA3;

        if (me == null || me.isEmpty() || statusId == null || statusId.isEmpty()) {
            Toast.makeText(this, R.string.ta_unavailable, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);
        buildTopBar(root);

        final NestedScrollView scroll = new NestedScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(24));
        buildCards(content);
        buildChartCard(content);
        buildSummary(content);
        buildUsersButton(content);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        if (!dark) WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(true);

        load();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int radiusDp) {
        final GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private ImageView icon(@DrawableRes int res, int tint, int sizeDp) {
        final ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageViewCompat.setImageTintList(v, ColorStateList.valueOf(tint));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private TextView text(CharSequence s, float sp, int color, boolean bold) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }

    static String compact(long n) {
        if (n >= 1_000_000) return trimZero(n / 100_000 / 10.0) + "M";
        if (n >= 1_000) return trimZero(n / 100 / 10.0) + "K";
        return String.valueOf(n);
    }

    private static String trimZero(double d) {
        final String s = String.format(Locale.US, "%.1f", d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ── layout ───────────────────────────────────────────────────────────────

    private void buildTopBar(LinearLayout root) {
        final FrameLayout bar = new FrameLayout(this);
        final FrameLayout back = new FrameLayout(this);
        back.setBackground(round(surface, 21));
        back.addView(icon(R.drawable.ic_arrow_back, textColor, 22), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        back.setOnClickListener(v -> finish());
        final FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.START | Gravity.CENTER_VERTICAL);
        blp.setMargins(dp(14), 0, 0, 0);
        bar.addView(back, blp);
        bar.addView(text(getString(R.string.ta_title), 19f, textColor, true), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));
    }

    /** Total Uses | This Week */
    private void buildCards(LinearLayout content) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout a = statCard(getString(R.string.ta_total_uses));
        totalVal = (TextView) a.getChildAt(1);
        totalSub = (TextView) a.getChildAt(2);
        final LinearLayout c = statCard(getString(R.string.ta_this_week));
        weekVal = (TextView) c.getChildAt(1);
        weekSub = (TextView) c.getChildAt(2);
        final LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1f);
        l1.setMargins(0, 0, dp(6), 0);
        final LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, -2, 1f);
        l2.setMargins(dp(6), 0, 0, 0);
        row.addView(a, l1);
        row.addView(c, l2);
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout statCard(String label) {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(surface, 18));
        c.addView(text(label, 12f, subColor, false));
        final TextView v = text("—", 28f, textColor, true);
        v.setPadding(0, dp(6), 0, 0);
        c.addView(v);
        final TextView s = text("", 12f, subColor, true);
        s.setPadding(0, dp(4), 0, 0);
        c.addView(s);
        return c;
    }

    private void buildChartCard(LinearLayout content) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(10));
        card.setBackground(round(surface, 18));

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(getString(R.string.ta_uses_over_time), 16f, textColor, true), new LinearLayout.LayoutParams(0, -2, 1f));
        final int[] labels = {R.string.ta_range_7, R.string.ta_range_30};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            final TextView t = text(getString(labels[i]), 12f, textColor, true);
            t.setPadding(dp(12), dp(7), dp(12), dp(7));
            t.setOnClickListener(v -> { range = idx == 0 ? 7 : 30; styleRange(); bindChart(); });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(6), 0, 0, 0);
            head.addView(t, lp);
            rangeViews[i] = t;
        }
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        styleRange();

        chart = new ChartView(this);
        final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(210));
        clp.setMargins(0, dp(10), 0, 0);
        card.addView(chart, clp);

        noDaily = text(getString(R.string.ta_no_daily), 12f, subColor, false);
        noDaily.setLineSpacing(0, 1.15f);
        noDaily.setPadding(dp(4), dp(8), dp(4), 0);
        noDaily.setVisibility(View.GONE);
        card.addView(noDaily);

        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        content.addView(card, lp);
    }

    private void styleRange() {
        for (int i = 0; i < 2; i++) {
            final boolean on = (i == 0 ? 7 : 30) == range;
            rangeViews[i].setBackground(round(on ? PURPLE : (dark ? 0x22FFFFFF : 0xFFFFFFFF), 14));
            rangeViews[i].setTextColor(on ? 0xFFFFFFFF : textColor);
        }
    }

    private void buildSummary(LinearLayout content) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(8));
        card.setBackground(round(surface, 18));
        card.addView(text(getString(R.string.ta_summary), 16f, textColor, true));
        bestVal = summaryRow(card, getString(R.string.ta_best_day));
        avgVal = summaryRow(card, getString(R.string.ta_avg_per_day));
        todayVal = summaryRow(card, getString(R.string.ta_today));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        content.addView(card, lp);
    }

    private TextView summaryRow(LinearLayout card, String label) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, dp(6));
        r.addView(text(label, 14f, subColor, false), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView v = text("—", 14f, textColor, true);
        r.addView(v);
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private void buildUsersButton(LinearLayout content) {
        final TextView cta = text(getString(R.string.ta_view_users), 15f, 0xFFFFFFFF, true);
        cta.setGravity(Gravity.CENTER);
        final GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE_LIGHT, PURPLE});
        g.setCornerRadius(dp(26));
        cta.setBackground(g);
        cta.setOnClickListener(v -> startActivity(new Intent(this, TemplateUsesActivity.class)
                .putExtra(TemplateUsesActivity.EXTRA_STATUS_ID, statusId)));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, dp(18), 0, 0);
        content.addView(cta, lp);
    }

    // ── data ─────────────────────────────────────────────────────────────────

    private void load() {
        StatusTemplateUses.fetchStats(me, statusId, st -> {
            if (isFinishing() || isDestroyed()) return;
            stats = st;
            if (st.total < 0) {   // old template without a counter -> the who-used rows give the total
                StatusTemplateUses.fetchCount(me, statusId, n -> {
                    if (isFinishing() || isDestroyed()) return;
                    stats.total = n;
                    bindAll();
                });
            }
            bindAll();
        });
    }

    /** Uses on UTC day {@code daysAgo} (0 = today). */
    private long dayCount(int daysAgo) {
        if (stats == null) return 0;
        final Long n = stats.days.get(StatusTemplateUses.dayKey(System.currentTimeMillis() - daysAgo * 86_400_000L));
        return n != null ? n : 0L;
    }

    private long sum(int fromDaysAgo, int toDaysAgoInclusive) {
        long s = 0;
        for (int d = fromDaysAgo; d <= toDaysAgoInclusive; d++) s += dayCount(d);
        return s;
    }

    private void bindAll() {
        if (stats == null) return;
        final long today = dayCount(0);
        final long week = sum(0, 6), prev = sum(7, 13);
        totalVal.setText(stats.total >= 0 ? compact(stats.total) : "—");
        if (today > 0) {
            totalSub.setText(getString(R.string.ta_today_plus, compact(today)));
            totalSub.setTextColor(GREEN);
        } else totalSub.setText("");
        weekVal.setText(compact(week));
        if (prev > 0) {
            final long pct = Math.round((week - prev) * 100.0 / prev);
            weekSub.setText(getString(R.string.ta_vs_last_week, (pct >= 0 ? "+" : "") + pct));
            weekSub.setTextColor(pct >= 0 ? GREEN : RED);
        } else if (week > 0) {
            weekSub.setText(R.string.ta_new_this_week);
            weekSub.setTextColor(GREEN);
        } else weekSub.setText("");
        noDaily.setVisibility(stats.days.isEmpty() ? View.VISIBLE : View.GONE);
        bindChart();
    }

    private void bindChart() {
        if (stats == null || chart == null) return;
        final int n = range;
        final long[] v = new long[n];
        final String[] labels = new String[n];
        final SimpleDateFormat wd = new SimpleDateFormat("EEE", Locale.getDefault());
        final SimpleDateFormat dd = new SimpleDateFormat("d MMM", Locale.getDefault());
        wd.setTimeZone(TimeZone.getTimeZone("UTC"));
        dd.setTimeZone(TimeZone.getTimeZone("UTC"));
        long total = 0, best = -1;
        int bestIdx = -1;
        for (int i = 0; i < n; i++) {
            final int ago = n - 1 - i;                       // oldest -> newest
            v[i] = dayCount(ago);
            final Date d = new Date(System.currentTimeMillis() - ago * 86_400_000L);
            labels[i] = n == 7 ? wd.format(d) : (i % 5 == 0 || i == n - 1 ? dd.format(d) : "");
            total += v[i];
            if (v[i] > best) { best = v[i]; bestIdx = i; }
        }
        final long finalBest = best;
        final int finalBestIdx = bestIdx;
        chart.set(v, labels, finalBest > 0 ? finalBestIdx : n - 1);
        if (finalBest > 0) {
            final Date d = new Date(System.currentTimeMillis() - (n - 1 - finalBestIdx) * 86_400_000L);
            bestVal.setText(dd.format(d) + " · " + getString(R.string.ta_uses_n, compact(finalBest)));
        } else bestVal.setText("—");
        avgVal.setText(trimZero(total / (double) n));
        todayVal.setText(getString(R.string.ta_uses_n, compact(dayCount(0))));
    }

    // ── chart ────────────────────────────────────────────────────────────────

    /** Smooth purple line + soft fill, 3 grid lines, tap a point to read it. */
    private final class ChartView extends View {
        private long[] v = new long[0];
        private String[] labels = new String[0];
        private int sel = -1;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tipTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), area = new Path();
        private final RectF r = new RectF();
        private float[] px = new float[0], py = new float[0];

        ChartView(Context c) {
            super(c);
            final float d = getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2.5f * d);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(PURPLE);
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(d);
            grid.setColor(dark ? 0x22FFFFFF : 0x14000000);
            txt.setColor(subColor);
            txt.setTextSize(11f * d);
            txt.setTextAlign(Paint.Align.CENTER);
            tip.setColor(0xFF14142B);
            tipTxt.setColor(0xFFFFFFFF);
            tipTxt.setTextSize(12f * d);
            tipTxt.setTypeface(Typeface.DEFAULT_BOLD);
            tipTxt.setTextAlign(Paint.Align.CENTER);
        }

        void set(long[] values, String[] lbls, int selected) {
            v = values; labels = lbls; sel = selected;
            invalidate();
        }

        @Override protected void onDraw(@NonNull Canvas c) {
            final int n = v.length;
            if (n == 0) return;
            final float d = getResources().getDisplayMetrics().density;
            final float left = 8 * d, right = getWidth() - 8 * d, top = 34 * d, bottom = getHeight() - 24 * d;
            long max = 1;
            for (long x : v) max = Math.max(max, x);
            for (int i = 0; i <= 2; i++) {
                final float y = top + (bottom - top) * i / 2f;
                c.drawLine(left, y, right, y, grid);
            }
            if (px.length != n) { px = new float[n]; py = new float[n]; }
            for (int i = 0; i < n; i++) {
                px[i] = n == 1 ? (left + right) / 2f : left + (right - left) * i / (n - 1f);
                py[i] = bottom - (bottom - top) * (v[i] / (float) max);
            }
            path.reset();
            area.reset();
            path.moveTo(px[0], py[0]);
            for (int i = 1; i < n; i++) {
                final float mx = (px[i - 1] + px[i]) / 2f;
                path.cubicTo(mx, py[i - 1], mx, py[i], px[i], py[i]);
            }
            area.addPath(path);
            area.lineTo(px[n - 1], bottom);
            area.lineTo(px[0], bottom);
            area.close();
            fill.setShader(new LinearGradient(0, top, 0, bottom, 0x556A3DE8, 0x006A3DE8, Shader.TileMode.CLAMP));
            c.drawPath(area, fill);
            c.drawPath(path, line);
            if (n <= 8) {
                dot.setColor(0xFFFFFFFF);
                for (int i = 0; i < n; i++) c.drawCircle(px[i], py[i], 4 * d, dot);
                dot.setColor(PURPLE);
                for (int i = 0; i < n; i++) c.drawCircle(px[i], py[i], 2.5f * d, dot);
            }
            for (int i = 0; i < n; i++) if (labels[i] != null && !labels[i].isEmpty()) c.drawText(labels[i], px[i], getHeight() - 6 * d, txt);
            if (sel >= 0 && sel < n) {
                dot.setColor(0xFFFFFFFF);
                c.drawCircle(px[sel], py[sel], 7 * d, dot);
                dot.setColor(PURPLE);
                c.drawCircle(px[sel], py[sel], 5 * d, dot);
                final String s = compact(v[sel]);
                final float tw = tipTxt.measureText(s) + 20 * d, th = 24 * d;
                final float tx = Math.max(tw / 2f, Math.min(getWidth() - tw / 2f, px[sel]));
                final float ty = Math.max(2 * d, py[sel] - 12 * d - th);
                r.set(tx - tw / 2f, ty, tx + tw / 2f, ty + th);
                c.drawRoundRect(r, 8 * d, 8 * d, tip);
                c.drawText(s, tx, ty + th / 2f + 4.5f * d, tipTxt);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (v.length == 0 || px.length != v.length) return super.onTouchEvent(e);
            final int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                int best = 0;
                float bd = Float.MAX_VALUE;
                for (int i = 0; i < px.length; i++) {
                    final float dx = Math.abs(px[i] - e.getX());
                    if (dx < bd) { bd = dx; best = i; }
                }
                if (best != sel) { sel = best; invalidate(); }
                return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
