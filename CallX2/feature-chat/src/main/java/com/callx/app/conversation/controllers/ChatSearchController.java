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
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import static android.content.Context.INPUT_METHOD_SERVICE;

/**
 * ChatSearchController — Production-grade in-chat full-text search.
 *
 * Features:
 *   • 300 ms debounce — no DB hit on every keystroke.
 *   • Adapter highlight — yellow BackgroundColorSpan on matched text.
 *   • Smooth LinearSmoothScroller scroll to result.
 *   • Prev / Next navigation with "M of N" counter.
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
    }

    // ─────────────────────────────────────────────────────────────────────

    private static final long DEBOUNCE_MS      = 300L;
    private static final long ANIM_DURATION_MS = 180L;

    private final SearchDelegate delegate;

    private final List<Integer> matchPositions = new ArrayList<>();
    private int currentIndex = -1;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable      pendingSearch   = null;
    private String        lastQuery       = "";
    private boolean       searchOpen      = false;

    public ChatSearchController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    public ChatSearchController(SearchDelegate delegate) {
        this.delegate = delegate;
    }

    // ── State ─────────────────────────────────────────────────────────────

    /** True when the search bar is visible. Used by the activity back-press handler. */
    public boolean isOpen() { return searchOpen; }

    // ── Open ──────────────────────────────────────────────────────────────

    public void openSearch() {
        ActivityChatBinding b = delegate.getBinding();
        if (b == null || b.llSearchBar == null) return;
        searchOpen = true;
        resetState();

        // Slide-in animation
        b.llSearchBar.setVisibility(View.VISIBLE);
        b.llSearchBar.setTranslationY(-b.llSearchBar.getHeight() - 8f);
        b.llSearchBar.animate()
                .translationY(0f)
                .setDuration(ANIM_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        if (b.etSearch != null) {
            b.etSearch.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    delegate.getActivity().getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(b.etSearch, 0);

            b.etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int bef, int c) {
                    scheduleSearch(s.toString().trim());
                }
            });
            b.etSearch.setOnEditorActionListener((v, actionId, e) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    cancelPending();
                    String q = b.etSearch.getText() != null
                            ? b.etSearch.getText().toString().trim() : "";
                    if (q.length() >= 2) runQuery(q);
                    return true;
                }
                return false;
            });
        }
        if (b.btnSearchPrev   != null) b.btnSearchPrev.setOnClickListener(v -> step(false));
        if (b.btnSearchNext   != null) b.btnSearchNext.setOnClickListener(v -> step(true));
        if (b.btnCloseSearch  != null) b.btnCloseSearch.setOnClickListener(v -> closeSearch());
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
            ActivityChatBinding b = delegate.getBinding();
            if (b != null && b.tvSearchCount != null) {
                b.tvSearchCount.setText("Searching…");
                b.tvSearchCount.setVisibility(View.VISIBLE);
                if (b.btnSearchPrev != null) b.btnSearchPrev.setVisibility(View.GONE);
                if (b.btnSearchNext != null) b.btnSearchNext.setVisibility(View.GONE);
            }
        });

        delegate.getIoExecutor().execute(() -> {
            AppDatabase db = delegate.getDb();
            if (db == null) return;
            List<MessageEntity> all =
                    db.messageDao().getMessagesPaged(delegate.getChatId(), 10000, 0);
            String lq = query.toLowerCase(Locale.getDefault());
            List<Integer> hits = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                MessageEntity me = all.get(i);
                if (me.text != null && me.text.toLowerCase(Locale.getDefault()).contains(lq))
                    hits.add(i);
            }
            delegate.runOnMain(() -> {
                if (!searchOpen) return;
                matchPositions.clear();
                matchPositions.addAll(hits);
                currentIndex = hits.isEmpty() ? -1 : hits.size() - 1;
                refreshCountUI();
                MessagePagingAdapter adapter = delegate.getPagingAdapter();
                if (adapter != null) adapter.setSearchQuery(query);
                if (!hits.isEmpty()) smoothScrollTo(matchPositions.get(currentIndex));
            });
        });
    }

    // ── Navigate ──────────────────────────────────────────────────────────

    private void step(boolean forward) {
        if (matchPositions.isEmpty()) return;
        currentIndex = forward
                ? (currentIndex + 1) % matchPositions.size()
                : (currentIndex - 1 + matchPositions.size()) % matchPositions.size();
        refreshCountUI();
        smoothScrollTo(matchPositions.get(currentIndex));
    }

    private void smoothScrollTo(int position) {
        ActivityChatBinding b = delegate.getBinding();
        if (b == null || b.rvMessages == null) return;
        androidx.recyclerview.widget.LinearLayoutManager lm =
                (androidx.recyclerview.widget.LinearLayoutManager)
                        b.rvMessages.getLayoutManager();
        if (lm == null) { b.rvMessages.scrollToPosition(position); return; }
        androidx.recyclerview.widget.LinearSmoothScroller sc =
                new androidx.recyclerview.widget.LinearSmoothScroller(delegate.getActivity()) {
                    @Override protected int getVerticalSnapPreference() { return SNAP_TO_START; }
                    @Override protected float calculateSpeedPerPixel(android.util.DisplayMetrics dm) {
                        return 80f / dm.densityDpi;
                    }
                };
        sc.setTargetPosition(position);
        lm.startSmoothScroll(sc);
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void refreshCountUI() {
        ActivityChatBinding b = delegate.getBinding();
        if (b == null || b.tvSearchCount == null) return;
        boolean hasResults = !matchPositions.isEmpty();
        if (hasResults) {
            b.tvSearchCount.setText((currentIndex + 1) + " of " + matchPositions.size());
            b.tvSearchCount.setVisibility(View.VISIBLE);
            if (b.btnSearchPrev != null) b.btnSearchPrev.setVisibility(View.VISIBLE);
            if (b.btnSearchNext != null) b.btnSearchNext.setVisibility(View.VISIBLE);
        } else {
            b.tvSearchCount.setVisibility(lastQuery.length() >= 2 ? View.VISIBLE : View.GONE);
            if (lastQuery.length() >= 2) b.tvSearchCount.setText("No results");
            if (b.btnSearchPrev != null) b.btnSearchPrev.setVisibility(View.GONE);
            if (b.btnSearchNext != null) b.btnSearchNext.setVisibility(View.GONE);
        }
    }

    // ── Clear / Close ─────────────────────────────────────────────────────

    private void clearResults() {
        cancelPending();
        matchPositions.clear();
        currentIndex = -1;
        lastQuery = "";
        refreshCountUI();
        MessagePagingAdapter adapter = delegate.getPagingAdapter();
        if (adapter != null) adapter.setSearchQuery(null);
    }

    private void resetState() {
        matchPositions.clear();
        currentIndex = -1;
        lastQuery = "";
    }

    /** Called by the activity's back-press handler or close button. */
    public void closeSearch() {
        searchOpen = false;
        cancelPending();
        ActivityChatBinding b = delegate.getBinding();
        if (b == null) return;

        // Clear UI text + highlights
        if (b.etSearch != null) b.etSearch.setText("");
        clearResults();

        // Slide-out animation, then hide
        if (b.llSearchBar != null && b.llSearchBar.getVisibility() == View.VISIBLE) {
            b.llSearchBar.animate()
                    .translationY(-b.llSearchBar.getHeight() - 8f)
                    .setDuration(ANIM_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator animation) {
                            if (b.llSearchBar != null) {
                                b.llSearchBar.setVisibility(View.GONE);
                                b.llSearchBar.setTranslationY(0);
                            }
                        }
                    })
                    .start();
        }

        // Dismiss keyboard
        if (b.etSearch != null) {
            InputMethodManager imm = (InputMethodManager)
                    delegate.getActivity().getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(b.etSearch.getWindowToken(), 0);
        }
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
