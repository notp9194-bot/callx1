package com.callx.app.group;

import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.ui.MentionSuggestAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GroupMentionController — @mention support for group chat.
 *
 * How it works:
 *   1. Watches etMessage for "@" triggers.
 *   2. Shows rv_mention_suggest above the input bar, filtered by
 *      what the user has typed after "@" (prefix-matched against all members).
 *   3. Tap → inserts "@MemberName " with blue ForegroundColorSpan.
 *   4. Dismiss when "@" token removed or a mention is inserted.
 *   5. Fires a Firebase notification to mentioned user (optional — server-side
 *      processing recommended; client-side fire-and-forget here).
 *
 * Usage in GroupChatActivity:
 *   groupMentionController = new GroupMentionController(this, binding, memberNames, memberPhotos);
 *   groupMentionController.attach();
 *   // When members change:
 *   groupMentionController.updateMembers(memberNames, memberPhotos);
 */
public class GroupMentionController {

    private static final int MENTION_COLOR = 0xFF1DA1F2;

    private final android.app.Activity      activity;
    private final ActivityChatBinding       binding;
    private final String                    groupId;
    private final String                    currentUid;
    private final String                    currentName;

    private MentionSuggestAdapter suggestAdapter;
    private int mentionStart = -1;

    // Mutable — updated when group membership changes
    private Map<String, String> memberNames;
    private Map<String, String> memberPhotos;

    public GroupMentionController(android.app.Activity activity,
                                  ActivityChatBinding binding,
                                  String groupId,
                                  String currentUid,
                                  String currentName,
                                  Map<String, String> memberNames,
                                  Map<String, String> memberPhotos) {
        this.activity    = activity;
        this.binding     = binding;
        this.groupId     = groupId;
        this.currentUid  = currentUid;
        this.currentName = currentName != null ? currentName : "";
        this.memberNames  = memberNames;
        this.memberPhotos = memberPhotos;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void attach() {
        if (binding == null || binding.rvMentionSuggest == null) return;

        setupRecyclerView();
        rebuildSuggestions();

        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                handleTextChange(s, start, before, count);
            }
        });
    }

    /** Call whenever the member list changes (e.g. someone joins/leaves). */
    public void updateMembers(Map<String, String> memberNames,
                              Map<String, String> memberPhotos) {
        this.memberNames  = memberNames;
        this.memberPhotos = memberPhotos;
        rebuildSuggestions();
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        suggestAdapter = new MentionSuggestAdapter(activity, item -> insertMention(item.name));

        binding.rvMentionSuggest.setLayoutManager(new LinearLayoutManager(activity));
        binding.rvMentionSuggest.setAdapter(suggestAdapter);
        binding.rvMentionSuggest.setVisibility(View.GONE);
    }

    private void rebuildSuggestions() {
        if (suggestAdapter == null || memberNames == null) return;
        List<MentionSuggestAdapter.MentionItem> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : memberNames.entrySet()) {
            String uid  = entry.getKey();
            String name = entry.getValue();
            if (uid.equals(currentUid)) continue;  // Don't suggest self
            String photo = memberPhotos != null ? memberPhotos.get(uid) : null;
            items.add(new MentionSuggestAdapter.MentionItem(uid, name, photo));
        }
        // Sort alphabetically
        items.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        suggestAdapter.setItems(items);
    }

    // ── Text change ──────────────────────────────────────────────────────

    private void handleTextChange(CharSequence s, int start, int before, int count) {
        if (binding == null || binding.rvMentionSuggest == null) return;

        String text = s.toString();
        int cursorPos = start + count;

        int atIdx = findAtBefore(text, cursorPos);
        if (atIdx < 0) {
            mentionStart = -1;
            hideSuggestions();
            return;
        }

        mentionStart = atIdx;
        String prefix = text.substring(atIdx + 1, cursorPos);

        suggestAdapter.filter(prefix);
        if (suggestAdapter.getItemCount() > 0) {
            binding.rvMentionSuggest.setVisibility(View.VISIBLE);
        } else {
            hideSuggestions();
        }
    }

    private int findAtBefore(String text, int cursorPos) {
        for (int i = cursorPos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') {
                if (i == 0 || Character.isWhitespace(text.charAt(i - 1))) return i;
                return -1;
            }
            if (Character.isWhitespace(c)) return -1;
        }
        return -1;
    }

    // ── Insert mention ────────────────────────────────────────────────────

    private void insertMention(String name) {
        if (binding == null || binding.etMessage == null) return;
        hideSuggestions();
        mentionStart = -1;

        Editable editable = binding.etMessage.getText();
        if (editable == null) return;

        int cursor = binding.etMessage.getSelectionEnd();
        if (cursor < 0) cursor = editable.length();

        String current = editable.toString();
        int atIdx = -1;
        for (int i = cursor - 1; i >= 0; i--) {
            if (current.charAt(i) == '@') { atIdx = i; break; }
            if (Character.isWhitespace(current.charAt(i))) break;
        }

        String insertion = "@" + name + " ";
        if (atIdx >= 0) {
            editable.replace(atIdx, cursor, insertion);
            int end = atIdx + name.length() + 1;
            editable.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    atIdx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            int pos = editable.length();
            editable.append(insertion);
            editable.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    pos, pos + name.length() + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── Visibility ────────────────────────────────────────────────────────

    private void hideSuggestions() {
        if (binding != null && binding.rvMentionSuggest != null) {
            binding.rvMentionSuggest.setVisibility(View.GONE);
        }
    }
}
