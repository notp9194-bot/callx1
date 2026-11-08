package com.callx.app.compose;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.callx.app.cache.StatusCacheManager;
import com.callx.app.models.StatusItem;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusTemplateUses;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * v776 — "which of MY templates?" for the Templates bottom-nav tabs Analytics / Notifications (both screens are per template).
 * One template → {@link Callback} fires at once; several → a bottom sheet (preview, age, uses); none → a toast.
 * Own templates = live statuses in the cached tray that carry a templateJson and were not made from someone else's template.
 */
final class OwnTemplatePicker {

    interface Callback { void onPick(@NonNull String statusId, @NonNull String templateJson); }

    private static final int PURPLE = 0xFF6A3DE8;

    private static final class Item { String id, json; long ts; TemplatePreviewDrawable preview; long uses = -1; TextView usesView; }

    private OwnTemplatePicker() { }

    static void show(@NonNull final Activity a, final int titleRes, @NonNull final Callback cb) {
        final String me = FirebaseUtils.getCurrentUid();
        final List<Item> items = new ArrayList<>();
        try {
            final List<StatusItem> list = me == null ? null : StatusCacheManager.getInstance(a.getApplicationContext()).getStatuses(me);
            if (list != null) for (StatusItem it : list) {
                if (it == null || it.templateJson == null || it.templateJson.isEmpty() || it.id == null || it.id.isEmpty()) continue;
                if ((it.templateFromUid != null && !it.templateFromUid.isEmpty()) || (it.templateFromId != null && !it.templateFromId.isEmpty())) continue;
                final Item i = new Item();
                i.id = it.id; i.json = it.templateJson; i.ts = it.timestamp != null ? it.timestamp : 0L;
                i.preview = TemplatePreviewDrawable.fromJson(i.json);
                items.add(i);
            }
        } catch (Exception ignored) { }
        if (items.isEmpty()) { Toast.makeText(a, R.string.tpl_pick_none, Toast.LENGTH_SHORT).show(); return; }
        Collections.sort(items, (x, y) -> Long.compare(y.ts, x.ts));
        if (items.size() == 1) { cb.onPick(items.get(0).id, items.get(0).json); return; }

        final float d = a.getResources().getDisplayMetrics().density;
        final BottomSheetDialog dlg = new BottomSheetDialog(a);
        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Math.round(18 * d), Math.round(18 * d), Math.round(18 * d), Math.round(22 * d));
        final GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(0xFFFFFFFF);
        bgd.setCornerRadii(new float[]{24 * d, 24 * d, 24 * d, 24 * d, 0, 0, 0, 0});
        root.setBackground(bgd);
        final TextView title = new TextView(a);
        title.setText(titleRes);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF14142B);
        title.setPadding(0, 0, 0, Math.round(10 * d));
        root.addView(title);

        final android.widget.ScrollView sv = new android.widget.ScrollView(a);
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, -2));

        int n = 0;
        for (final Item it : items) {
            n++;
            final LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(8 * d), Math.round(8 * d), Math.round(8 * d), Math.round(8 * d));
            final GradientDrawable rb = new GradientDrawable();
            rb.setColor(0xFFF2F0F8);
            rb.setCornerRadius(16 * d);
            row.setBackground(rb);
            final ImageView th = new ImageView(a);
            th.setScaleType(ImageView.ScaleType.FIT_XY);
            if (it.preview != null) th.setImageDrawable(it.preview);
            row.addView(th, new LinearLayout.LayoutParams(Math.round(40 * d), Math.round(72 * d)));
            final LinearLayout mid = new LinearLayout(a);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(Math.round(12 * d), 0, 0, 0);
            final TextView nm = new TextView(a);
            nm.setText(a.getString(R.string.tpl_pick_item, n));
            nm.setTextSize(15f);
            nm.setTypeface(Typeface.DEFAULT_BOLD);
            nm.setTextColor(0xFF14142B);
            mid.addView(nm);
            final TextView sub = new TextView(a);
            sub.setTextSize(12f);
            sub.setTextColor(0xFF8A8AA3);
            sub.setPadding(0, Math.round(4 * d), 0, 0);
            sub.setText(it.ts > 0 ? DateUtils.getRelativeTimeSpanString(it.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) : "");
            mid.addView(sub);
            final TextView uses = new TextView(a);
            uses.setTextSize(12f);
            uses.setTextColor(PURPLE);
            uses.setTypeface(Typeface.DEFAULT_BOLD);
            uses.setPadding(0, Math.round(3 * d), 0, 0);
            mid.addView(uses);
            it.usesView = uses;
            row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> { dlg.dismiss(); cb.onPick(it.id, it.json); });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, Math.round(8 * d));
            list.addView(row, lp);
            if (me != null) StatusTemplateUses.fetchCount(me, it.id, c -> {
                it.uses = c;
                if (!a.isFinishing() && !a.isDestroyed())
                    it.usesView.setText(c > 0 ? String.format(Locale.US, "%d uses", c) : a.getString(R.string.mt_no_uses));
            });
        }
        dlg.setContentView(root);
        dlg.show();
    }
}
