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
                .show();
    }

    private void saveEdit(Message m, String newText) {
        long editedAt = System.currentTimeMillis();
        String oldText = m.text;

        // Append the OLD text as a new history entry on top of whatever
        // history already existed (covers edit #2, #3, ... correctly).
        List<Map<String, Object>> updatedHistory =
                EditHistoryJsonUtil.appendVersion(m.editHistory, oldText, editedAt);
        String historyJson = EditHistoryJsonUtil.historyToJson(updatedHistory);

        // Local model — so the bound bubble reflects the new state immediately
        // even before Firebase/Room round-trip back through the paging source.
        m.text = newText;
        m.edited = true;
        m.editedAt = editedAt;
        m.editHistory = updatedHistory;

        // Firebase — single multi-path write so listeners get one consistent update.
        java.util.Map<String, Object> updates = new java.util.HashMap<>();
        updates.put("text", newText);
        updates.put("edited", true);
        updates.put("editedAt", editedAt);
        updates.put("editHistory", updatedHistory);
        delegate.getMessagesRef().child(m.id).updateChildren(updates);

        // Room — single write via the history-aware DAO method.
        delegate.getIoExecutor().execute(() ->
                delegate.getDb().messageDao()
                        .updateTextWithHistory(m.id, newText, editedAt, historyJson));

        delegate.showToast("Message edited");
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

        new AlertDialog.Builder(ctx)
                .setTitle("Edit history")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
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
