package com.callx.app.adapters;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.MessageEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SearchInChatAdapter — RecyclerView adapter for in-chat search results.
 *
 * Features:
 *   - Keyword highlighting (yellow background + bold) in matched text
 *   - Message type icons (text / image / audio / video / file)
 *   - Sender name + formatted timestamp per row
 *   - Selected row highlight (blue tint) for prev/next navigation
 *   - Click listener → passes MessageEntity to caller for scroll-to
 */
public class SearchInChatAdapter extends RecyclerView.Adapter<SearchInChatAdapter.VH> {

    public interface OnResultClickListener {
        void onClick(MessageEntity message);
    }

    // ── State ──────────────────────────────────────────────────────────────
    private final List<MessageEntity>    results  = new ArrayList<>();
    private       String                 query    = "";
    private       int                    highlighted = -1;
    private final OnResultClickListener  listener;
    private final SimpleDateFormat       timeFmt  =
            new SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault());

    public SearchInChatAdapter(OnResultClickListener listener) {
        this.listener = listener;
    }

    // ─────────────────────────────────────────────────────────────────────
    // DATA
    // ─────────────────────────────────────────────────────────────────────

    public void submitResults(List<MessageEntity> newResults, String query) {
        this.results.clear();
        this.results.addAll(newResults);
        this.query       = query;
        this.highlighted = -1;
        notifyDataSetChanged();
    }

    public void setHighlightedIndex(int index) {
        int old      = highlighted;
        highlighted  = index;
        if (old >= 0 && old < results.size()) notifyItemChanged(old);
        if (index >= 0 && index < results.size()) notifyItemChanged(index);
    }

    // ─────────────────────────────────────────────────────────────────────
    // VIEWHOLDER
    // ─────────────────────────────────────────────────────────────────────

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_result, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MessageEntity msg = results.get(position);

        // ── Row highlight (selected via prev/next) ──
        int bgColor = position == highlighted
                ? 0x220D47A1   // light blue tint
                : android.graphics.Color.TRANSPARENT;
        h.itemView.setBackgroundColor(bgColor);

        // ── Sender name ──
        h.tvSender.setText(msg.senderName != null ? msg.senderName : "You");

        // ── Timestamp ──
        if (msg.timestamp != null) {
            h.tvTime.setText(timeFmt.format(new Date(msg.timestamp)));
        } else {
            h.tvTime.setText("");
        }

        // ── Message type icon + preview text ──
        bindTypeAndPreview(h, msg);

        // ── Click ──
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(msg);
        });
    }

    private void bindTypeAndPreview(@NonNull VH h, MessageEntity msg) {
        String type = msg.type != null ? msg.type : "text";
        String text = msg.text;

        switch (type) {
            case "image":
                h.ivTypeIcon.setImageResource(R.drawable.ic_gallery);
                h.tvPreview.setText(buildHighlighted(
                        text != null && !text.isEmpty() ? text : "📷 Photo", query));
                break;

            case "video":
                h.ivTypeIcon.setImageResource(R.drawable.ic_video);
                h.tvPreview.setText(buildHighlighted(
                        text != null && !text.isEmpty() ? text : "🎬 Video", query));
                break;

            case "audio":
                h.ivTypeIcon.setImageResource(R.drawable.ic_audio);
                h.tvPreview.setText("🎤 Voice message");
                break;

            case "file":
                h.ivTypeIcon.setImageResource(R.drawable.ic_file);
                String fileName = msg.fileName != null ? msg.fileName : "File";
                h.tvPreview.setText(buildHighlighted(fileName, query));
                break;

            default: // text
                h.ivTypeIcon.setImageResource(R.drawable.ic_message_notification);
                h.tvPreview.setText(buildHighlighted(text != null ? text : "", query));
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // KEYWORD HIGHLIGHTING
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns a SpannableString with all occurrences of [query] highlighted.
     * Highlight style: yellow background + bold + dark text.
     */
    private SpannableString buildHighlighted(String text, String query) {
        SpannableString ss = new SpannableString(text);
        if (query == null || query.isEmpty() || text == null) return ss;

        String lowerText  = text.toLowerCase(Locale.getDefault());
        String lowerQuery = query.toLowerCase(Locale.getDefault());

        int start = 0;
        while (start < lowerText.length()) {
            int idx = lowerText.indexOf(lowerQuery, start);
            if (idx < 0) break;
            int end = idx + lowerQuery.length();

            // Yellow background highlight
            ss.setSpan(new BackgroundColorSpan(0xFFFFD600),
                    idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Dark text for contrast
            ss.setSpan(new ForegroundColorSpan(0xFF1A1A1A),
                    idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Bold
            ss.setSpan(new StyleSpan(Typeface.BOLD),
                    idx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            start = end;
        }
        return ss;
    }

    // ─────────────────────────────────────────────────────────────────────
    // BOILERPLATE
    // ─────────────────────────────────────────────────────────────────────

    @Override public int getItemCount() { return results.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivTypeIcon;
        final TextView  tvSender;
        final TextView  tvPreview;
        final TextView  tvTime;

        VH(@NonNull View v) {
            super(v);
            ivTypeIcon = v.findViewById(R.id.iv_type_icon);
            tvSender   = v.findViewById(R.id.tv_sender);
            tvPreview  = v.findViewById(R.id.tv_preview);
            tvTime     = v.findViewById(R.id.tv_time);
        }
    }
}
