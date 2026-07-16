package com.callx.app.messages;

import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.player.XImageViewerActivity;
import com.callx.app.player.XVideoPlayerActivity;
import com.callx.app.x.R;
import java.util.*;

/**
 * XGroupDMAdapter — RecyclerView adapter for X Group DM conversations.
 *
 * View types:
 *  VT_MY_TEXT     — sent text bubble (right-aligned)
 *  VT_OTHER_TEXT  — received text bubble with avatar + sender name
 *  VT_MY_MEDIA    — sent image/video
 *  VT_OTHER_MEDIA — received image/video with avatar + sender name
 *  VT_SYSTEM      — system event (member joined/left, group renamed)
 *
 * Uses manual DiffUtil for smooth, minimal rebinds — same pattern as XDMAdapter.
 */
public class XGroupDMAdapter extends RecyclerView.Adapter<XGroupDMAdapter.VH> {

    private static final int VT_MY_TEXT     = 0;
    private static final int VT_OTHER_TEXT  = 1;
    private static final int VT_MY_MEDIA    = 2;
    private static final int VT_OTHER_MEDIA = 3;
    private static final int VT_SYSTEM      = 4;

    public interface OnReactionListener {
        void onLongPress(XGroupDMMessage msg, View anchor);
        void onReact(XGroupDMMessage msg, String emoji);
    }

    private final Context ctx;
    private final String myUid;
    private final String groupId;
    private final List<XGroupDMMessage> msgs = new ArrayList<>();
    private OnReactionListener reactionListener;

    public XGroupDMAdapter(Context ctx, String myUid, String groupId) {
        this.ctx     = ctx;
        this.myUid   = myUid;
        this.groupId = groupId;
        setHasStableIds(true);
    }

    public void setReactionListener(OnReactionListener l) { this.reactionListener = l; }

    @Override public long getItemId(int pos) {
        String id = msgs.get(pos).id;
        return id != null ? id.hashCode() : pos;
    }

    /** Replaces the list with DiffUtil so only changed rows rebind. */
    public void setMessages(List<XGroupDMMessage> list) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return msgs.size(); }
            @Override public int getNewListSize() { return list.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return Objects.equals(msgs.get(o).id, list.get(n).id);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                XGroupDMMessage om = msgs.get(o), nm = list.get(n);
                return Objects.equals(om.text, nm.text)
                    && Objects.equals(om.reactions, nm.reactions)
                    && om.seenByCount == nm.seenByCount;
            }
        });
        msgs.clear();
        msgs.addAll(list);
        diff.dispatchUpdatesTo(this);
    }

    @Override public int getItemViewType(int pos) {
        XGroupDMMessage m = msgs.get(pos);
        if (m.isSystemMessage) return VT_SYSTEM;
        boolean isMine = myUid.equals(m.senderUid);
        boolean hasMedia = m.mediaUrl != null && !m.mediaUrl.isEmpty();
        if (isMine)  return hasMedia ? VT_MY_MEDIA    : VT_MY_TEXT;
        else         return hasMedia ? VT_OTHER_MEDIA : VT_OTHER_TEXT;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
        int layout;
        switch (vt) {
            case VT_OTHER_TEXT:  layout = R.layout.item_x_gdm_recv_text;  break;
            case VT_MY_MEDIA:    layout = R.layout.item_x_gdm_sent_media; break;
            case VT_OTHER_MEDIA: layout = R.layout.item_x_gdm_recv_media; break;
            case VT_SYSTEM:      layout = R.layout.item_x_gdm_system;     break;
            default:             layout = R.layout.item_x_gdm_sent_text;  break;
        }
        // Fallback: inflate sent text if a layout file is missing
        View v;
        try {
            v = LayoutInflater.from(ctx).inflate(layout, p, false);
        } catch (Exception e) {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_x_dm_sent, p, false);
        }
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        h.bind(msgs.get(pos));
    }

    @Override public int getItemCount() { return msgs.size(); }

    // ─────────────────────────────────────────────────────────────────────
    class VH extends RecyclerView.ViewHolder {
        // Common
        TextView  tvText, tvTime, tvSenderName, tvReplyPreview, tvSystemMsg;
        ImageView ivAvatar, ivMedia;
        LinearLayout llReactions;

        VH(View v) {
            super(v);
            tvText        = v.findViewById(R.id.tv_gdm_text);
            tvTime        = v.findViewById(R.id.tv_gdm_time);
            tvSenderName  = v.findViewById(R.id.tv_gdm_sender_name);
            tvReplyPreview= v.findViewById(R.id.tv_gdm_reply_preview);
            tvSystemMsg   = v.findViewById(R.id.tv_gdm_system);
            ivAvatar      = v.findViewById(R.id.iv_gdm_avatar);
            ivMedia       = v.findViewById(R.id.iv_gdm_media);
            llReactions   = v.findViewById(R.id.ll_gdm_reactions);
        }

        void bind(XGroupDMMessage m) {
            if (m.isSystemMessage) {
                if (tvSystemMsg != null)
                    tvSystemMsg.setText(m.systemText != null ? m.systemText : "");
                return;
            }

            // Sender name (for received messages)
            if (tvSenderName != null) {
                tvSenderName.setText(m.senderName != null ? m.senderName : "");
            }

            // Avatar (for received messages)
            if (ivAvatar != null) {
                if (m.senderThumbUrl != null && !m.senderThumbUrl.isEmpty()) {
                    Glide.with(ctx).load(m.senderThumbUrl)
                            .circleCrop()
                            .placeholder(R.drawable.ic_person)
                            .into(ivAvatar);
                } else {
                    ivAvatar.setImageResource(R.drawable.ic_person);
                }
            }

            // Text
            if (tvText != null) {
                if (m.text != null && !m.text.isEmpty()) {
                    tvText.setVisibility(View.VISIBLE);
                    tvText.setText(m.text);
                } else {
                    tvText.setVisibility(View.GONE);
                }
            }

            // Time
            if (tvTime != null && m.timestamp > 0) {
                tvTime.setText(formatTime(m.timestamp));
            }

            // Reply preview
            if (tvReplyPreview != null) {
                if (m.replyToMsgId != null && !m.replyToMsgId.isEmpty()) {
                    tvReplyPreview.setVisibility(View.VISIBLE);
                    String preview = m.replyToSenderName != null
                            ? m.replyToSenderName + ": " : "";
                    preview += m.replyToText != null ? m.replyToText : "Message";
                    tvReplyPreview.setText(preview);
                } else {
                    tvReplyPreview.setVisibility(View.GONE);
                }
            }

            // Media
            if (ivMedia != null) {
                if (m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                    ivMedia.setVisibility(View.VISIBLE);
                    Glide.with(ctx).load(m.mediaUrl)
                            .placeholder(R.color.md_grey_200)
                            .into(ivMedia);
                    ivMedia.setOnClickListener(v -> {
                        if ("video".equals(m.mediaType)) {
                            ctx.startActivity(new Intent(ctx, XVideoPlayerActivity.class)
                                    .putExtra("url", m.mediaUrl));
                        } else {
                            ctx.startActivity(new Intent(ctx, XImageViewerActivity.class)
                                    .putExtra("url", m.mediaUrl));
                        }
                    });
                } else {
                    ivMedia.setVisibility(View.GONE);
                }
            }

            // Reactions
            if (llReactions != null) {
                llReactions.removeAllViews();
                if (m.reactions != null && !m.reactions.isEmpty()) {
                    llReactions.setVisibility(View.VISIBLE);
                    for (Map.Entry<String, Map<String, Boolean>> entry : m.reactions.entrySet()) {
                        if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
                        TextView chip = new TextView(ctx);
                        chip.setText(entry.getKey() + " " + entry.getValue().size());
                        chip.setPadding(12, 4, 12, 4);
                        chip.setTextSize(12f);
                        // Highlight if I reacted
                        if (Boolean.TRUE.equals(entry.getValue().get(myUid))) {
                            chip.setBackgroundResource(R.drawable.bg_reaction_chip_active);
                        } else {
                            chip.setBackgroundResource(R.drawable.bg_reaction_chip);
                        }
                        chip.setOnClickListener(v -> {
                            if (reactionListener != null)
                                reactionListener.onReact(m, entry.getKey());
                        });
                        llReactions.addView(chip);
                    }
                } else {
                    llReactions.setVisibility(View.GONE);
                }
            }

            // Long-press → reaction picker
            itemView.setOnLongClickListener(v -> {
                if (reactionListener != null) reactionListener.onLongPress(m, v);
                return true;
            });
        }

        private String formatTime(long ts) {
            return (String) DateUtils.getRelativeTimeSpanString(
                    ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);
        }
    }
}
