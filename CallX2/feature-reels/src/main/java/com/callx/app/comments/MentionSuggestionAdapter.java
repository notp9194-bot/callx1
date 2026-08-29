package com.callx.app.comments;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.utils.AvatarUrlBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated adapter for the @mention autocomplete strip (shown while
 * composing a reel comment/reply — see ReelCommentFragment#showMentionSuggestions).
 *
 * PERF/CORRECTNESS: previously this strip had no adapter at all —
 * showMentionSuggestions() called container.removeAllViews() and then
 * `new TextView(requireContext())` + addView() for every matching
 * candidate, from scratch, on every single keystroke while typing a
 * mention. That's:
 *   - Zero view recycling — up to 8 fresh TextViews inflated/allocated per
 *     character typed instead of rebinding a small fixed pool.
 *   - No avatar shown, just plain name text (worse UX than Instagram/
 *     WhatsApp-style mention dropdowns, and inconsistent with every other
 *     avatar-bearing row in this same comment screen).
 *   - Display-name casing was recovered per-suggestion via a linear scan of
 *     the entire loaded comment list (capitalizeFromCandidate) — dropped
 *     entirely now that MentionCandidate stores the resolved name directly.
 *
 * This is intentionally a small, self-contained RecyclerView.Adapter (max
 * ~8 items, no DiffUtil needed) rather than reusing ReelCommentsAdapter's
 * machinery, since a mention row's needs (avatar + name only, no likes/
 * replies/reactions) are much simpler.
 */
public class MentionSuggestionAdapter extends RecyclerView.Adapter<MentionSuggestionAdapter.VH> {

    public interface OnMentionPickedListener {
        void onMentionPicked(MentionCandidate candidate);
    }

    // 22dp view, bucketed to the shared TINY tier (32dp) so this avatar reuses
    // the same cached decode as other small avatar/cover tiles across the app.
    private static final AvatarSizeTier AVATAR_TIER = AvatarSizeTier.TINY;

    // Same lazy-cached-RequestOptions trick used by ReelCommentsAdapter's
    // avatar loading — the target size is fixed for every row, so build the
    // override() options object once instead of on every bind.
    private static volatile RequestOptions avatarRequestOptions;

    private static RequestOptions avatarRequestOptions(Context ctx) {
        RequestOptions opts = avatarRequestOptions;
        if (opts == null) {
            int sizePx = AvatarUrlBuilder.tierPx(ctx, AVATAR_TIER);
            opts = new RequestOptions().override(sizePx, sizePx);
            avatarRequestOptions = opts;
        }
        return opts;
    }

    private final List<MentionCandidate> items = new ArrayList<>();
    private OnMentionPickedListener listener;

    public void setListener(OnMentionPickedListener l) {
        this.listener = l;
    }

    /** Replaces the current suggestion set. Capped at a small fixed list
     *  (caller already limits matches to ~8), so a plain list swap +
     *  notifyDataSetChanged is cheap — no need for AsyncListDiffer here. */
    public void submitList(List<MentionCandidate> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_mention_suggestion, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MentionCandidate c = items.get(position);
        Context ctx = h.itemView.getContext();

        h.tvName.setText("@" + c.name);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onMentionPicked(c);
        });

        h.ivAvatar.setImageResource(R.drawable.ic_person);
        if (c.avatarUrl != null && !c.avatarUrl.isEmpty()) {
            String resizedUrl = AvatarUrlBuilder.build(ctx, c.avatarUrl, AVATAR_TIER);
            Glide.with(ctx).load(resizedUrl)
                .apply(avatarRequestOptions(ctx))
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(h.ivAvatar);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivAvatar;
        final TextView tvName;

        VH(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(R.id.iv_mention_avatar);
            tvName   = v.findViewById(R.id.tv_mention_name);
        }
    }
}
