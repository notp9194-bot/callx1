package com.callx.app.conversation.controllers;

import android.app.Activity;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.models.Message;
import com.google.firebase.database.DatabaseReference;
import java.util.concurrent.Executor;

/**
 * Delegate interface exposed by ChatActivity to all controller classes.
 * Controllers call back into the Activity via this interface instead of
 * holding a direct hard reference to ChatActivity.
 */
public interface ChatActivityDelegate {

    // ── Binding ────────────────────────────────────────────────────────────
    ActivityChatBinding getBinding();

    // ── Identity ──────────────────────────────────────────────────────────
    String getChatId();
    String getPartnerUid();
    String getPartnerName();
    String getPartnerPhoto();
    String getPartnerThumb();
    String getCurrentUid();
    String getCurrentName();

    // ── Database & executor ───────────────────────────────────────────────
    AppDatabase getDb();
    Executor getIoExecutor();

    // ── Firebase ──────────────────────────────────────────────────────────
    DatabaseReference getMessagesRef();

    // ── Network ───────────────────────────────────────────────────────────
    boolean isOnline();

    // ── Mute state ────────────────────────────────────────────────────────
    boolean isMuted();
    void setMuted(boolean muted);

    // ── Block state ───────────────────────────────────────────────────────
    boolean isBlocked();
    void setBlocked(boolean blocked);
    boolean isPartnerPermaBlockedMe();
    void setPartnerPermaBlockedMe(boolean val);
    boolean isIPermaBlockedPartner();
    void setIPermaBlockedPartner(boolean val);

    // ── Recording state ───────────────────────────────────────────────────
    boolean isRecording();
    void setRecording(boolean recording);

    // ── UI helpers ────────────────────────────────────────────────────────
    void runOnMain(Runnable r);
    void showToast(String msg);
    void invalidateMenu();

    // ── Message operations ────────────────────────────────────────────────
    Message buildOutgoing();
    void pushMessage(Message m, String previewText);
    void firebasePushMessage(Message m, String key, String previewText);
    void clearReply();
    void startReply(Message m);
    void activateReplyDirect(Message m);
    void navigateToOriginal(String messageId);

    // ── Adapter ───────────────────────────────────────────────────────────
    MessagePagingAdapter getPagingAdapter();

    // ── Fragment manager ──────────────────────────────────────────────────
    androidx.fragment.app.FragmentManager getSupportFragmentManager();

    // ── Activity context ──────────────────────────────────────────────────
    Activity getActivity();

    // ── Theme / wallpaper refresh ─────────────────────────────────────────
    void refreshScreenTheme();
    void refreshWallpaper();

    // ── Wallpaper picker launcher ─────────────────────────────────────────
    void launchWallpaperPicker();

    // ── Poll creation ─────────────────────────────────────────────────────
    void launchPollCreator();
}
