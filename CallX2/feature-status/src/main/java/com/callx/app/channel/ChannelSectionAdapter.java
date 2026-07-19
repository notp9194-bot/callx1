package com.callx.app.channel;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.status.R;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;
import java.util.function.Consumer;

/**
 * ChannelSectionAdapter — WhatsApp-level Updates-tab adapter.
 *
 * Item types:
 *   0 — Section header  ("Channels" + "Explore" button)
 *   1 — Followed channel row (with unread badge, muted icon, last post preview)
 *   2 — "Find channels to follow" collapsible header
 *   3 — Suggested channel row  (Follow + dismiss X)
 *   4 — Footer ("Explore more" + "+ Create channel")
 *
 * New in WhatsApp-level upgrade:
 *   - Unread count badge on followed channel rows
 *   - Muted bell icon on muted channels
 *   - Long-press on followed channel → mute/unmute/notification settings
 */
public class ChannelSectionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SECTION_HDR  = 0;
    private static final int TYPE_FOLLOWED     = 1;
    private static final int TYPE_SUGGEST_HDR  = 2;
    private static final int TYPE_SUGGESTED    = 3;
    private static final int TYPE_FOOTER       = 4;

    private List<ChannelEntity> followed  = new ArrayList<>();
    private List<ChannelEntity> suggested = new ArrayList<>();
    private final Set<String>   dismissedIds = new HashSet<>();
    private boolean suggestExpanded = true;

    // Callbacks (wired from StatusFragment via ChannelViewModel)
    public Runnable                onExploreClick;
    public Consumer<ChannelEntity> onChannelClick;
    public Consumer<ChannelEntity> onFollowClick;
    public Consumer<ChannelEntity> onUnfollowClick;
    public Consumer<ChannelEntity> onDismissSuggested;
    public Consumer<ChannelEntity> onMuteClick;
    public Consumer<ChannelEntity> onUnmuteClick;
    public Consumer<ChannelEntity> onNotifSettingsClick;
    public Runnable                onCreateChannelClick;
    public Runnable                onExploreMoreClick;

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
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_SECTION_HDR: return new SectionHdrVH(inf.inflate(R.layout.item_channel_header, parent, false));
            case TYPE_FOLLOWED:    return new FollowedVH(inf.inflate(R.layout.item_channel_followed, parent, false));
            case TYPE_SUGGEST_HDR: return new SuggestHdrVH(inf.inflate(R.layout.item_channel_suggest_header, parent, false));
            case TYPE_SUGGESTED:   return new SuggestedVH(inf.inflate(R.layout.item_channel_suggested, parent, false));
            default:               return new FooterVH(inf.inflate(R.layout.item_channel_footer, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        int type = getItemViewType(pos);

        if (type == TYPE_SECTION_HDR) {
            SectionHdrVH h = (SectionHdrVH) holder;
            if (h.btnExplore != null && onExploreClick != null)
                h.btnExplore.setOnClickListener(v -> onExploreClick.run());
            return;
        }

        if (type == TYPE_SUGGEST_HDR) {
            SuggestHdrVH h = (SuggestHdrVH) holder;
            h.itemView.setOnClickListener(v -> {
                suggestExpanded = !suggestExpanded;
                if (h.ivChevron != null)
                    h.ivChevron.setRotation(suggestExpanded ? 0f : -90f);
                rebuild();
            });
            if (h.ivChevron != null) h.ivChevron.setRotation(suggestExpanded ? 0f : -90f);
            return;
        }

        if (type == TYPE_FOOTER) {
            FooterVH h = (FooterVH) holder;
            if (h.btnExploreMore != null && onExploreMoreClick != null)
                h.btnExploreMore.setOnClickListener(v -> onExploreMoreClick.run());
            if (h.btnCreate != null && onCreateChannelClick != null)
                h.btnCreate.setOnClickListener(v -> onCreateChannelClick.run());
            return;
        }

        // Followed or Suggested
        ChannelEntity ch = (ChannelEntity) items.get(pos);

        if (type == TYPE_FOLLOWED) {
            FollowedVH h = (FollowedVH) holder;
            loadIcon(holder.itemView.getContext(), ch.iconUrl, h.ivIcon);
            h.tvName.setText(ch.name != null ? ch.name : "");
            h.tvVerified.setVisibility(ch.verified ? View.VISIBLE : View.GONE);

            // Muted icon
            if (h.ivMuted != null) h.ivMuted.setVisibility(ch.isMuted ? View.VISIBLE : View.GONE);

            // Unread badge
            if (h.tvUnread != null) {
                if (ch.unreadCount > 0) {
                    h.tvUnread.setVisibility(View.VISIBLE);
                    h.tvUnread.setText(ch.unreadCount > 99 ? "99+" : String.valueOf(ch.unreadCount));
                } else {
                    h.tvUnread.setVisibility(View.GONE);
                }
            }

            // Last post preview
            if (h.tvLastPost != null) {
                String preview = buildLastPostPreview(ch);
                h.tvLastPost.setText(preview);
                h.tvLastPost.setVisibility(preview.isEmpty() ? View.GONE : View.VISIBLE);
            }

            // Time
            if (h.tvTime != null && ch.lastPostAt > 0) {
                h.tvTime.setText(DateUtils.getRelativeTimeSpanString(ch.lastPostAt,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE));
                h.tvTime.setVisibility(View.VISIBLE);
            } else if (h.tvTime != null) {
                h.tvTime.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(v -> {
                if (onChannelClick != null) onChannelClick.accept(ch);
            });

            // Long press → context menu
            h.itemView.setOnLongClickListener(v -> {
                showFollowedContextMenu(holder.itemView.getContext(), ch);
                return true;
            });

        } else { // TYPE_SUGGESTED
            SuggestedVH h = (SuggestedVH) holder;
            loadIcon(holder.itemView.getContext(), ch.iconUrl, h.ivIcon);
            h.tvName.setText(ch.name != null ? ch.name : "");
            h.tvVerified.setVisibility(ch.verified ? View.VISIBLE : View.GONE);

            long f = ch.followers;
            h.tvFollowers.setText(f >= 1_000_000L ? String.format("%.1fM", f/1_000_000.0)
                : f >= 1_000L ? String.format("%.1fK", f/1_000.0)
                : String.valueOf(f));

            h.btnFollow.setText(ch.isFollowed ? "Following" : "Follow");
            h.btnFollow.setAlpha(ch.isFollowed ? 0.6f : 1.0f);

            h.btnFollow.setOnClickListener(v -> {
                if (!ch.isFollowed) { if (onFollowClick != null) onFollowClick.accept(ch); }
                else { if (onUnfollowClick != null) onUnfollowClick.accept(ch); }
            });

            h.btnDismiss.setVisibility(View.VISIBLE);
            h.btnDismiss.setOnClickListener(v -> {
                if (ch.id != null) dismissedIds.add(ch.id);
                if (onDismissSuggested != null) onDismissSuggested.accept(ch);
                rebuild();
            });

            h.itemView.setOnClickListener(v -> {
                if (onChannelClick != null) onChannelClick.accept(ch);
            });
        }
    }

    private void showFollowedContextMenu(Context ctx, ChannelEntity ch) {
        List<String> options = new ArrayList<>();
        options.add(ch.isMuted ? "Unmute" : "Mute");
        options.add("Notification settings");
        options.add("Unfollow");
        String[] arr = options.toArray(new String[0]);
        new android.app.AlertDialog.Builder(ctx)
            .setTitle(ch.name)
            .setItems(arr, (d, which) -> {
                switch (arr[which]) {
                    case "Mute":
                        if (onMuteClick != null) onMuteClick.accept(ch); break;
                    case "Unmute":
                        if (onUnmuteClick != null) onUnmuteClick.accept(ch); break;
                    case "Notification settings":
                        if (onNotifSettingsClick != null) onNotifSettingsClick.accept(ch); break;
                    case "Unfollow":
                        if (onUnfollowClick != null) onUnfollowClick.accept(ch); break;
                }
            })
            .show();
    }

    private String buildLastPostPreview(ChannelEntity ch) {
        if (ch.lastPostText != null && !ch.lastPostText.isEmpty()) return ch.lastPostText;
        if (ch.lastPostType != null) {
            switch (ch.lastPostType) {
                case "image":    return "📷 Photo";
                case "video":    return "🎬 Video";
                case "link":     return "🔗 Link";
                case "poll":     return "📊 Poll";
                case "audio":    return "🎵 Voice message";
                case "document": return "📄 Document";
            }
        }
        return "";
    }

    private void loadIcon(Context ctx, String iconUrl, CircleImageView iv) {
        if (iconUrl != null && !iconUrl.isEmpty()) {
            Glide.with(ctx).load(iconUrl)
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
        TextView tvName, tvVerified, tvLastPost, tvTime, tvUnread;
        ImageView ivMuted;
        FollowedVH(View v) {
            super(v);
            ivIcon     = v.findViewById(R.id.iv_channel_icon);
            tvName     = v.findViewById(R.id.tv_channel_name);
            tvVerified = v.findViewById(R.id.tv_channel_verified);
            tvLastPost = v.findViewById(R.id.tv_channel_last_post);
            tvTime     = v.findViewById(R.id.tv_channel_time);
            tvUnread   = v.findViewById(R.id.tv_channel_unread_badge);
            ivMuted    = v.findViewById(R.id.iv_channel_muted);
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
