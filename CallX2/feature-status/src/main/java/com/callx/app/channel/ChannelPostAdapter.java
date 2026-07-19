package com.callx.app.channel;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.format.DateUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.ChannelPost;
import com.callx.app.status.R;
import java.util.*;

/**
 * ChannelPostAdapter — WhatsApp-level complete multi-type post adapter (v3).
 *
 * Post types (8 total):
 *   TYPE_TEXT      — plain text post
 *   TYPE_IMAGE     — image + optional caption
 *   TYPE_VIDEO     — video thumbnail + play overlay + caption
 *   TYPE_LINK      — link preview card (thumbnail, title, description, domain)
 *   TYPE_POLL      — question + options with vote progress bars + expiry
 *   TYPE_AUDIO     — waveform + duration + in-app play button (MediaPlayer, no external intent)
 *   TYPE_DOCUMENT  — doc icon + name + size + type label
 *   TYPE_DELETED   — "This post was deleted" placeholder
 *
 * v3 additions:
 *   ✓ In-app audio playback (MediaPlayer, play/pause, no external intent)
 *   ✓ Audio waveform visualization (Canvas-based bar chart from audioWaveformJson)
 *   ✓ Replies count row with click → onReply
 *   ✓ "💬 Replies" as first item in long-press menu
 *   ✓ Fixed showPostOptions variable-use-before-declaration bug
 */
public class ChannelPostAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_TEXT     = 0;
    private static final int TYPE_IMAGE    = 1;
    private static final int TYPE_VIDEO    = 2;
    private static final int TYPE_LINK     = 3;
    private static final int TYPE_POLL     = 4;
    private static final int TYPE_AUDIO    = 5;
    private static final int TYPE_DOCUMENT = 6;
    private static final int TYPE_DELETED  = 7;

    // ── In-app audio player state (static = one player at a time) ─────────
    private static MediaPlayer activePlayer  = null;
    private static String      activeAudioUrl= null;

    // ── Callback interface ────────────────────────────────────────────────

    public interface PostActionListener {
        void onReact(ChannelPost post);
        void onForward(ChannelPost post);
        void onCopy(ChannelPost post);
        void onDelete(ChannelPost post);
        void onEdit(ChannelPost post);
        void onReport(ChannelPost post);
        void onVotePoll(ChannelPost post, int optionIndex);
        void onViewMedia(ChannelPost post);
        void onPinPost(ChannelPost post);
        void onReactionsDetail(ChannelPost post);
        void onViewCount(ChannelPost post);
        void onReply(ChannelPost post);
    }

    private final List<ChannelPost>  posts = new ArrayList<>();
    private final String             myUid;
    private final PostActionListener listener;
    private       String             channelOwnerUid = "";

    public ChannelPostAdapter(Context ctx, String myUid, PostActionListener listener) {
        this.myUid    = myUid;
        this.listener = listener;
    }

    public void setOwnerUid(String uid) { this.channelOwnerUid = uid != null ? uid : ""; }

    public void setPosts(List<ChannelPost> list) {
        posts.clear();
        if (list != null) posts.addAll(list);
        notifyDataSetChanged();
    }

    public void addPost(ChannelPost post) {
        posts.add(0, post);
        notifyItemInserted(0);
    }

    public List<ChannelPost> getPosts() { return new ArrayList<>(posts); }

    public long getOldestTimestamp() {
        if (posts.isEmpty()) return 0;
        long oldest = Long.MAX_VALUE;
        for (ChannelPost p : posts) if (p.timestamp > 0 && p.timestamp < oldest) oldest = p.timestamp;
        return oldest == Long.MAX_VALUE ? 0 : oldest;
    }

    // ── Item type resolution ──────────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        ChannelPost p = posts.get(position);
        if (p.isDeleted) return TYPE_DELETED;
        if (p.type == null) return TYPE_TEXT;
        switch (p.type) {
            case "image":    return TYPE_IMAGE;
            case "video":    return TYPE_VIDEO;
            case "link":     return TYPE_LINK;
            case "poll":     return TYPE_POLL;
            case "audio":    return TYPE_AUDIO;
            case "document": return TYPE_DOCUMENT;
            default:         return TYPE_TEXT;
        }
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_IMAGE:
            case TYPE_VIDEO:    return new MediaVH(inf.inflate(R.layout.item_channel_post_media, parent, false));
            case TYPE_LINK:     return new LinkVH(inf.inflate(R.layout.item_channel_post_link, parent, false));
            case TYPE_POLL:     return new PollVH(inf.inflate(R.layout.item_channel_post_poll, parent, false));
            case TYPE_AUDIO:    return new AudioVH(inf.inflate(R.layout.item_channel_post_audio, parent, false));
            case TYPE_DOCUMENT: return new DocumentVH(inf.inflate(R.layout.item_channel_post_document, parent, false));
            case TYPE_DELETED:  return new DeletedVH(inf.inflate(R.layout.item_channel_post_deleted, parent, false));
            default:            return new TextVH(inf.inflate(R.layout.item_channel_post, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        ChannelPost post = posts.get(pos);
        int type = getItemViewType(pos);
        if (type == TYPE_DELETED) { bindDeletedHeader((DeletedVH) holder, post); return; }

        boolean isAdmin = myUid != null &&
            (myUid.equals(channelOwnerUid) || myUid.equals(post.authorUid));

        switch (type) {
            case TYPE_IMAGE:
            case TYPE_VIDEO:    bindMedia((MediaVH) holder, post, isAdmin);    break;
            case TYPE_LINK:     bindLink((LinkVH) holder, post, isAdmin);      break;
            case TYPE_POLL:     bindPoll((PollVH) holder, post, isAdmin);      break;
            case TYPE_AUDIO:    bindAudio((AudioVH) holder, post, isAdmin);    break;
            case TYPE_DOCUMENT: bindDocument((DocumentVH) holder, post, isAdmin); break;
            default:            bindText((TextVH) holder, post, isAdmin);
        }

        if (listener != null) listener.onViewCount(post);
    }

    // ── Deleted ───────────────────────────────────────────────────────────

    private void bindDeletedHeader(DeletedVH h, ChannelPost post) {
        if (h.tvTime != null && post.timestamp > 0) {
            h.tvTime.setText(DateUtils.getRelativeTimeSpanString(post.timestamp,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        }
    }

    // ── TEXT ──────────────────────────────────────────────────────────────

    private void bindText(TextVH h, ChannelPost post, boolean isAdmin) {
        if (h.tvText != null) {
            h.tvText.setVisibility(post.text != null && !post.text.isEmpty() ? View.VISIBLE : View.GONE);
            if (post.text != null) h.tvText.setText(post.text);
        }
        bindHeader(h.ivAuthorIcon, h.tvAuthorName, h.tvPinnedBadge, post);
        bindCommonFooter(h.tvTime, h.tvReactions, h.tvViews, h.tvForwards,
                         h.btnReact, h.btnForward, post, isAdmin, h.itemView);
    }

    // ── MEDIA (image / video) ─────────────────────────────────────────────

    private void bindMedia(MediaVH h, ChannelPost post, boolean isAdmin) {
        Context ctx = h.itemView.getContext();
        String thumb = post.thumbnailUrl != null && !post.thumbnailUrl.isEmpty()
                ? post.thumbnailUrl : post.mediaUrl;
        if (thumb != null && !thumb.isEmpty() && h.ivMedia != null)
            Glide.with(ctx).load(thumb).centerCrop().override(800, 600).into(h.ivMedia);
        if (h.ivPlayOverlay != null)
            h.ivPlayOverlay.setVisibility("video".equals(post.type) ? View.VISIBLE : View.GONE);
        if (h.ivMedia != null)
            h.ivMedia.setOnClickListener(v -> { if (listener != null) listener.onViewMedia(post); });
        if (h.tvCaption != null) {
            h.tvCaption.setVisibility(post.text != null && !post.text.isEmpty() ? View.VISIBLE : View.GONE);
            if (post.text != null) h.tvCaption.setText(post.text);
        }
        bindHeader(h.ivAuthorIcon, h.tvAuthorName, h.tvPinnedBadge, post);
        bindCommonFooter(h.tvTime, h.tvReactions, h.tvViews, h.tvForwards,
                         h.btnReact, h.btnForward, post, isAdmin, h.itemView);
    }

    // ── LINK ──────────────────────────────────────────────────────────────

    private void bindLink(LinkVH h, ChannelPost post, boolean isAdmin) {
        Context ctx = h.itemView.getContext();
        if (h.tvLinkTitle != null)
            h.tvLinkTitle.setText(post.linkTitle != null && !post.linkTitle.isEmpty()
                ? post.linkTitle : post.linkUrl);
        if (h.tvLinkDesc != null) {
            boolean hasDesc = post.linkDescription != null && !post.linkDescription.isEmpty();
            h.tvLinkDesc.setVisibility(hasDesc ? View.VISIBLE : View.GONE);
            if (hasDesc) h.tvLinkDesc.setText(post.linkDescription);
        }
        String domain = post.linkDomain != null && !post.linkDomain.isEmpty()
            ? post.linkDomain : extractDomain(post.linkUrl);
        if (h.tvLinkDomain != null) h.tvLinkDomain.setText(domain);
        if (h.ivLinkThumb != null) {
            boolean hasThumb = post.linkImageUrl != null && !post.linkImageUrl.isEmpty();
            h.ivLinkThumb.setVisibility(hasThumb ? View.VISIBLE : View.GONE);
            if (hasThumb)
                Glide.with(ctx).load(post.linkImageUrl).centerCrop().override(400, 200).into(h.ivLinkThumb);
        }
        if (h.layoutLinkCard != null) {
            h.layoutLinkCard.setOnClickListener(v -> {
                if (post.linkUrl != null) {
                    try { ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(post.linkUrl))); }
                    catch (Exception ignored) {}
                }
            });
        }
        if (h.tvCaption != null) {
            h.tvCaption.setVisibility(post.text != null && !post.text.isEmpty() ? View.VISIBLE : View.GONE);
            if (post.text != null) h.tvCaption.setText(post.text);
        }
        bindHeader(h.ivAuthorIcon, h.tvAuthorName, h.tvPinnedBadge, post);
        bindCommonFooter(h.tvTime, h.tvReactions, h.tvViews, h.tvForwards,
                         h.btnReact, h.btnForward, post, isAdmin, h.itemView);
    }

    // ── POLL ──────────────────────────────────────────────────────────────

    private void bindPoll(PollVH h, ChannelPost post, boolean isAdmin) {
        if (h.tvPollQuestion != null)
            h.tvPollQuestion.setText(post.pollQuestion != null ? post.pollQuestion : "Poll");
        if (h.tvMultiSelect != null)
            h.tvMultiSelect.setVisibility(post.pollMultiSelect ? View.VISIBLE : View.GONE);
        if (h.tvPollExpiry != null) {
            if (post.pollExpiresAt > 0) {
                long now = System.currentTimeMillis();
                if (now >= post.pollExpiresAt) {
                    h.tvPollExpiry.setText("Voting closed");
                    h.tvPollExpiry.setVisibility(View.VISIBLE);
                } else {
                    h.tvPollExpiry.setText("Closes in " + formatRemaining(post.pollExpiresAt - now));
                    h.tvPollExpiry.setVisibility(View.VISIBLE);
                }
            } else {
                h.tvPollExpiry.setVisibility(View.GONE);
            }
        }

        if (h.layoutPollOptions != null) h.layoutPollOptions.removeAllViews();

        int totalVotes   = post.getTotalVotes();
        Long myVoteLong  = post.pollVotes != null && myUid != null ? post.pollVotes.get(myUid) : null;
        boolean hasVoted = myVoteLong != null;
        boolean votingClosed = post.pollExpiresAt > 0 && System.currentTimeMillis() >= post.pollExpiresAt;

        // Multi-select: track which options were selected (stored as bitmask or separate keys)
        // For multi-select we allow clicking multiple options; for single-select one vote clears others.
        if (post.pollOptions != null && h.layoutPollOptions != null) {
            for (int i = 0; i < post.pollOptions.size(); i++) {
                final int idx = i;
                String opt = post.pollOptions.get(i);
                View optView = LayoutInflater.from(h.itemView.getContext())
                    .inflate(R.layout.item_poll_option, h.layoutPollOptions, false);

                TextView    tvOpt  = optView.findViewById(R.id.tv_poll_option_text);
                ProgressBar pb     = optView.findViewById(R.id.pb_poll_option);
                TextView    tvPct  = optView.findViewById(R.id.tv_poll_option_percent);
                ImageView   ivCheck= optView.findViewById(R.id.iv_poll_option_check);
                CheckBox    cbOpt  = optView.findViewById(R.id.cb_poll_option); // multi-select

                if (tvOpt != null) tvOpt.setText(opt);

                // Show results if voted or closed or admin
                if (hasVoted || votingClosed || isAdmin) {
                    int votes = post.getVotesForOption(i);
                    int pct   = totalVotes > 0 ? (int)(votes * 100.0 / totalVotes) : 0;
                    if (pb    != null) { pb.setVisibility(View.VISIBLE); pb.setProgress(pct); }
                    if (tvPct != null) { tvPct.setVisibility(View.VISIBLE); tvPct.setText(pct + "%"); }
                    boolean myChoice = myVoteLong != null && myVoteLong == (long) i;
                    if (ivCheck != null) ivCheck.setVisibility(myChoice ? View.VISIBLE : View.GONE);
                    if (cbOpt  != null) { cbOpt.setChecked(myChoice); cbOpt.setEnabled(false); }
                    optView.setEnabled(false);
                } else {
                    if (pb    != null) pb.setVisibility(View.GONE);
                    if (tvPct != null) tvPct.setVisibility(View.GONE);
                    if (ivCheck!=null) ivCheck.setVisibility(View.GONE);
                    if (cbOpt != null) {
                        cbOpt.setVisibility(post.pollMultiSelect ? View.VISIBLE : View.GONE);
                        cbOpt.setChecked(false);
                    }
                    optView.setOnClickListener(v -> {
                        if (listener != null) listener.onVotePoll(post, idx);
                    });
                }
                h.layoutPollOptions.addView(optView);
            }
        }

        if (h.tvVoteCount != null)
            h.tvVoteCount.setText(totalVotes + " vote" + (totalVotes == 1 ? "" : "s"));

        bindHeader(h.ivAuthorIcon, h.tvAuthorName, h.tvPinnedBadge, post);
        bindCommonFooter(h.tvTime, h.tvReactions, null, null,
                         h.btnReact, h.btnForward, post, isAdmin, h.itemView);
    }

    // ── AUDIO ─────────────────────────────────────────────────────────────

    private void bindAudio(AudioVH h, ChannelPost post, boolean isAdmin) {
        if (h.tvDuration != null) h.tvDuration.setText(formatDuration(post.audioDurationMs));
        if (h.tvCaption != null) {
            h.tvCaption.setVisibility(post.text != null && !post.text.isEmpty() ? View.VISIBLE : View.GONE);
            if (post.text != null) h.tvCaption.setText(post.text);
        }

        // ── In-app audio player ───────────────────────────────────────────
        if (h.btnPlayAudio != null) {
            // Set initial icon based on whether this post is the active one
            boolean isActiveAndPlaying = post.audioUrl != null
                    && post.audioUrl.equals(activeAudioUrl)
                    && activePlayer != null && activePlayer.isPlaying();
            h.btnPlayAudio.setImageResource(isActiveAndPlaying
                    ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play);
            h.btnPlayAudio.setOnClickListener(v ->
                    toggleAudioPlayback(post.audioUrl, h.btnPlayAudio, h.itemView.getContext()));
        }

        // ── Waveform visualization ────────────────────────────────────────
        if (h.waveformView != null) renderWaveform(h.waveformView, post.audioWaveformJson);

        bindHeader(h.ivAuthorIcon, h.tvAuthorName, h.tvPinnedBadge, post);
        bindCommonFooter(h.tvTime, h.tvReactions, h.tvViews, h.tvForwards,
                         h.btnReact, h.btnForward, post, isAdmin, h.itemView);
    }

    /** Toggle play/pause for in-app audio. Only one track plays at a time. */
    private void toggleAudioPlayback(String url, ImageButton btn, Context ctx) {
        if (url == null || url.isEmpty()) return;

        if (activePlayer != null && url.equals(activeAudioUrl)) {
            // Same track — toggle play/pause
            if (activePlayer.isPlaying()) {
                activePlayer.pause();
                btn.setImageResource(android.R.drawable.ic_media_play);
            } else {
                activePlayer.start();
                btn.setImageResource(android.R.drawable.ic_media_pause);
            }
            return;
        }

        // Different track — stop current and start new
        stopActivePlayer();

        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(url);
            activePlayer   = mp;
            activeAudioUrl = url;
            btn.setImageResource(android.R.drawable.ic_media_pause); // optimistic

            mp.setOnPreparedListener(player -> {
                player.start();
                btn.setImageResource(android.R.drawable.ic_media_pause);
            });
            mp.setOnCompletionListener(player -> {
                btn.setImageResource(android.R.drawable.ic_media_play);
                activePlayer   = null;
                activeAudioUrl = null;
                player.release();
            });
            mp.setOnErrorListener((player, what, extra) -> {
                btn.setImageResource(android.R.drawable.ic_media_play);
                Toast.makeText(ctx, "Playback error. Try again.", Toast.LENGTH_SHORT).show();
                activePlayer   = null;
                activeAudioUrl = null;
                player.release();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(ctx, "Cannot play audio: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            activePlayer   = null;
            activeAudioUrl = null;
            mp.release();
        }
    }

    private static void stopActivePlayer() {
        if (activePlayer != null) {
            try {
                if (activePlayer.isPlaying()) activePlayer.stop();
                activePlayer.release();
            } catch (Exception ignored) {}
            activePlayer   = null;
            activeAudioUrl = null;
        }
    }

    /** Draw audio waveform bars using amplitude samples from JSON. */
    private void renderWaveform(View waveView, String waveformJson) {
        final int[] samples = parseWaveformJson(waveformJson);
        waveView.setBackground(new Drawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xFF25D366); // WhatsApp green
                p.setStrokeWidth(6f);
                p.setStrokeCap(Paint.Cap.ROUND);

                int w  = getBounds().width();
                int h  = getBounds().height();
                int n  = samples.length;
                float step = (float) w / n;

                for (int i = 0; i < n; i++) {
                    float barH  = (samples[i] / 100f) * h * 0.85f;
                    float x     = i * step + step / 2f;
                    float top   = h / 2f - barH / 2f;
                    float bot   = h / 2f + barH / 2f;
                    canvas.drawLine(x, top, x, bot, p);
                }
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(@Nullable ColorFilter cf) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        });
        waveView.invalidate();
    }

    private int[] parseWaveformJson(String json) {
        // Default waveform if none provided
        int[] defaultSamples = { 40, 60, 35, 80, 55, 70, 45, 90, 50, 65,
                                  30, 75, 60, 40, 85, 55, 70, 35, 65, 50 };
        if (json == null || json.isEmpty()) return defaultSamples;
        try {
            String cleaned = json.trim().replaceAll("[\\[\\]\\s]", "");
            String[] parts = cleaned.split(",");
            int[] result = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Math.max(5, Math.min(100, Integer.parseInt(parts[i].trim())));
            }
            return result.length > 0 ? result : defaultSamples;
        } catch (Exception e) {
            return defaultSamples;
        }
    }

    // ── DOCUMENT ─────────────────────────────────────────────────────────

    private void bindDocument(DocumentVH h, ChannelPost post, boolean isAdmin) {
        if (h.tvDocName != null) h.tvDocName.setText(post.documentName != null ? post.documentName : "Document");
        if (h.tvDocSize != null) h.tvDocSize.setText(formatFileSize(post.documentSizeBytes));
        if (h.tvDocType != null) h.tvDocType.setText(mimeToLabel(post.documentMimeType));
        if (h.layoutDocCard != null) {
            h.layoutDocCard.setOnClickListener(v -> {
                if (post.documentUrl != null) {
                    try { h.itemView.getContext().startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse(post.documentUrl))); }
                    catch (Exception ignored) {}
                }
            });
        }
        if (h.tvCaption != null) {
            h.tvCaption.setVisibility(post.text != null && !post.text.isEmpty() ? View.VISIBLE : View.GONE);
            if (post.text != null) h.tvCaption.setText(post.text);
        }
        bindHeader(h.ivAuthorIcon, h.tvAuthorName, h.tvPinnedBadge, post);
        bindCommonFooter(h.tvTime, h.tvReactions, h.tvViews, h.tvForwards,
                         h.btnReact, h.btnForward, post, isAdmin, h.itemView);
    }

    // ── Common header (author + pinned badge) ─────────────────────────────

    private void bindHeader(android.widget.ImageView ivAuthorIcon,
                             TextView tvAuthorName,
                             TextView tvPinnedBadge,
                             ChannelPost post) {
        if (tvPinnedBadge != null)
            tvPinnedBadge.setVisibility(post.isPinned ? View.VISIBLE : View.GONE);
        if (ivAuthorIcon != null) {
            if (post.authorIconUrl != null && !post.authorIconUrl.isEmpty()) {
                ivAuthorIcon.setVisibility(View.VISIBLE);
                Glide.with(ivAuthorIcon.getContext()).load(post.authorIconUrl)
                    .circleCrop().override(32, 32).into(ivAuthorIcon);
            } else {
                ivAuthorIcon.setVisibility(View.GONE);
            }
        }
    }

    // ── Common footer ──────────────────────────────────────────────────────

    private void bindCommonFooter(TextView tvTime, TextView tvReactions,
                                   TextView tvViews, TextView tvForwards,
                                   ImageButton btnReact, ImageButton btnForward,
                                   ChannelPost post, boolean isAdmin, View root) {
        // Timestamp + edited
        if (tvTime != null && post.timestamp > 0) {
            CharSequence rel = DateUtils.getRelativeTimeSpanString(post.timestamp,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            String timeStr = rel.toString();
            if (post.wasEdited()) timeStr += " · edited";
            tvTime.setText(timeStr);
        }

        // Reactions summary
        if (tvReactions != null) {
            if (post.reactions != null && !post.reactions.isEmpty()) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (String emoji : post.reactions.values()) counts.merge(emoji, 1, Integer::sum);
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    sb.append(e.getKey());
                    if (e.getValue() > 1) sb.append(" ").append(e.getValue());
                    sb.append("  ");
                }
                tvReactions.setText(sb.toString().trim());
                tvReactions.setVisibility(View.VISIBLE);
                tvReactions.setOnClickListener(v -> {
                    if (listener != null) listener.onReactionsDetail(post);
                });
            } else {
                tvReactions.setVisibility(View.GONE);
                tvReactions.setOnClickListener(null);
            }
        }

        // View count
        if (tvViews != null) {
            tvViews.setVisibility(post.viewCount > 0 ? View.VISIBLE : View.GONE);
            tvViews.setText(post.viewCount >= 1000
                ? String.format("%.1fK views", post.viewCount / 1000.0)
                : post.viewCount + " views");
        }

        // Forward count
        if (tvForwards != null) {
            tvForwards.setVisibility(post.forwardCount > 0 ? View.VISIBLE : View.GONE);
            tvForwards.setText(String.valueOf(post.forwardCount));
        }

        // Reply count — tappable → opens ChannelReplyActivity
        TextView tvReplyCount = root.findViewById(R.id.tv_post_replies);
        if (tvReplyCount != null) {
            if (post.replyCount > 0) {
                tvReplyCount.setVisibility(View.VISIBLE);
                tvReplyCount.setText(post.replyCount + (post.replyCount == 1 ? " reply" : " replies"));
                tvReplyCount.setOnClickListener(v -> { if (listener != null) listener.onReply(post); });
            } else {
                tvReplyCount.setVisibility(View.GONE);
                tvReplyCount.setOnClickListener(null);
            }
        }

        // React button
        if (btnReact != null) {
            btnReact.setVisibility(post.allowReactions ? View.VISIBLE : View.GONE);
            boolean reacted = post.reactions != null && post.reactions.containsKey(myUid);
            btnReact.setAlpha(reacted ? 1.0f : 0.55f);
            btnReact.setOnClickListener(v -> { if (listener != null) listener.onReact(post); });
        }

        // Forward button
        if (btnForward != null) {
            btnForward.setVisibility(post.allowForward ? View.VISIBLE : View.GONE);
            btnForward.setOnClickListener(v -> { if (listener != null) listener.onForward(post); });
        }

        // Long-press → contextual actions menu
        root.setOnLongClickListener(v -> {
            showPostOptions(post, isAdmin, root.getContext());
            return true;
        });
    }

    // ── Post options dialog ───────────────────────────────────────────────

    private void showPostOptions(ChannelPost post, boolean isAdmin, Context ctx) {
        List<String> opts = new ArrayList<>();

        // Replies — always first
        opts.add("💬 Replies");

        // Reactions detail
        boolean hasReactions = post.reactions != null && !post.reactions.isEmpty();
        if (hasReactions) opts.add("👥 See reactions");

        // React
        if (post.allowReactions) {
            boolean alreadyReacted = post.reactions != null && post.reactions.containsKey(myUid);
            opts.add(alreadyReacted ? "😊 Change reaction" : "😊 React");
        }

        // Forward
        if (post.allowForward) opts.add("↪ Forward");

        // Copy text
        if (post.text != null && !post.text.isEmpty()) opts.add("📋 Copy text");

        // Admin-only
        if (isAdmin) opts.add(post.isPinned ? "📌 Unpin post" : "📌 Pin post");
        if (isAdmin && "text".equals(post.type)) opts.add("✏️ Edit post");
        if (isAdmin) opts.add("🗑 Delete post");

        // Report
        opts.add("🚩 Report");

        String[] arr = opts.toArray(new String[0]);
        new AlertDialog.Builder(ctx)
            .setItems(arr, (d, which) -> {
                if (listener == null) return;
                String choice = arr[which];
                if      (choice.contains("Replies"))                  listener.onReply(post);
                else if (choice.contains("See reactions"))            listener.onReactionsDetail(post);
                else if (choice.contains("React") || choice.contains("Change reaction"))
                                                                       listener.onReact(post);
                else if (choice.contains("Forward"))                  listener.onForward(post);
                else if (choice.contains("Copy"))                     listener.onCopy(post);
                else if (choice.contains("Pin") || choice.contains("Unpin"))
                                                                       listener.onPinPost(post);
                else if (choice.contains("Edit"))                     listener.onEdit(post);
                else if (choice.contains("Delete"))                   listener.onDelete(post);
                else if (choice.contains("Report"))                   listener.onReport(post);
            }).show();
    }

    @Override public int getItemCount() { return posts.size(); }

    // ── ViewHolders ───────────────────────────────────────────────────────

    static class TextVH extends RecyclerView.ViewHolder {
        android.widget.ImageView ivAuthorIcon;
        TextView    tvAuthorName, tvPinnedBadge;
        TextView    tvText, tvTime, tvReactions, tvViews, tvForwards;
        ImageButton btnReact, btnForward;
        TextVH(View v) {
            super(v);
            ivAuthorIcon  = v.findViewById(R.id.iv_post_author_icon);
            tvAuthorName  = v.findViewById(R.id.tv_post_author_name);
            tvPinnedBadge = v.findViewById(R.id.tv_post_pinned_badge);
            tvText        = v.findViewById(R.id.tv_post_text);
            tvTime        = v.findViewById(R.id.tv_post_time);
            tvReactions   = v.findViewById(R.id.tv_post_reactions);
            tvViews       = v.findViewById(R.id.tv_post_views);
            tvForwards    = v.findViewById(R.id.tv_post_forwards);
            btnReact      = v.findViewById(R.id.btn_post_react);
            btnForward    = v.findViewById(R.id.btn_post_forward);
        }
    }

    static class MediaVH extends RecyclerView.ViewHolder {
        android.widget.ImageView ivAuthorIcon;
        TextView    tvAuthorName, tvPinnedBadge;
        ImageView   ivMedia, ivPlayOverlay;
        TextView    tvCaption, tvTime, tvReactions, tvViews, tvForwards;
        ImageButton btnReact, btnForward;
        MediaVH(View v) {
            super(v);
            ivAuthorIcon  = v.findViewById(R.id.iv_post_author_icon);
            tvAuthorName  = v.findViewById(R.id.tv_post_author_name);
            tvPinnedBadge = v.findViewById(R.id.tv_post_pinned_badge);
            ivMedia       = v.findViewById(R.id.iv_post_media);
            ivPlayOverlay = v.findViewById(R.id.iv_post_play);
            tvCaption     = v.findViewById(R.id.tv_post_caption);
            tvTime        = v.findViewById(R.id.tv_post_time);
            tvReactions   = v.findViewById(R.id.tv_post_reactions);
            tvViews       = v.findViewById(R.id.tv_post_views);
            tvForwards    = v.findViewById(R.id.tv_post_forwards);
            btnReact      = v.findViewById(R.id.btn_post_react);
            btnForward    = v.findViewById(R.id.btn_post_forward);
        }
    }

    static class LinkVH extends RecyclerView.ViewHolder {
        android.widget.ImageView ivAuthorIcon;
        TextView    tvAuthorName, tvPinnedBadge;
        View        layoutLinkCard;
        ImageView   ivLinkThumb;
        TextView    tvLinkTitle, tvLinkDesc, tvLinkDomain, tvCaption;
        TextView    tvTime, tvReactions, tvViews, tvForwards;
        ImageButton btnReact, btnForward;
        LinkVH(View v) {
            super(v);
            ivAuthorIcon  = v.findViewById(R.id.iv_post_author_icon);
            tvAuthorName  = v.findViewById(R.id.tv_post_author_name);
            tvPinnedBadge = v.findViewById(R.id.tv_post_pinned_badge);
            layoutLinkCard= v.findViewById(R.id.layout_link_card);
            ivLinkThumb   = v.findViewById(R.id.iv_link_thumb);
            tvLinkTitle   = v.findViewById(R.id.tv_link_title);
            tvLinkDesc    = v.findViewById(R.id.tv_link_description);
            tvLinkDomain  = v.findViewById(R.id.tv_link_domain);
            tvCaption     = v.findViewById(R.id.tv_post_caption);
            tvTime        = v.findViewById(R.id.tv_post_time);
            tvReactions   = v.findViewById(R.id.tv_post_reactions);
            tvViews       = v.findViewById(R.id.tv_post_views);
            tvForwards    = v.findViewById(R.id.tv_post_forwards);
            btnReact      = v.findViewById(R.id.btn_post_react);
            btnForward    = v.findViewById(R.id.btn_post_forward);
        }
    }

    static class PollVH extends RecyclerView.ViewHolder {
        android.widget.ImageView ivAuthorIcon;
        TextView      tvAuthorName, tvPinnedBadge;
        TextView      tvPollQuestion, tvVoteCount, tvMultiSelect, tvPollExpiry;
        TextView      tvTime, tvReactions;
        LinearLayout  layoutPollOptions;
        ImageButton   btnReact, btnForward;
        PollVH(View v) {
            super(v);
            ivAuthorIcon     = v.findViewById(R.id.iv_post_author_icon);
            tvAuthorName     = v.findViewById(R.id.tv_post_author_name);
            tvPinnedBadge    = v.findViewById(R.id.tv_post_pinned_badge);
            tvPollQuestion   = v.findViewById(R.id.tv_poll_question);
            tvVoteCount      = v.findViewById(R.id.tv_poll_vote_count);
            tvMultiSelect    = v.findViewById(R.id.tv_poll_multi_select);
            tvPollExpiry     = v.findViewById(R.id.tv_poll_expiry);
            layoutPollOptions= v.findViewById(R.id.layout_poll_options);
            tvTime           = v.findViewById(R.id.tv_post_time);
            tvReactions      = v.findViewById(R.id.tv_post_reactions);
            btnReact         = v.findViewById(R.id.btn_post_react);
            btnForward       = v.findViewById(R.id.btn_post_forward);
        }
    }

    static class AudioVH extends RecyclerView.ViewHolder {
        android.widget.ImageView ivAuthorIcon;
        TextView    tvAuthorName, tvPinnedBadge;
        ImageButton btnPlayAudio;
        View        waveformView;
        TextView    tvDuration, tvCaption, tvTime, tvReactions, tvViews, tvForwards;
        ImageButton btnReact, btnForward;
        AudioVH(View v) {
            super(v);
            ivAuthorIcon = v.findViewById(R.id.iv_post_author_icon);
            tvAuthorName = v.findViewById(R.id.tv_post_author_name);
            tvPinnedBadge= v.findViewById(R.id.tv_post_pinned_badge);
            btnPlayAudio = v.findViewById(R.id.btn_play_audio);
            waveformView = v.findViewById(R.id.view_audio_waveform);
            tvDuration   = v.findViewById(R.id.tv_audio_duration);
            tvCaption    = v.findViewById(R.id.tv_post_caption);
            tvTime       = v.findViewById(R.id.tv_post_time);
            tvReactions  = v.findViewById(R.id.tv_post_reactions);
            tvViews      = v.findViewById(R.id.tv_post_views);
            tvForwards   = v.findViewById(R.id.tv_post_forwards);
            btnReact     = v.findViewById(R.id.btn_post_react);
            btnForward   = v.findViewById(R.id.btn_post_forward);
        }
    }

    static class DocumentVH extends RecyclerView.ViewHolder {
        android.widget.ImageView ivAuthorIcon;
        TextView    tvAuthorName, tvPinnedBadge;
        View        layoutDocCard;
        TextView    tvDocName, tvDocSize, tvDocType, tvCaption;
        TextView    tvTime, tvReactions, tvViews, tvForwards;
        ImageButton btnReact, btnForward;
        DocumentVH(View v) {
            super(v);
            ivAuthorIcon  = v.findViewById(R.id.iv_post_author_icon);
            tvAuthorName  = v.findViewById(R.id.tv_post_author_name);
            tvPinnedBadge = v.findViewById(R.id.tv_post_pinned_badge);
            layoutDocCard = v.findViewById(R.id.layout_doc_card);
            tvDocName     = v.findViewById(R.id.tv_doc_name);
            tvDocSize     = v.findViewById(R.id.tv_doc_size);
            tvDocType     = v.findViewById(R.id.tv_doc_type);
            tvCaption     = v.findViewById(R.id.tv_post_caption);
            tvTime        = v.findViewById(R.id.tv_post_time);
            tvReactions   = v.findViewById(R.id.tv_post_reactions);
            tvViews       = v.findViewById(R.id.tv_post_views);
            tvForwards    = v.findViewById(R.id.tv_post_forwards);
            btnReact      = v.findViewById(R.id.btn_post_react);
            btnForward    = v.findViewById(R.id.btn_post_forward);
        }
    }

    static class DeletedVH extends RecyclerView.ViewHolder {
        TextView tvTime;
        DeletedVH(View v) {
            super(v);
            tvTime = v.findViewById(R.id.tv_post_time);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String formatDuration(long ms) {
        if (ms <= 0) return "0:00";
        long secs = ms / 1000;
        return String.format("%d:%02d", secs / 60, secs % 60);
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String formatRemaining(long ms) {
        long secs = ms / 1000;
        if (secs < 60)  return secs + "s";
        long mins = secs / 60;
        if (mins < 60)  return mins + "m";
        long hours = mins / 60;
        if (hours < 24) return hours + "h";
        return (hours / 24) + "d";
    }

    private String mimeToLabel(String mime) {
        if (mime == null)                  return "FILE";
        if (mime.contains("pdf"))          return "PDF";
        if (mime.contains("word"))         return "DOC";
        if (mime.contains("sheet"))        return "XLS";
        if (mime.contains("presentation")) return "PPT";
        if (mime.contains("zip"))          return "ZIP";
        if (mime.contains("text"))         return "TXT";
        return "FILE";
    }

    private String extractDomain(String url) {
        if (url == null) return "";
        try { return new java.net.URL(url).getHost().replaceAll("^www\\.", ""); }
        catch (Exception e) { return url; }
    }
}
