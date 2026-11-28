package com.callx.app.smallwindow;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.R;
import com.callx.app.utils.BubbleColorStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal message-bubble adapter for the floating small window mini chat.
 * Not the full ChatActivity pipeline (no media, reactions, replies, paging) —
 * just enough to actually read + send text in-place without reopening the app.
 */
public class SmallWindowMessageAdapter extends RecyclerView.Adapter<SmallWindowMessageAdapter.VH> {

    /** One row's worth of data — deliberately tiny, not the full core Message model. */
    public static class SwMsg {
        public final String id;
        public final String senderId;
        public final String text;
        public final String type;
        public final long   timestamp;

        public SwMsg(String id, String senderId, String text, String type, long timestamp) {
            this.id = id;
            this.senderId = senderId;
            this.text = text;
            this.type = type;
            this.timestamp = timestamp;
        }
    }

    private final List<SwMsg> items = new ArrayList<>();
    private String myUid;

    public void setMyUid(String uid) { this.myUid = uid; }

    /** Replace the whole list (called on every Firebase update — list is capped small). */
    public void submit(List<SwMsg> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public int getSize() { return items.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sw_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        SwMsg m = items.get(position);
        boolean isMine = myUid != null && myUid.equals(m.senderId);

        holder.bubble.setText(previewFor(m.type, m.text));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        holder.row.setLayoutParams(rowLp);
        holder.row.setGravity(isMine ? Gravity.END : Gravity.START);

        // v928: honour the user's custom bubble colour (0 from the store == theme default -> stock drawable)
        BubbleColorStore.ensureLoaded(holder.itemView.getContext());
        final int custom = BubbleColorStore.bubbleColor(isMine, 0);
        if (custom != 0) {
            final float d = holder.itemView.getResources().getDisplayMetrics().density;
            final float big = 12f * d, small = 2f * d;
            GradientDrawable g = new GradientDrawable();
            g.setColor(custom);
            // same corner shape as bg_sw_bubble_sent / bg_sw_bubble_received (tail corner = 2dp)
            g.setCornerRadii(isMine
                    ? new float[]{big, big, big, big, small, small, big, big}
                    : new float[]{big, big, big, big, big, big, small, small});
            holder.bubble.setBackground(g);
            holder.bubble.setTextColor(BubbleColorStore.textColor(isMine, holder.defaultTextColor));
        } else {
            holder.bubble.setBackgroundResource(
                    isMine ? R.drawable.bg_sw_bubble_sent : R.drawable.bg_sw_bubble_received);
            holder.bubble.setTextColor(holder.defaultTextColor);
        }

        int maxWidthPx = (int) (holder.itemView.getResources().getDisplayMetrics().density * 190);
        holder.bubble.setMaxWidth(maxWidthPx);
    }

    @Override
    public int getItemCount() { return items.size(); }

    private String previewFor(String type, String text) {
        if (type == null) type = "text";
        switch (type) {
            case "image":      return "📷 Photo";
            case "video":      return "🎥 Video";
            case "audio":      return "🎤 Voice message";
            case "file":       return "📄 Document";
            case "reel_share": return "🎬 Shared a reel";
            default:
                return text != null && !text.isEmpty() ? text : "…";
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final LinearLayout row;
        final TextView      bubble;
        final int           defaultTextColor;

        VH(View itemView) {
            super(itemView);
            row    = itemView.findViewById(R.id.ll_sw_msg_row);
            bubble = itemView.findViewById(R.id.tv_sw_msg_bubble);
            defaultTextColor = bubble.getCurrentTextColor();
        }
    }
}
