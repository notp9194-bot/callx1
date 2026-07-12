package com.callx.app.channels;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.status.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Simple post list for {@link ChannelViewActivity} — sample broadcast text posts. */
public class ChannelPostsAdapter extends RecyclerView.Adapter<ChannelPostsAdapter.VH> {

    private final List<String> posts;
    private final long baseTimeMillis;

    public ChannelPostsAdapter(List<String> posts, long baseTimeMillis) {
        this.posts = posts;
        this.baseTimeMillis = baseTimeMillis;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_post, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        h.tvText.setText(posts.get(pos));
        // Older posts get progressively earlier timestamps, newest last (like a chat feed).
        long ts = baseTimeMillis - (long) (posts.size() - 1 - pos) * 3_600_000L;
        h.tvTime.setText(new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ts)));
    }

    @Override public int getItemCount() { return posts.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvText, tvTime;
        VH(View v) {
            super(v);
            tvText = v.findViewById(R.id.tv_post_text);
            tvTime = v.findViewById(R.id.tv_post_time);
        }
    }
}
