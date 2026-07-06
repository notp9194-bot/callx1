package com.callx.app.conversation.controllers;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
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
 * ChatSearchController — Production-grade in-chat search.
 *
 * Works for both ChatActivity (1:1) and GroupChatActivity.
 * Uses a minimal {@link SearchDelegate} so neither activity needs to implement
 * the full ChatActivityDelegate to get search support.
 *
 * Improvements over original:
 *   1. 300ms debounce — no query fired on every keystroke.
 *   2. Bubble highlighting — adapter highlights matched text in yellow.
 *   3. LinearSmoothScroller — animated scroll to matched message.
 *   4. Close clears adapter highlights.
 *   5. Minimum 2-char query guard (no flicker on single char).
 */
public class ChatSearchController {

    // ── Minimal delegate — only what search actually needs ────────────────

    /**
     * Minimum surface needed by ChatSearchController.
     * Both ChatActivity (via ChatActivityDelegate) and GroupChatActivity
     * implement this directly.
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

    private static final long DEBOUNCE_MS = 300L;

    private final SearchDelegate delegate;

    private final List<Integer> searchMatchPositions = new ArrayList<>();
    private int searchCurrentIndex = -1;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable      pendingSearch   = null;
    private String        lastQuery       = "";
    private boolean       searchOpen      = false;

    /** Constructor that takes the full ChatActivityDelegate (used by ChatActivity). */
    public ChatSearchController(ChatActivityDelegate delegate) {
        // ChatActivityDelegate extends SearchDelegate so this is safe.
        this.delegate = delegate;
    }

    /** Constructor that takes any SearchDelegate (used by GroupChatActivity). */
    public ChatSearchController(SearchDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Open ──────────────────────────────────────────────────────────────

    public void openSearch() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null || binding.llSearchBar == null) return;
        searchOpen = true;

        binding.llSearchBar.setVisibility(View.VISIBLE);
        if (binding.etSearch != null) {
            binding.etSearch.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    delegate.getActivity().getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(binding.etSearch, 0);

            binding.etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    scheduleSearch(s.toString().trim());
                }
            });

            binding.etSearch.setOnEditorActionListener((v, actionId, e) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    cancelPendingSearch();
                    String q = binding.etSearch.getText() != null
                            ? binding.etSearch.getText().toString().trim() : "";
                    if (q.length() >= 2) runSearchQuery(q);
                    return true;
                }
                return false;
            });
        }

        if (binding.btnSearchPrev != null)
            binding.btnSearchPrev.setOnClickListener(v -> navigateSearch(false));
        if (binding.btnSearchNext != null)
            binding.btnSearchNext.setOnClickListener(v -> navigateSearch(true));
        if (binding.btnCloseSearch != null)
            binding.btnCloseSearch.setOnClickListener(v -> closeSearch());
    }

    // ── Debounced scheduling ──────────────────────────────────────────────

    private void scheduleSearch(String query) {
        cancelPendingSearch();
        if (query.length() < 2) { clearResults(); return; }
        if (query.equals(lastQuery)) return;
        pendingSearch = () -> runSearchQuery(query);
        debounceHandler.postDelayed(pendingSearch, DEBOUNCE_MS);
    }

    private void cancelPendingSearch() {
        if (pendingSearch != null) {
            debounceHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────

    private void runSearchQuery(String query) {
        if (!searchOpen) return;
        lastQuery = query;
        delegate.getIoExecutor().execute(() -> {
            AppDatabase db = delegate.getDb();
            if (db == null) return;
            List<MessageEntity> all =
                    db.messageDao().getMessagesPaged(delegate.getChatId(), 5000, 0);
            List<Integer> matches = new ArrayList<>();
            String lq = query.toLowerCase(Locale.getDefault());
            for (int i = 0; i < all.size(); i++) {
                MessageEntity me = all.get(i);
                if (me.text != null
                        && me.text.toLowerCase(Locale.getDefault()).contains(lq)) {
                    matches.add(i);
                }
            }
            delegate.runOnMain(() -> {
                if (!searchOpen) return;
                searchMatchPositions.clear();
                searchMatchPositions.addAll(matches);
                searchCurrentIndex = matches.isEmpty() ? -1 : matches.size() - 1;
                updateSearchUI();
                if (delegate.getPagingAdapter() != null) {
                    delegate.getPagingAdapter().setSearchQuery(query);
                }
                if (!matches.isEmpty()) {
                    smoothScrollToMatch(searchMatchPositions.get(searchCurrentIndex));
                }
            });
        });
    }

    private void clearResults() {
        cancelPendingSearch();
        searchMatchPositions.clear();
        searchCurrentIndex = -1;
        lastQuery = "";
        updateSearchUI();
        if (delegate.getPagingAdapter() != null) {
            delegate.getPagingAdapter().setSearchQuery(null);
        }
    }

    // ── Navigate ──────────────────────────────────────────────────────────

    public void navigateSearch(boolean forward) {
        if (searchMatchPositions.isEmpty()) return;
        searchCurrentIndex = forward
                ? (searchCurrentIndex + 1) % searchMatchPositions.size()
                : (searchCurrentIndex - 1 + searchMatchPositions.size()) % searchMatchPositions.size();
        updateSearchUI();
        smoothScrollToMatch(searchMatchPositions.get(searchCurrentIndex));
    }

    private void smoothScrollToMatch(int position) {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null || binding.rvMessages == null) return;
        androidx.recyclerview.widget.LinearLayoutManager lm =
                (androidx.recyclerview.widget.LinearLayoutManager)
                        binding.rvMessages.getLayoutManager();
        if (lm == null) { binding.rvMessages.scrollToPosition(position); return; }

        androidx.recyclerview.widget.LinearSmoothScroller scroller =
                new androidx.recyclerview.widget.LinearSmoothScroller(delegate.getActivity()) {
                    @Override protected int getVerticalSnapPreference() { return SNAP_TO_START; }
                    @Override protected float calculateSpeedPerPixel(
                            android.util.DisplayMetrics dm) {
                        return 80f / dm.densityDpi;
                    }
                };
        scroller.setTargetPosition(position);
        lm.startSmoothScroll(scroller);
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void updateSearchUI() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null || binding.tvSearchCount == null) return;
        if (searchMatchPositions.isEmpty()) {
            binding.tvSearchCount.setVisibility(
                    lastQuery.length() >= 2 ? View.VISIBLE : View.GONE);
            if (lastQuery.length() >= 2) binding.tvSearchCount.setText("No results");
            if (binding.btnSearchPrev != null) binding.btnSearchPrev.setVisibility(View.GONE);
            if (binding.btnSearchNext != null) binding.btnSearchNext.setVisibility(View.GONE);
        } else {
            binding.tvSearchCount.setText(
                    (searchCurrentIndex + 1) + " / " + searchMatchPositions.size());
            binding.tvSearchCount.setVisibility(View.VISIBLE);
            if (binding.btnSearchPrev != null) binding.btnSearchPrev.setVisibility(View.VISIBLE);
            if (binding.btnSearchNext != null) binding.btnSearchNext.setVisibility(View.VISIBLE);
        }
    }

    // ── Close ─────────────────────────────────────────────────────────────

    public void closeSearch() {
        searchOpen = false;
        cancelPendingSearch();
        ActivityChatBinding binding = delegate.getBinding();
        if (binding == null) return;
        if (binding.llSearchBar != null) binding.llSearchBar.setVisibility(View.GONE);
        if (binding.etSearch != null) binding.etSearch.setText("");
        clearResults();
    }
}
