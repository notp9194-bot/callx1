package com.callx.app.conversation;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.text.PrecomputedTextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import com.callx.app.models.Message;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.MediaCache;

import java.text.SimpleDateFormat;
import java.util.Locale;
import com.callx.app.utils.LinkPreviewFetcher;

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
                    && safeEquals(asStr(a.editedAt), asStr(b.editedAt))
                    && a.deleted == b.deleted
                    && a.fontStyle == b.fontStyle
                    && reactionsEqual(a.reactions, b.reactions)  // FIX: reactions change pe rebind trigger
                    && pollVotesEqual(a.pollVotes, b.pollVotes)
                    && safeEquals(asStr(a.pollClosed), asStr(b.pollClosed));
            }

            private String asStr(Boolean b) { return b == null ? "null" : b.toString(); }
            private String asStr(Long l) { return l == null ? "null" : l.toString(); }

            private boolean pollVotesEqual(java.util.Map<String, java.util.List<Integer>> x,
                                            java.util.Map<String, java.util.List<Integer>> y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }

            private boolean safeEquals(String x, String y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }

            private boolean reactionsEqual(java.util.Map<String, String> x,
                                            java.util.Map<String, String> y) {
                if (x == null && y == null) return true;
                if (x == null || y == null) return false;
                return x.equals(y);
            }

            @Override
            public Object getChangePayload(@NonNull Message a, @NonNull Message b) {
                // Only status changed — return PAYLOAD_STATUS so onBind
                // skips full rebind and only updates tv_status.
                boolean onlyStatusChanged =
                        safeEquals(a.text, b.text) &&
                        safeEquals(a.type, b.type) &&
                        a.timestamp == b.timestamp &&
                        a.edited == b.edited &&
                        !safeEquals(a.status, b.status);
                if (onlyStatusChanged) return PAYLOAD_STATUS;
                return null; // null → full rebind
            }
        };

    // ── View types ────────────────────────────────────────────────
    private static final int TYPE_SENT        = 1;
    private static final int TYPE_RECEIVED    = 2;
    private static final int TYPE_STATUS_SEEN = 3;
    private static final int TYPE_REEL_SEEN   = 4;
    private static final int TYPE_CALL_ENTRY  = 5;
    /** reel_seen row that belongs to THIS viewer's own "watched X's reel"
     *  event — must render as nothing (0x0), since only the reel OWNER
     *  should ever see the bubble. See getItemViewType() below. */
    private static final int TYPE_HIDDEN         = 6;
    /** Standalone date separator chip — injected by insertSeparators() in ChatActivity.
     *  Stored as a synthetic Message with type="date_separator"; text holds the label. */
    private static final int TYPE_DATE_SEPARATOR   = 7;
    /** View-once message: receiver sees "View Once" tap badge. */
    private static final int TYPE_VIEW_ONCE_SENT    = 8;
    /** View-once message: already opened — shows expired state. */
    private static final int TYPE_VIEW_ONCE_EXPIRED = 9;
    /** View-once message: sent by ME, not yet opened by receiver — shows lock/waiting state. */
    private static final int TYPE_VIEW_ONCE_SENT_WAITING = 10;

    // ── DiffUtil payload key — only tv_status needs rebind when status changes ──
    static final String PAYLOAD_STATUS     = "status";
    static final String PAYLOAD_VIEW_ONCE  = "view_once_state";

    // PERF: RGB_565 for thumbnail-sized images — half the memory of ARGB_8888.
    // Thumbnails (avatars, video covers, reply previews, status/reel chips) have
    // no alpha channel, so the extra byte per pixel in ARGB_8888 is pure waste.
    // Full-size image loads (720×720) keep ARGB_8888 for quality.
    private static final RequestOptions THUMB_RGB565 = new RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.ALL);
    // ── Payload key for presence-only updates (viewing-dot / reply-glow /
    //    playing-badge) — lets setViewingMessageIds() etc. refresh just
    //    those three views instead of re-running the entire bindMessage()
    //    (Glide reloads, Linkify, GradientDrawable alloc, CountDownTimer
    //    restart...) every time a presence broadcast comes in. See
    //    bindPresenceOnly() and the payload check in onBindViewHolder().
    static final String PAYLOAD_PRESENCE = "presence";

    // ── ASYNC PrecomputedTextCompat ─────────────────────────────────
    // Small background pool so long-message text layout (line-breaking,
    // measuring) happens off the UI thread instead of inline during
    // onBindViewHolder. Low priority so it never competes with the main
    // thread for CPU during a fling.
    private static final java.util.concurrent.ExecutorService TEXT_PRECOMPUTE_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "msg-text-precompute");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    // LRU cache (messageId + text-hash → PrecomputedTextCompat) so scrolling
    // back to an already-seen long message reuses the previous result
    // instantly instead of recomputing. Capped at 80 entries — generous for
    // a chat screen's worth of long messages without growing unbounded.
    // Accessed from both the UI thread (read on bind) and the background
    // executor threads (write on completion) — all access MUST go through
    // precomputeCacheLock.
    private final Object precomputeCacheLock = new Object();
    private final java.util.LinkedHashMap<String, PrecomputedTextCompat> precomputedTextCache =
            new java.util.LinkedHashMap<String, PrecomputedTextCompat>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, PrecomputedTextCompat> eldest) {
                    return size() > 80;
                }
            };

    // ── Fields ────────────────────────────────────────────────────
    private final String currentUid;
    private final boolean isGroup;

    // ── Search highlight ──────────────────────────────────────────
    /** Non-null while a search is active. Applied as yellow BackgroundColorSpan in text bubbles. */
    private volatile String activeSearchQuery = null;

    /**
     * Set the current search query so text bubbles highlight matching substrings.
     * Pass null or empty string to clear highlights.
     * Must be called on the main thread.
     */
    public void setSearchQuery(String query) {
        activeSearchQuery = (query != null && !query.isEmpty()) ? query : null;
        notifyDataSetChanged();
    }

    /**
     * Overlay yellow BackgroundColorSpan on every occurrence of {@code query}
     * in the TextView's current text. Safe to call even if query is absent.
     * Called immediately after ExpandableTextHelper.bind() so spans are layered
     * on top of whatever text (full, truncated, or "Read more" form) is showing.
     */
    private void applySearchHighlight(android.widget.TextView tv, String query) {
        CharSequence cs = tv.getText();
        if (cs == null || cs.length() == 0) return;
        String lower = cs.toString().toLowerCase(Locale.getDefault());
        String lq = query.toLowerCase(Locale.getDefault());
        int idx = lower.indexOf(lq);
        if (idx < 0) return;
        android.text.SpannableString ss = new android.text.SpannableString(cs);
        while (idx >= 0) {
            ss.setSpan(
                new android.text.style.BackgroundColorSpan(0xFFFFD54F),
                idx, idx + lq.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            idx = lower.indexOf(lq, idx + 1);
        }
        tv.setText(ss);
        // Preserve LinkMovementMethod if already set by ExpandableTextHelper
        if (!(tv.getMovementMethod() instanceof android.text.method.LinkMovementMethod)) {
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
    }
    /** Set by the Activity right after construction (setChatId). Threaded
     *  through to MediaViewerActivity (video) so it can publish playback
     *  presence on chatPlayback/{chatId}/{uid} — audio playback presence
     *  doesn't need this since it goes through ActionListener instead,
     *  but video opens a separate full-screen Activity. */
    private String chatId;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private final SimpleDateFormat dateLabelFmt =
            new SimpleDateFormat("d MMM yyyy", Locale.getDefault());

    // ── PERF: timestamp → formatted string cache (LRU, 256 slots) ────────────
    // Messages in the same minute share a key → very high hit rate.
    // reuseDate avoids Date allocation on every format call.
    private final android.util.LruCache<Long, String> timeStringCache =
            new android.util.LruCache<>(256);
    private final java.util.Date reuseDate = new java.util.Date();

    private String formatTime(long ts) {
        long key = (ts / 60_000L) * 60_000L;
        String s = timeStringCache.get(key);
        if (s != null) return s;
        reuseDate.setTime(ts);
        s = timeFmt.format(reuseDate);
        timeStringCache.put(key, s);
        return s;
    }

    // ── PERF: date-label cache — "Today"/"Yesterday"/"3 Jan" per day ─────────
    // Keys are midnight-truncated timestamps. Recomputed once per day per key.
    private final android.util.LruCache<Long, String> dateLabelCache =
            new android.util.LruCache<>(64);

    // ── PERF: isSameDay result cache — keyed by (ts1/day, ts2/day) ───────────
    // Called on every row to decide whether to show the date separator.
    // A flat LongSparseArray isn't practical for two keys; use a small HashMap
    // of combined keys. Cache size capped — at most one entry per unique pair
    // in a visible window (~20 rows = ~20 unique pairs at most).
    private final java.util.HashMap<Long, Boolean> sameDayCache =
            new java.util.HashMap<>(32);

    // ── PERF: static Typeface — Typeface.create() is measured at ~0.5–2ms ────
    private static final android.graphics.Typeface TF_NORMAL =
            android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    android.graphics.Typeface.NORMAL);

    private ActionListener actionListener;
    private MediaPlayer player;
    private int playingPos = -1;
    // FIX [P3-1]: Track the ViewHolder that is currently playing so we can
    // reset its UI (icon + seekbar) when a different message starts playing.
    private VH playingVH = null;
    // FIX: SeekBar progress update via Handler — 250ms interval during playback
    private final android.os.Handler seekHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable seekUpdater;

    // ── Interface for long-press actions ─────────────────────────
    public interface ActionListener {
        void onReply(Message m);
        void onNavigateToOriginal(String messageId);
        void onDelete(Message m);
        void onReact(Message m, String emoji);
        void onStar(Message m);
        void onCopy(Message m);
        void onForward(Message m);
        /** Called when user taps the ⚠ failed-status icon to retry sending. */
        default void onRetry(Message m) {}
        /** Called when user chooses Edit from the action sheet (own messages only). */
        default void onEdit(Message m) {}
        /** Called when user taps the "✏️ edited" tag on a bubble's timestamp
         *  to view every prior version of the message text. */
        default void onShowEditHistory(Message m) {}
        /** Called when user pins or unpins a message from the action sheet. */
        default void onPin(Message m) {}
        /** Called when user taps a poll option to cast/change their vote. */
        default void onPollVote(Message m, int optionIndex) {}
        /** Called when user taps a message's existing reactions row (the small
         *  "❤️2 👍1" chip under a bubble) to see who reacted with what. */
        default void onReactionTap(Message m) {}
        /** Called when poll creator chooses Close/Reopen poll from the action sheet. */
        default void onPollToggleClose(Message m) {}
        /** Called whenever OUR OWN audio/video playback for this message
         *  starts or stops (play, pause, finish, error, or switching to a
         *  different bubble) — lets ChatPlaybackPresenceController publish
         *  a real-time "listening…/watching…" badge to the partner. */
        default void onPlaybackStateChanged(Message m, boolean playing) {}
    }

    // ── Multi-select interface ────────────────────────────────────
    public interface MultiSelectListener {
        void onSelectionChanged(int count);
    }

    // ── Multi-select state ────────────────────────────────────────
    private boolean multiSelectMode = false;
    private final java.util.Set<String> selectedMessageIds = new java.util.HashSet<>();

    /**
     * Expansion state for "Read more / Read less" — stored HERE (not on Message).
     * The Message model's isExpanded field is transient and dies on PagingData refresh.
     * Storing state in the adapter means it survives Room invalidations and new snapshots.
     */
    private final java.util.Set<String> expandedMessageIds = new java.util.HashSet<>();

    // ── "Currently viewing this message" live dots ──────────────────────
    // Set of messageIds that some OTHER participant currently has scrolled
    // into view, fed by ChatPresenceController / GroupWatchingController
    // listening on chatViewing/{chatOrGroupId}/*. Purely additive — never
    // touches tv_status (delivered/read ticks).
    private java.util.Set<String> currentlyViewedMessageIds = java.util.Collections.emptySet();

    /** Replaces the set of "being viewed right now" message ids and
     *  refreshes only the rows whose dot state actually changed. */
    public void setViewingMessageIds(java.util.Set<String> newIds) {
        if (newIds == null) newIds = java.util.Collections.emptySet();
        java.util.Set<String> old = currentlyViewedMessageIds;
        if (old.equals(newIds)) return;
        java.util.Set<String> changed = new java.util.HashSet<>(old);
        changed.addAll(newIds);
        currentlyViewedMessageIds = newIds;
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            // PERF: payload-only refresh — see PAYLOAD_PRESENCE. Avoids
            // re-running the full bindMessage() (Glide reloads, Linkify,
            // bubble redraw, countdown restart) just to flip a dot.
            if (id != null && changed.contains(id)) notifyItemChanged(i, PAYLOAD_PRESENCE);
        }
    }

    // ── "Someone is currently composing a reply to this message" glow ──────
    // Sibling set to currentlyViewedMessageIds, but scoped to whatever
    // bubble a participant has open in the reply bar AND is actively typing
    // into right now (not just "scrolled into view"). Fed by
    // ChatPresenceController / GroupChatActivity listening on
    // chatTypingReply/{chatOrGroupId}/{uid} = messageId | null.
    private java.util.Set<String> replyTargetMessageIds = java.util.Collections.emptySet();

    /** Replaces the set of "being replied to right now" message ids and
     *  refreshes only the rows whose highlight state actually changed. */
    public void setReplyTargetMessageIds(java.util.Set<String> newIds) {
        if (newIds == null) newIds = java.util.Collections.emptySet();
        java.util.Set<String> old = replyTargetMessageIds;
        if (old.equals(newIds)) return;
        java.util.Set<String> changed = new java.util.HashSet<>(old);
        changed.addAll(newIds);
        replyTargetMessageIds = newIds;
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            // PERF: payload-only refresh — see PAYLOAD_PRESENCE.
            if (id != null && changed.contains(id)) notifyItemChanged(i, PAYLOAD_PRESENCE);
        }
    }

    // ── "Someone is currently playing this voice note / video" badge ───────
    // Sibling of currentlyViewedMessageIds, but for actual audio/video
    // PLAYBACK rather than scroll position. Fed by ChatPlaybackPresenceController
    // listening on chatPlayback/{chatOrGroupId}/{uid} = messageId | null.
    private java.util.Set<String> currentlyPlayingMessageIds = java.util.Collections.emptySet();

    /** Replaces the set of "being played right now" message ids and
     *  refreshes only the rows whose badge state actually changed. */
    public void setPlayingMessageIds(java.util.Set<String> newIds) {
        if (newIds == null) newIds = java.util.Collections.emptySet();
        java.util.Set<String> old = currentlyPlayingMessageIds;
        if (old.equals(newIds)) return;
        java.util.Set<String> changed = new java.util.HashSet<>(old);
        changed.addAll(newIds);
        currentlyPlayingMessageIds = newIds;
        for (int i = 0; i < getItemCount(); i++) {
            Message m = getItem(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            // PERF: payload-only refresh — see PAYLOAD_PRESENCE.
            if (id != null && changed.contains(id)) notifyItemChanged(i, PAYLOAD_PRESENCE);
        }
    }
    private MultiSelectListener multiSelectListener;

    public void setMultiSelectListener(MultiSelectListener l) { this.multiSelectListener = l; }
    public ActionListener getActionListener() { return actionListener; }

    /** Called once by the Activity right after construction — threads chatId
     *  through to the video tap-to-view Intent so MediaViewerActivity can
     *  publish playback presence (see class-level field doc above). */
    public void setChatId(String chatId) { this.chatId = chatId; }

    public void enterMultiSelectMode(Message firstMessage) {
        multiSelectMode = true;
        selectedMessageIds.clear();
        String id = firstMessage != null ? firstMessage.messageId : null;
        if (id == null && firstMessage != null) id = firstMessage.id;
        if (id != null) selectedMessageIds.add(id);
        // FIX: notifyDataSetChanged() kills performance — notify only the first selected item
        // and rely on the long-press caller to refresh visible items via notifyItemRangeChanged
        notifyItemRangeChanged(0, getItemCount());
        if (multiSelectListener != null) multiSelectListener.onSelectionChanged(selectedMessageIds.size());
    }

    public void exitMultiSelectMode() {
        multiSelectMode = false;
        selectedMessageIds.clear();
        // FIX: targeted range notify instead of notifyDataSetChanged()
        notifyItemRangeChanged(0, getItemCount());
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

    private static final int TAG_KEY_SELECTED = 0x7F_0B_0001;

    private void applySelectionHighlight(VH h, Message m) {
        String id = m.messageId != null ? m.messageId : m.id;
        boolean selected = id != null && selectedMessageIds.contains(id);
        if (multiSelectMode) {
            h.itemView.setAlpha(selected ? 1.0f : 0.55f);
            Boolean was = (Boolean) h.itemView.getTag(TAG_KEY_SELECTED);
            if (was == null || was != selected) {
                h.itemView.setBackgroundColor(selected ? 0x336200EE : android.graphics.Color.TRANSPARENT);
                h.itemView.setTag(TAG_KEY_SELECTED, selected);
            }
        } else {
            if (h.itemView.isActivated() || h.itemView.getTag(TAG_KEY_SELECTED) != null) {
                h.itemView.setAlpha(1.0f);
                h.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                h.itemView.setTag(TAG_KEY_SELECTED, null);
            }
        }
    }

    public MessagePagingAdapter(String currentUid, boolean isGroup) {
        super(DIFF);
        this.currentUid = currentUid;
        this.isGroup    = isGroup;
        // NOTE: setHasStableIds(true) + custom getItemId() was attempted here
        // but PagingDataAdapter.getItemId(int) is FINAL — it already manages
        // item identity internally via the DiffUtil.ItemCallback (DIFF above
        // handles areItemsTheSame() by messageId), so a custom getItemId()
        // isn't possible and isn't needed: DiffUtil already gives correct
        // identity tracking across submitData() calls, including the
        // warm-cache-list → real-Paging-list transition.
    }

    public void setActionListener(ActionListener l) {
        this.actionListener = l;
    }

    // ──────────────────────────────────────────────────────────────
    @Override
    public int getItemViewType(int position) {
        Message m = getItem(position);
        if (m == null) return TYPE_RECEIVED;
        if ("date_separator".equals(m.type)) return TYPE_DATE_SEPARATOR;
        if ("status_seen".equals(m.type)) return TYPE_STATUS_SEEN;
        if ("reel_seen".equals(m.type)) {
            // Bubble must show ONLY to the reel's owner (the person who got
            // watched), never to the viewer who did the watching — otherwise
            // both sides see "watched your reel" for every reel view.
            return currentUid.equals(m.reelOwnerUid) ? TYPE_REEL_SEEN : TYPE_HIDDEN;
        }
        if ("call_entry".equals(m.type))  return TYPE_CALL_ENTRY;
        // Feature 13: View Once — intercept before generic sent/received
        if (Boolean.TRUE.equals(m.viewOnce)) {
            if (com.callx.app.conversation.controllers.ChatViewOnceController.isExpired(m)) {
                return TYPE_VIEW_ONCE_EXPIRED;
            }
            // Sender sees their own un-opened message as "Waiting to be opened" (lock state)
            // Only after receiver actually opens it does it become TYPE_VIEW_ONCE_EXPIRED
            if (currentUid.equals(m.senderId)) return TYPE_VIEW_ONCE_SENT_WAITING;
            return TYPE_VIEW_ONCE_SENT;
        }
        return currentUid.equals(m.senderId) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HIDDEN) {
            View v = new View(parent.getContext());
            v.setLayoutParams(new ViewGroup.LayoutParams(0, 0));
            return new VH(v);
        }
        if (viewType == TYPE_DATE_SEPARATOR) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_date_separator, parent, false);
            v.setSaveEnabled(false);
            return new VH(v);
        }
        int layout;
        if (viewType == TYPE_VIEW_ONCE_SENT)         layout = R.layout.item_view_once_bubble;
        else if (viewType == TYPE_VIEW_ONCE_EXPIRED) layout = R.layout.item_view_once_expired;
        else if (viewType == TYPE_VIEW_ONCE_SENT_WAITING) layout = R.layout.item_view_once_sent_waiting;
        else if (viewType == TYPE_SENT)          layout = R.layout.item_message_sent;
        else if (viewType == TYPE_STATUS_SEEN) layout = R.layout.item_status_seen_bubble;
        else if (viewType == TYPE_REEL_SEEN)   layout = R.layout.item_reel_seen_bubble;
        else if (viewType == TYPE_CALL_ENTRY)  layout = R.layout.item_call_entry_bubble;
        else                                   layout = R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        // PERF: item-level micro-optimizations (applied once at create time)
        // setSaveEnabled(false) — skip useless per-item state parcelling
        // LAYER_TYPE_NONE       — ensure no stray software layer from XML
        v.setSaveEnabled(false);
        v.setLayerType(View.LAYER_TYPE_NONE, null);
        VH vh = new VH(v);
        // ── One-time constant setup — keeps onBindViewHolder lean ────────────
        if (vh.tvMessage != null) {
            vh.tvMessage.setTypeface(TF_NORMAL);
            vh.tvMessage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    com.callx.app.utils.MessageFontSizeManager.get(parent.getContext()).getFontSizeSp());
        }
        // ── Apply 70% screen-width cap ──
        int screenW = parent.getContext().getResources().getDisplayMetrics().widthPixels;
        int maxW = (int) (screenW * 0.70f);
        if (vh.tvMessage != null) vh.tvMessage.setMaxWidth(maxW);
        // llAudio / llFile / flVideo / llLinkPreview / llPoll are now ViewStubs —
        // their LayoutParams are set via android:layout_width in the stub tag
        // (= @dimen/msg_bubble_max_width), so no runtime width override is needed.
        if (vh.ivImage != null) { android.view.ViewGroup.LayoutParams lp = vh.ivImage.getLayoutParams(); if (lp != null) { lp.width = maxW; vh.ivImage.setLayoutParams(lp); } }
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position,
                                 @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && PAYLOAD_STATUS.equals(payloads.get(0))) {
            // Fast path: only tick changed — update tv_status only, skip full bind
            Message m = getItem(position);
            if (m != null && h.tvStatus != null) {
                bindStatusTick(h, m);
            }
            return;
        }
        if (!payloads.isEmpty() && PAYLOAD_PRESENCE.equals(payloads.get(0))) {
            // Fast path: a viewing/typing-reply/playback broadcast changed —
            // update only the dot/glow/badge, skip full bind entirely.
            Message m = getItem(position);
            if (m != null) {
                bindPresenceOnly(h, m);
            }
            return;
        }
        // Full bind
        onBindViewHolder(h, position);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Message m = getItem(position);
        if (m == null) {
            // Placeholder — show shimmer or empty
            if (h.tvMessage != null) h.tvMessage.setVisibility(View.GONE);
            return;
        }
        // ── DATE SEPARATOR — standalone chip row ─────────────────────────
        if ("date_separator".equals(m.type)) {
            TextView tvLabel = h.itemView.findViewById(R.id.tv_date_label);
            if (tvLabel != null) tvLabel.setText(m.text != null ? m.text : "");
            return;
        }
        // ── STATUS SEEN BUBBLE — special system event row ─────────────────
        if ("status_seen".equals(m.type)) {
            bindStatusSeenBubble(h, m);
            return;
        }
        // ── REEL SEEN BUBBLE — special system event row ───────────────────
        if ("reel_seen".equals(m.type)) {
            if (!currentUid.equals(m.reelOwnerUid)) return; // hidden for the viewer side
            bindReelSeenBubble(h, m);
            return;
        }
        // ── CALL ENTRY BUBBLE — system call log row in chat ──────────────────
        if ("call_entry".equals(m.type)) {
            bindCallEntryBubble(h, m);
            return;
        }
        // ── VIEW ONCE BUBBLES — Feature 13 ────────────────────────────────
        if (Boolean.TRUE.equals(m.viewOnce)) {
            if (com.callx.app.conversation.controllers.ChatViewOnceController.isExpired(m)) {
                bindViewOnceExpired(h, m);
                return;
            }
            if (currentUid.equals(m.senderId)) {
                bindViewOnceSentWaiting(h, m);
                return;
            }
            bindViewOnceSent(h, m);
            return;
        }
        bindMessage(h, m, position);
    }

    // ──────────────────────────────────────────────────────────────
    // CALL ENTRY BUBBLE — centered system call log row in chat.
    // Layout: item_call_entry_bubble.xml
    //   tv_call_entry_icon  — emoji (📞 audio, 📹 video)
    //   tv_call_entry_label — e.g. "Audio call • 2:30" or "Missed video call"
    //   tv_call_entry_time  — formatted timestamp (hh:mm a)
    // No long-press / reactions — it's a system event.
    // ──────────────────────────────────────────────────────────────
    private void bindCallEntryBubble(@NonNull VH h, @NonNull Message m) {
        android.widget.TextView tvIcon  = h.tvCallEntryIcon;
        android.widget.TextView tvLabel = h.tvCallEntryLabel;
        android.widget.TextView tvTime  = h.tvCallEntryTime;
        android.view.View llRoot = h.llCallEntryRoot;
        android.view.View llPill = h.llCallEntryPill;

        boolean isVideoCall = "video".equals(m.fileName);
        boolean isMissed    = "missed".equals(m.text);
        boolean iAmCaller   = currentUid != null && currentUid.equals(m.senderId);

        // Align bubble to the caller's side — right if I called, left if they called.
        if (llRoot instanceof android.widget.LinearLayout) {
            ((android.widget.LinearLayout) llRoot).setGravity(
                    iAmCaller ? android.view.Gravity.END : android.view.Gravity.START);
        }
        if (llPill != null) {
            android.widget.LinearLayout.LayoutParams lp =
                    (android.widget.LinearLayout.LayoutParams) llPill.getLayoutParams();
            lp.gravity = iAmCaller ? android.view.Gravity.END : android.view.Gravity.START;
            llPill.setLayoutParams(lp);
        }

        // Icon
        if (tvIcon != null) tvIcon.setText(isVideoCall ? "📹" : "📞");

        // Label text
        String label;
        if (isMissed) {
            if (iAmCaller) {
                label = isVideoCall ? "No answer (video)" : "No answer";
            } else {
                label = isVideoCall ? "Missed video call" : "Missed call";
            }
            if (tvLabel != null) tvLabel.setTextColor(android.graphics.Color.parseColor("#FF5555"));
        } else {
            // Call was connected — show duration
            String durStr = "";
            if (m.duration != null && m.duration > 0) {
                long sec = m.duration / 1000;
                durStr = " • " + String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60);
            }
            if (iAmCaller) {
                label = isVideoCall ? ("Video call" + durStr) : ("Audio call" + durStr);
            } else {
                label = isVideoCall ? ("Incoming video call" + durStr) : ("Incoming call" + durStr);
            }
            if (tvLabel != null) tvLabel.setTextColor(0xFFFFFFFF);
        }
        if (tvLabel != null) tvLabel.setText(label);

        // Time
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(formatTime(m.timestamp));
        }
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
        de.hdodenhof.circleimageview.CircleImageView ivAvatar = h.ivStatusSeenAvatar;
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                com.bumptech.glide.Glide.with(ctx)
                    .load(photo)
                    .apply(THUMB_RGB565)
                    .override(96, 96)
                    .dontAnimate()
                    .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }
        }

        // Status thumbnail
        android.view.View flThumb = h.flStatusSeenThumb;
        android.widget.ImageView ivThumb = h.ivStatusSeenThumb;
        android.widget.ImageView ivEye   = h.ivStatusSeenEye;
        if (ivThumb != null && flThumb != null) {
            String thumb = m.statusThumbUrl != null ? m.statusThumbUrl : "";
            if (!thumb.isEmpty()) {
                flThumb.setVisibility(View.VISIBLE);
                if (ivEye != null) ivEye.setVisibility(View.VISIBLE);
                com.bumptech.glide.Glide.with(ctx)
                    .load(thumb)
                    .apply(THUMB_RGB565)
                    .override(240, 240)
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
        android.widget.TextView tvName = h.tvStatusSeenName;
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(View.VISIBLE);
            } else {
                tvName.setVisibility(View.GONE);
            }
        }

        // Time
        android.widget.TextView tvTime = h.tvStatusSeenTime;
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(formatTime(m.timestamp));
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
        de.hdodenhof.circleimageview.CircleImageView ivAvatar = h.ivReelSeenAvatar;
        if (ivAvatar != null) {
            String photo = m.senderPhoto != null ? m.senderPhoto : "";
            if (!photo.isEmpty()) {
                com.bumptech.glide.Glide.with(ctx)
                    .load(photo)
                    .apply(THUMB_RGB565)
                    .override(96, 96)
                    .dontAnimate()
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
        android.view.View flThumb = h.flReelSeenThumb;
        android.widget.ImageView ivThumb = h.ivReelSeenThumb;
        android.widget.ImageView ivPlay  = h.ivReelSeenPlay;
        if (ivThumb != null) {
            String thumb = m.reelThumbUrl != null ? m.reelThumbUrl : "";
            if (!thumb.isEmpty()) {
                ivThumb.setVisibility(android.view.View.VISIBLE);
                if (ivPlay != null) ivPlay.setVisibility(android.view.View.VISIBLE);
                com.bumptech.glide.Glide.with(ctx)
                    .load(thumb)
                    .apply(THUMB_RGB565)
                    .override(240, 240)
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
        android.widget.TextView tvName = h.tvReelSeenName;
        if (tvName != null) {
            if (isGroup && m.senderName != null && !m.senderName.isEmpty()) {
                tvName.setText(m.senderName);
                tvName.setVisibility(android.view.View.VISIBLE);
            } else {
                tvName.setVisibility(android.view.View.GONE);
            }
        }

        // Time
        android.widget.TextView tvTime = h.tvReelSeenTime;
        if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
            tvTime.setText(formatTime(m.timestamp));
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Core bind logic (mirrors MessageAdapter)
    // ──────────────────────────────────────────────────────────────
    // ──────────────────────────────────────────────────────────────
    // Date separator helper — returns "Today", "Yesterday", or "3 Jan 2025"
    // PERF: result is cached per midnight-truncated timestamp so Calendar
    // isn't allocated on every row during a scroll.
    // ──────────────────────────────────────────────────────────────
    private String formatDateLabel(long timestamp) {
        long dayKey = (timestamp / 86_400_000L) * 86_400_000L;
        String cached = dateLabelCache.get(dayKey);
        if (cached != null) return cached;

        java.util.Calendar msgCal = java.util.Calendar.getInstance();
        msgCal.setTimeInMillis(timestamp);
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);

        boolean isToday = msgCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
                && msgCal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR);
        boolean isYesterday = msgCal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR)
                && msgCal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR);

        String label;
        if (isToday) {
            label = "Today";
        } else if (isYesterday) {
            label = "Yesterday";
        } else {
            boolean sameYear = msgCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR);
            reuseDate.setTime(timestamp);
            label = (sameYear ? dateLabelFmt : new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()))
                    .format(reuseDate);
        }
        // Don't cache "Today" / "Yesterday" — they become stale at midnight.
        // Cache only absolute date strings which never change.
        if (!label.equals("Today") && !label.equals("Yesterday")) {
            dateLabelCache.put(dayKey, label);
        }
        return label;
    }

    // PERF: isSameDay — cached by combined day-truncated key pair.
    // Avoids two Calendar.getInstance() + setTimeInMillis on every row.
    private boolean isSameDay(long ts1, long ts2) {
        long day1 = ts1 / 86_400_000L;
        long day2 = ts2 / 86_400_000L;
        if (day1 == day2) return true;          // fast path — same millisecond-day bucket
        long cacheKey = (day1 << 20) ^ day2;    // combine; collisions harmless (fallback to Calendar)
        Boolean hit = sameDayCache.get(cacheKey);
        if (hit != null) return hit;
        java.util.Calendar c1 = java.util.Calendar.getInstance();
        java.util.Calendar c2 = java.util.Calendar.getInstance();
        c1.setTimeInMillis(ts1);
        c2.setTimeInMillis(ts2);
        boolean same = c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR)
                && c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR);
        sameDayCache.put(cacheKey, same);
        return same;
    }

    private void bindMessage(@NonNull VH h, @NonNull Message m, int position) {
        Context ctx = h.itemView.getContext();
        boolean sent = currentUid.equals(m.senderId);

        // ── "Someone is viewing this message right now" dot ───────────────
        if (h.viewSeenDot != null) {
            String mid = m.messageId != null ? m.messageId : m.id;
            boolean viewing = mid != null && currentlyViewedMessageIds.contains(mid);
            h.viewSeenDot.setVisibility(viewing ? View.VISIBLE : View.GONE);
        }

        // ── "Someone is currently playing this voice note / video" badge ──
        // Instagram-DM-style live indicator — independent of the dot above
        // (that one means "scrolled into view", this one means "actually
        // pressed play right now"). Audio/video only; harmless no-op for
        // text/image/poll bubbles since the badge just stays hidden.
        if (h.tvListeningBadge != null) {
            String mid = m.messageId != null ? m.messageId : m.id;
            boolean playing = mid != null && currentlyPlayingMessageIds.contains(mid);
            if (playing) {
                boolean isVideoMsg = "video".equals(m.type);
                h.tvListeningBadge.setText(isVideoMsg ? "▶ watching…" : "🎧 listening…");
            }
            h.tvListeningBadge.setVisibility(playing ? View.VISIBLE : View.GONE);
        }

        // ── Theme-aware bubble background ─────────────────────────────────
        android.view.View llBubble = h.llBubble;
        try {
            if (llBubble != null) {
                boolean hasReply = m.replyToId != null && !m.replyToId.isEmpty();
                String bType = m.type != null ? m.type : "text";
                com.callx.app.utils.ChatThemeManager
                        .get(ctx)
                        .applyBubble(llBubble, sent, bType, hasReply);
            }
        } catch (Exception ignored) {}

        // ── "Someone is currently composing a reply to THIS message" glow ──
        // Finer-grained sibling of the viewing-dot above: lights up only the
        // exact bubble being replied to (not just "screen open" or "this
        // message is in view"), fed by chatTypingReply/{id}/{uid}=messageId.
        if (llBubble != null) {
            String mid = m.messageId != null ? m.messageId : m.id;
            boolean isReplyTarget = mid != null && replyTargetMessageIds.contains(mid);
            llBubble.setForeground(isReplyTarget
                    ? ContextCompat.getDrawable(ctx, R.drawable.bg_reply_target_highlight)
                    : null);
        }

        // Reset visibility
        h.tvMessage.setVisibility(View.GONE);
        if (h.ivImage    != null) h.ivImage.setVisibility(View.GONE);
        if (h.llAudio    != null) h.llAudio.setVisibility(View.GONE);
        if (h.llFile     != null) h.llFile.setVisibility(View.GONE);
        if (h.llPoll     != null) h.llPoll.setVisibility(View.GONE);
        if (h.llReelShare!= null) h.llReelShare.setVisibility(View.GONE);
        if (h.tvTime     != null) h.tvTime.setVisibility(View.VISIBLE);

        // ── Quick Forward Button — media/link messages pe dikhao ──────────
        if (h.btnQuickForward != null) {
            String mt = m.type != null ? m.type : "text";
            boolean showFwd = mt.equals("image") || mt.equals("video") || mt.equals("audio")
                    || mt.equals("file") || mt.equals("reel_share") || mt.equals("reel_link")
                    || (mt.equals("text") && m.text != null
                        && (m.text.contains("http://") || m.text.contains("https://")));
            h.btnQuickForward.setVisibility(showFwd ? View.VISIBLE : View.GONE);
            if (showFwd) {
                final Message fwdMsg = m;
                h.btnQuickForward.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onForward(fwdMsg);
                });
            }
        }

        // Timestamp — append "(edited)" when applicable
        if (h.tvTime != null && m.timestamp > 0) {
            String timeStr = formatTime(m.timestamp);
            boolean isEdited = Boolean.TRUE.equals(m.edited);
            if (isEdited) timeStr = timeStr + "  \u270F\uFE0F edited";
            h.tvTime.setText(timeStr);
            // Tap the "✏️ edited" tag to view every prior version of the text.
            if (isEdited) {
                h.tvTime.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onShowEditHistory(m);
                });
            } else {
                h.tvTime.setOnClickListener(null);
                h.tvTime.setClickable(false);
            }
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
                                .load(thumbUrl)
                                .apply(THUMB_RGB565)
                                .override(120, 120)
                                .centerCrop()
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

        // ── Reactions display ─────────────────────────────────────────
        if (h.llReactions != null && h.tvReactions != null) {
            java.util.Map<String, String> rxMap = m.reactions;
            if (rxMap != null && !rxMap.isEmpty()) {
                // Count each unique emoji
                java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
                for (String emoji : rxMap.values()) {
                    counts.put(emoji, counts.containsKey(emoji) ? counts.get(emoji) + 1 : 1);
                }
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                    sb.append(e.getKey());
                    if (e.getValue() > 1) sb.append(e.getValue());
                    sb.append(" ");
                    if (++shown >= 4) break; // max 4 distinct emojis shown
                }
                h.tvReactions.setText(sb.toString().trim());
                h.llReactions.setVisibility(View.VISIBLE);
                h.llReactions.setOnClickListener(v -> {
                    if (actionListener != null) actionListener.onReactionTap(m);
                });
            } else {
                h.llReactions.setVisibility(View.GONE);
                h.llReactions.setOnClickListener(null);
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
                    boolean isGifMsg = "gif".equals(m.type);

                    // ── Progressive loading: thumb instantly → full replaces ──
                    if (thumbUrl != null && !thumbUrl.isEmpty() && !isGifMsg) {
                        // Step 1: Show thumbnail instantly (tiny, ~30KB)
                        Glide.with(ctx)
                            .load(thumbUrl)
                            .apply(THUMB_RGB565)
                            .override(200, 200)
                            .into(h.ivImage);

                        // Step 2: Load full in background — replaces thumb with crossfade
                        Glide.with(ctx)
                            .load(fullUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(720, 720) // PERF: cap decode size to bubble size, not native res
                            .thumbnail(Glide.with(ctx)
                                .load(thumbUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL))
                            .transition(com.bumptech.glide.load.resource.drawable
                                .DrawableTransitionOptions.withCrossFade(400))
                            .placeholder(R.drawable.ic_file)
                            .error(R.drawable.ic_file)
                            .into(h.ivImage);
                    } else {
                        // GIF ya no thumbnail — direct load with animation support
                        java.io.File cachedImg = isGifMsg ? null : MediaCache.getCached(ctx, fullUrl);
                        if (isGifMsg) {
                            // GIF: asGif() se URL directly load karo — MediaCache file use
                            // mat karo kyunki file mein .gif extension nahi hogi, Glide
                            // decode fail karta hai. Glide DiskCache GIF cache kar lega.
                            Glide.with(ctx)
                                .asGif()
                                .load(fullUrl)
                                .apply(THUMB_RGB565)
                                .override(480, 480) // PERF: GIFs are heavy to decode/animate at full res
                                .placeholder(R.drawable.ic_file)
                                .error(R.drawable.ic_file)
                                .into(h.ivImage);
                        } else if (cachedImg != null) {
                            Glide.with(ctx).load(cachedImg)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .override(720, 720)
                                .placeholder(R.drawable.ic_file)
                                .into(h.ivImage);
                        } else {
                            Glide.with(ctx).load(fullUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .override(720, 720)
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

                    // Click → WhatsApp-style image action bottom sheet
                    h.ivImage.setOnClickListener(v ->
                        showImageActionSheet(ctx, m, fullUrl, thumbUrl != null ? thumbUrl : fullUrl));
                    // Long-press → normal message action sheet
                    h.ivImage.setOnLongClickListener(v -> {
                        if (actionListener != null) showActionBottomSheet(ctx, m);
                        return true;
                    });
                }
                break;
            case "video": {
                // POLISH: Use fl_video + iv_video_thumb (thumbnail + play overlay)
                // Prefer thumbnailUrl (Cloudinary thumb) over raw video URL for preview
                final String vMid = m.messageId != null ? m.messageId : m.id;
                ensureVideoInflated(h); // ViewStub lazy inflate
                if (h.flVideo != null && h.ivVideoThumb != null) {
                    h.flVideo.setVisibility(View.VISIBLE);
                    if (h.ivImage != null) h.ivImage.setVisibility(View.GONE);
                    String vUrl   = m.mediaUrl != null ? m.mediaUrl : m.text;
                    // POLISH FIX: use Cloudinary thumbnail for preview image, not the raw video URL
                    String thumbUrl = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                            ? m.thumbnailUrl : vUrl;
                    Glide.with(ctx)
                        .load(thumbUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .thumbnail(0.1f) // PERF: render 10% low-res frame instantly, then upgrade
                        .override(480, 480)
                        .placeholder(R.drawable.ic_file)
                        .centerCrop()
                        .into(h.ivVideoThumb);
                    // Duration overlay
                    if (h.tvDuration != null && m.duration != null && m.duration > 0) {
                        long secs = m.duration / 1000;
                        h.tvDuration.setText(String.format(
                                java.util.Locale.US, "%d:%02d", secs / 60, secs % 60));
                        h.tvDuration.setVisibility(View.VISIBLE);
                    }
                    h.flVideo.setOnClickListener(v -> {
                        Intent i = new Intent().setClassName(ctx.getPackageName(),
                                "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url", vUrl);
                        i.putExtra("type", "video");
                        // Lets MediaViewerActivity publish playback presence
                        // (chatPlayback/{chatId}/{uid}=messageId) while the
                        // video is actually playing — null-safe extras.
                        i.putExtra("chatId", chatId);
                        i.putExtra("messageId", vMid);
                        ctx.startActivity(i);
                    });
                } else if (h.ivImage != null) {
                    // Fallback: layout without fl_video — show thumbnail in ivImage
                    h.ivImage.setVisibility(View.VISIBLE);
                    String vUrl     = m.mediaUrl != null ? m.mediaUrl : m.text;
                    String thumbUrl = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                            ? m.thumbnailUrl : vUrl;
                    Glide.with(ctx).load(thumbUrl)
                        .apply(THUMB_RGB565)
                        .override(480, 480)
                        .placeholder(R.drawable.ic_file)
                        .into(h.ivImage);
                    h.ivImage.setOnClickListener(v -> {
                        Intent i = new Intent().setClassName(ctx.getPackageName(),
                                "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url", vUrl);
                        i.putExtra("type", "video");
                        i.putExtra("chatId", chatId);
                        i.putExtra("messageId", vMid);
                        ctx.startActivity(i);
                    });
                }
                break;
            }
            case "audio":
                ensureAudioInflated(h, ctx, sent); // ViewStub lazy inflate
                if (h.llAudio != null && h.btnPlayPause != null) {
                    h.llAudio.setVisibility(View.VISIBLE);
                    String aUrl = m.mediaUrl != null ? m.mediaUrl : m.text;
                    if (h.seekAudio != null) {
                        h.seekAudio.setSeed(aUrl);
                        h.seekAudio.setProgress(0f);
                    }
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
                ensureFileInflated(h, ctx, sent); // ViewStub lazy inflate
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
            case "poll":
                ensurePollInflated(h); // ViewStub lazy inflate
                if (h.llPoll != null) {
                    bindPoll(h, m, sent);
                } else {
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText("\uD83D\uDCCA " + (m.pollQuestion != null ? m.pollQuestion : "Poll"));
                }
                break;
            case "reel_share":
            case "reel_link": {
                // ── Inflate ViewStub on first use ──
                if (h.stubReelShare != null) {
                    h.stubReelShare.inflate();
                    h.llReelShare         = h.itemView.findViewById(R.id.ll_reel_share);
                    h.ivReelShareThumb    = h.itemView.findViewById(R.id.iv_reel_share_thumb);
                    h.ivReelShareAvatar   = h.itemView.findViewById(R.id.iv_reel_share_avatar);
                    h.tvReelShareUsername = h.itemView.findViewById(R.id.tv_reel_share_username);
                    h.tvReelShareCaption  = h.itemView.findViewById(R.id.tv_reel_share_caption);
                    // Apply circular clip to avatar via ShapeAppearance (no CircleImageView dep needed)
                    if (h.ivReelShareAvatar != null) {
                        h.ivReelShareAvatar.setClipToOutline(true);
                        h.ivReelShareAvatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
                            @Override public void getOutline(android.view.View v, android.graphics.Outline outline) {
                                outline.setOval(0, 0, v.getWidth(), v.getHeight());
                            }
                        });
                    }
                    h.stubReelShare = null; // mark inflated
                }
                if (h.llReelShare == null) {
                    // Fallback: stub missing in layout, show text
                    h.tvMessage.setVisibility(View.VISIBLE);
                    h.tvMessage.setText(m.text != null ? m.text : "🎬 Reel");
                    break;
                }
                h.llReelShare.setVisibility(View.VISIBLE);

                // Username
                if (h.tvReelShareUsername != null) {
                    String uname = (m.reelShareUsername != null && !m.reelShareUsername.isEmpty())
                            ? "@" + m.reelShareUsername : "@callx_reel";
                    h.tvReelShareUsername.setText(uname);
                }
                // Avatar — load from reelShareOwnerPhoto; Firebase fallback reads profileImage/photoUrl
                if (h.ivReelShareAvatar != null) {
                    String avatarUrl = m.reelShareOwnerPhoto != null ? m.reelShareOwnerPhoto : "";
                    if (!avatarUrl.isEmpty()) {
                        com.bumptech.glide.Glide.with(ctx)
                                .load(avatarUrl)
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                .circleCrop()
                                .placeholder(android.R.drawable.ic_menu_camera)
                                .into(h.ivReelShareAvatar);
                    } else if (m.reelShareUsername != null && !m.reelShareUsername.isEmpty()) {
                        // Try to fetch avatar from Firebase users node by username
                        final VH fhA = h;
                        final Message fmA = m;
                        final android.content.Context fCtxA = ctx;
                        com.google.firebase.database.FirebaseDatabase.getInstance()
                            .getReference("users").orderByChild("username")
                            .equalTo(m.reelShareUsername)
                            .limitToFirst(1)
                            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                                    if (!snap.exists()) return;
                                    String photo = null;
                                    for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                                        photo = child.child("profileImage").getValue(String.class);
                                        if (photo == null || photo.isEmpty())
                                            photo = child.child("photoUrl").getValue(String.class);
                                        if (photo == null || photo.isEmpty())
                                            photo = child.child("profilePhoto").getValue(String.class);
                                        break;
                                    }
                                    if (photo != null && !photo.isEmpty() && fhA.ivReelShareAvatar != null) {
                                        fmA.reelShareOwnerPhoto = photo; // cache for next bind
                                        com.bumptech.glide.Glide.with(fCtxA)
                                                .load(photo)
                                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                                .circleCrop()
                                                .placeholder(android.R.drawable.ic_menu_camera)
                                                .into(fhA.ivReelShareAvatar);
                                    }
                                }
                                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                            });
                    }
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
                        com.bumptech.glide.Glide.with(ctx)
                                .load(thumb)
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                .centerCrop()
                                .placeholder(android.R.color.darker_gray)
                                .into(h.ivReelShareThumb);
                    } else if (m.reelId != null && !m.reelId.isEmpty()) {
                        // Thumb missing — fetch from Firebase
                        final VH fh = h;
                        final Message fm = m;
                        com.google.firebase.database.FirebaseDatabase.getInstance()
                            .getReference("reels").child(m.reelId)
                            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                                    if (!snap.exists()) return;
                                    String t = snap.child("thumbUrl").getValue(String.class);
                                    if (t == null || t.isEmpty())
                                        t = snap.child("thumbnailUrl").getValue(String.class);
                                    if (t != null && !t.isEmpty() && fh.ivReelShareThumb != null) {
                                        fm.reelShareThumb = t;
                                        com.bumptech.glide.Glide.with(ctx).load(t)
                                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                            .centerCrop()
                                            .into(fh.ivReelShareThumb);
                                    }
                                    String u = snap.child("ownerName").getValue(String.class);
                                    if (u == null || u.isEmpty())
                                        u = snap.child("username").getValue(String.class);
                                    if (u != null && !u.isEmpty() && fh.tvReelShareUsername != null)
                                        fh.tvReelShareUsername.setText("@" + u);
                                    // Also load avatar if not yet loaded
                                    String ap = snap.child("ownerPhoto").getValue(String.class);
                                    if (ap == null || ap.isEmpty())
                                        ap = snap.child("profileImage").getValue(String.class);
                                    if (ap != null && !ap.isEmpty() && fh.ivReelShareAvatar != null) {
                                        fm.reelShareOwnerPhoto = ap;
                                        com.bumptech.glide.Glide.with(ctx)
                                                .load(ap)
                                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                                .circleCrop()
                                                .placeholder(android.R.drawable.ic_menu_camera)
                                                .into(fh.ivReelShareAvatar);
                                    }
                                    String c = snap.child("caption").getValue(String.class);
                                    if (c != null && !c.isEmpty() && fh.tvReelShareCaption != null) {
                                        fh.tvReelShareCaption.setText(c);
                                        fh.tvReelShareCaption.setVisibility(View.VISIBLE);
                                    }
                                }
                                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                            });
                    }
                }
                // Tap to open reel
                final String fReelId  = m.reelId      != null ? m.reelId      : "";
                final String fReelUrl = m.reelShareUrl != null ? m.reelShareUrl : "";
                h.llReelShare.setOnClickListener(v -> {
                    String deepLink = !fReelId.isEmpty()
                            ? com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/reel/" + fReelId
                            : fReelUrl;
                    if (!deepLink.isEmpty()) {
                        try {
                            android.content.Intent ri = new android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(deepLink));
                            ri.setPackage(ctx.getPackageName());
                            ri.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(ri);
                        } catch (Exception ignored) {}
                    }
                });
                break;
            }
            default: // "text", "emoji", etc.
                h.tvMessage.setVisibility(View.VISIBLE);
                // ── Font Style — cached static Typeface, no allocation ────────
                h.tvMessage.setTypeface(TF_NORMAL);
                boolean isSentMsg = currentUid.equals(m.senderId);
                h.tvMessage.setAlpha(1f);
                h.tvMessage.setTextColor(
                    com.callx.app.utils.ChatThemeManager.get(ctx).getTextColor(isSentMsg));

                // ── Expandable text: WhatsApp-style "Read more / Read less" ───
                // State lives in expandedMessageIds (adapter-level HashSet) so it
                // survives PagingData refreshes from Room. The tvReadMore button
                // replaces the old ClickableSpan approach — plain OnClickListener
                // is 100% reliable inside RecyclerView.
                {
                    final String msgIdExp = m.messageId != null ? m.messageId : m.id;
                    final boolean isExpanded = msgIdExp != null
                            && expandedMessageIds.contains(msgIdExp);
                    final int bindPos = position;
                    com.callx.app.utils.ExpandableTextHelper.bind(
                            h.tvMessage,
                            h.tvReadMore,
                            m.text,
                            m,
                            msgIdExp,
                            isExpanded,
                            () -> { // onExpand — user tapped "Read more"
                                if (msgIdExp != null) expandedMessageIds.add(msgIdExp);
                                notifyItemChanged(bindPos);
                            },
                            () -> { // onCollapse — user tapped "Read less"
                                if (msgIdExp != null) expandedMessageIds.remove(msgIdExp);
                                notifyItemChanged(bindPos);
                            },
                            isSentMsg);
                }
                ++h.textBindToken; // invalidate any in-flight PrecomputedText for this VH

                // ── Search highlight ──────────────────────────────────────────
                // Overlay yellow spans on matched substrings while search is active.
                if (activeSearchQuery != null) {
                    applySearchHighlight(h.tvMessage, activeSearchQuery);
                }

                // ── Link preview (ViewStub lazy inflate) ─────────────────────
                // Hide stale link preview before checking for URL
                if (h.llLinkPreview != null) h.llLinkPreview.setVisibility(View.GONE);
                if (m.text != null) {
                    ensureLinkPreviewInflated(h, isSentMsg); // inflate once on demand
                }
                // POLISH: Link preview — detect URL, fetch OG data async, bind card
                if (h.llLinkPreview != null && m.text != null) {
                    // PERF: cache extracted URL in tvMessage tag — regex won't run again
                    // if same text is re-bound (e.g. after reaction/status update)
                    String cachedText = (String) h.tvMessage.getTag(R.id.tv_message);
                    String previewUrl;
                    if (m.text.equals(cachedText)) {
                        previewUrl = (String) h.llLinkPreview.getTag();
                        if (previewUrl == null) previewUrl = com.callx.app.utils.LinkPreviewFetcher.extractFirstUrl(m.text);
                    } else {
                        h.tvMessage.setTag(R.id.tv_message, m.text);
                        previewUrl = com.callx.app.utils.LinkPreviewFetcher.extractFirstUrl(m.text);
                    }
                    if (previewUrl != null) {
                        // Tag itemView with URL so we detect stale VH on recycle
                        h.llLinkPreview.setTag(previewUrl);
                        h.llLinkPreview.setVisibility(View.INVISIBLE); // reserve space while loading
                        // FIX: must be final for use inside anonymous inner class
                        final String finalPreviewUrl = previewUrl;
                        com.callx.app.utils.LinkPreviewFetcher.fetch(finalPreviewUrl,
                                new com.callx.app.utils.LinkPreviewFetcher.Callback() {
                            @Override public void onResult(com.callx.app.utils.LinkPreviewFetcher.Result r) {
                                // Guard against recycled VH
                                if (!finalPreviewUrl.equals(h.llLinkPreview.getTag())) return;
                                h.llLinkPreview.setVisibility(View.VISIBLE);
                                if (h.tvLinkDomain != null) h.tvLinkDomain.setText(r.domain);
                                if (h.tvLinkTitle  != null) h.tvLinkTitle.setText(r.title);
                                if (h.ivLinkThumb  != null) {
                                    if (r.imageUrl != null && !r.imageUrl.isEmpty()) {
                                        h.ivLinkThumb.setVisibility(View.VISIBLE);
                                        com.bumptech.glide.Glide.with(ctx)
                                            .load(r.imageUrl)
                                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                            .override(300, 300)
                                            .centerCrop()
                                            .into(h.ivLinkThumb);
                                    } else {
                                        h.ivLinkThumb.setVisibility(View.GONE);
                                    }
                                }
                                // Tapping the card opens the URL in browser
                                h.llLinkPreview.setOnClickListener(v -> {
                                    Intent browserIntent = new Intent(
                                            Intent.ACTION_VIEW, android.net.Uri.parse(r.url));
                                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    ctx.startActivity(browserIntent);
                                });
                            }
                            @Override public void onError(String url) {
                                if (!finalPreviewUrl.equals(h.llLinkPreview.getTag())) return;
                                h.llLinkPreview.setVisibility(View.GONE);
                            }
                        });
                    }
                }
                break;
        }

        // ── Delivery status (sent messages only) ─────────────────
        if (sent && h.tvStatus != null) {
            h.tvStatus.setVisibility(View.VISIBLE);
            String status = m.status != null ? m.status : "sent";
            switch (status) {
                case "seen":
                case "read":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTickColor(true));
                    break;
                case "delivered":
                    h.tvStatus.setText("✓✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTickColor(false));
                    break;
                case "pending":
                    // Clock icon — sent locally, not yet reached Firebase
                    h.tvStatus.setText("🕐");
                    h.tvStatus.setTextColor(0xFFAAAAAA);
                    break;
                case "failed":
                    // Error icon — Firebase push rejected; tap to retry
                    h.tvStatus.setText("⚠");
                    h.tvStatus.setTextColor(0xFFFF5555);
                    h.tvStatus.setOnClickListener(v -> {
                        if (actionListener != null) actionListener.onRetry(m);
                    });
                    break;
                default: // "sent" — one grey tick
                    h.tvStatus.setText("✓");
                    h.tvStatus.setTextColor(
                        com.callx.app.utils.ChatThemeManager.get(ctx).getTickColor(false));
                    h.tvStatus.setOnClickListener(null);
                    break;
            }
        } else if (h.tvStatus != null) {
            h.tvStatus.setVisibility(View.GONE);
        }

        // ── Disappearing message countdown ────────────────────────────────
        // PERF: shared ExpiryTickManager handler instead of a per-row CountDownTimer.
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
                        if (h.tvExpiry != null)
                            h.tvExpiry.setText("⏳ " + formatRemaining(ms));
                    }
                    @Override public void onFinish() {
                        if (h.tvExpiry != null) h.tvExpiry.setVisibility(View.GONE);
                    }
                });
            } else {
                h.tvExpiry.setVisibility(View.GONE);
            }
        }

        // ── Long press — multi-select mode ya action sheet ─────────────────
        h.itemView.setOnLongClickListener(v -> {
            // FIX: Haptic feedback on long press — production apps always do this
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
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
    // POLL BUBBLE (advanced) — icon-badge header, frosted card,
    // radio OR checkbox vote indicator (single- vs multi-choice),
    // animated fill bars, leading-option highlight, and a status chip
    // for Closed/Anonymous polls.
    // Layout (per-bubble): ll_poll > header(iv_poll_icon, tv_poll_icon_label,
    // tv_poll_status_badge), tv_poll_question, tv_poll_subtitle,
    // ll_poll_options, tv_poll_total_votes. Each option row is
    // item_poll_option_row.xml, inflated dynamically since option count
    // is variable (2–10).
    //
    // Multi-choice polls (Message#pollMultiChoice == true) let a voter
    // tick any number of options — tapping a ticked option un-ticks it,
    // tapping an un-ticked one adds it. Single-choice polls keep the
    // original radio behaviour: tapping any option replaces your vote.
    // ──────────────────────────────────────────────────────────────
    private void bindPoll(@NonNull VH h, @NonNull Message m, boolean sent) {
        Context ctx = h.itemView.getContext();
        h.llPoll.setVisibility(View.VISIBLE);

        int textColor = sent
                ? androidx.core.content.ContextCompat.getColor(ctx, R.color.bubble_sent_text)
                : androidx.core.content.ContextCompat.getColor(ctx, R.color.bubble_received_text);

        // Recolor the fixed-white header icon to match the bubble's text color
        // so it stays legible on both light and dark bubble/theme combinations.
        if (h.ivPollIcon != null) {
            h.ivPollIcon.setColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN);
        }

        if (h.tvPollQuestion != null) {
            h.tvPollQuestion.setText(m.pollQuestion != null ? m.pollQuestion : "");
        }

        boolean multiChoice = Boolean.TRUE.equals(m.pollMultiChoice);

        if (h.tvPollSubtitle != null) {
            h.tvPollSubtitle.setText(multiChoice ? "Select one or more answers" : "Select one answer");
            h.tvPollSubtitle.setTextColor((textColor & 0x00FFFFFF) | 0xAA000000);
        }

        java.util.List<String> options = m.pollOptions != null
                ? m.pollOptions : java.util.Collections.emptyList();
        java.util.Map<String, java.util.List<Integer>> votes = m.pollVotes != null
                ? m.pollVotes : java.util.Collections.emptyMap();
        int[] counts = com.callx.app.utils.PollJsonUtil.countVotes(votes, options.size());
        int total = com.callx.app.utils.PollJsonUtil.totalVotes(votes);
        java.util.List<Integer> myVotes = currentUid != null ? votes.get(currentUid) : null;
        if (myVotes == null) myVotes = java.util.Collections.emptyList();
        boolean closed = Boolean.TRUE.equals(m.pollClosed);
        boolean anonymous = Boolean.TRUE.equals(m.pollAnonymous);

        // Identify the leading option(s) so we can give them a subtle bold
        // treatment — but only when there IS a clear leader (skip when every
        // option is tied, including the all-zero-votes case).
        int maxCount = 0;
        for (int c : counts) if (c > maxCount) maxCount = c;
        boolean hasClearLeader = maxCount > 0;
        if (hasClearLeader) {
            int countAtMax = 0;
            for (int c : counts) if (c == maxCount) countAtMax++;
            if (countAtMax == options.size()) hasClearLeader = false;
        }

        if (h.llPollOptions != null) {
            // PERF: recycle existing option rows instead of removeAllViews + inflate
            int optCount = options.size();
            while (h.llPollOptions.getChildCount() > optCount) {
                h.llPollOptions.removeViewAt(h.llPollOptions.getChildCount() - 1);
            }
            for (int i = 0; i < optCount; i++) {
                final int optionIndex = i;
                View row;
                PollOptionRowViews rowViews;
                if (i < h.llPollOptions.getChildCount()) {
                    row = h.llPollOptions.getChildAt(i);
                    rowViews = (PollOptionRowViews) row.getTag();
                } else {
                    row = LayoutInflater.from(ctx)
                            .inflate(R.layout.item_poll_option_row, h.llPollOptions, false);
                    h.llPollOptions.addView(row);
                    rowViews = null;
                }
                // PERF: cache the 4 child views on the row itself via setTag().
                // findViewById() walks the row's view tree; with rows being
                // reused across binds (recycling, above) we'd otherwise pay
                // that walk cost every single bind even though the row's
                // structure never changes after first inflation.
                if (rowViews == null) {
                    rowViews = new PollOptionRowViews(row);
                    row.setTag(rowViews);
                }
                TextView  tvText  = rowViews.tvText;
                TextView  tvPct   = rowViews.tvPct;
                ImageView ivCheck = rowViews.ivCheck;
                View      vFill   = rowViews.vFill;

                int pct = total > 0 ? Math.round((counts[i] * 100f) / total) : 0;
                boolean isMyVote  = myVotes.contains(optionIndex);
                boolean isLeading = hasClearLeader && counts[i] == maxCount;

                if (tvText != null) {
                    tvText.setText(options.get(i));
                    tvText.setTextColor(textColor);
                    tvText.setTypeface(null, isLeading ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                }
                if (tvPct != null) {
                    tvPct.setText(total > 0 ? (pct + "%") : "");
                    tvPct.setTextColor(textColor);
                }
                if (ivCheck != null) {
                    int filledRes     = multiChoice ? R.drawable.ic_poll_checkbox_filled     : R.drawable.ic_poll_check_filled;
                    int unselectedRes = multiChoice ? R.drawable.ic_poll_checkbox_unselected  : R.drawable.ic_poll_radio_unselected;
                    if (isMyVote) {
                        ivCheck.clearColorFilter();
                        ivCheck.setImageResource(filledRes);
                    } else {
                        ivCheck.setImageResource(unselectedRes);
                        int ringColor = (textColor & 0x00FFFFFF) | 0x80000000;
                        ivCheck.setColorFilter(ringColor, android.graphics.PorterDuff.Mode.SRC_IN);
                    }
                }

                row.setBackgroundResource(isMyVote
                        ? R.drawable.bg_poll_option_voted
                        : R.drawable.bg_poll_option);
                if (vFill != null) {
                    vFill.setBackgroundResource(isMyVote
                            ? R.drawable.bg_poll_option_fill_voted
                            : R.drawable.bg_poll_option_fill);

                    final View fillView = vFill;
                    final int fPct = pct;
                    vFill.post(() -> {
                        Object parentObj = fillView.getParent();
                        if (!(parentObj instanceof View)) return;
                        int parentWidth = ((View) parentObj).getWidth();
                        if (parentWidth <= 0) return;
                        int targetWidth = Math.round(parentWidth * (fPct / 100f));
                        android.view.ViewGroup.LayoutParams lp = fillView.getLayoutParams();
                        lp.width = targetWidth;
                        fillView.setLayoutParams(lp);
                        fillView.setTag(targetWidth);
                    });
                }

                row.setOnClickListener(v -> {
                    if (closed) {
                        android.widget.Toast.makeText(ctx, "This poll is closed", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (actionListener != null) actionListener.onPollVote(m, optionIndex);
                });
            }
        }

        if (h.tvPollTotalVotes != null) {
            String label = total == 0 ? "No votes yet" : total + (total == 1 ? " person voted" : " people voted");
            h.tvPollTotalVotes.setText(label);
        }

        // Status chip — shows the single most relevant state: Closed takes
        // priority over Anonymous since it affects whether the user can vote.
        if (h.tvPollStatusBadge != null) {
            if (closed) {
                h.tvPollStatusBadge.setText("🔒 Closed");
                h.tvPollStatusBadge.setBackgroundResource(R.drawable.bg_poll_chip_closed);
                h.tvPollStatusBadge.setVisibility(View.VISIBLE);
            } else if (anonymous) {
                h.tvPollStatusBadge.setText("🙈 Anonymous");
                h.tvPollStatusBadge.setBackgroundResource(R.drawable.bg_poll_chip_neutral);
                h.tvPollStatusBadge.setVisibility(View.VISIBLE);
            } else {
                h.tvPollStatusBadge.setVisibility(View.GONE);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Audio playback toggle
    // ──────────────────────────────────────────────────────────────
    private void toggleAudio(@NonNull VH h, String url, int position) {
        if (playingPos == position && player != null && player.isPlaying()) {
            player.pause();
            if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
            notifyPlaybackChanged(getItem(position), false);
            return;
        }
        if (playingPos != -1 && playingPos != position) {
            // Switching to a different bubble mid-playback — tell the
            // partner we stopped listening to the OLD one before the new
            // one's "started playing" callback fires.
            notifyPlaybackChanged(getItem(playingPos), false);
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
            // FIX [P3-1]: Reset previous VH UI so two bubbles don't show "pause" at the same time
            if (playingVH != null && playingVH != h) {
                seekHandler.removeCallbacks(seekUpdater);
                if (playingVH.btnPlayPause != null)
                    playingVH.btnPlayPause.setImageResource(R.drawable.ic_play);
                if (playingVH.seekAudio != null) playingVH.seekAudio.setProgress(0f);
            }
            if (player != null) { try { player.release(); } catch (Exception ignored) {} }
            player = new MediaPlayer();
            playingVH = h;
            
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
                notifyPlaybackChanged(getItem(position), true);
                // FIX: SeekBar live progress update — runs every 250ms while playing
                if (h.seekAudio != null) {
                    final int durationMs = mp.getDuration();
                    seekHandler.removeCallbacks(seekUpdater);
                    seekUpdater = new Runnable() {
                        @Override public void run() {
                            if (player != null && player.isPlaying()) {
                                int cur = player.getCurrentPosition();
                                if (durationMs > 0) h.seekAudio.setProgress((float) cur / durationMs);
                                // Update duration label if present
                                if (h.tvAudioDur != null) {
                                    long sec = cur / 1000;
                                    h.tvAudioDur.setText(String.format(
                                        java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60));
                                }
                                seekHandler.postDelayed(this, 250);
                            }
                        }
                    };
                    seekHandler.post(seekUpdater);
                    // Allow user to scrub
                    h.seekAudio.setOnSeekListener(fraction -> {
                        if (player != null && durationMs > 0) player.seekTo((int) (fraction * durationMs));
                    });
                }
            });
            player.setOnCompletionListener(mp -> {
                notifyPlaybackChanged(getItem(position), false);
                playingPos = -1;
                seekHandler.removeCallbacks(seekUpdater);
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
                if (h.seekAudio != null) h.seekAudio.setProgress(0f);
                try { mp.release(); } catch (Exception ignored) {}
                player = null;
            });
            player.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.e("AudioPlay", "Error: " + what + " extra: " + extra + " path: " + path);
                notifyPlaybackChanged(getItem(position), false);
                playingPos = -1;
                if (h.btnPlayPause != null) h.btnPlayPause.setImageResource(R.drawable.ic_play);
                return true;
            });
        } catch (Exception e) {
            android.util.Log.e("AudioPlay", "playAudioFromPath error: " + e.getMessage() + " path: " + path);
            if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
        }
    }

    /** Funnels a local audio play/pause/finish/error event out through
     *  ActionListener#onPlaybackStateChanged so ChatPlaybackPresenceController
     *  can publish (or clear) the chatPlayback/{chatId}/{uid} node. */
    private void notifyPlaybackChanged(Message m, boolean playing) {
        if (actionListener != null && m != null) actionListener.onPlaybackStateChanged(m, playing);
    }

    // ──────────────────────────────────────────────────────────────
    // Long-press bottom sheet actions
    // ──────────────────────────────────────────────────────────────
    // ── WhatsApp-style image action bottom sheet ──────────────────
    private void showImageActionSheet(Context ctx, Message m, String fullUrl, String thumbForViewer) {
        com.google.android.material.bottomsheet.BottomSheetDialog bsd =
                new com.google.android.material.bottomsheet.BottomSheetDialog(ctx);

        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"));

        // Drag handle
        android.widget.FrameLayout handleWrap = new android.widget.FrameLayout(ctx);
        android.view.View handle = new android.view.View(ctx);
        int dp4  = dp(ctx, 4);
        int dp36 = dp(ctx, 36);
        int dp5  = dp(ctx, 5);
        android.widget.FrameLayout.LayoutParams handleLp =
                new android.widget.FrameLayout.LayoutParams(dp36, dp4);
        handleLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        handle.setLayoutParams(handleLp);
        handle.setBackgroundColor(android.graphics.Color.parseColor("#555555"));
        android.view.ViewGroup.MarginLayoutParams hlm = (android.view.ViewGroup.MarginLayoutParams) handle.getLayoutParams();
        hlm.topMargin = dp5;
        hlm.bottomMargin = dp5;
        handleWrap.setPadding(0, dp5, 0, dp5);
        handleWrap.addView(handle);
        root.addView(handleWrap);

        // Options: View, Share, Forward, Star, Delete
        String[] labels  = {"🖼  View",  "↗  Share", "↪  Forward", "⭐  Star", "🗑  Delete"};
        int[]    colors  = {0xFFFFFFFF,  0xFFFFFFFF,  0xFFFFFFFF,  0xFFFFFFFF,  0xFFFF5252 };

        boolean isOwnMsg = currentUid != null && currentUid.equals(m.senderId);

        for (int idx = 0; idx < labels.length; idx++) {
            // Skip Delete if not own message
            if (labels[idx].contains("Delete") && !isOwnMsg) continue;

            android.widget.TextView tv = new android.widget.TextView(ctx);
            tv.setText(labels[idx]);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            tv.setTextColor(colors[idx]);
            tv.setPadding(dp(ctx, 20), dp(ctx, 15), dp(ctx, 20), dp(ctx, 15));
            tv.setBackground(getRippleDrawable(ctx));

            final String label = labels[idx];
            tv.setOnClickListener(v -> {
                bsd.dismiss();
                switch (label) {
                    case "🖼  View":
                        android.content.Intent i = new android.content.Intent()
                                .setClassName(ctx.getPackageName(),
                                        "com.callx.app.activities.MediaViewerActivity");
                        i.putExtra("url",      fullUrl);
                        i.putExtra("thumbUrl", thumbForViewer);
                        i.putExtra("type",     "image");
                        ctx.startActivity(i);
                        break;
                    case "↗  Share":
                        android.content.Intent share = new android.content.Intent(
                                android.content.Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(android.content.Intent.EXTRA_TEXT, fullUrl);
                        ctx.startActivity(android.content.Intent.createChooser(share, "Share via"));
                        break;
                    case "↪  Forward":
                        if (actionListener != null) actionListener.onForward(m);
                        break;
                    case "⭐  Star":
                        if (actionListener != null) actionListener.onStar(m);
                        break;
                    case "🗑  Delete":
                        if (actionListener != null) actionListener.onDelete(m);
                        break;
                }
            });
            root.addView(tv);

            // Divider (not after last)
            if (idx < labels.length - 1 && !(idx == labels.length - 2 && !isOwnMsg)) {
                android.view.View div = new android.view.View(ctx);
                android.widget.LinearLayout.LayoutParams dlp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dlp.setMarginStart(dp(ctx, 20));
                div.setLayoutParams(dlp);
                div.setBackgroundColor(android.graphics.Color.parseColor("#333333"));
                root.addView(div);
            }
        }

        root.setPadding(0, 0, 0, dp(ctx, 16));
        bsd.setContentView(root);
        // Dark bottom sheet
        if (bsd.getWindow() != null) {
            bsd.getWindow().setNavigationBarColor(android.graphics.Color.parseColor("#1E1E1E"));
        }
        bsd.show();
    }

    private int dp(Context ctx, int value) {
        return (int)(value * ctx.getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.Drawable getRippleDrawable(Context ctx) {
        android.graphics.drawable.ColorDrawable content =
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);
        android.content.res.ColorStateList rippleColor =
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#33FFFFFF"));
        return new android.graphics.drawable.RippleDrawable(rippleColor, content, null);
    }

    private void showActionBottomSheet(Context ctx, Message m) {
        if (actionListener == null) return;

        // ── Step 1: Build emoji reaction row ──────────────────────────
        String[] QUICK_EMOJIS = {"\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE02",
                                  "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE21"};
        android.widget.LinearLayout emojiRow = new android.widget.LinearLayout(ctx);
        emojiRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        emojiRow.setGravity(android.view.Gravity.CENTER);
        int hPad = (int)(8 * ctx.getResources().getDisplayMetrics().density);
        int vPad = (int)(12 * ctx.getResources().getDisplayMetrics().density);
        emojiRow.setPadding(hPad, vPad, hPad, vPad);

        // Wrap in a container so AlertDialog can host it as a custom title
        android.widget.LinearLayout wrapper = new android.widget.LinearLayout(ctx);
        wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrapper.addView(emojiRow);

        // Keep a dialog reference so emoji tap can dismiss it
        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];

        for (String emoji : QUICK_EMOJIS) {
            android.widget.TextView tv = new android.widget.TextView(ctx);
            tv.setText(emoji);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28);
            int btnPad = (int)(10 * ctx.getResources().getDisplayMetrics().density);
            tv.setPadding(btnPad, btnPad / 2, btnPad, btnPad / 2);
            // Highlight whichever emoji the current user already reacted with —
            // tapping it again removes the reaction (see ChatReactionController#toggleReaction).
            boolean already = currentUid != null && m.reactions != null
                    && emoji.equals(m.reactions.get(currentUid));
            tv.setAlpha(already ? 1.0f : 0.7f);
            tv.setScaleX(already ? 1.25f : 1.0f);
            tv.setScaleY(already ? 1.25f : 1.0f);
            tv.setOnClickListener(v -> {
                actionListener.onReact(m, emoji);
                if (holder[0] != null) holder[0].dismiss();
            });
            emojiRow.addView(tv);
        }

        // FIX [P3-3]: "+" button → full emoji picker dialog (was dead/missing before)
        android.widget.TextView btnMore = new android.widget.TextView(ctx);
        btnMore.setText("+");
        btnMore.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        btnMore.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int morePad = (int)(10 * ctx.getResources().getDisplayMetrics().density);
        btnMore.setPadding(morePad, morePad / 2, morePad, morePad / 2);
        btnMore.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            showFullEmojiPicker(ctx, m);
        });
        emojiRow.addView(btnMore);

        // ── Step 2: Build action items list ───────────────────────────
        boolean isOwnMsg     = currentUid != null && currentUid.equals(m.senderId);
        boolean isTextMsg    = m.text != null && !m.text.trim().isEmpty()
                               && (m.type == null || "text".equals(m.type));
        boolean canEdit      = isOwnMsg && isTextMsg;
        boolean isStarred    = Boolean.TRUE.equals(m.starred);

        boolean isPinned = Boolean.TRUE.equals(m.pinned);
        boolean isPoll   = "poll".equals(m.type);
        boolean isPollClosed = Boolean.TRUE.equals(m.pollClosed);
        boolean hasEditHistory = Boolean.TRUE.equals(m.edited);

        java.util.List<String> optList = new java.util.ArrayList<>();
        optList.add("Reply");
        optList.add("Copy");
        optList.add(isStarred ? "Unstar" : "Star");
        optList.add(isPinned ? "Unpin" : "Pin");
        optList.add("Forward");
        if (canEdit) optList.add("Edit");
        if (hasEditHistory) optList.add("Edit history");
        if (isPoll && isOwnMsg) optList.add(isPollClosed ? "Reopen Poll" : "Close Poll");
        optList.add("Delete");
        String[] options = optList.toArray(new String[0]);

        android.app.AlertDialog.Builder builder =
                new android.app.AlertDialog.Builder(ctx)
                    .setCustomTitle(wrapper)
                    .setItems(options, (d, which) -> {
                        String choice = options[which];
                        switch (choice) {
                            case "Reply":   actionListener.onReply(m);   break;
                            case "Copy":    actionListener.onCopy(m);    break;
                            case "Star":    // fall-through
                            case "Unstar":  actionListener.onStar(m);    break;
                            case "Pin":     // fall-through
                            case "Unpin":   actionListener.onPin(m);     break;
                            case "Forward": actionListener.onForward(m); break;
                            case "Edit":    actionListener.onEdit(m);    break;
                            case "Edit history": actionListener.onShowEditHistory(m); break;
                            case "Close Poll":  // fall-through
                            case "Reopen Poll": actionListener.onPollToggleClose(m); break;
                            case "Delete":  actionListener.onDelete(m);  break;
                        }
                    });
        holder[0] = builder.show();
    }

    // FIX [P3-3]: Full emoji picker — 8-column scrollable grid of common emojis
    private void showFullEmojiPicker(Context ctx, Message m) {
        if (actionListener == null) return;
        final String[] ALL_EMOJIS = {
            "❤️","👍","😂","😮","😢","😡","🙏","🔥","✅","💯",
            "👏","🤣","😍","😎","🤔","😴","🥳","😅","🤩","🥰",
            "💀","🤯","😱","🤗","😇","🙄","😑","🤐","🫡","💪",
            "👀","✌️","🤞","🫶","❤️‍🔥","💔","💕","💖","💘","🫂",
            "🎉","🎊","🎈","🏆","⭐","🌟","💫","✨","🌈","☀️",
            "😁","😆","🤭","😜","😝","🥹","🥺","😭","😤","😠",
            "👋","🤙","🖐️","✋","👊","🫸","💅","🫰","👌","🤌",
            "🙌","🤜","🤛","🫵","☝️","👈","👉","👆","👇","🤷"
        };
        android.widget.GridView grid = new android.widget.GridView(ctx);
        grid.setNumColumns(8);
        grid.setPadding(12, 12, 12, 12);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(
                ctx, android.R.layout.simple_list_item_1, ALL_EMOJIS) {
            @Override public android.view.View getView(int pos, android.view.View cv, android.view.ViewGroup parent) {
                android.widget.TextView tv = (android.widget.TextView)
                        super.getView(pos, cv, parent);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setPadding(4, 8, 4, 8);
                return tv;
            }
        };
        grid.setAdapter(adapter);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ctx)
                .setTitle("Pick an emoji")
                .setView(grid)
                .create();
        grid.setOnItemClickListener((parent, v, pos, id) -> {
            actionListener.onReact(m, ALL_EMOJIS[pos]);
            dialog.dismiss();
        });
        dialog.show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ViewStub inflation helpers — one-time inflate + child ref caching.
    //  Each method is a no-op after first call (h.flVideo != null, etc.).
    // ══════════════════════════════════════════════════════════════════════════

    private void ensureVideoInflated(@NonNull VH h) {
        if (h.flVideo != null || h.stubVideo == null) return;
        h.stubVideo.inflate(); h.stubVideo = null;
        h.flVideo      = h.itemView.findViewById(R.id.fl_video);
        h.ivVideoThumb = h.itemView.findViewById(R.id.iv_video_thumb);
        h.tvDuration   = h.itemView.findViewById(R.id.tv_duration);
    }

    private void ensureAudioInflated(@NonNull VH h, Context ctx, boolean sent) {
        if (h.llAudio != null || h.stubAudio == null) return;
        h.stubAudio.inflate(); h.stubAudio = null;
        h.llAudio      = h.itemView.findViewById(R.id.ll_audio);
        h.btnPlayPause = h.itemView.findViewById(R.id.btn_play_pause);
        h.seekAudio    = h.itemView.findViewById(R.id.seek_audio);
        h.tvAudioDur   = h.itemView.findViewById(R.id.tv_audio_dur);
        if (h.tvAudioDur != null) {
            try {
                int color = ctx.getResources().getColor(
                        sent ? R.color.bubble_sent_text : R.color.bubble_received_text);
                h.tvAudioDur.setTextColor(color);
            } catch (Exception ignored) {}
        }
    }

    private void ensureFileInflated(@NonNull VH h, Context ctx, boolean sent) {
        if (h.llFile != null || h.stubFile == null) return;
        h.stubFile.inflate(); h.stubFile = null;
        h.llFile      = h.itemView.findViewById(R.id.ll_file);
        h.tvFileName  = h.itemView.findViewById(R.id.tv_file_name);
        h.btnDownload = h.itemView.findViewById(R.id.btn_download);
    }

    private void ensurePollInflated(@NonNull VH h) {
        if (h.llPoll != null || h.stubPoll == null) return;
        h.stubPoll.inflate(); h.stubPoll = null;
        h.llPoll            = h.itemView.findViewById(R.id.ll_poll);
        h.llPollOptions     = h.itemView.findViewById(R.id.ll_poll_options);
        h.tvPollQuestion    = h.itemView.findViewById(R.id.tv_poll_question);
        h.tvPollTotalVotes  = h.itemView.findViewById(R.id.tv_poll_total_votes);
        h.tvPollStatusBadge = h.itemView.findViewById(R.id.tv_poll_status_badge);
        h.tvPollSubtitle    = h.itemView.findViewById(R.id.tv_poll_subtitle);
        h.ivPollIcon        = h.itemView.findViewById(R.id.iv_poll_icon);
    }

    private void ensureLinkPreviewInflated(@NonNull VH h, boolean sent) {
        if (h.llLinkPreview != null || h.stubLinkPreview == null) return;
        h.stubLinkPreview.inflate(); h.stubLinkPreview = null;
        h.llLinkPreview = h.itemView.findViewById(R.id.ll_link_preview);
        h.tvLinkTitle   = h.itemView.findViewById(R.id.tv_link_title);
        h.tvLinkDomain  = h.itemView.findViewById(R.id.tv_link_domain);
        h.ivLinkThumb   = h.itemView.findViewById(R.id.iv_link_thumb);
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        // FIX #3: Clear ALL ImageViews on recycle — not just ivImage.
        // Missing clears on ivReplyThumb/ivLinkThumb/ivVideoThumb/ivStatusSeenThumb/ivReelSeenThumb
        // caused Glide memory leaks and stale image flicker on fast scrolling.
        Context ctx = holder.itemView.getContext();
        if (holder.ivImage           != null) Glide.with(ctx).clear(holder.ivImage);
        if (holder.ivReplyThumb      != null) Glide.with(ctx).clear(holder.ivReplyThumb);
        if (holder.ivLinkThumb       != null) Glide.with(ctx).clear(holder.ivLinkThumb);
        if (holder.ivVideoThumb      != null) Glide.with(ctx).clear(holder.ivVideoThumb);
        if (holder.ivStatusSeenThumb != null) Glide.with(ctx).clear(holder.ivStatusSeenThumb);
        if (holder.ivReelSeenThumb   != null) Glide.with(ctx).clear(holder.ivReelSeenThumb);
        // Stop any pending tick updates from the shared manager to prevent leaks on recycled views
        com.callx.app.utils.ExpiryTickManager.get().unregister(holder);
        // Invalidate any in-flight async PrecomputedText work for this
        // holder — it may still be running on TEXT_PRECOMPUTE_EXECUTOR
        // when the holder goes back into the pool. The posted callback
        // checks textBindToken before applying, so bumping it here makes
        // any such result a guaranteed no-op even if it lands while the
        // holder is sitting unused in the pool.
        holder.textBindToken++;
    }

    // PERF: onViewDetachedFromWindow — called when the ViewHolder scrolls off
    // the screen but hasn't been recycled yet. Stop audio playback for this
    // holder so a voice note playing in a bubble that scrolled off-screen stops
    // immediately rather than continuing silently (and tying up the MediaPlayer).
    @Override
    public void onViewDetachedFromWindow(@NonNull VH holder) {
        super.onViewDetachedFromWindow(holder);
        // If THIS holder was the playing one, stop audio
        if (player != null && playingVH == holder) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
            playingPos = -1;
            playingVH  = null;
            if (seekUpdater != null) { seekHandler.removeCallbacks(seekUpdater); seekUpdater = null; }
        }
        // Stop any in-progress link-preview load for this row — the URL tag
        // was already used as a stale-result guard in the fetch callback, but
        // clearing it here prevents a VISIBLE slot from showing a loading card
        // for a URL that belongs to a different (now-recycled) message.
        if (holder.llLinkPreview != null) holder.llLinkPreview.setTag(null);
    }

    // ── Disappearing messages — format remaining time ─────────────────────
    private static String formatRemaining(long ms) {
        long secs  = ms / 1000;
        long mins  = secs / 60;
        long hours = mins / 60;
        long days  = hours / 24;
        if (days  > 0) return days  + "d";
        if (hours > 0) return hours + "h";
        if (mins  > 0) return mins  + "m";
        return secs + "s";
    }

    // ──────────────────────────────────────────────────────────────
    // Font Style — always default (typing style system removed)
    private static void applyFontStyle(android.widget.TextView tv, int styleId) {
        tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL));
    }

    // ──────────────────────────────────────────────────────────────
    // ViewHolder — covers all view IDs used in both item layouts
    // ──────────────────────────────────────────────────────────────
    /** Fast-path: rebind only the tick icon, called from payload-aware onBind. */
    private void bindStatusTick(@NonNull VH h, @NonNull Message m) {
        boolean sent = currentUid != null && currentUid.equals(m.senderId);
        if (!sent || h.tvStatus == null) {
            if (h.tvStatus != null) h.tvStatus.setVisibility(View.GONE);
            return;
        }
        h.tvStatus.setVisibility(View.VISIBLE);
        String s = m.status == null ? "" : m.status;
        switch (s) {
            case "read":
                h.tvStatus.setText("✓✓");
                h.tvStatus.setTextColor(0xFF4FC3F7);
                h.tvStatus.setOnClickListener(null);
                break;
            case "delivered":
                h.tvStatus.setText("✓✓");
                h.tvStatus.setTextColor(0xAAFFFFFF);
                h.tvStatus.setOnClickListener(null);
                break;
            case "sending":
                h.tvStatus.setText("🕐");
                h.tvStatus.setTextColor(0xFFAAAAAA);
                h.tvStatus.setOnClickListener(null);
                break;
            case "failed":
                h.tvStatus.setText("⚠");
                h.tvStatus.setTextColor(0xFFFF5555);
                break;
            default:
                h.tvStatus.setText("✓");
                h.tvStatus.setTextColor(0xAAFFFFFF);
                h.tvStatus.setOnClickListener(null);
                break;
        }
    }

    /** Fast-path: rebind ONLY the three presence-driven views — the
     *  "someone's viewing this" dot, the "someone's playing this" badge,
     *  and the "someone's replying to this" bubble glow. Called from the
     *  payload-aware onBind for PAYLOAD_PRESENCE, so a viewing/typing/
     *  playback broadcast no longer re-runs the whole bindMessage() (no
     *  Glide reload, no Linkify, no new GradientDrawable, no countdown
     *  restart) just to flip a dot. Logic mirrors the equivalent blocks
     *  inside bindMessage() exactly — keep both in sync if either changes. */
    private void bindPresenceOnly(@NonNull VH h, @NonNull Message m) {
        String mid = m.messageId != null ? m.messageId : m.id;

        if (h.viewSeenDot != null) {
            boolean viewing = mid != null && currentlyViewedMessageIds.contains(mid);
            h.viewSeenDot.setVisibility(viewing ? View.VISIBLE : View.GONE);
        }

        if (h.tvListeningBadge != null) {
            boolean playing = mid != null && currentlyPlayingMessageIds.contains(mid);
            if (playing) {
                boolean isVideoMsg = "video".equals(m.type);
                h.tvListeningBadge.setText(isVideoMsg ? "▶ watching…" : "🎧 listening…");
            }
            h.tvListeningBadge.setVisibility(playing ? View.VISIBLE : View.GONE);
        }

        if (h.llBubble != null) {
            boolean isReplyTarget = mid != null && replyTargetMessageIds.contains(mid);
            h.llBubble.setForeground(isReplyTarget
                    ? ContextCompat.getDrawable(h.itemView.getContext(), R.drawable.bg_reply_target_highlight)
                    : null);
        }
    }

    /** PERF: tiny view-cache for a poll option row, stashed via row.setTag().
     *  See bindPoll() — avoids repeat findViewById() on every rebind of a
     *  recycled poll-option row. */
    private static final class PollOptionRowViews {
        final TextView  tvText;
        final TextView  tvPct;
        final ImageView ivCheck;
        final View      vFill;
        PollOptionRowViews(View row) {
            tvText  = row.findViewById(R.id.tv_poll_option_text);
            tvPct   = row.findViewById(R.id.tv_poll_option_pct);
            ivCheck = row.findViewById(R.id.iv_poll_option_check);
            vFill   = row.findViewById(R.id.v_poll_option_fill);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView     tvMessage, tvTime, tvSenderName, tvFileName;
        TextView     tvDateHeader;   // date separator chip (Today / Yesterday / MMM d)
        ImageView    ivImage;
        TextView     tvStatus;   // tv_status in both item layouts

        // ASYNC PrecomputedTextCompat staleness guard — bumped on every
        // full text-bind AND on recycle (see onViewRecycled). A pending
        // background precompute result is only applied if this still
        // matches the token it captured at dispatch time.
        volatile int textBindToken = 0;

        // ── ViewStub refs — each replaced in-place on first inflate ──────────
        // After inflate() the stub removes itself from the view tree;
        // we null the field to signal "already inflated" to ensure*Inflated().
        android.view.ViewStub stubVideo;
        android.view.ViewStub stubAudio;
        android.view.ViewStub stubFile;
        android.view.ViewStub stubPoll;
        android.view.ViewStub stubLinkPreview;
        android.view.ViewStub stubReelShare;

        // ── Heavy view refs — null until their stub is inflated ──────────────
        LinearLayout llAudio, llFile;
        ImageButton  btnPlayPause;
        ImageView    btnDownload;
        // FIX: AudioWaveformView — pre-rendered bitmap waveform, wired for live progress updates
        com.callx.app.chat.ui.AudioWaveformView seekAudio;
        TextView     tvAudioDur;
        // SwipeReplySystem v1: reply preview views
        LinearLayout llReplyPreview;
        TextView     tvReplySender, tvReplyText;
        ImageView    ivReplyThumb;
        // Reactions row (ll_reactions / tv_reactions in both item layouts)
        LinearLayout llReactions;
        TextView     tvReactions;
        // "Read more / Read less" dedicated button — below the FrameLayout in ll_bubble
        TextView     tvReadMore;
        // POLISH: Video — proper FrameLayout with thumbnail + play overlay
        android.widget.FrameLayout flVideo;
        ImageView    ivVideoThumb;
        TextView     tvDuration;
        // POLISH: Link preview card — visible only for text messages with URLs
        LinearLayout llLinkPreview;
        TextView     tvLinkTitle, tvLinkDomain;
        ImageView    ivLinkThumb;
        // ── Reel share card ──
        LinearLayout llReelShare;
        ImageView    ivReelShareThumb;
        ImageView    ivReelShareAvatar;
        TextView     tvReelShareUsername, tvReelShareCaption;
        // ── Disappearing messages ──
        TextView                  tvExpiry;
        // ── Polls ──
        LinearLayout llPoll, llPollOptions;
        TextView     tvPollQuestion, tvPollTotalVotes, tvPollStatusBadge, tvPollSubtitle;
        ImageView    ivPollIcon;
        // "Currently viewing this message" live dot (per-message granularity)
        View         viewSeenDot;
        // "Currently playing this voice note / video" live badge (sibling
        // of viewSeenDot above, but for playback instead of scroll position)
        TextView     tvListeningBadge;
        // PERF: bubble background container — was looked up via
        // itemView.findViewById() on every single bindMessage() call (i.e.
        // every text/image/audio row, every bind). Cached here instead since
        // it's the hottest path in the adapter.
        View         llBubble;
        // ── Quick Forward Button ──
        android.widget.ImageButton btnQuickForward;

        // PERF: the following are for the rarer system-row layouts
        // (status_seen / reel_seen / call_entry). They used to be looked
        // up with itemView.findViewById() inside the bind*Bubble() methods
        // on every bind. Cached here instead — null on layouts that don't
        // contain them, which is fine since those bind methods are never
        // called for the wrong view type.
        de.hdodenhof.circleimageview.CircleImageView ivStatusSeenAvatar;
        View      flStatusSeenThumb;
        ImageView ivStatusSeenThumb, ivStatusSeenEye;
        TextView  tvStatusSeenName, tvStatusSeenTime;

        de.hdodenhof.circleimageview.CircleImageView ivReelSeenAvatar;
        View      flReelSeenThumb;
        ImageView ivReelSeenThumb, ivReelSeenPlay;
        TextView  tvReelSeenName, tvReelSeenTime;

        TextView  tvCallEntryIcon, tvCallEntryLabel, tvCallEntryTime;
        View      llCallEntryRoot, llCallEntryPill;

        VH(@NonNull View v) {
            super(v);
            tvMessage      = v.findViewById(R.id.tv_message);
            tvTime         = v.findViewById(R.id.tv_time);
            tvSenderName   = v.findViewById(R.id.tv_sender_name);
            tvDateHeader   = null; // removed from item layouts — date chip is now a separate ViewHolder type
            ivImage        = v.findViewById(R.id.iv_image);
            tvStatus       = v.findViewById(R.id.tv_status);
            // ── ViewStub bindings — heavy child layouts inflate only on demand ──
            stubVideo       = v.findViewById(R.id.stub_video);
            stubAudio       = v.findViewById(R.id.stub_audio);
            stubFile        = v.findViewById(R.id.stub_file);
            stubPoll        = v.findViewById(R.id.stub_poll);
            stubLinkPreview = v.findViewById(R.id.stub_link_preview);
            stubReelShare   = v.findViewById(R.id.stub_reel_share);
            // Heavy child refs start null; populated by ensure*Inflated() below
            llAudio = null; btnPlayPause = null; seekAudio = null; tvAudioDur = null;
            llFile  = null; tvFileName   = null; btnDownload = null;
            flVideo = null; ivVideoThumb = null; tvDuration  = null;
            llLinkPreview = null; tvLinkTitle = null; tvLinkDomain = null; ivLinkThumb = null;
            llPoll = null; llPollOptions = null; tvPollQuestion = null;
            tvPollTotalVotes = null; tvPollStatusBadge = null; tvPollSubtitle = null;
            ivPollIcon = null;
            // SwipeReplySystem v1
            llReplyPreview = v.findViewById(R.id.ll_reply_preview);
            tvReplySender  = v.findViewById(R.id.tv_reply_sender);
            tvReplyText    = v.findViewById(R.id.tv_reply_text);
            ivReplyThumb   = v.findViewById(R.id.iv_reply_thumb);
            // Reactions
            llReactions    = v.findViewById(R.id.ll_reactions);
            tvReactions    = v.findViewById(R.id.tv_reactions);
            // Read more / Read less — null on system-row layouts; always checked before use
            tvReadMore     = v.findViewById(R.id.tv_read_more);
            // Disappearing messages
            tvExpiry       = v.findViewById(R.id.tv_expiry);
            // PERF: these are rarely shown — GONE by default avoids measure cost
            if (tvExpiry != null) tvExpiry.setVisibility(android.view.View.GONE);
            viewSeenDot       = v.findViewById(R.id.view_seen_dot);
            tvListeningBadge  = v.findViewById(R.id.tv_listening_badge);
            llBubble          = v.findViewById(R.id.ll_bubble);
            btnQuickForward   = v.findViewById(R.id.btn_quick_forward);

            // System-row layouts (status_seen / reel_seen / call_entry) —
            // these IDs only exist on their respective layouts, so they'll
            // simply resolve to null on the other view types. That's fine:
            // the bind*Bubble() method for one type never runs against a
            // VH inflated from a different type's layout.
            ivStatusSeenAvatar = v.findViewById(R.id.iv_status_seen_avatar);
            flStatusSeenThumb  = v.findViewById(R.id.fl_status_seen_thumb);
            ivStatusSeenThumb  = v.findViewById(R.id.iv_status_seen_thumb);
            ivStatusSeenEye    = v.findViewById(R.id.iv_status_seen_eye);
            tvStatusSeenName   = v.findViewById(R.id.tv_status_seen_name);
            tvStatusSeenTime   = v.findViewById(R.id.tv_status_seen_time);

            ivReelSeenAvatar = v.findViewById(R.id.iv_reel_seen_avatar);
            flReelSeenThumb  = v.findViewById(R.id.fl_reel_seen_thumb);
            ivReelSeenThumb  = v.findViewById(R.id.iv_reel_seen_thumb);
            ivReelSeenPlay   = v.findViewById(R.id.iv_reel_seen_play);
            tvReelSeenName   = v.findViewById(R.id.tv_reel_seen_name);
            tvReelSeenTime   = v.findViewById(R.id.tv_reel_seen_time);

            tvCallEntryIcon  = v.findViewById(R.id.tv_call_entry_icon);
            tvCallEntryLabel = v.findViewById(R.id.tv_call_entry_label);
            tvCallEntryTime  = v.findViewById(R.id.tv_call_entry_time);
            llCallEntryRoot  = v.findViewById(R.id.ll_call_entry_root);
            llCallEntryPill  = v.findViewById(R.id.ll_call_entry_pill);
        }
    }
    // ── Feature 13: View Once bubble binding ─────────────────────────────

    /**
     * Binds the "Waiting to be opened" sender bubble (TYPE_VIEW_ONCE_SENT_WAITING).
     * Shown to the SENDER after sending a view-once message, while receiver hasn't opened it yet.
     * Shows a lock icon + "Waiting to be opened" label.
     * Feature 3: sender can LONG-PRESS to revoke (remove) the message before receiver opens it.
     */
    private void bindViewOnceSentWaiting(RecyclerView.ViewHolder holder, Message m) {
        android.view.View root = holder.itemView;
        android.widget.TextView tvTime = root.findViewById(com.callx.app.chat.R.id.tv_time);
        if (tvTime != null && m.timestamp != null) {
            tvTime.setText(new java.text.SimpleDateFormat("h:mm a",
                    java.util.Locale.getDefault()).format(new java.util.Date(m.timestamp)));
        }
        android.view.View bubble = root.findViewById(com.callx.app.chat.R.id.ll_bubble);
        android.view.View tapTarget = bubble != null ? bubble : root;
        tapTarget.setOnClickListener(null); // sender cannot open their own view-once
        // Feature 3: long-press → revoke dialog
        tapTarget.setOnLongClickListener(v -> {
            if (viewOnceRevokeListener != null) {
                viewOnceRevokeListener.onRevokeViewOnce(m);
            }
            return true;
        });
    }

    /**
     * Binds the "View Once" tap bubble for the receiver.
     * Called from onBindViewHolder when viewType == TYPE_VIEW_ONCE_SENT.
     *
     * PERFORMANCE: SimpleViewHolder — no complex binding, just time + tap listener.
     * No Glide load, no media decode here. Media loads only in ViewOnceViewerActivity.
     */
    private void bindViewOnceSent(RecyclerView.ViewHolder holder, Message m) {
        android.view.View root = holder.itemView;

        // Sublabel: show media type hint
        android.widget.TextView tvSub = root.findViewById(com.callx.app.chat.R.id.tv_vo_sublabel);
        if (tvSub != null) {
            String hint = buildTypeHint(m.type);
            tvSub.setText(hint);
        }

        // Time
        android.widget.TextView tvTime = root.findViewById(com.callx.app.chat.R.id.tv_time);
        if (tvTime != null && m.timestamp != null) {
            tvTime.setText(new java.text.SimpleDateFormat("h:mm a",
                    java.util.Locale.getDefault()).format(new java.util.Date(m.timestamp)));
        }

        // BUG FIX: the click listener must go on `ll_bubble` (the actual 200dp
        // clickable card with its own ripple foreground), NOT on `root`/itemView.
        // ll_bubble is clickable="true" itself, so it intercepts the touch and
        // a listener on the full-width root never fires — that's why "Tap to
        // open" was doing nothing.
        android.view.View bubble = root.findViewById(com.callx.app.chat.R.id.ll_bubble);
        android.view.View tapTarget = bubble != null ? bubble : root;
        // DEBOUNCE: tag stores last-click timestamp. Prevents spurious double-fires
        // from RecyclerView rebind or rapid multi-tap causing dialog to open twice.
        tapTarget.setOnClickListener(v -> {
            Object lastClick = v.getTag(com.callx.app.chat.R.id.ll_bubble);
            long now = System.currentTimeMillis();
            if (lastClick instanceof Long && now - (Long) lastClick < 800) return;
            v.setTag(com.callx.app.chat.R.id.ll_bubble, now);
            if (viewOnceOpenListener != null) viewOnceOpenListener.onOpenViewOnce(m);
        });
        tapTarget.setOnLongClickListener(null); // no long-press for view-once (no copy/forward)
    }

    /**
     * Binds the post-open expired bubble.
     * Shows "Opened", "Expired", or "Removed" depending on viewOnceState.
     * Called from onBindViewHolder when viewType == TYPE_VIEW_ONCE_EXPIRED.
     */
    private void bindViewOnceExpired(RecyclerView.ViewHolder holder, Message m) {
        android.view.View root = holder.itemView;

        // Message sent time (bottom-right)
        android.widget.TextView tvTime = root.findViewById(com.callx.app.chat.R.id.tv_time);
        if (tvTime != null && m.timestamp != null) {
            tvTime.setText(new java.text.SimpleDateFormat("h:mm a",
                    java.util.Locale.getDefault()).format(new java.util.Date(m.timestamp)));
        }

        // Determine label based on state
        // Feature 7: expired timer → "Expired"
        // Feature 8: sender revoked → "Removed"
        // Default: receiver opened → "Opened"
        android.widget.TextView tvLabel = root.findViewById(com.callx.app.chat.R.id.tv_expired_label);
        if (tvLabel != null) {
            if (com.callx.app.conversation.controllers.ChatViewOnceController.isTimerExpired(m)) {
                tvLabel.setText("Expired");
            } else if (com.callx.app.conversation.controllers.ChatViewOnceController.isRevoked(m)) {
                tvLabel.setText("Removed");
            } else {
                tvLabel.setText("Opened");
            }
        }

        // "Opened on" date+time — shown only to sender (openedAt is set by receiver's device)
        // For Expired and Removed states there is no openedAt — hide this line.
        android.widget.TextView tvOpenedAt = root.findViewById(com.callx.app.chat.R.id.tv_opened_at);
        if (tvOpenedAt != null) {
            boolean isOpenedNormally = !com.callx.app.conversation.controllers.ChatViewOnceController.isTimerExpired(m)
                    && !com.callx.app.conversation.controllers.ChatViewOnceController.isRevoked(m);
            if (isOpenedNormally
                    && currentUid != null && currentUid.equals(m.senderId) && m.openedAt != null) {
                // Feature 6: format is "h:mm a" only — time, no date (e.g. "Opened · 3:45 PM")
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "h:mm a", java.util.Locale.getDefault());
                tvOpenedAt.setText("Opened · " + sdf.format(new java.util.Date(m.openedAt)));
                tvOpenedAt.setVisibility(android.view.View.VISIBLE);
            } else {
                tvOpenedAt.setVisibility(android.view.View.GONE);
            }
        }

        android.view.View bubble = root.findViewById(com.callx.app.chat.R.id.ll_bubble);
        android.view.View tapTarget = bubble != null ? bubble : root;
        tapTarget.setOnClickListener(null);
        tapTarget.setOnLongClickListener(null);
    }

    private static String buildTypeHint(String type) {
        if (type == null) return "Tap to open";
        switch (type) {
            case "image": return "📷  Photo · Tap to open";
            case "video": return "🎬  Video · Tap to open";
            case "audio": return "🎵  Audio · Tap to open";
            case "file":  return "📄  File · Tap to open";
            default:      return "Tap to open";
        }
    }

    /** Callback interface — ChatActivity implements this to wire ViewOnceController. */
    public interface ViewOnceOpenListener {
        void onOpenViewOnce(com.callx.app.models.Message message);
    }

    private ViewOnceOpenListener viewOnceOpenListener;

    public void setViewOnceOpenListener(ViewOnceOpenListener l) {
        this.viewOnceOpenListener = l;
    }

    /**
     * Feature 3: Callback interface — ChatActivity implements this to handle revoke confirmation dialog.
     * Called when sender long-presses their pending lock bubble.
     */
    public interface ViewOnceRevokeListener {
        void onRevokeViewOnce(com.callx.app.models.Message message);
    }

    private ViewOnceRevokeListener viewOnceRevokeListener;

    public void setViewOnceRevokeListener(ViewOnceRevokeListener l) {
        this.viewOnceRevokeListener = l;
    }


}
