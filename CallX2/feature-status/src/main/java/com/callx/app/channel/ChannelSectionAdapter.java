package com.callx.app.channel;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.status.R;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;
import java.util.function.Consumer;

/**
 * ChannelSectionAdapter v2 — WhatsApp-level architecture.
 *
 * CHANGED: Now uses ChannelEntity (from core DB) instead of Channel model.
 * UI receives data from ChannelViewModel → LiveData<List<ChannelEntity>>
 * and calls setFollowedChannels / setSuggestedChannels which rebuild the flat list.
 *
 * Item types:
 *   0 — Section header  ("Channels" + "Explore" button)
 *   1 — Followed channel row
 *   2 — "Find channels to follow" collapsible header
 *   3 — Suggested channel row  (Follow + dismiss X)
 *   4 — Footer ("Explore more" + "+ Create channel")
 */
public class ChannelSectionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SECTION_HDR  = 0;
    private static final int TYPE_FOLLOWED     = 1;
    private static final int TYPE_SUGGEST_HDR  = 2;
    private static final int TYPE_SUGGESTED    = 3;
    private static final int TYPE_FOOTER       = 4;

    // ── State ─────────────────────────────────────────────────────────────
    private List<ChannelEntity> followed  = new ArrayList<>();
    private List<ChannelEntity> suggested = new ArrayList<>();
    private final Set<String>   dismissedIds = new HashSet<>();
    private boolean suggestExpanded = true;

    // ── Callbacks (wired from StatusFragment via ChannelViewModel) ────────
    public Runnable              onExploreClick;
    public Consumer<ChannelEntity> onChannelClick;
    public Consumer<ChannelEntity> onFollowClick;
    public Consumer<ChannelEntity> onUnfollowClick;
    public Consumer<ChannelEntity> onDismissSuggested;
    public Runnable              onCreateChannelClick;
    public Runnable              onExploreMoreClick;

    // ── Flat item list ─────────────────────────────────────────────────────
    private final List<Object> items = new ArrayList<>();

    public ChannelSectionAdapter() {}

    public void setFollowedChannels(List<ChannelEntity> list) {
        this.followed = list != null ? list : new ArrayList<>();
        rebuild();
    }

    public void setSuggestedChannels(List<ChannelEntity> list) {
        this.suggested = list != null ? list : new ArrayList<>();
        rebuild();
    }

    private void rebuild() {
        items.clear();
        items.add("HEADER");
        items.addAll(followed);

        Set<String> followedIds = new HashSet<>();
        for (ChannelEntity c : followed) if (c.id != null) followedIds.add(c.id);

        List<ChannelEntity> filteredSug = new ArrayList<>();
        for (ChannelEntity c : suggested) {
            if (!dismissedIds.contains(c.id) && !followedIds.contains(c.id))
                filteredSug.add(c);
        }
        if (!filteredSug.isEmpty()) {
            items.add("SUGGEST_HDR");
            if (suggestExpanded) items.addAll(filteredSug);
        }
        items.add("FOOTER");
        notifyDataSetChanged();
    }

    @Override public int getItemViewType(int pos) {
        Object o = items.get(pos);
        if ("HEADER".equals(o))      return TYPE_SECTION_HDR;
        if ("SUGGEST_HDR".equals(o)) return TYPE_SUGGEST_HDR;
        if ("FOOTER".equals(o))      return TYPE_FOOTER;
        ChannelEntity ch = (ChannelEntity) o;
        return ch.isFollowed ? TYPE_FOLLOWED : TYPE_SUGGESTED;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
        LayoutInflater li = LayoutInflater.from(parent.getContext());
        switch (vt) {
            case TYPE_SECTION_HDR:
                return new SectionHdrVH(li.inflate(R.layout.item_channel_header, parent, false));
            case TYPE_FOLLOWED:
                return new FollowedVH(li.inflate(R.layout.item_channel_followed, parent, false));
            case TYPE_SUGGEST_HDR:
                return new SuggestHdrVH(li.inflate(R.layout.item_channel_suggest_header, parent, false));
            case TYPE_SUGGESTED:
                return new SuggestedVH(li.inflate(R.layout.item_channel_suggested, parent, false));
            default:
                return new FooterVH(li.inflate(R.layout.item_channel_footer, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        Context ctx = holder.itemView.getContext();
        if (holder instanceof SectionHdrVH) {
            ((SectionHdrVH) holder).btnExplore.setOnClickListener(v -> {
                if (onExploreClick != null) onExploreClick.run();
            });
        } else if (holder instanceof FollowedVH) {
            bindFollowed((FollowedVH) holder, (ChannelEntity) items.get(pos), ctx);
        } else if (holder instanceof SuggestHdrVH) {
            SuggestHdrVH h = (SuggestHdrVH) holder;
            h.ivChevron.setRotation(suggestExpanded ? 180f : 0f);
            h.itemView.setOnClickListener(v -> { suggestExpanded = !suggestExpanded; rebuild(); });
        } else if (holder instanceof SuggestedVH) {
            bindSuggested((SuggestedVH) holder, (ChannelEntity) items.get(pos), ctx);
        } else if (holder instanceof FooterVH) {
            FooterVH h = (FooterVH) holder;
            h.btnExploreMore.setOnClickListener(v -> {
                if (onExploreMoreClick != null) onExploreMoreClick.run();
                else if (onExploreClick != null) onExploreClick.run();
            });
            h.btnCreate.setOnClickListener(v -> {
                if (onCreateChannelClick != null) onCreateChannelClick.run();
            });
        }
    }

    private void bindFollowed(FollowedVH h, ChannelEntity ch, Context ctx) {
        h.tvName.setText(ch.name != null ? ch.name : "");
        h.tvVerified.setVisibility(ch.verified ? View.VISIBLE : View.GONE);
        String preview = "";
        if ("image".equals(ch.lastPostType))      preview = "\uD83D\uDCF7 Photo";
        else if ("video".equals(ch.lastPostType)) preview = "\uD83C\uDFA5 Video";
        else if (ch.lastPostText != null && !ch.lastPostText.isEmpty()) preview = ch.lastPostText;
        h.tvLastPost.setText(preview);
        if (ch.lastPostAt > 0) {
            h.tvTime.setText(DateUtils.getRelativeTimeSpanString(
                    ch.lastPostAt, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
        } else { h.tvTime.setText(""); }
        loadAvatar(ctx, ch.iconUrl, h.ivIcon);
        h.itemView.setOnClickListener(v -> { if (onChannelClick != null) onChannelClick.accept(ch); });
    }

    private void bindSuggested(SuggestedVH h, ChannelEntity ch, Context ctx) {
        h.tvName.setText(ch.name != null ? ch.name : "");
        h.tvVerified.setVisibility(ch.verified ? View.VISIBLE : View.GONE);
        // Format followers
        long f = ch.followers;
        String fs = f >= 1_000_000L ? String.format("%.1fM", f/1_000_000.0)
                  : f >= 1_000L     ? String.format("%.1fK", f/1_000.0)
                  : String.valueOf(f);
        h.tvFollowers.setText(fs);
        loadAvatar(ctx, ch.iconUrl, h.ivIcon);
        h.btnFollow.setOnClickListener(v -> { if (onFollowClick != null) onFollowClick.accept(ch); });
        h.btnDismiss.setOnClickListener(v -> {
            if (ch.id != null) dismissedIds.add(ch.id);
            if (onDismissSuggested != null) onDismissSuggested.accept(ch);
            rebuild();
        });
    }

    private void loadAvatar(Context ctx, String url, CircleImageView iv) {
        if (url != null && !url.isEmpty()) {
            Glide.with(ctx).load(url)
                .placeholder(R.drawable.bg_channel_avatar_default)
                .circleCrop().override(96, 96).into(iv);
        } else {
            iv.setImageResource(R.drawable.bg_channel_avatar_default);
        }
    }

    // ── ViewHolders ───────────────────────────────────────────────────────
    static class SectionHdrVH extends RecyclerView.ViewHolder {
        Button btnExplore;
        SectionHdrVH(View v) { super(v); btnExplore = v.findViewById(R.id.btn_channel_explore); }
    }
    static class FollowedVH extends RecyclerView.ViewHolder {
        CircleImageView ivIcon;
        TextView tvName, tvVerified, tvLastPost, tvTime;
        FollowedVH(View v) {
            super(v);
            ivIcon     = v.findViewById(R.id.iv_channel_icon);
            tvName     = v.findViewById(R.id.tv_channel_name);
            tvVerified = v.findViewById(R.id.tv_channel_verified);
            tvLastPost = v.findViewById(R.id.tv_channel_last_post);
            tvTime     = v.findViewById(R.id.tv_channel_time);
        }
    }
    static class SuggestHdrVH extends RecyclerView.ViewHolder {
        ImageView ivChevron;
        SuggestHdrVH(View v) { super(v); ivChevron = v.findViewById(R.id.iv_suggest_chevron); }
    }
    static class SuggestedVH extends RecyclerView.ViewHolder {
        CircleImageView ivIcon;
        TextView tvName, tvVerified, tvFollowers;
        Button btnFollow;
        ImageButton btnDismiss;
        SuggestedVH(View v) {
            super(v);
            ivIcon      = v.findViewById(R.id.iv_channel_icon);
            tvName      = v.findViewById(R.id.tv_channel_name);
            tvVerified  = v.findViewById(R.id.tv_channel_verified);
            tvFollowers = v.findViewById(R.id.tv_channel_followers);
            btnFollow   = v.findViewById(R.id.btn_channel_follow);
            btnDismiss  = v.findViewById(R.id.btn_channel_dismiss);
        }
    }
    static class FooterVH extends RecyclerView.ViewHolder {
        Button btnExploreMore, btnCreate;
        FooterVH(View v) {
            super(v);
            btnExploreMore = v.findViewById(R.id.btn_explore_more);
            btnCreate      = v.findViewById(R.id.btn_create_channel);
        }
    }
}
