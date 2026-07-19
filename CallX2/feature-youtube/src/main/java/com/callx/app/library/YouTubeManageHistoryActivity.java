package com.callx.app.library;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.home.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.player.YouTubePlayerActivity;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeManageHistoryActivity — Full history management.
 * - Pause/Resume watch history
 * - Clear all history
 * - Delete individual videos from history
 * - Real-time list from Firebase
 */
public class YouTubeManageHistoryActivity extends AppCompatActivity {

    private static final String PREFS      = "yt_history_prefs";
    private static final String KEY_PAUSED = "history_paused";

    private RecyclerView        rvHistory;
    private YouTubeVideoAdapter adapter;
    private TextView            tvEmpty, tvHistoryStatus;
    private SwitchCompat        swPauseHistory;
    private SharedPreferences   prefs;
    private String              myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_manage_history);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Toolbar toolbar = findViewById(R.id.toolbar_yt_manage_history);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Manage All History");
        }

        tvEmpty         = findViewById(R.id.tv_yt_manage_history_empty);
        tvHistoryStatus = findViewById(R.id.tv_yt_history_status);
        swPauseHistory  = findViewById(R.id.sw_yt_pause_history);

        // Pause history toggle
        boolean isPaused = prefs.getBoolean(KEY_PAUSED, false);
        swPauseHistory.setChecked(isPaused);
        updateStatusText(isPaused);
        swPauseHistory.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(KEY_PAUSED, checked).apply();
            updateStatusText(checked);
            Toast.makeText(this,
                checked ? "Watch history paused" : "Watch history resumed",
                Toast.LENGTH_SHORT).show();
        });

        // Clear all history
        View btnClearAll = findViewById(R.id.btn_yt_clear_all_history);
        if (btnClearAll != null) {
            btnClearAll.setOnClickListener(v -> confirmClearHistory());
        }

        // Search history (clear search history too)
        View btnClearSearch = findViewById(R.id.btn_yt_clear_search_history);
        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                getSharedPreferences("yt_search_prefs", MODE_PRIVATE).edit()
                    .remove("search_history").apply();
                Toast.makeText(this, "Search history cleared", Toast.LENGTH_SHORT).show();
            });
        }

        // History RecyclerView — individual delete via long-press
        rvHistory = findViewById(R.id.rv_yt_manage_history);
        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(),
            video -> startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        adapter.setOnLongClickListener((video, position) -> {
            new AlertDialog.Builder(this)
                .setTitle("Remove from history?")
                .setMessage("\"" + video.title + "\" ko history se hatana chahte ho?")
                .setPositiveButton("Hatao", (dlg, w) -> {
                    if (!myUid.isEmpty())
                        YouTubeFirebaseUtils.watchHistoryRef(myUid).child(video.videoId).removeValue();
                    adapter.removeAt(position);
                    Toast.makeText(this, "History se hata diya", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        rvHistory.setAdapter(adapter);

        loadHistory();
    }

    private void updateStatusText(boolean paused) {
        if (tvHistoryStatus == null) return;
        tvHistoryStatus.setText(paused
            ? "History paused hai — jo videos dekho woh save nahi honge"
            : "History active hai — sab videos save ho rahe hain");
        tvHistoryStatus.setTextColor(paused ? 0xFFFF9800 : 0xFF888888);
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
            .setTitle("Clear watch history")
            .setMessage("Kya aap apni poori watch history delete karna chahte ho? Ye wapas nahi aayegi.")
            .setPositiveButton("Clear", (dlg, w) -> {
                if (myUid.isEmpty()) return;
                YouTubeFirebaseUtils.watchHistoryRef(myUid).removeValue()
                    .addOnSuccessListener(unused -> {
                        adapter.setData(new ArrayList<>());
                        if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                        Toast.makeText(this, "Watch history cleared", Toast.LENGTH_SHORT).show();
                    });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void loadHistory() {
        if (myUid.isEmpty()) {
            if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        YouTubeFirebaseUtils.watchHistoryRef(myUid)
            .orderByValue().limitToLast(100)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) ids.add(ds.getKey());
                    if (ids.isEmpty()) {
                        if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                        return;
                    }
                    // Reverse so newest first
                    java.util.Collections.reverse(ids);
                    fetchVideos(ids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                }
            });
    }

    private void fetchVideos(List<String> ids) {
        List<YouTubeVideo> videos = new ArrayList<>();
        final int[] count = {0};
        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                    if (v != null) synchronized (videos) { videos.add(v); }
                    count[0]++;
                    if (count[0] == ids.size()) {
                        runOnUiThread(() -> {
                            if (videos.isEmpty() && tvEmpty != null)
                                tvEmpty.setVisibility(View.VISIBLE);
                            else
                                adapter.setData(videos);
                        });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    count[0]++;
                    if (count[0] == ids.size()) runOnUiThread(() -> adapter.setData(videos));
                }
            });
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    /** Check if history is currently paused — called by PlayerActivity before saving */
    public static boolean isHistoryPaused(android.content.Context ctx) {
        return ctx.getSharedPreferences("yt_history_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("history_paused", false);
    }
}
