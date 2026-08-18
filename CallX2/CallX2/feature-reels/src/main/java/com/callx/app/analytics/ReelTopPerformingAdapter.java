package com.callx.app.analytics;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import java.util.List;
import java.util.Locale;

public class ReelTopPerformingAdapter extends RecyclerView.Adapter<ReelTopPerformingAdapter.ViewHolder> {
    private final List<ReelMetric> items;

    public static class ReelMetric {
        public String id, thumbUrl;
        public int views;
        public float engagementRate;
        public long durationMs;
    }

    public ReelTopPerformingAdapter(List<ReelMetric> items) { this.items = items; }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_top_performing_reel, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ReelMetric m = items.get(position);
        Glide.with(holder.itemView).load(m.thumbUrl).into(holder.ivThumb);
        holder.tvViews.setText(String.format(Locale.US, "%,d views", m.views));
        holder.tvEngage.setText(String.format(Locale.US, "%.1f%% engagement", m.engagementRate));
        long sec = m.durationMs / 1000;
        holder.tvDur.setText(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));
    }

    @Override public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumb; TextView tvViews, tvEngage, tvDur;
        ViewHolder(View v) {
            super(v);
            ivThumb = v.findViewById(R.id.iv_thumbnail);
            tvViews = v.findViewById(R.id.tv_views);
            tvEngage = v.findViewById(R.id.tv_engagement);
            tvDur = v.findViewById(R.id.tv_duration);
        }
    }
}
