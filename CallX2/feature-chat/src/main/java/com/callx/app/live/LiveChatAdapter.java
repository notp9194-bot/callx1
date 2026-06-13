package com.callx.app.live;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import java.util.List;

public class LiveChatAdapter extends RecyclerView.Adapter<LiveChatAdapter.VH> {

    public static class LiveMessage {
        public String senderName;
        public String text;
        public long timestamp;
        public LiveMessage() {}
        public LiveMessage(String senderName, String text, long timestamp) {
            this.senderName = senderName;
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    private final List<LiveMessage> messages;

    public LiveChatAdapter(List<LiveMessage> messages) {
        this.messages = messages;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_live_chat_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        LiveMessage msg = messages.get(pos);
        h.tvSender.setText(msg.senderName != null ? msg.senderName : "");
        h.tvText.setText(msg.text != null ? msg.text : "");
    }

    @Override public int getItemCount() { return messages.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvSender, tvText;
        VH(View v) {
            super(v);
            tvSender = v.findViewById(R.id.tv_live_msg_sender);
            tvText   = v.findViewById(R.id.tv_live_msg_text);
        }
    }
}
