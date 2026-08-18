package com.callx.app.search;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.R;

import java.util.ArrayList;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * Rows used by the tab search surface.
 *
 * A single adapter intentionally handles recent searches, people, hashtags,
 * and reel suggestions so the search screen can move between the Instagram
 * style recent state and live results without replacing the RecyclerView.
 */
public class SearchSuggestionAdapter
        extends RecyclerView.Adapter<SearchSuggestionAdapter.SuggestionHolder> {

    public interface Listener {
        void onSuggestionClicked(SearchFragment.SearchSuggestion suggestion);
        void onRemoveClicked(SearchFragment.SearchSuggestion suggestion);
    }

    private final List<SearchFragment.SearchSuggestion> items = new ArrayList<>();
    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<SearchFragment.SearchSuggestion> nextItems) {
        items.clear();
        if (nextItems != null) items.addAll(nextItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SuggestionHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_suggestion, parent, false);
        return new SuggestionHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SuggestionHolder holder, int position) {
        SearchFragment.SearchSuggestion item = items.get(position);
        holder.primary.setText(item.primary);
        holder.secondary.setText(item.secondary == null ? "" : item.secondary);
        holder.secondary.setVisibility(item.secondary == null || item.secondary.isEmpty()
                ? View.GONE : View.VISIBLE);

        holder.remove.setVisibility(item.removeable ? View.VISIBLE : View.GONE);
        holder.remove.setOnClickListener(v -> {
            if (listener != null) listener.onRemoveClicked(item);
        });
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSuggestionClicked(item);
        });

        if (item.kind == SearchFragment.SearchSuggestion.KIND_HASHTAG) {
            holder.avatar.setVisibility(View.GONE);
            holder.kindIcon.setVisibility(View.VISIBLE);
            holder.kindIcon.setImageResource(R.drawable.ic_search);
            holder.kindIcon.setColorFilter(Color.rgb(76, 175, 80));
        } else if (item.kind == SearchFragment.SearchSuggestion.KIND_RECENT) {
            holder.avatar.setVisibility(View.GONE);
            holder.kindIcon.setVisibility(View.VISIBLE);
            holder.kindIcon.setImageResource(R.drawable.ic_history);
            holder.kindIcon.clearColorFilter();
        } else if (item.kind == SearchFragment.SearchSuggestion.KIND_REEL) {
            holder.avatar.setVisibility(View.VISIBLE);
            holder.kindIcon.setVisibility(View.GONE);
            Glide.with(holder.avatar)
                    .load(item.avatar)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_reels)
                    .error(R.drawable.ic_reels)
                    .centerCrop()
                    .into(holder.avatar);
        } else {
            holder.avatar.setVisibility(View.VISIBLE);
            holder.kindIcon.setVisibility(View.GONE);
            Glide.with(holder.avatar)
                    .load(item.avatar)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .circleCrop()
                    .into(holder.avatar);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class SuggestionHolder extends RecyclerView.ViewHolder {
        final CircleImageView avatar;
        final ImageView kindIcon;
        final TextView primary;
        final TextView secondary;
        final ImageButton remove;

        SuggestionHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.iv_suggestion_avatar);
            kindIcon = itemView.findViewById(R.id.iv_suggestion_kind);
            primary = itemView.findViewById(R.id.tv_suggestion_primary);
            secondary = itemView.findViewById(R.id.tv_suggestion_secondary);
            remove = itemView.findViewById(R.id.btn_remove_search);
        }
    }
}