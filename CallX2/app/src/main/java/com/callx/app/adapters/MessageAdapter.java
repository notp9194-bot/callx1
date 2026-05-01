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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    public interface MessageActionListener {
        void onReply(Message m);
        void onEdit(Message m);
        void onDelete(Message m);
        void onReact(Message m);
        void onForward(Message m);
        void onStar(Message m);
        void onPin(Message m);
    }

    private final List<Message> messages;
    private final String currentUid;
    private final boolean isGroup;
    private static final int SENT = 1, RECEIVED = 2;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private MediaPlayer player;
    private int playingPos = -1;
    private MessageActionListener listener;

    public MessageAdapter(List<Message> messages, String currentUid, boolean isGroup) {
        this.messages = messages;
        this.currentUid = currentUid;
        this.isGroup = isGroup;
    }

    public void setMessageActionListener(MessageActionListener l) {
        this.listener = l;
    }

    @Override public int getItemViewType(int pos) {
        Message m = messages.get(pos);
        return (m.senderId != null && m.senderId.equals(currentUid)) ? SENT : RECEIVED;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == SENT
                ? R.layout.item_message_sent
                : R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m = messages.get(pos);
        Context ctx = h.itemView.getContext();
        boolean isSent = getItemViewType(pos) == SENT;

        // Hide all content views
        h.tvMessage.setVisibility(View.GONE);
        h.ivImage.setVisibility(View.GONE);
        if (h.flVideo  != null) h.flVideo.setVisibility(View.GONE);
        if (h.llAudio  != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile   != null) h.llFile.setVisibility(View.GONE);
        if (h.tvEdited != null) h.tvEdited.setVisibility(View.GONE);

        // Feature 8: Pinned indicator
        if (h.tvPinnedLabel != null)
            h.tvPinnedLabel.setVisibility(Boolean.TRUE.equals(m.pinned) ? View.VISIBLE : View.GONE);

        // Feature 6: Forwarded label
        if (h.tvForwarded != null) {
            if (m.forwardedFrom != null && !m.forwardedFrom.isEmpty()) {
                h.tvForwarded.setText("↪ Forwarded from " + m.forwardedFrom);
                h.tvForwarded.setVisibility(View.VISIBLE);
            } else {
                h.tvForwarded.setVisibility(View.GONE);
            }
        }

        // Sender name in groups
        if (h.tvSenderName != null) {
            if (isGroup && !isSent) {
                h.tvSenderName.setVisibility(View.VISIBLE);
                h.tvSenderName.setText(m.senderName == null ? "Unknown" : m.senderName);
            } else {
                h.tvSenderName.setVisibility(View.GONE);
            }
        }

        // Feature 2: Reply preview
        if (h.llReplyPreview != null) {
            if (m.replyToText != null && !m.replyToText.isEmpty()) {
                h.llReplyPreview.setVisibility(View.VISIBLE);
                if (h.tvReplySender != null)
                    h.tvReplySender.setText(m.replyToSenderName != null ? m.replyToSenderName : "");
                if (h.tvReplyText != null)
                    h.tvReplyText.setText(m.replyToText);
            } else {
                h.llReplyPreview.setVisibility(View.GONE);
            }
        }

        // Feature 5: Deleted message
        if (Boolean.TRUE.equals(m.deleted)) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText("🚫 This message was deleted");
            h.tvMessage.setTextColor(0xFF888888);
            bindFooter(h, m, isSent);
            setupLongPress(h, m, isSent);
            return;
        }

        // Determine type
        String type = m.type == null ? "text" : m.type;
        if (m.imageUrl != null && !m.imageUrl.isEmpty()
                && (m.mediaUrl == null || m.mediaUrl.isEmpty())) type = "image";

        switch (type) {
            case "image":
                h.ivImage.setVisibility(View.VISIBLE);
                String iu = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
                Glide.with(ctx).load(iu).into(h.ivImage);
                h.ivImage.setOnClickListener(v -> openMedia(ctx, iu, "image"));
                break;
            case "video":
                if (h.flVideo != null) {
                    h.flVideo.setVisibility(View.VISIBLE);
                    if (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                        Glide.with(ctx).load(m.thumbnailUrl).into(h.ivVideoThumb);
                    else if (m.mediaUrl != null)
                        Glide.with(ctx).load(m.mediaUrl).into(h.ivVideoThumb);
                    h.flVideo.setOnClickListener(v -> openMedia(ctx, m.mediaUrl, "video"));
                }
                break;
            case "audio":
                if (h.llAudio != null) {
                    h.llAudio.setVisibility(View.VISIBLE);
                    h.tvAudioDur.setText(m.duration != null
                            ? FileUtils.formatDuration(m.duration) : "0:00");
                    h.btnPlayAudio.setOnClickListener(v -> togglePlay(h, m, pos));
                }
                break;
            case "file":
                if (h.llFile != null) {
                    h.llFile.setVisibility(View.VISIBLE);
                    h.tvFileName.setText(m.fileName == null ? "File" : m.fileName);
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
            default:
                h.tvMessage.setVisibility(View.VISIBLE);
                h.tvMessage.setText(m.text == null ? "" : m.text);
                h.tvMessage.setTextColor(isSent ? 0xFFFFFFFF : 0xFF212121);
                // Feature 4: Edited label
                if (h.tvEdited != null && Boolean.TRUE.equals(m.edited))
                    h.tvEdited.setVisibility(View.VISIBLE);
                break;
        }

        // Feature 3: Reactions
        if (h.tvReactions != null) {
            if (m.reactions != null && !m.reactions.isEmpty()) {
                java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                for (String emoji : m.reactions.values()) {
                    counts.put(emoji, counts.getOrDefault(emoji, 0) + 1);
                }
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    sb.append(e.getKey());
                    if (e.getValue() > 1) sb.append(e.getValue());
                    sb.append(" ");
                }
                h.tvReactions.setText(sb.toString().trim());
                h.tvReactions.setVisibility(View.VISIBLE);
            } else {
                h.tvReactions.setVisibility(View.GONE);
            }
        }

        bindFooter(h, m, isSent);
        setupLongPress(h, m, isSent);
    }

    private void bindFooter(VH h, Message m, boolean isSent) {
        // Time
        if (h.tvTime != null && m.timestamp != null)
            h.tvTime.setText(fmt.format(new Date(m.timestamp)));

        // Feature 1: Read receipts (only for sent messages)
        if (h.tvStatus != null) {
            if (isSent) {
                h.tvStatus.setVisibility(View.VISIBLE);
                String st = m.status;
                if ("read".equals(st)) {
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(0xFF2196F3); // blue
                } else if ("delivered".equals(st)) {
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(0xFF9E9E9E); // grey
                } else {
                    h.tvStatus.setText("✓");
                    h.tvStatus.setTextColor(0xFF9E9E9E);
                }
            } else {
                h.tvStatus.setVisibility(View.GONE);
            }
        }

        // Feature 7: Star icon
        if (h.tvStarredIcon != null)
            h.tvStarredIcon.setVisibility(Boolean.TRUE.equals(m.starred) ? View.VISIBLE : View.GONE);
    }

    private void setupLongPress(VH h, Message m, boolean isSent) {
        h.itemView.setOnLongClickListener(v -> {
            if (listener == null) return false;
            Context ctx = v.getContext();
            String[] allOptions = {"↩ Reply", "😀 React", "↪ Forward", "⭐ Star/Unstar", "📌 Pin"};
            String[] senderExtra = {"✏ Edit", "🗑 Delete"};

            java.util.List<String> opts = new java.util.ArrayList<>();
            for (String o : allOptions) opts.add(o);
            if (isSent && !"text".equals(m.type == null ? "" : m.type) == false
                    && !Boolean.TRUE.equals(m.deleted)) {
                if ("text".equals(m.type == null ? "text" : m.type)) {
                    opts.add(1, "✏ Edit");
                }
                opts.add("🗑 Delete");
            } else if (isSent && !Boolean.TRUE.equals(m.deleted)) {
                opts.add("🗑 Delete");
            }

            new androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setItems(opts.toArray(new String[0]), (d, which) -> {
                        String chosen = opts.get(which);
                        if (chosen.startsWith("↩")) listener.onReply(m);
                        else if (chosen.startsWith("✏")) listener.onEdit(m);
                        else if (chosen.startsWith("🗑")) listener.onDelete(m);
                        else if (chosen.startsWith("😀")) listener.onReact(m);
                        else if (chosen.startsWith("↪")) listener.onForward(m);
                        else if (chosen.startsWith("⭐")) listener.onStar(m);
                        else if (chosen.startsWith("📌")) listener.onPin(m);
                    }).show();
            return true;
        });
    }

    private void openMedia(Context ctx, String url, String type) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent(ctx, MediaViewerActivity.class);
        i.putExtra("url", url);
        i.putExtra("type", type);
        ctx.startActivity(i);
    }

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
        } catch (Exception ignored) {}
    }

    @Override public int getItemCount() { return messages.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvMessage, tvTime, tvSenderName, tvAudioDur, tvFileName, tvFileMeta;
        TextView tvStatus, tvReactions, tvEdited, tvPinnedLabel, tvForwarded, tvStarredIcon;
        TextView tvReplySender, tvReplyText;
        LinearLayout llReplyPreview;
        ImageView ivImage, ivVideoThumb;
        FrameLayout flVideo;
        LinearLayout llAudio, llFile;
        ImageButton btnPlayAudio;
        SeekBar seekAudio;

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
            // New feature views
            tvStatus      = v.findViewById(R.id.tv_status);
            tvReactions   = v.findViewById(R.id.tv_reactions);
            tvEdited      = v.findViewById(R.id.tv_edited);
            tvPinnedLabel = v.findViewById(R.id.tv_pinned_label);
            tvForwarded   = v.findViewById(R.id.tv_forwarded);
            tvStarredIcon = v.findViewById(R.id.tv_starred_icon);
            llReplyPreview = v.findViewById(R.id.ll_reply_preview);
            tvReplySender  = v.findViewById(R.id.tv_reply_sender);
            tvReplyText    = v.findViewById(R.id.tv_reply_text);
        }
    }
}
