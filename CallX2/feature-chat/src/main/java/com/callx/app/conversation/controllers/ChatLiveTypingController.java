package com.callx.app.conversation.controllers;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

/**
 * Live typing PREVIEW — separate from ChatPresenceController's plain
 * boolean "typing"/{chatId}/{uid} flag (which only drives the 3-dot
 * ll_typing_strip pill). This controller mirrors the partner's actual
 * in-progress draft text, character by character, into
 * ll_live_typing_preview / tv_live_typing_preview — including deletions,
 * so the box updates live as they type AND as they erase.
 *
 * Firebase path: typingContent/{chatId}/{uid} = "<current draft text>"
 * Cleared (set to "") on send, on text-empty, and on screen leave.
 */
public class ChatLiveTypingController {

    private static final long WRITE_DEBOUNCE_MS = 150;
    private static final String NODE = "typingContent";

    private final ChatActivityDelegate delegate;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ValueEventListener partnerPreviewListener;
    private String lastWrittenValue = null;
    private Runnable pendingWrite;

    public ChatLiveTypingController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init / teardown ─────────────────────────────────────────────────

    public void init() {
        watchPartnerLivePreview();
    }

    public void destroy() {
        if (partnerPreviewListener != null && delegate.getChatId() != null) {
            contentRef().removeEventListener(partnerPreviewListener);
            partnerPreviewListener = null;
        }
        if (pendingWrite != null) {
            handler.removeCallbacks(pendingWrite);
            pendingWrite = null;
        }
        // Best-effort clear so we don't leave a stale draft visible to the
        // partner after we leave the screen.
        clearOurPreview();
    }

    // ── Outgoing: push OUR current draft text (debounced) ───────────────

    /** Call this from the input bar's TextWatcher.onTextChanged, alongside
     *  the existing presenceController.setOurTypingStatus() call. */
    public void onOurTextChanged(String currentText) {
        // Live text mirror disabled — no-op
    }

    /** Call on send / clear-input so the partner's box empties immediately
     *  instead of lingering until the next debounce tick. */
    public void clearOurPreview() {
        if (pendingWrite != null) {
            handler.removeCallbacks(pendingWrite);
            pendingWrite = null;
        }
        writeOurPreview("");
    }

    private void writeOurPreview(String text) {
        if (delegate.getChatId() == null || delegate.getCurrentUid() == null) return;
        // Avoid redundant writes (e.g. repeated empty-string clears).
        if (text != null && text.equals(lastWrittenValue)) return;
        lastWrittenValue = text;
        contentRef().setValue(text == null ? "" : text);
    }

    // ── Incoming: mirror PARTNER's live draft text into the preview box ─

    private void watchPartnerLivePreview() {
        // Live text mirror disabled — no-op
    }

    private void showPartnerPreview(String text) {
        // Live text mirror disabled — always hidden
        ActivityChatBinding binding = delegate.getBinding();
        if (binding != null && binding.llLiveTypingPreview != null) {
            binding.llLiveTypingPreview.setVisibility(View.GONE);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private DatabaseReference contentRef() {
        return FirebaseUtils.db().getReference(NODE)
                .child(delegate.getChatId())
                .child(delegate.getCurrentUid());
    }
}
