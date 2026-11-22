package com.callx.app.chat.reply;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.Message;

/**
 * v819: keeps the ACTIVE reply across activity recreation (rotation, theme/locale change,
 * process death while the chat is in the background).
 *
 * Only the handful of fields needed to rebuild the reply bar AND to stamp the outgoing
 * reply (id, sender, type, text, thumb) are saved — never the heavy Message graph — so the
 * saved-state bundle stays tiny (the chat screen is careful about TransactionTooLarge, see
 * ChatActivity#onSaveInstanceState).
 */
public final class ReplySnapshot {

    private static final String KEY = "chat_reply_snapshot_v1";
    private static final int MAX_TEXT = 1000;

    private ReplySnapshot() { }

    public static void save(@NonNull Bundle out, @Nullable Message m) {
        out.remove(KEY);
        if (m == null) return;
        Bundle b = new Bundle();
        b.putString("id", m.id);
        b.putString("messageId", m.messageId);
        b.putString("senderId", m.senderId);
        b.putString("senderName", m.senderName);
        b.putString("type", m.type);
        b.putString("text", m.text != null && m.text.length() > MAX_TEXT
                ? m.text.substring(0, MAX_TEXT) : m.text);
        b.putString("mediaUrl", "image".equals(m.type) ? m.mediaUrl : null);
        b.putString("thumbnailUrl", m.thumbnailUrl);
        b.putString("fileName", m.fileName);
        b.putString("contactName", m.contactName);
        b.putString("pollQuestion", m.pollQuestion);
        if (m.duration != null) b.putLong("duration", m.duration);
        b.putBoolean("deleted", Boolean.TRUE.equals(m.deleted));
        out.putBundle(KEY, b);
    }

    /** @return the rebuilt reply target, or null if nothing (valid) was saved. */
    @Nullable
    public static Message restore(@Nullable Bundle in) {
        if (in == null) return null;
        Bundle b = in.getBundle(KEY);
        if (b == null) return null;
        String id = b.getString("messageId") != null ? b.getString("messageId") : b.getString("id");
        if (id == null || id.isEmpty()) return null;
        Message m = new Message();
        m.id = b.getString("id");
        m.messageId = b.getString("messageId");
        m.senderId = b.getString("senderId");
        m.senderName = b.getString("senderName");
        m.type = b.getString("type");
        m.text = b.getString("text");
        m.mediaUrl = b.getString("mediaUrl");
        m.thumbnailUrl = b.getString("thumbnailUrl");
        m.fileName = b.getString("fileName");
        m.contactName = b.getString("contactName");
        m.pollQuestion = b.getString("pollQuestion");
        if (b.containsKey("duration")) m.duration = b.getLong("duration");
        if (b.getBoolean("deleted")) m.deleted = Boolean.TRUE;
        return m;
    }
}
