package com.callx.app.conversation.controllers;

import android.view.View;
import android.widget.Toast;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Transformations;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.cache.CacheManager;
import com.callx.app.chat.R;
import com.callx.app.chat.performance.SwipeOptimizer;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

/**
 * ChatPagingController — extracted from ChatActivity v21.
 *
 * Handles:
 *   setupPagingRecyclerView
 *   observePagedMessages
 *   startRealtimeListener / attachFirebaseListener
 *   saveToRoom
 *   entityToModel / modelToEntity
 *   scheduleExpiryCleanup / deleteExpiredFromFirebase
 */
public class ChatPagingController {

    private static final int PAGE_SIZE     = 20;
    private static final int PREFETCH_DIST = 10;
    private static final int INITIAL_LOAD  = 40;

    private final ChatActivityDelegate d;
    private final LifecycleOwner       lifecycleOwner;
    private final RecyclerView         rv;
    private final View                 fabBack;

    // Expiry cleanup
    private final android.os.Handler expiryHandler  = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable                  expiryRunnable;

    // Realtime listener refs (for cleanup)
    private ChildEventListener realtimeListener;

    public ChatPagingController(ChatActivityDelegate delegate,
                                LifecycleOwner lifecycleOwner,
                                RecyclerView rv,
                                View fabBack) {
        this.d             = delegate;
        this.lifecycleOwner = lifecycleOwner;
        this.rv            = rv;
        this.fabBack       = fabBack;
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    public void setupPagingRecyclerView(
            ChatMessageActionController actionCtrl,
            ChatPinController pinController) {

        MessagePagingAdapter pagingAdapter = new MessagePagingAdapter(d.getCurrentUid(), false);
        pagingAdapter.setActionListener(new MessagePagingAdapter.ActionListener() {
            @Override public void onReply(Message m)               { d.startReply(m); }
            @Override public void onDelete(Message m)              { actionCtrl.confirmDeleteMessage(m); }
            @Override public void onReact(Message m, String emoji) { actionCtrl.sendReaction(m, emoji); }
            @Override public void onStar(Message m)                { actionCtrl.toggleStar(m); }
            @Override public void onCopy(Message m)                { actionCtrl.copyText(m); }
            @Override public void onForward(Message m)             { actionCtrl.forwardMessage(m); }
            @Override public void onNavigateToOriginal(String mid) { d.navigateToOriginal(mid); }
            @Override public void onRetry(Message m) {
                if (m.id == null) return;
                String preview = m.text != null ? m.text
                    : (m.type != null ? "[" + m.type + "]" : "[message]");
                d.firebasePushMessage(m, m.id, preview);
            }
            @Override public void onEdit(Message m)  { actionCtrl.editMessage(m); }
            @Override public void onPin(Message m)   { pinController.pinMessage(m); }
            @Override public void onVote(Message m, int optionIndex) { actionCtrl.votePoll(m, optionIndex); }
        });

        // Store adapter reference back in delegate (via interface)
        // Note: getPagingAdapter() is backed by this same instance once set
        // ChatActivity stores it internally; we just configure it here.

        LinearLayoutManager llm = new LinearLayoutManager(d.getActivity());
        llm.setStackFromEnd(true);
        llm.setReverseLayout(false);
        rv.setLayoutManager(llm);
        rv.setAdapter(pagingAdapter);
        SwipeOptimizer.disableChangeAnimations(rv);

        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                int total   = pagingAdapter.getItemCount();
                int lastVis = llm.findLastVisibleItemPosition();
                if (lastVis >= total - itemCount - 2)
                    rv.scrollToPosition(total - 1);
            }
        });

        pagingAdapter.addLoadStateListener(states -> {
            androidx.paging.LoadState refresh = states.getRefresh();
            if (refresh instanceof androidx.paging.LoadState.Loading) {
                if (rv.getVisibility() == View.GONE) {
                    d.getBinding().shimmerContainer.startShimmer();
                    d.getBinding().shimmerContainer.setVisibility(View.VISIBLE);
                }
                d.getBinding().llEmptyChat.setVisibility(View.GONE);
            } else {
                d.getBinding().shimmerContainer.stopShimmer();
                d.getBinding().shimmerContainer.setVisibility(View.GONE);
                if (refresh instanceof androidx.paging.LoadState.Error) {
                    String msg = ((androidx.paging.LoadState.Error) refresh)
                        .getError().getMessage();
                    d.showToast("Failed to load messages: " + msg);
                }
                boolean isEmpty = pagingAdapter.getItemCount() == 0;
                rv.setVisibility(isEmpty ? View.GONE  : View.VISIBLE);
                d.getBinding().llEmptyChat.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            }
            return null;
        });
    }

    public void observePagedMessages(MessagePagingAdapter pagingAdapter) {
        Pager<Integer, MessageEntity> pager = new Pager<>(
            new PagingConfig(PAGE_SIZE, PREFETCH_DIST, false, INITIAL_LOAD),
            () -> d.getDb().messageDao().getMessagesPagingSource(d.getChatId())
        );
        Transformations.map(
            PagingLiveData.getLiveData(pager),
            pagingData -> PagingDataTransforms.map(
                pagingData, d.getIoExecutor(), ChatActivity::entityToModel)
        ).observe(lifecycleOwner, pagingData ->
            pagingAdapter.submitData(lifecycleOwner.getLifecycle(), pagingData));
    }

    // ── Realtime listener ─────────────────────────────────────────────────

    public void startRealtimeListener(ChatPresenceController presenceController) {
        d.getIoExecutor().execute(() -> {
            long lastTs = CacheManager.getInstance(d.getActivity())
                .getLastSyncTimestamp(d.getChatId());
            d.runOnMain(() -> attachFirebaseListener(lastTs, presenceController));
        });
    }

    private void attachFirebaseListener(long lastTs,
                                        ChatPresenceController presenceController) {
        com.google.firebase.database.Query query = lastTs > 0
            ? d.getMessagesRef().orderByChild("timestamp").startAfter((double) lastTs)
            : d.getMessagesRef().orderByChild("timestamp").limitToLast(INITIAL_LOAD);

        realtimeListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot snapshot, String prev) {
                Message m = snapshot.getValue(Message.class);
                if (m == null) return;
                m.id = snapshot.getKey();
                saveToRoom(m);
                presenceController.markRead(m);
            }
            @Override public void onChildChanged(DataSnapshot snapshot, String prev) {
                Message m = snapshot.getValue(Message.class);
                if (m == null) return;
                m.id = snapshot.getKey();
                saveToRoom(m);
            }
            @Override public void onChildRemoved(DataSnapshot snapshot) {
                String key = snapshot.getKey();
                if (key == null) return;
                d.getIoExecutor().execute(() -> d.getDb().messageDao().softDelete(key));
            }
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError error) {}
        };
        query.addChildEventListener(realtimeListener);
    }

    public void releaseListener() {
        if (d.getMessagesRef() != null && realtimeListener != null)
            d.getMessagesRef().removeEventListener(realtimeListener);
    }

    // ── Room save ─────────────────────────────────────────────────────────

    private void saveToRoom(Message m) {
        d.getIoExecutor().execute(() ->
            d.getDb().messageDao().insertMessage(modelToEntity(m)));
    }

    // ── Expiry cleanup ────────────────────────────────────────────────────

    public void scheduleExpiryCleanup() {
        expiryRunnable = new Runnable() {
            @Override public void run() {
                if (d.getDb() == null) return;
                d.getIoExecutor().execute(() -> {
                    int deleted = d.getDb().messageDao()
                        .deleteExpiredMessages(System.currentTimeMillis());
                    if (deleted > 0) deleteExpiredFromFirebase();
                });
                expiryHandler.postDelayed(this, 30_000L);
            }
        };
        expiryHandler.post(expiryRunnable);
    }

    public void cancelExpiryCleanup() {
        if (expiryRunnable != null) expiryHandler.removeCallbacks(expiryRunnable);
    }

    private void deleteExpiredFromFirebase() {
        if (d.getMessagesRef() == null || d.getChatId() == null) return;
        long nowMs = System.currentTimeMillis();
        d.getMessagesRef().orderByChild("expiresAt").endAt(nowMs)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        Object raw = child.child("expiresAt").getValue();
                        if (raw instanceof Long) {
                            long expiresAt = (Long) raw;
                            if (expiresAt > 0 && expiresAt <= nowMs) child.getRef().removeValue();
                        }
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ── Entity ↔ Model ────────────────────────────────────────────────────

    private MessageEntity modelToEntity(Message m) {
        MessageEntity e = new MessageEntity();
        e.id            = m.id != null ? m.id : "";
        e.chatId        = d.getChatId();
        e.senderId      = m.senderId;
        e.senderName    = m.senderName;
        e.senderPhoto   = m.senderPhoto;
        e.text          = m.text;
        e.type          = m.type != null ? m.type : "text";
        e.mediaUrl      = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
        e.thumbnailUrl  = m.thumbnailUrl;
        e.fileName      = m.fileName;
        e.fileSize      = m.fileSize;
        e.duration      = m.duration;
        e.timestamp     = m.timestamp;
        e.status        = m.status;
        e.replyToId         = m.replyToId;
        e.replyToText       = m.replyToText;
        e.replyToSenderName = m.replyToSenderName;
        e.replyToType       = m.replyToType;
        e.replyToMediaUrl   = m.replyToMediaUrl;
        e.edited        = m.edited;
        e.deleted       = m.deleted;
        e.forwardedFrom = m.forwardedFrom;
        e.starred       = Boolean.TRUE.equals(m.starred);
        e.pinned        = Boolean.TRUE.equals(m.pinned);
        e.reelId        = m.reelId;
        e.reelThumbUrl  = m.reelThumbUrl;
        e.fontStyle     = m.fontStyle;
        e.expiresAt     = m.expiresAt;
        e.syncedAt      = System.currentTimeMillis();
        return e;
    }
}
