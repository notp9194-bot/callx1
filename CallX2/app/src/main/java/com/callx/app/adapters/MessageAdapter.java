package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.R;
import com.callx.app.activities.MediaViewerActivity;
import com.callx.app.models.Message;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.MediaCache;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Production-grade MessageAdapter.
 * Supports all original features (1–8) + new features:
 *   N4 Copy Message
 *   N7 Message Info
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {

    public interface ActionListener {
        void onReply(Message m);
        void onEdit(Message m);
        void onDelete(Message m);
        void onReact(Message m, String emoji);
        void onForward(Message m);
        void onStar(Message m);
        void onPin(Message m);
        void onReactionTap(Message m);
        void onCopy(Message m);   // N4
        void onInfo(Message m);   // N7
    }

    private final List<Message> messages;
    private final String currentUid;
    private final boolean isGroup;
    private ActionListener actionListener;

    private static final int TYPE_SENT = 1, TYPE_RECEIVED = 2;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    private MediaPlayer player;
    private int playingPos = -1;

    public MessageAdapter(List<Message> messages, String currentUid, boolean isGroup) {
        this.messages   = messages;
        this.currentUid = currentUid;
        this.isGroup    = isGroup;
        setHasStableIds(false);
    }

    public void setActionListener(ActionListener l) { this.actionListener = l; }

    @Override public int getItemViewType(int pos) {
        return currentUid.equals(messages.get(pos).senderId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_SENT
                ? R.layout.item_message_sent
                : R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m   = messages.get(pos);
        Context ctx = h.itemView.getContext();
        boolean sent = getItemViewType(pos) == TYPE_SENT;

        h.tvMessage.setVisibility(View.GONE);
        h.ivImage.setVisibility(View.GONE);
        if (h.flVideo  != null) h.flVideo.setVisibility(View.GONE);
        if (h.llAudio  != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile   != null) h.llFile.setVisibility(View.GONE);
        if (h.tvEdited != null) h.tvEdited.setVisibility(View.GONE);

        // Feature 8: Pinned label
        if (h.tvPinnedLabel != null)
            h.tvPinnedLabel.setVisibility(
                    Boolean.TRUE.equals(m.pinned) ? View.VISIBLE : View.GONE);

        // Feature 6: Forwarded label
        if (h.tvForwarded != null) {
            boolean fwd = m.forwardedFrom != null && !m.forwardedFrom.isEmpty();
            h.tvForwarded.setVisibility(fwd ? View.VISIBLE : View.GONE);
            if (fwd) h.tvForwarded.setText("\u21AA Forwarded from " + m.forwardedFrom);
        }

        // Group sender avatar (received only)
        if (h.ivSenderAvatar != null) {
            boolean showAvatar = isGroup && !sent && m.senderId != null;
            h.ivSenderAvatar.setVisibility(showAvatar ? android.view.View.VISIBLE : android.view.View.GONE);
            if (showAvatar) {
                loadSenderAvatar(h.itemView.getContext(), m.senderId, h.ivSenderAvatar);
                final String sUid  = m.senderId;
                final String sName = m.senderName;
                h.ivSenderAvatar.setOnClickListener(v -> {
                    if (sUid == null) return;
                    android.content.Intent i = new android.content.Intent(h.itemView.getContext(), com.callx.app.activities.ChatActivity.class);
                    i.putExtra("partnerUid",  sUid);
                    i.putExtra("partnerName", sName != null ? sName : "");
                    h.itemView.getContext().startActivity(i);
                });
            } else {
                h.ivSenderAvatar.setOnClickListener(null);
            }
        }

                // Group sender name (received only)
        if (h.tvSenderName != null) {
            boolean show = isGroup && !sent;
            h.tvSenderName.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) h.tvSenderName.setText(
                    m.senderName != null ? m.senderName : "Unknown");
        }

        // Feature 2: Reply preview
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

        // Feature 5: Deleted state
        if (Boolean.TRUE.equals(m.deleted)) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText("\uD83D\uDEAB  This message was deleted");
            h.tvMessage.setTextColor(0xFF9E9E9E);
            h.tvMessage.setTextSize(13f);
            h.tvMessage.setTypeface(null, android.graphics.Typeface.ITALIC);
            bindFooter(h, m, sent);
            setupLongPress(h, m, sent, ctx);
            return;
        }

        String type = m.type == null ? "text" : m.type;
        if ("image".equals(type) || (m.imageUrl != null && !m.imageUrl.isEmpty()
                && (m.mediaUrl == null || m.mediaUrl.isEmpty())))
            type = "image";

        switch (type) {
            case "image": {
                h.ivImage.setVisibility(View.VISIBLE);
                String url = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
                Glide.with(ctx).load(url)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.bg_circle_white)
                        .into(h.ivImage);
                final String fu = url;
                h.ivImage.setOnClickListener(v -> openMedia(ctx, fu, "image"));
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
                    h.tvAudioDur.setText(m.duration != null
                            ? FileUtils.formatDuration(m.duration) : "0:00");
                    final int fPos = pos;
                    h.btnPlayAudio.setOnClickListener(v -> togglePlay(h, m, fPos));
                    h.btnPlayAudio.setImageResource(
                            playingPos == pos ? R.drawable.ic_pause : R.drawable.ic_play);
                }
                break;
            }
            case "file": {
                if (h.llFile != null) {
                    h.llFile.setVisibility(View.VISIBLE);
                    String fName = m.fileName != null ? m.fileName : "File";
                    h.tvFileName.setText(fName);
                    h.tvFileMeta.setText(m.fileSize != null
                            ? FileUtils.humanSize(m.fileSize) : "Document");
                    h.llFile.setOnClickListener(v -> {
                        if (m.mediaUrl == null) return;
                        // Pehle local cache check, agar cached hai to seedha kholo
                        java.io.File cached = MediaCache.getCached(ctx, m.mediaUrl);
                        if (cached != null) {
                            FileUtils.openOrDownload(ctx, cached.toURI().toString(), fName);
                            return;
                        }
                        // Nahi hai to download & cache karo
                        android.widget.Toast.makeText(ctx, "Downloading…", android.widget.Toast.LENGTH_SHORT).show();
                        MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                            @Override public void onReady(java.io.File file) {
                                FileUtils.openOrDownload(ctx, file.toURI().toString(), fName);
                            }
                            @Override public void onError(String reason) {
                                FileUtils.openOrDownload(ctx, m.mediaUrl, fName);
                            }
                        });
                    });
                }
                break;
            }
            default: {
                h.tvMessage.setVisibility(View.VISIBLE);
                h.tvMessage.setText(m.text != null ? m.text : "");
                h.tvMessage.setTextColor(sent ? 0xFFFFFFFF : 0xFF212121);
                h.tvMessage.setTextSize(15f);
                h.tvMessage.setTypeface(null, android.graphics.Typeface.NORMAL);
                if (h.tvEdited != null && Boolean.TRUE.equals(m.edited))
                    h.tvEdited.setVisibility(View.VISIBLE);
                break;
            }
        }

        // Feature 3: Reactions
        if (h.llReactions != null && h.tvReactions != null) {
            if (m.reactions != null && !m.reactions.isEmpty()) {
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

    private final java.util.Map<String, String> photoCache = new java.util.HashMap<>();

    private void loadSenderAvatar(android.content.Context ctx, String uid,
            de.hdodenhof.circleimageview.CircleImageView iv) {
        if (uid == null || ctx == null || iv == null) return;
        String cached = photoCache.get(uid);
        if (cached != null) {
            if (!cached.isEmpty())
                com.bumptech.glide.Glide.with(ctx).load(cached)
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(com.callx.app.R.drawable.ic_person).into(iv);
            else iv.setImageResource(com.callx.app.R.drawable.ic_person);
            return;
        }
        com.callx.app.utils.FirebaseUtils.getUserRef(uid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    String photo = snap.child("photoUrl").getValue(String.class);
                    photoCache.put(uid, photo != null ? photo : "");
                    if (photo != null && !photo.isEmpty())
                        com.bumptech.glide.Glide.with(ctx).load(photo)
                            .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                            .placeholder(com.callx.app.R.drawable.ic_person).into(iv);
                    else iv.setImageResource(com.callx.app.R.drawable.ic_person);
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {}
            });
    }

        private void bindFooter(VH h, Message m, boolean sent) {
        if (h.tvTime != null && m.timestamp != null)
            h.tvTime.setText(timeFmt.format(new Date(m.timestamp)));

        if (h.tvStarredIcon != null)
            h.tvStarredIcon.setVisibility(
                    Boolean.TRUE.equals(m.starred) ? View.VISIBLE : View.GONE);

        if (h.tvStatus != null) {
            if (sent) {
                h.tvStatus.setVisibility(View.VISIBLE);
                switch (m.status == null ? "sent" : m.status) {
                    case "read":
                        h.tvStatus.setText("\u2713\u2713 ");
                        h.tvStatus.setTextSize(14f);
                        h.tvStatus.setTextColor(0xFFFF00FF); break; // Magenta double-tick (read)
                    case "delivered":
                        h.tvStatus.setText("\u2713\u2713");
                        h.tvStatus.setTextSize(13f);
                        h.tvStatus.setTextColor(0xFFFF00FF); break; // Magenta double-tick (delivered)
                    default:
                        h.tvStatus.setText("\u2713");
                        h.tvStatus.setTextSize(13f);
                        h.tvStatus.setTextColor(0xCCFFFFFF); break;
                }
            } else {
                h.tvStatus.setVisibility(View.GONE);
            }
        }
    }

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
        String[] emojis  = {"❤️", "👍", "😂", "😮", "😢", "🙏"};
        int[]    emojiIds = {
            R.id.emoji_heart, R.id.emoji_thumb, R.id.emoji_laugh,
            R.id.emoji_wow,   R.id.emoji_sad,   R.id.emoji_pray};
        for (int i = 0; i < emojiIds.length; i++) {
            TextView et = sv.findViewById(emojiIds[i]);
            final String emoji = emojis[i];
            if (et != null) {
                boolean already = m.reactions != null &&
                        emoji.equals(m.reactions.get(currentUid));
                et.setAlpha(already ? 1.0f : 0.65f);
                et.setScaleX(already ? 1.2f : 1.0f);
                et.setScaleY(already ? 1.2f : 1.0f);
                et.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (actionListener != null) actionListener.onReact(m, emoji);
                });
            }
        }

        // Reply
        sv.findViewById(R.id.action_reply).setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onReply(m);
        });

        // Edit — sender only, text only, not deleted
        TextView editBtn = sv.findViewById(R.id.action_edit);
        if (sent && "text".equals(m.type) && !Boolean.TRUE.equals(m.deleted)) {
            editBtn.setVisibility(View.VISIBLE);
            editBtn.setOnClickListener(v -> {
                sheet.dismiss();
                if (actionListener != null) actionListener.onEdit(m);
            });
        }

        // N4: Copy — text messages only
        TextView copyBtn = sv.findViewById(R.id.action_copy);
        if (copyBtn != null) {
            boolean isText = "text".equals(m.type) || m.type == null;
            boolean hasText = m.text != null && !m.text.isEmpty();
            if (isText && hasText && !Boolean.TRUE.equals(m.deleted)) {
                copyBtn.setVisibility(View.VISIBLE);
                copyBtn.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (actionListener != null) actionListener.onCopy(m);
                });
            } else {
                copyBtn.setVisibility(View.GONE);
            }
        }

        // Forward
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
        starBtn.setText(Boolean.TRUE.equals(m.starred)
                ? "\u2606  Unstar" : "\u2605  Star Message");
        starBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onStar(m);
        });

        // Pin / Unpin
        TextView pinBtn = sv.findViewById(R.id.action_pin);
        pinBtn.setText(Boolean.TRUE.equals(m.pinned)
                ? "\uD83D\uDCCC  Unpin" : "\uD83D\uDCCC  Pin Message");
        pinBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onPin(m);
        });

        // N7: Info (sender only)
        TextView infoBtn = sv.findViewById(R.id.action_info);
        if (infoBtn != null) {
            if (sent) {
                infoBtn.setVisibility(View.VISIBLE);
                infoBtn.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (actionListener != null) actionListener.onInfo(m);
                });
            } else {
                infoBtn.setVisibility(View.GONE);
            }
        }

        // Delete
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

    private void openMedia(Context ctx, String url, String type) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent(ctx, MediaViewerActivity.class);
        i.putExtra("url", url); i.putExtra("type", type);
        ctx.startActivity(i);
    }

    private void togglePlay(VH h, Message m, int pos) {
        if (m.mediaUrl == null) return;
        if (player != null && playingPos == pos && player.isPlaying()) {
            player.pause();
            h.btnPlayAudio.setImageResource(R.drawable.ic_play);
            return;
        }
        if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        playingPos = pos;
        h.btnPlayAudio.setImageResource(R.drawable.ic_pause);

        // Pehle cached file check karo — agar hai to seedha play karo (zero data)
        java.io.File cached = MediaCache.getCached(h.itemView.getContext(), m.mediaUrl);
        if (cached != null) {
            playFromFile(h, cached.getAbsolutePath(), pos);
            return;
        }
        // Cache nahi hai — download karo phir play karo
        MediaCache.get(h.itemView.getContext(), m.mediaUrl, new MediaCache.Callback() {
            @Override public void onReady(java.io.File file) { playFromFile(h, file.getAbsolutePath(), pos); }
            @Override public void onError(String reason)     { playFromFile(h, m.mediaUrl, pos); }
        });
    }

    private void playFromFile(VH h, String path, int pos) {
        try {
            if (player != null) { try { player.release(); } catch (Exception ignored) {} }
            player = new MediaPlayer();
            
            // Local file - use FileDescriptor
            if (!path.startsWith("http")) {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                        player.setDataSource(fis.getFD());
                    }
                } else {
                    player.setDataSource(path);
                }
            } else {
                player.setDataSource(path);
            }
            
            player.prepareAsync();
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                h.btnPlayAudio.setImageResource(R.drawable.ic_play);
                try { mp.release(); } catch (Exception ignored) {}
                player = null; playingPos = -1;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.e("AudioPlay", "Error: " + what + " path: " + path);
                playingPos = -1;
                return true;
            });
        } catch (Exception e) {
            android.util.Log.e("AudioPlay", "playFromFile error: " + e.getMessage() + " path: " + path);
            if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        }
    }

    public void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null; playingPos = -1;
        }
    }

    @Override public int getItemCount() { return messages.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView     tvMessage, tvTime, tvSenderName;
        ImageView    ivImage, ivVideoThumb;
        FrameLayout  flVideo;
        LinearLayout llAudio;
        ImageButton  btnPlayAudio;
        SeekBar      seekAudio;
        TextView     tvAudioDur;
        LinearLayout llFile;
        TextView     tvFileName, tvFileMeta;
        TextView     tvStatus;
        LinearLayout llReplyPreview;
        TextView     tvReplySender, tvReplyText;
        LinearLayout llReactions;
        TextView     tvReactions;
        TextView     tvEdited;
        TextView     tvPinnedLabel;
        TextView     tvForwarded;
        TextView     tvStarredIcon;
        de.hdodenhof.circleimageview.CircleImageView ivSenderAvatar;

        VH(View v) {
            super(v);
            tvMessage    = v.findViewById(R.id.tv_message);
            tvTime       = v.findViewById(R.id.tv_time);
            tvSenderName = v.findViewById(R.id.tv_sender_name);
            ivImage      = v.findViewById(R.id.iv_image);
            flVideo      = v.findViewById(R.id.fl_video);
            ivVideoThumb = v.findViewById(R.id.iv_video_thumb);
            llAudio      = v.findViewById(R.id.ll_audio);
            btnPlayAudio = v.findViewById(R.id.btn_play_pause);
            seekAudio    = v.findViewById(R.id.seek_audio);
            tvAudioDur   = v.findViewById(R.id.tv_audio_dur);
            llFile       = v.findViewById(R.id.ll_file);
            tvFileName   = v.findViewById(R.id.tv_file_name);
            tvFileMeta   = v.findViewById(R.id.tv_file_meta);
            tvStatus     = v.findViewById(R.id.tv_status);
            llReplyPreview = v.findViewById(R.id.ll_reply_preview);
            tvReplySender  = v.findViewById(R.id.tv_reply_sender);
            tvReplyText    = v.findViewById(R.id.tv_reply_text);
            llReactions  = v.findViewById(R.id.ll_reactions);
            tvReactions  = v.findViewById(R.id.tv_reactions);
            tvEdited     = v.findViewById(R.id.tv_edited);
            tvPinnedLabel = v.findViewById(R.id.tv_pinned_label);
            tvForwarded  = v.findViewById(R.id.tv_forwarded);
            tvStarredIcon = v.findViewById(R.id.tv_starred_icon);
        }
    }
}
