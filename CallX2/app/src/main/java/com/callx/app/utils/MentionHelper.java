package com.callx.app.utils;

import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feature 7: @Mention in Group
 *
 * - Watches EditText for '@' character.
 * - When '@' is typed, shows a member suggestion popup.
 * - Replaces @name with a mention span and records the UID.
 * - Highlights @mentions in blue in the rendered message bubble.
 */
public class MentionHelper {

    public interface MentionListener {
        /** Called when user types '@' — show suggestions filtered by query */
        void onMentionStarted(String query);
        /** Called when mention search is cancelled */
        void onMentionCancelled();
    }

    private final EditText editText;
    private final MentionListener listener;
    private boolean mentionActive = false;
    private int mentionStart = -1;

    // uid → display name (group members)
    private Map<String, String> members;

    public MentionHelper(EditText et, MentionListener l) {
        this.editText = et;
        this.listener = l;
        attachWatcher();
    }

    public void setMembers(Map<String, String> members) {
        this.members = members;
    }

    private void attachWatcher() {
        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                checkMention(s.toString(), editText.getSelectionStart());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void checkMention(String text, int cursor) {
        int at = text.lastIndexOf('@', cursor - 1);
        if (at >= 0 && (at == 0 || text.charAt(at - 1) == ' ' || text.charAt(at - 1) == '\n')) {
            String query = text.substring(at + 1, cursor);
            if (!query.contains(" ")) {
                mentionActive = true;
                mentionStart  = at;
                listener.onMentionStarted(query);
                return;
            }
        }
        if (mentionActive) {
            mentionActive = false;
            mentionStart  = -1;
            listener.onMentionCancelled();
        }
    }

    /**
     * Call when user selects a mention from the suggestion list.
     * Inserts "@DisplayName " into EditText and records the uid.
     */
    public String insertMention(String uid, String displayName,
                                List<String> mentionedUids) {
        if (mentionStart < 0) return editText.getText().toString();
        String current  = editText.getText().toString();
        int cursorPos   = editText.getSelectionStart();
        String before   = current.substring(0, mentionStart);
        String after    = cursorPos < current.length() ? current.substring(cursorPos) : "";
        String inserted = "@" + displayName + " ";
        String newText  = before + inserted + after;
        editText.setText(newText);
        editText.setSelection(before.length() + inserted.length());
        if (!mentionedUids.contains(uid)) mentionedUids.add(uid);
        mentionActive = false;
        mentionStart  = -1;
        listener.onMentionCancelled();
        return newText;
    }

    // ── Span rendering ─────────────────────────────────────────────────────

    private static final int MENTION_COLOR = 0xFF1976D2; // Material Blue 700
    private static final Pattern MENTION_PATTERN = Pattern.compile("@(\\w[\\w ]*)");

    /**
     * Highlight @mentions in blue in a message bubble TextView.
     */
    public static SpannableString highlight(String text) {
        SpannableString ss = new SpannableString(text);
        Matcher m = MENTION_PATTERN.matcher(text);
        while (m.find()) {
            ss.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    // ── UID extraction from text ───────────────────────────────────────────

    /**
     * Given the final message text and the member map (uid→name),
     * returns UIDs whose display name appears as @mention in the text.
     */
    public static List<String> extractMentionedUids(String text, Map<String, String> members) {
        List<String> result = new ArrayList<>();
        if (text == null || members == null) return result;
        for (Map.Entry<String, String> e : members.entrySet()) {
            if (text.contains("@" + e.getValue())) result.add(e.getKey());
        }
        return result;
    }
}
