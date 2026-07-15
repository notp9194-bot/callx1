package com.callx.app.community;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityNotificationEntity;

import java.util.Collections;
import java.util.List;

/**
 * v31: Adapter for in-app community notifications.
 * Unread notifications shown at full opacity with a blue dot indicator.
 */
public class CommunityNotificationAdapter
        extends RecyclerView.Adapter<CommunityNotificationAdapter.VH> {

    public interface Listener {
        void onNotificationClicked(CommunityNotificationEntity notif);
    }

    private static final DiffUtil.ItemCallback<CommunityNotificationEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityNotificationEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityNotificationEntity a,
                                               @NonNull CommunityNotificationEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityNotificationEntity a,
                                                  @NonNull CommunityNotificationEntity b) {
                    return a.isRead == b.isRead;
                }
            };

    private final AsyncListDiffer<CommunityNotificationEntity> differ =
            new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;

    public CommunityNotificationAdapter(Listener listener) { this.listener = listener; }

    public void submitList(List<CommunityNotificationEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_notification, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityNotificationEntity n = differ.getCurrentList().get(pos);

        h.tvTitle.setText(n.title != null ? n.title : "");
        h.tvBody.setText(n.body != null ? n.body : "");
        h.tvTime.setText(n.createdAt > 0
                ? DateUtils.getRelativeTimeSpanString(n.createdAt,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) : "");

        // Type icon emoji
        h.ivType.setImageResource(getNotifIconRes(n.type));

        // Unread state
        h.viewUnreadDot.setVisibility(n.isRead ? View.GONE : View.VISIBLE);
        h.itemView.setAlpha(n.isRead ? 0.65f : 1.0f);

        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onNotificationClicked(n); });
    }

    private int getNotifIconRes(String type) {
        if (type == null) return R.drawable.ic_notifications;
        switch (type) {
            case "mention":        return R.drawable.ic_alternate_email;
            case "reply":          return R.drawable.ic_reply;
            case "role_change":    return R.drawable.ic_admin_panel;
            case "join_approved":  return R.drawable.ic_check_circle;
            case "join_rejected":  return R.drawable.ic_cancel;
            case "event_reminder": return R.drawable.ic_event;
            case "reaction":       return R.drawable.ic_favorite;
            case "new_post":
            default:               return R.drawable.ic_post_add;
        }
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivType;
        TextView tvTitle, tvBody, tvTime;
        View viewUnreadDot;

        VH(@NonNull View itemView) {
            super(itemView);
            ivType       = itemView.findViewById(R.id.iv_notif_type);
            tvTitle      = itemView.findViewById(R.id.tv_notif_title);
            tvBody       = itemView.findViewById(R.id.tv_notif_body);
            tvTime       = itemView.findViewById(R.id.tv_notif_time);
            viewUnreadDot = itemView.findViewById(R.id.view_unread_dot);
        }
    }
}
