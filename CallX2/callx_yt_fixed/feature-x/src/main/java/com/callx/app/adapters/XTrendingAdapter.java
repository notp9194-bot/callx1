package com.callx.app.adapters;

import android.content.Context;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.models.XTrendingTopic;
import com.callx.app.x.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class XTrendingAdapter extends RecyclerView.Adapter<XTrendingAdapter.TopicVH> {

    public interface OnTopicClickListener {
        void onTopicClick(XTrendingTopic topic);
    }

    private final Context ctx;
    private final List<XTrendingTopic> topics = new ArrayList<>();
    private final OnTopicClickListener listener;

    public XTrendingAdapter(Context ctx, OnTopicClickListener listener) {
        this.ctx = ctx; this.listener = listener;
    }

    public void setTopics(List<XTrendingTopic> list) {
        topics.clear(); topics.addAll(list); notifyDataSetChanged();
    }

    @NonNull @Override
    public TopicVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TopicVH(LayoutInflater.from(ctx)
            .inflate(R.layout.item_x_trending_topic, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull TopicVH h, int pos) {
        h.bind(pos + 1, topics.get(pos));
    }
    @Override public int getItemCount() { return topics.size(); }

    class TopicVH extends RecyclerView.ViewHolder {
        TextView tvRank, tvTag, tvCount;
        TopicVH(View v) {
            super(v);
            tvRank  = v.findViewById(R.id.tv_trending_rank);
            tvTag   = v.findViewById(R.id.tv_trending_tag);
            tvCount = v.findViewById(R.id.tv_trending_count);
        }
        void bind(int rank, XTrendingTopic topic) {
            tvRank.setText(String.valueOf(rank));
            tvTag.setText(topic.displayTag);
            long c = topic.count24h;
            if (c <= 0) c = topic.countAll;
            String countText = c >= 1_000_000
                ? String.format(Locale.US, "%.1fM posts", c / 1_000_000.0)
                : c >= 1_000
                    ? String.format(Locale.US, "%.1fK posts", c / 1_000.0)
                    : c + (c == 1 ? " post" : " posts");
            tvCount.setText(countText + " · Trending");
            itemView.setOnClickListener(v -> { if (listener != null) listener.onTopicClick(topic); });
        }
    }
}
