package com.callx.app.conversation;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.PrecomputedTextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
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
 * Production-grade MessageAdapter — DiffUtil + ViewStub + PrecomputedText edition.
 *
 * Optimizations applied:
 *   • Migrated to ListAdapter (AsyncListDiffer under the hood) — no notifyDataSetChanged()
 *   • DiffUtil.ItemCallback compares by messageId, then full object equality
 *   • TICK_PAYLOAD partial bind still works — only tv_status redrawn for status changes
 *   • ViewStub lazy inflation for heavy layouts (video, audio, file, poll, link_preview)
 *   • PrecomputedTextCompat for long text messages — avoids heavy measure on UI thread
 *   • Multi-select now uses notifyItemRangeChanged() instead of notifyDataSetChanged()
 */
public class MessageAdapter extends ListAdapter<Message, MessageAdapter.VH> {

    // ── DiffUtil callback ─────────────────────────────────────────────────────
    private static final DiffUtil.ItemCallback<Message> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<Message>() {
                @Override
                public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
                    // Identity check — same database row / Firebase node
                    String oldId = oldItem.id != null ? oldItem.id : oldItem.messageId;
                    String newId = newItem.id != null ? newItem.id : newItem.messageId;
                    if (oldId == null || newId == null) return oldItem == newItem;
                    return oldId.equals(newId);
                }

                @Override
                public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
                    // Full structural equality — if false, onBindViewHolder is called with empty payload
                    return Objects.equals(oldItem.id,             newItem.id)
                        && Objects.equals(oldItem.text,           newItem.text)
                        && Objects.equals(oldItem.type,           newItem.type)
                        && Objects.equals(oldItem.status,         newItem.status)
                        && Objects.equals(oldItem.mediaUrl,       newItem.mediaUrl)
                        && Objects.equals(oldItem.thumbnailUrl,   newItem.thumbnailUrl)
                        && Objects.equals(oldItem.fileName,       newItem.fileName)
                        && Objects.equals(oldItem.fileSize,       newItem.fileSize)
                        && Objects.equals(oldItem.duration,       newItem.duration)
                        && Objects.equals(oldItem.reactions,      newItem.reactions)
                        && Objects.equals(oldItem.edited,         newItem.edited)
                        && Objects.equals(oldItem.deleted,        newItem.deleted)
                        && Objects.equals(oldItem.starred,        newItem.starred)
                        && Objects.equals(oldItem.pinned,         newItem.pinned)
                        && Objects.equals(oldItem.replyToText,    newItem.replyToText)
                        && Objects.equals(oldItem.replyToSenderName, newItem.replyToSenderName)
                        && Objects.equals(oldItem.forwardedFrom,  newItem.forwardedFrom)
                        && Objects.equals(oldItem.pollVotes,      newItem.pollVotes)
                        && Objects.equals(oldItem.pollClosed,     newItem.pollClosed)
                        && Objects.equals(oldItem.expiresAt,      newItem.expiresAt);
                }

                @Nullable
                @Override
                public Object getChangePayload(@NonNull Message oldItem, @NonNull Message newItem) {
                    // Deliver TICK_PAYLOAD when ONLY the status field changed
                    // → onBindViewHolder will handle it with a partial bind (tick only)
                    boolean onlyStatusChanged =
                            Objects.equals(oldItem.id,           newItem.id)
                         && !Objects.equals(oldItem.status,      newItem.status)
                         && Objects.equals(oldItem.text,         newItem.text)
                         && Objects.equals(oldItem.type,         newItem.type)
                         && Objects.equals(oldItem.reactions,    newItem.reactions)
                         && Objects.equals(oldItem.edited,       newItem.edited)
                         && Objects.equals(oldItem.deleted,      newItem.deleted)
                         && Objects.equals(oldItem.starred,      newItem.starred)
                         && Objects.equals(oldItem.pinned,       newItem.pinned);
                    return onlyStatusChanged ? TICK_PAYLOAD : null;
                }
            };

    // ────────────────────────────────────────────────────────────────────────
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

    public interface MultiSelectListener {
        void onSelectionChanged(int count);
    }

    private final String currentUid;
    private final boolean isGroup;
    private ActionListener actionListener;

    // ── Multi-select state ────────────────────────────────────────────────────
    private boolean multiSelectMode = false;
    private final Set<String> selectedMessageIds = new HashSet<>();
    private MultiSelectListener multiSelectListener;

    public void setMultiSelectListener(MultiSelectListener l) { this.multiSelectListener = l; }

    public void enterMultiSelectMode(Message firstMessage) {
        multiSelectMode = true;
        selectedMessageIds.clear();
        if (firstMessage != null && firstMessage.id != null) {
            selectedMessageIds.add(firstMessage.id);
        }
        // Refresh all visible items without destroying ViewHolders
        notifyItemRangeChanged(0, getItemCount());
        if (multiSelectListener != null)
            multiSelectListener.onSelectionChanged(selectedMessageIds.size());
    }

    public void exitMultiSelectMode() {
        multiSelectMode = false;
        selectedMessageIds.clear();
        notifyItemRangeChanged(0, getItemCount());
        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(0);
    }

    public boolean isInMultiSelectMode() { return multiSelectMode; }

    public List<Message> getSelectedMessages() {
        List<Message> result = new ArrayList<>();
        for (Message m : getCurrentList()) {
            if (m.id != null && selectedMessageIds.contains(m.id)) result.add(m);
        }
        return result;
    }

    private static final int TYPE_SENT       = 1;
    private static final int TYPE_RECEIVED   = 2;
    private static final int TYPE_STATUS_SEEN = 3;
    private static final int TYPE_REEL_SEEN  = 4;
    private static final int TYPE_HIDDEN     = 5;

    /** Payload key for partial bind — only tick pill is updated. */
    public static final String TICK_PAYLOAD = "TICK_PAYLOAD";

    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());

    // ── Timestamp formatting cache — avoids SimpleDateFormat + Date allocation per bind ──
    private final android.util.LruCache<Long, String> timeStringCache =
            new android.util.LruCache<>(256);
    private final java.util.Date reuseDate = new java.util.Date();

    private String formatTime(long timestamp) {
        long key = (timestamp / 60_000L) * 60_000L;
        String cached = timeStringCache.get(key);
        if (cached != null) return cached;
        reuseDate.setTime(timestamp);
        String s = timeFmt.format(reuseDate);
        timeStringCache.put(key, s);
        return s;
    }

    private MediaPlayer player;
    private int playingPos = -1;
    private final android.os.Handler seekHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable seekUpdater;

    public MessageAdapter(String currentUid, boolean isGroup) {
        super(DIFF_CALLBACK);
        this.currentUid = currentUid;
        this.isGroup    = isGroup;
        setHasStableIds(false);
    }

    public void setActionListener(ActionListener l) { this.actionListener = l; }

    /**
     * Update only the tick pill of a message by Firebase ID.
     * Uses notifyItemChanged(pos, TICK_PAYLOAD) → partial bind, zero flicker.
     * Also updates the in-memory status so the next full bind is correct.
     */
    public void updateMessageStatus(String messageId, String newStatus) {
        List<Message> list = getCurrentList();
        for (int i = 0; i < list.size(); i++) {
            Message m = list.get(i);
            if (messageId != null && messageId.equals(m.id)) {
                m.status = newStatus;
                notifyItemChanged(i, TICK_PAYLOAD);
                return;
            }
        }
    }

    @Override public int getItemViewType(int pos) {
        Message m = getItem(pos);
        if ("status_seen".equals(m.type)) return TYPE_STATUS_SEEN;
        if ("reel_seen".equals(m.type)) {
            return currentUid.equals(m.reelOwnerUid) ? TYPE_REEL_SEEN : TYPE_HIDDEN;
        }
        return currentUid.equals(m.senderId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HIDDEN) {
            View v = new View(parent.getContext());
            v.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
            return new VH(v);
        }
        int layout;
        if (viewType == TYPE_SENT)             layout = R.layout.item_message_sent;
        else if (viewType == TYPE_STATUS_SEEN) layout = R.layout.item_status_seen_bubble;
        else if (viewType == TYPE_REEL_SEEN)   layout = R.layout.item_reel_seen_bubble;
        else                                   layout = R.layout.item_message_received;

        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        // PERF: item-level micro-optimizations applied once at create time
        v.setSaveEnabled(false);
        v.setLayerType(View.LAYER_TYPE_NONE, null);
        VH vh = new VH(v);

        // ── One-time constant setup — moves work out of onBindViewHolder ────
        if (vh.tvStatus != null) vh.tvStatus.setTextSize(14f);
        // tvMessage text size is constant — set once here, MessageFontSizeManager value
        // is read-once; dynamic changes (if user changes font size) should call notifyDataSetChanged.
        if (vh.tvMessage != null) {
            Context createCtx = parent.getContext();
            vh.tvMessage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    com.callx.app.utils.MessageFontSizeManager.get(createCtx).getFontSizeSp());
            vh.tvMessage.setTypeface(TF_NORMAL);
        }

        // ── 70% screen-width cap on bubble and text ──────────────────────────
        int screenW = parent.getContext().getResources().getDisplayMetrics().widthPixels;
        int maxW    = (int) (screenW * 0.70f);
        if (vh.tvMessage != null)  vh.tvMessage.setMaxWidth(maxW);
        if (vh.llReactions != null) {
            ViewGroup.LayoutParams lp = vh.llReactions.getLayoutParams();
            if (lp != null) { lp.width = maxW; vh.llReactions.setLayoutParams(lp); }
        }
        return vh;
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos,
                                           @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(TICK_PAYLOAD)) {
            // Partial bind — only redraw tick pill, nothing else
            bindTickOnly(h, getItem(pos));
            return;
        }
        onBindViewHolder(h, pos);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m   = getItem(pos);
        Context ctx = h.itemView.getContext();
        int viewType = getItemViewType(pos);

        if (viewType == TYPE_HIDDEN)      { return; }
        if (viewType == TYPE_STATUS_SEEN) { bindStatusSeenBubble(h, m, ctx); return; }
        if (viewType == TYPE_REEL_SEEN)   { bindReelSeenBubble(h, m, ctx);   return; }

        boolean sent = (viewType == TYPE_SENT);

        // ── Reset all content views (only those already inflated need hiding) ─
        h.tvMessage.setVisibility(View.GONE);
        h.ivImage.setVisibility(View.GONE);
        if (h.flVideo       != null) h.flVideo.setVisibility(View.GONE);
        if (h.llAudio       != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile        != null) h.llFile.setVisibility(View.GONE);
        if (h.llPoll        != null) h.llPoll.setVisibility(View.GONE);
        if (h.llLinkPreview != null) h.llLinkPreview.setVisibility(View.GONE);
        if (h.llReelShare   != null) h.llReelShare.setVisibility(View.GONE);
        if (h.tvEdited      != null) h.tvEdited.setVisibility(View.GONE);

        // Pinned label
        if (h.tvPinnedLabel != null)
            h.tvPinnedLabel.setVisibility(Boolean.TRUE.equals(m.pinned) ? View.VISIBLE : View.GONE);

        // Forwarded label
        if (h.tvForwarded != null) {
            boolean fwd = m.forwardedFrom != null && !m.forwardedFrom.isEmpty();
            h.tvForwarded.setVisibility(fwd ? View.VISIBLE : View.GONE);
            if (fwd) h.tvForwarded.setText("\u21AA Forwarded from " + m.forwardedFrom);
        }

        // Quick Forward Button
        if (h.btnQuickForward != null) {
            String mt = m.type != null ? m.type : "text";
            boolean showFwdBtn = mt.equals("image") || mt.equals("video") || mt.equals("audio")
                    || mt.equals("file") || mt.equals("reel_share")
                    || (mt.equals("text") && m.text != null
                        && (m.text.contains("http://") || m.text.contains("https://")));
            h.btnQuickForward.setVisibility(showFwdBtn ? View.VISIBLE : View.GONE);
            if (showFwdBtn) {
                h.btnQuickForward.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onForward(m);
                });
            }
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
                    Intent i = new Intent().setClassName(ctx,
                            "com.callx.app.conversation.ChatActivity");
                    i.putExtra("partnerUid",  sUid);
                    i.putExtra("partnerName", sName != null ? sName : "");
                    ctx.startActivity(i);
                });
            } else {
                h.ivSenderAvatar.setOnClickListener(null);
            }
        }

        // Group sender name
        if (h.tvSenderName != null) {
            boolean show = isGroup && !sent;
            h.tvSenderName.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) h.tvSenderName.setText(m.senderName != null ? m.senderName : "Unknown");
        }

        // Reply preview
        if (h.llReplyPreview != null) {
            boolean hasReply = m.replyToText != null && !m.replyToText.isEmpty();
            h.llReplyPreview.setVisibility(hasReply ? View.VISIBLE : View.GONE);
            if (hasReply) {
                if (h.tvReplySender != null)
                    h.tvReplySender.setText(m.replyToSenderName != null ? m.replyToSenderName : "");
                if (h.tvReplyText != null)
                    h.tvReplyText.setText(m.replyToText);
            }
        }

        // Deleted state — short-circuit
        if (Boolean.TRUE.equals(m.deleted)) {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText("\uD83D\uDEAB  This message was deleted");
            h.tvMessage.setTextColor(0xFF9E9E9E);
            h.tvMessage.setTextSize(13f);
            h.tvMessage.setTypeface(null, android.graphics.Typeface.ITALIC);
            bindFooter(h, m, sent);
            applySelectionHighlight(h, m);
            setupLongPress(h, m, sent, ctx);
            return;
        }

        // Resolve type
        String type = m.type == null ? "text" : m.type;
        if ("image".equals(type) || (m.imageUrl != null && !m.imageUrl.isEmpty()
                && (m.mediaUrl == null || m.mediaUrl.isEmpty())))
            type = "image";
        if ("gif".equals(type)) type = "gif";

        // Bubble background (runtime gradient via ChatThemeManager)
        try {
            View llBubble = h.itemView.findViewById(R.id.ll_bubble);
            if (llBubble != null) {
                boolean hasReply = m.replyToText != null && !m.replyToText.isEmpty();
                com.callx.app.utils.ChatThemeManager.get(ctx)
                        .applyBubble(llBubble, sent, type, hasReply);
            }
        } catch (Exception ignored) {}

        switch (type) {
            // ── IMAGE / GIF ─────────────────────────────────────────────────
            case "gif":
            case "image": {
                h.ivImage.setVisibility(View.VISIBLE);
                String url = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
                android.util.Log.d("ImageLoad", "Loading image/gif: " + url);
                boolean isGif = "gif".equals(m.type);
                java.io.File cached = isGif ? null : MediaCache.getCached(ctx, url);
                if (isGif) {
                    Glide.with(ctx).asGif().load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.bg_circle_white)
                            .into(h.ivImage);
                } else if (cached != null) {
                    Glide.with(ctx).load(cached)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.bg_circle_white)
                            .into(h.ivImage);
                } else {
                    Glide.with(ctx).load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.bg_circle_white)
                            .into(h.ivImage);
                    MediaCache.get(ctx, url, new MediaCache.Callback() {
                        @Override public void onReady(java.io.File f) {}
                        @Override public void onError(String r) {}
                    });
                }
                final String fu = url;
                final String tu = m.thumbnailUrl;
                h.ivImage.setOnClickListener(v -> showImageActionSheet(ctx, m, fu, tu != null ? tu : fu));
                h.ivImage.setOnLongClickListener(v -> { openActionSheet(ctx, m); return true; });
                break;
            }

            // ── VIDEO ────────────────────────────────────────────────────────
            case "video": {
                ensureVideoInflated(h);
                h.flVideo.setVisibility(View.VISIBLE);
                String thumb    = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
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
                            .setClassName(ctx, "com.callx.app.player.VideoPlayerActivity");
                    intent.putExtra("videoUrl", videoUrl);
                    intent.putExtra("thumbUrl", m.thumbnailUrl);
                    intent.putExtra("durationMs", m.duration != null ? m.duration.intValue() : 0);
                    ctx.startActivity(intent);
                });
                break;
            }

            // ── AUDIO ────────────────────────────────────────────────────────
            case "audio": {
                ensureAudioInflated(h, ctx, sent);
                h.llAudio.setVisibility(View.VISIBLE);
                h.tvAudioDur.setText(m.duration != null
                        ? FileUtils.formatDuration(m.duration) : "0:00");
                if (h.seekAudio != null) {
                    h.seekAudio.setSeed(m.mediaUrl);
                    h.seekAudio.setProgress(playingPos == pos ? h.seekAudio.getProgress() : 0f);
                }
                final int fPos = pos;
                h.btnPlayAudio.setOnClickListener(v -> togglePlay(h, m, fPos));
                h.btnPlayAudio.setImageResource(
                        playingPos == pos ? R.drawable.ic_pause : R.drawable.ic_play);
                if (m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                    if (MediaCache.getCached(ctx, m.mediaUrl) == null) {
                        MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                            @Override public void onReady(java.io.File f) {}
                            @Override public void onError(String r) {}
                        });
                    }
                }
                break;
            }

            // ── FILE ─────────────────────────────────────────────────────────
            case "file": {
                ensureFileInflated(h, ctx, sent);
                h.llFile.setVisibility(View.VISIBLE);
                String fName = m.fileName != null ? m.fileName : "File";
                h.tvFileName.setText(fName);
                h.tvFileMeta.setText(m.fileSize != null
                        ? FileUtils.humanSize(m.fileSize) : "Document");
                if (h.ivDownload != null) {
                    if (!sent && m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                        h.ivDownload.setVisibility(View.VISIBLE);
                        h.ivDownload.setImageResource(R.drawable.ic_file);
                        final String fUrl      = m.mediaUrl;
                        final String fFileName = fName;
                        h.ivDownload.setOnClickListener(v -> {
                            java.io.File cached2 = MediaCache.getCached(ctx, fUrl);
                            if (cached2 != null) {
                                FileUtils.openOrDownload(ctx, cached2.toURI().toString(), fFileName);
                            } else {
                                android.widget.Toast.makeText(ctx, "Downloading…",
                                        android.widget.Toast.LENGTH_SHORT).show();
                                h.ivDownload.setEnabled(false);
                                MediaCache.get(ctx, fUrl, new MediaCache.Callback() {
                                    @Override public void onReady(java.io.File file) {
                                        h.ivDownload.setEnabled(true);
                                        android.widget.Toast.makeText(ctx, "Downloaded!",
                                                android.widget.Toast.LENGTH_SHORT).show();
                                        FileUtils.openOrDownload(ctx,
                                                file.toURI().toString(), fFileName);
                                    }
                                    @Override public void onError(String reason) {
                                        h.ivDownload.setEnabled(true);
                                        android.widget.Toast.makeText(ctx, "Download failed",
                                                android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    } else {
                        h.ivDownload.setVisibility(View.GONE);
                    }
                }
                if (m.mediaUrl != null && !m.mediaUrl.isEmpty()
                        && MediaCache.getCached(ctx, m.mediaUrl) == null) {
                    MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                        @Override public void onReady(java.io.File f) {}
                        @Override public void onError(String r) {}
                    });
                }
                h.llFile.setOnClickListener(v -> {
                    if (m.mediaUrl == null) return;
                    java.io.File cached = MediaCache.getCached(ctx, m.mediaUrl);
                    if (cached != null) {
                        FileUtils.openOrDownload(ctx, cached.toURI().toString(), fName);
                        return;
                    }
                    android.widget.Toast.makeText(ctx, "Downloading…",
                            android.widget.Toast.LENGTH_SHORT).show();
                    MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                        @Override public void onReady(java.io.File file) {
                            FileUtils.openOrDownload(ctx, file.toURI().toString(), fName);
                        }
                        @Override public void onError(String reason) {
                            FileUtils.openOrDownload(ctx, m.mediaUrl, fName);
                        }
                    });
                });
                break;
            }

            // ── POLL ─────────────────────────────────────────────────────────
            case "poll": {
                ensurePollInflated(h);
                h.llPoll.setVisibility(View.VISIBLE);
                bindPoll(h, m, ctx, sent);
                break;
            }

            // ── REEL SHARE ───────────────────────────────────────────────────
            case "reel_share": {
                ensureReelShareInflated(h);
                h.llReelShare.setVisibility(View.VISIBLE);

                // ── Populate card with whatever data is already on the message ──
                bindReelShareCard(h, m, ctx);

                // ── If thumb is missing but reelId exists, fetch from Firebase ──
                boolean thumbMissing = m.reelShareThumb == null || m.reelShareThumb.isEmpty();
                boolean hasReelId    = m.reelId != null && !m.reelId.isEmpty();
                if (thumbMissing && hasReelId) {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reels").child(m.reelId)
                        .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                            @Override
                            public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                                if (snap.exists()) {
                                    // Patch message object with live data
                                    // ✅ FIX: check both "thumbUrl" and "thumbnailUrl" — old reels use thumbUrl, new ones use thumbnailUrl
                                    String t  = snap.child("thumbUrl").getValue(String.class);
                                    if (t == null || t.isEmpty())
                                        t = snap.child("thumbnailUrl").getValue(String.class);
                                    String c  = snap.child("caption").getValue(String.class);
                                    String u  = snap.child("uid").getValue(String.class);
                                    String vu = snap.child("videoUrl").getValue(String.class);
                                    if (t  != null && !t.isEmpty()) m.reelShareThumb    = t;
                                    if (c  != null) m.reelShareCaption   = c;
                                    if (u  != null) m.reelShareUsername  = u;
                                    if (vu != null && (m.reelShareUrl == null || m.reelShareUrl.isEmpty()))
                                        m.reelShareUrl = vu;
                                    bindReelShareCard(h, m, ctx);
                                }
                            }
                            @Override
                            public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                        });
                }

                // ── Open reel in-app on tap ──
                final String reelId2  = m.reelId      != null ? m.reelId      : "";
                final String reelUrl2 = m.reelShareUrl != null ? m.reelShareUrl : "";
                h.llReelShare.setOnClickListener(v -> {
                    // Try deep link URI first (opens in-app via DeepLinkRouterActivity)
                    String deepLink = !reelId2.isEmpty()
                            ? com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/" + reelId2
                            : reelUrl2;
                    if (!deepLink.isEmpty()) {
                        try {
                            Intent ri = new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse(deepLink));
                            ri.setPackage(ctx.getPackageName()); // stay in-app
                            ri.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(ri);
                        } catch (Exception ignored) {
                            if (!reelUrl2.isEmpty()) openInCustomTab(ctx, reelUrl2);
                        }
                    }
                });
                break;
            }

            // ── TEXT (default) ───────────────────────────────────────────────
            default: {
                h.tvMessage.setVisibility(View.VISIBLE);
                h.tvMessage.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTextColor(sent));
                // setTextSize + applyFontStyle moved to onCreateViewHolder (constants)
                if (h.tvEdited != null && Boolean.TRUE.equals(m.edited))
                    h.tvEdited.setVisibility(View.VISIBLE);

                String rawText = m.text != null ? m.text : "";

                // ── Linkify ──────────────────────────────────────────────────
                // Quick pre-check: only allocate SpannableString + run Linkify regex
                // when text is likely to contain a linkable pattern.
                // Plain-text messages (no URL/phone/email) skip the allocation entirely.
                boolean mightHaveLink = rawText.contains("http://")
                        || rawText.contains("https://")
                        || rawText.contains("www.")
                        || rawText.contains("@")
                        || (rawText.length() >= 7 && rawText.contains("+"));
                android.text.SpannableString spanned;
                if (mightHaveLink) {
                    spanned = new android.text.SpannableString(rawText);
                    android.text.util.Linkify.addLinks(spanned,
                            android.text.util.Linkify.WEB_URLS |
                            android.text.util.Linkify.PHONE_NUMBERS |
                            android.text.util.Linkify.EMAIL_ADDRESSES);
                    // Only set MovementMethod when we actually have links — avoids
                    // unnecessary touch-event interception on plain-text rows.
                    int linkColor = sent ? 0xFFB3E5FC : 0xFF1565C0;
                    h.tvMessage.setLinkTextColor(linkColor);
                    h.tvMessage.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    h.tvMessage.setHighlightColor(0x33FFFFFF);
                } else {
                    spanned = new android.text.SpannableString(rawText);
                    // No links — remove MovementMethod so RecyclerView gets touch events back
                    h.tvMessage.setMovementMethod(null);
                }

                // ── PrecomputedTextCompat — avoids heavy text-layout measure on UI thread ──
                // For long messages (>80 chars), pre-compute layout metadata so the
                // TextView's measure/draw passes are cheaper.
                if (rawText.length() > 80) {
                    try {
                        PrecomputedTextCompat.Params params =
                                TextViewCompat.getTextMetricsParams(h.tvMessage);
                        PrecomputedTextCompat pct =
                                PrecomputedTextCompat.create(spanned, params);
                        TextViewCompat.setPrecomputedText(h.tvMessage, pct);
                    } catch (Exception e) {
                        h.tvMessage.setText(spanned);
                    }
                } else {
                    h.tvMessage.setText(spanned);
                }

                // ── Link Preview ─────────────────────────────────────────────
                String previewUrl =
                        com.callx.app.utils.LinkPreviewFetcher.extractFirstUrl(rawText);
                if (previewUrl != null) {
                    ensureLinkPreviewInflated(h, sent);
                    h.llLinkPreview.setTag(previewUrl);
                    h.llLinkPreview.setVisibility(View.INVISIBLE);
                    com.callx.app.utils.LinkPreviewFetcher.fetch(previewUrl,
                            new com.callx.app.utils.LinkPreviewFetcher.Callback() {
                        @Override
                        public void onResult(com.callx.app.utils.LinkPreviewFetcher.Result r) {
                            if (!previewUrl.equals(h.llLinkPreview.getTag())) return;
                            h.llLinkPreview.setVisibility(View.VISIBLE);
                            if (h.tvLinkDomain != null)      h.tvLinkDomain.setText(r.domain);
                            if (h.tvLinkTitle  != null)      h.tvLinkTitle.setText(r.title);
                            if (h.tvLinkDescription != null) {
                                if (r.description != null && !r.description.isEmpty()) {
                                    h.tvLinkDescription.setText(r.description);
                                    h.tvLinkDescription.setVisibility(View.VISIBLE);
                                } else {
                                    h.tvLinkDescription.setVisibility(View.GONE);
                                }
                            }
                            if (h.ivLinkThumb != null) {
                                if (r.imageUrl != null && !r.imageUrl.isEmpty()) {
                                    h.ivLinkThumb.setVisibility(View.VISIBLE);
                                    Glide.with(ctx)
                                            .load(r.imageUrl)
                                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                                            .centerCrop()
                                            .placeholder(android.R.color.darker_gray)
                                            .into(h.ivLinkThumb);
                                } else {
                                    h.ivLinkThumb.setVisibility(View.GONE);
                                }
                            }
                            h.llLinkPreview.setOnClickListener(v -> openInCustomTab(ctx, r.url));
                        }
                        @Override
                        public void onError(String url) {
                            if (!previewUrl.equals(h.llLinkPreview.getTag())) return;
                            h.llLinkPreview.setVisibility(View.GONE);
                        }
                    });
                } else if (h.llLinkPreview != null) {
                    h.llLinkPreview.setVisibility(View.GONE);
                }
                break;
            }
        }

        // ── Reactions ────────────────────────────────────────────────────────
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
        applySelectionHighlight(h, m);
        setupLongPress(h, m, sent, ctx);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ViewStub inflation helpers — each ensures one-time inflation and caches
    //  child view references on the VH for fast access on subsequent rebinds.
    // ══════════════════════════════════════════════════════════════════════════

    private void ensureVideoInflated(@NonNull VH h) {
        if (h.flVideo != null) return;
        if (h.stubVideo == null) return;
        h.stubVideo.inflate();
        h.flVideo        = h.itemView.findViewById(R.id.fl_video);
        h.ivVideoThumb   = h.itemView.findViewById(R.id.iv_video_thumb);
        h.tvVideoDuration = h.itemView.findViewById(R.id.tv_duration);
    }

    private void ensureAudioInflated(@NonNull VH h, Context ctx, boolean sent) {
        if (h.llAudio != null) return;
        if (h.stubAudio == null) return;
        h.stubAudio.inflate();
        h.llAudio      = h.itemView.findViewById(R.id.ll_audio);
        h.btnPlayAudio = h.itemView.findViewById(R.id.btn_play_pause);
        h.seekAudio    = h.itemView.findViewById(R.id.seek_audio);
        h.tvAudioDur   = h.itemView.findViewById(R.id.tv_audio_dur);
        // Set text color after inflation (single shared layout, color set programmatically)
        if (h.tvAudioDur != null) {
            try {
                int color = ctx.getResources().getColor(
                        sent ? R.color.bubble_sent_text : R.color.bubble_received_text);
                h.tvAudioDur.setTextColor(color);
            } catch (Exception ignored) {}
        }
    }

    private void ensureFileInflated(@NonNull VH h, Context ctx, boolean sent) {
        if (h.llFile != null) return;
        if (h.stubFile == null) return;
        h.stubFile.inflate();
        h.llFile     = h.itemView.findViewById(R.id.ll_file);
        h.tvFileName = h.itemView.findViewById(R.id.tv_file_name);
        h.tvFileMeta = h.itemView.findViewById(R.id.tv_file_meta);
        h.ivDownload = h.itemView.findViewById(R.id.btn_download);
        // Set text colors after inflation
        try {
            int color = ctx.getResources().getColor(
                    sent ? R.color.bubble_sent_text : R.color.bubble_received_text);
            if (h.tvFileName != null) h.tvFileName.setTextColor(color);
        } catch (Exception ignored) {}
    }

    private void ensurePollInflated(@NonNull VH h) {
        if (h.llPoll != null) return;
        if (h.stubPoll == null) return;
        h.stubPoll.inflate();
        h.llPoll = h.itemView.findViewById(R.id.ll_poll);
    }

    private void ensureLinkPreviewInflated(@NonNull VH h, boolean sent) {
        if (h.llLinkPreview != null) return;
        if (h.stubLinkPreview == null) return;
        h.stubLinkPreview.inflate();
        h.llLinkPreview     = h.itemView.findViewById(R.id.ll_link_preview);
        h.tvLinkDomain      = h.itemView.findViewById(R.id.tv_link_domain);
        h.tvLinkTitle       = h.itemView.findViewById(R.id.tv_link_title);
        h.tvLinkDescription = h.itemView.findViewById(R.id.tv_link_description);
        h.ivLinkThumb       = h.itemView.findViewById(R.id.iv_link_thumb);
    }

    private void ensureReelShareInflated(@NonNull VH h) {
        if (h.llReelShare != null) return;
        if (h.stubReelShare == null) return;
        h.stubReelShare.inflate();
        h.llReelShare          = h.itemView.findViewById(R.id.ll_reel_share);
        h.ivReelShareThumb     = h.itemView.findViewById(R.id.iv_reel_share_thumb);
        h.tvReelShareUsername  = h.itemView.findViewById(R.id.tv_reel_share_username);
        h.tvReelShareCaption   = h.itemView.findViewById(R.id.tv_reel_share_caption);
    }

    /** Fills the reel share card views from a (possibly partially populated) Message. */
    private void bindReelShareCard(@NonNull VH h, Message m, Context ctx) {
        // Username
        if (h.tvReelShareUsername != null) {
            String uname = (m.reelShareUsername != null && !m.reelShareUsername.isEmpty())
                    ? "@" + m.reelShareUsername : "@callx_reel";
            h.tvReelShareUsername.setText(uname);
        }
        // Caption
        if (h.tvReelShareCaption != null) {
            if (m.reelShareCaption != null && !m.reelShareCaption.isEmpty()) {
                h.tvReelShareCaption.setText(m.reelShareCaption);
                h.tvReelShareCaption.setVisibility(View.VISIBLE);
            } else {
                h.tvReelShareCaption.setVisibility(View.GONE);
            }
        }
        // Thumbnail
        if (h.ivReelShareThumb != null) {
            String thumb = m.reelShareThumb != null ? m.reelShareThumb : "";
            if (!thumb.isEmpty()) {
                Glide.with(ctx)
                        .load(thumb)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop()
                        .placeholder(android.R.color.darker_gray)
                        .error(android.R.color.darker_gray)
                        .into(h.ivReelShareThumb);
            } else {
                h.ivReelShareThumb.setImageResource(android.R.color.darker_gray);
            }
        }
    }

    // ── Poll bind helper ──────────────────────────────────────────────────────
    private void bindPoll(@NonNull VH h, Message m, Context ctx, boolean sent) {
        if (h.llPoll == null) return;
        TextView tvQuestion = h.llPoll.findViewById(R.id.tv_poll_question);
        TextView tvSubtitle = h.llPoll.findViewById(R.id.tv_poll_subtitle);
        TextView tvBadge    = h.llPoll.findViewById(R.id.tv_poll_status_badge);
        TextView tvVotes    = h.llPoll.findViewById(R.id.tv_poll_total_votes);
        LinearLayout llOptions = h.llPoll.findViewById(R.id.ll_poll_options);

        if (tvQuestion != null && m.pollQuestion != null)
            tvQuestion.setText(m.pollQuestion);

        if (tvSubtitle != null)
            tvSubtitle.setText(Boolean.TRUE.equals(m.pollMultiChoice)
                    ? "Select all that apply" : "Select one answer");

        if (tvBadge != null) {
            if (Boolean.TRUE.equals(m.pollClosed)) {
                tvBadge.setVisibility(View.VISIBLE);
                tvBadge.setText("CLOSED");
            } else if (Boolean.TRUE.equals(m.pollAnonymous)) {
                tvBadge.setVisibility(View.VISIBLE);
                tvBadge.setText("ANONYMOUS");
            } else {
                tvBadge.setVisibility(View.GONE);
            }
        }

        // Total votes count
        int totalVotes = 0;
        if (m.pollVotes != null) {
            for (List<Integer> v : m.pollVotes.values()) totalVotes += (v != null ? 1 : 0);
        }
        if (tvVotes != null)
            tvVotes.setText(totalVotes + " vote" + (totalVotes != 1 ? "s" : ""));

        // Options — recycle existing TextViews instead of removeAllViews + re-inflate
        if (llOptions != null && m.pollOptions != null) {
            int optCount = m.pollOptions.size();
            // Reuse existing child views; add or remove only the delta
            while (llOptions.getChildCount() > optCount) {
                llOptions.removeViewAt(llOptions.getChildCount() - 1);
            }
            List<Integer> myVotes = (m.pollVotes != null && currentUid != null)
                    ? m.pollVotes.get(currentUid) : null;
            for (int idx = 0; idx < optCount; idx++) {
                String option = m.pollOptions.get(idx);
                int optVotes = 0;
                if (m.pollVotes != null) {
                    for (List<Integer> vl : m.pollVotes.values()) {
                        if (vl != null && vl.contains(idx)) optVotes++;
                    }
                }
                int pct = totalVotes > 0 ? (optVotes * 100 / totalVotes) : 0;
                boolean voted = myVotes != null && myVotes.contains(idx);

                TextView optView;
                if (idx < llOptions.getChildCount()) {
                    // Reuse
                    optView = (TextView) llOptions.getChildAt(idx);
                } else {
                    // Create new only when needed
                    optView = new TextView(ctx);
                    optView.setTextColor(0xFFFFFFFF);
                    optView.setPadding(0, 8, 0, 8);
                    llOptions.addView(optView);
                }
                optView.setText((voted ? "✓ " : "") + option + "  " + pct + "%");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Status Seen Bubble
    // ══════════════════════════════════════════════════════════════════════════
    private void bindStatusSeenBubble(@NonNull VH h, Message m, Context ctx) {
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(R.id.iv_status_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                Glide.with(ctx).load(photo)
                        .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person)
                        .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }
        View flThumb = h.itemView.findViewById(R.id.fl_status_seen_thumb);
        ImageView ivThumb = h.itemView.findViewById(R.id.iv_status_seen_thumb);
        ImageView ivEye   = h.itemView.findViewById(R.id.iv_status_seen_eye);
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
            Intent intent = new Intent(com.callx.app.utils.Constants.ACTION_OPEN_STATUS);
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
        TextView tvName = h.itemView.findViewById(R.id.tv_status_seen_name);
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName); tvName.setVisibility(View.VISIBLE);
            } else { tvName.setVisibility(View.GONE); }
        }
        TextView tvTime = h.itemView.findViewById(R.id.tv_status_seen_time);
        if (tvTime != null && m.timestamp != null)
            tvTime.setText(formatTime(m.timestamp));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Reel Seen Bubble
    // ══════════════════════════════════════════════════════════════════════════
    private void bindReelSeenBubble(@NonNull VH h, Message m, Context ctx) {
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(R.id.iv_reel_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                Glide.with(ctx).load(photo)
                        .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person).into(ivAvatar);
            } else { ivAvatar.setImageResource(R.drawable.ic_person); }
        }
        final String reelId = m.reelId;
        View.OnClickListener openReel = v -> {
            if (reelId == null || reelId.isEmpty()) return;
            Intent intent = new Intent(com.callx.app.utils.Constants.ACTION_OPEN_REEL);
            intent.putExtra("reelId", reelId);
            intent.setPackage(ctx.getPackageName());
            ctx.startActivity(intent);
        };
        View flThumb   = h.itemView.findViewById(R.id.fl_reel_seen_thumb);
        ImageView ivThumb = h.itemView.findViewById(R.id.iv_reel_seen_thumb);
        ImageView ivPlay  = h.itemView.findViewById(R.id.iv_reel_seen_play);
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
        TextView tvName = h.itemView.findViewById(R.id.tv_reel_seen_name);
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName); tvName.setVisibility(View.VISIBLE);
            } else { tvName.setVisibility(View.GONE); }
        }
        TextView tvTime = h.itemView.findViewById(R.id.tv_reel_seen_time);
        if (tvTime != null && m.timestamp != null)
            tvTime.setText(formatTime(m.timestamp));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Sender avatar — photo-cache deduplication
    // ══════════════════════════════════════════════════════════════════════════
    private final Map<String, String> photoCache        = new HashMap<>();
    private final Set<String>         photoFetchInFlight = new HashSet<>();

    private void loadSenderAvatar(Context ctx, String uid,
            de.hdodenhof.circleimageview.CircleImageView iv) {
        if (uid == null || ctx == null || iv == null) return;
        String cached = photoCache.get(uid);
        if (cached != null) {
            if (!cached.isEmpty())
                Glide.with(ctx).load(cached)
                        .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person).into(iv);
            else iv.setImageResource(R.drawable.ic_person);
            return;
        }
        iv.setImageResource(R.drawable.ic_person);
        if (photoFetchInFlight.contains(uid)) return;
        photoFetchInFlight.add(uid);
        com.callx.app.utils.FirebaseUtils.getUserRef(uid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                    photoFetchInFlight.remove(uid);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    photoCache.put(uid, photo != null ? photo : "");
                    if (photo != null && !photo.isEmpty()) {
                        try {
                            Glide.with(ctx).load(photo)
                                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                                    .placeholder(R.drawable.ic_person).into(iv);
                        } catch (Exception ignored) {}
                    }
                }
                @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                    photoFetchInFlight.remove(uid);
                    photoCache.put(uid, "");
                }
            });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Tick pill — partial bind, zero flicker
    // ══════════════════════════════════════════════════════════════════════════
    private void bindTickOnly(VH h, Message m) {
        if (h.tvStatus == null) return;
        if (h.llTickPill != null) {
            h.tvStatus.setVisibility(View.VISIBLE);
            // setTextSize(14f) removed — constant, set once when view is first bound
            switch (m.status == null ? "sent" : m.status) {
                case "read":
                    h.tvStatus.setText("\u2713\u2713 ");
                    h.tvStatus.setTextColor(
                            com.callx.app.utils.ChatThemeManager.get(h.itemView.getContext())
                                    .getTickColor(true));
                    break;
                case "delivered":
                    h.tvStatus.setText("\u2713\u2713");
                    h.tvStatus.setTextColor(
                            com.callx.app.utils.ChatThemeManager.get(h.itemView.getContext())
                                    .getTickColor(false));
                    break;
                default:
                    h.tvStatus.setText("\u2713");
                    h.tvStatus.setTextColor(
                            com.callx.app.utils.ChatThemeManager.get(h.itemView.getContext())
                                    .getTickColor(false));
                    break;
            }
        } else {
            h.tvStatus.setVisibility(View.GONE);
        }
    }

    private void bindFooter(VH h, Message m, boolean sent) {
        if (h.tvTime != null && m.timestamp != null)
            h.tvTime.setText(formatTime(m.timestamp));
        if (h.tvStarredIcon != null)
            h.tvStarredIcon.setVisibility(Boolean.TRUE.equals(m.starred) ? View.VISIBLE : View.GONE);
        bindTickOnly(h, m);
        // Disappearing message countdown — shared ExpiryTickManager handler
        com.callx.app.utils.ExpiryTickManager.get().unregister(h);
        if (h.tvExpiry != null) {
            long expiresAt = m.expiresAt != null ? m.expiresAt : 0L;
            long remaining = expiresAt - System.currentTimeMillis();
            if (expiresAt > 0 && remaining > 0) {
                h.tvExpiry.setVisibility(View.VISIBLE);
                h.tvExpiry.setText("⏳ " + formatRemaining(remaining));
                com.callx.app.utils.ExpiryTickManager.get().register(h, expiresAt,
                        new com.callx.app.utils.ExpiryTickManager.Listener() {
                    @Override public void onTick(long ms) {
                        h.tvExpiry.setText("⏳ " + formatRemaining(ms));
                    }
                    @Override public void onFinish() {
                        h.tvExpiry.setText("⏳ 0s");
                    }
                });
            } else {
                h.tvExpiry.setVisibility(View.GONE);
            }
        }
    }

    private static String formatRemaining(long ms) {
        long s = ms / 1000;
        if (s < 60)  return s + "s";
        if (s < 3600) return (s / 60) + "m";
        return (s / 3600) + "h";
    }

    private void setupLongPress(VH h, Message m, boolean sent, Context ctx) {
        h.itemView.setOnLongClickListener(v -> {
            if (multiSelectMode) {
                String id = m.id != null ? m.id : m.messageId;
                if (id != null) {
                    if (selectedMessageIds.contains(id)) selectedMessageIds.remove(id);
                    else selectedMessageIds.add(id);
                    notifyItemChanged(h.getAdapterPosition());
                    if (multiSelectListener != null)
                        multiSelectListener.onSelectionChanged(selectedMessageIds.size());
                }
            } else {
                showActionSheet(ctx, m, sent);
            }
            return true;
        });
        h.itemView.setOnClickListener(v -> {
            if (multiSelectMode) {
                String id = m.id != null ? m.id : m.messageId;
                if (id != null) {
                    if (selectedMessageIds.contains(id)) selectedMessageIds.remove(id);
                    else selectedMessageIds.add(id);
                    notifyItemChanged(h.getAdapterPosition());
                    if (multiSelectListener != null)
                        multiSelectListener.onSelectionChanged(selectedMessageIds.size());
                }
            }
        });
    }

    // Tag key for tracking selection state on itemView — avoids redundant setBackgroundResource calls
    private static final int TAG_KEY_SELECTED = 0x7F_0A_0001; // arbitrary non-conflicting int

    private void applySelectionHighlight(VH h, Message m) {
        boolean selected = m.id != null && selectedMessageIds.contains(m.id);
        if (multiSelectMode) {
            h.itemView.setActivated(selected);
            h.itemView.setAlpha(selected ? 1.0f : 0.6f);
            Boolean wasSelected = (Boolean) h.itemView.getTag(TAG_KEY_SELECTED);
            if (wasSelected == null || wasSelected != selected) {
                h.itemView.setBackgroundResource(selected
                        ? android.R.color.holo_blue_light : android.R.color.transparent);
                h.itemView.setTag(TAG_KEY_SELECTED, selected);
            }
        } else {
            if (h.itemView.isActivated()) {
                h.itemView.setActivated(false);
                h.itemView.setAlpha(1.0f);
                h.itemView.setBackgroundResource(android.R.color.transparent);
                h.itemView.setTag(TAG_KEY_SELECTED, null);
            }
        }
    }

    private void showActionSheet(Context ctx, Message m, boolean sent) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        View sv = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_message_actions, null);
        String[] emojis   = {"❤️", "👍", "😂", "😮", "😢", "🙏"};
        int[]    emojiIds = {
            R.id.emoji_heart, R.id.emoji_thumb, R.id.emoji_laugh,
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
        sv.findViewById(R.id.action_reply).setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onReply(m);
        });
        TextView editBtn = sv.findViewById(R.id.action_edit);
        if (sent && "text".equals(m.type) && !Boolean.TRUE.equals(m.deleted)) {
            editBtn.setVisibility(View.VISIBLE);
            editBtn.setOnClickListener(v -> {
                sheet.dismiss();
                if (actionListener != null) actionListener.onEdit(m);
            });
        }
        TextView copyBtn = sv.findViewById(R.id.action_copy);
        if (copyBtn != null) {
            boolean isText  = "text".equals(m.type) || m.type == null;
            boolean hasText = m.text != null && !m.text.isEmpty();
            if (isText && hasText && !Boolean.TRUE.equals(m.deleted)) {
                copyBtn.setVisibility(View.VISIBLE);
                copyBtn.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (actionListener != null) actionListener.onCopy(m);
                });
            } else { copyBtn.setVisibility(View.GONE); }
        }
        if (!Boolean.TRUE.equals(m.deleted)) {
            sv.findViewById(R.id.action_forward).setOnClickListener(v -> {
                sheet.dismiss();
                if (actionListener != null) actionListener.onForward(m);
            });
        } else { sv.findViewById(R.id.action_forward).setVisibility(View.GONE); }
        TextView starBtn = sv.findViewById(R.id.action_star);
        starBtn.setText(Boolean.TRUE.equals(m.starred) ? "\u2606  Unstar" : "\u2605  Star Message");
        starBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onStar(m);
        });
        TextView pinBtn = sv.findViewById(R.id.action_pin);
        pinBtn.setText(Boolean.TRUE.equals(m.pinned)
                ? "\uD83D\uDCCC  Unpin" : "\uD83D\uDCCC  Pin Message");
        pinBtn.setOnClickListener(v -> {
            sheet.dismiss();
            if (actionListener != null) actionListener.onPin(m);
        });
        TextView infoBtn = sv.findViewById(R.id.action_info);
        if (infoBtn != null) {
            if (sent) {
                infoBtn.setVisibility(View.VISIBLE);
                infoBtn.setOnClickListener(v -> {
                    sheet.dismiss();
                    if (actionListener != null) actionListener.onInfo(m);
                });
            } else { infoBtn.setVisibility(View.GONE); }
        }
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

    private void openInCustomTab(Context ctx, String url) {
        if (url == null || url.isEmpty()) return;
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String browserPkg = getBrowserPackage(ctx);
            androidx.browser.customtabs.CustomTabColorSchemeParams colorParams =
                new androidx.browser.customtabs.CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(0xFF1565C0).build();
            androidx.browser.customtabs.CustomTabsIntent customTab =
                new androidx.browser.customtabs.CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(colorParams)
                    .setShowTitle(true)
                    .setShareState(androidx.browser.customtabs.CustomTabsIntent.SHARE_STATE_ON)
                    .setCloseButtonPosition(
                            androidx.browser.customtabs.CustomTabsIntent.CLOSE_BUTTON_POSITION_START)
                    .build();
            if (browserPkg != null) customTab.intent.setPackage(browserPkg);
            customTab.launchUrl(ctx, uri);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }

    private String getBrowserPackage(Context ctx) {
        String[] preferred = {
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
            "org.mozilla.firefox", "com.microsoft.emmx", "com.brave.browser"
        };
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        for (String pkg : preferred) {
            try { pm.getPackageInfo(pkg, 0); return pkg; }
            catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        }
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://"));
        android.content.pm.ResolveInfo info = pm.resolveActivity(browserIntent, 0);
        if (info != null && info.activityInfo != null) return info.activityInfo.packageName;
        return null;
    }

    private void openActionSheet(Context ctx, Message m) {
        boolean sent = currentUid != null && currentUid.equals(m.senderId);
        showActionSheet(ctx, m, sent);
    }

    private void showImageActionSheet(Context ctx, Message m, String fullUrl, String thumbForViewer) {
        com.google.android.material.bottomsheet.BottomSheetDialog bsd =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"));
        String[] labels = {"🖼  View", "↗  Share", "↪  Forward", "⭐  Star", "🗑  Delete"};
        int[]    colors = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFF5252};
        boolean isOwn   = currentUid != null && currentUid.equals(m.senderId);
        float density   = ctx.getResources().getDisplayMetrics().density;
        int px20 = (int)(20 * density), px15 = (int)(15 * density);
        for (int idx = 0; idx < labels.length; idx++) {
            if (labels[idx].contains("Delete") && !isOwn) continue;
            TextView tv = new TextView(ctx);
            tv.setText(labels[idx]);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            tv.setTextColor(colors[idx]);
            tv.setPadding(px20, px15, px20, px15);
            android.graphics.drawable.ColorDrawable cnt =
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);
            tv.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#33FFFFFF")), cnt, null));
            final String lbl = labels[idx];
            tv.setOnClickListener(v -> {
                bsd.dismiss();
                if (lbl.contains("View")) {
                    openMedia(ctx, fullUrl, "image");
                } else if (lbl.contains("Share")) {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("text/plain");
                    s.putExtra(Intent.EXTRA_TEXT, fullUrl);
                    ctx.startActivity(Intent.createChooser(s, "Share via"));
                } else if (lbl.contains("Forward") && actionListener != null) {
                    actionListener.onForward(m);
                } else if (lbl.contains("Star") && actionListener != null) {
                    actionListener.onStar(m);
                } else if (lbl.contains("Delete") && actionListener != null) {
                    actionListener.onDelete(m);
                }
            });
            root.addView(tv);
            View div = new View(ctx);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dlp.setMarginStart(px20);
            div.setLayoutParams(dlp);
            div.setBackgroundColor(android.graphics.Color.parseColor("#333333"));
            root.addView(div);
        }
        root.setPadding(0, 0, 0, px15);
        bsd.setContentView(root);
        bsd.show();
    }

    private void openMedia(Context ctx, String url, String type) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent().setClassName(ctx.getPackageName(),
                "com.callx.app.activities.MediaViewerActivity");
        i.putExtra("url", url); i.putExtra("type", type);
        ctx.startActivity(i);
    }

    private static String formatVideoDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60, sec = totalSec % 60;
        return String.format(java.util.Locale.US, "%d:%02d", min, sec);
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
        java.io.File cached = MediaCache.getCached(h.itemView.getContext(), m.mediaUrl);
        if (cached != null) { playFromFile(h, cached.getAbsolutePath(), pos); return; }
        MediaCache.get(h.itemView.getContext(), m.mediaUrl, new MediaCache.Callback() {
            @Override public void onReady(java.io.File file) { playFromFile(h, file.getAbsolutePath(), pos); }
            @Override public void onError(String reason)     { playFromFile(h, m.mediaUrl, pos); }
        });
    }

    private void playFromFile(VH h, String path, int pos) {
        try {
            if (player != null) { try { player.release(); } catch (Exception ignored) {} }
            player = new MediaPlayer();
            if (!path.startsWith("http")) {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                        player.setDataSource(fis.getFD());
                    }
                } else { player.setDataSource(path); }
            } else { player.setDataSource(path); }
            player.prepareAsync();
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                h.btnPlayAudio.setImageResource(R.drawable.ic_play);
                try { mp.release(); } catch (Exception ignored) {}
                player = null; playingPos = -1;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.e("AudioPlay", "Error: " + what + " path: " + path);
                playingPos = -1; return true;
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

    // Cached normal typeface — Typeface.create() is expensive, cache once
    private static final android.graphics.Typeface TF_NORMAL =
            android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL);

    private static void applyFontStyle(TextView tv, int styleId) {
        tv.setTypeface(TF_NORMAL);
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        com.callx.app.utils.ExpiryTickManager.get().unregister(holder);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ViewHolder
    // ══════════════════════════════════════════════════════════════════════════
    static class VH extends RecyclerView.ViewHolder {

        // ── Always-present views (in root item layout) ──
        TextView     tvMessage, tvTime, tvSenderName;
        ImageView    ivImage;
        LinearLayout llReplyPreview;
        TextView     tvReplySender, tvReplyText;
        LinearLayout llReactions;
        TextView     tvReactions;
        TextView     tvEdited;
        TextView     tvPinnedLabel;
        TextView     tvForwarded;
        TextView     tvStarredIcon;
        TextView     tvStatus;
        de.hdodenhof.circleimageview.CircleImageView ivSenderAvatar;
        TextView     tvExpiry;
        ImageButton  btnQuickForward;
        LinearLayout llTickPill;
        LinearLayout llBubble;

        // ── Countdown for disappearing messages ──

        // ── ViewStub references — null after inflation (stub replaced in-place) ──
        ViewStub stubVideo;
        ViewStub stubAudio;
        ViewStub stubFile;
        ViewStub stubPoll;
        ViewStub stubLinkPreview;
        ViewStub stubReelShare;

        // ── Heavy view refs — null until the stub is inflated ──
        // Video
        FrameLayout flVideo;
        ImageView   ivVideoThumb;
        TextView    tvVideoDuration;
        // Audio
        LinearLayout llAudio;
        ImageButton  btnPlayAudio;
        com.callx.app.chat.ui.AudioWaveformView seekAudio;
        TextView     tvAudioDur;
        // File
        LinearLayout llFile;
        TextView     tvFileName, tvFileMeta;
        ImageButton  ivDownload;
        // Poll
        LinearLayout llPoll;
        // Link preview
        LinearLayout llLinkPreview;
        TextView     tvLinkTitle, tvLinkDomain, tvLinkDescription;
        ImageView    ivLinkThumb;
        // Reel share
        LinearLayout llReelShare;
        ImageView    ivReelShareThumb;
        TextView     tvReelShareUsername, tvReelShareCaption;

        VH(View v) {
            super(v);
            tvMessage       = v.findViewById(R.id.tv_message);
            tvTime          = v.findViewById(R.id.tv_time);
            tvSenderName    = v.findViewById(R.id.tv_sender_name);
            ivImage         = v.findViewById(R.id.iv_image);
            llReplyPreview  = v.findViewById(R.id.ll_reply_preview);
            tvReplySender   = v.findViewById(R.id.tv_reply_sender);
            tvReplyText     = v.findViewById(R.id.tv_reply_text);
            llReactions     = v.findViewById(R.id.ll_reactions);
            tvReactions     = v.findViewById(R.id.tv_reactions);
            tvEdited        = v.findViewById(R.id.tv_edited);
            tvPinnedLabel   = v.findViewById(R.id.tv_pinned_label);
            tvForwarded     = v.findViewById(R.id.tv_forwarded);
            tvStarredIcon   = v.findViewById(R.id.tv_starred_icon);
            tvStatus        = v.findViewById(R.id.tv_status);
            ivSenderAvatar  = v.findViewById(R.id.iv_sender_avatar);
            tvExpiry        = v.findViewById(R.id.tv_expiry);
            btnQuickForward = v.findViewById(R.id.btn_quick_forward);
            llTickPill      = v.findViewById(R.id.ll_tick_pill);
            llBubble        = v.findViewById(R.id.ll_bubble);

            // Bind ViewStub references — these are replaced when inflate() is called
            stubVideo       = v.findViewById(R.id.stub_video);
            stubAudio       = v.findViewById(R.id.stub_audio);
            stubFile        = v.findViewById(R.id.stub_file);
            stubPoll        = v.findViewById(R.id.stub_poll);
            stubLinkPreview = v.findViewById(R.id.stub_link_preview);
            stubReelShare   = v.findViewById(R.id.stub_reel_share);

            // Heavy view refs start null — populated lazily by ensureXxxInflated()
            flVideo         = null;
            ivVideoThumb    = null;
            tvVideoDuration = null;
            llAudio         = null;
            btnPlayAudio    = null;
            seekAudio       = null;
            tvAudioDur      = null;
            llFile          = null;
            tvFileName      = null;
            tvFileMeta      = null;
            ivDownload      = null;
            llPoll          = null;
            llLinkPreview   = null;
            tvLinkTitle     = null;
            tvLinkDomain    = null;
            tvLinkDescription = null;
            ivLinkThumb     = null;
        }
    }
}
