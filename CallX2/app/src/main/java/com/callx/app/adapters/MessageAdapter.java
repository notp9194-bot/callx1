package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.activities.MediaViewerActivity;
import com.callx.app.models.Message;
import com.callx.app.utils.FileUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Production-grade MessageAdapter supporting:
 *  1. Read receipts       (✓ / ✓✓ grey / ✓✓ blue)
 *  2. Reply/quote preview
 *  3. Emoji reactions     (aggregated pill below bubble)
 *  4. Edited indicator
 *  5. Deleted state
 *  6. Forwarded label
 *  7. Starred icon
 *  8. Pinned label
 *  Long-press → Material bottom-sheet with emoji row + action list
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    // ── Callback interface for activities to handle actions ────────────────
    public interface ActionListener {
        void onReply(Message m);
        void onEdit(Message m);
        void onDelete(Message m);
        void onReact(Message m, String emoji);
        void onForward(Message m);
        void onStar(Message m);
        void onPin(Message m);
        void onReactionTap(Message m);   // tap reaction bar → see who reacted
        void onCopy(Message m);             // copy text to clipboard
    }

    private final List<Message> messages;
    private final String currentUid;
    private final boolean isGroup;
    private ActionListener actionListener;

    private static final int TYPE_SENT = 1, TYPE_RECEIVED = 2;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    // Audio playback state
    private MediaPlayer player;
    private int playingPos = -1;

    // Feature 9: search highlight
    private String searchHighlight = null;
    private int searchHighlightPos = -1;

    public MessageAdapter(List<Message> messages, String currentUid, boolean isGroup) {
        this.messages   = messages;
        this.currentUid = currentUid;
        this.isGroup    = isGroup;
        setHasStableIds(false);
    }

    public void setActionListener(ActionListener l) { this.actionListener = l; }

    /** Called by ChatActivity to highlight a search match. pos=-1 clears. */
    public void setSearchHighlight(String query, int pos) {
        this.searchHighlight = query;
        this.searchHighlightPos = pos;
        notifyDataSetChanged();
    }

    // ── Item type ──────────────────────────────────────────────────────────
    @Override public int getItemViewType(int pos) {
        Message m = messages.get(pos);
        return currentUid.equals(m.senderId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    // ── Inflate ────────────────────────────────────────────────────────────
    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_SENT
                ? R.layout.item_message_sent
                : R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    // ── Bind ───────────────────────────────────────────────────────────────
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m   = messages.get(pos);
        Context ctx = h.itemView.getContext();
        boolean sent = getItemViewType(pos) == TYPE_SENT;

        // Reset all content views to gone
        h.tvMessage.setVisibility(View.GONE);
        h.ivImage.setVisibility(View.GONE);
        if (h.flVideo  != null) h.flVideo.setVisibility(View.GONE);
        if (h.llAudio  != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile   != null) h.llFile.setVisibility(View.GONE);
        if (h.tvEdited != null) h.tvEdited.setVisibility(View.GONE);

        // ── Feature 8: Pinned label ───────────────────────────────────────
        if (h.tvPinnedLabel != null)
            h.tvPinnedLabel.setVisibility(
                    Boolean.TRUE.equals(m.pinned) ? View.VISIBLE : View.GONE);

        // ── Feature 6: Forwarded label ────────────────────────────────────
        if (h.tvForwarded != null) {
            boolean fwd = m.forwardedFrom != null && !m.forwardedFrom.isEmpty();
            h.tvForwarded.setVisibility(fwd ? View.VISIBLE : View.GONE);
            if (fwd) h.tvForwarded.setText("↪ Forwarded from " + m.forwardedFrom);
        }

        // ── Group sender name ─────────────────────────────────────────────
        if (h.tvSenderName != null) {
            boolean show = isGroup && !sent;
            h.tvSenderName.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) h.tvSenderName.setText(
                    m.senderName != null ? m.senderName : "Unknown");
        }

        // ── Feature 2: Reply preview ──────────────────────────────────────
        if (h.llReplyPreview != null) {
            boolean hasReply = m.replyToText != null && !m.replyToText.isEmpty();
            h.llReplyPreview.setVisibility(hasReply ? View.VISIBLE : View.GONE);
            if (hasReply) {
                if (h.tvReplySender != null)
                    h.tvReplySender.setText(
                            m.replyToSenderName != null ? m.replyToSenderName : "");
                if (h.tvReplyText != null)
                    h.tvReplyText.setText(m.replyToText);
            }
        }

        // ── Feature 5: Deleted state ──────────────────────────────────────
        if (Boolean.TRUE.equals(m.deleted)) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText("🚫  This message was deleted");
            h.tvMessage.setTextColor(0xFF9E9E9E);
            h.tvMessage.setTextSize(13f);
            h.tvMessage.setTypeface(null, android.graphics.Typeface.ITALIC);
            bindFooter(h, m, sent);
            setupLongPress(h, m, sent, ctx);
            return;
        }

        // ── Resolve type ──────────────────────────────────────────────────
        String type = m.type == null ? "text" : m.type;
        if ("image".equals(type) || (m.imageUrl != null && !m.imageUrl.isEmpty()
                && (m.mediaUrl == null || m.mediaUrl.isEmpty()))) {
            type = "image";
        }

        switch (type) {
            case "image": {
                h.ivImage.setVisibility(View.VISIBLE);
                String url = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
                Glide.with(ctx).load(url)
                        .placeholder(R.drawable.bg_circle_white)
                        .into(h.ivImage);
                final String finalUrl = url;
                h.ivImage.setOnClickListener(v -> openMedia(ctx, finalUrl, "image"));
                break;
            }
            case "video": {
                if (h.flVideo != null) {
                    h.flVideo.setVisibility(View.VISIBLE);
                    String thumb = m.thumbnailUrl != null ? m.thumbnailUrl : m.mediaUrl;
                    Glide.with(ctx).load(thumb).centerCrop().into(h.ivVideoThumb);
                    final String url = m.mediaUrl;
                    h.flVideo.setOnClickListener(v -> openMedia(ctx, url, "video"));
                }
                break;
            }
            case "audio": {
                if (h.llAudio != null) {
                    h.llAudio.setVisibility(View.VISIBLE);
                    long totalMs = m.duration != null ? m.duration : 0L;
                    // Show remaining/total time
                    if (playingPos == pos && player != null) {
                        try {
                            long rem = totalMs - player.getCurrentPosition();
                            h.tvAudioDur.setText(FileUtils.formatDuration(Math.max(0, rem)));
                        } catch (Exception ignored) {
                            h.tvAudioDur.setText(FileUtils.formatDuration(totalMs));
                        }
                    } else {
                        h.tvAudioDur.setText(FileUtils.formatDuration(totalMs));
                    }
                    final int fPos = pos;
                    h.btnPlayAudio.setOnClickListener(v -> togglePlay(h, m, fPos));
                    h.btnPlayAudio.setImageResource(
                            playingPos == pos && player != null && player.isPlaying()
                                    ? R.drawable.ic_pause : R.drawable.ic_play);
                    // SeekBar: scrub support
                    if (h.seekAudio != null) {
                        h.seekAudio.setMax(totalMs > 0 ? (int) totalMs : 100);
                        if (playingPos == pos && player != null) {
                            try { h.seekAudio.setProgress(player.getCurrentPosition()); }
                            catch (Exception ignored) {}
                        } else {
                            h.seekAudio.setProgress(0);
                        }
                        h.seekAudio.setOnSeekBarChangeListener(
                                new android.widget.SeekBar.OnSeekBarChangeListener() {
                            @Override public void onProgressChanged(
                                    android.widget.SeekBar sb, int progress, boolean fromUser) {
                                if (fromUser && playingPos == fPos && player != null) {
                                    try { player.seekTo(progress); } catch (Exception ignored) {}
                                }
                            }
                            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
                        });
                    }
                }
                break;
            }
            case "file": {
                if (h.llFile != null) {
                    h.llFile.setVisibility(View.VISIBLE);
                    h.tvFileName.setText(m.fileName != null ? m.fileName : "File");
                    h.tvFileMeta.setText(m.fileSize != null
                            ? FileUtils.humanSize(m.fileSize) : "Document");
                    h.llFile.setOnClickListener(v -> {
                        if (m.mediaUrl == null) return;
                        try {
                            ctx.startActivity(new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse(m.mediaUrl)));
                        } catch (Exception ignored) {}
                    });
                }
                break;
            }
            default: { // text
                h.tvMessage.setVisibility(View.VISIBLE);
                String rawText = m.text != null ? m.text : "";
                // Feature 9: Search highlight
                if (searchHighlight != null && !searchHighlight.isEmpty()
                        && pos == searchHighlightPos
                        && rawText.toLowerCase(java.util.Locale.getDefault())
                               .contains(searchHighlight.toLowerCase(java.util.Locale.getDefault()))) {
                    android.text.SpannableString sp = new android.text.SpannableString(rawText);
                    int idx = rawText.toLowerCase(java.util.Locale.getDefault())
                            .indexOf(searchHighlight.toLowerCase(java.util.Locale.getDefault()));
                    sp.setSpan(new android.text.style.BackgroundColorSpan(0xFFFFEB3B),
                            idx, idx + searchHighlight.length(),
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    h.tvMessage.setText(sp);
                } else {
                    h.tvMessage.setText(rawText);
                }
                h.tvMessage.setTextColor(sent ? 0xFFFFFFFF : 0xFF212121);
                h.tvMessage.setTextSize(15f);
                h.tvMessage.setTypeface(null, android.graphics.Typeface.NORMAL);
                // Feature 4: Edited indicator
                if (h.tvEdited != null && Boolean.TRUE.equals(m.edited))
                    h.tvEdited.setVisibility(View.VISIBLE);
                break;
            }
        }

        // ── Feature 3: Reactions ──────────────────────────────────────────
        if (h.llReactions != null && h.tvReactions != null) {
            if (m.reactions != null && !m.reactions.isEmpty()) {
                // Aggregate: emoji → count
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String emoji : m.reactions.values())
                    counts.put(emoji, counts.getOrDefault(emoji, 0) + 1);
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    sb.append(e.getKey());
                    if (e.getValue() > 1) sb.append(" ").append(e.getValue());
                    sb.append("  ");
                }
                h.tvReactions.setText(sb.toString().trim());
                h.llReactions.setVisibility(View.VISIBLE);
                h.llReactions.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onReactionTap(m);
                });
            } else {
                h.llReactions.setVisibility(View.GONE);
            }
        }

        bindFooter(h, m, sent);
        setupLongPress(h, m, sent, ctx);
    }

    // ── Footer (time + ticks + star) ──────────────────────────────────────
    private void bindFooter(VH h, Message m, boolean sent) {
        if (h.tvTime != null && m.timestamp != null)
            h.tvTime.setText(timeFmt.format(new Date(m.timestamp)));

        // Feature 7: Star icon
        if (h.tvStarredIcon != null)
            h.tvStarredIcon.setVisibility(
                    Boolean.TRUE.equals(m.starred) ? View.VISIBLE : View.GONE);

        // Feature 1: Status ticks (only for sent messages)
        if (h.tvStatus != null) {
            if (sent) {
                h.tvStatus.setVisibility(View.VISIBLE);
                switch (m.status == null ? "sent" : m.status) {
                    case "read":
                        h.tvStatus.setText("✓✓");
                        h.tvStatus.setTextColor(0xFF40C4FF); // bright blue
                        break;
                    case "delivered":
                        h.tvStatus.setText("✓✓");
                        h.tvStatus.setTextColor(0xAAFFFFFF); // grey-white
                        break;
                    default: // sent
                        h.tvStatus.setText("✓");
                        h.tvStatus.setTextColor(0xAAFFFFFF);
                        break;
                }
            } else {
                h.tvStatus.setVisibility(View.GONE);
            }
        }
    }

    // ── Long-press → Bottom Sheet ──────────────────────────────────────────
    private void setupLongPress(VH h, Message m, boolean sent, Context ctx) {
        h.itemView.setOnLongClickListener(v -> {
            showActionSheet(ctx, m, sent);
            return true;
        });
    }

    private void showActionSheet(Context ctx, Message m, boolean sent) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        View sv = LayoutInflater.from(ctx)
                .inflate(R.layout.bottom_sheet_message_actions, null);

        // Emoji quick-react row
        String[] emojis = {"❤️", "👍", "😂", "😮", "😢", "🙏"};
        int[] emojiIds = {
            R.id.emoji_heart, R.id.emoji_thumb, R.id.emoji_laugh,
            R.id.emoji_wow,   R.id.emoji_sad,   R.id.emoji_pray
        };
        for (int i = 0; i < emojiIds.length; i++) {
            TextView et = sv.findViewById(emojiIds[i]);
            final String emoji = emojis[i];
            if (et != null) {
                // Highlight if user already reacted with this emoji
                boolean already = m.reactions != null &&
                        emoji.equals(m.reactions.get(currentUid));
                et.setAlpha(already ? 1.0f : 0.65f);
                et.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (actionListener != null) actionListener.onReact(m, emoji);
                });
            }
        }

        // Copy
        TextView copyBtn = sv.findViewById(R.id.action_copy);
        if (copyBtn != null) {
            if ("text".equals(m.type) && m.text != null && !m.text.isEmpty()
                    && !Boolean.TRUE.equals(m.deleted)) {
                copyBtn.setVisibility(View.VISIBLE);
                copyBtn.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (actionListener != null) actionListener.onCopy(m);
                });
            } else {
                copyBtn.setVisibility(View.GONE);
            }
        }

        // Reply
        sv.findViewById(R.id.action_reply).setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onReply(m);
        });

        // Edit — only sender, only text, not deleted
        TextView editBtn = sv.findViewById(R.id.action_edit);
        if (sent && "text".equals(m.type) && !Boolean.TRUE.equals(m.deleted)) {
            editBtn.setVisibility(View.VISIBLE);
            editBtn.setOnClickListener(v -> {
                sheet.dismiss();
                if (actionListener != null) actionListener.onEdit(m);
            });
        }

        // Forward — not for deleted
        if (!Boolean.TRUE.equals(m.deleted)) {
            sv.findViewById(R.id.action_forward).setOnClickListener(v -> {
                sheet.dismiss();
                if (actionListener != null) actionListener.onForward(m);
            });
        } else {
            sv.findViewById(R.id.action_forward).setVisibility(View.GONE);
        }

        // Star / Unstar
        TextView starBtn = sv.findViewById(R.id.action_star);
        starBtn.setText(Boolean.TRUE.equals(m.starred) ? "☆  Unstar Message" : "⭐  Star Message");
        starBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onStar(m);
        });

        // Pin / Unpin
        TextView pinBtn = sv.findViewById(R.id.action_pin);
        pinBtn.setText(Boolean.TRUE.equals(m.pinned) ? "📌  Unpin Message" : "📌  Pin Message");
        pinBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onPin(m);
        });

        // Delete — sender always; receiver only "for me"
        TextView deleteBtn = sv.findViewById(R.id.action_delete);
        if (!Boolean.TRUE.equals(m.deleted)) {
            deleteBtn.setVisibility(View.VISIBLE);
            deleteBtn.setOnClickListener(v -> {
                sheet.dismiss();
                if (actionListener != null) actionListener.onDelete(m);
            });
        }

        sheet.setContentView(sv);
        sheet.show();
    }

    // ── Media helper ───────────────────────────────────────────────────────
    private void openMedia(Context ctx, String url, String type) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent(ctx, MediaViewerActivity.class);
        i.putExtra("url", url);
        i.putExtra("type", type);
        ctx.startActivity(i);
    }

    // ── Audio playback ─────────────────────────────────────────────────────
    private void togglePlay(VH h, Message m, int pos) {
        if (m.mediaUrl == null) return;
        try {
            if (player != null && playingPos == pos && player.isPlaying()) {
                player.pause();
                h.btnPlayAudio.setImageResource(R.drawable.ic_play);
                return;
            }
            if (player != null) { player.release(); player = null; }
            player = new MediaPlayer();
            player.setDataSource(m.mediaUrl);
            player.prepareAsync();
            playingPos = pos;
            h.btnPlayAudio.setImageResource(R.drawable.ic_pause);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                h.btnPlayAudio.setImageResource(R.drawable.ic_play);
                mp.release(); player = null; playingPos = -1;
            });
        } catch (Exception e) {
            if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        }
    }

    /** Call from Activity.onDestroy() to release audio resources */
    public void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null; playingPos = -1;
        }
    }

    @Override public int getItemCount() { return messages.size(); }

    // ── ViewHolder ─────────────────────────────────────────────────────────
    static class VH extends RecyclerView.ViewHolder {
        // Core
        TextView  tvMessage, tvTime, tvSenderName;
        ImageView ivImage, ivVideoThumb;
        FrameLayout flVideo;
        // Audio
        LinearLayout llAudio;
        ImageButton  btnPlayAudio;
        SeekBar      seekAudio;
        TextView     tvAudioDur;
        // File
        LinearLayout llFile;
        TextView     tvFileName, tvFileMeta;
        // Feature views
        TextView     tvStatus;          // F1 read receipts
        LinearLayout llReplyPreview;    // F2 reply
        TextView     tvReplySender, tvReplyText;
        LinearLayout llReactions;       // F3 reactions
        TextView     tvReactions;
        TextView     tvEdited;          // F4
        TextView     tvPinnedLabel;     // F8
        TextView     tvForwarded;       // F6
        TextView     tvStarredIcon;     // F7

        VH(View v) {
            super(v);
            tvMessage     = v.findViewById(R.id.tv_message);
            tvTime        = v.findViewById(R.id.tv_time);
            tvSenderName  = v.findViewById(R.id.tv_sender_name);
            ivImage       = v.findViewById(R.id.iv_image);
            flVideo       = v.findViewById(R.id.fl_video);
            ivVideoThumb  = v.findViewById(R.id.iv_video_thumb);
            llAudio       = v.findViewById(R.id.ll_audio);
            btnPlayAudio  = v.findViewById(R.id.btn_play_audio);
            seekAudio     = v.findViewById(R.id.seek_audio);
            tvAudioDur    = v.findViewById(R.id.tv_audio_dur);
            llFile        = v.findViewById(R.id.ll_file);
            tvFileName    = v.findViewById(R.id.tv_file_name);
            tvFileMeta    = v.findViewById(R.id.tv_file_meta);
            // Feature views
            tvStatus      = v.findViewById(R.id.tv_status);
            llReplyPreview = v.findViewById(R.id.ll_reply_preview);
            tvReplySender  = v.findViewById(R.id.tv_reply_sender);
            tvReplyText    = v.findViewById(R.id.tv_reply_text);
            llReactions    = v.findViewById(R.id.ll_reactions);
            tvReactions    = v.findViewById(R.id.tv_reactions);
            tvEdited       = v.findViewById(R.id.tv_edited);
            tvPinnedLabel  = v.findViewById(R.id.tv_pinned_label);
            tvForwarded    = v.findViewById(R.id.tv_forwarded);
            tvStarredIcon  = v.findViewById(R.id.tv_starred_icon);
        }
    }
}
