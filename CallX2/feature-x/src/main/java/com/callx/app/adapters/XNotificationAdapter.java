package com.callx.app.adapters;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.activities.XProfileActivity;
import com.callx.app.activities.XTweetDetailActivity;
import com.callx.app.models.XNotification;
import com.callx.app.x.R;
import java.text.SimpleDateFormat;
import java.util.*;

public class XNotificationAdapter extends RecyclerView.Adapter<XNotificationAdapter.VH> {

    private final Context ctx;
    private final List<XNotification> items = new ArrayList<>();

    public XNotificationAdapter(Context ctx) { this.ctx = ctx; }

    public void setItems(List<XNotification> list) {
        items.clear(); items.addAll(list); notifyDataSetChanged();
    }

    public void addItem(XNotification n) { items.add(0, n); notifyItemInserted(0); }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(ctx).inflate(R.layout.item_x_notification, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(items.get(pos)); }
    @Override public int getItemCount() { return items.size(); }

    class VH extends RecyclerView.ViewHolder {
        LinearLayout llRoot;
        View vUnreadBar;
        ImageView ivAvatar, ivType;
        TextView tvTitle, tvSnippet, tvTime;

        VH(View v) {
            super(v);
            llRoot    = v.findViewById(R.id.ll_xn_root);
            vUnreadBar= v.findViewById(R.id.v_xn_unread_bar);
            ivAvatar  = v.findViewById(R.id.iv_xn_avatar);
            ivType    = v.findViewById(R.id.iv_xn_type);
            tvTitle   = v.findViewById(R.id.tv_xn_title);
            tvSnippet = v.findViewById(R.id.tv_xn_snippet);
            tvTime    = v.findViewById(R.id.tv_xn_time);
        }

        void bind(XNotification n) {
            // Unread highlight
            boolean unread = !Boolean.TRUE.equals(n.read);
            if (vUnreadBar != null)
                vUnreadBar.setVisibility(unread ? View.VISIBLE : View.GONE);
            if (llRoot != null) {
                llRoot.setBackgroundColor(unread
                    ? ctx.getColor(R.color.x_notif_unread_bg)
                    : android.graphics.Color.TRANSPARENT);
            }

            // Avatar — prefer thumb for small circular avatars
            String avatarUrl = (n.fromThumbUrl != null && !n.fromThumbUrl.isEmpty())
                ? n.fromThumbUrl : n.fromPhotoUrl;
            Glide.with(ctx).load(avatarUrl).circleCrop()
                .placeholder(R.drawable.ic_person).into(ivAvatar);

            // Type icon + tint
            ivType.setImageResource(iconForType(n.type));
            ivType.setColorFilter(tintForType(n.type));

            // Text
            tvTitle.setText(buildTitle(n));
            if (n.tweetSnippet != null && !n.tweetSnippet.isEmpty()) {
                tvSnippet.setVisibility(View.VISIBLE);
                tvSnippet.setText(n.tweetSnippet);
            } else {
                tvSnippet.setVisibility(View.GONE);
            }

            // Bold title for unread
            tvTitle.setTypeface(null, unread
                ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);

            // Time
            tvTime.setText(formatTime(n.timestamp));

            // Tap — navigate to tweet or profile
            itemView.setOnClickListener(v -> {
                // Animate background away on tap
                animateClearUnread();
                if (n.tweetId != null && !n.tweetId.isEmpty()) {
                    ctx.startActivity(new Intent(ctx, XTweetDetailActivity.class)
                        .putExtra("tweet_id", n.tweetId));
                } else if (n.fromUid != null && !n.fromUid.isEmpty()) {
                    ctx.startActivity(new Intent(ctx, XProfileActivity.class)
                        .putExtra("uid", n.fromUid));
                }
            });
        }

        private void animateClearUnread() {
            if (llRoot == null) return;
            int from = ctx.getColor(R.color.x_notif_unread_bg);
            int to   = android.graphics.Color.TRANSPARENT;
            ValueAnimator anim = ValueAnimator.ofArgb(from, to);
            anim.setDuration(300);
            anim.addUpdateListener(a -> llRoot.setBackgroundColor((int) a.getAnimatedValue()));
            anim.start();
            if (vUnreadBar != null) vUnreadBar.setVisibility(View.GONE);
        }

        private String buildTitle(XNotification n) {
            String name = n.fromName != null && !n.fromName.isEmpty() ? n.fromName : "Someone";
            switch (n.type != null ? n.type : "") {
                case "like":    return name + " liked your post";
                case "retweet": return name + " reposted your post";
                case "reply":   return name + " replied to your post";
                case "follow":  return name + " followed you";
                case "mention": return name + " mentioned you";
                case "quote":   return name + " quoted your post";
                case "dm":      return name + " sent you a message";
                default:        return name + " interacted with your post";
            }
        }

        private int iconForType(String type) {
            if (type == null) return R.drawable.ic_x_logo;
            switch (type) {
                case "like":           return R.drawable.ic_x_heart_filled;
                case "retweet":        return R.drawable.ic_x_retweet;
                case "follow":         return R.drawable.ic_person;
                case "reply":
                case "mention":        return R.drawable.ic_x_reply;
                case "quote":          return R.drawable.ic_x_retweet;
                case "dm":             return R.drawable.ic_x_dm;
                default:               return R.drawable.ic_x_logo;
            }
        }

        private int tintForType(String type) {
            if (type == null) return ctx.getColor(R.color.x_accent);
            switch (type) {
                case "like":    return ctx.getColor(R.color.x_like_active);
                case "retweet":
                case "quote":   return ctx.getColor(R.color.x_retweet_active);
                case "follow":  return ctx.getColor(R.color.x_accent);
                case "dm":      return ctx.getColor(R.color.x_accent);
                default:        return ctx.getColor(R.color.x_text_secondary);
            }
        }

        private String formatTime(long ts) {
            long diff = System.currentTimeMillis() - ts;
            if (diff < 60_000)          return diff / 1000 + "s";
            if (diff < 3_600_000)       return diff / 60_000 + "m";
            if (diff < 86_400_000)      return diff / 3_600_000 + "h";
            if (diff < 7 * 86_400_000L) return diff / 86_400_000 + "d";
            return new SimpleDateFormat("MMM d", Locale.US).format(new Date(ts));
        }
    }
}
