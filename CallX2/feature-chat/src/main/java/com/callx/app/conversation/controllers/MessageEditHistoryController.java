package com.callx.app.conversation.controllers;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.callx.app.models.Message;
import com.callx.app.utils.EditHistoryJsonUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles editing a message's text AND keeping a full history of every
 * prior version, plus the "View edit history" dialog triggered by tapping
 * the ✏️ edited tag on a bubble's timestamp.
 *
 * Storage shape (see EditHistoryJsonUtil for the JSON ↔ List conversion):
 *   Firebase: messages/{id}/editHistory = [{text, editedAt}, ...]   (oldest first)
 *   Room:     messages.editHistoryJson  = same, serialized as a JSON string
 *
 * Each entry holds what the text WAS *before* a given edit — the CURRENT
 * text always stays in Message#text / messages/{id}/text as before. So a
 * message edited twice has 2 history entries (original + first revision);
 * the dialog also appends the live current text as the final "Current"
 * row so the full lineage is visible in one place.
 */
public class MessageEditHistoryController {

    private final ChatActivityDelegate delegate;

    public MessageEditHistoryController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Edit (own messages only) ─────────────────────────────────────────

    /** Opens the "Edit message" dialog and, on save, pushes the OLD text
     *  into history before overwriting — both in Firebase and Room. */
    public void editMessage(Message m) {
        if (m == null || m.id == null) return;
        if (!delegate.getCurrentUid().equals(m.senderId)) return;

        android.content.Context ctx = delegate.getActivity();
        if (ctx == null) return;

        EditText input = new EditText(ctx);
        input.setText(m.text);
        input.setSelection(input.getText().length());
        int pad = dp(ctx, 16);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setSingleLine(false);
        input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        com.callx.app.utils.AlertDialogStyler.showRounded(
            new AlertDialog.Builder(ctx)
                .setTitle("Edit message")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = input.getText().toString().trim();
                    if (newText.isEmpty()) {
                        delegate.showToast("Message cannot be empty");
                        return;
                    }
                    if (newText.equals(m.text)) return;
                    saveEdit(m, newText);
                })
                .setNegativeButton("Cancel", null)
        .create(), com.callx.app.utils.AlertDialogStyler.DialogSize.WIDE);
    }

    /** How long an ONLINE edit waits for Firebase's ack before it is journaled to the outbox. */
    private static final long OUTBOX_DEFER_MS = 3000L;

    private void saveEdit(Message m, String newText) {
        final long tapNanos = System.nanoTime();
        // Monotonic per message: never reuse/undercut the previous editedAt
        // (clock skew between devices) so the conditional Room update and the
        // adapter's stale-edit guard always see a strictly newer value.
        final long now = System.currentTimeMillis();
        final long editedAt = (m.editedAt != null && m.editedAt >= now) ? m.editedAt + 1 : now;
        final String oldText = m.text;
        final String messageId = m.id;
        final String chatId = delegate.getChatId();
        final String senderId = m.senderId;
        final boolean plainText = m.type == null || "text".equals(m.type);
        final android.content.Context appCtx = delegate.getActivity() != null
                ? delegate.getActivity().getApplicationContext() : null;

        // Append the OLD text as a new history entry on top of whatever
        // history already existed (covers edit #2, #3, ... correctly). Bounded:
        // see EditHistoryJsonUtil#appendVersionCapped (keeps "Original" + newest).
        final int priorSize = m.editHistory != null ? m.editHistory.size() : 0;
        final List<Map<String, Object>> updatedHistory =
                EditHistoryJsonUtil.appendVersionCapped(m.editHistory, oldText, editedAt);
        // Normal case: exactly one new entry -> write ONLY that entry. A capped
        // list (indices shifted) is the rare case that rewrites the whole node.
        final boolean appendOnly = updatedHistory.size() == priorSize + 1;

        // NOTE: the bound Message is NOT mutated here. The adapter's
        // applyLocalEdit() mutates it AND sends exactly one FLAG_TEXT rebind,
        // once the new text's layout is pre-warmed off the UI thread (below).

        // ── Outbox journal: online edits wait for Firebase's ack first ───────
        // state 0 = not journaled, 1 = journaled, 2 = acked. Offline edits (no
        // ack will come) are journaled immediately, exactly as before; online
        // edits only hit the outbox (2 DB writes: enqueue + delete) if the ack
        // has not arrived within OUTBOX_DEFER_MS.
        final java.util.concurrent.atomic.AtomicInteger journal =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final Runnable journalNow = () -> {
            if (appCtx == null || !journal.compareAndSet(0, 1)) return;
            runIo(() -> com.callx.app.sync.OfflineOutbox.enqueueEdit(
                    appCtx, chatId, messageId, newText, editedAt,
                    EditHistoryJsonUtil.historyToJson(updatedHistory), false));
        };

        // Firebase — single multi-path write so listeners get one consistent update.
        // Stays on the calling thread: it is async already and must not depend on
        // the IO executor being alive (user may leave the chat right after Save).
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("text", newText);
        updates.put("edited", true);
        updates.put("editedAt", editedAt);
        if (appendOnly) {
            updates.put("editHistory/" + priorSize, updatedHistory.get(priorSize));
        } else {
            updates.put("editHistory", updatedHistory);
        }
        delegate.getMessagesRef().child(messageId).updateChildren(updates)
                .addOnSuccessListener(ignored -> {
                    // If the deferred journal already fired, drop its row now.
                    if (journal.getAndSet(2) == 1) {
                        runIo(() -> delegate.getDb().outboxOperationDao().delete(
                                "edit:" + chatId + ":" + messageId));
                    }
                })
                .addOnFailureListener(error -> journalNow.run());

        if (delegate.isOnline()) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(journalNow, OUTBOX_DEFER_MS);
        } else {
            journalNow.run();
        }

        // UI + Room — ONE targeted rebind, ONE coalesced Room write.
        // Pre-warm the StaticLayout + markdown/emoji spans on the IO thread, then
        // hop to main: the adapter mutates + notifyItemChanged(pos, FLAG_TEXT)
        // exactly once, and the Activity's existing write batch persists the row
        // (single Room transaction + LastMessagesCache upsert, merged with any other
        // event of the same debounce window).
        final Runnable applyOnMain = () -> {
            com.callx.app.conversation.MessagePagingAdapter ad = delegate.getPagingAdapter();
            final Message applied;
            if (ad != null) {
                applied = ad.applyLocalEdit(m, newText, editedAt, updatedHistory);
            } else {
                // No adapter (shouldn't happen) — keep the old in-place behaviour.
                m.text = newText;
                m.edited = true;
                m.editedAt = editedAt;
                m.editHistory = updatedHistory;
                applied = m;
            }
            if (applied != null && !delegate.queueLocalEditWrite(applied)) {
                // Host has no write batch — direct conditional DAO write (see MessageDao).
                runIo(() -> delegate.getDb().messageDao().updateTextWithHistory(
                        messageId, newText, editedAt,
                        EditHistoryJsonUtil.historyToJson(updatedHistory)));
            }
            com.callx.app.debug.OpenPerfLog.acc("edit.tapToApply", System.nanoTime() - tapNanos);
        };
        boolean queued = false;
        if (plainText) {
            try {
                delegate.getIoExecutor().execute(() -> {
                    com.callx.app.conversation.canvas.MessageBubbleCanvasView
                            .precomputeTextLayoutIfPossible(newText, false, senderId);
                    delegate.runOnMain(applyOnMain);
                });
                queued = true;
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // executor already shut down — fall through to the direct path
            }
        }
        if (!queued) applyOnMain.run();

        delegate.showToast("Message edited");
    }

    /** IO-executor hop that survives a shut-down executor (falls back to the shared bg pool). */
    private void runIo(Runnable r) {
        try {
            delegate.getIoExecutor().execute(r);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            com.callx.app.utils.AppBgExecutor.execute(r);
        }
    }

    // ── View history (tap the "edited" tag) ──────────────────────────────

    /** Shows every prior version of this message, oldest first, ending
     *  with the current text. No-ops silently if there's nothing to show
     *  (shouldn't normally be reachable since the tag only renders when
     *  Message#edited is true, but guards against stale/partial data). */
    public void showHistory(Message m) {
        if (m == null) return;
        android.content.Context ctx = delegate.getActivity();
        if (ctx == null) return;

        List<Map<String, Object>> history = m.editHistory;
        if (history == null || history.isEmpty()) {
            // Edited flag is set but no history payload reached us yet
            // (e.g. older message edited before this feature existed).
            Toast.makeText(ctx, "No earlier versions available", Toast.LENGTH_SHORT).show();
            return;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault());

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        container.setPadding(pad, dp(ctx, 8), pad, dp(ctx, 8));
        scroll.addView(container);

        // Oldest prior versions first...
        for (int i = 0; i < history.size(); i++) {
            Map<String, Object> entry = history.get(i);
            String label = (i == 0) ? "Original" : "Edited";
            addVersionRow(ctx, container, label,
                    EditHistoryJsonUtil.textOf(entry),
                    EditHistoryJsonUtil.editedAtOf(entry),
                    fmt);
        }
        // ...then the live current text last, clearly marked.
        long currentAt = m.editedAt != null ? m.editedAt : 0L;
        addVersionRow(ctx, container, "Current", m.text != null ? m.text : "", currentAt, fmt);

        com.callx.app.utils.AlertDialogStyler.showRounded(
            new AlertDialog.Builder(ctx)
                .setTitle("Edit history")
                .setView(scroll)
                .setPositiveButton("Close", null)
        .create(), com.callx.app.utils.AlertDialogStyler.DialogSize.WIDE);
    }

    private void addVersionRow(android.content.Context ctx, LinearLayout container,
                                String label, String text, long at, SimpleDateFormat fmt) {
        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label + (at > 0 ? "  ·  " + fmt.format(new Date(at)) : ""));
        tvLabel.setTextSize(11);
        tvLabel.setTypeface(tvLabel.getTypeface(), android.graphics.Typeface.BOLD);
        tvLabel.setTextColor(0xFF888888);
        tvLabel.setPadding(0, dp(ctx, 12), 0, dp(ctx, 2));
        container.addView(tvLabel);

        TextView tvText = new TextView(ctx);
        tvText.setText(text);
        tvText.setTextSize(15);
        tvText.setTextColor(0xFF222222);
        container.addView(tvText);

        android.view.View divider = new android.view.View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.topMargin = dp(ctx, 10);
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(0xFFE0E0E0);
        container.addView(divider);
    }

    private int dp(android.content.Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }
}
