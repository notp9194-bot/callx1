package com.callx.app.adapters;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import java.util.*;
import java.util.function.Consumer;

/**
 * HighlightsStripAdapter v26 — NEW: Horizontal highlights strip at top of StatusFragment.
 * FIX: Was mentioned in docs but never implemented.
 */
public class HighlightsStripAdapter extends RecyclerView.Adapter<HighlightsStripAdapter.VH> {
    public static class AlbumPreview {
        public String id, name, coverUrl; public int count;
    }
    private final List<AlbumPreview> albums = new ArrayList<>();
    private final Consumer<AlbumPreview> onClick;

    public HighlightsStripAdapter(Consumer<AlbumPreview> onClick) { this.onClick = onClick; }

    public void setData(List<AlbumPreview> data) {
        albums.clear(); if (data != null) albums.addAll(data); notifyDataSetChanged();
    }

    @Override public int getItemCount() { return albums.size(); }

    @Override public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        Context ctx = parent.getContext();
        LinearLayout ll = new LinearLayout(ctx); ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(android.view.Gravity.CENTER); ll.setPadding(dp(ctx,6),dp(ctx,6),dp(ctx,6),dp(ctx,6));
        ll.setLayoutParams(new RecyclerView.LayoutParams(dp(ctx,80), RecyclerView.LayoutParams.WRAP_CONTENT));
        return new VH(ll);
    }

    @Override public void onBindViewHolder(VH h, int pos) {
        AlbumPreview a = albums.get(pos);
        Context ctx = h.itemView.getContext();
        LinearLayout ll = (LinearLayout) h.itemView; ll.removeAllViews();

        // Circular cover image
        FrameLayout frame = new FrameLayout(ctx);
        frame.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx,64), dp(ctx,64)));
        ImageView iv = new ImageView(ctx);
        iv.setLayoutParams(new FrameLayout.LayoutParams(dp(ctx,60), dp(ctx,60)));
        ((FrameLayout.LayoutParams)iv.getLayoutParams()).gravity = android.view.Gravity.CENTER;
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (a.coverUrl != null) Glide.with(ctx).load(a.coverUrl).circleCrop().into(iv);
        else { iv.setBackgroundColor(0xFF888888); }
        // Ring border
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setStroke(dp(ctx,3), Color.parseColor("#6200EE")); ring.setColor(Color.TRANSPARENT);
        View ringView = new View(ctx);
        ringView.setBackground(ring); ringView.setLayoutParams(new FrameLayout.LayoutParams(dp(ctx,64),dp(ctx,64)));
        frame.addView(iv); frame.addView(ringView); ll.addView(frame);

        // Name
        TextView tvName = new TextView(ctx); tvName.setText(a.name); tvName.setTextSize(11);
        tvName.setMaxLines(1); tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvName.setGravity(android.view.Gravity.CENTER); tvName.setPadding(0,dp(ctx,4),0,0);
        ll.addView(tvName);

        ll.setOnClickListener(v -> { if (onClick != null) onClick.accept(a); });
    }

    static class VH extends RecyclerView.ViewHolder { VH(View v){super(v);} }
    private static int dp(Context ctx, int v){return Math.round(v*ctx.getResources().getDisplayMetrics().density);}
}
