package com.callx.app.conversation;

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

    // ── Multi-select interface ────────────────────────────────────────────
    public interface MultiSelectListener {
        void onSelectionChanged(int count);  // count = selected messages count
    }

    private final List<Message> messages;
    private final String currentUid;
    private final boolean isGroup;
    private ActionListener actionListener;

    // ── Multi-select state ───────────────────────────────────────────────
    private boolean multiSelectMode = false;
    private final java.util.Set<String> selectedMessageIds = new java.util.HashSet<>();
    private MultiSelectListener multiSelectListener;

    public void setMultiSelectListener(MultiSelectListener l) { this.multiSelectListener = l; }

    public void enterMultiSelectMode(Message firstMessage) {
        multiSelectMode = true;
        selectedMessageIds.clear();
        if (firstMessage != null && firstMessage.id != null) {
            selectedMessageIds.add(firstMessage.id);
        }
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
        for (Message m : messages) {
            if (m.id != null && selectedMessageIds.contains(m.id)) result.add(m);
        }
        return result;
    }

    private static final int TYPE_SENT = 1, TYPE_RECEIVED = 2, TYPE_STATUS_SEEN = 3, TYPE_REEL_SEEN = 4;
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
        Message m = messages.get(pos);
        if ("status_seen".equals(m.type)) return TYPE_STATUS_SEEN;
        if ("reel_seen".equals(m.type))   return TYPE_REEL_SEEN;
        return currentUid.equals(m.senderId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout;
        if (viewType == TYPE_SENT)          layout = R.layout.item_message_sent;
        else if (viewType == TYPE_STATUS_SEEN) layout = R.layout.item_status_seen_bubble;
        else if (viewType == TYPE_REEL_SEEN)   layout = R.layout.item_reel_seen_bubble;
        else                                layout = R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m   = messages.get(pos);
        Context ctx = h.itemView.getContext();
        int viewType = getItemViewType(pos);

        // ── STATUS SEEN BUBBLE — special system event row ─────────────────────
        if (viewType == TYPE_STATUS_SEEN) {
            bindStatusSeenBubble(h, m, ctx);
            return;
        }

        // ── REEL SEEN BUBBLE — special system event row ───────────────────────
        if (viewType == TYPE_REEL_SEEN) {
            bindReelSeenBubble(h, m, ctx);
            return;
        }

        boolean sent = viewType == TYPE_SENT;

        h.tvMessage.setVisibility(View.GONE);
        h.ivImage.setVisibility(View.GONE);
        if (h.flVideo  != null) h.flVideo.setVisibility(View.GONE);
        if (h.llAudio  != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile   != null) h.llFile.setVisibility(View.GONE);
        if (h.tvEdited     != null) h.tvEdited.setVisibility(View.GONE);
        if (h.llLinkPreview != null) h.llLinkPreview.setVisibility(View.GONE);

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
                    android.content.Intent i = new android.content.Intent().setClassName(h.itemView.getContext(), "com.callx.app.conversation.ChatActivity");
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
            applySelectionHighlight(h, m);
            setupLongPress(h, m, sent, ctx);
            return;
        }

        String type = m.type == null ? "text" : m.type;
        // Sticker type preserve karo — image/gif override se pehle check karo
        if (!"sticker".equals(type) && !"gif".equals(type)) {
            if ("image".equals(type) || (m.imageUrl != null && !m.imageUrl.isEmpty()
                    && (m.mediaUrl == null || m.mediaUrl.isEmpty())))
                type = "image";
        }

        // ── Per-type bubble background — ChatThemeManager (runtime gradient) ──
        try {
            android.view.View llBubble = h.itemView.findViewById(R.id.ll_bubble);
            if (llBubble != null) {
                boolean hasReply = m.replyToText != null && !m.replyToText.isEmpty();
                com.callx.app.utils.ChatThemeManager
                        .get(ctx)
                        .applyBubble(llBubble, sent, type, hasReply);

            }
        } catch (Exception ignored) {}

        switch (type) {
            case "sticker":
            case "gif":
            case "image": {
                h.ivImage.setVisibility(View.VISIBLE);
                String url = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
                android.util.Log.d("ImageLoad", "Loading image/gif: " + url);
                boolean isGif = "gif".equals(m.type);
                boolean isSticker = "sticker".equals(m.type);
                // Check if already cached (only used for non-GIF/non-sticker types)
                java.io.File cached = (isGif || isSticker) ? null : MediaCache.getCached(ctx, url);
                if (isGif) {
                    // GIF: Glide ke DiskCache pe rely karo — MediaCache file se load
                    // karne par .gif extension nahi hoti, Glide decode fail karta hai.
                    Glide.with(ctx)
                            .asGif()
                            .load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.bg_circle_white)
                            .into(h.ivImage);
                } else if (isSticker) {
                    // Sticker: WebP format — asGif() mat use karo, normal load karo
                    // Glide WebP animated stickers bhi support karta hai .load() se
                    Glide.with(ctx)
                            .load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.bg_circle_white)
                            .into(h.ivImage);
                } else if (cached != null) {
                    android.util.Log.d("ImageLoad", "Image found in cache: " + cached.getAbsolutePath());
                    Glide.with(ctx).load(cached)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.bg_circle_white)
                            .into(h.ivImage);
                } else {
                    android.util.Log.d("ImageLoad", "Image NOT in cache, will download: " + url);
                    Glide.with(ctx).load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.bg_circle_white)
                            .into(h.ivImage);
                    // Background cache
                    MediaCache.get(ctx, url, new MediaCache.Callback() {
                        @Override public void onReady(java.io.File file) {
                            android.util.Log.d("ImageLoad", "Image cached: " + file.getAbsolutePath());
                        }
                        @Override public void onError(String reason) {
                            android.util.Log.w("ImageLoad", "Failed to cache image: " + reason);
                        }
                    });
                }
                final String fu = url;
                final String tu = m.thumbnailUrl;
                h.ivImage.setOnClickListener(v -> showImageActionSheet(ctx, m, fu, tu != null ? tu : fu));
                h.ivImage.setOnLongClickListener(v -> {
                    openActionSheet(ctx, m);
                    return true;
                });
                break;
            }
            case "video": {
                if (h.flVideo != null) {
                    h.flVideo.setVisibility(View.VISIBLE);

                    // Thumbnail: proper WebP generated by VideoCompressor
                    String thumb    = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                            ? m.thumbnailUrl : m.mediaUrl;
                    final String videoUrl = m.mediaUrl;

                    // Load thumbnail (fast — cached WebP ~30KB)
                    java.io.File cachedThumb = com.callx.app.utils.MediaCache.getCached(ctx, thumb);
                    if (cachedThumb != null) {
                        Glide.with(ctx).load(cachedThumb)
                                .centerCrop().into(h.ivVideoThumb);
                    } else {
                        Glide.with(ctx).load(thumb)
                                .centerCrop()
                                .placeholder(R.drawable.ic_video)
                                .into(h.ivVideoThumb);
                        com.callx.app.utils.MediaCache.get(ctx, thumb,
                                new com.callx.app.utils.MediaCache.Callback() {
                                    @Override public void onReady(java.io.File f) {}
                                    @Override public void onError(String r) {}
                                });
                    }

                    // Duration badge (e.g. "1:24")
                    if (h.tvVideoDuration != null) {
                        if (m.duration != null && m.duration > 0) {
                            h.tvVideoDuration.setVisibility(View.VISIBLE);
                            h.tvVideoDuration.setText(formatVideoDuration(m.duration));
                        } else {
                            h.tvVideoDuration.setVisibility(View.GONE);
                        }
                    }

                    // Pre-cache video in background (for instant replay)
                    java.io.File cachedVideo = com.callx.app.utils.MediaCache.getCached(ctx, videoUrl);
                    if (cachedVideo == null && videoUrl != null && !videoUrl.isEmpty()) {
                        com.callx.app.utils.MediaCache.get(ctx, videoUrl,
                                new com.callx.app.utils.MediaCache.Callback() {
                                    @Override public void onReady(java.io.File f) {}
                                    @Override public void onError(String r) {}
                                });
                    }

                    // Click → VideoPlayerActivity (full ExoPlayer)
                    h.flVideo.setOnClickListener(v -> {
                        android.content.Intent intent = new android.content.Intent()
                                .setClassName(ctx, "com.callx.app.player.VideoPlayerActivity");
                        intent.putExtra(
                                "videoUrl",
                                videoUrl);
                        intent.putExtra(
                                "thumbUrl",
                                m.thumbnailUrl);
                        intent.putExtra(
                                "durationMs",
                                m.duration != null ? m.duration.intValue() : 0);
                        ctx.startActivity(intent);
                    });
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
                    
                    // Pre-cache audio in background
                    if (m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                        java.io.File cachedAudio = MediaCache.getCached(ctx, m.mediaUrl);
                        if (cachedAudio == null) {
                            android.util.Log.d("AudioLoad", "Pre-caching audio: " + m.mediaUrl);
                            MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {
                                    android.util.Log.d("AudioLoad", "Audio cached: " + file.getAbsolutePath());
                                }
                                @Override public void onError(String reason) {
                                    android.util.Log.w("AudioLoad", "Failed to cache audio: " + reason);
                                }
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
                    h.tvFileMeta.setText(m.fileSize != null
                            ? FileUtils.humanSize(m.fileSize) : "Document");

                    // FIX: Download button — only show for RECEIVED messages
                    if (h.ivDownload != null) {
                        if (!sent && m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                            h.ivDownload.setVisibility(View.VISIBLE);
                            java.io.File alreadyCached = MediaCache.getCached(ctx, m.mediaUrl);
                            // Show download icon if not cached, open icon if cached
                            h.ivDownload.setImageResource(alreadyCached != null
                                    ? R.drawable.ic_file : R.drawable.ic_file);
                            final String fUrl = m.mediaUrl;
                            final String fFileName = fName;
                            h.ivDownload.setOnClickListener(v -> {
                                java.io.File cached2 = MediaCache.getCached(ctx, fUrl);
                                if (cached2 != null) {
                                    // Already downloaded — open directly
                                    FileUtils.openOrDownload(ctx, cached2.toURI().toString(), fFileName);
                                } else {
                                    // Download karo
                                    android.widget.Toast.makeText(ctx, "Downloading…", android.widget.Toast.LENGTH_SHORT).show();
                                    h.ivDownload.setEnabled(false);
                                    MediaCache.get(ctx, fUrl, new MediaCache.Callback() {
                                        @Override public void onReady(java.io.File file) {
                                            h.ivDownload.setEnabled(true);
                                            android.widget.Toast.makeText(ctx, "Downloaded!", android.widget.Toast.LENGTH_SHORT).show();
                                            FileUtils.openOrDownload(ctx, file.toURI().toString(), fFileName);
                                        }
                                        @Override public void onError(String reason) {
                                            h.ivDownload.setEnabled(true);
                                            android.widget.Toast.makeText(ctx, "Download failed", android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }
                            });
                        } else {
                            h.ivDownload.setVisibility(View.GONE);
                        }
                    }

                    // Pre-cache file in background
                    if (m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                        java.io.File cachedFile = MediaCache.getCached(ctx, m.mediaUrl);
                        if (cachedFile == null) {
                            android.util.Log.d("FileLoad", "Pre-caching file: " + m.mediaUrl);
                            MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {
                                    android.util.Log.d("FileLoad", "File cached: " + file.getAbsolutePath());
                                }
                                @Override public void onError(String reason) {
                                    android.util.Log.w("FileLoad", "Failed to cache file: " + reason);
                                }
                            });
                        }
                    }
                    
                    h.llFile.setOnClickListener(v -> {
                        if (m.mediaUrl == null) return;
                        // Pehle local cache check, agar cached hai to seedha kholo
                        java.io.File cached = MediaCache.getCached(ctx, m.mediaUrl);
                        if (cached != null) {
                            android.util.Log.d("FileClick", "Opening cached file: " + cached.getAbsolutePath());
                            FileUtils.openOrDownload(ctx, cached.toURI().toString(), fName);
                            return;
                        }
                        // Nahi hai to download & cache karo
                        android.util.Log.d("FileClick", "Downloading file: " + m.mediaUrl);
                        android.widget.Toast.makeText(ctx, "Downloading…", android.widget.Toast.LENGTH_SHORT).show();
                        MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                            @Override public void onReady(java.io.File file) {
                                android.util.Log.d("FileClick", "Download complete: " + file.getAbsolutePath());
                                FileUtils.openOrDownload(ctx, file.toURI().toString(), fName);
                            }
                            @Override public void onError(String reason) {
                                android.util.Log.e("FileClick", "Download failed: " + reason);
                                FileUtils.openOrDownload(ctx, m.mediaUrl, fName);
                            }
                        });
                    });
                }
                break;
            }
            default: {
                h.tvMessage.setVisibility(View.VISIBLE);
                h.tvMessage.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTextColor(sent));
                h.tvMessage.setTextSize(15f);
                // ── Font Style: sender ke selected typing style ko apply karo ──
                applyFontStyle(h.tvMessage, m.fontStyle);
                if (h.tvEdited != null && Boolean.TRUE.equals(m.edited))
                    h.tvEdited.setVisibility(View.VISIBLE);
                // ── Clickable links: URLs, phone numbers, emails ────────────
                String rawText = m.text != null ? m.text : "";
                android.text.SpannableString spanned = new android.text.SpannableString(rawText);
                android.text.util.Linkify.addLinks(spanned,
                    android.text.util.Linkify.WEB_URLS |
                    android.text.util.Linkify.PHONE_NUMBERS |
                    android.text.util.Linkify.EMAIL_ADDRESSES);
                h.tvMessage.setText(spanned);
                // Link color matching bubble theme
                int linkColor = sent ? 0xFFB3E5FC : 0xFF1565C0;
                h.tvMessage.setLinkTextColor(linkColor);
                h.tvMessage.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                h.tvMessage.setHighlightColor(0x33FFFFFF);

                // ── Link Preview Card ────────────────────────────────────
                if (h.llLinkPreview != null) {
                    String previewUrl = com.callx.app.utils.LinkPreviewFetcher.extractFirstUrl(rawText);
                    if (previewUrl != null) {
                        h.llLinkPreview.setTag(previewUrl);
                        h.llLinkPreview.setVisibility(View.INVISIBLE);
                        com.callx.app.utils.LinkPreviewFetcher.fetch(previewUrl,
                                new com.callx.app.utils.LinkPreviewFetcher.Callback() {
                            @Override public void onResult(com.callx.app.utils.LinkPreviewFetcher.Result r) {
                                if (!previewUrl.equals(h.llLinkPreview.getTag())) return;
                                h.llLinkPreview.setVisibility(View.VISIBLE);
                                if (h.tvLinkDomain != null) h.tvLinkDomain.setText(r.domain);
                                if (h.tvLinkTitle  != null) h.tvLinkTitle.setText(r.title);
                                // Description (e.g. YouTube channel name, OG description)
                                if (h.tvLinkDescription != null) {
                                    if (r.description != null && !r.description.isEmpty()) {
                                        h.tvLinkDescription.setText(r.description);
                                        h.tvLinkDescription.setVisibility(View.VISIBLE);
                                    } else {
                                        h.tvLinkDescription.setVisibility(View.GONE);
                                    }
                                }
                                if (h.ivLinkThumb  != null) {
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
                                h.llLinkPreview.setOnClickListener(v -> {
                                    openInCustomTab(ctx, r.url);
                                });
                            }
                            @Override public void onError(String url) {
                                if (!previewUrl.equals(h.llLinkPreview.getTag())) return;
                                h.llLinkPreview.setVisibility(View.GONE);
                            }
                        });
                    } else {
                        h.llLinkPreview.setVisibility(View.GONE);
                    }
                }
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
        applySelectionHighlight(h, m);
        setupLongPress(h, m, sent, ctx);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  bindStatusSeenBubble — renders the "👁 Seen your status" row.
    //
    //  Layout: item_status_seen_bubble.xml (received side only — always A's event)
    //   • Circular avatar  → iv_status_seen_avatar  (Glide + circleCrop)
    //   • Status thumbnail → iv_status_seen_thumb   (tappable → StatusViewerActivity)
    //   • "👁 Seen your status" → tv_status_seen_label  (in XML)
    //   • Sender name (optional, group-only) → tv_status_seen_name
    //   • Time → tv_status_seen_time
    //
    //  No long-press menu, no reactions, no reply — it's a system event.
    // ══════════════════════════════════════════════════════════════════════════
    private void bindStatusSeenBubble(@NonNull VH h, Message m, Context ctx) {
        // Avatar
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(com.callx.app.chat.R.id.iv_status_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                com.bumptech.glide.Glide.with(ctx)
                    .load(photo)
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(com.callx.app.chat.R.drawable.ic_person)
                    .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(com.callx.app.chat.R.drawable.ic_person);
            }
        }

        // Status thumbnail (tappable → open StatusViewerActivity)
        android.view.View flThumb =
            h.itemView.findViewById(com.callx.app.chat.R.id.fl_status_seen_thumb);
        android.widget.ImageView ivThumb =
            h.itemView.findViewById(com.callx.app.chat.R.id.iv_status_seen_thumb);
        android.widget.ImageView ivEye =
            h.itemView.findViewById(com.callx.app.chat.R.id.iv_status_seen_eye);
        if (ivThumb != null && flThumb != null) {
            String thumb = m.statusThumbUrl != null ? m.statusThumbUrl : "";
            if (!thumb.isEmpty()) {
                flThumb.setVisibility(View.VISIBLE);
                if (ivEye != null) ivEye.setVisibility(View.VISIBLE);
                com.bumptech.glide.Glide.with(ctx)
                    .load(thumb)
                    .centerCrop()
                    .placeholder(com.callx.app.chat.R.drawable.bg_skeleton_rect)
                    .into(ivThumb);
            } else {
                flThumb.setVisibility(View.GONE);
                if (ivEye != null) ivEye.setVisibility(View.GONE);
            }
        }

        // Click handler on the whole bubble → open status
        final String ownerUid  = (m.statusOwnerUid != null && !m.statusOwnerUid.isEmpty())
                                 ? m.statusOwnerUid : m.senderId;
        final String ownerName = m.statusOwnerName != null ? m.statusOwnerName
                                 : (m.senderName != null ? m.senderName : "");
        android.view.View.OnClickListener openStatus = v -> {
            if (ownerUid == null || ownerUid.isEmpty()) return;
            // Implicit intent — no compile-time dep on feature-status
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

        // Sender name (shown in group chat)
        android.widget.TextView tvName =
            h.itemView.findViewById(com.callx.app.chat.R.id.tv_status_seen_name);
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
            h.itemView.findViewById(com.callx.app.chat.R.id.tv_status_seen_time);
        if (tvTime != null && m.timestamp != null) {
            tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  bindReelSeenBubble — renders the "🎬 Watched your reel" row.
    //
    //  Layout: item_reel_seen_bubble.xml
    //   • iv_reel_seen_avatar  → Glide + circleCrop
    //   • fl_reel_seen_thumb   → FrameLayout container (tappable → opens reel)
    //   • iv_reel_seen_thumb   → reel thumbnail
    //   • iv_reel_seen_play    → play icon overlay on thumbnail
    //   • tv_reel_seen_name    → sender name (group only)
    //   • tv_reel_seen_time    → formatted timestamp
    // ══════════════════════════════════════════════════════════════════════════
    private void bindReelSeenBubble(@NonNull VH h, Message m, Context ctx) {
        // Avatar
        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            h.itemView.findViewById(com.callx.app.chat.R.id.iv_reel_seen_avatar);
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                com.bumptech.glide.Glide.with(ctx)
                    .load(photo)
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(com.callx.app.chat.R.drawable.ic_person)
                    .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(com.callx.app.chat.R.drawable.ic_person);
            }
        }

        // Click handler — reelId se reel kholo (thumb ho ya na ho, click always kaam kare)
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
        android.view.View flThumb =
            h.itemView.findViewById(com.callx.app.chat.R.id.fl_reel_seen_thumb);
        android.widget.ImageView ivThumb =
            h.itemView.findViewById(com.callx.app.chat.R.id.iv_reel_seen_thumb);
        android.widget.ImageView ivPlay =
            h.itemView.findViewById(com.callx.app.chat.R.id.iv_reel_seen_play);
        if (ivThumb != null) {
            String thumb = m.reelThumbUrl != null ? m.reelThumbUrl : "";
            if (!thumb.isEmpty()) {
                ivThumb.setVisibility(View.VISIBLE);
                if (ivPlay != null) ivPlay.setVisibility(View.VISIBLE);
                com.bumptech.glide.Glide.with(ctx)
                    .load(thumb)
                    .centerCrop()
                    .placeholder(com.callx.app.chat.R.drawable.bg_skeleton_rect)
                    .into(ivThumb);
            } else {
                ivThumb.setVisibility(View.GONE);
                if (ivPlay != null) ivPlay.setVisibility(View.GONE);
            }
        }
        // Click on thumbnail container, play icon, AND whole item view
        if (flThumb != null) flThumb.setOnClickListener(openReel);
        if (ivPlay  != null) ivPlay.setOnClickListener(openReel);
        h.itemView.setOnClickListener(openReel);

        // Sender name (group only)
        android.widget.TextView tvName =
            h.itemView.findViewById(com.callx.app.chat.R.id.tv_reel_seen_name);
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
            h.itemView.findViewById(com.callx.app.chat.R.id.tv_reel_seen_time);
        if (tvTime != null && m.timestamp != null) {
            tvTime.setText(timeFmt.format(new java.util.Date(m.timestamp)));
        }
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
                    .placeholder(R.drawable.ic_person).into(iv);
            else iv.setImageResource(R.drawable.ic_person);
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
                            .placeholder(R.drawable.ic_person).into(iv);
                    else iv.setImageResource(R.drawable.ic_person);
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
                        h.tvStatus.setTextColor(
                            com.callx.app.utils.ChatThemeManager.get(h.itemView.getContext()).getTickColor(true));
                        break;
                    case "delivered":
                        h.tvStatus.setText("\u2713\u2713");
                        h.tvStatus.setTextSize(13f);
                        h.tvStatus.setTextColor(
                            com.callx.app.utils.ChatThemeManager.get(h.itemView.getContext()).getTickColor(false));
                        break;
                    default:
                        h.tvStatus.setText("\u2713");
                        h.tvStatus.setTextSize(13f);
                        h.tvStatus.setTextColor(
                            com.callx.app.utils.ChatThemeManager.get(h.itemView.getContext()).getTickColor(false));
                        break;
                }
            } else {
                h.tvStatus.setVisibility(View.GONE);
            }
        }
    }

    private void setupLongPress(VH h, Message m, boolean sent, Context ctx) {
        h.itemView.setOnLongClickListener(v -> {
            if (!multiSelectMode) {
                // Long press pe multi-select mode start karo
                enterMultiSelectMode(m);
            } else {
                showActionSheet(ctx, m, sent);
            }
            return true;
        });
        h.itemView.setOnClickListener(v -> {
            if (multiSelectMode) {
                // Multi-select mode mein tap = toggle select
                String id = m.id;
                if (id != null) {
                    if (selectedMessageIds.contains(id)) {
                        selectedMessageIds.remove(id);
                    } else {
                        selectedMessageIds.add(id);
                    }
                    notifyItemChanged(h.getAdapterPosition());
                    if (multiSelectListener != null)
                        multiSelectListener.onSelectionChanged(selectedMessageIds.size());
                    // Agar sab deselect ho gaye toh mode exit karo
                    if (selectedMessageIds.isEmpty()) exitMultiSelectMode();
                }
            }
            // Normal mode mein click ka koi action nahi (scroll mein use hota)
        });
    }

    /** Apply or remove selection highlight on a VH */
    private void applySelectionHighlight(VH h, Message m) {
        boolean selected = m.id != null && selectedMessageIds.contains(m.id);
        if (multiSelectMode) {
            h.itemView.setActivated(selected);
            h.itemView.setAlpha(selected ? 1.0f : 0.6f);
            // Light blue tint for selected messages
            h.itemView.setBackgroundResource(selected
                    ? android.R.color.holo_blue_light
                    : android.R.color.transparent);
        } else {
            h.itemView.setActivated(false);
            h.itemView.setAlpha(1.0f);
            h.itemView.setBackgroundResource(android.R.color.transparent);
        }
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

    // ── Chrome Custom Tabs: force browser, block native app deep links ────────
    private void openInCustomTab(Context ctx, String url) {
        if (url == null || url.isEmpty()) return;
        try {
            android.net.Uri uri = android.net.Uri.parse(url);

            // Find the best browser package that supports Custom Tabs.
            // We do this manually so we can set the package explicitly —
            // that forces Chrome/browser to open instead of native apps (YouTube, Instagram etc.)
            String browserPkg = getBrowserPackage(ctx);

            androidx.browser.customtabs.CustomTabColorSchemeParams colorParams =
                new androidx.browser.customtabs.CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(0xFF1565C0)
                    .build();
            androidx.browser.customtabs.CustomTabsIntent customTab =
                new androidx.browser.customtabs.CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(colorParams)
                    .setShowTitle(true)
                    .setShareState(androidx.browser.customtabs.CustomTabsIntent.SHARE_STATE_ON)
                    .setCloseButtonPosition(androidx.browser.customtabs.CustomTabsIntent.CLOSE_BUTTON_POSITION_START)
                    .build();

            // Explicitly set browser package → Android won't redirect to native apps
            if (browserPkg != null) {
                customTab.intent.setPackage(browserPkg);
            }

            customTab.launchUrl(ctx, uri);
        } catch (Exception e) {
            // Last-resort fallback
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }

    // Returns package name of a browser that supports Custom Tabs, preferring Chrome.
    private String getBrowserPackage(Context ctx) {
        // Preferred browsers in order
        String[] preferred = {
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.brave.browser"
        };
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        for (String pkg : preferred) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg; // installed → use it
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        }
        // Fallback: find any browser that handles http
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://"));
        android.content.pm.ResolveInfo info = pm.resolveActivity(browserIntent, 0);
        if (info != null && info.activityInfo != null) {
            return info.activityInfo.packageName;
        }
        return null; // no browser found — Custom Tabs will pick default
    }

    private void openActionSheet(Context ctx, Message m) {
        boolean sent = currentUid != null && currentUid.equals(m.senderId);
        showActionSheet(ctx, m, sent);
    }

    private void showImageActionSheet(Context ctx, Message m, String fullUrl, String thumbForViewer) {
        com.google.android.material.bottomsheet.BottomSheetDialog bsd =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx);
        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"));

        String[] labels = {"🖼  View", "↗  Share", "↪  Forward", "⭐  Star", "🗑  Delete"};
        int[] colors    = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFF5252};
        boolean isOwn   = currentUid != null && currentUid.equals(m.senderId);
        float density   = ctx.getResources().getDisplayMetrics().density;
        int px20 = (int)(20 * density), px15 = (int)(15 * density);

        for (int idx = 0; idx < labels.length; idx++) {
            if (labels[idx].contains("Delete") && !isOwn) continue;
            android.widget.TextView tv = new android.widget.TextView(ctx);
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
                    android.content.Intent s = new android.content.Intent(
                            android.content.Intent.ACTION_SEND);
                    s.setType("text/plain");
                    s.putExtra(android.content.Intent.EXTRA_TEXT, fullUrl);
                    ctx.startActivity(android.content.Intent.createChooser(s, "Share via"));
                } else if (lbl.contains("Forward") && actionListener != null) {
                    actionListener.onForward(m);
                } else if (lbl.contains("Star") && actionListener != null) {
                    actionListener.onStar(m);
                } else if (lbl.contains("Delete") && actionListener != null) {
                    actionListener.onDelete(m);
                }
            });
            root.addView(tv);
            android.view.View div = new android.view.View(ctx);
            android.widget.LinearLayout.LayoutParams dlp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
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
        Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.activities.MediaViewerActivity");
        i.putExtra("url", url); i.putExtra("type", type);
        ctx.startActivity(i);
    }

    // v21: Video duration formatter  e.g. 75000ms → "1:15"
    private static String formatVideoDuration(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
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

    // ─────────────────────────────────────────────────────────────────────────
    // Font Style helper — TypingStyleManager.STYLE_* (0–19) ko TextView pe apply
    // ─────────────────────────────────────────────────────────────────────────
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
                // Unicode script text — transformation already ho chuka hai text mein.
                // Normal typeface apply karo taaki characters properly render hon.
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)); break;
            case com.callx.app.utils.TypingStyleManager.STYLE_NORMAL:
            default:
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)); break;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView     tvMessage, tvTime, tvSenderName;
        ImageView    ivImage, ivVideoThumb;
        FrameLayout  flVideo;
        android.widget.TextView tvVideoDuration; // v21: video duration badge
        LinearLayout llAudio;
        ImageButton  btnPlayAudio;
        SeekBar      seekAudio;
        TextView     tvAudioDur;
        LinearLayout llFile;
        TextView     tvFileName, tvFileMeta;
        ImageButton  ivDownload; // FIX: Download button for received file messages
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
        LinearLayout llLinkPreview;
        TextView     tvLinkTitle, tvLinkDomain, tvLinkDescription;
        ImageView    ivLinkThumb;

        VH(View v) {
            super(v);
            tvMessage    = v.findViewById(R.id.tv_message);
            tvTime       = v.findViewById(R.id.tv_time);
            tvSenderName = v.findViewById(R.id.tv_sender_name);
            ivImage      = v.findViewById(R.id.iv_image);
            flVideo      = v.findViewById(R.id.fl_video);
            ivVideoThumb = v.findViewById(R.id.iv_video_thumb);
            tvVideoDuration = v.findViewById(R.id.tv_duration); // v21
            llAudio      = v.findViewById(R.id.ll_audio);
            btnPlayAudio = v.findViewById(R.id.btn_play_pause);
            seekAudio    = v.findViewById(R.id.seek_audio);
            tvAudioDur   = v.findViewById(R.id.tv_audio_dur);
            llFile       = v.findViewById(R.id.ll_file);
            tvFileName   = v.findViewById(R.id.tv_file_name);
            tvFileMeta   = v.findViewById(R.id.tv_file_meta);
            ivDownload   = v.findViewById(R.id.btn_download); // FIX: bind download button
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
            ivSenderAvatar = v.findViewById(R.id.iv_sender_avatar);
            llLinkPreview  = v.findViewById(R.id.ll_link_preview);
            tvLinkTitle    = v.findViewById(R.id.tv_link_title);
            tvLinkDomain   = v.findViewById(R.id.tv_link_domain);
            tvLinkDescription = v.findViewById(R.id.tv_link_description);
            ivLinkThumb    = v.findViewById(R.id.iv_link_thumb);
        }
    }
}
