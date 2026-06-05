package com.callx.app.search;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.MessageEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MessageSearchAdapter — Shows search results with highlighted matched text.
 */
public class MessageSearchAdapter
        extends ListAdapter<MessageEntity, MessageSearchAdapter.VH> {

    public interface OnResultClick {
        void onClick(MessageEntity msg);
    }

    private static final DiffUtil.ItemCallback<MessageEntity> DIFF =
        new DiffUtil.ItemCallback<MessageEntity>() {
            @Override public boolean areItemsTheSame(@NonNull MessageEntity a, @NonNull MessageEntity b) {
                return a.id.equals(b.id);
            }
            @Override public boolean areContentsTheSame(@NonNull MessageEntity a, @NonNull MessageEntity b) {
                return a.id.equals(b.id) && a.text != null && a.text.equals(b.text);
            }
        };

    private final String   inChatId;
    private final OnResultClick listener;
    private String query = "";

    public MessageSearchAdapter(String inChatId, OnResultClick listener) {
        super(DIFF);
        this.inChatId = inChatId;
        this.listener = listener;
    }

    public void setQuery(String q) {
        this.query = q.toLowerCase(Locale.getDefault());
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message_search_result, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        MessageEntity msg = getItem(pos);
        h.bind(msg, query, inChatId == null, listener);
    }

    static class VH extends RecyclerView.ViewHolder {
        private final TextView tvSender;
        private final TextView tvChatId;
        private final TextView tvText;
        private final TextView tvTime;

        VH(View v) {
            super(v);
            tvSender = v.findViewById(R.id.tv_sender);
            tvChatId = v.findViewById(R.id.tv_chat_label);
            tvText   = v.findViewById(R.id.tv_message_text);
            tvTime   = v.findViewById(R.id.tv_time);
        }

        void bind(MessageEntity msg, String query, boolean showChat, OnResultClick listener) {
            tvSender.setText(msg.senderName != null ? msg.senderName : "Unknown");
            tvChatId.setVisibility(showChat ? View.VISIBLE : View.GONE);
            if (showChat) tvChatId.setText("Chat: " + (msg.chatId != null ? msg.chatId : ""));

            String rawText = msg.text != null ? msg.text : "[" + msg.type + "]";
            tvText.setText(highlight(rawText, query));

            if (msg.timestamp != null) {
                tvTime.setText(new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                        .format(new Date(msg.timestamp)));
            }

            itemView.setOnClickListener(v -> listener.onClick(msg));
        }

        private CharSequence highlight(String text, String query) {
            if (query.isEmpty()) return text;
            SpannableString ss = new SpannableString(text);
            String lower = text.toLowerCase(Locale.getDefault());
            int idx = lower.indexOf(query);
            while (idx >= 0) {
                ss.setSpan(new BackgroundColorSpan(0xFFFFEB3B), idx, idx + query.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new StyleSpan(Typeface.BOLD), idx, idx + query.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                idx = lower.indexOf(query, idx + query.length());
            }
            return ss;
        }
    }
}
