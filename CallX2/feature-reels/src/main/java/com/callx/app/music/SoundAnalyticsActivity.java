package com.callx.app.music;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.Locale;

/**
 * SoundAnalyticsActivity — Lightweight sound stats screen.
 * Single Firebase read. No canvas, no programmatic charts.
 * Firebase path: musicLibrary/{soundId}/
 */
public class SoundAnalyticsActivity extends AppCompatActivity {

    public static final String EXTRA_SOUND_ID    = "analytics_sound_id";
    public static final String EXTRA_SOUND_TITLE = "analytics_sound_title";

    private String soundId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_analytics);

        soundId = getIntent().getStringExtra(EXTRA_SOUND_ID);
        String title = getIntent().getStringExtra(EXTRA_SOUND_TITLE);

        ImageButton btnBack = findViewById(R.id.btn_back);
        TextView tvTitle    = findViewById(R.id.tv_sound_name);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (tvTitle != null) tvTitle.setText(title != null && !title.isEmpty() ? title : "Sound Analytics");

        loadStats();
    }

    private void loadStats() {
        if (soundId == null || soundId.isEmpty()) {
            showState(false);
            return;
        }

        ProgressBar pb = findViewById(R.id.progress);
        if (pb != null) pb.setVisibility(View.VISIBLE);

        FirebaseUtils.getMusicLibraryRef().child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (pb != null) pb.setVisibility(View.GONE);
                    if (!snap.exists()) { showState(false); return; }

                    Long   reels   = snap.child("usageCount").getValue(Long.class);
                    Long   rank    = snap.child("trendingRank").getValue(Long.class);
                    Long   saves   = snap.child("savesCount").getValue(Long.class);
                    String creator = snap.child("topCreator").getValue(String.class);
                    Double vtr     = snap.child("analytics").child("avgVTR").getValue(Double.class);

                    setText(R.id.tv_reels,   fmt(reels)   + " Reels");
                    setText(R.id.tv_saves,   fmt(saves)   + " Saves");
                    setText(R.id.tv_rank,    rank != null && rank > 0 ? "#" + rank + " Trending" : "—");
                    setText(R.id.tv_creator, creator != null && !creator.isEmpty() ? "@" + creator : "—");
                    setText(R.id.tv_vtr,     vtr != null ? String.format(Locale.US, "%.1f%%", vtr * 100) : "—");

                    showState(true);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    if (pb != null) pb.setVisibility(View.GONE);
                    showState(false);
                }
            });
    }

    private void showState(boolean hasData) {
        View stats  = findViewById(R.id.layout_stats);
        View noData = findViewById(R.id.tv_no_data);
        if (stats  != null) stats.setVisibility(hasData ? View.VISIBLE : View.GONE);
        if (noData != null) noData.setVisibility(hasData ? View.GONE : View.VISIBLE);
    }

    private void setText(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(text);
    }

    private static String fmt(Long n) {
        if (n == null || n == 0) return "0";
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
