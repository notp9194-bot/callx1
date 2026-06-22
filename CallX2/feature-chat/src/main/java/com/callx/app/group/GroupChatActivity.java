package com.callx.app.group;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.MessageDao;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.conversation.controllers.ChatActivityDelegate;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GroupChatActivity — WhatsApp-level group chat screen
 *
 * FIX v5.1: entity.chatId = groupId (not entity.groupId)
 * groupId column exist nahi karta messages table mein.
 * chatId column se hi sab queries kaam karti hain.
 */
public class GroupChatActivity extends AppCompatActivity {

    public static final String EXTRA_GROUP_ID    = "groupId";
    public static final String EXTRA_GROUP_NAME  = "groupName";
    public static final String EXTRA_GROUP_PHOTO = "groupPhoto";

    private static final long TYPING_STOP_MS = 2_000L;

    private String groupId;
    private String myUid;
    private String groupName;
    private String groupPhoto;

    private MessageDao           messageDao;
    private ExecutorService      ioExecutor;
    private ChatActivityDelegate delegate;

    private RecyclerView         rvMessages;
    private LinearLayoutManager  layoutManager;
    private MessagePagingAdapter pagingAdapter;
    private boolean              isFirstLoad = true;

    private DatabaseReference  groupMessagesRef;
    private DatabaseReference  groupTypingRef;
    private DatabaseReference  myTypingRef;
    private ChildEventListener messageListener;
    private ValueEventListener typingListener;

    private final Handler  mainHandler    = new Handler(Looper.getMainLooper());
    private boolean        isTypingActive = false;
    private final Runnable stopTypingRunnable = () -> {
        if (myTypingRef != null) myTypingRef.removeValue();
        isTypingActive = false;
    };

    private ConnectivityManager                 connMgr;
    private ConnectivityManager.NetworkCallback netCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setContentView(R.layout.activity_group_chat);

        readIntentExtras();
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        ioExecutor = Executors.newFixedThreadPool(2);
        messageDao = AppDatabase.getInstance(this).messageDao();
        delegate   = new ChatActivityDelegate(messageDao, ioExecutor);

        setupToolbar();
        setupPagingRecyclerView();
        setupFirebaseListener();
        setupGroupTypingListener();
        setupInputBar();
        setupFabBackToLatest();
        setupNetworkMonitor();
        restoreDraft();
        markAllUnreadAsRead();
    }

    @Override protected void onStart()  { super.onStart(); setMyGroupPresence(true);  }
    @Override protected void onStop()   {
        super.onStop(); setMyGroupPresence(false);
        mainHandler.removeCallbacks(stopTypingRunnable);
        if (myTypingRef != null) myTypingRef.removeValue();
        isTypingActive = false;
    }
    @Override protected void onPause()  { super.onPause(); saveDraft(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        delegate.flushNow();
        detachFirebaseListeners();
        if (connMgr != null && netCallback != null) {
            try { connMgr.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
        if (ioExecutor != null) ioExecutor.shutdown();
    }

    private void setupPagingRecyclerView() {
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        layoutManager.setReverseLayout(false);

        rvMessages = findViewById(R.id.rv_messages);
        rvMessages.setLayoutManager(layoutManager);
        rvMessages.setHasFixedSize(false);
        rvMessages.setItemViewCacheSize(20);

        pagingAdapter = new MessagePagingAdapter(myUid);
        rvMessages.setAdapter(pagingAdapter);

        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onItemRangeInserted(int pos, int count) {
                if (isFirstLoad) { isFirstLoad = false; return; }
                if (isUserAtBottom()) scrollToBottom(true);
            }
        });

        // Wire Paging3:
        // new Pager<>(new PagingConfig(30, 15, false),
        //     () -> messageDao.getPagedGroupMessages(groupId))
        //     .getLiveData().observe(this, pagingAdapter::submitData);
    }

    private void setupFirebaseListener() {
        groupMessagesRef = FirebaseDatabase.getInstance()
                .getReference("groupMessages").child(groupId)
                .orderByChild("timestamp").limitToLast(30);

        messageListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot s, String p) {
                MessageEntity e = parseGroupMessage(s);
                if (e != null) {
                    delegate.queueUpsert(e);
                    if (!e.senderId.equals(myUid)) {
                        delegate.queueMarkRead(e.id);
                        pushReadReceipt(e.id);
                    }
                }
            }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String p) {
                MessageEntity e = parseGroupMessage(s); if (e != null) delegate.queueUpsert(e);
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {
                if (s.getKey() != null) delegate.queueRemove(s.getKey());
            }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        groupMessagesRef.addChildEventListener(messageListener);
    }

    private void setupGroupTypingListener() {
        groupTypingRef = FirebaseDatabase.getInstance().getReference("groupTyping").child(groupId);
        myTypingRef    = groupTypingRef.child(myUid);

        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<String> names = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (DataSnapshot c : snap.getChildren()) {
                    if (myUid.equals(c.getKey())) continue;
                    Long ts   = c.child("timestamp").getValue(Long.class);
                    String nm = c.child("name").getValue(String.class);
                    if (ts != null && (now - ts) < TYPING_STOP_MS && nm != null) names.add(nm);
                }
                runOnUiThread(() -> updateGroupTypingIndicator(names));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        groupTypingRef.addValueEventListener(typingListener);
    }

    private void detachFirebaseListeners() {
        if (groupMessagesRef != null && messageListener != null) {
            groupMessagesRef.removeEventListener(messageListener); messageListener = null; }
        if (groupTypingRef != null && typingListener != null) {
            groupTypingRef.removeEventListener(typingListener); typingListener = null; }
    }

    private void markAllUnreadAsRead() {
        ioExecutor.execute(() -> {
            List<String> ids = messageDao.getUnreadGroupMessageIds(groupId, myUid);
            if (ids != null && !ids.isEmpty()) {
                delegate.queueMarkReadBulk(ids);
                pushReadReceiptBulk(ids);
            }
        });
    }

    private void pushReadReceipt(String msgId) {
        ioExecutor.execute(() ->
            FirebaseDatabase.getInstance().getReference()
                .child("groupMessages").child(groupId)
                .child(msgId).child("readBy").child(myUid).setValue(true));
    }

    private void pushReadReceiptBulk(List<String> ids) {
        ioExecutor.execute(() -> {
            DatabaseReference r = FirebaseDatabase.getInstance().getReference()
                    .child("groupMessages").child(groupId);
            for (String id : ids) r.child(id).child("readBy").child(myUid).setValue(true);
        });
    }

    private void onUserTyping() {
        if (!isTypingActive) {
            myTypingRef.child("timestamp").setValue(ServerValue.TIMESTAMP);
            // myTypingRef.child("name").setValue(myDisplayName);
            isTypingActive = true;
        }
        mainHandler.removeCallbacks(stopTypingRunnable);
        mainHandler.postDelayed(stopTypingRunnable, TYPING_STOP_MS);
    }

    private void onUserStoppedTyping() {
        mainHandler.removeCallbacks(stopTypingRunnable);
        if (isTypingActive) { myTypingRef.removeValue(); isTypingActive = false; }
    }

    private void setMyGroupPresence(boolean active) {
        DatabaseReference r = FirebaseDatabase.getInstance().getReference()
                .child("groupActive").child(groupId).child(myUid);
        if (active) r.setValue(true); else r.removeValue();
    }

    private static final String PREF_DRAFT = "group_draft_";
    private void saveDraft()    { /* SharedPreferences save */ }
    private void restoreDraft() { /* SharedPreferences restore */ }

    private void setupFabBackToLatest() {
        rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!isUserAtBottom()) showFab(); else hideFab();
            }
        });
    }

    private void setupNetworkMonitor() {
        connMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network n) { runOnUiThread(() -> updateOfflineBanner(false)); }
            @Override public void onLost(@NonNull Network n)      { runOnUiThread(() -> updateOfflineBanner(true));  }
        };
        connMgr.registerNetworkCallback(new NetworkRequest.Builder().build(), netCallback);
    }

    private boolean isUserAtBottom() {
        int last = layoutManager.findLastVisibleItemPosition();
        int total = pagingAdapter.getItemCount();
        return total == 0 || last >= total - 2;
    }
    private void scrollToBottom(boolean animate) {
        int total = pagingAdapter.getItemCount(); if (total == 0) return;
        if (animate) rvMessages.smoothScrollToPosition(total - 1);
        else          rvMessages.scrollToPosition(total - 1);
    }

    private void setupInputBar() {}
    private void setupToolbar()  {}
    private void readIntentExtras() {
        groupId    = getIntent().getStringExtra(EXTRA_GROUP_ID);
        groupName  = getIntent().getStringExtra(EXTRA_GROUP_NAME);
        groupPhoto = getIntent().getStringExtra(EXTRA_GROUP_PHOTO);
    }
    private void updateGroupTypingIndicator(List<String> names) {
        // if(names.isEmpty()){ binding.tvTyping.setVisibility(View.GONE); return; }
        // String t = names.size()==1 ? names.get(0)+" is typing…"
        //          : names.get(0)+" and "+(names.size()-1)+" others are typing…";
        // binding.tvTyping.setText(t); binding.tvTyping.setVisibility(View.VISIBLE);
    }
    private void updateOfflineBanner(boolean s) {}
    private void showFab() {}
    private void hideFab() {}

    // ── FIX v5.1: e.chatId = groupId (NOT e.groupId) ────────────────────────
    // messages table mein groupId column nahi hai.
    // chatId column se hi group messages bhi store hoti hain.

    private MessageEntity parseGroupMessage(DataSnapshot snap) {
        if (snap == null || snap.getKey() == null) return null;
        try {
            MessageEntity e = new MessageEntity();
            e.id           = snap.getKey();
            e.chatId       = groupId;                  // ← FIX: chatId = groupId (no groupId column needed)
            e.senderId     = snap.child("senderId").getValue(String.class);
            e.senderName   = snap.child("senderName").getValue(String.class);
            e.text         = snap.child("text").getValue(String.class);
            e.mediaUrl     = snap.child("mediaUrl").getValue(String.class);
            e.mediaType    = snap.child("mediaType").getValue(String.class);
            e.thumbnailUrl = snap.child("thumbnailUrl").getValue(String.class);
            e.status       = snap.child("status").getValue(String.class);
            e.deleted      = Boolean.TRUE.equals(snap.child("deleted").getValue(Boolean.class)) ? 1 : 0;
            e.replyToId    = snap.child("replyToId").getValue(String.class);
            e.replyToText  = snap.child("replyToText").getValue(String.class);
            Long ts        = snap.child("timestamp").getValue(Long.class);
            e.timestamp    = ts != null ? ts : System.currentTimeMillis();
            return e;
        } catch (Exception ex) { return null; }
    }
}
