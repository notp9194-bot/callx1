package com.callx.app.conversation.controllers;

import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.ui.MentionSuggestAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatMentionController — @mention support for 1:1 (personal) chat.
 *
 * How it works:
 *   1. Watches etMessage for "@" triggers.
 *   2. Shows rv_mention_suggest above the input bar with the partner's name.
 *   3. Tap → inserts "@PartnerName " with blue ForegroundColorSpan into etMessage.
 *   4. Dismiss suggestion list once a mention is inserted or "@" is deleted.
 *
 * Usage in ChatActivity:
 *   mentionController = new ChatMentionController(this, partnerUid, partnerName, partnerPhoto);
 *   mentionController.attach();
 */
public class ChatMentionController {

    private static final int MENTION_COLOR = 0xFF1DA1F2;

    private final ChatActivityDelegate delegate;
    private final String partnerUid;
    private final String partnerName;
    private final String partnerPhoto;

    private MentionSuggestAdapter suggestAdapter;

    /** Index in etMessage where the current "@token" started (-1 = no active mention). */
    private int mentionStart = -1;

    public ChatMentionController(ChatActivityDelegate delegate,
                                 String partnerUid,
                                 String partnerName,
                                 String partnerPhoto) {
        this.delegate    = delegate;
        this.partnerUid  = partnerUid;
        this.partnerName = partnerName != null ? partnerName : "";
        this.partnerPhoto = partnerPhoto;
    }

    // ── Attach ───────────────────────────────────────────────────────────

    public void attach() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null || binding.rvMentionSuggest == null) return;

        setupRecyclerView();

        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                handleTextChange(s, start, before, count);
            }
        });
    }

    // ── RV setup ─────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        ActivityChatBinding binding = delegate.getBinding();
        suggestAdapter = new MentionSuggestAdapter(
                delegate.getActivity(),
                item -> insertMention(item.name));

        List<MentionSuggestAdapter.MentionItem> items = new ArrayList<>();
        items.add(new MentionSuggestAdapter.MentionItem(
                partnerUid, partnerName, partnerPhoto));
        suggestAdapter.setItems(items);

        binding.rvMentionSuggest.setLayoutManager(
                new LinearLayoutManager(delegate.getActivity()));
        binding.rvMentionSuggest.setAdapter(suggestAdapter);
        binding.rvMentionSuggest.setVisibility(View.GONE);
    }

    // ── Text change handling ──────────────────────────────────────────────

    private void handleTextChange(CharSequence s, int start, int before, int count) {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null || binding.rvMentionSuggest == null) return;

        String text = s.toString();
        int cursorPos = start + count;

        // Detect if we're inside an @word (walk back from cursor)
        int atIdx = findAtBefore(text, cursorPos);
        if (atIdx < 0) {
            // No active @token
            mentionStart = -1;
            hideSuggestions();
            return;
        }

        mentionStart = atIdx;
        String prefix = text.substring(atIdx + 1, cursorPos);  // text after "@"

        // Filter suggestion list
        suggestAdapter.filter(prefix);

        if (suggestAdapter.getItemCount() > 0) {
            binding.rvMentionSuggest.setVisibility(View.VISIBLE);
        } else {
            hideSuggestions();
        }
    }

    /**
     * Walk back from cursorPos to find the most recent "@" that:
     *   - has no space between it and cursorPos
     *   - is preceded by start-of-string or a space/newline
     * Returns the index of "@", or -1 if not in an @word.
     */
    private int findAtBefore(String text, int cursorPos) {
        for (int i = cursorPos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') {
                // Valid start: beginning of string OR preceded by whitespace
                if (i == 0 || Character.isWhitespace(text.charAt(i - 1))) {
                    return i;
                }
                return -1;
            }
            if (Character.isWhitespace(c)) {
                return -1;   // Space found before reaching "@"
            }
        }
        return -1;
    }

    // ── Insert mention ────────────────────────────────────────────────────

    private void insertMention(String name) {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null || binding.etMessage == null) return;

        hideSuggestions();
        mentionStart = -1;

        Editable editable = binding.etMessage.getText();
        if (editable == null) return;

        int cursor = binding.etMessage.getSelectionEnd();
        if (cursor < 0) cursor = editable.length();

        // Find the "@" we started from
        String current = editable.toString();
        int atIdx = -1;
        for (int i = cursor - 1; i >= 0; i--) {
            if (current.charAt(i) == '@') { atIdx = i; break; }
            if (Character.isWhitespace(current.charAt(i))) break;
        }

        String insertion = "@" + name + " ";
        if (atIdx >= 0) {
            editable.replace(atIdx, cursor, insertion);
            // Apply blue color span
            int end = atIdx + name.length() + 1; // +1 for "@"
            editable.setSpan(
                    new ForegroundColorSpan(MENTION_COLOR),
                    atIdx, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            int pos = editable.length();
            editable.append(insertion);
            editable.setSpan(
                    new ForegroundColorSpan(MENTION_COLOR),
                    pos, pos + name.length() + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── Visibility ────────────────────────────────────────────────────────

    private void hideSuggestions() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding != null && binding.rvMentionSuggest != null) {
            binding.rvMentionSuggest.setVisibility(View.GONE);
        }
    }
}
