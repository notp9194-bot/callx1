package com.callx.app.conversation.controllers;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.db.entity.MessageEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static android.content.Context.INPUT_METHOD_SERVICE;

/**
 * Handles in-chat search: open, close, query, prev/next navigation.
 */
public class ChatSearchController {

    private final ChatActivityDelegate delegate;

    private final List<Integer> searchMatchPositions = new ArrayList<>();
    private int searchCurrentIndex = -1;

    public ChatSearchController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Open ──────────────────────────────────────────────────────────────

    public void openSearch() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llSearchBar == null) return;

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
                    String query = s.toString().trim();
                    if (query.length() < 2) {
                        searchMatchPositions.clear();
                        searchCurrentIndex = -1;
                        updateSearchUI();
                        return;
                    }
                    runSearchQuery(query);
                }
            });

            binding.etSearch.setOnEditorActionListener((v, actionId, e) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    navigateSearch(true);
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

    // ── Query ─────────────────────────────────────────────────────────────

    private void runSearchQuery(String query) {
        delegate.getIoExecutor().execute(() -> {
            List<MessageEntity> all =
                    delegate.getDb().messageDao().getMessagesPaged(delegate.getChatId(), 2000, 0);
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
                searchMatchPositions.clear();
                searchMatchPositions.addAll(matches);
                searchCurrentIndex = matches.isEmpty() ? -1 : matches.size() - 1;
                updateSearchUI();
                if (!matches.isEmpty()) {
                    delegate.getBinding().rvMessages.scrollToPosition(
                            searchMatchPositions.get(searchCurrentIndex));
                }
            });
        });
    }

    // ── Navigate ──────────────────────────────────────────────────────────

    public void navigateSearch(boolean forward) {
        if (searchMatchPositions.isEmpty()) return;
        if (forward) {
            searchCurrentIndex = (searchCurrentIndex + 1) % searchMatchPositions.size();
        } else {
            searchCurrentIndex = (searchCurrentIndex - 1 + searchMatchPositions.size())
                                  % searchMatchPositions.size();
        }
        updateSearchUI();
        delegate.getBinding().rvMessages.scrollToPosition(
                searchMatchPositions.get(searchCurrentIndex));
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private void updateSearchUI() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.tvSearchCount == null) return;
        if (searchMatchPositions.isEmpty()) {
            binding.tvSearchCount.setVisibility(View.GONE);
            if (binding.btnSearchPrev != null) binding.btnSearchPrev.setVisibility(View.GONE);
            if (binding.btnSearchNext != null) binding.btnSearchNext.setVisibility(View.GONE);
        } else {
            String label = (searchCurrentIndex + 1) + " / " + searchMatchPositions.size();
            binding.tvSearchCount.setText(label);
            binding.tvSearchCount.setVisibility(View.VISIBLE);
            if (binding.btnSearchPrev != null) binding.btnSearchPrev.setVisibility(View.VISIBLE);
            if (binding.btnSearchNext != null) binding.btnSearchNext.setVisibility(View.VISIBLE);
        }
    }

    // ── Close ─────────────────────────────────────────────────────────────

    public void closeSearch() {
        ActivityChatBinding binding = delegate.getBinding();
        if (binding.llSearchBar != null) binding.llSearchBar.setVisibility(View.GONE);
        if (binding.etSearch != null) binding.etSearch.setText("");
        searchMatchPositions.clear();
        searchCurrentIndex = -1;
        updateSearchUI();
    }
}
