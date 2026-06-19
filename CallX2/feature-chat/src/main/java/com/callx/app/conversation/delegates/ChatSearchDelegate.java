package com.callx.app.conversation.delegates;

import android.app.Activity;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * ChatSearchDelegate — In-chat message search with prev/next navigation,
 *                      All Media/Links/Docs launch.
 */
public class ChatSearchDelegate {

    private final Activity            activity;
    private final ActivityChatBinding binding;
    private final String              chatId;
    private final AppDatabase         db;
    private final Executor            ioExecutor;

    private final List<Integer> searchMatchPositions = new ArrayList<>();
    private int searchCurrentIndex = -1;

    public ChatSearchDelegate(Activity activity, ActivityChatBinding binding,
                              String chatId, AppDatabase db, Executor ioExecutor) {
        this.activity   = activity;
        this.binding    = binding;
        this.chatId     = chatId;
        this.db         = db;
        this.ioExecutor = ioExecutor;
    }

    public void openSearch() {
        if (binding.llSearchBar == null) return;
        binding.llSearchBar.setVisibility(View.VISIBLE);
        if (binding.etSearch != null) {
            binding.etSearch.requestFocus();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(binding.etSearch, 0);
            binding.etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    String q = s.toString().trim();
                    if (q.length() < 2) { searchMatchPositions.clear(); searchCurrentIndex = -1; updateSearchUI(); return; }
                    runSearchQuery(q);
                }
            });
            binding.etSearch.setOnEditorActionListener((v, actionId, e) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { navigateSearch(true); return true; }
                return false;
            });
        }
        if (binding.btnSearchPrev != null) binding.btnSearchPrev.setOnClickListener(v -> navigateSearch(false));
        if (binding.btnSearchNext != null) binding.btnSearchNext.setOnClickListener(v -> navigateSearch(true));
        if (binding.btnCloseSearch != null) binding.btnCloseSearch.setOnClickListener(v -> closeSearch());
    }

    private void runSearchQuery(String query) {
        ioExecutor.execute(() -> {
            List<MessageEntity> all = db.messageDao().getMessagesPaged(chatId, 2000, 0);
            List<Integer> matches = new ArrayList<>();
            String lq = query.toLowerCase(Locale.getDefault());
            for (int i = 0; i < all.size(); i++) {
                MessageEntity me = all.get(i);
                if (me.text != null && me.text.toLowerCase(Locale.getDefault()).contains(lq)) matches.add(i);
            }
            activity.runOnUiThread(() -> {
                searchMatchPositions.clear();
                searchMatchPositions.addAll(matches);
                searchCurrentIndex = matches.isEmpty() ? -1 : matches.size() - 1;
                updateSearchUI();
                if (!matches.isEmpty())
                    binding.rvMessages.scrollToPosition(searchMatchPositions.get(searchCurrentIndex));
            });
        });
    }

    private void navigateSearch(boolean forward) {
        if (searchMatchPositions.isEmpty()) return;
        if (forward) searchCurrentIndex = (searchCurrentIndex + 1) % searchMatchPositions.size();
        else searchCurrentIndex = (searchCurrentIndex - 1 + searchMatchPositions.size()) % searchMatchPositions.size();
        updateSearchUI();
        binding.rvMessages.scrollToPosition(searchMatchPositions.get(searchCurrentIndex));
    }

    private void updateSearchUI() {
        if (binding.tvSearchCount == null) return;
        if (searchMatchPositions.isEmpty()) {
            binding.tvSearchCount.setVisibility(View.GONE);
            if (binding.btnSearchPrev != null) binding.btnSearchPrev.setVisibility(View.GONE);
            if (binding.btnSearchNext != null) binding.btnSearchNext.setVisibility(View.GONE);
        } else {
            binding.tvSearchCount.setText((searchCurrentIndex + 1) + " / " + searchMatchPositions.size());
            binding.tvSearchCount.setVisibility(View.VISIBLE);
            if (binding.btnSearchPrev != null) binding.btnSearchPrev.setVisibility(View.VISIBLE);
            if (binding.btnSearchNext != null) binding.btnSearchNext.setVisibility(View.VISIBLE);
        }
    }

    public void closeSearch() {
        if (binding.llSearchBar != null) binding.llSearchBar.setVisibility(View.GONE);
        if (binding.etSearch != null) binding.etSearch.setText("");
        searchMatchPositions.clear(); searchCurrentIndex = -1; updateSearchUI();
    }

    public void openAllMediaLinksDocs(String chatId, String partnerName) {
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.AllMediaLinksDocsActivity");
            Intent i = new Intent(activity, cls);
            i.putExtra("chatId", chatId); i.putExtra("partnerName", partnerName); i.putExtra("isGroup", false);
            activity.startActivity(i);
        } catch (ClassNotFoundException e) {
            android.util.Log.e("ChatSearchDelegate", "AllMediaLinksDocsActivity not found", e);
        }
    }
}
