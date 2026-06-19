package com.callx.app.conversation.delegates;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.NonNull;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.google.firebase.database.DatabaseReference;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * ChatNetworkDelegate — Connectivity monitoring, offline banner, retry pending messages,
 *                        expiry cleanup, clear chat, small window launcher.
 */
public class ChatNetworkDelegate {

    public interface Callback {
        void onNetworkAvailable();
        void onNetworkLost();
        void retryPendingMessages();
        void updateSendButtons(boolean online);
        void runOnUiThread(Runnable r);
    }

    private final Activity            activity;
    private final ActivityChatBinding binding;
    private final String              chatId;
    private final String              currentUid;
    private final String              partnerUid;
    private final String              partnerName;
    private final AppDatabase         db;
    private final Executor            ioExecutor;
    private final DatabaseReference   messagesRef;
    private final Callback            callback;

    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public ChatNetworkDelegate(Activity activity, ActivityChatBinding binding, String chatId,
                               String currentUid, String partnerUid, String partnerName,
                               AppDatabase db, Executor ioExecutor, DatabaseReference messagesRef,
                               Callback callback) {
        this.activity    = activity; this.binding = binding; this.chatId = chatId;
        this.currentUid  = currentUid; this.partnerUid = partnerUid; this.partnerName = partnerName;
        this.db = db; this.ioExecutor = ioExecutor; this.messagesRef = messagesRef; this.callback = callback;
    }

    // ── Network monitor ───────────────────────────────────────────────────

    public void setupNetworkMonitor() {
        ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        boolean online = isOnline(cm);
        updateOfflineBanner(!online);
        callback.updateSendButtons(online);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network n) {
                handler.post(() -> {
                    updateOfflineBanner(false); callback.updateSendButtons(true);
                    callback.onNetworkAvailable(); callback.retryPendingMessages();
                });
            }
            @Override public void onLost(@NonNull Network n) {
                handler.post(() -> {
                    updateOfflineBanner(true); callback.updateSendButtons(false);
                    callback.onNetworkLost();
                });
            }
        };
        cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
    }

    private void updateOfflineBanner(boolean offline) {
        if (binding.llOfflineBanner == null) return;
        binding.llOfflineBanner.setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    public boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
        return isOnline(cm);
    }

    private boolean isOnline(ConnectivityManager cm) {
        if (cm == null) return true;
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ── Expiry cleanup ────────────────────────────────────────────────────

    public void scheduleExpiryCleanup() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                deleteExpiredFromFirebase();
                handler.postDelayed(this, 60_000L);
            }
        }, 30_000L);
    }

    private void deleteExpiredFromFirebase() {
        ioExecutor.execute(() -> {
            if (db == null || chatId == null) return;
            long now = System.currentTimeMillis();
            List<MessageEntity> expired = db.messageDao().getExpiredMessages(chatId, now);
            if (expired == null || expired.isEmpty()) return;
            for (MessageEntity me : expired) {
                if (messagesRef != null && me.id != null) messagesRef.child(me.id).removeValue();
            }
            db.messageDao().deleteExpiredMessages(chatId, now);
        });
    }

    // ── Clear chat ────────────────────────────────────────────────────────

    public void confirmClearChat() {
        new android.app.AlertDialog.Builder(activity)
                .setTitle("Clear chat?").setMessage("All messages will be deleted locally.")
                .setPositiveButton("Clear", (d, w) -> {
                    ioExecutor.execute(() -> db.messageDao().deleteAllForChat(chatId));
                    com.callx.app.cache.CacheManager.getInstance(activity).invalidateMessages(chatId);
                    android.widget.Toast.makeText(activity, "Chat cleared", android.widget.Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show();
    }

    // ── Small Window ──────────────────────────────────────────────────────

    public void openSmallWindow() {
        android.content.Context appCtx = activity.getApplicationContext();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(appCtx)) {
            android.content.Intent pi = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + appCtx.getPackageName()));
            activity.startActivity(pi);
            android.widget.Toast.makeText(activity,
                    "'Display over other apps' permission dijiye phir Small Window use karo",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String name = partnerName != null ? partnerName : "Chat";
        try {
            Class<?> cls = Class.forName("com.callx.app.smallwindow.SmallWindowService");
            android.content.Intent svc = new android.content.Intent(appCtx, cls);
            svc.putExtra("name", name); svc.putExtra("status", "CallX Small Window");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                appCtx.startForegroundService(svc);
            else appCtx.startService(svc);
            activity.moveTaskToBack(true);
        } catch (ClassNotFoundException e) {
            android.widget.Toast.makeText(activity, "Small Window unavailable", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    public void detach() {
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
            if (cm != null) { try { cm.unregisterNetworkCallback(networkCallback); } catch (IllegalArgumentException ignored) {} }
        }
        handler.removeCallbacksAndMessages(null);
    }
}
