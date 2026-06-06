package com.callx.app.conversation;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.chat.R;

import com.callx.app.models.Message;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.MediaCache;

import java.text.SimpleDateFormat;
import java.util.Locale;
import com.callx.app.utils.LinkPreviewFetcher;

/**
 * MessagePagingAdapter — Paging 3 PagingDataAdapter for chat messages.
 *
 * Drop-in replacement for MessageAdapter when loading messages from Room DB
 * via Pager3 + PagingSource. Supports sent/received layout types, text,
 * image, audio, file, and video message rendering identical to MessageAdapter.
 *
 * Usage in ChatActivity:
 *   MessagePagingAdapter pagingAdapter = new MessagePagingAdapter(uid, false);
 *   binding.rvMessages.setAdapter(pagingAdapter);
 *   viewModel.getPagedMessages(chatId).observe(this, pagingAdapter::submitData);
 */
public class MessagePagingAdapter
        extends PagingDataAdapter<Message, MessagePagingAdapter.VH> {

    // ── DiffUtil — required by PagingDataAdapter ──────────────────
    private static final DiffUtil.ItemCallback<Message> DIFF =
        new DiffUtil.ItemCallback<Message>() {
            @Override
            public boolean areItemsTheSame(@NonNull Message a, @NonNull Message b) {
                return a.messageId != null && a.messageId.equals(b.messageId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Message a, @NonNull Message b) {
                return a.messageId.equals(b.messageId)
                    && safeEquals(a.text, b.text)
                    && safeEquals(a.type, b.type)
                    && safeEquals(a.status, b.status)
                    && a.timestamp == b.timestamp
                    && a.edited == b.edited
                    && a.deleted == b.deleted
                    && a.fontStyle == b.fontStyle
                    && reactionsEqual(a.reactions, b.reactions);  // FIX: reactions change pe rebind trigger
            }

            private boolean safeEquals(String x, String y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }

            private boolean reactionsEqual(java.util.Map<String, String> x,
                                            java.util.Map<String, String> y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }
        };

    // ── View types ────────────────────────────────────────────────
    private static final int TYPE_SENT        = 1;
    private static final int TYPE_RECEIVED    = 2;
    private static final int TYPE_STATUS_SEEN = 3;
    private static final int TYPE_REEL_SEEN   = 4;
    private static final int TYPE_CALL_ENTRY  = 5;

    // ── Fields ────────────────────────────────────────────────────
    private final String currentUid;
    private final boolean isGroup;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private final SimpleDateFormat dateLabelFmt =
            new SimpleDateFormat("d MMM yyyy", Locale.getDefault());

    private ActionListener actionListener;
    private MediaPlayer player;
    private int playingPos = -1;
    // FIX [P3-1]: Track the ViewHolder that is currently playing so we can
    // reset its UI (icon + seekbar) when a different message starts playing.
    private VH playingVH = null;
    // FIX: SeekBar progress update via Handler — 250ms interval during playback
    private final android.os.Handler seekHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable seekUpdater;

    // ── Interface for long-press actions ─────────────────────────
    public interface ActionListener {
        void onReply(Message m);
        void onNavigateToOriginal(String messageId);
        void onDelete(Message m);
        void onReact(Message m, String emoji);
        void onStar(Message m);
        void onCopy(Message m);
        void onForward(Message m);
        /** Called when user taps the ⚠ failed-status icon to retry sending. */
        default void onRetry(Message m) {}
        /** Called when user chooses Edit from the action sheet (own messages only). */
        default void onEdit(Message m) {}
        /** Called when user pins or unpins a message from the action sheet. */
        default void onPin(Message m) {}
    }

    // ── Multi-select interface ────────────────────────────────────
    public interface MultiSelectListener {
        void onSelectionChanged(int count);
    }

    // ── Multi-select state ────────────────────────────────────────
    private boolean multiSelectMode = false;
    private final java.util.Set<String> selectedMessageIds = new java.util.HashSet<>();
    private MultiSelectListener multiSelectListener;

    public void setMultiSelectListener(MultiSelectListener l) { this.multiSelectListener = l; }
    public ActionListener getActionListener() { return actionListener; }

    public void enterMultiSelectMode(Message firstMessage) {
        multiSelectMode = true;
        selectedMessageIds.clear();
        String id = firstMessage != null ? firstMessage.messageId : null;
        if (id == null && firstMessage != null) id = firstMessage.id;
        if (id != null) selectedMessageIds.add(id);
        // FIX: notifyDataSetChanged() kills performance — notify only the first selected item
        // and rely on the long-press caller to refresh visible items via notifyItemRangeChanged
        notifyItemRangeChanged(0, getItemCount());
        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(selectedMessageIds.size());
    }

    public void exitMultiSelectMode() {
        multiSelectMode = false;
        selectedMessageIds.clear();
        // FIX: targeted range notify instead of notifyDataSetChanged()
        notifyItemRangeChanged(0, getItemCount());
        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(0);
    }

    public boolean isInMultiSelectMode() { return multiSelectMode; }

    public java.util.List<Message> getSelectedMessages() {
        java.util.List<Message> result = new java.util.ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            if (id != null && selectedMessageIds.contains(id)) result.add(m);
        }
        return result;
    }

    private void applySelectionHighlight(VH h, Message m) {
        String id = m.messageId != null ? m.messageId : m.id;
        boolean selected = id != null && selectedMessageIds.contains(id);
        if (multiSelectMode) {
            h.itemView.setAlpha(selected ? 1.0f : 0.55f);
            // FIX: holo_blue_light is outdated/ugly. Use a translucent brand-aware overlay instead.
            h.itemView.setBackgroundColor(selected ? 0x336200EE : android.graphics.Color.TRANSPARENT);
        } else {
            h.itemView.setAlpha(1.0f);
            h.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }
    }

    public MessagePagingAdapter(String currentUid, boolean isGroup) {
        super(DIFF);
        this.currentUid = currentUid;
        this.isGroup    = isGroup;
    }

    public void setActionListener(ActionListener l) {
        this.actionListener = l;
    }

    // ──────────────────────────────────────────────────────────────
    @Override
    public int getItemViewType(int position) {
        Message m = getItem(position);
        if (m == null) return TYPE_RECEIVED;
        if ("status_seen".equals(m.type)) return TYPE_STATUS_SEEN;
        if ("reel_seen".equals(m.type))   return TYPE_REEL_SEEN;
        if ("call_entry".equals(m.type))  return TYPE_CALL_ENTRY;
        return currentUid.equals(m.senderId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout;
        if (viewType == TYPE_SENT)             layout = R.layout.item_message_sent;
        else if (viewType == TYPE_STATUS_SEEN) layout = R.layout.item_status_seen_bubble;
        else if (viewType == TYPE_REEL_SEEN)   layout = R.layout.item_reel_seen_bubble;
        else if (viewType == TYPE_CALL_ENTRY)  layout = R.layout.item_call_entry_bubble;
        else                                   layout = R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Message m = getItem(position);
        if (m == null) {
            // Placeholder — show shimmer or empty
            if (h.tvMessage != null) h.tvMessage.setVisibility(View.GONE);
            return;
        }
        // ── STATUS SEEN BUBBLE — special system event row ─────────────────
        if ("status_seen".equals(m.type)) {
            bindStatusSeenBubble(h, m);
            return;
        }
        // ── REEL SEEN BUBBLE — special system event row ───────────────────
        if ("reel_seen".equals(m.type)) {
            bindReelSeenBubble(h, m);
            return;
        }
        // ── CALL ENTRY BUBBLE — system call log row in chat ──────────────────
        if ("call_entry".equals(m.type)) {
            bindCallEntryBubble(h, m);
            return;
        }
        bindMessage(h, m, position);
    }

    // ──────────────────────────────────────────────────────────────
    // CALL ENTRY BUBBLE — centered system call log row in chat.
    // Layout: item_call_entry_bubble.xml
    //   tv_call_entry_icon  — emoji (📞 audio, 📹 video)
    //   tv_call_entry_label — e.g. "Audio call • 2:30" or "Missed video call"
    //   tv_call_entry_time  — formatted timestamp (hh:mm a)
    // No long-press / reactions — it's a system event.
    // ──────────────────────────────────────────────────────────────
    private void bindCallEntryBubble(@NonNull VH h, @NonNull Message m) {
        android.widget.TextView tvIcon  = h.itemView.findViewById(R.id.tv_call_entry_icon);
        android.widget.TextView tvLabel = h.itemView.findViewById(R.id.tv_call_entry_label);
        android.widget.TextView tvTime  = h.itemView.findViewById(R.id.tv_call_entry_time);

        boolean isVideoCall = "video".equals(m.fileName);
        boolean isMissed    = "missed".equals(m.text);
        boolean iAmCaller   = currentUid != null && currentUid.equals(m.senderId);

        // Icon
        if (tvIcon != null) tvIcon.setText(isVideoCall ? "📹" : "📞");

        // Label text
        String label;
        if (isMissed) {
            if (iAmCaller) {
                label = isVideoCall ? "No answer (video)" : "No answer";
            } else {
                label = isVideoCall ? "Missed video call" : "Missed call";
            }
            if (tvLabel != null) tvLabel.setTextColor(android.graphics.Color.parseColor("#FF5555"));
        } else {
            // Call was connected — show duration
            String durStr = "";
            if (m.duration != null && m.duration > 0) {
                long sec = m.duration / 1000;
                durStr = " • " + String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
            }
            if (iAmCaller) {
                label = isVideoCall ? ("Video call" + durStr) : ("Audio call" + durStr);
            } else {
                label = isVideoCall ? ("Incoming video call" + durStr) : ("Incoming call" + durStr);
            }
            if (tvLabel != null) tvLabel.setTextColor(0xFFFFFFFF);
        }
        if (tvLabel != null) tvLabel.setText(label);

        // Time
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // STATUS SEEN BUBBLE — "👁 Seen your status" system event row.
    // Layout: item_status_seen_bubble.xml
    //   • iv_status_seen_avatar  → circular avatar (Glide)
    //   • fl_status_seen_thumb   → thumbnail container (visible for image/video statuses)
    //   • iv_status_seen_thumb   → status thumbnail (tappable → StatusViewerActivity)
    //   • iv_status_seen_eye     → eye overlay icon on thumbnail
    //   • tv_status_seen_label   → "Seen your status" (set in XML)
    //   • tv_status_seen_name    → sender name (group only)
    //   • tv_status_seen_time    → formatted timestamp
    // No long-press / reactions / reply — it's a system event.
    // ──────────────────────────────────────────────────────────────
    private void bindStatusSeenBubble(@NonNull VH h, @NonNull Message m) {
        Context ctx = h.itemView.getContext();

        // Avatar
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(R.id.iv_status_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                com.bumptech.glide.Glide.with(ctx)
                    .load(photo)
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        // Status thumbnail
        android.view.View flThumb = h.itemView.findViewById(R.id.fl_status_seen_thumb);
        android.widget.ImageView ivThumb = h.itemView.findViewById(R.id.iv_status_seen_thumb);
        android.widget.ImageView ivEye   = h.itemView.findViewById(R.id.iv_status_seen_eye);
        if (ivThumb != null && flThumb != null) {
            String thumb = m.statusThumbUrl != null ? m.statusThumbUrl : "";
            if (!thumb.isEmpty()) {
                flThumb.setVisibility(View.VISIBLE);
                if (ivEye != null) ivEye.setVisibility(View.VISIBLE);
                com.bumptech.glide.Glide.with(ctx)
                    .load(thumb)
                    .centerCrop()
                    .placeholder(R.drawable.bg_skeleton_rect)
                    .into(ivThumb);
            } else {
                flThumb.setVisibility(View.GONE);
                if (ivEye != null) ivEye.setVisibility(View.GONE);
            }
        }

        // Click on whole bubble or thumbnail → open StatusViewerActivity
        final String ownerUid  = (m.statusOwnerUid != null && !m.statusOwnerUid.isEmpty())
                                 ? m.statusOwnerUid : m.senderId;
        final String ownerName = m.statusOwnerName != null ? m.statusOwnerName
                                 : (m.senderName != null ? m.senderName : "");
        android.view.View.OnClickListener openStatus = v -> {
            if (ownerUid == null || ownerUid.isEmpty()) return;
            android.content.Intent intent = new android.content.Intent(
                    com.callx.app.utils.Constants.ACTION_OPEN_STATUS);
            intent.putExtra("ownerUid",  ownerUid);
            intent.putExtra("ownerName", ownerName);
            intent.setPackage(ctx.getPackageName());
            try { ctx.startActivity(intent); }
            catch (android.content.ActivityNotFoundException e) {
                android.widget.Toast.makeText(ctx, "Status viewer not available",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        };
        h.itemView.setOnClickListener(openStatus);
        if (flThumb != null) flThumb.setOnClickListener(openStatus);

        // Sender name (shown in group chat only)
        android.widget.TextView tvName =
            h.itemView.findViewById(R.id.tv_status_seen_name);
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(View.VISIBLE);
            } else {
                tvName.setVisibility(View.GONE);
            }
        }

        // Time
        android.widget.TextView tvTime =
            h.itemView.findViewById(R.id.tv_status_seen_time);
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // REEL SEEN BUBBLE — "🎬 Watched your reel" system event row.
    // Layout: item_reel_seen_bubble.xml
    //   • iv_reel_seen_avatar   → circular avatar (Glide)
    //   • fl_reel_seen_thumb    → FrameLayout container (tappable → opens reel)
    //   • iv_reel_seen_thumb    → reel thumbnail
    //   • iv_reel_seen_play     → play icon overlay on thumbnail
    //   • tv_reel_seen_label    → "Watched your reel" (set in XML)
    //   • tv_reel_seen_name     → sender name (group only)
    //   • tv_reel_seen_time     → formatted timestamp
    // No long-press / reactions / reply — system event.
    // ──────────────────────────────────────────────────────────────
    private void bindReelSeenBubble(@NonNull VH h, @NonNull Message m) {
        Context ctx = h.itemView.getContext();

        // Avatar
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(R.id.iv_reel_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                com.bumptech.glide.Glide.with(ctx)
                    .load(photo)
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        // Click handler — reelId se reel kholo (thumb ho ya na ho, always kaam kare)
        final String reelId = m.reelId;
        android.view.View.OnClickListener openReel = v -> {
            if (reelId == null || reelId.isEmpty()) return;
            android.content.Intent intent = new android.content.Intent(
                    com.callx.app.utils.Constants.ACTION_OPEN_REEL);
            intent.putExtra("reelId", reelId);
            intent.setPackage(ctx.getPackageName());
            ctx.startActivity(intent);
        };

        // Reel thumbnail + play icon
        android.view.View flThumb = h.itemView.findViewById(R.id.fl_reel_seen_thumb);
        android.widget.ImageView ivThumb = h.itemView.findViewById(R.id.iv_reel_seen_thumb);
        android.widget.ImageView ivPlay  = h.itemView.findViewById(R.id.iv_reel_seen_play);
        if (ivThumb != null) {
            String thumb = m.reelThumbUrl != null ? m.reelThumbUrl : "";
            if (!thumb.isEmpty()) {
                ivThumb.setVisibility(android.view.View.VISIBLE);
                if (ivPlay != null) ivPlay.setVisibility(android.view.View.VISIBLE);
                com.bumptech.glide.Glide.with(ctx)
                    .load(thumb)
                    .centerCrop()
                    .placeholder(R.drawable.bg_skeleton_rect)
                    .into(ivThumb);
            } else {
                ivThumb.setVisibility(android.view.View.GONE);
                if (ivPlay != null) ivPlay.setVisibility(android.view.View.GONE);
            }
        }
        // Click on FrameLayout container, play icon, AND whole item
        if (flThumb != null) flThumb.setOnClickListener(openReel);
        if (ivPlay  != null) ivPlay.setOnClickListener(openReel);
        h.itemView.setOnClickListener(openReel);

        // Sender name (group only)
        android.widget.TextView tvName =
            h.itemView.findViewById(R.id.tv_reel_seen_name);
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(android.view.View.VISIBLE);
            } else {
                tvName.setVisibility(android.view.View.GONE);
            }
        }

        // Time
        android.widget.TextView tvTime =
            h.itemView.findViewById(R.id.tv_reel_seen_time);
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Core bind logic (mirrors MessageAdapter)
    // ──────────────────────────────────────────────────────────────
    // ──────────────────────────────────────────────────────────────
    // Date separator helper — returns "Today", "Yesterday", or "3 Jan 2025"
    // ──────────────────────────────────────────────────────────────
    private static String formatDateLabel(long timestamp) {
        java.util.Calendar msgCal = java.util.Calendar.getInstance();
        msgCal.setTimeInMillis(timestamp);
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);

        boolean isToday = msgCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
                && msgCal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR);
        boolean isYesterday = msgCal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR)
                && msgCal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR);

        if (isToday) return "Today";
        if (isYesterday) return "Yesterday";
        // Older: "3 Jan 2025" or just "3 Jan" if same year
        boolean sameYear = msgCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR);
        if (sameYear) {
            return new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                    .format(new java.util.Date(timestamp));
        }
        return new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                .format(new java.util.Date(timestamp));
    }

    private static boolean isSameDay(long ts1, long ts2) {
        java.util.Calendar c1 = java.util.Calendar.getInstance();
        java.util.Calendar c2 = java.util.Calendar.getInstance();
        c1.setTimeInMillis(ts1);
        c2.setTimeInMillis(ts2);
        return c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR)
                && c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private void bindMessage(@NonNull VH h, @NonNull Message m, int position) {
        Context ctx = h.itemView.getContext();
        boolean sent = currentUid.equals(m.senderId);

        // ── Date separator chip ───────────────────────────────────────────
        if (h.tvDateHeader != null && m.timestamp != null && m.timestamp > 0) {
            boolean showHeader;
            if (position == 0) {
                showHeader = true;
            } else {
                Message prev = getItem(position - 1);
                showHeader = prev == null || prev.timestamp == null
                        || !isSameDay(prev.timestamp, m.timestamp);
            }
            if (showHeader) {
                h.tvDateHeader.setText(formatDateLabel(m.timestamp));
                h.tvDateHeader.setVisibility(View.VISIBLE);
            } else {
                h.tvDateHeader.setVisibility(View.GONE);
            }
        } else if (h.tvDateHeader != null) {
            h.tvDateHeader.setVisibility(View.GONE);
        }

        // ── Theme-aware bubble background ─────────────────────────────────
        try {
            android.view.View llBubble = h.itemView.findViewById(R.id.ll_bubble);
            if (llBubble != null) {
                boolean hasReply = m.replyToId != null && !m.replyToId.isEmpty();
                String bType = m.type != null ? m.type : "text";
                com.callx.app.utils.ChatThemeManager
                        .get(ctx)
                        .applyBubble(llBubble, sent, bType, hasReply);
            }
        } catch (Exception ignored) {}

        // Reset visibility
        h.tvMessage.setVisibility(View.GONE);
        if (h.ivImage    != null) h.ivImage.setVisibility(View.GONE);
        if (h.llAudio    != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile     != null) h.llFile.setVisibility(View.GONE);
        if (h.tvTime     != null) h.tvTime.setVisibility(View.VISIBLE);

        // Timestamp — append "(edited)" when applicable
        if (h.tvTime != null && m.timestamp > 0) {
            String timeStr = timeFmt.format(new java.util.Date(m.timestamp));
            if (Boolean.TRUE.equals(m.edited)) timeStr = timeStr + "  \u270F\uFE0F edited";
            h.tvTime.setText(timeStr);
        }

        // ── REPLY PREVIEW (SwipeReplySystem v1) ─────────────────────────
        if (h.llReplyPreview != null) {
            boolean hasReply = m.replyToId != null && !m.replyToId.isEmpty();
            h.llReplyPreview.setVisibility(hasReply ? View.VISIBLE : View.GONE);
            if (hasReply) {
                if (h.tvReplySender != null)
                    h.tvReplySender.setText(
                            m.replyToSenderName != null ? m.replyToSenderName : "");
                if (h.tvReplyText != null)
                    h.tvReplyText.setText(
                            m.replyToText != null ? m.replyToText : "[Original message]");
                // Thumbnail
                if (h.ivReplyThumb != null) {
                    String thumbUrl = m.replyToMediaUrl;
                    if (thumbUrl != null && !thumbUrl.isEmpty()) {
                        h.ivReplyThumb.setVisibility(View.VISIBLE);
                        com.bumptech.glide.Glide.with(ctx)
                                .load(thumbUrl).centerCrop()
                                .into(h.ivReplyThumb);
                    } else {
                        h.ivReplyThumb.setVisibility(View.GONE);
                    }
                }
                // Click → scroll to original message
                final String replyId = m.replyToId;
                h.llReplyPreview.setOnClickListener(v -> {
                    if (actionListener != null) {
                        actionListener.onNavigateToOriginal(replyId);
                    }
                });
            } else {
                h.llReplyPreview.setOnClickListener(null);
            }
        }

        // ── Reactions display ─────────────────────────────────────────
        if (h.llReactions != null && h.tvReactions != null) {
            java.util.Map<String, String> rxMap = m.reactions;
            if (rxMap != null && !rxMap.isEmpty()) {
                // Count each unique emoji
                java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
                for (String emoji : rxMap.values()) {
                    counts.put(emoji, counts.containsKey(emoji) ? counts.get(emoji) + 1 : 1);
                }
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                    sb.append(e.getKey());
                    if (e.getValue() > 1) sb.append(e.getValue());
                    sb.append(" ");
                    if (++shown >= 4) break; // max 4 distinct emojis shown
                }
                h.tvReactions.setText(sb.toString().trim());
                h.llReactions.setVisibility(View.VISIBLE);
            } else {
                h.llReactions.setVisibility(View.GONE);
            }
        }

        // Sender name (group chats)
        if (isGroup && !sent && h.tvSenderName != null) {
            h.tvSenderName.setVisibility(View.VISIBLE);
            String sn = m.senderName != null ? m.senderName : "Member";
            h.tvSenderName.setText(sn);
        } else if (h.tvSenderName != null) {
            h.tvSenderName.setVisibility(View.GONE);
        }

        // Deleted message
        if (Boolean.TRUE.equals(m.deleted)) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText(sent ? "You deleted this message" : "This message was deleted");
            h.tvMessage.setAlpha(0.6f);
            return;
        }

        // ── Render by type ───────────────────────────────────────
        String type = m.type != null ? m.type : "text";
        switch (type) {
            case "sticker":
            case "image":
            case "gif":
                if (h.ivImage != null) {
                    h.ivImage.setVisibility(View.VISIBLE);
                    String fullUrl  = m.mediaUrl != null ? m.mediaUrl : m.text;
                    String thumbUrl = m.thumbnailUrl;
                    boolean isGifMsg = "gif".equals(m.type);
                    boolean isStickerType = "sticker".equals(m.type);

                    // ── Progressive loading: thumb instantly → full replaces ──
                    // Sticker aur GIF ke liye thumbnail skip karo
                    if (thumbUrl != null && !thumbUrl.isEmpty() && !isGifMsg && !isStickerType) {
                        // Step 1: Show thumbnail instantly (tiny, ~30KB)
                        Glide.with(ctx)
                            .load(thumbUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(200, 200)
                            .into(h.ivImage);

                        // Step 2: Load full in background — replaces thumb with crossfade
                        Glide.with(ctx)
                            .load(fullUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .thumbnail(Glide.with(ctx)
                                .load(thumbUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL))
                            .transition(com.bumptech.glide.load.resource.drawable
                                .DrawableTransitionOptions.withCrossFade(400))
                            .placeholder(R.drawable.ic_file)
                            .error(R.drawable.ic_file)
                            .into(h.ivImage);
                    } else {
                        // GIF ya no thumbnail — direct load with animation support
                        // isStickerType already declared above
                        java.io.File cachedImg = (isGifMsg || isStickerType) ? null : MediaCache.getCached(ctx, fullUrl);
                        if (isStickerType) {
                            // Sticker: WebP — normal Glide load, asGif() use mat karo
                            Glide.with(ctx)
                                .load(fullUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(R.drawable.ic_file)
                                .error(R.drawable.ic_file)
                                .into(h.ivImage);
                        } else if (isGifMsg) {
                            // GIF: asGif() se URL directly load karo
                            Glide.with(ctx)
                                .asGif()
                                .load(fullUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(R.drawable.ic_file)
                                .error(R.drawable.ic_file)
                                .into(h.ivImage);
                        } else if (cachedImg != null) {
                            Glide.with(ctx).load(cachedImg)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(R.drawable.ic_file)
                                .into(h.ivImage);
                        } else {
                            Glide.with(ctx).load(fullUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(R.drawable.ic_file)
                                .error(R.drawable.ic_file)
                                .into(h.ivImage);
                            // Cache in background for next time
                            MediaCache.get(ctx, fullUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {}
                                @Override public void onError(String reason) {}
                            });
                        }
                    }

                    // Click → WhatsApp-style image action bottom sheet
                    h.ivImage.setOnClickListener(v ->
                        showImageActionSheet(ctx, m, fullUrl, thumbUrl != null ? thumbUrl : fullUrl));
                    // Long-press → normal message action sheet
                    h.ivImage.setOnLongClickListener(v -> {
                        if (actionListener != null) showActionBottomSheet(ctx, m);
                        return true;
                    });
                }
                break;
            case "video":
                // POLISH: Use fl_video + iv_video_thumb (thumbnail + play overlay)
                // Prefer thumbnailUrl (Cloudinary thumb) over raw video URL for preview
                if (h.flVideo != null && h.ivVideoThumb != null) {
                    h.flVideo.setVisibility(View.VISIBLE);
                    if (h.ivImage != null) h.ivImage.setVisibility(View.GONE);
                    String vUrl   = m.mediaUrl != null ? m.mediaUrl : m.text;
                    // POLISH FIX: use Cloudinary thumbnail for preview image, not the raw video URL
                    String thumbUrl = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                            ? m.thumbnailUrl : vUrl;
                    Glide.with(ctx)
                        .load(thumbUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.ic_file)
                        .centerCrop()
                        .into(h.ivVideoThumb);
                    // Duration overlay
                    if (h.tvDuration != null && m.duration != null && m.duration > 0) {
                        long secs = m.duration / 1000;
                        h.tvDuration.setText(String.format(
                                java.util.Locale.US, "%d:%02d", secs / 60, secs % 60));
                        h.tvDuration.setVisibility(View.VISIBLE);
                    }
                    h.flVideo.setOnClickListener(v -> {
                        Intent i = new Intent().setClassName(ctx.getPackageName(),
                                "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url", vUrl);
                        i.putExtra("type", "video");
                        ctx.startActivity(i);
                    });
                } else if (h.ivImage != null) {
                    // Fallback: layout without fl_video — show thumbnail in ivImage
                    h.ivImage.setVisibility(View.VISIBLE);
                    String vUrl     = m.mediaUrl != null ? m.mediaUrl : m.text;
                    String thumbUrl = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                            ? m.thumbnailUrl : vUrl;
                    Glide.with(ctx).load(thumbUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.ic_file)
                        .into(h.ivImage);
                    h.ivImage.setOnClickListener(v -> {
                        Intent i = new Intent().setClassName(ctx.getPackageName(),
                                "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url", vUrl);
                        i.putExtra("type", "video");
                        ctx.startActivity(i);
                    });
                }
                break;
            case "audio":
                if (h.llAudio != null && h.btnPlayPause != null) {
                    h.llAudio.setVisibility(View.VISIBLE);
                    String aUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                    final int pos = position;
                    h.btnPlayPause.setOnClickListener(v -> toggleAudio(h, aUrl, pos));
                    // FIX v14: Audio preload — MediaStreamCache se pehle 512KB cache karo
                    // Taaki play button press karne par turant start ho, buffer nahi kare
                    java.io.File cachedAudio = MediaCache.getCached(ctx, aUrl);
                    if (cachedAudio == null && aUrl != null && !aUrl.isEmpty()) {
                        com.callx.app.cache.MediaStreamCache.getInstance(ctx)
                            .preloadPartial(aUrl, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                                @Override public void onComplete(java.io.File file) {
                                    android.util.Log.d("PagingAdapter", "Audio preloaded: " + file.getName());
                                }
                                @Override public void onError(String error) {}
                                @Override public void onProgress(int percent) {}
                            });
                    }
                } else {
                    // Fallback if no audio layout
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText("Audio message");
                }
                break;
            case "file":
            case "document":
                if (h.llFile != null && h.tvFileName != null) {
                    h.llFile.setVisibility(View.VISIBLE);
                    String fName = m.fileName != null ? m.fileName : "File";
                    h.tvFileName.setText(fName);
                    if (h.btnDownload != null) {
                        String fUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                        h.btnDownload.setOnClickListener(v -> {
                            // Pehle local cache check karo
                            java.io.File cached = MediaCache.getCached(ctx, fUrl);
                            if (cached != null) {
                                FileUtils.openOrDownload(ctx, cached.toURI().toString(), fName);
                                return;
                            }
                            android.widget.Toast.makeText(ctx, "Downloading…", android.widget.Toast.LENGTH_SHORT).show();
                            MediaCache.get(ctx, fUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {
                                    FileUtils.openOrDownload(ctx, file.toURI().toString(), fName);
                                }
                                @Override public void onError(String reason) {
                                    FileUtils.openOrDownload(ctx, fUrl, fName);
                                }
                            });
                        });
                    }
                } else {
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText(m.fileName != null ? m.fileName : "File");
                }
                break;
            default: // "text", "emoji", etc.
                // POLISH: hide link preview by default before checking for URL
                if (h.llLinkPreview != null) h.llLinkPreview.setVisibility(View.GONE);
                h.tvMessage.setVisibility(View.VISIBLE);
                String txt = m.text != null ? m.text : "";
                if (Boolean.TRUE.equals(m.edited)) txt += " (edited)";
                // ── Font Style: sender ke selected typing style ko receiver pe bhi apply karo ──
                applyFontStyle(h.tvMessage, m.fontStyle);
                // ── Clickable links: URLs, phone numbers, emails ────────────
                android.text.SpannableString spanned = new android.text.SpannableString(txt);
                android.text.util.Linkify.addLinks(spanned,
                    android.text.util.Linkify.WEB_URLS |
                    android.text.util.Linkify.PHONE_NUMBERS |
                    android.text.util.Linkify.EMAIL_ADDRESSES);
                h.tvMessage.setText(spanned);
                // Link color matching bubble theme
                boolean isSentMsg = currentUid.equals(m.senderId);
                int linkColor = isSentMsg ? 0xFFB3E5FC : 0xFF1565C0;
                h.tvMessage.setLinkTextColor(linkColor);
                h.tvMessage.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                h.tvMessage.setHighlightColor(0x33FFFFFF);
                h.tvMessage.setAlpha(1f);
                h.tvMessage.setTextColor(
                    com.callx.app.utils.ChatThemeManager.get(ctx).getTextColor(isSentMsg));

                // POLISH: Link preview — detect URL, fetch OG data async, bind card
                if (h.llLinkPreview != null && m.text != null) {
                    String previewUrl = com.callx.app.utils.LinkPreviewFetcher.extractFirstUrl(m.text);
                    if (previewUrl != null) {
                        // Tag itemView with URL so we detect stale VH on recycle
                        h.llLinkPreview.setTag(previewUrl);
                        h.llLinkPreview.setVisibility(View.INVISIBLE); // reserve space while loading
                        com.callx.app.utils.LinkPreviewFetcher.fetch(previewUrl,
                                new com.callx.app.utils.LinkPreviewFetcher.Callback() {
                            @Override public void onResult(com.callx.app.utils.LinkPreviewFetcher.Result r) {
                                // Guard against recycled VH
                                if (!previewUrl.equals(h.llLinkPreview.getTag())) return;
                                h.llLinkPreview.setVisibility(View.VISIBLE);
                                if (h.tvLinkDomain != null) h.tvLinkDomain.setText(r.domain);
                                if (h.tvLinkTitle  != null) h.tvLinkTitle.setText(r.title);
                                if (h.ivLinkThumb  != null) {
                                    if (r.imageUrl != null && !r.imageUrl.isEmpty()) {
                                        h.ivLinkThumb.setVisibility(View.VISIBLE);
                                        com.bumptech.glide.Glide.with(ctx)
                                            .load(r.imageUrl)
                                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                            .centerCrop()
                                            .into(h.ivLinkThumb);
                                    } else {
                                        h.ivLinkThumb.setVisibility(View.GONE);
                                    }
                                }
                                // Tapping the card opens the URL in browser
                                h.llLinkPreview.setOnClickListener(v -> {
                                    Intent browserIntent = new Intent(
                                            Intent.ACTION_VIEW, android.net.Uri.parse(r.url));
                                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    ctx.startActivity(browserIntent);
                                });
                            }
                            @Override public void onError(String url) {
                                if (!previewUrl.equals(h.llLinkPreview.getTag())) return;
                                h.llLinkPreview.setVisibility(View.GONE);
                            }
                        });
                    }
                }
                break;
        }

        // ── Delivery status (sent messages only) ─────────────────
        if (sent && h.tvStatus != null) {
            h.tvStatus.setVisibility(View.VISIBLE);
            String status = m.status != null ? m.status : "sent";
            switch (status) {
                case "seen":
                case "read":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTickColor(true));
                    break;
                case "delivered":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTickColor(false));
                    break;
                case "pending":
                    // Clock icon — sent locally, not yet reached Firebase
                    h.tvStatus.setText("🕐");
                    h.tvStatus.setTextColor(0xFFAAAAAA);
                    break;
                case "failed":
                    // Error icon — Firebase push rejected; tap to retry
                    h.tvStatus.setText("⚠");
                    h.tvStatus.setTextColor(0xFFFF5555);
                    h.tvStatus.setOnClickListener(v -> {
                        if (actionListener != null) actionListener.onRetry(m);
                    });
                    break;
                default: // "sent" — one grey tick
                    h.tvStatus.setText("✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTickColor(false));
                    h.tvStatus.setOnClickListener(null);
                    break;
            }
        } else if (h.tvStatus != null) {
            h.tvStatus.setVisibility(View.GONE);
        }

        // ── Long press — multi-select mode ya action sheet ─────────────────
        h.itemView.setOnLongClickListener(v -> {
            // FIX: Haptic feedback on long press — production apps always do this
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (!multiSelectMode) {
                enterMultiSelectMode(m);
            } else {
                if (actionListener != null) showActionBottomSheet(ctx, m);
            }
            return true;
        });
        h.itemView.setOnClickListener(v -> {
            if (multiSelectMode) {
                String id = m.messageId != null ? m.messageId : m.id;
                if (id != null) {
                    if (selectedMessageIds.contains(id)) {
                        selectedMessageIds.remove(id);
                    } else {
                        selectedMessageIds.add(id);
                    }
                    notifyItemChanged(h.getAdapterPosition());
                    if (multiSelectListener != null)
                        multiSelectListener.onSelectionChanged(selectedMessageIds.size());
                    if (selectedMessageIds.isEmpty()) exitMultiSelectMode();
                }
            }
        });
        applySelectionHighlight(h, m);
    }

    // ──────────────────────────────────────────────────────────────
    // Audio playback toggle
    // ──────────────────────────────────────────────────────────────
    private void toggleAudio(@NonNull VH h, String url, int position) {
        if (playingPos == position && player != null && player.isPlaying()) {
            player.pause();
            if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
            return;
        }
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        playingPos = position;
        if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_pause);

        // Pehle local cache check — cached hai to seedha play (zero data use)
        java.io.File cached = MediaCache.getCached(h.itemView.getContext(), url);
        if (cached != null) {
            playAudioFromPath(h, cached.getAbsolutePath(), position);
            return;
        }

        // FIX v14: MediaStreamCache use karo — pehle 512KB stream karo (fast start),
        // baaki background mein download hota rahe. User ko buffer nahi karega.
        com.callx.app.cache.MediaStreamCache.getInstance(h.itemView.getContext())
            .preloadPartial(url, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                @Override public void onComplete(java.io.File file) {
                    // Partial/full file ready — play from local file (zero buffering)
                    android.util.Log.d("AudioPlay", "MediaStreamCache ready, playing: " + file.getName());
                    playAudioFromPath(h, file.getAbsolutePath(), position);
                }
                @Override public void onError(String error) {
                    // Fallback: stream directly from URL
                    android.util.Log.w("AudioPlay", "MediaStreamCache failed, streaming URL: " + error);
                    playAudioFromPath(h, url, position);
                }
                @Override public void onProgress(int percent) {
                    android.util.Log.v("AudioPlay", "Audio preload: " + percent + "%");
                }
            });
    }

    private void playAudioFromPath(@NonNull VH h, String path, int position) {
        try {
            // FIX [P3-1]: Reset previous VH UI so two bubbles don't show "pause" at the same time
            if (playingVH != null && playingVH != h) {
                seekHandler.removeCallbacks(seekUpdater);
                if (playingVH.btnPlayPause != null)
                    playingVH.btnPlayPause.setImageResource(R.drawable.ic_play);
                if (playingVH.seekAudio != null) playingVH.seekAudio.setProgress(0);
            }
            if (player != null) { try { player.release(); } catch (Exception ignored) {} }
            player = new MediaPlayer();
            playingVH = h;
            
            // Agar local file hai to FileDescriptor se set karo (cache files ke liye)
            // Agar URL hai to directly
            if (path.startsWith("http")) {
                player.setDataSource(path);
            } else {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                        player.setDataSource(fis.getFD());
                    }
                } else {
                    // File nahi milti to URL ke bahawe try karo
                    player.setDataSource(path);
                }
            }
            
            player.prepareAsync();
            player.setOnPreparedListener(mp -> {
                mp.start();
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_pause);
                // FIX: SeekBar live progress update — runs every 250ms while playing
                if (h.seekAudio != null) {
                    h.seekAudio.setMax(mp.getDuration());
                    seekHandler.removeCallbacks(seekUpdater);
                    seekUpdater = new Runnable() {
                        @Override public void run() {
                            if (player != null && player.isPlaying()) {
                                int cur = player.getCurrentPosition();
                                h.seekAudio.setProgress(cur);
                                // Update duration label if present
                                if (h.tvAudioDur != null) {
                                    long sec = cur / 1000;
                                    h.tvAudioDur.setText(String.format(
                                        java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60));
                                }
                                seekHandler.postDelayed(this, 250);
                            }
                        }
                    };
                    seekHandler.post(seekUpdater);
                    // Allow user to scrub
                    h.seekAudio.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                        @Override public void onProgressChanged(android.widget.SeekBar sb, int prog, boolean fromUser) {
                            if (fromUser && player != null) player.seekTo(prog);
                        }
                        @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                        @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
                    });
                }
            });
            player.setOnCompletionListener(mp -> {
                playingPos = -1;
                seekHandler.removeCallbacks(seekUpdater);
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
                if (h.seekAudio != null) h.seekAudio.setProgress(0);
                try { mp.release(); } catch (Exception ignored) {}
                player = null;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.e("AudioPlay", "Error: " + what + " extra: " + extra + " path: " + path);
                playingPos = -1;
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
                return true;
            });
        } catch (Exception e) {
            android.util.Log.e("AudioPlay", "playAudioFromPath error: " + e.getMessage() + " path: " + path);
            if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Long-press bottom sheet actions
    // ──────────────────────────────────────────────────────────────
    // ── WhatsApp-style image action bottom sheet ──────────────────
    private void showImageActionSheet(Context ctx, Message m, String fullUrl, String thumbForViewer) {
        com.google.android.material.bottomsheet.BottomSheetDialog bsd =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx);

        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"));

        // Drag handle
        android.widget.FrameLayout handleWrap = new android.widget.FrameLayout(ctx);
        android.view.View handle = new android.view.View(ctx);
        int dp4  = dp(ctx, 4);
        int dp36 = dp(ctx, 36);
        int dp5  = dp(ctx, 5);
        android.widget.FrameLayout.LayoutParams handleLp =
                new android.widget.FrameLayout.LayoutParams(dp36, dp4);
        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        handle.setLayoutParams(handleLp);
        handle.setBackgroundColor(android.graphics.Color.parseColor("#555555"));
        android.view.ViewGroup.MarginLayoutParams hlm = (android.view.ViewGroup.MarginLayoutParams) handle.getLayoutParams();
        hlm.topMargin = dp5;
        hlm.bottomMargin = dp5;
        handleWrap.setPadding(0, dp5, 0, dp5);
        handleWrap.addView(handle);
        root.addView(handleWrap);

        // Options: View, Share, Forward, Star, Delete
        String[] labels  = {"🖼  View",  "↗  Share", "↪  Forward", "⭐  Star", "🗑  Delete"};
        int[]    colors  = {0xFFFFFFFF,  0xFFFFFFFF,  0xFFFFFFFF,  0xFFFFFFFF,  0xFFFF5252 };

        boolean isOwnMsg = currentUid != null && currentUid.equals(m.senderId);

        for (int idx = 0; idx < labels.length; idx++) {
            // Skip Delete if not own message
            if (labels[idx].contains("Delete") && !isOwnMsg) continue;

            android.widget.TextView tv = new android.widget.TextView(ctx);
            tv.setText(labels[idx]);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            tv.setTextColor(colors[idx]);
            tv.setPadding(dp(ctx, 20), dp(ctx, 15), dp(ctx, 20), dp(ctx, 15));
            tv.setBackground(getRippleDrawable(ctx));

            final String label = labels[idx];
            tv.setOnClickListener(v -> {
                bsd.dismiss();
                switch (label) {
                    case "🖼  View":
                        android.content.Intent i = new android.content.Intent()
                                .setClassName(ctx.getPackageName(),
                                        "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url",      fullUrl);
                        i.putExtra("thumbUrl", thumbForViewer);
                        i.putExtra("type",     "image");
                        ctx.startActivity(i);
                        break;
                    case "↗  Share":
                        android.content.Intent share = new android.content.Intent(
                                android.content.Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(android.content.Intent.EXTRA_TEXT, fullUrl);
                        ctx.startActivity(android.content.Intent.createChooser(share, "Share via"));
                        break;
                    case "↪  Forward":
                        if (actionListener != null) actionListener.onForward(m);
                        break;
                    case "⭐  Star":
                        if (actionListener != null) actionListener.onStar(m);
                        break;
                    case "🗑  Delete":
                        if (actionListener != null) actionListener.onDelete(m);
                        break;
                }
            });
            root.addView(tv);

            // Divider (not after last)
            if (idx < labels.length - 1 && !(idx == labels.length - 2 && !isOwnMsg)) {
                android.view.View div = new android.view.View(ctx);
                android.widget.LinearLayout.LayoutParams dlp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dlp.setMarginStart(dp(ctx, 20));
                div.setLayoutParams(dlp);
                div.setBackgroundColor(android.graphics.Color.parseColor("#333333"));
                root.addView(div);
            }
        }

        root.setPadding(0, 0, 0, dp(ctx, 16));
        bsd.setContentView(root);
        // Dark bottom sheet
        if (bsd.getWindow() != null) {
            bsd.getWindow().setNavigationBarColor(android.graphics.Color.parseColor("#1E1E1E"));
        }
        bsd.show();
    }

    private int dp(Context ctx, int value) {
        return (int)(value * ctx.getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.Drawable getRippleDrawable(Context ctx) {
        android.graphics.drawable.ColorDrawable content =
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);
        android.content.res.ColorStateList rippleColor =
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#33FFFFFF"));
        return new android.graphics.drawable.RippleDrawable(rippleColor, content, null);
    }

    private void showActionBottomSheet(Context ctx, Message m) {
        if (actionListener == null) return;

        // ── Step 1: Build emoji reaction row ──────────────────────────
        String[] QUICK_EMOJIS = {"\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE02",
                                  "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE21"};
        android.widget.LinearLayout emojiRow = new android.widget.LinearLayout(ctx);
        emojiRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        emojiRow.setGravity(android.view.Gravity.CENTER);
        int hPad = (int)(8 * ctx.getResources().getDisplayMetrics().density);
        int vPad = (int)(12 * ctx.getResources().getDisplayMetrics().density);
        emojiRow.setPadding(hPad, vPad, hPad, vPad);

        // Wrap in a container so AlertDialog can host it as a custom title
        android.widget.LinearLayout wrapper = new android.widget.LinearLayout(ctx);
        wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrapper.addView(emojiRow);

        // Keep a dialog reference so emoji tap can dismiss it
        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];

        for (String emoji : QUICK_EMOJIS) {
            android.widget.TextView tv = new android.widget.TextView(ctx);
            tv.setText(emoji);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28);
            int btnPad = (int)(10 * ctx.getResources().getDisplayMetrics().density);
            tv.setPadding(btnPad, btnPad / 2, btnPad, btnPad / 2);
            tv.setOnClickListener(v -> {
                actionListener.onReact(m, emoji);
                if (holder[0] != null) holder[0].dismiss();
            });
            emojiRow.addView(tv);
        }

        // FIX [P3-3]: "+" button → full emoji picker dialog (was dead/missing before)
        android.widget.TextView btnMore = new android.widget.TextView(ctx);
        btnMore.setText("+");
        btnMore.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        btnMore.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int morePad = (int)(10 * ctx.getResources().getDisplayMetrics().density);
        btnMore.setPadding(morePad, morePad / 2, morePad, morePad / 2);
        btnMore.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            showFullEmojiPicker(ctx, m);
        });
        emojiRow.addView(btnMore);

        // ── Step 2: Build action items list ───────────────────────────
        boolean isOwnMsg     = currentUid != null && currentUid.equals(m.senderId);
        boolean isTextMsg    = m.text != null && !m.text.trim().isEmpty()
                               && (m.type == null || "text".equals(m.type));
        boolean canEdit      = isOwnMsg && isTextMsg;
        boolean isStarred    = Boolean.TRUE.equals(m.starred);

        boolean isPinned = Boolean.TRUE.equals(m.pinned);

        java.util.List<String> optList = new java.util.ArrayList<>();
        optList.add("Reply");
        optList.add("Copy");
        optList.add(isStarred ? "Unstar" : "Star");
        optList.add(isPinned ? "Unpin" : "Pin");
        optList.add("Forward");
        if (canEdit) optList.add("Edit");
        optList.add("Delete");
        String[] options = optList.toArray(new String[0]);

        android.app.AlertDialog.Builder builder =
                new android.app.AlertDialog.Builder(ctx)
                    .setCustomTitle(wrapper)
                    .setItems(options, (d, which) -> {
                        String choice = options[which];
                        switch (choice) {
                            case "Reply":   actionListener.onReply(m);   break;
                            case "Copy":    actionListener.onCopy(m);    break;
                            case "Star":    // fall-through
                            case "Unstar":  actionListener.onStar(m);    break;
                            case "Pin":     // fall-through
                            case "Unpin":   actionListener.onPin(m);     break;
                            case "Forward": actionListener.onForward(m); break;
                            case "Edit":    actionListener.onEdit(m);    break;
                            case "Delete":  actionListener.onDelete(m);  break;
                        }
                    });
        holder[0] = builder.show();
    }

    // FIX [P3-3]: Full emoji picker — 8-column scrollable grid of common emojis
    private void showFullEmojiPicker(Context ctx, Message m) {
        if (actionListener == null) return;
        final String[] ALL_EMOJIS = {
            "❤️","👍","😂","😮","😢","😡","🙏","🔥","✅","💯",
            "👏","🤣","😍","😎","🤔","😴","🥳","😅","🤩","🥰",
            "💀","🤯","😱","🤗","😇","🙄","😑","🤐","🫡","💪",
            "👀","✌️","🤞","🫶","❤️‍🔥","💔","💕","💖","💘","🫂",
            "🎉","🎊","🎈","🏆","⭐","🌟","💫","✨","🌈","☀️",
            "😁","😆","🤭","😜","😝","🥹","🥺","😭","😤","😠",
            "👋","🤙","🖐️","✋","👊","🫸","💅","🫰","👌","🤌",
            "🙌","🤜","🤛","🫵","☝️","👈","👉","👆","👇","🤷"
        };
        android.widget.GridView grid = new android.widget.GridView(ctx);
        grid.setNumColumns(8);
        grid.setPadding(12, 12, 12, 12);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                ctx, android.R.layout.simple_list_item_1, ALL_EMOJIS) {
            @Override public android.view.View getView(int pos, android.view.View cv, android.view.ViewGroup parent) {
                android.widget.TextView tv = (android.widget.TextView)
                        super.getView(pos, cv, parent);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setPadding(4, 8, 4, 8);
                return tv;
            }
        };
        grid.setAdapter(adapter);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ctx)
                .setTitle("Pick an emoji")
                .setView(grid)
                .create();
        grid.setOnItemClickListener((parent, v, pos, id) -> {
            actionListener.onReact(m, ALL_EMOJIS[pos]);
            dialog.dismiss();
        });
        dialog.show();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        if (holder.ivImage != null) Glide.with(holder.ivImage).clear(holder.ivImage);
    }

    // ──────────────────────────────────────────────────────────────
    // Font Style helper — TypingStyleManager.STYLE_* (0–19) ko TextView pe apply
    // ──────────────────────────────────────────────────────────────
    private static void applyFontStyle(TextView tv, int styleId) {
        switch (styleId) {
            case com.callx.app.utils.TypingStyleManager.STYLE_BOLD:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_ITALIC:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_BOLD_ITALIC:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG:
                try {
                    android.graphics.Typeface samsungTf = android.graphics.Typeface.create("SamsungOne", android.graphics.Typeface.NORMAL);
                    if (samsungTf != null && !samsungTf.equals(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL))) {
                        tv.setTypeface(samsungTf);
                    } else {
                        android.graphics.Typeface alt = android.graphics.Typeface.create("samsung-sans", android.graphics.Typeface.NORMAL);
                        if (alt != null && !alt.equals(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL))) {
                            tv.setTypeface(alt);
                        } else {
                            tv.setTypeface(android.graphics.Typeface.SERIF);
                        }
                    }
                } catch (Exception e) {
                    tv.setTypeface(android.graphics.Typeface.SERIF);
                }
                break;
            case com.callx.app.utils.TypingStyleManager.STYLE_MONOSPACE:
                tv.setTypeface(android.graphics.Typeface.MONOSPACE); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SERIF:
                tv.setTypeface(android.graphics.Typeface.SERIF); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SERIF_BOLD:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_CONDENSED:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_LIGHT:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_HANDWRITING:
                tv.setTypeface(android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_MEDIUM:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_THIN:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SERIF_ITALIC:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_CONDENSED_BOLD:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_BLACK:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_CURSIVE:
                tv.setTypeface(android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SANS_MEDIUM:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_MONO_BOLD:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_LIGHT_ITALIC:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_CLASSIC_BOLD:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG_SCRIPT:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_NORMAL:
            default:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)); break;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // ViewHolder — covers all view IDs used in both item layouts
    // ──────────────────────────────────────────────────────────────
    static class VH extends RecyclerView.ViewHolder {
        TextView     tvMessage, tvTime, tvSenderName, tvFileName;
        TextView     tvDateHeader;   // date separator chip (Today / Yesterday / MMM d)
        ImageView    ivImage;
        TextView     tvStatus;   // tv_status in both item layouts
        LinearLayout llAudio, llFile;
        ImageButton  btnPlayPause;
        ImageView    btnDownload;
        // FIX: SeekBar + duration label — wired for live progress updates
        android.widget.SeekBar seekAudio;
        TextView     tvAudioDur;
        // SwipeReplySystem v1: reply preview views
        LinearLayout llReplyPreview;
        TextView     tvReplySender, tvReplyText;
        ImageView    ivReplyThumb;
        // Reactions row (ll_reactions / tv_reactions in both item layouts)
        LinearLayout llReactions;
        TextView     tvReactions;
        // POLISH: Video — proper FrameLayout with thumbnail + play overlay
        android.widget.FrameLayout flVideo;
        ImageView    ivVideoThumb;
        TextView     tvDuration;
        // POLISH: Link preview card — visible only for text messages with URLs
        LinearLayout llLinkPreview;
        TextView     tvLinkTitle, tvLinkDomain;
        ImageView    ivLinkThumb;

        VH(@NonNull View v) {
            super(v);
            tvMessage      = v.findViewById(R.id.tv_message);
            tvTime         = v.findViewById(R.id.tv_time);
            tvSenderName   = v.findViewById(R.id.tv_sender_name);
            tvDateHeader   = v.findViewById(R.id.tv_date_header);
            ivImage        = v.findViewById(R.id.iv_image);
            tvStatus       = v.findViewById(R.id.tv_status);
            llAudio        = v.findViewById(R.id.ll_audio);
            btnPlayPause   = v.findViewById(R.id.btn_play_pause);
            // FIX: bind SeekBar so we can update progress during playback
            seekAudio      = v.findViewById(R.id.seek_audio);
            tvAudioDur     = v.findViewById(R.id.tv_audio_dur);
            llFile         = v.findViewById(R.id.ll_file);
            tvFileName     = v.findViewById(R.id.tv_file_name);
            btnDownload    = v.findViewById(R.id.btn_download);
            // SwipeReplySystem v1
            llReplyPreview = v.findViewById(R.id.ll_reply_preview);
            tvReplySender  = v.findViewById(R.id.tv_reply_sender);
            tvReplyText    = v.findViewById(R.id.tv_reply_text);
            ivReplyThumb   = v.findViewById(R.id.iv_reply_thumb);
            // Reactions
            llReactions    = v.findViewById(R.id.ll_reactions);
            tvReactions    = v.findViewById(R.id.tv_reactions);
            // POLISH: Video FrameLayout with thumbnail + play overlay
            flVideo        = v.findViewById(R.id.fl_video);
            ivVideoThumb   = v.findViewById(R.id.iv_video_thumb);
            tvDuration     = v.findViewById(R.id.tv_duration);
            // POLISH: Link preview card
            llLinkPreview  = v.findViewById(R.id.ll_link_preview);
            tvLinkTitle    = v.findViewById(R.id.tv_link_title);
            tvLinkDomain   = v.findViewById(R.id.tv_link_domain);
            ivLinkThumb    = v.findViewById(R.id.iv_link_thumb);
        }
    }
}
