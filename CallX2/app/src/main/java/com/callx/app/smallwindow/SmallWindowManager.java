package com.callx.app.smallwindow;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SmallWindowManager — Chat ko ek chhoti floating window mein poora functional
 * dikhao (WhatsApp/Messenger chat-head style), placeholder nahi:
 *
 *  - Real contact avatar (Glide, circleCrop)
 *  - Live message list — last ~20 messages, real-time (Firebase listener)
 *  - Real send box — yahin se type + send karo, ChatActivity kholne ki zaroorat nahi
 *  - Live presence status ("Online" / "Offline")
 *  - Expand button → poori ChatActivity khulti hai (deep link via userId)
 *  - Minimize → corner bubble
 *  - Close button
 *  - Android 8+ TYPE_APPLICATION_OVERLAY use karta hai
 *
 * Note: yeh ChatActivity ka poora pipeline replicate nahi karta (Room cache,
 * WorkManager, view-once, disappearing messages, media send, paging) — sirf
 * text chat ka real, live, working core: dekhna + bhejna, seedha window se.
 *
 * Usage:
 *   SmallWindowManager.getInstance().show(context, uid, name, status, photoUrl);
 *   SmallWindowManager.getInstance().dismiss(context);
 *
 * Requires: android.permission.SYSTEM_ALERT_WINDOW
 */
public class SmallWindowManager {

    private static final String TAG = "SmallWindowManager";
    private static final int MAX_MESSAGES = 20;

    private static SmallWindowManager instance;

    public static SmallWindowManager getInstance() {
        if (instance == null) instance = new SmallWindowManager();
        return instance;
    }

    private View    smallWindowView;
    private View    bubbleView;
    private boolean isMinimized = false;

    // ── Cached userId/name/status/photo for bubble restore ─────────────────
    private String cachedUserId;
    private String cachedName;
    private String cachedStatus;
    private String cachedPhoto;

    // ── Live Firebase listeners (messages + presence) ──────────────────────
    private Query              messagesQuery;
    private ValueEventListener messagesListener;
    private DatabaseReference  presenceRef;
    private ValueEventListener presenceListener;

    private SmallWindowMessageAdapter adapter;

    // ── Drag state ────────────────────────────────────────────────────────
    private int   initialX, initialY;
    private float initialTouchX, initialTouchY;

    // ─────────────────────────────────────────────────────────────────────

    /** Backward-compatible overload (no photo). */
    public void show(Context context, String userId, String name, String status) {
        show(context, userId, name, status, null);
    }

    /**
     * Small window dikhao — real live mini-chat.
     *
     * @param context  Application context
     * @param userId   Firebase UID — messages/presence binding + reopen chat ke liye
     * @param name     Contact / chat ka naam
     * @param status   Initial status text (live presence isse turant overwrite kar degi)
     * @param photoUrl Contact ka avatar URL (nullable)
     */
    public void show(Context context, String userId, String name, String status, String photoUrl) {
        if (smallWindowView != null || bubbleView != null) dismiss(context); // purana remove karo

        // Cache for bubble restore / reopen
        if (userId   != null) cachedUserId = userId;
        if (name     != null) cachedName   = name;
        if (status   != null) cachedStatus = status;
        if (photoUrl != null) cachedPhoto  = photoUrl;

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        LayoutInflater inflater = LayoutInflater.from(
            new android.view.ContextThemeWrapper(context, R.style.Theme_CallX));
        smallWindowView = inflater.inflate(R.layout.layout_small_window, null);

        // ── Window params ─────────────────────────────────────────────────
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            dpToPx(context, 300),
            dpToPx(context, 400),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 32;
        params.y = 100;

        // ── Bind static views ───────────────────────────────────────────────
        TextView    tvName     = smallWindowView.findViewById(R.id.tv_sw_name);
        TextView    tvStatus   = smallWindowView.findViewById(R.id.tv_sw_status);
        ImageView   ivAvatar   = smallWindowView.findViewById(R.id.iv_sw_avatar_small);
        ImageView   ivAvatarBg = smallWindowView.findViewById(R.id.iv_sw_avatar);
        ImageButton btnMin     = smallWindowView.findViewById(R.id.btn_sw_minimize);
        ImageButton btnClose   = smallWindowView.findViewById(R.id.btn_sw_close);
        ImageButton btnExpand  = smallWindowView.findViewById(R.id.btn_sw_expand);
        RecyclerView rvMessages = smallWindowView.findViewById(R.id.rv_sw_messages);
        View        emptyState = smallWindowView.findViewById(R.id.ll_sw_empty_state);
        EditText    etMessage  = smallWindowView.findViewById(R.id.et_sw_message);
        ImageButton btnSend    = smallWindowView.findViewById(R.id.btn_sw_send);

        if (tvName   != null) tvName.setText(cachedName != null ? cachedName : "");
        if (tvStatus != null) tvStatus.setText(cachedStatus != null ? cachedStatus : "");

        loadAvatar(context, ivAvatar,   cachedPhoto);
        loadAvatar(context, ivAvatarBg, cachedPhoto);

        // ── Real mini-chat message list ─────────────────────────────────────
        if (rvMessages != null) {
            adapter = new SmallWindowMessageAdapter();
            adapter.setMyUid(FirebaseUtils.getCurrentUid());
            LinearLayoutManager lm = new LinearLayoutManager(context);
            rvMessages.setLayoutManager(lm);
            rvMessages.setAdapter(adapter);
        }

        // ── Live data: messages + presence (real content, not static) ──────
        attachLiveMessages(rvMessages, emptyState);
        attachLivePresence(tvStatus);

        // ── Send box — real send, not a preview ─────────────────────────────
        final WindowManager fwm = wm;
        if (etMessage != null) {
            // Overlay window is FLAG_NOT_FOCUSABLE by default (so drag works
            // without stealing focus from whatever's underneath). Grant focus
            // + show keyboard only while the user is actually typing.
            etMessage.setOnClickListener(v -> requestInputFocus(context, fwm, params, etMessage));
            etMessage.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) releaseInputFocus(context, fwm, params, etMessage);
            });
            etMessage.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage(context, etMessage);
                    releaseInputFocus(context, fwm, params, etMessage);
                    return true;
                }
                return false;
            });
        }
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> sendMessage(context, etMessage));
        }

        // ── Expand → open full ChatActivity ─────────────────────────────────
        if (btnExpand != null) {
            btnExpand.setOnClickListener(v -> openChatAndDismiss(context));
        }

        // ── Drag logic on the drag-bar only (message list + input need their
        //    own touch handling — RecyclerView scroll, EditText typing) ─────
        View dragBar = smallWindowView.findViewById(R.id.ll_sw_dragbar);
        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX      = params.x;
                        initialY      = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging    = false;
                        return false;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            try { fwm.updateViewLayout(smallWindowView, params); } catch (Exception ignored) {}
                            return true;
                        }
                        return false;

                    case MotionEvent.ACTION_UP:
                        return isDragging;
                }
                return false;
            }
        };
        if (dragBar != null) dragBar.setOnTouchListener(dragListener);
        else smallWindowView.setOnTouchListener(dragListener); // fallback

        // ── Minimize button ───────────────────────────────────────────────
        if (btnMin != null) {
            btnMin.setOnClickListener(v -> minimize(context, wm, params));
        }

        // ── Close button ──────────────────────────────────────────────────
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                dismiss(context);
                try {
                    context.stopService(new android.content.Intent(context, SmallWindowService.class));
                } catch (Exception ignored) {}
            });
        }

        // Vivo/FuntouchOS: canDrawOverlays() sometimes lies — do a real permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(context)) {
            Toast.makeText(context,
                "'Display over other apps' permission nahi hai. Settings > Apps > Special app access > Display over other apps mein ON karo.",
                Toast.LENGTH_LONG).show();
            detachLiveListeners();
            smallWindowView = null;
            return;
        }

        try {
            wm.addView(smallWindowView, params);
            isMinimized = false;
        } catch (WindowManager.BadTokenException e) {
            Log.e(TAG, "BadTokenException — overlay permission denied by system", e);
            Toast.makeText(context,
                "Floating window permission nahi mili. Settings mein jaake ON karo.",
                Toast.LENGTH_LONG).show();
            detachLiveListeners();
            smallWindowView = null;
        } catch (Exception e) {
            Log.e(TAG, "addView failed", e);
            Toast.makeText(context, "Floating window nahi khul saka: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            detachLiveListeners();
            smallWindowView = null;
        }
    }

    // ── Keyboard focus toggle (floating windows must opt in per-interaction) ──

    private void requestInputFocus(Context context, WindowManager wm, WindowManager.LayoutParams params, EditText et) {
        if (smallWindowView == null) return;
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        try {
            wm.updateViewLayout(smallWindowView, params);
        } catch (Exception ignored) {}
        et.requestFocus();
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
    }

    private void releaseInputFocus(Context context, WindowManager wm, WindowManager.LayoutParams params, EditText et) {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (smallWindowView != null) {
            try { wm.updateViewLayout(smallWindowView, params); } catch (Exception ignored) {}
        }
    }

    // ── Real send — pushes straight to Firebase, same "messages/{chatId}"
    //    node + contacts last-message summary that ChatActivity reads ───────

    private void sendMessage(Context context, EditText etMessage) {
        if (etMessage == null) return;
        String text = etMessage.getText() != null ? etMessage.getText().toString().trim() : "";
        if (text.isEmpty()) return;
        if (cachedUserId == null || cachedUserId.isEmpty()) {
            Toast.makeText(context, "Chat nahi mila", Toast.LENGTH_SHORT).show();
            return;
        }
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty()) return;

        String chatId = FirebaseUtils.getChatId(myUid, cachedUserId);
        String key = FirebaseUtils.getMessagesRef(chatId).push().getKey();
        if (key == null) return;

        long ts = System.currentTimeMillis();

        Message m = new Message();
        m.id         = key;
        m.messageId  = key;
        m.senderId   = myUid;
        m.senderName = FirebaseUtils.getCurrentName();
        m.text       = text;
        m.type       = "text";
        m.timestamp  = ts;
        m.status     = "sent";

        FirebaseUtils.getMessagesRef(chatId).child(key).setValue(m)
            .addOnFailureListener(e -> Toast.makeText(context, "Message send nahi hua", Toast.LENGTH_SHORT).show());

        // Keep chat-list "last message" summary in sync on both sides —
        // same convention ChatActivity's ChatMessageSender uses.
        Map<String, Object> myUpd = new HashMap<>();
        myUpd.put("lastMessage",         text);
        myUpd.put("lastTs",              ts);
        myUpd.put("lastMessageType",     "text");
        myUpd.put("lastMessageSenderUid", myUid);
        myUpd.put("lastMessageStatus",   "sent");
        myUpd.put("lastMessageId",       key);
        FirebaseUtils.getContactsRef(myUid).child(cachedUserId).updateChildren(myUpd);

        Map<String, Object> theirUpd = new HashMap<>(myUpd);
        FirebaseUtils.getContactsRef(cachedUserId).child(myUid).updateChildren(theirUpd);

        etMessage.setText("");
    }

    // ── Live message list ────────────────────────────────────────────────

    private void attachLiveMessages(RecyclerView rv, View emptyState) {
        if (rv == null || cachedUserId == null || cachedUserId.isEmpty()) return;

        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty()) return;

        String chatId = FirebaseUtils.getChatId(myUid, cachedUserId);

        messagesQuery = FirebaseUtils.getMessagesRef(chatId).limitToLast(MAX_MESSAGES);
        messagesListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (smallWindowView == null || adapter == null) return; // window dismissed

                List<SmallWindowMessageAdapter.SwMsg> list = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    String senderId = child.child("senderId").getValue(String.class);
                    String text     = child.child("text").getValue(String.class);
                    String type     = child.child("type").getValue(String.class);
                    Long   ts       = child.child("timestamp").getValue(Long.class);
                    list.add(new SmallWindowMessageAdapter.SwMsg(
                        child.getKey(), senderId, text, type, ts != null ? ts : 0L));
                }

                adapter.submit(list);

                if (emptyState != null) {
                    emptyState.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                }
                if (!list.isEmpty()) {
                    rv.scrollToPosition(adapter.getItemCount() - 1);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w(TAG, "messages listener cancelled: " + error.getMessage());
            }
        };
        messagesQuery.addValueEventListener(messagesListener);
    }

    // ── Live presence (Online / Offline) ──────────────────────────────────

    private void attachLivePresence(TextView tvStatus) {
        if (tvStatus == null || cachedUserId == null || cachedUserId.isEmpty()) return;

        presenceRef = FirebaseUtils.getUserRef(cachedUserId);
        presenceListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (smallWindowView == null) return;
                Boolean online = snapshot.child("online").getValue(Boolean.class);
                tvStatus.setText(Boolean.TRUE.equals(online) ? "Online" : "Offline");
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w(TAG, "presence listener cancelled: " + error.getMessage());
            }
        };
        presenceRef.addValueEventListener(presenceListener);
    }

    private void detachLiveListeners() {
        if (messagesQuery != null && messagesListener != null) {
            try { messagesQuery.removeEventListener(messagesListener); } catch (Exception ignored) {}
        }
        messagesQuery    = null;
        messagesListener = null;

        if (presenceRef != null && presenceListener != null) {
            try { presenceRef.removeEventListener(presenceListener); } catch (Exception ignored) {}
        }
        presenceRef      = null;
        presenceListener = null;

        adapter = null;
    }

    // ── Avatar loading ───────────────────────────────────────────────────────

    private void loadAvatar(Context context, ImageView iv, String photoUrl) {
        if (iv == null) return;
        iv.setImageTintList(null); // clear placeholder tint so a real photo isn't recolored
        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(context.getApplicationContext())
                .load(photoUrl)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(iv);
        } else {
            iv.setImageResource(R.drawable.ic_person);
        }
    }

    // ── Expand → reopen full chat ───────────────────────────────────────────

    private void openChatAndDismiss(Context context) {
        try {
            Class<?> chatCls = Class.forName("com.callx.app.conversation.ChatActivity");
            Intent intent = new Intent(context, chatCls);
            intent.putExtra("partnerUid",   cachedUserId != null ? cachedUserId : "");
            intent.putExtra("partnerName",  cachedName   != null ? cachedName   : "");
            intent.putExtra("partnerPhoto", cachedPhoto  != null ? cachedPhoto  : "");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "ChatActivity not found for reopen", e);
        }
        dismiss(context);
        try {
            context.stopService(new Intent(context, SmallWindowService.class));
        } catch (Exception ignored) {}
    }

    // ── Minimize → corner bubble ──────────────────────────────────────────

    private void minimize(Context context, WindowManager wm, WindowManager.LayoutParams swParams) {
        if (smallWindowView == null || isMinimized) return;
        isMinimized = true;

        try { wm.removeView(smallWindowView); } catch (Exception ignored) {}
        smallWindowView = null;
        // Live listeners stay attached (cheap) so bubble restore is instant + fresh;
        // they get detached in dismiss().

        LayoutInflater inflater = LayoutInflater.from(
            new android.view.ContextThemeWrapper(context, R.style.Theme_CallX));
        bubbleView = inflater.inflate(R.layout.layout_small_window_bubble, null);

        ImageView ivBubbleAvatar = bubbleView.findViewById(R.id.iv_bubble_avatar);
        loadAvatar(context, ivBubbleAvatar, cachedPhoto);

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams bubbleParams = new WindowManager.LayoutParams(
            dpToPx(context, 56),
            dpToPx(context, 56),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT);

        bubbleParams.gravity = Gravity.TOP | Gravity.END;
        bubbleParams.x = 24;
        bubbleParams.y = 80;

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX      = bubbleParams.x;
                        initialY      = bubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging    = false;
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            bubbleParams.x = initialX - (int) dx;
                            bubbleParams.y = initialY + (int) dy;
                            try { wm.updateViewLayout(bubbleView, bubbleParams); } catch (Exception ignored) {}
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            try { wm.removeView(bubbleView); } catch (Exception ignored) {}
                            bubbleView  = null;
                            isMinimized = false;
                            // Restore with cached userId/name/status/photo
                            show(context, cachedUserId, cachedName, cachedStatus, cachedPhoto);
                        }
                        return true;
                }
                return false;
            }
        });

        wm.addView(bubbleView, bubbleParams);
    }

    // ── Dismiss ───────────────────────────────────────────────────────────

    public void dismiss(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        if (smallWindowView != null) {
            try { wm.removeView(smallWindowView); } catch (Exception ignored) {}
            smallWindowView = null;
        }
        if (bubbleView != null) {
            try { wm.removeView(bubbleView); } catch (Exception ignored) {}
            bubbleView = null;
        }
        detachLiveListeners();
        isMinimized  = false;
        cachedUserId = null;
        cachedName   = null;
        cachedStatus = null;
        cachedPhoto  = null;
    }

    public boolean isShowing() {
        return smallWindowView != null || bubbleView != null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private int dpToPx(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
