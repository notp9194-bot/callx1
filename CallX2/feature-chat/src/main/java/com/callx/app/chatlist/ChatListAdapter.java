package com.callx.app.chatlist;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.chat.R;

import com.callx.app.chatlist.canvas.ChatListLastMessageView;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.models.User;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.cache.StatusCacheManager;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.ChatListPreviewUtil;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatListAdapter v23
 *
 * CHANGES v23 — Canvas rendering for last-message row (perf):
 *  1. item_chat.xml's last-message line — previously a nested LinearLayout
 *     holding an iv_read_status ImageView (tick) + tv_last_message TextView —
 *     is now a single ChatListLastMessageView that paints both the ticks
 *     and the text directly with Canvas.drawLine()/drawText(), same
 *     technique MessageBubbleCanvasView already uses for bubbles in the
 *     chat screen. One less inflate + one less measure/layout pass per
 *     row, so fast scrolling on the chat list stays smoother.
 *  2. VH.lastMessageView replaces VH.tvLastMessage/VH.ivReadStatus; all
 *     read-status + typing text now go through setMessageText()/setTicks(),
 *     which no-op (skip invalidate) when the value hasn't actually changed.
 *
 * CHANGES v22 — Read receipts, media labels, live typing:
 *  1. Read-receipt ticks (✓ sent / ✓✓ delivered / blue ✓✓ read) rendered
 *     via the last-message row's ChatListLastMessageView (see v23 above),
 *     driven by User.lastMessageStatus — only shown when the current user
 *     sent the chat's last message.
 *  2. Media messages (image/video/audio/gif/sticker/poll/contact/location/
 *     multi_media/reel_share/document) now always show their emoji label
 *     ("📷 Photo", "🎤 Voice message", ...) via ChatListPreviewUtil, derived
 *     from lastMessageType instead of trusting raw lastMessage text.
 *  3. Live "typing..." indicator per row — mirrors "typing"/{chatId}/{uid}
 *     from Firebase, attached/detached with the row's bind/recycle
 *     lifecycle (see attachTypingListener/onViewRecycled) so it never
 *     leaks listeners while scrolling.
 *
 * CHANGES v21 — Delete / Delete-All system:
 *  1. Long-press pe directly selection mode start hota hai.
 *     PrivacyDirectDialog ab DOUBLE long-press ya dedicated method se trigger hoga
 *     (ChatsFragment mein showPrivacyDirectDialog() ko context menu se call karo).
 *  2. Selection overlay: checkmark circle avatar ke upar dikhta hai.
 *  3. Call buttons + story ring selection mode mein hide hote hain.
 *  4. setOnLongPressListener() is now used ONLY for external callers who
 *     still want the old behavior; if NOT set, long-press starts selection.
 *     ChatsFragment v21 mein longPressListener set nahi karta —
 *     PrivacyDirectDialog ab selection bar ke 3-dot menu se accessible hai.
 */
public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {

    // PERF FIX: preload trigger moved here from ChatActivity. Earlier,
    // ChatRepository.preloadRecentChats() only ran 3s AFTER a chat was
    // already open — so the NEXT chat you tapped had zero preload benefit.
    // Now, the moment a row is actually visible in the list (onBindViewHolder
    // fires only for visible/soon-visible items), we kick off its delta sync
    // in the background. By the time the user taps it, Room already has the
    // latest messages and ChatActivity's Pager attaches instantly — no
    // Firebase round-trip wait on the chat screen itself.
    // Cooldown map avoids re-triggering a Firebase sync on every scroll-driven
    // rebind of the same row (RecyclerView rebinds recycled views often).
    private static final ConcurrentHashMap<String, Long> sLastPreloadAt = new ConcurrentHashMap<>();
    private static final long PRELOAD_COOLDOWN_MS = 30_000L;

    private void preloadChatIfDue(Context ctx, User u) {
        if (u == null || u.uid == null) return;
        if (myUid == null) return;
        String chatId = FirebaseUtils.getChatId(myUid, u.uid);
        long now = System.currentTimeMillis();
        Long last = sLastPreloadAt.get(chatId);
        if (last != null && (now - last) < PRELOAD_COOLDOWN_MS) return;
        sLastPreloadAt.put(chatId, now);
        ChatRepository repo = ChatRepository.getInstance(ctx.getApplicationContext());
        // Local disk read (Room persists across app-kills) — warms the
        // in-memory render cache with ZERO network wait. This is what makes
        // ChatActivity's warmCacheHit fast path fire on the very FIRST tap,
        // not just on a chat's 2nd+ open.
        repo.warmLastMessagesCache(chatId);
        // Network delta sync — keeps Room itself up to date in the background.
        repo.syncMessagesDelta(chatId);
    }

    public interface SelectionListener {
        void onSelectionStarted();
        void onSelectionChanged();
        void onSelectionCleared();
    }

    public interface OnAvatarClickListener {
        void onAvatarClick(User user);
    }

    /** @deprecated v21: Long-press now starts selection. Use context-menu instead. */
    @Deprecated
    public interface OnLongPressListener {
        void onLongPress(User user, View anchor);
    }

    private final List<User> contacts;
    private final SelectionListener selectionListener;
    private OnAvatarClickListener avatarClickListener;

    /** kept for backward-compat but ChatsFragment v21 does NOT set this */
    private OnLongPressListener longPressListener;

    private final SimpleDateFormat fmt = new SimpleDateFormat("hh:mm a", Locale.getDefault());
    private Set<String> specialRequestSenders = new HashSet<>();

    // v22: cached once — used to decide whether ticks show on a row (only
    // when the LAST message in that chat was sent by the current user).
    private final String myUid = FirebaseAuth.getInstance().getUid();

    private boolean isSelecting = false;
    private final Set<String> selectedUids = new HashSet<>();

    // PERF FIX: payload marker used to signal "only selection-mode visuals
    // changed" so onBindViewHolder can skip the expensive full rebind
    // (Glide load, preloadChatIfDue, click-listener re-creation) for rows
    // whose content hasn't actually changed — just their selected / call-btn /
    // overlay appearance. This is what stops entry/exit of selection mode
    // and select-all/clear-all from flickering or re-triggering avatar loads
    // on large chat lists.
    private static final String PAYLOAD_SELECTION = "payload_selection";

    public ChatListAdapter(List<User> contacts, SelectionListener listener) {
        this.contacts          = contacts;
        this.selectionListener = listener;
        // PERF FIX: stable IDs allow RecyclerView to animate diffs correctly
        // and avoid unnecessary rebind calls for unchanged items.
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= contacts.size()) return RecyclerView.NO_ID;
        String uid = contacts.get(position).uid;
        return uid != null ? uid.hashCode() : position;
    }

    public void setSpecialRequestSenders(Set<String> set) {
        this.specialRequestSenders = set == null ? new HashSet<>() : set;
        notifyItemRangeChanged(0, getItemCount());
    }

    public void setOnAvatarClickListener(OnAvatarClickListener listener) {
        this.avatarClickListener = listener;
    }

    /** @deprecated v21 — long-press now triggers selection mode. */
    @Deprecated
    public void setOnLongPressListener(OnLongPressListener listener) {
        this.longPressListener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_chat, parent, false);
        return new VH(v);
    }

    // PERF FIX: payload-aware bind entry point. When RecyclerView calls this
    // with a non-empty payloads list (selection-mode toggles route through
    // notifyItemChanged/notifyItemRangeChanged with PAYLOAD_SELECTION), we
    // skip straight to the lightweight selection-only update instead of
    // running the full bind (Glide reload, preload trigger, listener re-attach).
    @Override public void onBindViewHolder(@NonNull VH h, int pos, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            User u = contacts.get(pos);
            applySelectionVisuals(h, u);
            return;
        }
        super.onBindViewHolder(h, pos, payloads);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        User u = contacts.get(pos);
        Context ctx = h.itemView.getContext();

        // PERF FIX: preload this chat's messages the moment its row becomes
        // visible, well before the user taps it. See preloadChatIfDue() above.
        preloadChatIfDue(ctx, u);

        h.tvName.setText(u.name == null ? "User" : u.name);

        // thumbUrl → 100px WebP, fast load. Fallback: photoUrl
        String avatarUrl = (u.thumbUrl != null && !u.thumbUrl.isEmpty())
            ? u.thumbUrl : u.photoUrl;
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(ctx)
                .load(avatarUrl)
                .dontAnimate()
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(R.drawable.ic_person);
        }

        // ── Story ring ──────────────────────────────────────────────────────
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        boolean hasStory = u.uid != null && (scm.hasUnseen(u.uid) || scm.hasStatus(u.uid));

        if (h.ivStoryRing != null && u.uid != null) {
            if (!isSelecting && scm.hasUnseen(u.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_unseen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else if (!isSelecting && scm.hasStatus(u.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_seen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else {
                h.ivStoryRing.setVisibility(View.GONE);
            }
            h.ivStoryRing.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                if (avatarClickListener != null) avatarClickListener.onAvatarClick(u);
                else openStatusOrChat(ctx, u);
            });
        }

        // ── Time ─────────────────────────────────────────────────────────────
        Long when = u.lastMessageAt != null ? u.lastMessageAt : u.lastSeen;
        h.tvTime.setText((when != null && when > 0) ? fmt.format(new Date(when)) : "");

        // v22/v23: reset any stale "typing..." flag left over from a
        // recycled row — the canvas view's own setMessageText()/setTicks()
        // no-op when values are unchanged, so no explicit reset call is
        // needed here for text/ticks themselves; applySelectionVisuals()
        // below always re-drives them from this row's real data.
        h.isTypingNow = false;

        // ── Selection-dependent state (last-message text + ticks, unread
        // badge, special-text override, background / overlay / call-btns /
        // story-ring) ──────────────────────────────────────────────────────
        // Extracted into applySelectionVisuals() so selection-mode toggles
        // can re-run just this part via a payload bind instead of a full
        // rebind — see that method for the unread/isSpecial/isSelecting logic.
        applySelectionVisuals(h, u);

        // v22: live "typing..." indicator — attached/detached with this row's
        // bind/recycle lifecycle (same pattern as Glide's avatar load above),
        // so it never leaks a listener onto a row that's since scrolled away
        // and been rebound to a different contact.
        attachTypingListener(h, u);

        // ── Click listeners ─────────────────────────────────────────────────

        h.ivAvatar.setOnClickListener(v -> {
            if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
            if (avatarClickListener != null) {
                avatarClickListener.onAvatarClick(u);
            } else {
                if (hasStory) openStatusOrChat(ctx, u);
                else openChat(ctx, u);
            }
        });

        h.ivAvatar.setOnLongClickListener(v -> {
            if (isSelecting) return true;
            showAvatarZoom(ctx, u.photoUrl, u.name);
            return true;
        });

        h.itemView.setOnClickListener(v -> {
            if (isSelecting) toggleSelection(h.getAdapterPosition());
            else openChat(ctx, u);
        });

        // ── Long-press: START selection mode (v21) ──────────────────────────
        h.itemView.setOnLongClickListener(v -> {
            if (!isSelecting) {
                // Start selection mode
                isSelecting = true;
                if (u.uid != null) selectedUids.add(u.uid);
                // PERF FIX: payload bind — every row's selection chrome
                // changes, but avatars/preload/listeners don't need re-doing.
                notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
                if (selectionListener != null) selectionListener.onSelectionStarted();
            } else {
                toggleSelection(h.getAdapterPosition());
            }
            return true;
        });

        // Call buttons
        if (h.btnCall != null) {
            h.btnCall.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid", u.uid);
                i.putExtra("partnerName", u.name);
                i.putExtra("isCaller", true);
                i.putExtra("video", false);
                ctx.startActivity(i);
            });
        }

        if (h.btnVideoCall != null) {
            h.btnVideoCall.setOnClickListener(v -> {
                if (isSelecting) { toggleSelection(h.getAdapterPosition()); return; }
                Intent i = new Intent().setClassName(ctx.getPackageName(), "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid", u.uid);
                i.putExtra("partnerName", u.name);
                i.putExtra("isCaller", true);
                i.putExtra("video", true);
                ctx.startActivity(i);
            });
        }
    }

    // PERF FIX: everything that changes purely because `isSelecting` /
    // `selectedUids` changed — background highlight, avatar checkmark
    // overlay, call-button visibility, unread-badge visibility, and the
    // story-ring's visibility (it hides while selecting). Called from the
    // full bind AND from the lightweight payload bind, so selection-mode
    // enter/exit and select-all/clear-all never need to touch Glide,
    // preloadChatIfDue(), or re-attach click listeners.
    private void applySelectionVisuals(VH h, User u) {
        Context ctx = h.itemView.getContext();
        boolean selected  = u.uid != null && selectedUids.contains(u.uid);
        boolean isSpecial = u.uid != null && specialRequestSenders.contains(u.uid);

        // Unread badge + last-message color (hidden while selecting)
        long unread = u.unread == null ? 0 : u.unread;
        int lastMsgColor;
        if (unread > 0 && !isSelecting) {
            h.tvUnread.setText(unread > 99 ? "99+" : String.valueOf(unread));
            h.tvUnread.setVisibility(View.VISIBLE);
            lastMsgColor = ctx.getResources().getColor(R.color.text_primary);
        } else {
            h.tvUnread.setVisibility(View.GONE);
            lastMsgColor = ctx.getResources().getColor(R.color.text_secondary);
        }

        // v22: while the partner is actively typing (see attachTypingListener/
        // applyTypingRow below) that row already owns the canvas view's text —
        // don't clobber "typing..." with the stored last message on a payload
        // bind (e.g. selection mode toggled mid-typing).
        if (!h.isTypingNow) {
            if (isSpecial && !isSelecting) {
                h.lastMessageView.setMessageText("⭐ Special unblock request", 0xFFFF8F00, false);
            } else {
                // BUG FIX: media messages (image/video/voice/etc.) now always
                // show their emoji label ("📷 Photo", "🎤 Voice message", ...)
                // derived from lastMessageType, instead of trusting whatever
                // raw text happened to be stored for that message.
                String preview = ChatListPreviewUtil.buildPreview(
                        u.lastMessageType, u.lastMessage, "Tap karke chat karo");
                h.lastMessageView.setMessageText(preview, lastMsgColor, false);
            }
        }

        // Read-status ticks (✓ sent / ✓✓ delivered / blue ✓✓ read) — only
        // for the row's own last message, and only when the CURRENT USER
        // was the sender of it (you don't see ticks on messages you received).
        updateReadStatusTicks(h, u, isSelecting, isSpecial);

        // Story ring hides while selecting (own click listener is set once
        // during the full bind and reads `isSelecting` live, so it doesn't
        // need to be re-attached here).
        if (h.ivStoryRing != null && u.uid != null) {
            StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
            if (!isSelecting && scm.hasUnseen(u.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_unseen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else if (!isSelecting && scm.hasStatus(u.uid)) {
                h.ivStoryRing.setBackgroundResource(R.drawable.circle_status_seen);
                h.ivStoryRing.setVisibility(View.VISIBLE);
            } else {
                h.ivStoryRing.setVisibility(View.GONE);
            }
        }

        // Card background highlight
        h.itemView.setBackgroundColor(
            selected      ? 0x335B5BF6 :
            isSpecial     ? 0x33FFC107 : 0x00000000);

        // Selection overlay on avatar
        if (h.flSelectOverlay != null) {
            if (isSelecting) {
                h.flSelectOverlay.setVisibility(View.VISIBLE);
                if (h.vCheckRing != null)  h.vCheckRing.setVisibility(selected ? View.VISIBLE : View.GONE);
                if (h.ivCheck != null)     h.ivCheck.setVisibility(View.INVISIBLE);  // ring handles it
            } else {
                h.flSelectOverlay.setVisibility(View.GONE);
            }
        }

        // Call buttons: hide during selection mode
        if (h.llCallBtns != null) {
            h.llCallBtns.setVisibility(isSelecting ? View.GONE : View.VISIBLE);
        }
    }

    // ── v22: Read-status ticks ───────────────────────────────────────────
    // ✓ (sent, grey) / ✓✓ (delivered, grey) / ✓✓ (read, blue). Only shown
    // when the CURRENT USER sent the chat's last message — matching
    // WhatsApp, you never see ticks on a message the other person sent you.
    private void updateReadStatusTicks(VH h, User u, boolean isSelecting, boolean isSpecial) {
        boolean iAmLastSender = myUid != null && u.uid != null
                && myUid.equals(u.lastMessageSenderUid);
        if (h.isTypingNow || isSelecting || isSpecial || !iAmLastSender || u.lastMessageStatus == null) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_NONE, 0);
            return;
        }
        Context ctx = h.itemView.getContext();
        if ("read".equals(u.lastMessageStatus)) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_READ,
                    ctx.getResources().getColor(R.color.tick_read_blue));
        } else if ("delivered".equals(u.lastMessageStatus)) {
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_DELIVERED,
                    ctx.getResources().getColor(R.color.text_muted));
        } else {
            // "sent" (or any other/unknown non-null status)
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_SENT,
                    ctx.getResources().getColor(R.color.text_muted));
        }
    }

    // ── v22: Live "typing..." indicator ──────────────────────────────────
    // Mirrors ChatPresenceController's own "typing"/{chatId}/{uid}=bool node
    // (the same flag that drives the in-chat 3-dot typing strip) — here we
    // watch it per-row so the chat LIST can also show "typing..." in place
    // of the last message while that contact is composing a reply.
    //
    // PERF: attach/detach is tied to this row's bind/recycle lifecycle,
    // exactly like the Glide avatar load and preloadChatIfDue() above —
    // it only ever listens for rows Currently on/near screen, and is
    // detached the instant a row is recycled (onViewRecycled below), so
    // scrolling never accumulates orphaned listeners.
    private void attachTypingListener(VH h, User u) {
        detachTypingListener(h);
        if (u.uid == null || myUid == null) return;
        String chatId = FirebaseUtils.getChatId(myUid, u.uid);
        DatabaseReference ref = FirebaseUtils.db().getReference("typing")
                .child(chatId).child(u.uid);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= contacts.size()) return;
                User current = contacts.get(pos);
                // Row may have been recycled to a different contact while
                // this listener's callback was in flight — verify first.
                if (current.uid == null || !current.uid.equals(u.uid)) return;
                applyTypingRow(h, current, Boolean.TRUE.equals(snap.getValue(Boolean.class)));
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        ref.addValueEventListener(listener);
        h.typingRef = ref;
        h.typingListener = listener;
    }

    private void detachTypingListener(VH h) {
        if (h.typingRef != null && h.typingListener != null) {
            h.typingRef.removeEventListener(h.typingListener);
        }
        h.typingRef = null;
        h.typingListener = null;
    }

    private void applyTypingRow(VH h, User u, boolean isTyping) {
        h.isTypingNow = isTyping;
        if (isTyping) {
            h.lastMessageView.setMessageText("typing...", 0xFF0F4C3A, true); // brand_primary, italic — matches typing strip in-chat
            h.lastMessageView.setTicks(ChatListLastMessageView.TICK_NONE, 0);
        } else {
            // Typing stopped — redraw this row's normal state (last message
            // text/color, ticks, unread badge) without touching anything
            // else (avatar, call buttons, story ring, selection chrome).
            applySelectionVisuals(h, u);
        }
    }

    // Safety cap only — normal Room reads finish in a few ms (indexed,
    // LIMIT-20, local disk). This just stops a tap from ever feeling stuck
    // on an unusually slow device or a brand-new/never-synced chat.
    private static final long OPEN_CHAT_SAFETY_CAP_MS = 150L;

    private void openChat(Context ctx, User u) {
        String chatId = (myUid != null && u.uid != null) ? FirebaseUtils.getChatId(myUid, u.uid) : null;

        Runnable navigate = () -> {
            Intent i = new Intent(ctx, ChatActivity.class);
            i.putExtra("partnerUid",   u.uid);
            i.putExtra("partnerName",  u.name);
            i.putExtra("partnerPhoto", u.photoUrl != null ? u.photoUrl : "");
            i.putExtra("partnerThumb", u.thumbUrl != null ? u.thumbUrl : "");
            ctx.startActivity(i);
        };

        if (chatId == null) {
            navigate.run(); // can't resolve a chatId — nothing to prime, just open
            return;
        }

        // WhatsApp-style open: read this chat's recent messages from Room
        // (local disk, no network) and only THEN start ChatActivity, so the
        // screen arrives with content already in it instead of arriving
        // blank and having messages pop in afterward. If this chat is
        // already warm from earlier this session, the callback fires
        // synchronously-ish (posted immediately) with no real wait.
        final boolean[] navigated = {false};
        android.os.Handler safetyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable safetyFallback = () -> {
            if (!navigated[0]) { navigated[0] = true; navigate.run(); }
        };
        safetyHandler.postDelayed(safetyFallback, OPEN_CHAT_SAFETY_CAP_MS);

        ChatRepository.getInstance(ctx.getApplicationContext()).primeChatFromRoom(chatId, () -> {
            safetyHandler.removeCallbacks(safetyFallback);
            if (!navigated[0]) { navigated[0] = true; navigate.run(); }
        });
    }

    private void openStatusOrChat(Context ctx, User u) {
        if (u.uid == null) { openChat(ctx, u); return; }
        StatusCacheManager scm = StatusCacheManager.getInstance(ctx);
        if (scm.hasUnseen(u.uid) || scm.hasStatus(u.uid)) {
            Intent si = new Intent().setClassName(ctx.getPackageName(),
                    "com.callx.app.viewer.StatusViewerActivity");
            si.putExtra("ownerUid",  u.uid);
            si.putExtra("ownerName", u.name != null ? u.name : "");
            ctx.startActivity(si);
        } else {
            openChat(ctx, u);
        }
    }

    private void toggleSelection(int pos) {
        if (pos < 0 || pos >= contacts.size()) return;
        User u = contacts.get(pos);
        if (u.uid == null) return;
        if (selectedUids.contains(u.uid)) selectedUids.remove(u.uid);
        else selectedUids.add(u.uid);

        if (selectedUids.isEmpty()) {
            // Last item deselected → selection mode itself ends, which
            // changes EVERY row's chrome (call-btns reappear etc.), so this
            // one needs the full-range payload notify, not just this row.
            isSelecting = false;
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
            if (selectionListener != null) selectionListener.onSelectionCleared();
        } else {
            // PERF FIX: payload bind — only this row's checkmark/background
            // need to change, no need to re-run Glide or preloadChatIfDue for it.
            notifyItemChanged(pos, PAYLOAD_SELECTION);
            if (selectionListener != null) selectionListener.onSelectionChanged();
        }
    }

    public void selectAll() {
        isSelecting = true;
        for (User u : contacts) if (u.uid != null) selectedUids.add(u.uid);
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (selectionListener != null) selectionListener.onSelectionChanged();
    }

    public void clearSelection() {
        isSelecting = false;
        selectedUids.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (selectionListener != null) selectionListener.onSelectionCleared();
    }

    public boolean isSelecting() { return isSelecting; }
    public int getSelectedCount() { return selectedUids.size(); }

    public List<User> getSelectedItems() {
        List<User> sel = new ArrayList<>();
        for (User u : contacts)
            if (u.uid != null && selectedUids.contains(u.uid)) sel.add(u);
        return sel;
    }

    // ── Avatar Zoom Dialog ────────────────────────────────────────────────
    private void showAvatarZoom(Context ctx, String photoUrl, String name) {
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            ctx, photoUrl, name, R.drawable.ic_person, R.drawable.ic_close);
    }

    @Override public int getItemCount() { return contacts.size(); }

    // v22: detach the per-row typing listener the instant a row leaves the
    // screen and goes back into the recycled pool — mirrors how Glide loads
    // get cancelled/replaced on rebind, so scrolling never leaks listeners
    // or lets a stale callback write into a row that's since been reused
    // for a different contact.
    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        detachTypingListener(h);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvTime, tvUnread;
        CircleImageView ivAvatar;
        android.widget.ImageView ivStoryRing, ivCheck;
        View flSelectOverlay, vCheckRing, llCallBtns;
        ImageButton btnCall, btnVideoCall;

        // v23: last-message text + read-receipt ticks, Canvas-rendered —
        // replaces the old tv_last_message TextView + iv_read_status
        // ImageView pair (see ChatListLastMessageView for why).
        ChatListLastMessageView lastMessageView;

        // v22: live typing indicator — listener ref for detach-on-recycle,
        // plus a flag so applySelectionVisuals() knows not to clobber the
        // "typing..." text with the stored last message mid-payload-bind.
        DatabaseReference typingRef;
        ValueEventListener typingListener;
        boolean isTypingNow = false;

        VH(View v) {
            super(v);
            tvName          = v.findViewById(R.id.tv_name);
            lastMessageView = v.findViewById(R.id.view_last_message);
            tvTime          = v.findViewById(R.id.tv_time);
            tvUnread        = v.findViewById(R.id.tv_unread_badge);
            ivAvatar        = v.findViewById(R.id.iv_avatar);
            ivStoryRing     = v.findViewById(R.id.iv_story_ring);
            flSelectOverlay = v.findViewById(R.id.fl_select_overlay);
            ivCheck         = v.findViewById(R.id.iv_check);
            vCheckRing      = v.findViewById(R.id.v_check_ring);
            llCallBtns      = v.findViewById(R.id.ll_call_btns);
            btnCall         = v.findViewById(R.id.btn_call);
            btnVideoCall    = v.findViewById(R.id.btn_video_call);
        }
    }
}
