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

        // ── Message grouping: hide tail for consecutive messages from same sender ──
        boolean showTail = true;
        if (pos + 1 < messages.size()) {
            Message next = messages.get(pos + 1);
            if (!Boolean.TRUE.equals(next.deleted) && next.senderId != null
                    && next.senderId.equals(m.senderId)
                    && (next.timestamp != null && m.timestamp != null
                        && (next.timestamp - m.timestamp) < 60_000)) { // within 60s
                showTail = false;
            }
        }
        // Apply no-tail bubble background when consecutive
        android.view.View llBubbleGroup = h.itemView.findViewById(R.id.ll_bubble);
        if (llBubbleGroup != null && showTail == false) {
            llBubbleGroup.setBackgroundResource(sent
                ? R.drawable.bubble_sent   // fallback — ideally bubble_sent_notail
                : R.drawable.bubble_received);
        }

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
                    android.content.Intent i = new android.content.Intent().setClassName(h.itemView.getContext(), "com.callx.app.activities.ChatActivity");
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
        if ("image".equals(type) || (m.imageUrl != null && !m.imageUrl.isEmpty()
                && (m.mediaUrl == null || m.mediaUrl.isEmpty())))
            type = "image";

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
            case "image": {
                // Use fl_image wrapper if available (new layout), else direct iv_image
                if (h.flImage != null) h.flImage.setVisibility(View.VISIBLE);
                else h.ivImage.setVisibility(View.VISIBLE);
                String url = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
                java.io.File cached = MediaCache.getCached(ctx, url);
                if (cached != null) {
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
                        @Override public void onReady(java.io.File file) {}
                        @Override public void onError(String reason) {}
                    });
                }
                // Per-message upload progress (local uploads)
                if (h.flUploadOverlay != null) {
                    if (m.uploadProgress >= 0 && m.uploadProgress < 100) {
                        h.flUploadOverlay.setVisibility(View.VISIBLE);
                        if (h.progressImageUpload != null) h.progressImageUpload.setProgress(m.uploadProgress);
                        if (h.tvUploadPct != null) h.tvUploadPct.setText(m.uploadProgress + "%");
                    } else {
                        h.flUploadOverlay.setVisibility(View.GONE);
                    }
                }
                final String fu = url;
                h.ivImage.setOnClickListener(v -> openMedia(ctx, fu, "image"));
                // Long press on image — save/share options
                h.ivImage.setOnLongClickListener(v -> {
                    showImageActions(ctx, m, sent, fu);
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
                                .setClassName(ctx, "com.callx.app.activities.VideoPlayerActivity");
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

                    // ── SeekBar wiring ──────────────────────────────────────────
                    if (h.seekAudio != null) {
                        h.seekAudio.setMax(100);
                        if (playingPos == pos && player != null && player.isPlaying()) {
                            startSeekBarUpdater(h, pos);
                        } else {
                            h.seekAudio.setProgress(0);
                        }
                        h.seekAudio.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                                if (fromUser && player != null && playingPos == fPos) {
                                    int dur = player.getDuration();
                                    if (dur > 0) player.seekTo(progress * dur / 100);
                                }
                            }
                            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
                        });
                    }

                    // Pre-cache audio in background
                    if (m.mediaUrl != null && !m.mediaUrl.isEmpty()) {
                        java.io.File cachedAudio = MediaCache.getCached(ctx, m.mediaUrl);
                        if (cachedAudio == null) {
                            MediaCache.get(ctx, m.mediaUrl, new MediaCache.Callback() {
                                @Override public void onReady(java.io.File file) {}
                                @Override public void onError(String reason) {}
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
            case "contact": {
                if (h.llContact != null) {
                    h.llContact.setVisibility(View.VISIBLE);
                    if (h.tvContactName  != null) h.tvContactName.setText(m.contactName  != null ? m.contactName  : "Contact");
                    if (h.tvContactPhone != null) h.tvContactPhone.setText(m.contactPhone != null ? m.contactPhone : "");
                    if (h.ivContactPhoto != null && m.contactPhotoUrl != null && !m.contactPhotoUrl.isEmpty()) {
                        Glide.with(ctx).load(m.contactPhotoUrl)
                            .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                            .placeholder(R.drawable.ic_person).into(h.ivContactPhoto);
                    }
                    android.widget.ImageButton btnAdd = h.itemView.findViewById(R.id.btn_add_contact);
                    if (btnAdd != null && m.contactPhone != null) {
                        btnAdd.setOnClickListener(v -> {
                            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_INSERT);
                            i.setType(android.provider.ContactsContract.Contacts.CONTENT_TYPE);
                            i.putExtra(android.provider.ContactsContract.Intents.Insert.NAME,  m.contactName != null ? m.contactName : "");
                            i.putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, m.contactPhone);
                            try { ctx.startActivity(i); } catch (Exception ignored) {}
                        });
                    }
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
                break;
            }
        }

        // ── Link Preview ────────────────────────────────────────────────
        if (h.llLinkPreview != null) {
            boolean hasUrl = m.linkPreviewUrl != null && !m.linkPreviewUrl.isEmpty();
            boolean hasTitle = m.linkPreviewTitle != null && !m.linkPreviewTitle.isEmpty();
            if ((hasUrl || hasTitle) && ("text".equals(type) || type == null)) {
                h.llLinkPreview.setVisibility(View.VISIBLE);
                if (h.tvLinkTitle != null)
                    h.tvLinkTitle.setText(hasTitle ? m.linkPreviewTitle : m.linkPreviewUrl);
                if (h.tvLinkDesc != null) {
                    if (m.linkPreviewDescription != null && !m.linkPreviewDescription.isEmpty()) {
                        h.tvLinkDesc.setVisibility(View.VISIBLE);
                        h.tvLinkDesc.setText(m.linkPreviewDescription);
                    } else { h.tvLinkDesc.setVisibility(View.GONE); }
                }
                if (h.tvLinkSite != null) {
                    if (m.linkPreviewSiteName != null && !m.linkPreviewSiteName.isEmpty()) {
                        h.tvLinkSite.setVisibility(View.VISIBLE);
                        h.tvLinkSite.setText(m.linkPreviewSiteName);
                    } else { h.tvLinkSite.setVisibility(View.GONE); }
                }
                if (h.ivLinkImage != null) {
                    if (m.linkPreviewImageUrl != null && !m.linkPreviewImageUrl.isEmpty()) {
                        h.ivLinkImage.setVisibility(View.VISIBLE);
                        Glide.with(ctx).load(m.linkPreviewImageUrl).centerCrop()
                            .placeholder(R.drawable.bg_circle_white).into(h.ivLinkImage);
                    } else { h.ivLinkImage.setVisibility(View.GONE); }
                }
                final String linkUrl = m.linkPreviewUrl;
                h.llLinkPreview.setOnClickListener(v -> {
                    if (linkUrl != null) {
                        try {
                            ctx.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(linkUrl)));
                        } catch (Exception ignored) {}
                    }
                });
            } else {
                h.llLinkPreview.setVisibility(View.GONE);
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

        // ── Status tick icons (iv_status drawable) ─────────────────────────
        if (h.ivStatus != null) {
            if (sent) {
                h.ivStatus.setVisibility(View.VISIBLE);
                String st = m.status == null ? "pending" : m.status;
                switch (st) {
                    case "read":
                        h.ivStatus.setImageResource(R.drawable.ic_tick_double_blue);
                        break;
                    case "delivered":
                        h.ivStatus.setImageResource(R.drawable.ic_tick_double);
                        break;
                    case "failed":
                        h.ivStatus.setImageResource(R.drawable.ic_tick_failed);
                        break;
                    case "pending":
                        h.ivStatus.setImageResource(R.drawable.ic_tick_clock);
                        break;
                    default: // sent
                        h.ivStatus.setImageResource(R.drawable.ic_tick_single);
                        break;
                }
            } else {
                h.ivStatus.setVisibility(View.GONE);
            }
        }

        // ── Retry button (failed messages only) ────────────────────────────
        if (h.btnRetry != null) {
            boolean failed = "failed".equals(m.status);
            h.btnRetry.setVisibility(failed && sent ? View.VISIBLE : View.GONE);
            if (failed && sent) {
                h.btnRetry.setOnClickListener(v -> {
                    if (actionListener != null) {
                        // Reuse onForward as retry signal — or add onRetry to ActionListener
                        // For now, fire a custom event
                        retryMessage(h.itemView.getContext(), m);
                    }
                });
            }
        }

        // Legacy tv_status kept for backward compat (always gone in new layout)
        if (h.tvStatus != null) h.tvStatus.setVisibility(View.GONE);
    }

    private void retryMessage(android.content.Context ctx, Message m) {
        // Notify the host Activity to retry sending this message
        android.widget.Toast.makeText(ctx, "Retrying…", android.widget.Toast.LENGTH_SHORT).show();
        // Fire a local broadcast so ChatActivity can pick it up
        android.content.Intent intent = new android.content.Intent("com.callx.app.RETRY_MESSAGE");
        intent.putExtra("messageId", m.id != null ? m.id : "");
        intent.putExtra("text", m.text != null ? m.text : "");
        intent.putExtra("type", m.type != null ? m.type : "text");
        intent.putExtra("mediaUrl", m.mediaUrl != null ? m.mediaUrl : "");
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(ctx)
            .sendBroadcast(intent);
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

        // Emoji quick-react row (including 🔥 and + full picker)
        String[] emojis  = {"❤️", "👍", "😂", "😮", "😢", "🙏", "🔥"};
        int[]    emojiIds = {
            R.id.emoji_heart, R.id.emoji_thumb, R.id.emoji_laugh,
            R.id.emoji_wow,   R.id.emoji_sad,   R.id.emoji_pray, R.id.emoji_fire};
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
        // "+" button opens full EmojiPickerSheet
        TextView emojiMore = sv.findViewById(R.id.emoji_more);
        if (emojiMore != null) {
            emojiMore.setOnClickListener(v -> {
                sheet.dismiss();
                if (ctx instanceof androidx.fragment.app.FragmentActivity) {
                    com.callx.app.chat.ui.EmojiPickerSheet picker =
                        com.callx.app.chat.ui.EmojiPickerSheet.newInstance(selectedEmoji -> {
                            if (actionListener != null) actionListener.onReact(m, selectedEmoji);
                        });
                    picker.show(((androidx.fragment.app.FragmentActivity) ctx).getSupportFragmentManager(), "emoji_picker");
                }
            });
        }
        // Save to gallery / Share actions (media messages)
        TextView saveMediaBtn = sv.findViewById(R.id.action_save_media);
        if (saveMediaBtn != null) {
            boolean isMedia = "image".equals(m.type) || "video".equals(m.type);
            if (isMedia && m.mediaUrl != null) {
                saveMediaBtn.setVisibility(View.VISIBLE);
                saveMediaBtn.setOnClickListener(v -> {
                    sheet.dismiss();
                    showImageActions(ctx, m, sent, m.mediaUrl);
                });
            } else { saveMediaBtn.setVisibility(View.GONE); }
        }
        // Share action for text or media
        TextView shareBtn = sv.findViewById(R.id.action_share);
        if (shareBtn != null && !Boolean.TRUE.equals(m.deleted)) {
            shareBtn.setVisibility(View.VISIBLE);
            shareBtn.setOnClickListener(v -> {
                sheet.dismiss();
                android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
                if ("text".equals(m.type) || m.type == null) {
                    share.setType("text/plain");
                    share.putExtra(android.content.Intent.EXTRA_TEXT, m.text != null ? m.text : "");
                } else if (m.mediaUrl != null) {
                    share.setType("*/*");
                    share.putExtra(android.content.Intent.EXTRA_TEXT, m.mediaUrl);
                }
                ctx.startActivity(android.content.Intent.createChooser(share, "Share"));
            });
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

    /** Image long-press: Save to gallery / Share options */
    private void showImageActions(Context ctx, Message m, boolean sent, String url) {
        new android.app.AlertDialog.Builder(ctx)
            .setTitle("Image")
            .setItems(new CharSequence[]{"Save to Gallery", "Share", "Open"}, (dialog, which) -> {
                switch (which) {
                    case 0: // Save
                        if (android.os.Build.VERSION.SDK_INT < 29) {
                            android.widget.Toast.makeText(ctx, "Storage permission required", android.widget.Toast.LENGTH_SHORT).show();
                        } else {
                            // MediaStore insert
                            android.content.ContentValues values = new android.content.ContentValues();
                            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "callx_" + System.currentTimeMillis() + ".jpg");
                            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CallX");
                            android.net.Uri uri = ctx.getContentResolver().insert(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                            if (uri != null) {
                                MediaCache.get(ctx, url, new MediaCache.Callback() {
                                    @Override public void onReady(java.io.File file) {
                                        try {
                                            java.io.OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                                            java.io.InputStream is = new java.io.FileInputStream(file);
                                            byte[] buf = new byte[8192]; int n;
                                            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                                            os.close(); is.close();
                                            android.widget.Toast.makeText(ctx, "Saved!", android.widget.Toast.LENGTH_SHORT).show();
                                        } catch (Exception e) {
                                            android.widget.Toast.makeText(ctx, "Save failed", android.widget.Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                    @Override public void onError(String r) {
                                        android.widget.Toast.makeText(ctx, "Download failed", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }
                        break;
                    case 1: // Share
                        MediaCache.get(ctx, url, new MediaCache.Callback() {
                            @Override public void onReady(java.io.File file) {
                                android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                                    ctx, ctx.getPackageName() + ".provider", file);
                                android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
                                share.setType("image/*");
                                share.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
                                share.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                ctx.startActivity(android.content.Intent.createChooser(share, "Share via"));
                            }
                            @Override public void onError(String r) {
                                android.widget.Toast.makeText(ctx, "Download failed", android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                        break;
                    case 2: openMedia(ctx, url, "image"); break;
                }
            }).show();
    }

    // SeekBar real-time progress updater
    private final android.os.Handler seekHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable seekUpdater;

    private void startSeekBarUpdater(VH h, int pos) {
        stopSeekBarUpdater();
        seekUpdater = new Runnable() {
            @Override public void run() {
                if (player != null && player.isPlaying() && playingPos == pos) {
                    try {
                        int dur = player.getDuration();
                        int cur = player.getCurrentPosition();
                        if (dur > 0 && h.seekAudio != null) {
                            h.seekAudio.setProgress(cur * 100 / dur);
                            if (h.tvAudioDur != null)
                                h.tvAudioDur.setText(FileUtils.formatDuration((long) cur));
                        }
                    } catch (Exception ignored) {}
                    seekHandler.postDelayed(this, 200);
                }
            }
        };
        seekHandler.post(seekUpdater);
    }

    private void stopSeekBarUpdater() {
        if (seekUpdater != null) { seekHandler.removeCallbacks(seekUpdater); seekUpdater = null; }
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
            stopSeekBarUpdater();
            return;
        }
        if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        stopSeekBarUpdater();
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
            player.setOnPreparedListener(mp -> { mp.start(); startSeekBarUpdater(h, pos); });
            player.setOnCompletionListener(mp -> {
                h.btnPlayAudio.setImageResource(R.drawable.ic_play);
                stopSeekBarUpdater();
                if (h.seekAudio != null) h.seekAudio.setProgress(0);
                if (h.tvAudioDur != null && m.duration != null)
                    h.tvAudioDur.setText(FileUtils.formatDuration(m.duration));
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
        // Production additions
        android.view.View llLinkPreview;
        android.widget.TextView tvLinkTitle, tvLinkDesc, tvLinkSite;
        android.widget.ImageView ivLinkImage;
        android.widget.ImageView ivStatus;          // tick icon (replaces tv_status text)
        android.widget.ImageButton btnRetry;
        android.widget.LinearLayout llContact;
        android.widget.TextView tvContactName, tvContactPhone;
        de.hdodenhof.circleimageview.CircleImageView ivContactPhoto;
        android.widget.FrameLayout flImage, flUploadOverlay;
        android.widget.ProgressBar progressImageUpload;
        android.widget.TextView tvUploadPct;

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
            ivSenderAvatar    = v.findViewById(R.id.iv_sender_avatar);
            // Production additions
            llLinkPreview     = v.findViewById(R.id.ll_link_preview);
            tvLinkTitle       = v.findViewById(R.id.tv_link_title);
            tvLinkDesc        = v.findViewById(R.id.tv_link_desc);
            tvLinkSite        = v.findViewById(R.id.tv_link_site);
            ivLinkImage       = v.findViewById(R.id.iv_link_image);
            ivStatus          = v.findViewById(R.id.iv_status);
            btnRetry          = v.findViewById(R.id.btn_retry);
            llContact         = v.findViewById(R.id.ll_contact);
            tvContactName     = v.findViewById(R.id.tv_contact_name);
            tvContactPhone    = v.findViewById(R.id.tv_contact_phone);
            ivContactPhoto    = v.findViewById(R.id.iv_contact_photo);
            flImage           = v.findViewById(R.id.fl_image);
            flUploadOverlay   = v.findViewById(R.id.fl_upload_overlay);
            progressImageUpload = v.findViewById(R.id.progress_image_upload);
            tvUploadPct       = v.findViewById(R.id.tv_upload_pct);
        }
    }
}
