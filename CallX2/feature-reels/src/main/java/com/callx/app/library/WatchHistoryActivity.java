package com.callx.app.library;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;

/**
 * WatchHistoryActivity — Full watch history management UI
 *
 * Features:
 *  ✅ Chronological list of all watched reels (newest first)
 *  ✅ Search bar to filter by owner name or caption
 *  ✅ Individual delete (swipe or long-press)
 *  ✅ "Clear All" with confirmation dialog
 *  ✅ Group header by date: "Today", "Yesterday", "This Week", older dates
 *  ✅ Empty state illustration
 *  ✅ Tap to re-watch reel via SingleReelPlayerActivity
 *  ✅ Shows % watched completion bar per entry
 *  ✅ Watch count badge
 *  ✅ Real-time Firebase listener (live updates)
 *
 * Usage:
 *   startActivity(new Intent(context, WatchHistoryActivity.class));
 */
public class WatchHistoryActivity extends AppCompatActivity
    implements WatchHistoryAdapter.OnItemClickListener {

    private RecyclerView        rvHistory;
    private ProgressBar         progressBar;
    private LinearLayout        layoutEmpty;
    private TextView            tvEmptyMsg, tvTotalCount;
    private WatchHistoryAdapter adapter;
    private SearchView          searchView;

    private final List<WatchHistoryItem> allItems      = new ArrayList<>();
    private final List<WatchHistoryItem> filteredItems = new ArrayList<>();
    private ValueEventListener           listener;
    private String                       searchQuery   = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch_history);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Watch History");
        }
        tb.setNavigationOnClickListener(v -> finish());

        rvHistory   = findViewById(R.id.rv_watch_history);
        progressBar = findViewById(R.id.progress_watch_history);
        layoutEmpty = findViewById(R.id.layout_empty_history);
        tvEmptyMsg  = findViewById(R.id.tv_empty_history_msg);
        tvTotalCount = findViewById(R.id.tv_history_total_count);

        adapter = new WatchHistoryAdapter(this, filteredItems, this);
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(adapter);

        loadHistory();
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_watch_history, menu);

        MenuItem searchItem = menu.findItem(R.id.action_search_history);
        if (searchItem != null) {
            searchView = (SearchView) searchItem.getActionView();
            if (searchView != null) {
                searchView.setQueryHint("Search by name or caption…");
                searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override public boolean onQueryTextSubmit(String q) {
                        filter(q); return true;
                    }
                    @Override public boolean onQueryTextChange(String q) {
                        filter(q); return true;
                    }
                });
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_clear_all_history) {
            confirmClearAll();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Load from Firebase ────────────────────────────────────────────────────

    private void loadHistory() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || uid.isEmpty()) {
            showEmpty("Sign in to see your history");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                allItems.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    WatchHistoryItem item = s.getValue(WatchHistoryItem.class);
                    if (item != null) allItems.add(item);
                }
                // Newest first
                allItems.sort((a, b) -> Long.compare(b.watchedAtMs, a.watchedAtMs));
                progressBar.setVisibility(View.GONE);
                filter(searchQuery);
                updateCountHeader();
            }

            @Override public void onCancelled(@NonNull DatabaseError e) {
                progressBar.setVisibility(View.GONE);
                showEmpty("Failed to load history");
            }
        };

        WatchHistoryManager.get()
            .currentUserHistoryRef()
            .orderByChild("watchedAtMs")
            .addValueEventListener(listener);
    }

    // ── Filter / search ───────────────────────────────────────────────────────

    private void filter(String query) {
        searchQuery = query == null ? "" : query.toLowerCase().trim();
        filteredItems.clear();

        for (WatchHistoryItem item : allItems) {
            if (searchQuery.isEmpty()) {
                filteredItems.add(item);
            } else {
                boolean nameMatch = item.ownerName != null
                    && item.ownerName.toLowerCase().contains(searchQuery);
                boolean captionMatch = item.caption != null
                    && item.caption.toLowerCase().contains(searchQuery);
                if (nameMatch || captionMatch) filteredItems.add(item);
            }
        }

        if (filteredItems.isEmpty()) {
            String msg = searchQuery.isEmpty()
                ? "You haven't watched any reels yet"
                : "No results for "" + searchQuery + """;
            showEmpty(msg);
        } else {
            layoutEmpty.setVisibility(View.GONE);
            rvHistory.setVisibility(View.VISIBLE);
        }

        adapter.notifyDataSetChanged();
        updateCountHeader();
    }

    private void updateCountHeader() {
        int total = allItems.size();
        if (total == 0) {
            tvTotalCount.setVisibility(View.GONE);
        } else {
            tvTotalCount.setVisibility(View.VISIBLE);
            tvTotalCount.setText(total + " reel" + (total == 1 ? "" : "s") + " watched");
        }
    }

    private void showEmpty(String msg) {
        rvHistory.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        tvEmptyMsg.setText(msg);
    }

    // ── WatchHistoryAdapter.OnItemClickListener ───────────────────────────────

    @Override
    public void onPlay(WatchHistoryItem item, int position) {
        Intent intent = new Intent(this, SingleReelPlayerActivity.class);
        intent.putExtra(SingleReelPlayerActivity.EXTRA_REEL_ID, item.reelId);
        startActivity(intent);
    }

    @Override
    public void onDelete(WatchHistoryItem item, int position) {
        new AlertDialog.Builder(this)
            .setTitle("Remove from history?")
            .setMessage("This reel will be removed from your watch history.")
            .setPositiveButton("Remove", (d, w) -> {
                WatchHistoryManager.get().delete(item.reelId);
                allItems.remove(item);
                adapter.removeAt(position);
                filteredItems.remove(item);
                if (filteredItems.isEmpty()) showEmpty("No more history");
                updateCountHeader();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Clear all ─────────────────────────────────────────────────────────────

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
            .setTitle("Clear watch history?")
            .setMessage("All " + allItems.size() + " entries will be permanently removed.")
            .setPositiveButton("Clear All", (d, w) -> {
                WatchHistoryManager.get().clearAll(success -> {
                    if (success) {
                        allItems.clear();
                        filteredItems.clear();
                        adapter.notifyDataSetChanged();
                        updateCountHeader();
                        showEmpty("Watch history cleared");
                        Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Failed to clear history", Toast.LENGTH_SHORT).show();
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override protected void onDestroy() {
        super.onDestroy();
        if (listener != null)
            WatchHistoryManager.get().currentUserHistoryRef().removeEventListener(listener);
    }
}
