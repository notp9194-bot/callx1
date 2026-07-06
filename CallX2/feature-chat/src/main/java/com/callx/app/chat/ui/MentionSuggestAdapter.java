package com.callx.app.chat.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;

import java.util.ArrayList;
import java.util.List;

/**
 * MentionSuggestAdapter — shows a filterable list of group members (or the
 * partner in 1:1 chat) when the user types "@" in the message input.
 *
 * Each row: circular avatar + display name.
 * Tap → {@link OnMentionSelectedListener#onMentionSelected(MentionItem)} fires
 * so the controller can insert "@Name " into the EditText.
 */
public class MentionSuggestAdapter
        extends RecyclerView.Adapter<MentionSuggestAdapter.VH> {

    /** One entry in the suggestion list. */
    public static class MentionItem {
        public final String uid;
        public final String name;
        public final String photoUrl;

        public MentionItem(String uid, String name, String photoUrl) {
            this.uid      = uid;
            this.name     = name != null ? name : "";
            this.photoUrl = photoUrl;
        }
    }

    public interface OnMentionSelectedListener {
        void onMentionSelected(MentionItem item);
    }

    private final Context context;
    private final OnMentionSelectedListener listener;
    private final List<MentionItem> allItems      = new ArrayList<>();
    private final List<MentionItem> filteredItems = new ArrayList<>();

    public MentionSuggestAdapter(@NonNull Context context,
                                 @NonNull OnMentionSelectedListener listener) {
        this.context  = context;
        this.listener = listener;
    }

    /** Replace the full member list (call when group membership changes). */
    public void setItems(@NonNull List<MentionItem> items) {
        allItems.clear();
        allItems.addAll(items);
        filteredItems.clear();
        filteredItems.addAll(items);
        notifyDataSetChanged();
    }

    /**
     * Filter the list by what the user has typed after "@".
     * Empty / null prefix → show all.
     */
    public void filter(String prefix) {
        filteredItems.clear();
        if (prefix == null || prefix.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String lp = prefix.toLowerCase(java.util.Locale.getDefault());
            for (MentionItem item : allItems) {
                if (item.name.toLowerCase(java.util.Locale.getDefault()).startsWith(lp)) {
                    filteredItems.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_mention_suggest, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MentionItem item = filteredItems.get(position);

        h.tvName.setText(item.name);

        if (item.photoUrl != null && !item.photoUrl.isEmpty()) {
            Glide.with(context)
                    .load(item.photoUrl)
                    .placeholder(R.drawable.ic_person)
                    .circleCrop()
                    .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMentionSelected(item);
        });
    }

    @Override
    public int getItemCount() {
        return filteredItems.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView  tvName;

        VH(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_mention_avatar);
            tvName   = v.findViewById(R.id.tv_mention_name);
        }
    }
}
