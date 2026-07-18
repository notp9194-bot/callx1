package com.callx.app.upload;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReelCaptionMentionController — Instagram-style @mention for the reel
 * caption field in {@link ReelPostDetailsActivity}.
 *
 * All dependencies are within :feature-reels and :core — no :feature-chat
 * import is needed.  The RecyclerView adapter is
 * {@link ReelMentionSuggestAdapter} (a self-contained copy kept here).
 *
 * Features:
 *  ✅ Triggers on "@" in caption EditText (real-time TextWatcher)
 *  ✅ Lazy-loads the user's followers once on first trigger
 *  ✅ Contains-match filtering — "@ali" matches "Malik Ali"
 *  ✅ Animated slide-up suggestion list above the caption field
 *  ✅ Tap → inserts "@Name " with blue ForegroundColorSpan into EditText
 *  ✅ Tracks name→uid map so host can retrieve UIDs of mentioned users
 *  ✅ Multiple mentions per caption supported
 *  ✅ dismissSuggestions() / isShowing() / onDestroy() for lifecycle
 *
 * Usage in ReelPostDetailsActivity:
 * <pre>
 *   mentionController = new ReelCaptionMentionController(
 *           etCaption, rvMentionSuggest, myUid);
 *   mentionController.attach();
 * </pre>
 * Before launching next screen:
 * <pre>
 *   ArrayList&lt;String&gt; uids = mentionController.getMentionedUids(captionText);
 * </pre>
 */
public class ReelCaptionMentionController {

    public static final int MENTION_COLOR = 0xFF1DA1F2; // Twitter/Instagram blue

    // ── Dependencies ──────────────────────────────────────────────────────
    private final TextInputEditText       etCaption;
    private final RecyclerView            rvSuggest;
    private final String                  myUid;

    // ── State ─────────────────────────────────────────────────────────────
    private ReelMentionSuggestAdapter     adapter;
    private TextWatcher                   textWatcher;
    private boolean                       attached         = false;
    private boolean                       followersLoaded  = false;
    private boolean                       followersLoading = false;

    /** Full follower list — loaded once, filtered locally. */
    private final List<ReelMentionSuggestAdapter.MentionItem> allFollowers = new ArrayList<>();

    /**
     * name → uid map so {@link #getMentionedUids(String)} can resolve
     * @Name tokens back to Firebase UIDs.
     */
    private final Map<String, String> nameToUidMap = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────

    public ReelCaptionMentionController(@NonNull TextInputEditText etCaption,
                                        @NonNull RecyclerView      rvSuggest,
                                        @NonNull String            myUid) {
        this.etCaption = etCaption;
        this.rvSuggest = rvSuggest;
        this.myUid     = myUid;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Attaches the TextWatcher. Call from Activity.onCreate() after bindViews(). */
    public void attach() {
        if (attached) return;
        attached = true;
        setupRecyclerView();
        textWatcher = buildWatcher();
        etCaption.addTextChangedListener(textWatcher);
    }

    /**
     * Hides the suggestion list.
     * Call on "Next →" press, back press, or Activity stop.
     */
    public void dismissSuggestions() {
        if (rvSuggest.getVisibility() == View.VISIBLE) animateHide(rvSuggest);
    }

    /** True if the suggestion dropdown is currently visible. */
    public boolean isShowing() {
        return rvSuggest.getVisibility() == View.VISIBLE;
    }

    /**
     * Parses the final caption and returns UIDs for every @Name token
     * that matches a known follower.  Call just before launching
     * ReelUploadActivity.
     */
    @NonNull
    public ArrayList<String> getMentionedUids(@NonNull String caption) {
        ArrayList<String> uids = new ArrayList<>();
        if (caption.isEmpty() || nameToUidMap.isEmpty()) return uids;

        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("@([\\w.]+)").matcher(caption);
        while (m.find()) {
            String token = m.group(1);
            if (token == null) continue;
            String uid = nameToUidMap.get(token);
            if (uid == null) {
                // Case-insensitive fallback
                for (Map.Entry<String, String> e : nameToUidMap.entrySet()) {
                    if (e.getKey().equalsIgnoreCase(token)) { uid = e.getValue(); break; }
                }
            }
            if (uid != null && !uid.equals(myUid) && !uids.contains(uid)) {
                uids.add(uid);
            }
        }
        return uids;
    }

    /** Must be called from Activity.onDestroy() to avoid leaks. */
    public void onDestroy() {
        if (attached && textWatcher != null) {
            etCaption.removeTextChangedListener(textWatcher);
        }
        attached = false;
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    private void setupRecyclerView() {
        adapter = new ReelMentionSuggestAdapter(
                etCaption.getContext(),
                this::insertMention);
        rvSuggest.setLayoutManager(new LinearLayoutManager(etCaption.getContext()));
        rvSuggest.setAdapter(adapter);
        rvSuggest.setVisibility(View.GONE);
    }

    private TextWatcher buildWatcher() {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable ed) {
                String full   = ed.toString();
                int    cursor = etCaption.getSelectionStart();
                if (cursor < 0 || cursor > full.length()) { dismissSuggestions(); return; }

                // Walk backwards from cursor to find the last '@'
                int atIdx = -1;
                for (int i = cursor - 1; i >= 0; i--) {
                    char ch = full.charAt(i);
                    if (ch == '@') { atIdx = i; break; }
                    if (Character.isWhitespace(ch)) break;
                }

                if (atIdx < 0) { dismissSuggestions(); return; }

                String query = full.substring(atIdx + 1, cursor);

                // Load followers lazily on first "@" trigger
                if (!followersLoaded && !followersLoading) {
                    followersLoading = true;
                    loadFollowers(() -> {
                        followersLoaded  = true;
                        followersLoading = false;
                        showSuggestions(query);
                    });
                } else if (followersLoaded) {
                    showSuggestions(query);
                }
            }
        };
    }

    // ── Follower loading ──────────────────────────────────────────────────

    private void loadFollowers(@NonNull Runnable onLoaded) {
        FirebaseUtils.db()
                .getReference("followers")
                .child(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        allFollowers.clear();
                        nameToUidMap.clear();

                        List<String> uidsToFetch = new ArrayList<>();
                        for (DataSnapshot child : snap.getChildren()) {
                            uidsToFetch.add(child.getKey());
                        }

                        if (uidsToFetch.isEmpty()) { onLoaded.run(); return; }

                        int limit = Math.min(uidsToFetch.size(), 50);
                        final int[] remaining = {limit};

                        for (int i = 0; i < limit; i++) {
                            final String uid = uidsToFetch.get(i);
                            FirebaseUtils.db()
                                    .getReference("users")
                                    .child(uid)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override
                                        public void onDataChange(@NonNull DataSnapshot s) {
                                            String name  = s.child("name").getValue(String.class);
                                            String photo = s.child("photoUrl").getValue(String.class);
                                            if (name != null && !name.isEmpty()) {
                                                allFollowers.add(new ReelMentionSuggestAdapter.MentionItem(
                                                        uid, name, photo != null ? photo : ""));
                                                // Store both "Ali Khan" and "AliKhan" as keys
                                                nameToUidMap.put(name, uid);
                                                nameToUidMap.put(name.replace(" ", ""), uid);
                                            }
                                            if (--remaining[0] <= 0) onLoaded.run();
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {
                                            if (--remaining[0] <= 0) onLoaded.run();
                                        }
                                    });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { onLoaded.run(); }
                });
    }

    // ── Suggestion display ────────────────────────────────────────────────

    private void showSuggestions(String query) {
        adapter.setItems(allFollowers);
        adapter.filter(query);
        if (adapter.getItemCount() == 0) dismissSuggestions();
        else animateShow(rvSuggest);
    }

    // ── Mention insertion ─────────────────────────────────────────────────

    private void insertMention(@NonNull ReelMentionSuggestAdapter.MentionItem item) {
        dismissSuggestions();
        Editable ed = etCaption.getText();
        if (ed == null) return;

        int    cursor = etCaption.getSelectionStart();
        String full   = ed.toString();

        // Find the "@" that triggered this suggestion
        int atIdx = -1;
        for (int i = Math.min(cursor, full.length()) - 1; i >= 0; i--) {
            char ch = full.charAt(i);
            if (ch == '@') { atIdx = i; break; }
            if (Character.isWhitespace(ch)) break;
        }

        // Token without spaces: "@AliKhan "
        String token = "@" + item.name.replace(" ", "") + " ";
        nameToUidMap.put(item.name.replace(" ", ""), item.uid);
        nameToUidMap.put(item.name, item.uid);

        if (atIdx >= 0) {
            ed.replace(atIdx, cursor, token);
            ed.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    atIdx, atIdx + token.length() - 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            int pos = ed.length();
            ed.append(token);
            ed.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    pos, pos + token.length() - 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── Animation ─────────────────────────────────────────────────────────

    private void animateShow(View v) {
        if (v.getVisibility() == View.VISIBLE) return;
        v.setVisibility(View.VISIBLE);
        v.setAlpha(0f);
        v.setTranslationY(30f);
        v.animate().alpha(1f).translationY(0f)
                .setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void animateHide(View v) {
        v.animate().alpha(0f).translationY(30f)
                .setDuration(120).setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                        v.setTranslationY(0f);
                        v.animate().setListener(null);
                    }
                }).start();
    }
}
