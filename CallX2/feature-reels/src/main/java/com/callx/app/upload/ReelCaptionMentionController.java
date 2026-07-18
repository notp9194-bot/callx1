package com.callx.app.upload;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Color;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.ui.MentionSuggestAdapter;
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
 * Features:
 *  ✅ Triggers on "@" in caption EditText (real-time TextWatcher)
 *  ✅ Lazy-loads the user's followers once on first trigger
 *  ✅ Contains-match filtering (not prefix-only) — "@ali" matches "Malik Ali"
 *  ✅ Animated slide-up suggestion list above the caption field
 *  ✅ Tap → inserts "@Name " with blue ForegroundColorSpan into the EditText
 *  ✅ Tracks (name → uid) map so the host can retrieve UIDs of mentioned users
 *  ✅ Multiple mentions per caption supported
 *  ✅ dismissSuggestions() / isShowing() / onDestroy() for lifecycle management
 *
 * Usage in ReelPostDetailsActivity:
 * <pre>
 *   mentionController = new ReelCaptionMentionController(
 *           etCaption, rvMentionSuggest, myUid);
 *   mentionController.attach();
 * </pre>
 * After user presses "Next →":
 * <pre>
 *   ArrayList&lt;String&gt; uids = mentionController.getMentionedUids(captionText);
 * </pre>
 */
public class ReelCaptionMentionController {

    public static final int MENTION_COLOR = 0xFF1DA1F2; // Twitter/Instagram blue

    // ── Dependencies ──────────────────────────────────────────────────────
    private final TextInputEditText etCaption;
    private final RecyclerView      rvSuggest;
    private final String            myUid;

    // ── State ─────────────────────────────────────────────────────────────
    private MentionSuggestAdapter   adapter;
    private TextWatcher             textWatcher;
    private boolean                 attached          = false;
    private boolean                 followersLoaded   = false;
    private boolean                 followersLoading  = false;

    /** All known followers — loaded once, then filtered locally. */
    private final List<MentionSuggestAdapter.MentionItem> allFollowers = new ArrayList<>();

    /**
     * Maps displayName → uid for followers seen in the suggestion list.
     * Used by {@link #getMentionedUids(String)} to resolve @Name → uid.
     */
    private final Map<String, String> nameToUidMap = new HashMap<>();

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
     * Call on "Next →" press, back press, or fragment stop.
     */
    public void dismissSuggestions() {
        if (rvSuggest.getVisibility() == View.VISIBLE) animateHide(rvSuggest);
    }

    /** True if the suggestion dropdown is currently visible. */
    public boolean isShowing() {
        return rvSuggest.getVisibility() == View.VISIBLE;
    }

    /**
     * Parses the final caption text and returns a deduplicated list of UIDs
     * for every @Name token that matches a known follower.
     *
     * Call this just before launching ReelUploadActivity.
     */
    @NonNull
    public ArrayList<String> getMentionedUids(@NonNull String caption) {
        ArrayList<String> uids = new ArrayList<>();
        if (caption.isEmpty() || nameToUidMap.isEmpty()) return uids;

        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("@([\\w.]+)").matcher(caption);
        while (m.find()) {
            String name = m.group(1);
            if (name != null) {
                // Exact match on display name (spaces removed, as inserted by us)
                String uid = nameToUidMap.get(name);
                if (uid != null && !uid.equals(myUid) && !uids.contains(uid)) {
                    uids.add(uid);
                }
                // Also try case-insensitive scan
                if (uid == null) {
                    for (Map.Entry<String, String> e : nameToUidMap.entrySet()) {
                        if (e.getKey().equalsIgnoreCase(name) && !uids.contains(e.getValue())) {
                            uids.add(e.getValue());
                            break;
                        }
                    }
                }
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
        adapter = new MentionSuggestAdapter(
                etCaption.getContext(),
                item -> insertMention(item));
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
                if (cursor < 0 || cursor > full.length()) {
                    dismissSuggestions();
                    return;
                }

                // Walk backwards from cursor to find the last '@'
                int atIdx = -1;
                for (int i = cursor - 1; i >= 0; i--) {
                    char ch = full.charAt(i);
                    if (ch == '@') { atIdx = i; break; }
                    if (Character.isWhitespace(ch)) break;
                }

                if (atIdx < 0) {
                    dismissSuggestions();
                    return;
                }

                // Extract the query typed after '@'
                String query = full.substring(atIdx + 1, cursor);

                // Load followers lazily on first trigger
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
                // If still loading, suggestions appear via the callback above
            }
        };
    }

    // ── Follower loading ──────────────────────────────────────────────────

    private void loadFollowers(@NonNull Runnable onLoaded) {
        // followers/{myUid}/{followerUid}: true
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
                        if (uidsToFetch.isEmpty()) {
                            onLoaded.run();
                            return;
                        }

                        // Fetch user profiles in batch (limit 50 to stay fast)
                        int limit = Math.min(uidsToFetch.size(), 50);
                        final int[] remaining = {limit};
                        for (int i = 0; i < limit; i++) {
                            String uid = uidsToFetch.get(i);
                            FirebaseUtils.db()
                                    .getReference("users")
                                    .child(uid)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override
                                        public void onDataChange(@NonNull DataSnapshot userSnap) {
                                            String name  = userSnap.child("name").getValue(String.class);
                                            String photo = userSnap.child("photoUrl").getValue(String.class);
                                            if (name != null && !name.isEmpty()) {
                                                MentionSuggestAdapter.MentionItem item =
                                                        new MentionSuggestAdapter.MentionItem(uid, name, photo);
                                                allFollowers.add(item);
                                                // Store mention-safe key (spaces → removed for @token)
                                                String mentionKey = name.replace(" ", "");
                                                nameToUidMap.put(mentionKey, uid);
                                                nameToUidMap.put(name, uid); // also store with space
                                            }
                                            if (--remaining[0] <= 0) onLoaded.run();
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {
                                            if (--remaining[0] <= 0) onLoaded.run();
                                        }
                                    });
                        }
                    }

                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        onLoaded.run();
                    }
                });
    }

    // ── Suggestion display ────────────────────────────────────────────────

    private void showSuggestions(String query) {
        adapter.setItems(allFollowers);
        adapter.filter(query);
        if (adapter.getItemCount() == 0) {
            dismissSuggestions();
        } else {
            animateShow(rvSuggest);
        }
    }

    // ── Mention insertion ─────────────────────────────────────────────────

    private void insertMention(@NonNull MentionSuggestAdapter.MentionItem item) {
        dismissSuggestions();
        Editable ed     = etCaption.getText();
        if (ed == null) return;
        int cursor = etCaption.getSelectionStart();
        String full = ed.toString();

        // Find the '@' that triggered this suggestion
        int atIdx = -1;
        for (int i = Math.min(cursor, full.length()) - 1; i >= 0; i--) {
            char ch = full.charAt(i);
            if (ch == '@') { atIdx = i; break; }
            if (Character.isWhitespace(ch)) break;
        }

        // Build mention token without spaces (e.g. "@JohnDoe ")
        String token = "@" + item.name.replace(" ", "") + " ";

        // Store the mapping so getMentionedUids() resolves it later
        nameToUidMap.put(item.name.replace(" ", ""), item.uid);
        nameToUidMap.put(item.name, item.uid);

        if (atIdx >= 0) {
            ed.replace(atIdx, cursor, token);
            // Apply blue colour span over "@Name" (not trailing space)
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
        v.animate()
                .alpha(1f).translationY(0f)
                .setDuration(160)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateHide(View v) {
        v.animate()
                .alpha(0f).translationY(30f)
                .setDuration(120)
                .setInterpolator(new DecelerateInterpolator())
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
