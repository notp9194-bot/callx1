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
 * Usage in ChatActivity:
 *   MessagePagingAdapter pagingAdapter = new MessagePagingAdapter(uid, false);
 *   binding.rvMessages.setAdapter(pagingAdapter);
 *   viewModel.getPagedMessages(chatId).observe(this, pagingAdapter::submitData);
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
                    && a.deleted == b.deleted
                    && a.fontStyle == b.fontStyle;  // FIX: font style change pe rebind trigger
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
    }

    // ── Multi-select interface ────────────────────────────────────
    public interface MultiSelectListener {
        void onSelectionChanged(int count);
    }

    // ── Multi-select state ────────────────────────────────────────
    private boolean multiSelectMode = false;
    private final java.util.Set<String> selectedMessageIds = new java.util.HashSet<>();
    private MultiSelectListener multiSelectListener;

    public void setMultiSelectListener(MultiSelectListener l) { this.multiSelectListener = l; }

    public void enterMultiSelectMode(Message firstMessage) {
        multiSelectMode = true;
        selectedMessageIds.clear();
        String id = firstMessage != null ? firstMessage.messageId : null;
        if (id == null && firstMessage != null) id = firstMessage.id;
        if (id != null) selectedMessageIds.add(id);
        notifyDataSetChanged();
        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(selectedMessageIds.size());
    }

    public void exitMultiSelectMode() {
        multiSelectMode = false;
        selectedMessageIds.clear();
        notifyDataSetChanged();
        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(0);
    }

    public boolean isInMultiSelectMode() { return multiSelectMode; }

    public java.util.List<Message> getSelectedMessages() {
        java.util.List<Message> result = new java.util.ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            if (id != null && selectedMessageIds.contains(id)) result.add(m);
        }
        return result;
    }

    private void applySelectionHighlight(VH h, Message m) {
        String id = m.messageId != null ? m.messageId : m.id;
        boolean selected = id != null && selectedMessageIds.contains(id);
        if (multiSelectMode) {
            h.itemView.setAlpha(selected ? 1.0f : 0.6f);
            h.itemView.setBackgroundResource(selected
                    ? android.R.color.holo_blue_light
                    : android.R.color.transparent);
        } else {
            h.itemView.setAlpha(1.0f);
            h.itemView.setBackgroundResource(android.R.color.transparent);
        }
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
            // Placeholder — show shimmer or empty
            if (h.tvMessage != null) h.tvMessage.setVisibility(View.GONE);
            return;
        }
        // ── STATUS SEEN BUBBLE — special system event row ─────────────────
        if ("status_seen".equals(m.type)) {
            bindStatusSeenBubble(h, m);
            return;
        }
        // ── REEL SEEN BUBBLE — special system event row ───────────────────
        if ("reel_seen".equals(m.type)) {
            bindReelSeenBubble(h, m);
            return;
        }
        bindMessage(h, m, position);
    }

    // ──────────────────────────────────────────────────────────────
    // STATUS SEEN BUBBLE — "👁 Seen your status" system event row.
    // Layout: item_status_seen_bubble.xml
    //   • iv_status_seen_avatar  → circular avatar (Glide)
    //   • fl_status_seen_thumb   → thumbnail container (visible for image/video statuses)
    //   • iv_status_seen_thumb   → status thumbnail (tappable → StatusViewerActivity)
    //   • iv_status_seen_eye     → eye overlay icon on thumbnail
    //   • tv_status_seen_label   → "Seen your status" (set in XML)
    //   • tv_status_seen_name    → sender name (group only)
    //   • tv_status_seen_time    → formatted timestamp
    // No long-press / reactions / reply — it's a system event.
    // ──────────────────────────────────────────────────────────────
    private void bindStatusSeenBubble(@NonNull VH h, @NonNull Message m) {
        Context ctx = h.itemView.getContext();

        // Avatar
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(R.id.iv_status_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                com.bumptech.glide.Glide.with(ctx)
                    .load(photo)
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        // Status thumbnail
        android.view.View flThumb = h.itemView.findViewById(R.id.fl_status_seen_thumb);
        android.widget.ImageView ivThumb = h.itemView.findViewById(R.id.iv_status_seen_thumb);
        android.widget.ImageView ivEye   = h.itemView.findViewById(R.id.iv_status_seen_eye);
        if (ivThumb != null && flThumb != null) {
            String thumb = m.statusThumbUrl != null ? m.statusThumbUrl : "";
            if (!thumb.isEmpty()) {
                flThumb.setVisibility(View.VISIBLE);
                if (ivEye != null) ivEye.setVisibility(View.VISIBLE);
                com.bumptech.glide.Glide.with(ctx)
                    .load(thumb)
                    .centerCrop()
                    .placeholder(R.drawable.bg_skeleton_rect)
                    .into(ivThumb);
            } else {
                flThumb.setVisibility(View.GONE);
                if (ivEye != null) ivEye.setVisibility(View.GONE);
            }
        }

        // Click on whole bubble or thumbnail → open StatusViewerActivity
        final String ownerUid  = (m.statusOwnerUid != null && !m.statusOwnerUid.isEmpty())
                                 ? m.statusOwnerUid : m.senderId;
        final String ownerName = m.statusOwnerName != null ? m.statusOwnerName
                                 : (m.senderName != null ? m.senderName : "");
        android.view.View.OnClickListener openStatus = v -> {
            if (ownerUid == null || ownerUid.isEmpty()) return;
            android.content.Intent intent = new android.content.Intent(
                    com.callx.app.utils.Constants.ACTION_OPEN_STATUS);
            intent.putExtra("ownerUid",  ownerUid);
            intent.putExtra("ownerName", ownerName);
            intent.setPackage(ctx.getPackageName());
            try { ctx.startActivity(intent); }
            catch (android.content.ActivityNotFoundException e) {
                android.widget.Toast.makeText(ctx, "Status viewer not available",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        };
        h.itemView.setOnClickListener(openStatus);
        if (flThumb != null) flThumb.setOnClickListener(openStatus);

        // Sender name (shown in group chat only)
        android.widget.TextView tvName =
            h.itemView.findViewById(R.id.tv_status_seen_name);
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(View.VISIBLE);
            } else {
                tvName.setVisibility(View.GONE);
            }
        }

        // Time
        android.widget.TextView tvTime =
            h.itemView.findViewById(R.id.tv_status_seen_time);
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // REEL SEEN BUBBLE — "🎬 Watched your reel" system event row.
    // Layout: item_reel_seen_bubble.xml
    //   • iv_reel_seen_avatar   → circular avatar (Glide)
    //   • fl_reel_seen_thumb    → FrameLayout container (tappable → opens reel)
    //   • iv_reel_seen_thumb    → reel thumbnail
    //   • iv_reel_seen_play     → play icon overlay on thumbnail
    //   • tv_reel_seen_label    → "Watched your reel" (set in XML)
    //   • tv_reel_seen_name     → sender name (group only)
    //   • tv_reel_seen_time     → formatted timestamp
    // No long-press / reactions / reply — system event.
    // ──────────────────────────────────────────────────────────────
    private void bindReelSeenBubble(@NonNull VH h, @NonNull Message m) {
        Context ctx = h.itemView.getContext();

        // Avatar
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(R.id.iv_reel_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                com.bumptech.glide.Glide.with(ctx)
                    .load(photo)
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        // Click handler — reelId se reel kholo (thumb ho ya na ho, always kaam kare)
        final String reelId = m.reelId;
        android.view.View.OnClickListener openReel = v -> {
            if (reelId == null || reelId.isEmpty()) return;
            android.content.Intent intent = new android.content.Intent(
                    com.callx.app.utils.Constants.ACTION_OPEN_REEL);
            intent.putExtra("reelId", reelId);
            intent.setPackage(ctx.getPackageName());
            ctx.startActivity(intent);
        };

        // Reel thumbnail + play icon
        android.view.View flThumb = h.itemView.findViewById(R.id.fl_reel_seen_thumb);
        android.widget.ImageView ivThumb = h.itemView.findViewById(R.id.iv_reel_seen_thumb);
        android.widget.ImageView ivPlay  = h.itemView.findViewById(R.id.iv_reel_seen_play);
        if (ivThumb != null) {
            String thumb = m.reelThumbUrl != null ? m.reelThumbUrl : "";
            if (!thumb.isEmpty()) {
                ivThumb.setVisibility(android.view.View.VISIBLE);
                if (ivPlay != null) ivPlay.setVisibility(android.view.View.VISIBLE);
                com.bumptech.glide.Glide.with(ctx)
                    .load(thumb)
                    .centerCrop()
                    .placeholder(R.drawable.bg_skeleton_rect)
                    .into(ivThumb);
            } else {
                ivThumb.setVisibility(android.view.View.GONE);
                if (ivPlay != null) ivPlay.setVisibility(android.view.View.GONE);
            }
        }
        // Click on FrameLayout container, play icon, AND whole item
        if (flThumb != null) flThumb.setOnClickListener(openReel);
        if (ivPlay  != null) ivPlay.setOnClickListener(openReel);
        h.itemView.setOnClickListener(openReel);

        // Sender name (group only)
        android.widget.TextView tvName =
            h.itemView.findViewById(R.id.tv_reel_seen_name);
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(android.view.View.VISIBLE);
            } else {
                tvName.setVisibility(android.view.View.GONE);
            }
        }

        // Time
        android.widget.TextView tvTime =
            h.itemView.findViewById(R.id.tv_reel_seen_time);
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Core bind logic (mirrors MessageAdapter)
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
                com.callx.app.utils.ChatThemeManager
                        .get(ctx)
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
        if (h.tvTime != null && m.timestamp > 0) {
            h.tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }

        // ── REPLY PREVIEW (SwipeReplySystem v1) ─────────────────────────
        if (h.llReplyPreview != null) {
            boolean hasReply = m.replyToId != null && !m.replyToId.isEmpty();
            h.llReplyPreview.setVisibility(hasReply ? View.VISIBLE : View.GONE);
            if (hasReply) {
                if (h.tvReplySender != null)
                    h.tvReplySender.setText(
                            m.replyToSenderName != null ? m.replyToSenderName : "");
                if (h.tvReplyText != null)
                    h.tvReplyText.setText(
                            m.replyToText != null ? m.replyToText : "[Original message]");
                // Thumbnail
                if (h.ivReplyThumb != null) {
                    String thumbUrl = m.replyToMediaUrl;
                    if (thumbUrl != null && !thumbUrl.isEmpty()) {
                        h.ivReplyThumb.setVisibility(View.VISIBLE);
                        com.bumptech.glide.Glide.with(ctx)
                                .load(thumbUrl).centerCrop()
                                .into(h.ivReplyThumb);
                    } else {
                        h.ivReplyThumb.setVisibility(View.GONE);
                    }
                }
                // Click → scroll to original message
                final String replyId = m.replyToId;
                h.llReplyPreview.setOnClickListener(v -> {
                    if (actionListener != null) {
                        actionListener.onNavigateToOriginal(replyId);
                    }
                });
            } else {
                h.llReplyPreview.setOnClickListener(null);
            }
        }

        // Sender name (group chats)
        if (isGroup && !sent && h.tvSenderName != null) {
            h.tvSenderName.setVisibility(View.VISIBLE);
            String sn = m.senderName != null ? m.senderName : "Member";
            h.tvSenderName.setText(sn);
        } else if (h.tvSenderName != null) {
            h.tvSenderName.setVisibility(View.GONE);
        }

        // Deleted message
        if (Boolean.TRUE.equals(m.deleted)) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText(sent ? "You deleted this message" : "This message was deleted");
            h.tvMessage.setAlpha(0.6f);
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

                    // ── Progressive loading: thumb instantly → full replaces ──
                    // If thumbnail exists: show it immediately, then full overlays
                    // If no thumbnail: direct load from cache/network
                    if (thumbUrl != null && !thumbUrl.isEmpty()) {
                        // Step 1: Show thumbnail instantly (tiny, ~30KB)
                        Glide.with(ctx)
                            .load(thumbUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(200, 200)
                            .into(h.ivImage);

                        // Step 2: Load full in background — replaces thumb with crossfade
                        Glide.with(ctx)
                            .load(fullUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .thumbnail(Glide.with(ctx)
                                .load(thumbUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL))
                            .transition(com.bumptech.glide.load.resource.drawable
                                .DrawableTransitionOptions.withCrossFade(400))
                            .placeholder(R.drawable.ic_file)
                            .error(R.drawable.ic_file)
                            .into(h.ivImage);
                    } else {
                        // No thumbnail — direct progressive load with cache
                        java.io.File cachedImg = MediaCache.getCached(ctx, fullUrl);
                        if (cachedImg != null) {
                            Glide.with(ctx).load(cachedImg)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(R.drawable.ic_file)
                                .into(h.ivImage);
                        } else {
                            Glide.with(ctx).load(fullUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(R.drawable.ic_file)
                                .error(R.drawable.ic_file)
                                .into(h.ivImage);
                            // Cache in background for next time
                            MediaCache.get(ctx, fullUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {}
                                @Override public void onError(String reason) {}
                            });
                        }
                    }

                    // Click → open full-screen viewer
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
                    // FIX v14: Video thumbnail — MediaCache se check karo
                    java.io.File cachedVid = MediaCache.getCached(ctx, vUrl);
                    if (cachedVid != null) {
                        android.util.Log.d("PagingAdapter", "Video cache HIT: " + cachedVid.getName());
                        Glide.with(ctx).load(cachedVid)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_file)
                            .into(h.ivImage);
                    } else {
                        android.util.Log.d("PagingAdapter", "Video cache MISS, downloading: " + vUrl);
                        Glide.with(ctx).load(vUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_file)
                            .into(h.ivImage);
                        // FIX v14: Video background mein pre-cache karo (MediaStreamCache se partial)
                        com.callx.app.cache.MediaStreamCache.getInstance(ctx)
                            .preloadPartial(vUrl, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                                @Override public void onComplete(java.io.File file) {
                                    android.util.Log.d("PagingAdapter", "Video partial cached: " + file.getName());
                                }
                                @Override public void onError(String error) {
                                    android.util.Log.w("PagingAdapter", "Video preload failed: " + error);
                                }
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
                    // FIX v14: Audio preload — MediaStreamCache se pehle 512KB cache karo
                    // Taaki play button press karne par turant start ho, buffer nahi kare
                    java.io.File cachedAudio = MediaCache.getCached(ctx, aUrl);
                    if (cachedAudio == null && aUrl != null && !aUrl.isEmpty()) {
                        com.callx.app.cache.MediaStreamCache.getInstance(ctx)
                            .preloadPartial(aUrl, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                                @Override public void onComplete(java.io.File file) {
                                    android.util.Log.d("PagingAdapter", "Audio preloaded: " + file.getName());
                                }
                                @Override public void onError(String error) {}
                                @Override public void onProgress(int percent) {}
                            });
                    }
                } else {
                    // Fallback if no audio layout
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText("Audio message");
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
                            // Pehle local cache check karo
                            java.io.File cached = MediaCache.getCached(ctx, fUrl);
                            if (cached != null) {
                                FileUtils.openOrDownload(ctx, cached.toURI().toString(), fName);
                                return;
                            }
                            android.widget.Toast.makeText(ctx, "Downloading…", android.widget.Toast.LENGTH_SHORT).show();
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
                    h.tvMessage.setText(m.fileName != null ? m.fileName : "File");
                }
                break;
            default: // "text", "emoji", etc.
                h.tvMessage.setVisibility(View.VISIBLE);
                String txt = m.text != null ? m.text : "";
                if (Boolean.TRUE.equals(m.edited)) txt += " (edited)";
                // ── Font Style: sender ke selected typing style ko receiver pe bhi apply karo ──
                applyFontStyle(h.tvMessage, m.fontStyle);
                // ── Clickable links: URLs, phone numbers, emails ────────────
                android.text.SpannableString spanned = new android.text.SpannableString(txt);
                android.text.util.Linkify.addLinks(spanned,
                    android.text.util.Linkify.WEB_URLS |
                    android.text.util.Linkify.PHONE_NUMBERS |
                    android.text.util.Linkify.EMAIL_ADDRESSES);
                h.tvMessage.setText(spanned);
                // Link color matching bubble theme
                boolean isSentMsg = currentUid.equals(m.senderId);
                int linkColor = isSentMsg ? 0xFFB3E5FC : 0xFF1565C0;
                h.tvMessage.setLinkTextColor(linkColor);
                h.tvMessage.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                h.tvMessage.setHighlightColor(0x33FFFFFF);
                h.tvMessage.setAlpha(1f);
                h.tvMessage.setTextColor(
                    com.callx.app.utils.ChatThemeManager.get(ctx).getTextColor(isSentMsg));
                break;
        }

        // ── Delivery status (sent messages only) ─────────────────
        if (sent && h.tvStatus != null) {
            h.tvStatus.setVisibility(View.VISIBLE);
            String status = m.status != null ? m.status : "sent";
            switch (status) {
                case "seen":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTickColor(true));
                    break;
                case "delivered":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTickColor(false));
                    break;
                default:
                    h.tvStatus.setText("✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTickColor(false));
                    break;
            }
        } else if (h.tvStatus != null) {
            h.tvStatus.setVisibility(View.GONE);
        }

        // ── Long press — multi-select mode ya action sheet ─────────────────
        h.itemView.setOnLongClickListener(v -> {
            if (!multiSelectMode) {
                enterMultiSelectMode(m);
            } else {
                if (actionListener != null) showActionBottomSheet(ctx, m);
            }
            return true;
        });
        h.itemView.setOnClickListener(v -> {
            if (multiSelectMode) {
                String id = m.messageId != null ? m.messageId : m.id;
                if (id != null) {
                    if (selectedMessageIds.contains(id)) {
                        selectedMessageIds.remove(id);
                    } else {
                        selectedMessageIds.add(id);
                    }
                    notifyItemChanged(h.getAdapterPosition());
                    if (multiSelectListener != null)
                        multiSelectListener.onSelectionChanged(selectedMessageIds.size());
                    if (selectedMessageIds.isEmpty()) exitMultiSelectMode();
                }
            }
        });
        applySelectionHighlight(h, m);
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

        // Pehle local cache check — cached hai to seedha play (zero data use)
        java.io.File cached = MediaCache.getCached(h.itemView.getContext(), url);
        if (cached != null) {
            playAudioFromPath(h, cached.getAbsolutePath(), position);
            return;
        }

        // FIX v14: MediaStreamCache use karo — pehle 512KB stream karo (fast start),
        // baaki background mein download hota rahe. User ko buffer nahi karega.
        com.callx.app.cache.MediaStreamCache.getInstance(h.itemView.getContext())
            .preloadPartial(url, new com.callx.app.cache.MediaStreamCache.DownloadCallback() {
                @Override public void onComplete(java.io.File file) {
                    // Partial/full file ready — play from local file (zero buffering)
                    android.util.Log.d("AudioPlay", "MediaStreamCache ready, playing: " + file.getName());
                    playAudioFromPath(h, file.getAbsolutePath(), position);
                }
                @Override public void onError(String error) {
                    // Fallback: stream directly from URL
                    android.util.Log.w("AudioPlay", "MediaStreamCache failed, streaming URL: " + error);
                    playAudioFromPath(h, url, position);
                }
                @Override public void onProgress(int percent) {
                    android.util.Log.v("AudioPlay", "Audio preload: " + percent + "%");
                }
            });
    }

    private void playAudioFromPath(@NonNull VH h, String path, int position) {
        try {
            if (player != null) { try { player.release(); } catch (Exception ignored) {} }
            player = new MediaPlayer();
            
            // Agar local file hai to FileDescriptor se set karo (cache files ke liye)
            // Agar URL hai to directly
            if (path.startsWith("http")) {
                player.setDataSource(path);
            } else {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                        player.setDataSource(fis.getFD());
                    }
                } else {
                    // File nahi milti to URL ke bahawe try karo
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
                android.util.Log.e("AudioPlay", "Error: " + what + " extra: " + extra + " path: " + path);
                playingPos = -1;
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
                return true;
            });
        } catch (Exception e) {
            android.util.Log.e("AudioPlay", "playAudioFromPath error: " + e.getMessage() + " path: " + path);
            if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Long-press bottom sheet actions
    // ──────────────────────────────────────────────────────────────
    private void showActionBottomSheet(Context ctx, Message m) {
        if (actionListener == null) return;
        String[] options = {"Reply", "Copy", "Star", "Forward", "Delete"};
        new android.app.AlertDialog.Builder(ctx)
            .setItems(options, (d, which) -> {
                switch (which) {
                    case 0: actionListener.onReply(m);   break;
                    case 1: actionListener.onCopy(m);    break;
                    case 2: actionListener.onStar(m);    break;
                    case 3: actionListener.onForward(m); break;
                    case 4: actionListener.onDelete(m);  break;
                }
            }).show();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        if (holder.ivImage != null) Glide.with(holder.ivImage).clear(holder.ivImage);
    }

    // ──────────────────────────────────────────────────────────────
    // Font Style helper — TypingStyleManager.STYLE_* (0–19) ko TextView pe apply
    // ──────────────────────────────────────────────────────────────
    private static void applyFontStyle(TextView tv, int styleId) {
        switch (styleId) {
            case com.callx.app.utils.TypingStyleManager.STYLE_BOLD:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_ITALIC:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_BOLD_ITALIC:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG:
                try {
                    android.graphics.Typeface samsungTf = android.graphics.Typeface.create("SamsungOne", android.graphics.Typeface.NORMAL);
                    if (samsungTf != null && !samsungTf.equals(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL))) {
                        tv.setTypeface(samsungTf);
                    } else {
                        android.graphics.Typeface alt = android.graphics.Typeface.create("samsung-sans", android.graphics.Typeface.NORMAL);
                        if (alt != null && !alt.equals(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL))) {
                            tv.setTypeface(alt);
                        } else {
                            tv.setTypeface(android.graphics.Typeface.SERIF);
                        }
                    }
                } catch (Exception e) {
                    tv.setTypeface(android.graphics.Typeface.SERIF);
                }
                break;
            case com.callx.app.utils.TypingStyleManager.STYLE_MONOSPACE:
                tv.setTypeface(android.graphics.Typeface.MONOSPACE); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SERIF:
                tv.setTypeface(android.graphics.Typeface.SERIF); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SERIF_BOLD:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_CONDENSED:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_LIGHT:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_HANDWRITING:
                tv.setTypeface(android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_MEDIUM:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_THIN:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SERIF_ITALIC:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_CONDENSED_BOLD:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_BLACK:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_CURSIVE:
                tv.setTypeface(android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SANS_MEDIUM:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_MONO_BOLD:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_LIGHT_ITALIC:
                tv.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_CLASSIC_BOLD:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD_ITALIC)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG_SCRIPT:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_NORMAL:
            default:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)); break;
        }
    }

    // ──────────────────────────────────────────────────────────────
    // ViewHolder — covers all view IDs used in both item layouts
    // ──────────────────────────────────────────────────────────────
    static class VH extends RecyclerView.ViewHolder {
        TextView     tvMessage, tvTime, tvSenderName, tvFileName;
        ImageView    ivImage;
        TextView     tvStatus;   // tv_status in both item layouts
        LinearLayout llAudio, llFile;
        ImageButton  btnPlayPause;
        ImageView    btnDownload;
        // SwipeReplySystem v1: reply preview views
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
            // SwipeReplySystem v1
            llReplyPreview = v.findViewById(R.id.ll_reply_preview);
            tvReplySender  = v.findViewById(R.id.tv_reply_sender);
            tvReplyText    = v.findViewById(R.id.tv_reply_text);
            ivReplyThumb   = v.findViewById(R.id.iv_reply_thumb);
        }
    }
}
