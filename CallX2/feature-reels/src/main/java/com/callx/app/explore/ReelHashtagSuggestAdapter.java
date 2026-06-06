package com.callx.app.explore;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.util.List;

/**
 * ReelHashtagSuggestAdapter — Horizontal scrollable hashtag chips.
 * Used in ReelSearchActivity as trending hashtag suggestions.
 */
public class ReelHashtagSuggestAdapter extends RecyclerView.Adapter<ReelHashtagSuggestAdapter.VH> {

    public interface OnHashtagClickListener {
        void onHashtagClick(String hashtag);
    }

    private final Context                context;
    private final List<String>           hashtags;
    private final OnHashtagClickListener listener;

    public ReelHashtagSuggestAdapter(Context context, List<String> hashtags,
                                     OnHashtagClickListener listener) {
        this.context   = context;
        this.hashtags  = hashtags;
        this.listener  = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
            .inflate(R.layout.item_hashtag_chip, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        String tag = hashtags.get(position);
        h.tvHashtag.setText("#" + tag);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onHashtagClick(tag);
        });
    }

    @Override
    public int getItemCount() { return hashtags.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvHashtag;
        VH(@NonNull View v) {
            super(v);
            tvHashtag = v.findViewById(R.id.tv_hashtag);
        }
    }
}
