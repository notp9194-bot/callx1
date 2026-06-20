package com.callx.app.conversation.controllers;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import com.callx.app.chat.R;
import com.callx.app.chat.ui.GifAwareEditText;
import com.callx.app.models.Message;
import com.callx.app.utils.TypingStyleManager;
import com.callx.app.utils.UnicodeStyler;

import java.util.concurrent.Executors;

/**
 * ChatInputController — extracted from ChatActivity v21.
 *
 * Handles:
 *   setupInputBar (TextWatcher, send/mic/attach/camera clicks)
 *   sendTextMessage
 *   typing debounce callbacks
 *   saveDraft / restoreDraft
 */
public class ChatInputController {

    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final ChatActivityDelegate d;
    private final ChatPresenceController presenceController;

    private final Handler  typingHandler      = new Handler(Looper.getMainLooper());
    private final Runnable stopTypingRunnable = this::onStopTypingTimeout;

    public ChatInputController(ChatActivityDelegate delegate,
                               ChatPresenceController presenceController) {
        this.d                 = delegate;
        this.presenceController = presenceController;
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    public void setupInputBar(ChatMediaController mediaController) {
        d.getBinding().etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                boolean hasText = s.toString().trim().length() > 0;
                d.getBinding().btnSend.setVisibility(hasText ? View.VISIBLE : View.GONE);
                d.getBinding().btnMic .setVisibility(hasText ? View.GONE   : View.VISIBLE);

                int remaining = MAX_MESSAGE_LENGTH - s.length();
                if (remaining <= 200) {
                    d.getBinding().etMessage.setError(remaining < 0
                        ? "Limit exceeded! (" + Math.abs(remaining) + " extra)"
                        : remaining + " characters remaining");
                } else {
                    d.getBinding().etMessage.setError(null);
                }

                if (hasText) {
                    presenceController.setOurTypingStatus(true);
                    typingHandler.removeCallbacks(stopTypingRunnable);
                    typingHandler.postDelayed(stopTypingRunnable, 2000);
                } else {
                    typingHandler.removeCallbacks(stopTypingRunnable);
                    presenceController.setOurTypingStatus(false);
                }
            }
        });

        d.getBinding().btnSend  .setOnClickListener(v -> sendTextMessage());
        d.getBinding().btnMic   .setOnClickListener(v -> mediaController.toggleRecording());
        d.getBinding().btnAttach.setOnClickListener(v -> mediaController.showAttachSheet());
        d.getBinding().btnCamera.setOnClickListener(v -> mediaController.launchCamera());

        if (d.getBinding().btnCancelReply != null)
            d.getBinding().btnCancelReply.setOnClickListener(v -> d.clearReply());

        if (d.getBinding().etMessage instanceof GifAwareEditText) {
            ((GifAwareEditText) d.getBinding().etMessage).setGifReceivedListener(contentInfo -> {
                contentInfo.requestPermission();
                mediaController.sendGifMessage(
                    contentInfo.getContentUri(), contentInfo);
            });
        }
    }

    // ── Send text ─────────────────────────────────────────────────────────

    public void sendTextMessage() {
        String text = d.getBinding().etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_MESSAGE_LENGTH) {
            d.getBinding().etMessage.setError(
                "Message too long! Max " + MAX_MESSAGE_LENGTH + " characters allowed.");
            d.showToast("Message too long!");
            return;
        }

        d.getBinding().etMessage.setText("");
        d.getBinding().etMessage.post(() ->
            TypingStyleManager.get(d.getActivity()).applyToInput(d.getBinding().etMessage));

        presenceController.clearOurTypingStatus();
        Executors.newSingleThreadExecutor().execute(() -> {
            if (d.getDb() != null && d.getChatId() != null)
                d.getDb().chatDao().saveDraft(d.getChatId(), "");
        });

        Message m = d.buildOutgoing();
        m.type = "text";
        m.fontStyle = TypingStyleManager.get(d.getActivity()).getCurrentStyle();
        if (m.fontStyle == TypingStyleManager.STYLE_SAMSUNG_SCRIPT) {
            text = UnicodeStyler.toScript(text);
        }
        m.text = text;
        d.pushMessage(m, text);
        d.clearReply();
    }

    // ── Typing debounce ───────────────────────────────────────────────────

    private void onStopTypingTimeout() {
        presenceController.setOurTypingStatus(false);
    }

    public void clearTypingOnPause() {
        presenceController.clearOurTypingStatus();
        typingHandler.removeCallbacks(stopTypingRunnable);
    }

    public void releaseTypingHandler() {
        typingHandler.removeCallbacks(stopTypingRunnable);
    }

    // ── Draft ─────────────────────────────────────────────────────────────

    public void saveDraft() {
        if (d.getDb() == null || d.getChatId() == null || d.getBinding() == null) return;
        String draftText = d.getBinding().etMessage.getText() != null
            ? d.getBinding().etMessage.getText().toString() : "";
        Executors.newSingleThreadExecutor().execute(() ->
            d.getDb().chatDao().saveDraft(d.getChatId(), draftText));
    }

    public void restoreDraft() {
        if (d.getDb() == null || d.getChatId() == null) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            String draft = d.getDb().chatDao().getDraft(d.getChatId());
            if (draft != null && !draft.isEmpty()) {
                d.runOnMain(() -> {
                    if (d.getBinding() != null && d.getBinding().etMessage != null) {
                        d.getBinding().etMessage.setText(draft);
                        d.getBinding().etMessage.setSelection(draft.length());
                    }
                });
            }
        });
    }
}
