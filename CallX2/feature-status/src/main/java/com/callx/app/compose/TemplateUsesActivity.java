package com.callx.app.compose;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.viewer.StatusViewerActivity;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v749: the template OWNER's "who used it" list — statusTemplateUses/{me}/{statusId} (uid → time), newest first, with
 * name + avatar from users/{uid}. Tap a row → that person's status in the normal viewer (the viewer applies its own
 * privacy rules, so nothing private is exposed here beyond the uid the counter already holds).
 */
public class TemplateUsesActivity extends AppCompatActivity {

    public static final String EXTRA_STATUS_ID = "templateStatusId";

    private static final class Row { String uid, name = "", photo = ""; long ts; }

    private final List<Row> rows = new ArrayList<>();
    private Adapter adapter;
    private TextView title, empty;
    private int textColor, subColor;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String me = FirebaseUtils.getCurrentUid();
        final String statusId = getIntent().getStringExtra(EXTRA_STATUS_ID);
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        final int bg = tv.data;
        final boolean dark = ColorUtils.calculateLuminance(bg) < 0.5;
        textColor = dark ? 0xFFFFFFFF : 0xFF111111;
        subColor = dark ? 0xAAFFFFFF : 0x99000000;

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);
        final LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(8), dp(16), dp(8));
        final TextView back = new TextView(this);
        back.setText("\u2190"); back.setTextSize(24f); back.setTextColor(textColor); back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = new TextView(this);
        title.setText("Template use"); title.setTextSize(20f); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(textColor);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

        final android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        final RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        body.addView(rv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        empty = new TextView(this);
        empty.setText("Abhi kisi ne use nahi kiya.");
        empty.setTextColor(subColor); empty.setGravity(Gravity.CENTER); empty.setVisibility(View.GONE);
        body.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        if (me == null || me.isEmpty() || statusId == null || statusId.isEmpty()) { finish(); return; }
        FirebaseUtils.db().getReference("statusTemplateUses").child(me).child(statusId).limitToLast(200)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        for (DataSnapshot c : s.getChildren()) {
                            final Row r = new Row();
                            r.uid = c.getKey();
                            final Long t = c.getValue(Long.class);
                            r.ts = t != null ? t : 0L;
                            if (r.uid != null) rows.add(r);
                        }
                        Collections.sort(rows, (x, y) -> Long.compare(y.ts, x.ts));
                        title.setText(rows.isEmpty() ? "Template use" : rows.size() + " logon ne use kiya");
                        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                        adapter.notifyItemRangeInserted(0, rows.size());   // rows was empty before this one-shot fill
                        for (int i = 0; i < rows.size(); i++) resolve(i);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { empty.setVisibility(View.VISIBLE); }
                });
    }

    private void resolve(final int index) {
        final Row r = rows.get(index);
        FirebaseUtils.getUserRef(r.uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                final String n = s.child("name").getValue(String.class);
                final String p = s.child("photoUrl").getValue(String.class);
                r.name = n != null ? n : "";
                r.photo = p != null ? p : "";
                if (!isFinishing() && !isDestroyed()) adapter.notifyItemChanged(index);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final ImageView avatar; final TextView name, time;
            VH(LinearLayout v, ImageView a, TextView n, TextView t) { super(v); avatar = a; name = n; time = t; }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final LinearLayout row = new LinearLayout(parent.getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            final ImageView a = new ImageView(parent.getContext());
            a.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(a, new LinearLayout.LayoutParams(dp(44), dp(44)));
            final LinearLayout col = new LinearLayout(parent.getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            final TextView n = new TextView(parent.getContext());
            n.setTextColor(textColor); n.setTextSize(15f); n.setTypeface(Typeface.DEFAULT_BOLD); n.setSingleLine(true);
            final TextView t = new TextView(parent.getContext());
            t.setTextColor(subColor); t.setTextSize(12f);
            col.addView(n); col.addView(t);
            final LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            clp.setMarginStart(dp(12));
            row.addView(col, clp);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(row, a, n, t);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            final Row r = rows.get(pos);
            h.name.setText(r.name.isEmpty() ? "User" : r.name);
            h.time.setText(android.text.format.DateUtils.getRelativeTimeSpanString(
                    r.ts, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
            Glide.with(h.avatar).load(r.photo.isEmpty() ? null : r.photo).apply(RequestOptions.circleCropTransform()).into(h.avatar);
            h.itemView.setOnClickListener(v -> {
                final Intent i = new Intent(TemplateUsesActivity.this, StatusViewerActivity.class);
                i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, r.uid);
                i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, r.name);
                if (!r.photo.isEmpty()) i.putExtra(StatusViewerActivity.EXTRA_OWNER_PHOTO, r.photo);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return rows.size(); }
    }
}
