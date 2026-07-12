package com.callx.app.chat.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.mention.canvas.MentionRowCanvasView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MentionSuggestAdapter — filterable list of members shown when the user
 * types "@" in the message input.
 *
 * Row layout: circular avatar + display name (drawn together by
 * MentionRowCanvasView — see its class doc; the row went from 3 child
 * Views to 1 because filter() re-binds every visible row on every
 * keystroke, not just when the dropdown first opens).
 *
 * Filtering uses <b>contains</b>-matching (not just prefix) for a more
 * natural UX — "@ali" matches "Malik Ali", "Aaliyah", etc.
 */
public class MentionSuggestAdapter
        extends RecyclerView.Adapter<MentionSuggestAdapter.VH> {

    // ── Data ──────────────────────────────────────────────────────────────

    /** One entry in the suggestion list. */
    public static class MentionItem {
        public final String uid;
        public final String name;
        public final String photoUrl;

        public MentionItem(String uid, String name, String photoUrl) {
            this.uid      = uid != null ? uid : "";
            this.name     = name != null ? name : "";
            this.photoUrl = photoUrl;
        }
    }

    public interface OnMentionSelectedListener {
        void onMentionSelected(MentionItem item);
    }

    // ─────────────────────────────────────────────────────────────────────

    private final Context                   context;
    private final OnMentionSelectedListener listener;
    private final List<MentionItem>         allItems      = new ArrayList<>();
    private final List<MentionItem>         filteredItems = new ArrayList<>();

    public MentionSuggestAdapter(@NonNull Context context,
                                 @NonNull OnMentionSelectedListener listener) {
        this.context  = context;
        this.listener = listener;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Replaces the full member list (call when group membership changes). */
    public void setItems(@NonNull List<MentionItem> items) {
        allItems.clear();
        allItems.addAll(items);
        filteredItems.clear();
        filteredItems.addAll(items);
        notifyDataSetChanged();
    }

    /**
     * Filter suggestions by prefix typed after "@".
     * Uses <b>contains</b>-match so "@ali" matches "Malik Ali", "Aaliyah".
     * Empty / null → show all.
     */
    public void filter(String prefix) {
        filteredItems.clear();
        if (prefix == null || prefix.isEmpty()) {
            filteredItems.addAll(allItems);
        } else {
            String lp = prefix.toLowerCase(Locale.getDefault());
            for (MentionItem item : allItems) {
                if (item.name.toLowerCase(Locale.getDefault()).contains(lp)) {
                    filteredItems.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    // ── RecyclerView ──────────────────────────────────────────────────────

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(context)
                .inflate(R.layout.item_mention_suggest, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MentionItem item = filteredItems.get(position);
        h.rowView.setName(item.name);
        h.rowView.setAvatarUrl(item.photoUrl != null && !item.photoUrl.isEmpty() ? item.photoUrl : null);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMentionSelected(item);
        });
    }

    @Override public int getItemCount() { return filteredItems.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        final MentionRowCanvasView rowView;

        VH(@NonNull View v) {
            super(v);
            rowView = (MentionRowCanvasView) v;
        }
    }
}
