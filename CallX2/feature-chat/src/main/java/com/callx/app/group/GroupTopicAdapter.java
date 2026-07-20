package com.callx.app.group;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.models.GroupTopic;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Adapter for the Group Topics list screen.
 * Uses ListAdapter + DiffUtil for smooth updates.
 */
public class GroupTopicAdapter extends ListAdapter<GroupTopic, GroupTopicAdapter.VH> {

    public interface Listener {
        void onTopicClick(GroupTopic topic);
        void onTopicLongClick(GroupTopic topic);
    }

    private final Listener listener;
    private final String currentUid;

    public GroupTopicAdapter(Listener listener, String currentUid) {
        super(DIFF);
        this.listener = listener;
        this.currentUid = currentUid;
    }

    private static final DiffUtil.ItemCallback<GroupTopic> DIFF = new DiffUtil.ItemCallback<GroupTopic>() {
        @Override public boolean areItemsTheSame(@NonNull GroupTopic a, @NonNull GroupTopic b) {
            return a.id != null && a.id.equals(b.id);
        }
        @Override public boolean areContentsTheSame(@NonNull GroupTopic a, @NonNull GroupTopic b) {
            return a.name.equals(b.name)
                    && a.messageCount == b.messageCount
                    && a.lastMessageAt == b.lastMessageAt
                    && a.closed == b.closed;
        }
    };

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_group_topic, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        GroupTopic topic = getItem(pos);
        h.bind(topic);
    }

    class VH extends RecyclerView.ViewHolder {
        private final TextView tvEmoji, tvName, tvDesc, tvLastMsg, tvTime, tvBadge;
        private final ImageView ivLock, ivPin;
        private final View badgeView;

        VH(View v) {
            super(v);
            tvEmoji   = v.findViewById(R.id.tv_topic_emoji);
            tvName    = v.findViewById(R.id.tv_topic_name);
            tvDesc    = v.findViewById(R.id.tv_topic_desc);
            tvLastMsg = v.findViewById(R.id.tv_topic_last_msg);
            tvTime    = v.findViewById(R.id.tv_topic_time);
            tvBadge   = v.findViewById(R.id.tv_topic_unread_badge);
            ivLock    = v.findViewById(R.id.iv_topic_lock);
            ivPin     = v.findViewById(R.id.iv_topic_pin);
            badgeView = v.findViewById(R.id.badge_container);
        }

        void bind(GroupTopic topic) {
            tvEmoji.setText(TextUtils.isEmpty(topic.emoji) ? "💬" : topic.emoji);
            tvName.setText(topic.name);
            tvDesc.setVisibility(TextUtils.isEmpty(topic.description) ? View.GONE : View.VISIBLE);
            tvDesc.setText(topic.description);
            tvLastMsg.setText(TextUtils.isEmpty(topic.lastMessage) ? "No messages yet" : topic.lastMessage);
            ivLock.setVisibility(topic.closed ? View.VISIBLE : View.GONE);
            ivPin.setVisibility(topic.pinned ? View.VISIBLE : View.GONE);
            if (topic.lastMessageAt > 0) {
                tvTime.setVisibility(View.VISIBLE);
                tvTime.setText(formatTime(topic.lastMessageAt));
            } else {
                tvTime.setVisibility(View.GONE);
            }
            long unread = topic.unread != null && currentUid != null
                    ? (topic.unread.getOrDefault(currentUid, 0L))
                    : 0L;
            if (unread > 0) {
                badgeView.setVisibility(View.VISIBLE);
                tvBadge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            } else {
                badgeView.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> { if (listener != null) listener.onTopicClick(topic); });
            itemView.setOnLongClickListener(v -> { if (listener != null) listener.onTopicLongClick(topic); return true; });
        }

        private String formatTime(long ms) {
            long now = System.currentTimeMillis();
            long diff = now - ms;
            if (diff < 86400_000L) {
                return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ms));
            } else if (diff < 7 * 86400_000L) {
                return new SimpleDateFormat("EEE", Locale.getDefault()).format(new Date(ms));
            } else {
                return new SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(new Date(ms));
            }
        }
    }
}
