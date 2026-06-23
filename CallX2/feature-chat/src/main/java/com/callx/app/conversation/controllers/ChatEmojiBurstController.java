package com.callx.app.conversation.controllers;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.models.Message;

import java.util.regex.Pattern;

/**
 * ChatEmojiBurstController — NO animation version for performance.
 * Emoji-only detection kept. Burst overlay removed entirely.
 */
public class ChatEmojiBurstController {

    private static final Pattern EMOJI_RANGE = Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F1E6}-\\x{1F1FF}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}]");

    private final ChatActivityDelegate delegate;

    public ChatEmojiBurstController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    public void release() {
        // No-op
    }

    public void onMessageReceived(Message m) {
        // Burst overlay removed for performance
    }

    private boolean isEmojiOnly(String raw) {
        String text = raw.trim();
        if (text.isEmpty() || text.length() > 12) return false;
        String stripped = text
                .replace("\uFE0F", "")
                .replace("\u200D", "")
                .replaceAll("\\s+", "");
        if (stripped.isEmpty()) return false;
        int i = 0;
        boolean sawEmoji = false;
        while (i < stripped.length()) {
            int cp = stripped.codePointAt(i);
            int charCount = Character.charCount(cp);
            String unit = stripped.substring(i, i + charCount);
            if (!EMOJI_RANGE.matcher(unit).matches()) return false;
            sawEmoji = true;
            i += charCount;
        }
        return sawEmoji;
    }
}
