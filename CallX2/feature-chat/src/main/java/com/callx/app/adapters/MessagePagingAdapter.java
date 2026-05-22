package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.chat.R;

import com.callx.app.models.Message;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.MediaCache;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * MessagePagingAdapter — Paging 3 PagingDataAdapter for chat messages.
 *
 * Drop-in replacement for MessageAdapter when loading messages from Room DB
 * via Pager3 + PagingSource. Supports sent/received layout types, text,
 * image, audio, file, and video message rendering identical to MessageAdapter.
 *
 * v22 changes:
 *   - Tick colour logic: single grey ✓ = sent, grey ✓✓ = delivered, BLUE ✓✓ = seen
 *   - onInfo() callback added to ActionListener
 *   - Message Info bottom sheet opens MessageInfoActivity with timestamps
 */
public class MessagePagingAdapter
        extends PagingDataAdapter<Message, MessagePagingAdapter.VH> {

    // ── DiffUtil — required by PagingDataAdapter ──────────────────
    private static final DiffUtil.ItemCallback<Message> DIFF =
        new DiffUtil.ItemCallback<Message>() {
            @Override
            public boolean areItemsTheSame(@NonNull Message a, @NonNull Message b) {
                return a.messageId != null && a.messageId.equals(b.messageId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull Message a, @NonNull Message b) {
                return a.messageId.equals(b.messageId)
                    && safeEquals(a.text, b.text)
                    && safeEquals(a.type, b.type)
                    && safeEquals(a.status, b.status)
                    && a.timestamp == b.timestamp
                    && a.edited == b.edited
                    && a.deleted == b.deleted;
            }

            private boolean safeEquals(String x, String y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }
        };

    // ── View types ────────────────────────────────────────────────
    private static final int TYPE_SENT        = 1;
    private static final int TYPE_RECEIVED    = 2;
    private static final int TYPE_STATUS_SEEN = 3;
    private static final int TYPE_REEL_SEEN   = 4;

    // ── Tick colours ──────────────────────────────────────────────
    private static final int COLOUR_TICK_SEEN      = 0xFF2196F3; // blue  — seen ✓✓
    private static final int COLOUR_TICK_GREY      = 0xFF9E9E9E; // grey  — sent ✓ / delivered ✓✓
    private static final int COLOUR_TICK_WHITE     = 0xCCFFFFFF; // white — sent inside dark bubble

    // ── Fields ────────────────────────────────────────────────────
    private final String currentUid;
    private final boolean isGroup;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    private ActionListener actionListener;
    private MediaPlayer player;
    private int playingPos = -1;

    // ── Interface for long-press actions ─────────────────────────
    public interface ActionListener {
        void onReply(Message m);
        void onNavigateToOriginal(String messageId);
        void onDelete(Message m);
        void onReact(Message m, String emoji);
        void onStar(Message m);
        void onCopy(Message m);
        void onForward(Message m);
        /** Called when user taps "Info" on their own sent message */
        void onInfo(Message m);
    }

    public MessagePagingAdapter(String currentUid, boolean isGroup) {
        super(DIFF);
        this.currentUid = currentUid;
        this.isGroup    = isGroup;
    }

    public void setActionListener(ActionListener l) {
        this.actionListener = l;
    }

    // ──────────────────────────────────────────────────────────────
    @Override
    public int getItemViewType(int position) {
        Message m = getItem(position);
        if (m == null) return TYPE_RECEIVED;
        if ("status_seen".equals(m.type)) return TYPE_STATUS_SEEN;
        if ("reel_seen".equals(m.type))   return TYPE_REEL_SEEN;
        return currentUid.equals(m.senderId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout;
        if (viewType == TYPE_SENT)             layout = R.layout.item_message_sent;
        else if (viewType == TYPE_STATUS_SEEN) layout = R.layout.item_status_seen_bubble;
        else if (viewType == TYPE_REEL_SEEN)   layout = R.layout.item_reel_seen_bubble;
        else                                   layout = R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Message m = getItem(position);
        if (m == null) {
            if (h.tvMessage != null) h.tvMessage.setVisibility(View.GONE);
            return;
        }
        if ("status_seen".equals(m.type)) { bindStatusSeenBubble(h, m); return; }
        if ("reel_seen".equals(m.type))   { bindReelSeenBubble(h, m);   return; }
        bindMessage(h, m, position);
    }

    // ──────────────────────────────────────────────────────────────
    // STATUS SEEN BUBBLE
    // ──────────────────────────────────────────────────────────────
    private void bindStatusSeenBubble(@NonNull VH h, @NonNull Message m) {
        Context ctx = h.itemView.getContext();

        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(R.id.iv_status_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                Glide.with(ctx).load(photo)
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        android.view.View flThumb = h.itemView.findViewById(R.id.fl_status_seen_thumb);
        android.widget.ImageView ivThumb = h.itemView.findViewById(R.id.iv_status_seen_thumb);
        android.widget.ImageView ivEye   = h.itemView.findViewById(R.id.iv_status_seen_eye);
        if (ivThumb != null && flThumb != null) {
            String thumb = m.statusThumbUrl != null ? m.statusThumbUrl : "";
            if (!thumb.isEmpty()) {
                flThumb.setVisibility(View.VISIBLE);
                if (ivEye != null) ivEye.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(thumb).centerCrop()
                    .placeholder(R.drawable.bg_skeleton_rect).into(ivThumb);
            } else {
                flThumb.setVisibility(View.GONE);
                if (ivEye != null) ivEye.setVisibility(View.GONE);
            }
        }

        final String ownerUid  = (m.statusOwnerUid != null && !m.statusOwnerUid.isEmpty())
                                 ? m.statusOwnerUid : m.senderId;
        final String ownerName = m.statusOwnerName != null ? m.statusOwnerName
                                 : (m.senderName != null ? m.senderName : "");
        View.OnClickListener openStatus = v -> {
            if (ownerUid == null || ownerUid.isEmpty()) return;
            android.content.Intent intent = new android.content.Intent(
                    com.callx.app.utils.Constants.ACTION_OPEN_STATUS);
            intent.putExtra("ownerUid",  ownerUid);
            intent.putExtra("ownerName", ownerName);
            intent.setPackage(ctx.getPackageName());
            try { ctx.startActivity(intent); }
            catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(ctx, "Status viewer not available", Toast.LENGTH_SHORT).show();
            }
        };
        h.itemView.setOnClickListener(openStatus);
        if (flThumb != null) flThumb.setOnClickListener(openStatus);

        android.widget.TextView tvName = h.itemView.findViewById(R.id.tv_status_seen_name);
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(View.VISIBLE);
            } else {
                tvName.setVisibility(View.GONE);
            }
        }

        android.widget.TextView tvTime = h.itemView.findViewById(R.id.tv_status_seen_time);
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // REEL SEEN BUBBLE
    // ──────────────────────────────────────────────────────────────
    private void bindReelSeenBubble(@NonNull VH h, @NonNull Message m) {
        Context ctx = h.itemView.getContext();

        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(R.id.iv_reel_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                Glide.with(ctx).load(photo)
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        final String reelId = m.reelId;
        View.OnClickListener openReel = v -> {
            if (reelId == null || reelId.isEmpty()) return;
            android.content.Intent intent = new android.content.Intent(
                    com.callx.app.utils.Constants.ACTION_OPEN_REEL);
            intent.putExtra("reelId", reelId);
            intent.setPackage(ctx.getPackageName());
            ctx.startActivity(intent);
        };

        android.view.View flThumb = h.itemView.findViewById(R.id.fl_reel_seen_thumb);
        android.widget.ImageView ivThumb = h.itemView.findViewById(R.id.iv_reel_seen_thumb);
        android.widget.ImageView ivPlay  = h.itemView.findViewById(R.id.iv_reel_seen_play);
        if (ivThumb != null) {
            String thumb = m.reelThumbUrl != null ? m.reelThumbUrl : "";
            if (!thumb.isEmpty()) {
                ivThumb.setVisibility(View.VISIBLE);
                if (ivPlay != null) ivPlay.setVisibility(View.VISIBLE);
                Glide.with(ctx).load(thumb).centerCrop()
                    .placeholder(R.drawable.bg_skeleton_rect).into(ivThumb);
            } else {
                ivThumb.setVisibility(View.GONE);
                if (ivPlay != null) ivPlay.setVisibility(View.GONE);
            }
        }
        if (flThumb != null) flThumb.setOnClickListener(openReel);
        if (ivPlay  != null) ivPlay.setOnClickListener(openReel);
        h.itemView.setOnClickListener(openReel);

        android.widget.TextView tvName = h.itemView.findViewById(R.id.tv_reel_seen_name);
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(View.VISIBLE);
            } else {
                tvName.setVisibility(View.GONE);
            }
        }

        android.widget.TextView tvTime = h.itemView.findViewById(R.id.tv_reel_seen_time);
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Core bind logic
    // ──────────────────────────────────────────────────────────────
    private void bindMessage(@NonNull VH h, @NonNull Message m, int position) {
        Context ctx = h.itemView.getContext();
        boolean sent = currentUid.equals(m.senderId);

        // ── Theme-aware bubble background ─────────────────────────────────
        try {
            android.view.View llBubble = h.itemView.findViewById(R.id.ll_bubble);
            if (llBubble != null) {
                boolean hasReply = m.replyToId != null && !m.replyToId.isEmpty();
                String bType = m.type != null ? m.type : "text";
                com.callx.app.utils.ChatThemeManager.get(ctx)
                        .applyBubble(llBubble, sent, bType, hasReply);
            }
        } catch (Exception ignored) {}

        // Reset visibility
        h.tvMessage.setVisibility(View.GONE);
        if (h.ivImage    != null) h.ivImage.setVisibility(View.GONE);
        if (h.llAudio    != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile     != null) h.llFile.setVisibility(View.GONE);
        if (h.tvTime     != null) h.tvTime.setVisibility(View.VISIBLE);

        // Timestamp
        if (h.tvTime != null && m.timestamp != null && m.timestamp > 0) {
            h.tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }

        // ── REPLY PREVIEW ─────────────────────────────────────────────────
        if (h.llReplyPreview != null) {
            boolean hasReply = m.replyToId != null && !m.replyToId.isEmpty();
            h.llReplyPreview.setVisibility(hasReply ? View.VISIBLE : View.GONE);
            if (hasReply) {
                if (h.tvReplySender != null)
                    h.tvReplySender.setText(m.replyToSenderName != null ? m.replyToSenderName : "");
                if (h.tvReplyText != null)
                    h.tvReplyText.setText(m.replyToText != null ? m.replyToText : "[Original message]");
                if (h.ivReplyThumb != null) {
                    String thumbUrl = m.replyToMediaUrl;
                    if (thumbUrl != null && !thumbUrl.isEmpty()) {
                        h.ivReplyThumb.setVisibility(View.VISIBLE);
                        Glide.with(ctx).load(thumbUrl).centerCrop().into(h.ivReplyThumb);
                    } else {
                        h.ivReplyThumb.setVisibility(View.GONE);
                    }
                }
                final String replyId = m.replyToId;
                h.llReplyPreview.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onNavigateToOriginal(replyId);
                });
            } else {
                h.llReplyPreview.setOnClickListener(null);
            }
        }

        // Sender name (group chats)
        if (h.tvSenderName != null) {
            if (isGroup && !sent) {
                h.tvSenderName.setVisibility(View.VISIBLE);
                h.tvSenderName.setText(m.senderName != null ? m.senderName : "Member");
            } else {
                h.tvSenderName.setVisibility(View.GONE);
            }
        }

        // Deleted message
        if (Boolean.TRUE.equals(m.deleted)) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText(sent ? "You deleted this message" : "This message was deleted");
            h.tvMessage.setAlpha(0.6f);
            bindDeliveryStatus(h, m, sent, ctx);
            h.itemView.setOnLongClickListener(null);
            return;
        }

        // ── Render by type ───────────────────────────────────────
        String type = m.type != null ? m.type : "text";
        switch (type) {
            case "image":
            case "gif":
                if (h.ivImage != null) {
                    h.ivImage.setVisibility(View.VISIBLE);
                    String fullUrl  = m.mediaUrl != null ? m.mediaUrl : m.text;
                    String thumbUrl = m.thumbnailUrl;

                    if (thumbUrl != null && !thumbUrl.isEmpty()) {
                        Glide.with(ctx).load(fullUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .thumbnail(Glide.with(ctx).load(thumbUrl).diskCacheStrategy(DiskCacheStrategy.ALL))
                            .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(400))
                            .placeholder(R.drawable.ic_file)
                            .error(R.drawable.ic_file)
                            .into(h.ivImage);
                    } else {
                        java.io.File cachedImg = MediaCache.getCached(ctx, fullUrl);
                        if (cachedImg != null) {
                            Glide.with(ctx).load(cachedImg).diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(R.drawable.ic_file).into(h.ivImage);
                        } else {
                            Glide.with(ctx).load(fullUrl).diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(R.drawable.ic_file).error(R.drawable.ic_file).into(h.ivImage);
                            MediaCache.get(ctx, fullUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {}
                                @Override public void onError(String reason) {}
                            });
                        }
                    }
                    h.ivImage.setOnClickListener(v -> {
                        Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url",      fullUrl);
                        i.putExtra("thumbUrl", thumbUrl != null ? thumbUrl : fullUrl);
                        i.putExtra("type", "image");
                        ctx.startActivity(i);
                    });
                }
                break;
            case "video":
                if (h.ivImage != null) {
                    h.ivImage.setVisibility(View.VISIBLE);
                    String vUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                    java.io.File cachedVid = MediaCache.getCached(ctx, vUrl);
                    if (cachedVid != null) {
                        Glide.with(ctx).load(cachedVid).diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_file).into(h.ivImage);
                    } else {
                        Glide.with(ctx).load(vUrl).diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_file).into(h.ivImage);
                        com.callx.app.cache.MediaStreamCache.getInstance(ctx)
                            .preloadPartial(vUrl, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                                @Override public void onComplete(java.io.File file) {}
                                @Override public void onError(String error) {}
                                @Override public void onProgress(int percent) {}
                            });
                    }
                    h.ivImage.setOnClickListener(v -> {
                        Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url", vUrl);
                        i.putExtra("type", "video");
                        ctx.startActivity(i);
                    });
                }
                break;
            case "audio":
                if (h.llAudio != null && h.btnPlayPause != null) {
                    h.llAudio.setVisibility(View.VISIBLE);
                    String aUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                    final int pos = position;
                    h.btnPlayPause.setOnClickListener(v -> toggleAudio(h, aUrl, pos));
                    java.io.File cachedAudio = MediaCache.getCached(ctx, aUrl);
                    if (cachedAudio == null && aUrl != null && !aUrl.isEmpty()) {
                        com.callx.app.cache.MediaStreamCache.getInstance(ctx)
                            .preloadPartial(aUrl, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                                @Override public void onComplete(java.io.File file) {}
                                @Override public void onError(String error) {}
                                @Override public void onProgress(int percent) {}
                            });
                    }
                } else {
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText("🎤 Voice message");
                }
                break;
            case "file":
            case "document":
                if (h.llFile != null && h.tvFileName != null) {
                    h.llFile.setVisibility(View.VISIBLE);
                    String fName = m.fileName != null ? m.fileName : "File";
                    h.tvFileName.setText(fName);
                    if (h.btnDownload != null) {
                        String fUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                        h.btnDownload.setOnClickListener(v -> {
                            java.io.File cached = MediaCache.getCached(ctx, fUrl);
                            if (cached != null) {
                                FileUtils.openOrDownload(ctx, cached.toURI().toString(), fName);
                                return;
                            }
                            Toast.makeText(ctx, "Downloading…", Toast.LENGTH_SHORT).show();
                            MediaCache.get(ctx, fUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {
                                    FileUtils.openOrDownload(ctx, file.toURI().toString(), fName);
                                }
                                @Override public void onError(String reason) {
                                    FileUtils.openOrDownload(ctx, fUrl, fName);
                                }
                            });
                        });
                    }
                } else {
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText("📎 " + (m.fileName != null ? m.fileName : "File"));
                }
                break;
            default: // text
                h.tvMessage.setVisibility(View.VISIBLE);
                String txt = m.text != null ? m.text : "";
                if (Boolean.TRUE.equals(m.edited)) txt += " (edited)";
                android.text.SpannableString spanned = new android.text.SpannableString(txt);
                android.text.util.Linkify.addLinks(spanned,
                    android.text.util.Linkify.WEB_URLS |
                    android.text.util.Linkify.PHONE_NUMBERS |
                    android.text.util.Linkify.EMAIL_ADDRESSES);
                h.tvMessage.setText(spanned);
                int linkColor = sent ? 0xFFB3E5FC : 0xFF1565C0;
                h.tvMessage.setLinkTextColor(linkColor);
                h.tvMessage.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                h.tvMessage.setHighlightColor(0x33FFFFFF);
                h.tvMessage.setAlpha(1f);
                h.tvMessage.setTextColor(
                    com.callx.app.utils.ChatThemeManager.get(ctx).getTextColor(sent));
                break;
        }

        // ── Delivery status tick ──────────────────────────────────
        bindDeliveryStatus(h, m, sent, ctx);

        // ── Long press ───────────────────────────────────────────
        h.itemView.setOnLongClickListener(v -> {
            if (actionListener != null) showActionBottomSheet(ctx, m, sent);
            return true;
        });
    }

    /**
     * Binds the delivery status tick (✓ / ✓✓ grey / ✓✓ blue) to tv_status.
     *
     * Rules (WhatsApp-style):
     *   sent      → single grey ✓
     *   delivered → double grey ✓✓
     *   seen      → double BLUE ✓✓
     */
    private void bindDeliveryStatus(@NonNull VH h, @NonNull Message m,
                                    boolean sent, Context ctx) {
        if (h.tvStatus == null) return;
        if (!sent) {
            h.tvStatus.setVisibility(View.GONE);
            return;
        }
        h.tvStatus.setVisibility(View.VISIBLE);
        String status = m.status != null ? m.status : "sent";
        switch (status) {
            case "seen":
            case "read":        // legacy alias
                h.tvStatus.setText("✓✓");
                h.tvStatus.setTextColor(COLOUR_TICK_SEEN);   // blue
                break;
            case "delivered":
                h.tvStatus.setText("✓✓");
                h.tvStatus.setTextColor(COLOUR_TICK_GREY);   // grey double
                break;
            default:            // "sent" / "pending"
                h.tvStatus.setText("✓");
                h.tvStatus.setTextColor(COLOUR_TICK_GREY);   // grey single
                break;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Audio playback toggle
    // ──────────────────────────────────────────────────────────────
    private void toggleAudio(@NonNull VH h, String url, int position) {
        if (playingPos == position && player != null && player.isPlaying()) {
            player.pause();
            if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
            return;
        }
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
        playingPos = position;
        if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_pause);

        java.io.File cached = MediaCache.getCached(h.itemView.getContext(), url);
        if (cached != null) {
            playAudioFromPath(h, cached.getAbsolutePath(), position);
            return;
        }

        com.callx.app.cache.MediaStreamCache.getInstance(h.itemView.getContext())
            .preloadPartial(url, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                @Override public void onComplete(java.io.File file) {
                    playAudioFromPath(h, file.getAbsolutePath(), position);
                }
                @Override public void onError(String error) {
                    playAudioFromPath(h, url, position);
                }
                @Override public void onProgress(int percent) {}
            });
    }

    private void playAudioFromPath(@NonNull VH h, String path, int position) {
        try {
            if (player != null) { try { player.release(); } catch (Exception ignored) {} }
            player = new MediaPlayer();
            if (path.startsWith("http")) {
                player.setDataSource(path);
            } else {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                        player.setDataSource(fis.getFD());
                    }
                } else {
                    player.setDataSource(path);
                }
            }
            player.prepareAsync();
            player.setOnPreparedListener(mp -> {
                mp.start();
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_pause);
            });
            player.setOnCompletionListener(mp -> {
                playingPos = -1;
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
                try { mp.release(); } catch (Exception ignored) {}
                player = null;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                playingPos = -1;
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
                return true;
            });
        } catch (Exception e) {
            if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Long-press bottom sheet — WhatsApp-style action menu
    // ──────────────────────────────────────────────────────────────
    private void showActionBottomSheet(Context ctx, Message m, boolean sent) {
        if (actionListener == null) return;

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx);
        android.view.View sv = android.view.LayoutInflater.from(ctx)
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
                    actionListener.onReact(m, emoji);
                });
            }
        }

        // Reply
        View replyBtn = sv.findViewById(R.id.action_reply);
        if (replyBtn != null) replyBtn.setOnClickListener(v -> {
            sheet.dismiss(); actionListener.onReply(m);
        });

        // Edit — sender only, text only
        View editBtn = sv.findViewById(R.id.action_edit);
        if (editBtn != null) {
            boolean canEdit = sent && "text".equals(m.type) && !Boolean.TRUE.equals(m.deleted);
            editBtn.setVisibility(canEdit ? View.VISIBLE : View.GONE);
        }

        // Copy — text only
        View copyBtn = sv.findViewById(R.id.action_copy);
        if (copyBtn != null) {
            boolean isText  = "text".equals(m.type) || m.type == null;
            boolean hasText = m.text != null && !m.text.isEmpty();
            boolean canCopy = isText && hasText && !Boolean.TRUE.equals(m.deleted);
            copyBtn.setVisibility(canCopy ? View.VISIBLE : View.GONE);
            if (canCopy) copyBtn.setOnClickListener(v -> {
                sheet.dismiss(); actionListener.onCopy(m);
            });
        }

        // Forward
        View fwdBtn = sv.findViewById(R.id.action_forward);
        if (fwdBtn != null) {
            if (!Boolean.TRUE.equals(m.deleted)) {
                fwdBtn.setOnClickListener(v -> { sheet.dismiss(); actionListener.onForward(m); });
            } else {
                fwdBtn.setVisibility(View.GONE);
            }
        }

        // Star
        TextView starBtn = sv.findViewById(R.id.action_star);
        if (starBtn != null) {
            starBtn.setText(Boolean.TRUE.equals(m.starred) ? "☆  Unstar" : "⭐  Star Message");
            starBtn.setOnClickListener(v -> { sheet.dismiss(); actionListener.onStar(m); });
        }

        // Pin
        View pinBtn = sv.findViewById(R.id.action_pin);
        if (pinBtn != null) {
            // Pin handled by ChatActivity if onInfo is extended; keep visible
            pinBtn.setVisibility(View.VISIBLE);
        }

        // ── Info — sender only (v22: shows sent/delivered/seen timestamps) ──
        View infoBtn = sv.findViewById(R.id.action_info);
        if (infoBtn != null) {
            if (sent && !Boolean.TRUE.equals(m.deleted)) {
                infoBtn.setVisibility(View.VISIBLE);
                infoBtn.setOnClickListener(v -> {
                    sheet.dismiss();
                    actionListener.onInfo(m);
                });
            } else {
                infoBtn.setVisibility(View.GONE);
            }
        }

        // Delete
        View deleteBtn = sv.findViewById(R.id.action_delete);
        if (deleteBtn != null && !Boolean.TRUE.equals(m.deleted)) {
            deleteBtn.setOnClickListener(v -> { sheet.dismiss(); actionListener.onDelete(m); });
        }

        sheet.setContentView(sv);
        sheet.show();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        if (holder.ivImage != null) Glide.with(holder.ivImage).clear(holder.ivImage);
    }

    // ──────────────────────────────────────────────────────────────
    // ViewHolder
    // ──────────────────────────────────────────────────────────────
    static class VH extends RecyclerView.ViewHolder {
        TextView     tvMessage, tvTime, tvSenderName, tvFileName;
        ImageView    ivImage;
        TextView     tvStatus;
        LinearLayout llAudio, llFile;
        ImageButton  btnPlayPause;
        ImageView    btnDownload;
        LinearLayout llReplyPreview;
        TextView     tvReplySender, tvReplyText;
        ImageView    ivReplyThumb;

        VH(@NonNull View v) {
            super(v);
            tvMessage      = v.findViewById(R.id.tv_message);
            tvTime         = v.findViewById(R.id.tv_time);
            tvSenderName   = v.findViewById(R.id.tv_sender_name);
            ivImage        = v.findViewById(R.id.iv_image);
            tvStatus       = v.findViewById(R.id.tv_status);
            llAudio        = v.findViewById(R.id.ll_audio);
            btnPlayPause   = v.findViewById(R.id.btn_play_pause);
            llFile         = v.findViewById(R.id.ll_file);
            tvFileName     = v.findViewById(R.id.tv_file_name);
            btnDownload    = v.findViewById(R.id.btn_download);
            llReplyPreview = v.findViewById(R.id.ll_reply_preview);
            tvReplySender  = v.findViewById(R.id.tv_reply_sender);
            tvReplyText    = v.findViewById(R.id.tv_reply_text);
            ivReplyThumb   = v.findViewById(R.id.iv_reply_thumb);
        }
    }
}
