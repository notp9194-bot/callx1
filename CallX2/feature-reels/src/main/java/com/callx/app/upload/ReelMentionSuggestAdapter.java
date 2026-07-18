package com.callx.app.upload;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.mention.canvas.MentionRowCanvasView;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ReelMentionSuggestAdapter — self-contained filterable adapter for the
 * @mention suggestion dropdown inside feature-reels.
 *
 * Mirrors MentionSuggestAdapter from feature-chat but lives inside
 * feature-reels to avoid a cross-module dependency on :feature-chat.
 *
 * Row layout: {@code item_mention_suggest_reel.xml} — a single
 * {@link MentionRowCanvasView} (avatar + name + "@" badge) from :core.
 */
public class ReelMentionSuggestAdapter
        extends RecyclerView.Adapter<ReelMentionSuggestAdapter.VH> {

    // ── Data model ────────────────────────────────────────────────────────

    public static class MentionItem {
        public final String uid;
        public final String name;
        public final String photoUrl;

        public MentionItem(String uid, String name, String photoUrl) {
            this.uid      = uid      != null ? uid      : "";
            this.name     = name     != null ? name     : "";
            this.photoUrl = photoUrl != null ? photoUrl : "";
        }
    }

    public interface OnMentionSelectedListener {
        void onMentionSelected(MentionItem item);
    }

    // ─────────────────────────────────────────────────────────────────────

    private final Context                    context;
    private final OnMentionSelectedListener  listener;
    private final List<MentionItem>          allItems      = new ArrayList<>();
    private final List<MentionItem>          filteredItems = new ArrayList<>();

    public ReelMentionSuggestAdapter(@NonNull Context context,
                                     @NonNull OnMentionSelectedListener listener) {
        this.context  = context;
        this.listener = listener;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Replace the full list (call when follower data arrives). */
    public void setItems(@NonNull List<MentionItem> items) {
        allItems.clear();
        allItems.addAll(items);
        filteredItems.clear();
        filteredItems.addAll(items);
        notifyDataSetChanged();
    }

    /**
     * Filter by query typed after "@".
     * Uses contains-match so "@ali" matches "Malik Ali", "Aaliyah", etc.
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
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_mention_suggest_reel, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MentionItem item = filteredItems.get(position);
        h.rowView.setName(item.name);
        h.rowView.setAvatarUrl(item.photoUrl.isEmpty() ? null : item.photoUrl);
        h.itemView.setOnClickListener(v -> listener.onMentionSelected(item));
    }

    @Override
    public int getItemCount() { return filteredItems.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        final MentionRowCanvasView rowView;

        VH(@NonNull View v) {
            super(v);
            rowView = (MentionRowCanvasView) v;
        }
    }
}
