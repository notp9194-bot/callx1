package com.callx.app.conversation.popup;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.models.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal bubble-list adapter for the mini popup chat window — intentionally
 * lightweight (no media/reactions/swipe-reply etc, just text bubbles left/
 * right) since the popup is a quick-reply surface, not the full chat UI.
 */
public class MiniChatPopupAdapter extends RecyclerView.Adapter<MiniChatPopupAdapter.VH> {

    private final List<Message> messages = new ArrayList<>();
    private final String currentUid;

    public MiniChatPopupAdapter(String currentUid) {
        this.currentUid = currentUid;
    }

    public void submitList(List<Message> newMessages) {
        messages.clear();
        if (newMessages != null) messages.addAll(newMessages);
        notifyDataSetChanged();
    }

    public void addMessage(Message m) {
        messages.add(m);
        notifyItemInserted(messages.size() - 1);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mini_chat_popup_bubble, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Message m = messages.get(position);
        boolean isMine = m.senderId != null && m.senderId.equals(currentUid);

        holder.text.setText(m.text != null ? m.text : "");
        holder.text.setBackgroundResource(isMine
                ? R.drawable.bubble_sent
                : R.drawable.bubble_received);

        holder.root.setGravity(isMine ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) holder.text.getLayoutParams();
        textLp.gravity = isMine ? Gravity.END : Gravity.START;
        holder.text.setLayoutParams(textLp);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final TextView text;
        VH(@NonNull View itemView) {
            super(itemView);
            root = (LinearLayout) itemView;
            text = itemView.findViewById(R.id.tv_popup_bubble_text);
        }
    }
}
