package com.callx.app.conversation.controllers;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.callx.app.chat.R;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.models.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatMessageActionController — extracted from ChatActivity v21.
 *
 * Handles all per-message actions:
 *   copy, edit, toggleStar, sendReaction, confirmDelete,
 *   forward single/multi, showMessageInfo, multiSelect toolbar.
 */
public class ChatMessageActionController {

    private final ChatActivityDelegate d;

    public ChatMessageActionController(ChatActivityDelegate delegate) {
        this.d = delegate;
    }

    // ── Copy ──────────────────────────────────────────────────────────────

    public void copyText(Message m) {
        if (m.text == null || m.text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager)
            d.getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message", m.text));
            d.showToast("Message copied");
        }
    }

    // ── Edit ──────────────────────────────────────────────────────────────

    public void editMessage(Message m) {
        if (m == null || m.id == null) return;
        if (!d.getCurrentUid().equals(m.senderId)) return;

        android.widget.EditText input = new android.widget.EditText(d.getActivity());
        input.setText(m.text);
        input.setSelection(input.getText().length());
        int pad = dp(16);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setSingleLine(false);
        input.setMaxLines(6);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        new AlertDialog.Builder(d.getActivity())
                .setTitle("Edit message").setView(input)
                .setPositiveButton("Save", (dlg, w) -> {
                    String newText = input.getText().toString().trim();
                    if (newText.isEmpty()) {
                        d.showToast("Message cannot be empty"); return;
                    }
                    if (newText.equals(m.text)) return;
                    long editedAt = System.currentTimeMillis();
                    d.getMessagesRef().child(m.id).child("text").setValue(newText);
                    d.getMessagesRef().child(m.id).child("edited").setValue(true);
                    d.getMessagesRef().child(m.id).child("editedAt").setValue(editedAt);
                    d.getIoExecutor().execute(() ->
                        d.getDb().messageDao().updateText(m.id, newText, editedAt));
                    d.showToast("Message edited");
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ── Star ──────────────────────────────────────────────────────────────

    public void toggleStar(Message m) {
        boolean nowStarred = !Boolean.TRUE.equals(m.starred);
        d.getIoExecutor().execute(() ->
            d.getDb().messageDao().updateStarred(m.id, nowStarred));
        d.getMessagesRef().child(m.id).child("starred").setValue(nowStarred);
    }

    // ── Reaction ──────────────────────────────────────────────────────────

    public void sendReaction(Message m, String emoji) {
        if (m.id == null) return;
        d.getMessagesRef().child(m.id).child("reactions")
            .child(d.getCurrentUid()).setValue(emoji);
    }

    // ── Delete single ─────────────────────────────────────────────────────

    public void confirmDeleteMessage(Message m) {
        boolean isMine = d.getCurrentUid() != null &&
                         d.getCurrentUid().equals(m.senderId);

        AlertDialog.Builder builder = new AlertDialog.Builder(d.getActivity())
                .setTitle("Delete message")
                .setNegativeButton("Cancel", null);

        if (isMine) {
            builder.setPositiveButton("Delete for everyone", (dlg, w) -> {
                        d.getMessagesRef().child(m.id).child("deleted").setValue(true);
                        d.getMessagesRef().child(m.id).child("text").setValue("");
                        final String mid = m.id;
                        d.getIoExecutor().execute(() -> d.getDb().messageDao().softDelete(mid));
                    })
                    .setNeutralButton("Delete for me", (dlg, w) -> {
                        final String mid = m.id;
                        d.getIoExecutor().execute(() -> d.getDb().messageDao().softDelete(mid));
                    });
        } else {
            builder.setMessage("Delete this message for you only?")
                    .setPositiveButton("Delete for me", (dlg, w) -> {
                        final String mid = m.id;
                        d.getIoExecutor().execute(() -> d.getDb().messageDao().softDelete(mid));
                    });
        }
        builder.show();
    }

    // ── Forward single ────────────────────────────────────────────────────

    public void forwardMessage(Message m) {
        Intent i = new Intent().setClassName(
            d.getActivity(), "com.callx.app.activities.ContactsActivity");
        i.putExtra("forwardText",      m.text);
        i.putExtra("forwardType",      m.type != null ? m.type : "text");
        i.putExtra("forwardMedia",     m.mediaUrl);
        i.putExtra("forwardFileName",  m.fileName);
        d.getActivity().startActivity(i);
    }

    // ── Forward multi-select ──────────────────────────────────────────────

    public void forwardSelectedMessages() {
        List<Message> selected = d.getPagingAdapter().getSelectedMessages();
        if (selected.isEmpty()) {
            d.showToast("Koi message select nahi"); return;
        }
        ArrayList<String> texts     = new ArrayList<>();
        ArrayList<String> types     = new ArrayList<>();
        ArrayList<String> medias    = new ArrayList<>();
        ArrayList<String> fileNames = new ArrayList<>();

        selected.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        for (Message m : selected) {
            texts.add(m.text     != null ? m.text     : "");
            types.add(m.type     != null ? m.type     : "text");
            medias.add(m.mediaUrl!= null ? m.mediaUrl : "");
            fileNames.add(m.fileName != null ? m.fileName : "");
        }
        Intent i = new Intent().setClassName(
            d.getActivity(), "com.callx.app.activities.ContactsActivity");
        i.putStringArrayListExtra("forwardTexts",     texts);
        i.putStringArrayListExtra("forwardTypes",     types);
        i.putStringArrayListExtra("forwardMedias",    medias);
        i.putStringArrayListExtra("forwardFileNames", fileNames);
        d.getActivity().startActivity(i);
        d.getPagingAdapter().exitMultiSelectMode();
    }

    // ── Delete multi-select ───────────────────────────────────────────────

    public void deleteSelectedMessages(Runnable onDone) {
        List<Message> sel = d.getPagingAdapter().getSelectedMessages();
        if (sel.isEmpty()) return;
        String msg = sel.size() == 1 ? "Delete this message?"
                                     : "Delete " + sel.size() + " messages?";
        new AlertDialog.Builder(d.getActivity())
                .setTitle("Delete messages").setMessage(msg)
                .setPositiveButton("Delete for everyone", (dlg, w) -> {
                    for (Message m : sel) {
                        d.getMessagesRef().child(m.id).child("deleted").setValue(true);
                        d.getMessagesRef().child(m.id).child("text").setValue("");
                        final String mid = m.id;
                        d.getIoExecutor().execute(() -> d.getDb().messageDao().softDelete(mid));
                    }
                    if (onDone != null) onDone.run();
                })
                .setNeutralButton("Delete for me", (dlg, w) -> {
                    for (Message m : sel) {
                        final String mid = m.id;
                        d.getIoExecutor().execute(() -> d.getDb().messageDao().softDelete(mid));
                    }
                    if (onDone != null) onDone.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ── Message info ──────────────────────────────────────────────────────

    public void showMessageInfoDialog(Message m) {
        if (m == null) return;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
            "dd MMM yyyy, hh:mm a", java.util.Locale.getDefault());
        String sentTime = (m.timestamp != null && m.timestamp > 0)
            ? sdf.format(new java.util.Date(m.timestamp)) : "Unknown";
        String info = "Sent:  " + sentTime
                + "\nStatus:  " + (m.status != null ? m.status : "unknown")
                + "\nType:  "   + (m.type   != null ? m.type   : "text")
                + "\nTo:  "     + (d.getPartnerName() != null
                                    ? d.getPartnerName() : d.getPartnerUid());
        new AlertDialog.Builder(d.getActivity())
                .setTitle("\u2139 Message Info").setMessage(info)
                .setPositiveButton("OK", null).show();
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int)(dp * d.getActivity().getResources().getDisplayMetrics().density);
    }
}
