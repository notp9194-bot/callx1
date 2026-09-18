package com.callx.app.group;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.ui.ChatLazyViewUtils;
import com.callx.app.chat.ui.MentionSuggestAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * GroupMentionController — Production-grade @mention for group chat.
 *
 * Features:
 *   • Watches {@code etMessage} for "@" trigger.
 *   • Shows animated suggestion list above the input capsule.
 *   • Members sorted alphabetically; self excluded.
 *   • Tap → inserts "@Name " with blue ForegroundColorSpan.
 *   • {@link #updateMembers(Map, Map)} — call whenever membership changes
 *     (Firebase membersListener or subscribePresence fires).
 *   • {@link #dismissSuggestions()} — call on send / back press.
 *   • Sends mention UIDs in {@code m.mentionedUids} for server-side
 *     push routing (see extractMentions / GroupChatActivity.sendText).
 *
 * Usage in GroupChatActivity.setupInputBar():
 * <pre>
 *   groupMentionController = new GroupMentionController(
 *       this, binding, groupId, currentUid, currentName,
 *       memberNames, memberPhotos);
 *   groupMentionController.attach();
 * </pre>
 */
public class GroupMentionController {

    public static final int MENTION_COLOR = 0xFF1DA1F2;

    private final Activity              activity;
    private final ActivityChatBinding   binding;
    private final String                currentUid;

    private MentionSuggestAdapter suggestAdapter;
    private TextWatcher           textWatcher;
    private boolean               attached = false;

    // Kept as references — updated live when membership changes
    private Map<String, String> memberNames;
    private Map<String, String> memberPhotos;

    public GroupMentionController(Activity activity,
                                  ActivityChatBinding binding,
                                  String groupId,
                                  String currentUid,
                                  String currentName,
                                  Map<String, String> memberNames,
                                  Map<String, String> memberPhotos) {
        this.activity     = activity;
        this.binding      = binding;
        this.currentUid   = currentUid;
        this.memberNames  = memberNames;
        this.memberPhotos = memberPhotos;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void attach() {
        if (attached || binding == null
                || binding.etMessage == null) return;
        attached = true;
        suggestAdapter = new MentionSuggestAdapter(activity, item -> insertMention(item.name));
        rebuildItems();
        textWatcher = buildWatcher();
        binding.etMessage.addTextChangedListener(textWatcher);
    }

    /**
     * Call from GroupChatActivity whenever Firebase membership/presence fires.
     * Safe to call from the main thread.
     */
    public void updateMembers(Map<String, String> names, Map<String, String> photos) {
        this.memberNames  = names;
        this.memberPhotos = photos;
        if (attached) rebuildItems();
    }

    /** Hides the suggestion list. Call on send, back-press, or onStop. */
    public void dismissSuggestions() {
        RecyclerView list = findMentionList();
        if (list != null && list.getVisibility() == View.VISIBLE) {
            animateHide(list);
        }
    }

    /** True when the dropdown is currently visible. */
    public boolean isShowing() {
        RecyclerView list = findMentionList();
        return list != null && list.getVisibility() == View.VISIBLE;
    }

    public void onDestroy() {
        if (attached && binding.etMessage != null && textWatcher != null) {
            binding.etMessage.removeTextChangedListener(textWatcher);
        }
        attached = false;
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private RecyclerView findMentionList() {
        View list = binding.getRoot().findViewById(R.id.rv_mention_suggest);
        return list instanceof RecyclerView ? (RecyclerView) list : null;
    }

    private RecyclerView ensureRecyclerView() {
        RecyclerView list = findMentionList();
        if (list == null) {
            View inflated = ChatLazyViewUtils.ensureInflated(
                    binding.getRoot(), R.id.stub_mention_suggestions, R.id.rv_mention_suggest);
            if (inflated instanceof RecyclerView) list = (RecyclerView) inflated;
        }
        if (list == null || list.getAdapter() != null) return list;

        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(suggestAdapter);
        list.setNestedScrollingEnabled(false);
        list.setVisibility(View.GONE);
        return list;
    }

    private void rebuildItems() {
        if (suggestAdapter == null || memberNames == null) return;
        List<MentionSuggestAdapter.MentionItem> items = new ArrayList<>();
        for (Map.Entry<String, String> e : memberNames.entrySet()) {
            String uid = e.getKey();
            if (uid.equals(currentUid)) continue;   // Don't suggest self
            String name  = e.getValue();
            String photo = (memberPhotos != null) ? memberPhotos.get(uid) : null;
            items.add(new MentionSuggestAdapter.MentionItem(uid, name, photo));
        }
        // Alphabetical
        Collections.sort(items, (a, b) -> a.name.compareToIgnoreCase(b.name));
        suggestAdapter.setItems(items);
    }

    // ── Text watcher ──────────────────────────────────────────────────────

    private TextWatcher buildWatcher() {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                int cursor = start + count;
                int atIdx  = findAtBefore(s.toString(), cursor);
                if (atIdx < 0) { dismissSuggestions(); return; }
                String prefix = s.subSequence(atIdx + 1, cursor).toString();
                suggestAdapter.filter(prefix);
                if (suggestAdapter.getItemCount() > 0) {
                    RecyclerView list = ensureRecyclerView();
                    if (list != null) animateShow(list);
                } else {
                    dismissSuggestions();
                }
            }
        };
    }

    // ── Detect @-token ────────────────────────────────────────────────────

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

    private void insertMention(String name) {
        dismissSuggestions();
        if (binding.etMessage == null) return;
        Editable ed = binding.etMessage.getText();
        if (ed == null) return;
        int cursor = binding.etMessage.getSelectionEnd();
        if (cursor < 0) cursor = ed.length();

        // Walk back to find the @ that triggered this
        String current = ed.toString();
        int atIdx = -1;
        for (int i = cursor - 1; i >= 0; i--) {
            if (current.charAt(i) == '@') { atIdx = i; break; }
            if (Character.isWhitespace(current.charAt(i))) break;
        }

        String insertion = "@" + name + " ";
        if (atIdx >= 0) {
            ed.replace(atIdx, cursor, insertion);
            ed.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    atIdx, atIdx + name.length() + 1,
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
        v.animate().alpha(1f).translationY(0f)
                .setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void animateHide(View v) {
        v.animate().alpha(0f).translationY(40f)
                .setDuration(120).setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                        v.setTranslationY(0f);
                    }
                }).start();
    }
}
