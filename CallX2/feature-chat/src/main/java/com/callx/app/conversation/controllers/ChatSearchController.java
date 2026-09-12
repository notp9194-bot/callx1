package com.callx.app.conversation.controllers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.databinding.LayoutSearchBarBinding;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static android.content.Context.INPUT_METHOD_SERVICE;

/**
 * ChatSearchController — in-chat full-text search.
 *
 * Features:
 *   • 300 ms debounce — no DB hit on every keystroke.
 *   • DB-side search via {@code MessageDao#searchMessagesByText} — a LIKE
 *     query on (chatId, text), not a full-chat load filtered in Java, so
 *     cost doesn't grow unbounded with chat history size.
 *   • Adapter highlight — yellow BackgroundColorSpan on matched text,
 *     applied by {@code MessagePagingAdapter#setSearchQuery} to whichever
 *     rows are actually bound (works regardless of which page is loaded).
 *   • Navigation by messageId via {@link SearchDelegate#navigateToMessage},
 *     NOT by adapter position. The chat screen keyset-paginates through
 *     {@code MessageKeysetPagingSource} — only a small window of the chat
 *     is ever loaded into the adapter, so a match's index in the full
 *     search-result list has no relationship to any adapter position.
 *     navigateToMessage() reuses each activity's existing reply-jump /
 *     notification-jump logic (checks the loaded window first, falls back
 *     to a Room lookup + approximate scroll otherwise), so a match anywhere
 *     in chat history — not just the currently loaded page — is reachable.
 *   • Prev / Next navigation with "M of N" counter; lands on the most
 *     recent match first.
 *   • "No results" label when nothing found.
 *   • ≥ 2 char query guard — no flicker on single keystroke.
 *   • Animated slide-in / slide-out for the search bar.
 *   • Back-press integration via {@link #isOpen()} (activity wires this).
 *   • {@link #closeSearch()} clears all adapter highlights on close.
 *   • Works for both ChatActivity (1:1) and GroupChatActivity via
 *     {@link SearchDelegate} — no full ChatActivityDelegate needed.
 */
public class ChatSearchController {

    // ── Minimal delegate ───────────────────────────────────────────────────

    /**
     * The 7 methods ChatSearchController actually needs.
     * ChatActivityDelegate extends this so ChatActivity works with zero
     * extra code. GroupChatActivity creates an anonymous impl inline.
     */
    public interface SearchDelegate {
        ActivityChatBinding  getBinding();
        Activity             getActivity();
        AppDatabase          getDb();
        Executor             getIoExecutor();
        String               getChatId();
        void                 runOnMain(Runnable r);
        MessagePagingAdapter getPagingAdapter();

        /**
         * Jump to the message with this ID and flash-highlight it once
         * visible. MUST NOT assume the message is anywhere near the
         * currently loaded paging window — the chat screen loads messages
         * via {@code MessageKeysetPagingSource} (see that class), which only
         * keeps a small keyset-paged slice of the full history in the
         * adapter at any time. A correct implementation:
         *   1. Checks the loaded window first (pagingAdapter.peek(i) loop) —
         *      cheap, no DB hit, covers the common case of searching
         *      something already on screen.
         *   2. Falls back to a Room lookup (getMessageById +
         *      countMessagesAfterTimestamp) to compute an approximate
         *      position and scroll there when the match isn't loaded.
         * ChatActivity and GroupChatActivity already implement exactly this
         * for reply-tap-to-jump and notification-tap navigation
         * (navigateToOriginalMsg / scrollToMessageId) — search reuses those,
         * it does not reimplement its own (broken) position math.
         */
        void navigateToMessage(String messageId);
    }

    // ─────────────────────────────────────────────────────────────────────

    private static final long DEBOUNCE_MS      = 300L;
    private static final long ANIM_DURATION_MS = 180L;
    /** Hard cap on how many hits a single search pulls back — plenty for
     *  "find the message", keeps a giant chat from building a huge list. */
    private static final int  MAX_RESULTS      = 500;

    private final SearchDelegate delegate;

    // Matches are stored as message IDs (in chat order, oldest→newest), not
    // adapter positions — the chat screen keyset-paginates (see
    // MessageKeysetPagingSource), so only a small window of messages is ever
    // loaded into the adapter at once. A match found deep in chat history
    // has no adapter position at all until its page gets loaded, so
    // navigation goes through delegate.navigateToMessage(id) instead of any
    // position math done here.
    private final List<String> matchIds = new ArrayList<>();
    private int currentIndex = -1;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable      pendingSearch   = null;
    private String        lastQuery       = "";
    private boolean       searchOpen      = false;

    /** Set once the search bar ViewStub has actually inflated (first
     *  openSearch() call ever, for this activity instance). Null until then —
     *  every reader below must be null-safe. */
    private LayoutSearchBarBinding searchBarBinding;

    public ChatSearchController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
        wireInflateListener();
    }

    public ChatSearchController(SearchDelegate delegate) {
        this.delegate = delegate;
        wireInflateListener();
    }

    /**
     * PERF: registers the stub's inflate callback once, at controller
     * construction (the controller itself is created lazily and cached by
     * the activity, so this runs exactly once per activity lifetime).
     * setOnInflateListener() only *registers* the callback — it does not
     * inflate — so nothing is actually built until the first real
     * openSearch() call triggers stub.inflate(). That inflate fires this
     * listener synchronously, which is where all the one-time view wiring
     * (TextWatcher, editor action, button clicks) happens, so a second and
     * third openSearch() call never re-registers any listener.
     */
    private void wireInflateListener() {
        ActivityChatBinding b = delegate.getBinding();
        if (b == null || b.stubSearchBar == null) return;
        b.stubSearchBar.setOnInflateListener((stub, inflated) -> {
            LayoutSearchBarBinding sb = LayoutSearchBarBinding.bind(inflated);
            searchBarBinding = sb;

            sb.etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int bef, int c) {
                    scheduleSearch(s.toString().trim());
                }
            });
            sb.etSearch.setOnEditorActionListener((v, actionId, e) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    cancelPending();
                    String q = sb.etSearch.getText() != null
                            ? sb.etSearch.getText().toString().trim() : "";
                    if (q.length() >= 2) runQuery(q);
                    return true;
                }
                return false;
            });
            sb.btnSearchPrev.setOnClickListener(v -> step(false));
            sb.btnSearchNext.setOnClickListener(v -> step(true));
            sb.btnCloseSearch.setOnClickListener(v -> closeSearch());
        });
    }

    /** Lazily inflates the search-bar ViewStub on first use. Safe to call
     *  repeatedly — only actually inflates once per activity lifetime. */
    private LayoutSearchBarBinding searchBar(ActivityChatBinding binding) {
        if (searchBarBinding == null) {
            binding.stubSearchBar.inflate(); // triggers the OnInflateListener set in wireInflateListener()
        }
        return searchBarBinding;
    }

    // ── State ─────────────────────────────────────────────────────────────

    /** True when the search bar is visible. Used by the activity back-press handler. */
    public boolean isOpen() { return searchOpen; }

    // ── Open ──────────────────────────────────────────────────────────────

    public void openSearch() {
        ActivityChatBinding b = delegate.getBinding();
        if (b == null || b.stubSearchBar == null) return;
        searchOpen = true;
        resetState();

        LayoutSearchBarBinding sb = searchBar(b);
        if (sb == null) return;

        // Slide-in animation
        sb.getRoot().setVisibility(View.VISIBLE);
        sb.getRoot().setTranslationY(-sb.getRoot().getHeight() - 8f);
        sb.getRoot().animate()
                .translationY(0f)
                .setDuration(ANIM_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        sb.etSearch.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                delegate.getActivity().getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(sb.etSearch, 0);
    }

    // ── Debounce ──────────────────────────────────────────────────────────

    private void scheduleSearch(String query) {
        cancelPending();
        if (query.length() < 2) { clearResults(); return; }
        if (query.equals(lastQuery)) return;
        pendingSearch = () -> runQuery(query);
        debounceHandler.postDelayed(pendingSearch, DEBOUNCE_MS);
    }

    private void cancelPending() {
        if (pendingSearch != null) {
            debounceHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
    }

    // ── Query (background thread) ─────────────────────────────────────────

    private void runQuery(String query) {
        if (!searchOpen) return;
        lastQuery = query;

        // Show "Searching…" while debounce fires
        delegate.runOnMain(() -> {
            LayoutSearchBarBinding sb = searchBarBinding;
            if (sb != null) {
                sb.tvSearchCount.setText("Searching…");
                sb.tvSearchCount.setVisibility(View.VISIBLE);
                sb.btnSearchPrev.setVisibility(View.GONE);
                sb.btnSearchNext.setVisibility(View.GONE);
            }
        });

        delegate.getIoExecutor().execute(() -> {
            AppDatabase db = delegate.getDb();
            if (db == null) return;
            List<String> ids;
            // ULTRA-OPT: FTS4 index instead of a per-chat LIKE table scan —
            // see AppDatabase#MIGRATION_59_60's doc. Wrapped in try/catch as
            // a zero-risk fallback: if anything about the FTS path is ever
            // unavailable (e.g. this exact moment during/just after the
            // v60 migration on an existing install), search silently drops
            // back to the old LIKE query instead of the user seeing a
            // broken/empty search.
            String ftsQuery = buildFtsQuery(query);
            if (!ftsQuery.isEmpty()) {
                try {
                    ids = db.messageDao().searchMessageIdsFts(delegate.getChatId(), ftsQuery, MAX_RESULTS);
                } catch (Exception e) {
                    ids = legacyLikeSearch(db, query);
                }
            } else {
                // Query was pure punctuation/whitespace after sanitizing —
                // FTS MATCH can't run on it; LIKE still can.
                ids = legacyLikeSearch(db, query);
            }
            final List<String> finalIds = ids;
            delegate.runOnMain(() -> {
                if (!searchOpen || !query.equals(lastQuery)) return; // stale result, a newer query already superseded this one
                matchIds.clear();
                matchIds.addAll(finalIds);
                // Land on the most recent match first (closest to where the
                // chat is usually scrolled to), same convention WhatsApp uses.
                currentIndex = matchIds.isEmpty() ? -1 : matchIds.size() - 1;
                refreshCountUI();
                MessagePagingAdapter adapter = delegate.getPagingAdapter();
                if (adapter != null) adapter.setSearchQuery(query);
                if (currentIndex >= 0) delegate.navigateToMessage(matchIds.get(currentIndex));
            });
        });
    }

    private List<String> legacyLikeSearch(AppDatabase db, String query) {
        String pattern = buildLikePattern(query);
        List<MessageEntity> hits = db.messageDao()
                .searchMessagesByText(delegate.getChatId(), pattern, MAX_RESULTS);
        List<String> ids = new ArrayList<>(hits.size());
        for (MessageEntity me : hits) ids.add(me.id);
        return ids;
    }

    /**
     * Turns a raw user query into a SQLite LIKE pattern, escaping the LIKE
     * wildcard characters (% and _) and the escape character itself so a
     * literal search for e.g. "50% off" or "file_name" matches literally
     * instead of being interpreted as a wildcard.
     */
    private static String buildLikePattern(String raw) {
        String escaped = raw.replace("\\", "\\\\")
                             .replace("%", "\\%")
                             .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /**
     * ULTRA-OPT: builds an FTS4 MATCH expression from free-typed user input
     * for {@link com.callx.app.db.dao.MessageDao#searchMessageIdsFts}.
     *
     * Each whitespace-separated token becomes a `term*` prefix match
     * (space-separated terms are implicitly AND-ed by FTS4) — the closest
     * FTS equivalent to the old LIKE '%...%' feel for "start typing a
     * word" search, though (like Telegram/WhatsApp search) it matches
     * whole-word prefixes, not an arbitrary substring in the middle of a
     * word.
     *
     * Characters FTS4's own query-string parser treats specially
     * (quotes, parens, +, -, :, *, ^) are stripped from each token first
     * so user-typed punctuation can't accidentally form invalid or
     * unintended MATCH syntax (e.g. a leading "-" would otherwise be
     * parsed as a NOT operator). Returns "" if nothing usable remains
     * (e.g. the query was pure punctuation) — callers should treat that
     * as "can't run an FTS query for this input" and fall back.
     */
    private static String buildFtsQuery(String raw) {
        String[] tokens = raw.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            String cleaned = t.replaceAll("[\"()+\\-:*^]", "");
            if (cleaned.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(cleaned).append('*');
        }
        return sb.toString();
    }

    // ── Navigate ──────────────────────────────────────────────────────────

    private void step(boolean forward) {
        if (matchIds.isEmpty()) return;
        currentIndex = forward
                ? (currentIndex + 1) % matchIds.size()
                : (currentIndex - 1 + matchIds.size()) % matchIds.size();
        refreshCountUI();
        delegate.navigateToMessage(matchIds.get(currentIndex));
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void refreshCountUI() {
        LayoutSearchBarBinding sb = searchBarBinding; // null if search never opened this session
        if (sb == null) return;
        boolean hasResults = !matchIds.isEmpty();
        if (hasResults) {
            sb.tvSearchCount.setText((currentIndex + 1) + " of " + matchIds.size());
            sb.tvSearchCount.setVisibility(View.VISIBLE);
            sb.btnSearchPrev.setVisibility(View.VISIBLE);
            sb.btnSearchNext.setVisibility(View.VISIBLE);
        } else {
            sb.tvSearchCount.setVisibility(lastQuery.length() >= 2 ? View.VISIBLE : View.GONE);
            if (lastQuery.length() >= 2) sb.tvSearchCount.setText("No results");
            sb.btnSearchPrev.setVisibility(View.GONE);
            sb.btnSearchNext.setVisibility(View.GONE);
        }
    }

    // ── Clear / Close ─────────────────────────────────────────────────────

    private void clearResults() {
        cancelPending();
        matchIds.clear();
        currentIndex = -1;
        lastQuery = "";
        refreshCountUI();
        MessagePagingAdapter adapter = delegate.getPagingAdapter();
        if (adapter != null) adapter.setSearchQuery(null);
    }

    private void resetState() {
        matchIds.clear();
        currentIndex = -1;
        lastQuery = "";
    }

    /** Called by the activity's back-press handler or close button. */
    public void closeSearch() {
        searchOpen = false;
        cancelPending();
        // Never opened this session → nothing was ever inflated, nothing to close.
        LayoutSearchBarBinding sb = searchBarBinding;
        if (sb == null) return;

        // Clear UI text + highlights
        sb.etSearch.setText("");
        clearResults();

        // Slide-out animation, then hide. `sb` (not the searchBarBinding
        // field) is what the AnimatorListenerAdapter callback below
        // captures — it's a local final reference to the one-and-only
        // inflated binding, so there's no risk of it reading a stale/rebound
        // field by the time the animation actually ends.
        if (sb.getRoot().getVisibility() == View.VISIBLE) {
            sb.getRoot().animate()
                    .translationY(-sb.getRoot().getHeight() - 8f)
                    .setDuration(ANIM_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator animation) {
                            sb.getRoot().setVisibility(View.GONE);
                            sb.getRoot().setTranslationY(0);
                        }
                    })
                    .start();
        }

        // Dismiss keyboard
        InputMethodManager imm = (InputMethodManager)
                delegate.getActivity().getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(sb.etSearch.getWindowToken(), 0);
    }

    /** Call from Activity.onDestroy() to prevent leaks. */
    public void onDestroy() {
        cancelPending();
        debounceHandler.removeCallbacksAndMessages(null);
        searchOpen = false;
        MessagePagingAdapter adapter = delegate.getPagingAdapter();
        if (adapter != null) adapter.setSearchQuery(null);
    }
}
