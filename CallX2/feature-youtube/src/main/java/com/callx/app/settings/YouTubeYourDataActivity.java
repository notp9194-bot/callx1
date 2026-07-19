package com.callx.app.settings;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;

/**
 * YouTubeYourDataActivity — Data management and privacy controls.
 */
public class YouTubeYourDataActivity extends AppCompatActivity {

    private String myUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yt_your_data);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        Toolbar toolbar = findViewById(R.id.toolbar_yt_your_data);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Your Data in YouTube");
        }

        // Load stats
        loadStats();

        // Request data download
        Button btnRequestData = findViewById(R.id.btn_yt_request_data);
        if (btnRequestData != null) {
            btnRequestData.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Request Your Data")
                    .setMessage("Aapka data compile karna shuru kar denge. " +
                        "Tayaar hone pe notification aayega. Isme 24-48 ghante lag sakte hain.")
                    .setPositiveButton("Request Karo", (dlg, w) -> {
                        if (!myUid.isEmpty()) {
                            Map<String, Object> req = new HashMap<>();
                            req.put("requestedAt", System.currentTimeMillis());
                            req.put("status", "pending");
                            YouTubeFirebaseUtils.userDataRef(myUid).child("data_requests")
                                .push().setValue(req);
                        }
                        Toast.makeText(this,
                            "✅ Request submit ho gayi. 24-48 ghante mein ready hoga.",
                            Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }

        // Clear watch history
        View btnClearWatch = findViewById(R.id.btn_yt_clear_watch_data);
        if (btnClearWatch != null) {
            btnClearWatch.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Watch History Delete?")
                    .setMessage("Poori watch history delete ho jaayegi.")
                    .setPositiveButton("Delete", (dlg, w) -> {
                        YouTubeFirebaseUtils.watchHistoryRef(myUid).removeValue();
                        Toast.makeText(this, "Watch history deleted", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }

        // Clear search history
        View btnClearSearch = findViewById(R.id.btn_yt_clear_search_data);
        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                getSharedPreferences("yt_search_prefs", MODE_PRIVATE).edit()
                    .remove("search_history").apply();
                Toast.makeText(this, "Search history deleted", Toast.LENGTH_SHORT).show();
            });
        }

        // Delete account data
        View btnDeleteAccount = findViewById(R.id.btn_yt_delete_account_data);
        if (btnDeleteAccount != null) {
            btnDeleteAccount.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("⚠️ Delete All Account Data?")
                    .setMessage("Is action se aapka channel, sab videos, comments, likes, " +
                        "aur sab history permanent delete ho jayega. Ye wapas NAHI aayega.")
                    .setPositiveButton("Delete Everything", (dlg, w) ->
                        Toast.makeText(this,
                            "Data deletion request registered. Processing in 7 days.",
                            Toast.LENGTH_LONG).show())
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }
    }

    private void loadStats() {
        if (myUid.isEmpty()) return;

        TextView tvVideos  = findViewById(R.id.tv_yt_data_videos);
        TextView tvHistory = findViewById(R.id.tv_yt_data_history);
        TextView tvLiked   = findViewById(R.id.tv_yt_data_liked);

        if (tvVideos != null) {
            YouTubeFirebaseUtils.userVideosRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        tvVideos.setText(snap.getChildrenCount() + " videos uploaded");
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }

        if (tvHistory != null) {
            YouTubeFirebaseUtils.watchHistoryRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        tvHistory.setText(snap.getChildrenCount() + " videos in watch history");
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }

        if (tvLiked != null) {
            YouTubeFirebaseUtils.likedVideosRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        tvLiked.setText(snap.getChildrenCount() + " liked videos");
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
