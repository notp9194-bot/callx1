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
import com.callx.app.chat.R;

import com.callx.app.models.Message;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.MediaCache;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Production-grade MessageAdapter — v22.
 *
 * Tick system improvements (v22):
 *   - ImageView iv_tick replaces TextView tv_status for proper vector drawable rendering
 *   - 5 states: pending (clock), sent (single grey), delivered (double grey),
 *               read (double blue), failed (red error)
 *   - Smooth alpha animation on status change
 *   - Reply thumbnail now loaded via Glide for image/video replies
 *
 * Features supported:
 *   1  Read receipts (pending/sent/delivered/read/failed)
 *   2  Reply / Quote with media thumbnail
 *   3  Emoji reactions
 *   4  Message editing
 *   5  Delete for everyone
 *   6  Forward
 *   7  Starred
 *   8  Pinned
 *   N4 Copy message
 *   N7 Message info (→ MessageInfoActivity)
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
        void onCopy(Message m);
        void onInfo(Message m);
    }

    private final List<Message> messages;
    private final String currentUid;
    private final boolean isGroup;
    private ActionListener actionListener;

    private static final int TYPE_SENT = 1, TYPE_RECEIVED = 2, TYPE_STATUS_SEEN = 3, TYPE_REEL_SEEN = 4;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());

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
        Message m = messages.get(pos);
        if ("status_seen".equals(m.type)) return TYPE_STATUS_SEEN;
        if ("reel_seen".equals(m.type))   return TYPE_REEL_SEEN;
        return currentUid.equals(m.senderId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout;
        if (viewType == TYPE_SENT)             layout = R.layout.item_message_sent;
        else if (viewType == TYPE_STATUS_SEEN) layout = R.layout.item_status_seen_bubble;
        else if (viewType == TYPE_REEL_SEEN)   layout = R.layout.item_reel_seen_bubble;
        else                                   layout = R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m   = messages.get(pos);
        Context ctx = h.itemView.getContext();
        int viewType = getItemViewType(pos);

        if (viewType == TYPE_STATUS_SEEN) { bindStatusSeenBubble(h, m, ctx); return; }
        if (viewType == TYPE_REEL_SEEN)   { bindReelSeenBubble(h, m, ctx);   return; }

        boolean sent = (viewType == TYPE_SENT);

        // Reset visibility
        h.tvMessage.setVisibility(View.GONE);
        h.ivImage.setVisibility(View.GONE);
        if (h.flVideo  != null) h.flVideo.setVisibility(View.GONE);
        if (h.llAudio  != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile   != null) h.llFile.setVisibility(View.GONE);
        if (h.tvEdited != null) h.tvEdited.setVisibility(View.GONE);

        // Feature 8: Pinned label
        if (h.tvPinnedLabel != null)
            h.tvPinnedLabel.setVisibility(Boolean.TRUE.equals(m.pinned) ? View.VISIBLE : View.GONE);

        // Feature 6: Forwarded label
        if (h.tvForwarded != null) {
            boolean fwd = m.forwardedFrom != null && !m.forwardedFrom.isEmpty();
            h.tvForwarded.setVisibility(fwd ? View.VISIBLE : View.GONE);
            if (fwd) h.tvForwarded.setText("\u21AA Forwarded from " + m.forwardedFrom);
        }

        // Group sender avatar (received only)
        if (h.ivSenderAvatar != null) {
            boolean showAvatar = isGroup && !sent && m.senderId != null;
            h.ivSenderAvatar.setVisibility(showAvatar ? View.VISIBLE : View.GONE);
            if (showAvatar) {
                loadSenderAvatar(ctx, m.senderId, h.ivSenderAvatar);
                final String sUid  = m.senderId;
                final String sName = m.senderName;
                h.ivSenderAvatar.setOnClickListener(v -> {
                    if (sUid == null) return;
                    Intent i = new Intent().setClassName(ctx, "com.callx.app.activities.ChatActivity");
                    i.putExtra("partnerUid", sUid);
                    i.putExtra("partnerName", sName != null ? sName : "");
                    ctx.startActivity(i);
                });
            } else {
                h.ivSenderAvatar.setOnClickListener(null);
            }
        }

        // Group sender name (received only)
        if (h.tvSenderName != null) {
            boolean show = isGroup && !sent;
            h.tvSenderName.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) h.tvSenderName.setText(m.senderName != null ? m.senderName : "Unknown");
        }

        // Feature 2: Reply preview — text + media thumbnail
        if (h.llReplyPreview != null) {
            boolean hasReply = m.replyToText != null && !m.replyToText.isEmpty();
            h.llReplyPreview.setVisibility(hasReply ? View.VISIBLE : View.GONE);
            if (hasReply) {
                if (h.tvReplySender != null)
                    h.tvReplySender.setText(m.replyToSenderName != null ? m.replyToSenderName : "");
                if (h.tvReplyText != null)
                    h.tvReplyText.setText(m.replyToText);

                // FIX: Load reply media thumbnail (image/video replies)
                if (h.ivReplyThumb != null) {
                    boolean isMediaReply = ("image".equals(m.replyToType) || "video".equals(m.replyToType))
                            && m.replyToMediaUrl != null && !m.replyToMediaUrl.isEmpty();
                    if (isMediaReply) {
                        h.ivReplyThumb.setVisibility(View.VISIBLE);
                        Glide.with(ctx)
                                .load(m.replyToMediaUrl)
                                .centerCrop()
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(R.drawable.ic_video)
                                .into(h.ivReplyThumb);
                    } else {
                        h.ivReplyThumb.setVisibility(View.GONE);
                        Glide.with(ctx).clear(h.ivReplyThumb);
                    }
                }
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

        // Per-type bubble background (ChatThemeManager runtime gradient)
        try {
            View llBubble = h.itemView.findViewById(R.id.ll_bubble);
            if (llBubble != null) {
                boolean hasReply = m.replyToText != null && !m.replyToText.isEmpty();
                com.callx.app.utils.ChatThemeManager.get(ctx).applyBubble(llBubble, sent, type, hasReply);
            }
        } catch (Exception ignored) {}

        switch (type) {
            case "image": {
                h.ivImage.setVisibility(View.VISIBLE);
                String url = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
                java.io.File cached = MediaCache.getCached(ctx, url);
                if (cached != null) {
                    Glide.with(ctx).load(cached).diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.bg_circle_white).into(h.ivImage);
                } else {
                    Glide.with(ctx).load(url).diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.bg_circle_white).into(h.ivImage);
                    MediaCache.get(ctx, url, new MediaCache.Callback() {
                        @Override public void onReady(java.io.File f) {}
                        @Override public void onError(String r) {}
                    });
                }
                final String fu = url;
                h.ivImage.setOnClickListener(v -> openMedia(ctx, fu, "image"));
                break;
            }
            case "video": {
                if (h.flVideo != null) {
                    h.flVideo.setVisibility(View.VISIBLE);
                    String thumb = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                            ? m.thumbnailUrl : m.mediaUrl;
                    final String videoUrl = m.mediaUrl;
                    java.io.File cachedThumb = MediaCache.getCached(ctx, thumb);
                    if (cachedThumb != null) {
                        Glide.with(ctx).load(cachedThumb).centerCrop().into(h.ivVideoThumb);
                    } else {
                        Glide.with(ctx).load(thumb).centerCrop()
                                .placeholder(R.drawable.ic_video).into(h.ivVideoThumb);
                        MediaCache.get(ctx, thumb, new MediaCache.Callback() {
                            @Override public void onReady(java.io.File f) {}
                            @Override public void onError(String r) {}
                        });
                    }
                    if (h.tvVideoDuration != null) {
                        if (m.duration != null && m.duration > 0) {
                            h.tvVideoDuration.setVisibility(View.VISIBLE);
                            h.tvVideoDuration.setText(formatVideoDuration(m.duration));
                        } else {
                            h.tvVideoDuration.setVisibility(View.GONE);
                        }
                    }
                    java.io.File cachedVideo = MediaCache.getCached(ctx, videoUrl);
                    if (cachedVideo == null && videoUrl != null && !videoUrl.isEmpty()) {
                        MediaCache.get(ctx, videoUrl, new MediaCache.Callback() {
                            @Override public void onReady(java.io.File f) {}
                            @Override public void onError(String r) {}
                        });
                    }
                    h.flVideo.setOnClickListener(v -> {
                        Intent intent = new Intent()
                                .setClassName(ctx, "com.callx.app.activities.VideoPlayerActivity");
                        intent.putExtra("videoUrl", videoUrl);
                        intent.putExtra("thumbUrl", m.thumbnailUrl);
                        intent.putExtra("durationMs", m.duration != null ? m.duration.intValue() : 0);
                        ctx.startActivity(intent);
                    });
                }
                break;
            }
            case "audio": {
                if (h.llAudio != null) {
                    h.llAudio.setVisibility(View.VISIBLE);
                    h.tvAudioDur.setText(m.duration != null ? FileUtils.formatDuration(m.duration) : "0:00");
                    final int fPos = pos;
                    h.btnPlayAudio.setOnClickListener(v -> togglePlay(h, m, fPos));
                    h.btnPlayAudio.setImageResource(playingPos == pos ? R.drawable.ic_pause : R.drawable.ic_play);
                    if (m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                        if (MediaCache.getCached(ctx, m.mediaUrl) == null) {
                            MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File f) {}
                                @Override public void onError(String r) {}
                            });
                        }
                    }
                }
                break;
            }
            case "file": {
                if (h.llFile != null) {
                    h.llFile.setVisibility(View.VISIBLE);
                    String fName = m.fileName != null ? m.fileName : "File";
                    h.tvFileName.setText(fName);
                    h.tvFileMeta.setText(m.fileSize != null ? FileUtils.humanSize(m.fileSize) : "Document");
                    if (h.ivDownload != null) {
                        if (!sent && m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                            h.ivDownload.setVisibility(View.VISIBLE);
                            h.ivDownload.setImageResource(R.drawable.ic_file);
                            final String fUrl = m.mediaUrl;
                            final String fFileName = fName;
                            h.ivDownload.setOnClickListener(v -> {
                                java.io.File cached2 = MediaCache.getCached(ctx, fUrl);
                                if (cached2 != null) {
                                    FileUtils.openOrDownload(ctx, cached2.toURI().toString(), fFileName);
                                } else {
                                    Toast.makeText(ctx, "Downloading…", Toast.LENGTH_SHORT).show();
                                    h.ivDownload.setEnabled(false);
                                    MediaCache.get(ctx, fUrl, new MediaCache.Callback() {
                                        @Override public void onReady(java.io.File file) {
                                            h.ivDownload.setEnabled(true);
                                            Toast.makeText(ctx, "Downloaded!", Toast.LENGTH_SHORT).show();
                                            FileUtils.openOrDownload(ctx, file.toURI().toString(), fFileName);
                                        }
                                        @Override public void onError(String reason) {
                                            h.ivDownload.setEnabled(true);
                                            Toast.makeText(ctx, "Download failed", Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            });
                        } else {
                            h.ivDownload.setVisibility(View.GONE);
                        }
                    }
                    if (m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                        if (MediaCache.getCached(ctx, m.mediaUrl) == null) {
                            MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File f) {}
                                @Override public void onError(String r) {}
                            });
                        }
                    }
                    h.llFile.setOnClickListener(v -> {
                        if (m.mediaUrl == null) return;
                        java.io.File cached = MediaCache.getCached(ctx, m.mediaUrl);
                        if (cached != null) {
                            FileUtils.openOrDownload(ctx, cached.toURI().toString(), fName);
                            return;
                        }
                        Toast.makeText(ctx, "Downloading…", Toast.LENGTH_SHORT).show();
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
                h.tvMessage.setTextColor(com.callx.app.utils.ChatThemeManager.get(ctx).getTextColor(sent));
                h.tvMessage.setTextSize(15f);
                h.tvMessage.setTypeface(null, android.graphics.Typeface.NORMAL);
                if (h.tvEdited != null && Boolean.TRUE.equals(m.edited))
                    h.tvEdited.setVisibility(View.VISIBLE);
                String rawText = m.text != null ? m.text : "";
                android.text.SpannableString spanned = new android.text.SpannableString(rawText);
                android.text.util.Linkify.addLinks(spanned,
                        android.text.util.Linkify.WEB_URLS |
                        android.text.util.Linkify.PHONE_NUMBERS |
                        android.text.util.Linkify.EMAIL_ADDRESSES);
                h.tvMessage.setText(spanned);
                int linkColor = sent ? 0xFFB3E5FC : 0xFF1565C0;
                h.tvMessage.setLinkTextColor(linkColor);
                h.tvMessage.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                h.tvMessage.setHighlightColor(0x33FFFFFF);
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

    // ══════════════════════════════════════════════════════════════════════════
    //  bindFooter — time + tick (v22: proper ImageView-based tick)
    //
    //  Tick states:
    //    pending   → ic_clock (grey clock, spinning via ObjectAnimator optional)
    //    sent      → ic_single_tick (single grey ✓)
    //    delivered → ic_double_tick (double grey ✓✓)
    //    read      → ic_double_tick_blue (double blue ✓✓)
    //    failed    → ic_tick_failed (red ! circle)
    // ══════════════════════════════════════════════════════════════════════════
    private void bindFooter(@NonNull VH h, Message m, boolean sent) {
        if (h.tvTime != null && m.timestamp != null)
            h.tvTime.setText(timeFmt.format(new Date(m.timestamp)));

        if (h.tvStarredIcon != null)
            h.tvStarredIcon.setVisibility(Boolean.TRUE.equals(m.starred) ? View.VISIBLE : View.GONE);

        // ── Tick icon (sent messages only) ────────────────────────────────────
        if (h.ivTick != null) {
            if (sent) {
                h.ivTick.setVisibility(View.VISIBLE);

                String status = m.status == null ? "sent" : m.status;
                int prevTag = h.ivTick.getTag() instanceof Integer ? (int) h.ivTick.getTag() : -1;
                int newRes;

                switch (status) {
                    case "read":
                        newRes = R.drawable.ic_double_tick_blue;
                        break;
                    case "delivered":
                        newRes = R.drawable.ic_double_tick;
                        break;
                    case "pending":
                        newRes = R.drawable.ic_clock;
                        break;
                    case "failed":
                        newRes = R.drawable.ic_tick_failed;
                        break;
                    default: // "sent"
                        newRes = R.drawable.ic_single_tick;
                        break;
                }

                if (prevTag != newRes) {
                    h.ivTick.setTag(newRes);
                    h.ivTick.setImageResource(newRes);

                    // Smooth fade-in when transitioning grey→blue (read receipt pop)
                    if (prevTag == R.drawable.ic_double_tick && newRes == R.drawable.ic_double_tick_blue) {
                        h.ivTick.setAlpha(0.3f);
                        h.ivTick.animate().alpha(1.0f).setDuration(300).start();
                    } else {
                        h.ivTick.setAlpha(1.0f);
                    }
                }
            } else {
                h.ivTick.setVisibility(View.GONE);
            }
        }

        // Keep tv_status always hidden (legacy — replaced by iv_tick)
        if (h.tvStatus != null) h.tvStatus.setVisibility(View.GONE);
    }

    private void setupLongPress(@NonNull VH h, Message m, boolean sent, Context ctx) {
        h.itemView.setOnLongClickListener(v -> {
            showActionSheet(ctx, m, sent);
            return true;
        });
    }

    private void showActionSheet(Context ctx, Message m, boolean sent) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        View sv = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_message_actions, null);

        // Emoji quick-react row
        String[] emojis   = {"❤️", "👍", "😂", "😮", "😢", "🙏"};
        int[]    emojiIds = {R.id.emoji_heart, R.id.emoji_thumb, R.id.emoji_laugh,
                             R.id.emoji_wow,   R.id.emoji_sad,   R.id.emoji_pray};
        for (int i = 0; i < emojiIds.length; i++) {
            TextView et = sv.findViewById(emojiIds[i]);
            final String emoji = emojis[i];
            if (et != null) {
                boolean already = m.reactions != null && emoji.equals(m.reactions.get(currentUid));
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
        starBtn.setText(Boolean.TRUE.equals(m.starred) ? "\u2606  Unstar" : "\u2605  Star Message");
        starBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onStar(m);
        });

        // Pin / Unpin
        TextView pinBtn = sv.findViewById(R.id.action_pin);
        pinBtn.setText(Boolean.TRUE.equals(m.pinned) ? "\uD83D\uDCCC  Unpin" : "\uD83D\uDCCC  Pin Message");
        pinBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onPin(m);
        });

        // N7: Info (sender only, not deleted/pending/failed)
        TextView infoBtn = sv.findViewById(R.id.action_info);
        if (infoBtn != null) {
            boolean canShowInfo = sent
                    && !Boolean.TRUE.equals(m.deleted)
                    && !"pending".equals(m.status)
                    && !"failed".equals(m.status);
            if (canShowInfo) {
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

    // ══════════════════════════════════════════════════════════════════════════
    //  Status seen / reel seen bubbles (unchanged from original)
    // ══════════════════════════════════════════════════════════════════════════

    private void bindStatusSeenBubble(@NonNull VH h, Message m, Context ctx) {
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
                h.itemView.findViewById(R.id.iv_status_seen_avatar);
        ImageView ivThumb = h.itemView.findViewById(R.id.iv_status_seen_thumb);
        TextView  tvTime  = h.itemView.findViewById(R.id.tv_status_seen_time);
        TextView  tvName  = h.itemView.findViewById(R.id.tv_status_seen_name);

        if (ivAvatar != null && m.senderPhoto != null && !m.senderPhoto.isEmpty()) {
            Glide.with(ctx).load(m.senderPhoto).circleCrop()
                    .placeholder(R.drawable.ic_person).into(ivAvatar);
        }
        if (ivThumb != null && m.statusThumbUrl != null && !m.statusThumbUrl.isEmpty()) {
            ivThumb.setVisibility(View.VISIBLE);
            Glide.with(ctx).load(m.statusThumbUrl).centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL).into(ivThumb);
            final String uid  = m.statusOwnerUid;
            final String name = m.statusOwnerName;
            ivThumb.setOnClickListener(v -> {
                Intent i = new Intent().setClassName(ctx, "com.callx.app.activities.StatusViewerActivity");
                i.putExtra("ownerUid",  uid);
                i.putExtra("ownerName", name != null ? name : "");
                ctx.startActivity(i);
            });
        } else if (ivThumb != null) {
            ivThumb.setVisibility(View.GONE);
        }
        if (tvTime  != null && m.timestamp != null) tvTime.setText(timeFmt.format(new Date(m.timestamp)));
        if (tvName  != null) {
            boolean showName = isGroup && m.senderName != null;
            tvName.setVisibility(showName ? View.VISIBLE : View.GONE);
            if (showName) tvName.setText(m.senderName);
        }
    }

    private void bindReelSeenBubble(@NonNull VH h, Message m, Context ctx) {
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
                h.itemView.findViewById(R.id.iv_reel_seen_avatar);
        ImageView ivThumb = h.itemView.findViewById(R.id.iv_reel_seen_thumb);
        TextView  tvTime  = h.itemView.findViewById(R.id.tv_reel_seen_time);
        TextView  tvName  = h.itemView.findViewById(R.id.tv_reel_seen_name);

        if (ivAvatar != null && m.senderPhoto != null && !m.senderPhoto.isEmpty()) {
            Glide.with(ctx).load(m.senderPhoto).circleCrop()
                    .placeholder(R.drawable.ic_person).into(ivAvatar);
        }
        if (ivThumb != null && m.reelThumbUrl != null && !m.reelThumbUrl.isEmpty()) {
            ivThumb.setVisibility(View.VISIBLE);
            Glide.with(ctx).load(m.reelThumbUrl).centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL).into(ivThumb);
            final String reelId = m.reelId;
            ivThumb.setOnClickListener(v -> {
                if (reelId == null) return;
                Intent i = new Intent().setClassName(ctx, "com.callx.app.activities.ReelViewerActivity");
                i.putExtra("reelId", reelId);
                ctx.startActivity(i);
            });
        } else if (ivThumb != null) {
            ivThumb.setVisibility(View.GONE);
        }
        if (tvTime != null && m.timestamp != null) tvTime.setText(timeFmt.format(new Date(m.timestamp)));
        if (tvName != null) {
            boolean showName = isGroup && m.senderName != null;
            tvName.setVisibility(showName ? View.VISIBLE : View.GONE);
            if (showName) tvName.setText(m.senderName);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void loadSenderAvatar(Context ctx, String senderId,
                                  de.hdodenhof.circleimageview.CircleImageView iv) {
        com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("users").child(senderId).child("profileImageUrl")
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                        String url = snap.getValue(String.class);
                        if (url != null && !url.isEmpty()) {
                            Glide.with(ctx).load(url).circleCrop()
                                    .placeholder(R.drawable.ic_person).into(iv);
                        }
                    }
                    @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {}
                });
    }

    private void openMedia(Context ctx, String url, String type) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent().setClassName(ctx.getPackageName(),
                "com.callx.app.activities.MediaViewerActivity");
        i.putExtra("url", url);
        i.putExtra("type", type);
        ctx.startActivity(i);
    }

    private static String formatVideoDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(java.util.Locale.US, "%d:%02d", min, sec);
    }

    private void togglePlay(@NonNull VH h, Message m, int pos) {
        if (m.mediaUrl == null) return;
        if (player != null && playingPos == pos && player.isPlaying()) {
            player.pause();
            h.btnPlayAudio.setImageResource(R.drawable.ic_play);
            return;
        }
        if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        playingPos = pos;
        h.btnPlayAudio.setImageResource(R.drawable.ic_pause);
        java.io.File cached = MediaCache.getCached(h.itemView.getContext(), m.mediaUrl);
        if (cached != null) {
            playFromFile(h, cached.getAbsolutePath(), pos);
            return;
        }
        MediaCache.get(h.itemView.getContext(), m.mediaUrl, new MediaCache.Callback() {
            @Override public void onReady(java.io.File file) { playFromFile(h, file.getAbsolutePath(), pos); }
            @Override public void onError(String reason)     { playFromFile(h, m.mediaUrl, pos); }
        });
    }

    private void playFromFile(@NonNull VH h, String path, int pos) {
        try {
            if (player != null) { try { player.release(); } catch (Exception ignored) {} }
            player = new MediaPlayer();
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
            android.util.Log.e("AudioPlay", "playFromFile error: " + e.getMessage());
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

    // ══════════════════════════════════════════════════════════════════════════
    //  ViewHolder
    // ══════════════════════════════════════════════════════════════════════════
    static class VH extends RecyclerView.ViewHolder {
        TextView     tvMessage, tvTime, tvSenderName;
        ImageView    ivImage, ivVideoThumb;
        FrameLayout  flVideo;
        TextView     tvVideoDuration;
        LinearLayout llAudio;
        ImageButton  btnPlayAudio;
        SeekBar      seekAudio;
        TextView     tvAudioDur;
        LinearLayout llFile;
        TextView     tvFileName, tvFileMeta;
        ImageButton  ivDownload;

        /**
         * v22: ivTick replaces tvStatus for proper vector drawable tick rendering.
         * States: pending(clock) / sent(single grey) / delivered(double grey) /
         *         read(double blue) / failed(red error).
         */
        ImageView ivTick;

        /** Legacy — kept for null-safe backward compat, always GONE at runtime. */
        TextView tvStatus;

        LinearLayout llReplyPreview;
        TextView     tvReplySender, tvReplyText;

        /** v22 FIX: Reply thumbnail for image/video quoted messages. */
        ImageView ivReplyThumb;

        LinearLayout llReactions;
        TextView     tvReactions;
        TextView     tvEdited;
        TextView     tvPinnedLabel;
        TextView     tvForwarded;
        TextView     tvStarredIcon;
        de.hdodenhof.circleimageview.CircleImageView ivSenderAvatar;

        VH(View v) {
            super(v);
            tvMessage      = v.findViewById(R.id.tv_message);
            tvTime         = v.findViewById(R.id.tv_time);
            tvSenderName   = v.findViewById(R.id.tv_sender_name);
            ivImage        = v.findViewById(R.id.iv_image);
            flVideo        = v.findViewById(R.id.fl_video);
            ivVideoThumb   = v.findViewById(R.id.iv_video_thumb);
            tvVideoDuration = v.findViewById(R.id.tv_duration);
            llAudio        = v.findViewById(R.id.ll_audio);
            btnPlayAudio   = v.findViewById(R.id.btn_play_pause);
            seekAudio      = v.findViewById(R.id.seek_audio);
            tvAudioDur     = v.findViewById(R.id.tv_audio_dur);
            llFile         = v.findViewById(R.id.ll_file);
            tvFileName     = v.findViewById(R.id.tv_file_name);
            tvFileMeta     = v.findViewById(R.id.tv_file_meta);
            ivDownload     = v.findViewById(R.id.btn_download);

            // v22: proper tick ImageView
            ivTick         = v.findViewById(R.id.iv_tick);
            // legacy (always hidden)
            tvStatus       = v.findViewById(R.id.tv_status);

            llReplyPreview  = v.findViewById(R.id.ll_reply_preview);
            tvReplySender   = v.findViewById(R.id.tv_reply_sender);
            tvReplyText     = v.findViewById(R.id.tv_reply_text);
            ivReplyThumb    = v.findViewById(R.id.iv_reply_thumb);  // v22 FIX

            llReactions    = v.findViewById(R.id.ll_reactions);
            tvReactions    = v.findViewById(R.id.tv_reactions);
            tvEdited       = v.findViewById(R.id.tv_edited);
            tvPinnedLabel  = v.findViewById(R.id.tv_pinned_label);
            tvForwarded    = v.findViewById(R.id.tv_forwarded);
            tvStarredIcon  = v.findViewById(R.id.tv_starred_icon);
            ivSenderAvatar = v.findViewById(R.id.iv_sender_avatar);
        }
    }
}
