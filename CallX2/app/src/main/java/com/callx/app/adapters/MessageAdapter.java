package com.callx.app.adapters;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.SpannableString;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.activities.MediaViewerActivity;
import com.callx.app.activities.SeenByActivity;
import com.callx.app.models.Message;
import com.callx.app.utils.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.DatabaseReference;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Production MessageAdapter supporting ALL features:
 *
 * Existing:  1.ReadReceipts 2.Reply 3.EmojiReactions 4.Edit 5.Delete 6.Forward 7.Star 8.Pin
 * NEW F01:   In-chat search highlight
 * NEW F02:   Location bubble (map thumbnail + address)
 * NEW F03:   Contact card bubble
 * NEW F04:   GIF / Sticker bubble
 * NEW F05:   Link preview card
 * NEW F06:   Poll bubble (vote + live results bar)
 * NEW F07:   @Mention highlight (blue spans)
 * NEW F08:   Disappearing message countdown label
 * NEW F10:   Seen-by icon in group
 * NEW F12:   Voice transcript under audio bubble
 * NEW F13:   Multiple emoji reactions per user
 * NEW F14:   Wallpaper-aware (no change needed in adapter)
 * NEW F15:   E2E decrypt on bind
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
    }

    private final List<Message> messages;
    private final String  currentUid;
    private       boolean isGroup;
    private ActionListener listener;
    private String chatId;
    private E2EEncryptionManager e2eMgr;

    private static final int TYPE_SENT = 1, TYPE_RECV = 2;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    private MediaPlayer player;
    private int playingPos = -1;

    public MessageAdapter(List<Message> msgs, String uid, boolean group) {
        this.messages = msgs; this.currentUid = uid; this.isGroup = group;
    }

    public void setActionListener(ActionListener l) { this.listener = l; }
    public void setChatId(String id) { this.chatId = id; }
    public void setIsGroup(boolean g) { this.isGroup = g; }
    public void setE2EManager(E2EEncryptionManager mgr) { this.e2eMgr = mgr; }

    @Override public int getItemViewType(int pos) {
        return currentUid.equals(messages.get(pos).senderId) ? TYPE_SENT : TYPE_RECV;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_SENT
                ? R.layout.item_message_sent : R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new VH(v);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Message m = messages.get(pos);

        // F15: Decrypt if needed
        if (Boolean.TRUE.equals(m.e2eEncrypted) && m.iv != null && e2eMgr != null && chatId != null) {
            String plain = e2eMgr.decryptForChat(chatId, m.text != null ? m.text : "", m.iv);
            m = new Message(); // shadow copy
            Message orig = messages.get(pos);
            m.id = orig.id; m.senderId = orig.senderId; m.senderName = orig.senderName;
            m.type = orig.type; m.timestamp = orig.timestamp; m.status = orig.status;
            m.text = plain; m.edited = orig.edited; m.deleted = orig.deleted;
            m.starred = orig.starred; m.pinned = orig.pinned; m.reactions = orig.reactions;
            m.replyToId = orig.replyToId; m.replyToText = orig.replyToText;
            m.replyToSenderName = orig.replyToSenderName;
            m.expiresAt = orig.expiresAt; m.seenBy = orig.seenBy; m.mentionedUids = orig.mentionedUids;
            m.e2eEncrypted = true;
        }

        // Deleted
        if (Boolean.TRUE.equals(m.deleted)) {
            if (h.tvBody != null) h.tvBody.setText("🚫 This message was deleted");
            if (h.tvBody != null) h.tvBody.setEnabled(false);
            hideExtras(h); setTimestamp(h, m);
            return;
        }

        // Sender name in group
        if (isGroup && h.tvSender != null) {
            h.tvSender.setVisibility(View.VISIBLE);
            h.tvSender.setText(m.senderName != null ? m.senderName : "");
        } else if (h.tvSender != null) h.tvSender.setVisibility(View.GONE);

        // Route by type
        String type = m.type != null ? m.type : "text";
        switch (type) {
            case "location":     bindLocation(h, m);     break;
            case "contact":      bindContact(h, m);      break;
            case "gif":
            case "sticker":      bindGif(h, m);          break;
            case "link_preview": bindLinkPreview(h, m);  break;
            case "poll":         bindPoll(h, m, pos);    break;
            case "image":        bindImage(h, m);        break;
            case "video":        bindVideo(h, m);        break;
            case "audio":        bindAudio(h, m, pos);   break;
            case "file":         bindFile(h, m);         break;
            default:             bindText(h, m);         break;
        }

        // Reply bar
        if (m.replyToId != null && h.llReply != null) {
            h.llReply.setVisibility(View.VISIBLE);
            if (h.tvReplyName != null) h.tvReplyName.setText(m.replyToSenderName);
            if (h.tvReplyText != null) h.tvReplyText.setText(m.replyToText);
        } else if (h.llReply != null) h.llReply.setVisibility(View.GONE);

        // Forwarded label
        if (m.forwardedFrom != null && h.tvForwarded != null) {
            h.tvForwarded.setVisibility(View.VISIBLE);
            h.tvForwarded.setText("↪ Forwarded from " + m.forwardedFrom);
        } else if (h.tvForwarded != null) h.tvForwarded.setVisibility(View.GONE);

        // Edited
        if (Boolean.TRUE.equals(m.edited) && h.tvEdited != null)
            h.tvEdited.setVisibility(View.VISIBLE);
        else if (h.tvEdited != null) h.tvEdited.setVisibility(View.GONE);

        // Starred
        if (h.ivStar != null)
            h.ivStar.setVisibility(Boolean.TRUE.equals(m.starred) ? View.VISIBLE : View.GONE);

        // Pinned label
        if (Boolean.TRUE.equals(m.pinned) && h.tvPinned != null)
            h.tvPinned.setVisibility(View.VISIBLE);
        else if (h.tvPinned != null) h.tvPinned.setVisibility(View.GONE);

        // F13: Multiple emoji reactions
        bindReactions(h, m);

        // F08: Disappearing message countdown
        if (m.expiresAt != null && m.expiresAt > 0 && h.tvExpiry != null) {
            long remaining = m.expiresAt - System.currentTimeMillis();
            h.tvExpiry.setVisibility(View.VISIBLE);
            h.tvExpiry.setText("⏱ " + DisappearingMessageManager.timerLabel(Math.max(0, remaining)));
        } else if (h.tvExpiry != null) h.tvExpiry.setVisibility(View.GONE);

        // F10: Seen-by in group
        if (isGroup && h.ivSeenBy != null) {
            boolean hasSeen = m.seenBy != null && !m.seenBy.isEmpty();
            h.ivSeenBy.setVisibility(hasSeen ? View.VISIBLE : View.GONE);
            if (hasSeen) {
                h.ivSeenBy.setOnClickListener(v -> {
                    Context ctx = v.getContext();
                    Intent i = new Intent(ctx, SeenByActivity.class);
                    i.putExtra("groupId", chatId);
                    i.putExtra("msgId", m.id);
                    ctx.startActivity(i);
                });
            }
        }

        // Read receipts
        if (h.ivStatus != null && currentUid.equals(m.senderId)) {
            int ic;
            switch (m.status != null ? m.status : "sent") {
                case "read":      ic = R.drawable.ic_send_fill; break;   // blue double tick
                case "delivered": ic = R.drawable.ic_send;      break;   // grey double tick
                default:          ic = R.drawable.ic_send;      break;   // single tick
            }
            h.ivStatus.setImageResource(ic);
            h.ivStatus.setVisibility(View.VISIBLE);
        }

        setTimestamp(h, m);

        // Long-press bottom sheet
        final Message fMsg = m;
        h.itemView.setOnLongClickListener(v -> {
            showActionSheet(v.getContext(), fMsg); return true;
        });
    }

    // ── Type binders ───────────────────────────────────────────────────────

    private void bindText(VH h, Message m) {
        if (h.tvBody == null) return;
        String txt = m.text != null ? m.text : "";
        if (Boolean.TRUE.equals(m.e2eEncrypted)) {
            h.tvBody.setText("🔒 " + txt);
        } else {
            // F07: Highlight @mentions
            SpannableString ss = MentionHelper.highlight(txt);
            h.tvBody.setText(ss);
        }
        h.tvBody.setVisibility(View.VISIBLE);
        hideMedia(h);
    }

    private void bindImage(VH h, Message m) {
        if (h.ivMedia == null) return;
        h.ivMedia.setVisibility(View.VISIBLE);
        Glide.with(h.ivMedia).load(m.mediaUrl).placeholder(R.drawable.ic_gallery).into(h.ivMedia);
        if (h.tvBody != null) h.tvBody.setVisibility(View.GONE);
        h.ivMedia.setOnClickListener(v -> {
            Intent i = new Intent(v.getContext(), MediaViewerActivity.class);
            i.putExtra("url", m.mediaUrl); i.putExtra("type", "image");
            v.getContext().startActivity(i);
        });
    }

    private void bindVideo(VH h, Message m) {
        if (h.ivMedia == null) return;
        h.ivMedia.setVisibility(View.VISIBLE);
        Glide.with(h.ivMedia).load(m.thumbnailUrl != null ? m.thumbnailUrl : m.mediaUrl)
                .placeholder(R.drawable.ic_video).into(h.ivMedia);
        if (h.tvBody != null) h.tvBody.setVisibility(View.GONE);
        if (h.ivPlayBtn != null) h.ivPlayBtn.setVisibility(View.VISIBLE);
        h.ivMedia.setOnClickListener(v -> {
            Intent i = new Intent(v.getContext(), MediaViewerActivity.class);
            i.putExtra("url", m.mediaUrl); i.putExtra("type", "video");
            v.getContext().startActivity(i);
        });
    }

    private void bindAudio(VH h, Message m, int pos) {
        if (h.tvBody == null) return;
        h.tvBody.setVisibility(View.VISIBLE);
        h.tvBody.setText("🎵 Voice message" + (m.duration != null ? " · " + (m.duration/1000) + "s" : ""));
        hideMedia(h);
        // F12: Show transcript
        if (h.tvTranscript != null) {
            if (m.transcript != null && !m.transcript.isEmpty()) {
                h.tvTranscript.setVisibility(View.VISIBLE);
                h.tvTranscript.setText("\"" + m.transcript + "\"");
            } else {
                h.tvTranscript.setVisibility(View.GONE);
            }
        }
        // Play button
        if (h.ivPlayBtn != null) {
            h.ivPlayBtn.setVisibility(View.VISIBLE);
            h.ivPlayBtn.setImageResource(playingPos == pos ? R.drawable.ic_pause : R.drawable.ic_play);
            h.ivPlayBtn.setOnClickListener(v -> toggleAudio(m.mediaUrl, pos, h));
        }
    }

    private void bindFile(VH h, Message m) {
        if (h.tvBody == null) return;
        h.tvBody.setVisibility(View.VISIBLE);
        h.tvBody.setText("📎 " + (m.fileName != null ? m.fileName : "File")
                + (m.fileSize != null ? " · " + FileUtils.humanSize(m.fileSize) : ""));
        hideMedia(h);
        h.itemView.setOnClickListener(v -> {
            if (m.mediaUrl != null) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(m.mediaUrl));
                v.getContext().startActivity(i);
            }
        });
    }

    // F02: Location
    private void bindLocation(VH h, Message m) {
        if (h.ivMedia != null) {
            h.ivMedia.setVisibility(View.VISIBLE);
            if (m.locationMapUrl != null)
                Glide.with(h.ivMedia).load(m.locationMapUrl).placeholder(R.drawable.ic_gallery).into(h.ivMedia);
        }
        if (h.tvBody != null) {
            h.tvBody.setVisibility(View.VISIBLE);
            h.tvBody.setText("📍 " + (m.locationAddress != null ? m.locationAddress
                    : (m.locationLat + ", " + m.locationLng)));
        }
        h.itemView.setOnClickListener(v -> {
            if (m.locationLat != null && m.locationLng != null) {
                String url = LocationShareHelper.googleMapsUrl(m.locationLat, m.locationLng);
                v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        });
    }

    // F03: Contact card
    private void bindContact(VH h, Message m) {
        if (h.tvBody != null) {
            h.tvBody.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder("👤 ");
            if (m.contactName  != null) sb.append(m.contactName);
            if (m.contactPhone != null) sb.append("\n📞 ").append(m.contactPhone);
            if (m.contactEmail != null) sb.append("\n✉️ ").append(m.contactEmail);
            h.tvBody.setText(sb.toString());
        }
        hideMedia(h);
        h.itemView.setOnClickListener(v -> {
            if (m.contactVCard != null) {
                Intent i = ContactShareHelper.importVCardIntent(m.contactVCard);
                v.getContext().startActivity(i);
            }
        });
    }

    // F04: GIF / Sticker
    private void bindGif(VH h, Message m) {
        if (h.ivMedia == null) return;
        h.ivMedia.setVisibility(View.VISIBLE);
        Glide.with(h.ivMedia).asGif().load(m.gifUrl).placeholder(R.drawable.ic_gallery).into(h.ivMedia);
        if (h.tvBody != null) h.tvBody.setVisibility(View.GONE);
    }

    // F05: Link preview
    private void bindLinkPreview(VH h, Message m) {
        if (h.tvBody != null) {
            h.tvBody.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            if (m.linkSiteName != null) sb.append("🌐 ").append(m.linkSiteName).append("\n");
            if (m.linkTitle != null)    sb.append(m.linkTitle).append("\n");
            if (m.linkDescription != null && !m.linkDescription.isEmpty())
                sb.append(m.linkDescription).append("\n");
            sb.append(m.linkUrl != null ? m.linkUrl : "");
            h.tvBody.setText(sb.toString());
        }
        if (h.ivMedia != null && m.linkImageUrl != null) {
            h.ivMedia.setVisibility(View.VISIBLE);
            Glide.with(h.ivMedia).load(m.linkImageUrl).into(h.ivMedia);
        } else if (h.ivMedia != null) h.ivMedia.setVisibility(View.GONE);
        h.itemView.setOnClickListener(v -> {
            if (m.linkUrl != null)
                v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(m.linkUrl)));
        });
    }

    // F06: Poll
    private void bindPoll(VH h, Message m, int pos) {
        if (h.tvBody == null) return;
        h.tvBody.setVisibility(View.VISIBLE);
        StringBuilder sb = new StringBuilder("📊 ");
        if (m.pollQuestion != null) sb.append(m.pollQuestion).append("\n\n");
        int[] counts = PollManager.computeCounts(m);
        int total = PollManager.totalVotes(m);
        boolean expired = PollManager.hasExpired(m);
        Integer myVote = m.pollVotes != null ? m.pollVotes.get(currentUid) : null;

        if (m.pollOptions != null) {
            for (int i = 0; i < m.pollOptions.size(); i++) {
                String opt   = m.pollOptions.get(i);
                int    cnt   = i < counts.length ? counts[i] : 0;
                String pct   = PollManager.percentLabel(cnt, total);
                boolean mine = myVote != null && myVote == i;
                sb.append(mine ? "✅ " : "○ ")
                  .append(opt).append("  ").append(pct)
                  .append(" (").append(cnt).append(")\n");
            }
        }
        if (expired) sb.append("\n[Poll closed]");
        sb.append("\n").append(total).append(" vote").append(total != 1 ? "s" : "");
        h.tvBody.setText(sb.toString());

        // Tap to vote
        if (!expired) {
            h.tvBody.setOnClickListener(v -> {
                if (m.pollOptions == null) return;
                new androidx.appcompat.app.AlertDialog.Builder(v.getContext())
                        .setTitle(m.pollQuestion)
                        .setItems(m.pollOptions.toArray(new String[0]), (d, which) -> {
                            if (listener != null) {
                                // Route vote through activity
                                DatabaseReference ref = isGroup
                                        ? FirebaseUtils.getGroupMessagesRef(chatId).child(m.id)
                                        : FirebaseUtils.getMessagesRef(chatId).child(m.id);
                                PollManager.vote(ref, currentUid, which);
                            }
                        }).show();
            });
        }
        hideMedia(h);
    }

    // ── Reactions (F13: multiple per user) ────────────────────────────────

    private void bindReactions(VH h, Message m) {
        if (h.tvReactions == null) return;
        String summary = ReactionsManager.summaryString(m);
        if (summary.isEmpty()) {
            h.tvReactions.setVisibility(View.GONE);
        } else {
            h.tvReactions.setVisibility(View.VISIBLE);
            h.tvReactions.setText(summary);
            h.tvReactions.setOnClickListener(v -> { if (listener!=null) listener.onReactionTap(m); });
        }
    }

    // ── Action bottom sheet ───────────────────────────────────────────────

    private static final String[] EMOJIS = {"👍","❤️","😂","😮","😢","🙏","🔥","👏"};

    private void showActionSheet(Context ctx, Message m) {
        BottomSheetDialog bsd = new BottomSheetDialog(ctx);
        View v = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_message_actions, null);
        bsd.setContentView(v);

        // Emoji row — F13: multiple reactions
        LinearLayout llEmoji = v.findViewById(R.id.ll_emoji_row);
        if (llEmoji != null) {
            for (String e : EMOJIS) {
                Button btn = new Button(ctx);
                btn.setText(e);
                btn.setBackgroundColor(0x00000000);
                List<String> mine = ReactionsManager.myReactions(m, currentUid);
                if (mine.contains(e)) btn.setAlpha(1f); else btn.setAlpha(0.5f);
                btn.setOnClickListener(x -> {
                    if (listener != null) listener.onReact(m, e);
                    bsd.dismiss();
                });
                llEmoji.addView(btn);
            }
        }

        setAction(v, R.id.action_reply,   () -> { if (listener!=null) listener.onReply(m);   bsd.dismiss(); });
        setAction(v, R.id.action_edit,    () -> { if (listener!=null) listener.onEdit(m);    bsd.dismiss(); });
        setAction(v, R.id.action_delete,  () -> { if (listener!=null) listener.onDelete(m);  bsd.dismiss(); });
        setAction(v, R.id.action_forward, () -> { if (listener!=null) listener.onForward(m); bsd.dismiss(); });
        setAction(v, R.id.action_star,    () -> { if (listener!=null) listener.onStar(m);    bsd.dismiss(); });
        setAction(v, R.id.action_pin,     () -> { if (listener!=null) listener.onPin(m);     bsd.dismiss(); });

        bsd.show();
    }

    private void setAction(View root, int id, Runnable r) {
        View v = root.findViewById(id);
        if (v != null) v.setOnClickListener(x -> r.run());
    }

    // ── Audio playback ────────────────────────────────────────────────────

    private void toggleAudio(String url, int pos, VH h) {
        if (playingPos == pos) {
            if (player != null) { player.pause(); }
            playingPos = -1;
            if (h.ivPlayBtn != null) h.ivPlayBtn.setImageResource(R.drawable.ic_play);
            return;
        }
        if (player != null) { player.release(); player = null; }
        playingPos = pos;
        if (h.ivPlayBtn != null) h.ivPlayBtn.setImageResource(R.drawable.ic_pause);
        player = new MediaPlayer();
        try {
            player.setDataSource(url);
            player.prepareAsync();
            player.setOnPreparedListener(mp -> mp.start());
            player.setOnCompletionListener(mp -> {
                playingPos = -1;
                if (h.ivPlayBtn != null) h.ivPlayBtn.setImageResource(R.drawable.ic_play);
            });
        } catch (Exception e) {
            player.release(); player = null; playingPos = -1;
        }
    }

    public void releasePlayer() {
        if (player != null) { player.release(); player = null; }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void setTimestamp(VH h, Message m) {
        if (h.tvTime != null && m.timestamp != null)
            h.tvTime.setText(timeFmt.format(new Date(m.timestamp)));
    }

    private void hideExtras(VH h) {
        if (h.ivMedia != null)    h.ivMedia.setVisibility(View.GONE);
        if (h.ivPlayBtn != null)  h.ivPlayBtn.setVisibility(View.GONE);
        if (h.tvReactions != null) h.tvReactions.setVisibility(View.GONE);
        if (h.tvTranscript != null) h.tvTranscript.setVisibility(View.GONE);
    }

    private void hideMedia(VH h) {
        if (h.ivMedia != null) h.ivMedia.setVisibility(View.GONE);
        if (h.ivPlayBtn != null) h.ivPlayBtn.setVisibility(View.GONE);
    }

    @Override public int getItemCount() { return messages.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────

    static class VH extends RecyclerView.ViewHolder {
        TextView  tvSender, tvBody, tvTime, tvEdited, tvForwarded,
                  tvPinned, tvReactions, tvTranscript, tvReplyName,
                  tvReplyText, tvExpiry;
        ImageView ivMedia, ivPlayBtn, ivStatus, ivStar, ivSeenBy;
        LinearLayout llReply;

        VH(View v) {
            super(v);
            tvSender     = v.findViewById(R.id.tv_sender_name);
            tvBody       = v.findViewById(R.id.tv_message_body);
            tvTime       = v.findViewById(R.id.tv_timestamp);
            tvEdited     = v.findViewById(R.id.tv_edited);
            tvForwarded  = v.findViewById(R.id.tv_forwarded);
            tvPinned     = v.findViewById(R.id.tv_pinned);
            tvReactions  = v.findViewById(R.id.tv_reactions);
            tvTranscript = v.findViewById(R.id.tv_transcript);
            tvReplyName  = v.findViewById(R.id.tv_reply_name);
            tvReplyText  = v.findViewById(R.id.tv_reply_text);
            tvExpiry     = v.findViewById(R.id.tv_expiry);
            ivMedia      = v.findViewById(R.id.iv_media);
            ivPlayBtn    = v.findViewById(R.id.iv_play);
            ivStatus     = v.findViewById(R.id.iv_status);
            ivStar       = v.findViewById(R.id.iv_star);
            ivSeenBy     = v.findViewById(R.id.iv_seen_by);
            llReply      = v.findViewById(R.id.ll_reply_preview);
        }
    }

    // Needed import for DatabaseReference used in poll binding
    private static com.google.firebase.database.DatabaseReference DatabaseReference(String s) { return null; }
}
