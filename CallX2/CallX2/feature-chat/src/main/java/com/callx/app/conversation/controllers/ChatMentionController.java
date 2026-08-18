package com.callx.app.conversation.controllers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.ui.MentionSuggestAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatMentionController — Production-grade @mention for 1:1 (personal) chat.
 *
 * Features:
 *   • Watches {@code etMessage} for "@" trigger in real time.
 *   • Filters suggestions by contains-match (not just prefix) on name.
 *   • Shows animated slide-up suggestion list above the input capsule.
 *   • Tap → inserts "@Name " with blue {@link ForegroundColorSpan} into the EditText.
 *   • {@link #dismissSuggestions()} — call from send / activity back-press.
 *   • Properly cleans up in {@link #onDestroy()}.
 *
 * Usage in ChatActivity.setupMentionController():
 * <pre>
 *   mentionController = new ChatMentionController(
 *       this, partnerUid, partnerName, partnerPhoto);
 *   mentionController.attach();
 * </pre>
 */
public class ChatMentionController {

    public static final int MENTION_COLOR = 0xFF1DA1F2;

    private final ChatActivityDelegate delegate;
    private final String               partnerUid;
    private final String               partnerName;
    private final String               partnerPhoto;

    private MentionSuggestAdapter suggestAdapter;
    private TextWatcher           textWatcher;
    private boolean               attached = false;

    public ChatMentionController(ChatActivityDelegate delegate,
                                 String partnerUid,
                                 String partnerName,
                                 String partnerPhoto) {
        this.delegate    = delegate;
        this.partnerUid  = partnerUid;
        this.partnerName = partnerName != null ? partnerName : "";
        this.partnerPhoto = partnerPhoto;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void attach() {
        ActivityChatBinding b = delegate.getBinding();
        if (attached || b == null || b.rvMentionSuggest == null || b.etMessage == null) return;
        attached = true;
        setupRecyclerView(b);
        textWatcher = buildWatcher(b);
        b.etMessage.addTextChangedListener(textWatcher);
    }

    /** Hides the suggestion list. Call on send, back press, or fragment stop. */
    public void dismissSuggestions() {
        ActivityChatBinding b = delegate.getBinding();
        if (b != null && b.rvMentionSuggest != null
                && b.rvMentionSuggest.getVisibility() == View.VISIBLE) {
            animateHide(b.rvMentionSuggest);
        }
    }

    /** True if the suggestion dropdown is currently visible. */
    public boolean isShowing() {
        ActivityChatBinding b = delegate.getBinding();
        return b != null && b.rvMentionSuggest != null
                && b.rvMentionSuggest.getVisibility() == View.VISIBLE;
    }

    public void onDestroy() {
        ActivityChatBinding b = delegate.getBinding();
        if (attached && b != null && b.etMessage != null && textWatcher != null) {
            b.etMessage.removeTextChangedListener(textWatcher);
        }
        attached = false;
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private void setupRecyclerView(ActivityChatBinding b) {
        suggestAdapter = new MentionSuggestAdapter(
                delegate.getActivity(),
                item -> insertMention(b, item.name));

        List<MentionSuggestAdapter.MentionItem> items = new ArrayList<>();
        items.add(new MentionSuggestAdapter.MentionItem(
                partnerUid, partnerName, partnerPhoto));
        suggestAdapter.setItems(items);

        b.rvMentionSuggest.setLayoutManager(
                new LinearLayoutManager(delegate.getActivity()));
        b.rvMentionSuggest.setAdapter(suggestAdapter);
        b.rvMentionSuggest.setNestedScrollingEnabled(false);
        b.rvMentionSuggest.setVisibility(View.GONE);
    }

    // ── Text watcher ──────────────────────────────────────────────────────

    private TextWatcher buildWatcher(ActivityChatBinding b) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (b.rvMentionSuggest == null) return;
                int cursor = start + count;
                int atIdx  = findAtBefore(s.toString(), cursor);
                if (atIdx < 0) {
                    dismissSuggestions();
                    return;
                }
                String prefix = s.subSequence(atIdx + 1, cursor).toString();
                suggestAdapter.filter(prefix);
                if (suggestAdapter.getItemCount() > 0) {
                    animateShow(b.rvMentionSuggest);
                } else {
                    dismissSuggestions();
                }
            }
        };
    }

    // ── Detect @-token ────────────────────────────────────────────────────

    /**
     * Walk backwards from {@code cursorPos} to find a valid {@code @word} start.
     * Returns the index of {@code @}, or -1 if the cursor is not inside an @word.
     * Valid: @ is at start-of-string, or preceded by whitespace.
     */
    private int findAtBefore(String text, int cursorPos) {
        for (int i = cursorPos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') {
                return (i == 0 || Character.isWhitespace(text.charAt(i - 1))) ? i : -1;
            }
            if (Character.isWhitespace(c)) return -1;
        }
        return -1;
    }

    // ── Insert mention ────────────────────────────────────────────────────

    private void insertMention(ActivityChatBinding b, String name) {
        dismissSuggestions();
        if (b.etMessage == null) return;

        Editable ed = b.etMessage.getText();
        if (ed == null) return;
        int cursor = b.etMessage.getSelectionEnd();
        if (cursor < 0) cursor = ed.length();

        // Walk back to find the @ that triggered this
        int atIdx = -1;
        String current = ed.toString();
        for (int i = cursor - 1; i >= 0; i--) {
            if (current.charAt(i) == '@') { atIdx = i; break; }
            if (Character.isWhitespace(current.charAt(i))) break;
        }

        String insertion = "@" + name + " ";
        if (atIdx >= 0) {
            ed.replace(atIdx, cursor, insertion);
            // Blue span covers "@Name" (not the trailing space)
            ed.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    atIdx, atIdx + name.length() + 1,   // +1 for '@'
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            int pos = ed.length();
            ed.append(insertion);
            ed.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    pos, pos + name.length() + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── Animation ─────────────────────────────────────────────────────────

    private void animateShow(View v) {
        if (v.getVisibility() == View.VISIBLE) return;
        v.setVisibility(View.VISIBLE);
        v.setAlpha(0f);
        v.setTranslationY(40f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateHide(View v) {
        v.animate()
                .alpha(0f)
                .translationY(40f)
                .setDuration(120)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                        v.setTranslationY(0f);
                    }
                })
                .start();
    }
}
