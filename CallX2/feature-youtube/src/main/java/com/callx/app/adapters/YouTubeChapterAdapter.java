package com.callx.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.models.YouTubeChapter;
import com.callx.app.youtube.R;
import java.util.List;

/** Horizontal chapters strip shown below the player. */
public class YouTubeChapterAdapter
    extends RecyclerView.Adapter<YouTubeChapterAdapter.ChVH> {

    public interface OnChapterClick { void onClick(YouTubeChapter chapter); }

    private final List<YouTubeChapter> data;
    private final OnChapterClick       click;

    public YouTubeChapterAdapter(List<YouTubeChapter> data, OnChapterClick click) {
        this.data = data; this.click = click;
    }

    @NonNull @Override
    public ChVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(p.getContext())
            .inflate(R.layout.item_yt_chapter, p, false);
        return new ChVH(v);
    }

    @Override public void onBindViewHolder(@NonNull ChVH h, int pos) {
        YouTubeChapter c = data.get(pos);
        h.tvTitle.setText(c.title);
        h.tvTime.setText(c.getFormattedTime());
        h.itemView.setOnClickListener(v -> click.onClick(c));
    }

    @Override public int getItemCount() { return data.size(); }

    static class ChVH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvTime;
        ChVH(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tv_yt_chapter_title);
            tvTime  = v.findViewById(R.id.tv_yt_chapter_time);
        }
    }
}
