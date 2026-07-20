package com.callx.app.channel;

import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;
import android.widget.PopupWindow;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ChannelMentionHandler — @mention autocomplete for channel post composer and replies.
 *
 * WhatsApp-level mention system:
 *   - Detects "@" typed in an EditText
 *   - Queries Firebase for channel followers / admins matching the typed prefix
 *   - Shows a floating suggestion list (PopupWindow)
 *   - On selection: replaces "@prefix" with "@username" as a colored span
 *   - Tracks all mentioned UIDs for notification delivery
 *   - Highlights @mentions in post text (blue color)
 *
 * Usage:
 *   ChannelMentionHandler handler = new ChannelMentionHandler(context, editText, channelId);
 *   handler.setOnMentionSelected(uid, name -> notifiedUids.add(uid));
 *   handler.attach();
 */
public class ChannelMentionHandler {

    public interface OnMentionSelected {
        void onSelected(String uid, String displayName);
    }

    public interface SuggestionShowCallback {
        void show(List<MentionCandidate> candidates, int atStart, int atEnd);
        void hide();
    }

    private final android.content.Context context;
    private final EditText                editText;
    private final String                  channelId;

    private OnMentionSelected    mentionCallback;
    private SuggestionShowCallback showCallback;

    // Track position of the active "@..." token
    private int  activeAtStart = -1;
    private String activePrefix = "";

    public ChannelMentionHandler(android.content.Context ctx, EditText et, String channelId) {
        this.context   = ctx;
        this.editText  = et;
        this.channelId = channelId;
    }

    public void setOnMentionSelected(OnMentionSelected cb) { this.mentionCallback = cb; }
    public void setSuggestionShowCallback(SuggestionShowCallback cb) { this.showCallback = cb; }

    public void attach() {
        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                checkForMention(s.toString(), editText.getSelectionStart());
            }
        });
    }

    private void checkForMention(String text, int cursorPos) {
        // Find the "@" before the cursor
        int atPos = -1;
        for (int i = cursorPos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') { atPos = i; break; }
            if (Character.isWhitespace(c)) break;
        }

        if (atPos < 0) {
            activeAtStart = -1;
            activePrefix  = "";
            if (showCallback != null) showCallback.hide();
            return;
        }

        String prefix = text.substring(atPos + 1, cursorPos);
        activeAtStart = atPos;
        activePrefix  = prefix;

        if (prefix.length() < 1) {
            if (showCallback != null) showCallback.hide();
            return;
        }

        queryCandidates(prefix.toLowerCase(), atPos, cursorPos);
    }

    private void queryCandidates(String prefix, int atStart, int atEnd) {
        FirebaseUtils.db().getReference()
            .child("channelFollowers").child(channelId)
            .limitToFirst(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<MentionCandidate> candidates = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        String uid  = ds.getKey();
                        String name = ds.child("name").getValue(String.class);
                        if (uid == null || name == null) continue;
                        if (name.toLowerCase().startsWith(prefix)) {
                            candidates.add(new MentionCandidate(uid, name,
                                ds.child("iconUrl").getValue(String.class)));
                        }
                    }
                    if (showCallback != null) {
                        if (candidates.isEmpty()) showCallback.hide();
                        else showCallback.show(candidates, atStart, atEnd);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * Call when the user picks a mention candidate.
     * Replaces "@prefix" in the EditText with "@displayName " (colored span).
     */
    public void selectMention(MentionCandidate candidate, int atStart, int atEnd) {
        Editable editable = editText.getText();
        if (editable == null) return;

        String replacement = "@" + candidate.displayName + " ";
        editable.replace(atStart, atEnd, replacement);

        // Color the mention span blue/green
        int spanEnd = atStart + replacement.length() - 1; // exclude trailing space
        ((Spannable) editable).setSpan(
            new ForegroundColorSpan(0xFF25D366),
            atStart, spanEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        activeAtStart = -1;
        activePrefix  = "";
        if (showCallback != null) showCallback.hide();
        if (mentionCallback != null)
            mentionCallback.onSelected(candidate.uid, candidate.displayName);
    }

    /**
     * Applies @mention coloring to already-written text (for edit mode).
     * Call on an existing SpannableStringBuilder before setting on EditText.
     */
    public static SpannableStringBuilder applyMentionSpans(String text, int color) {
        SpannableStringBuilder ssb = new SpannableStringBuilder(text);
        int start = 0;
        while (start < text.length()) {
            int atIdx = text.indexOf('@', start);
            if (atIdx < 0) break;
            int end = atIdx + 1;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            if (end > atIdx + 1) {
                ssb.setSpan(new ForegroundColorSpan(color), atIdx, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end;
        }
        return ssb;
    }

    // ── Data ─────────────────────────────────────────────────────────────

    public static class MentionCandidate {
        public final String uid, displayName, iconUrl;
        public MentionCandidate(String uid, String displayName, @Nullable String iconUrl) {
            this.uid = uid; this.displayName = displayName; this.iconUrl = iconUrl;
        }
    }
}
