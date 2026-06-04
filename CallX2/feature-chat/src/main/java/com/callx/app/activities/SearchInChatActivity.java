package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.adapters.SearchInChatAdapter;
import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivitySearchInChatBinding;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SearchInChatActivity — Search messages within a single conversation.
 *
 * Features:
 *   - Live search: results update as user types (300ms debounce)
 *   - Highlights matched keyword in each result row
 *   - Tap a result → navigates back to ChatActivity and scrolls to that message
 *   - Shows message type icon (text / image / audio / file / video)
 *   - Shows sender name + timestamp for each result
 *   - Result count badge (e.g. "12 results")
 *   - Empty state illustration when no results
 *   - Works fully offline (searches Room DB, no Firebase call needed)
 *
 * Launch from ChatActivity (chat_menu.xml → action_search):
 *
 *   case R.id.action_search:
 *       Intent i = new Intent(this, SearchInChatActivity.class);
 *       i.putExtra("chatId",      chatId);
 *       i.putExtra("currentUid",  currentUid);
 *       i.putExtra("partnerName", partnerName);
 *       startActivityForResult(i, REQ_SEARCH);
 *       break;
 *
 * On result (in ChatActivity.onActivityResult):
 *
 *   if (requestCode == REQ_SEARCH && resultCode == RESULT_OK && data != null) {
 *       String msgId = data.getStringExtra("messageId");
 *       long   ts    = data.getLongExtra("timestamp", -1);
 *       scrollToMessage(msgId, ts);   // your existing scroll helper
 *   }
 */
public class SearchInChatActivity extends AppCompatActivity {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final String TAG         = "SearchInChat";
    private static final int    DEBOUNCE_MS = 300;   // wait after last keystroke
    private static final int    MIN_QUERY   = 1;     // minimum chars before searching

    // ── Views ──────────────────────────────────────────────────────────────
    private ActivitySearchInChatBinding binding;

    // ── Data ───────────────────────────────────────────────────────────────
    private String chatId;
    private String currentUid;
    private String partnerName;

    // ── Adapter ────────────────────────────────────────────────────────────
    private SearchInChatAdapter adapter;

    // ── Search state ───────────────────────────────────────────────────────
    private String lastQuery     = "";
    private int    currentIndex  = -1;   // index within results for prev/next nav
    private List<MessageEntity> currentResults = new ArrayList<>();

    // ── Threading ──────────────────────────────────────────────────────────
    private final ExecutorService ioExecutor   = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler  = new Handler(Looper.getMainLooper());
    private Runnable              debounceTask;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySearchInChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        readExtras();
        setupToolbar();
        setupRecyclerView();
        setupSearchBar();
        showEmptyState(true, false); // initial idle state
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────
    // INIT
    // ─────────────────────────────────────────────────────────────────────

    private void readExtras() {
        Intent i    = getIntent();
        chatId      = i.getStringExtra("chatId");
        currentUid  = i.getStringExtra("currentUid");
        partnerName = i.getStringExtra("partnerName");
    }

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> finish());

        String title = partnerName != null ? "Search in " + partnerName : "Search";
        binding.tvTitle.setText(title);

        // Prev / Next navigation buttons
        binding.btnPrev.setOnClickListener(v -> navigateResult(-1));
        binding.btnNext.setOnClickListener(v -> navigateResult(+1));
        updateNavButtons();
    }

    private void setupRecyclerView() {
        adapter = new SearchInChatAdapter(result -> {
            // User tapped a search result — return messageId + timestamp to ChatActivity
            Intent data = new Intent();
            data.putExtra("messageId", result.id);
            data.putExtra("timestamp", result.timestamp != null ? result.timestamp : -1L);
            setResult(RESULT_OK, data);
            finish();
        });

        binding.rvResults.setLayoutManager(new LinearLayoutManager(this));
        binding.rvResults.setAdapter(adapter);
        binding.rvResults.setHasFixedSize(false);
    }

    private void setupSearchBar() {
        // Auto-show keyboard on launch
        binding.etSearch.requestFocus();
        showKeyboard();

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                binding.btnClear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                scheduleSearch(q);
            }
        });

        binding.etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });

        binding.btnClear.setOnClickListener(v -> {
            binding.etSearch.setText("");
            currentResults.clear();
            currentIndex = -1;
            adapter.submitResults(new ArrayList<>(), "");
            showEmptyState(true, false);
            updateResultCount(0, "");
            updateNavButtons();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // SEARCH
    // ─────────────────────────────────────────────────────────────────────

    private void scheduleSearch(String query) {
        // Cancel previous pending search
        if (debounceTask != null) mainHandler.removeCallbacks(debounceTask);

        if (query.length() < MIN_QUERY) {
            currentResults.clear();
            currentIndex = -1;
            adapter.submitResults(new ArrayList<>(), "");
            showEmptyState(true, false);
            updateResultCount(0, "");
            updateNavButtons();
            return;
        }

        debounceTask = () -> executeSearch(query);
        mainHandler.postDelayed(debounceTask, DEBOUNCE_MS);
    }

    private void executeSearch(String query) {
        if (chatId == null) return;

        lastQuery = query;
        showLoading(true);

        ioExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());

            // Search text messages + file names
            List<MessageEntity> results = db.messageDao().searchMessages(chatId, "%" + query + "%");

            mainHandler.post(() -> {
                showLoading(false);
                currentResults = results != null ? results : new ArrayList<>();
                currentIndex   = currentResults.isEmpty() ? -1 : 0;

                adapter.submitResults(new ArrayList<>(currentResults), query);
                updateResultCount(currentResults.size(), query);
                updateNavButtons();

                boolean isEmpty = currentResults.isEmpty();
                showEmptyState(isEmpty, !isEmpty || !query.isEmpty());

                // Scroll to first result
                if (!currentResults.isEmpty()) {
                    binding.rvResults.scrollToPosition(0);
                }
            });
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // PREV / NEXT NAVIGATION
    // ─────────────────────────────────────────────────────────────────────

    private void navigateResult(int direction) {
        if (currentResults.isEmpty()) return;
        currentIndex = (currentIndex + direction + currentResults.size()) % currentResults.size();
        binding.rvResults.smoothScrollToPosition(currentIndex);
        adapter.setHighlightedIndex(currentIndex);
        updateNavButtons();
    }

    private void updateNavButtons() {
        boolean hasResults = !currentResults.isEmpty();
        binding.btnPrev.setEnabled(hasResults);
        binding.btnNext.setEnabled(hasResults);
        binding.btnPrev.setAlpha(hasResults ? 1f : 0.3f);
        binding.btnNext.setAlpha(hasResults ? 1f : 0.3f);
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private void updateResultCount(int count, String query) {
        if (query.isEmpty() || count == 0) {
            binding.tvResultCount.setVisibility(View.GONE);
            return;
        }
        binding.tvResultCount.setVisibility(View.VISIBLE);
        binding.tvResultCount.setText(count + (count == 1 ? " result" : " results"));
    }

    private void showLoading(boolean show) {
        binding.progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            binding.rvResults.setVisibility(View.GONE);
            binding.layoutEmptyState.setVisibility(View.GONE);
        } else {
            binding.rvResults.setVisibility(View.VISIBLE);
        }
    }

    /**
     * @param show     Show the empty state container
     * @param searched True if user has typed something (show "no results" vs idle hint)
     */
    private void showEmptyState(boolean show, boolean searched) {
        if (!show) {
            binding.layoutEmptyState.setVisibility(View.GONE);
            binding.rvResults.setVisibility(View.VISIBLE);
            return;
        }
        binding.rvResults.setVisibility(View.GONE);
        binding.layoutEmptyState.setVisibility(View.VISIBLE);
        if (searched) {
            binding.tvEmptyTitle.setText("No results found");
            binding.tvEmptySubtitle.setText("Try different keywords");
            binding.ivEmptyIcon.setImageResource(R.drawable.ic_search);
        } else {
            binding.tvEmptyTitle.setText("Search messages");
            binding.tvEmptySubtitle.setText("Type to search in this conversation");
            binding.ivEmptyIcon.setImageResource(R.drawable.ic_search);
        }
    }

    private void showKeyboard() {
        binding.etSearch.post(() -> {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(binding.etSearch.getWindowToken(), 0);
    }
}
