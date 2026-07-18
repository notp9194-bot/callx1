package com.callx.app.messages;

import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.player.XImageViewerActivity;
import com.callx.app.player.XVideoPlayerActivity;
import com.callx.app.models.XDMMessage;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import java.text.SimpleDateFormat;
import java.util.*;

public class XDMAdapter extends RecyclerView.Adapter<XDMAdapter.MsgVH> {

    private static final int VT_SENT = 0, VT_RECV = 1;
    private final Context ctx;
    private final String myUid;
    private final String convId;
    private final List<XDMMessage> msgs = new ArrayList<>();
    private OnReactionListener reactionListener;

    public interface OnReactionListener {
        void onReact(XDMMessage msg, String emoji);
        void onLongPress(XDMMessage msg, View anchor);
    }

    public XDMAdapter(Context ctx, String myUid, String convId) {
        this.ctx    = ctx;
        this.myUid  = myUid;
        this.convId = convId;
        setHasStableIds(true);
    }

    public XDMAdapter(Context ctx, String myUid) {
        this(ctx, myUid, null);
    }

    public void setReactionListener(OnReactionListener l) { this.reactionListener = l; }

    @Override public long getItemId(int pos) {
        String id = msgs.get(pos).id;
        return id != null ? id.hashCode() : pos;
    }

    public void setMessages(List<XDMMessage> list) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return msgs.size(); }
            @Override public int getNewListSize() { return list.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return Objects.equals(msgs.get(o).id, list.get(n).id);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                XDMMessage om = msgs.get(o), nm = list.get(n);
                return om.seen == nm.seen
                    && Objects.equals(om.text, nm.text)
                    && Objects.equals(om.reactions, nm.reactions);
            }
        });
        msgs.clear(); msgs.addAll(list);
        diff.dispatchUpdatesTo(this);
    }

    @Override public int getItemViewType(int pos) {
        return myUid.equals(msgs.get(pos).senderUid) ? VT_SENT : VT_RECV;
    }

    @NonNull @Override
    public MsgVH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
        int layout = vt == VT_SENT ? R.layout.item_x_dm_sent : R.layout.item_x_dm_recv;
        return new MsgVH(LayoutInflater.from(ctx).inflate(layout, p, false));
    }

    @Override public void onBindViewHolder(@NonNull MsgVH h, int pos) { h.bind(msgs.get(pos)); }
    @Override public int getItemCount() { return msgs.size(); }

    class MsgVH extends RecyclerView.ViewHolder {
        TextView tvText, tvTime, tvReadReceipt, tvReplyPreview, tvForwarded;
        ImageView ivMedia;
        LinearLayout llReactions;

        MsgVH(View v) {
            super(v);
            tvText        = v.findViewById(R.id.tv_dm_text);
            tvTime        = v.findViewById(R.id.tv_dm_time);
            tvReadReceipt = v.findViewById(R.id.tv_dm_read_receipt);
            tvReplyPreview= v.findViewById(R.id.tv_dm_reply_preview);
            tvForwarded   = v.findViewById(R.id.tv_dm_forwarded);
            ivMedia       = v.findViewById(R.id.iv_dm_media);
            llReactions   = v.findViewById(R.id.ll_dm_reactions);
        }

        void bind(XDMMessage m) {
            // Reply preview
            if (tvReplyPreview != null) {
                if (m.replyToText != null && !m.replyToText.isEmpty()) {
                    tvReplyPreview.setVisibility(View.VISIBLE);
                    tvReplyPreview.setText("↩ " + m.replyToText);
                } else {
                    tvReplyPreview.setVisibility(View.GONE);
                }
            }

            // Forwarded label
            if (tvForwarded != null)
                tvForwarded.setVisibility(m.forwarded ? View.VISIBLE : View.GONE);

            // Text
            if (tvText != null) {
                tvText.setVisibility(m.text != null && !m.text.isEmpty() ? View.VISIBLE : View.GONE);
                if (m.text != null) tvText.setText(m.text);
            }

            // Media
            if (ivMedia != null) {
                if (m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                    ivMedia.setVisibility(View.VISIBLE);
                    .override(720, 720)
                    Glide.with(ctx).load(m.mediaUrl).centerCrop().override(720, 720).into(ivMedia);
                    ivMedia.setOnClickListener(v -> {
                        if ("video".equals(m.mediaType)) {
                            ctx.startActivity(new Intent(ctx, XVideoPlayerActivity.class)
                                .putExtra("video_url", m.mediaUrl));
                        } else {
                            ctx.startActivity(new Intent(ctx, XImageViewerActivity.class)
                                .putExtra("image_url", m.mediaUrl));
                        }
                    });
                } else {
                    ivMedia.setVisibility(View.GONE);
                }
            }

            // Time
            if (tvTime != null)
                tvTime.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.timestamp)));

            // Read receipt (sent messages only)
            if (tvReadReceipt != null) {
                boolean isSent = myUid.equals(m.senderUid);
                tvReadReceipt.setVisibility(isSent ? View.VISIBLE : View.GONE);
                if (isSent) {
                    if (m.seen) {
                        tvReadReceipt.setText("✓✓ Seen");
                        tvReadReceipt.setTextColor(ctx.getColor(R.color.x_accent));
                    } else {
                        tvReadReceipt.setText("✓ Sent");
                        tvReadReceipt.setTextColor(ctx.getColor(R.color.x_text_secondary));
                    }
                }
            }

            // Emoji reactions
            bindReactions(m);

            // Long-press for reaction picker / options
            itemView.setOnLongClickListener(v -> {
                if (reactionListener != null) reactionListener.onLongPress(m, v);
                return true;
            });
        }

        private void bindReactions(XDMMessage m) {
            if (llReactions == null) return;
            if (m.reactions == null || m.reactions.isEmpty()) {
                llReactions.setVisibility(View.GONE); return;
            }
            llReactions.setVisibility(View.VISIBLE);
            llReactions.removeAllViews();
            for (Map.Entry<String, Map<String, Boolean>> entry : m.reactions.entrySet()) {
                String emoji = entry.getKey();
                int count = entry.getValue() != null ? entry.getValue().size() : 0;
                if (count == 0) continue;
                TextView tv = new TextView(ctx);
                boolean myReaction = m.hasReacted(myUid, emoji);
                tv.setText(emoji + " " + count);
                tv.setPadding(12, 4, 12, 4);
                tv.setTextSize(13f);
                tv.setBackgroundResource(myReaction
                    ? R.drawable.bg_x_reaction_mine : R.drawable.bg_x_reaction);
                tv.setOnClickListener(v -> {
                    if (reactionListener != null) reactionListener.onReact(m, emoji);
                });
                llReactions.addView(tv);
            }
        }
    }
}
