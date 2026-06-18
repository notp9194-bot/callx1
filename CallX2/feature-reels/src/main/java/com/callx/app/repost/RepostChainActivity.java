package com.callx.app.repost;

import com.callx.app.reels.R;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.RepostChainAdapter;
import com.callx.app.models.RepostModel;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Repost Chain Visualization — shows the viral spread tree:
 * Original reel → who reposted → when → with what caption.
 * Displayed as a chronological timeline.
 */
public class RepostChainActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "reelId";
    public static final String EXTRA_REEL_THUMB = "reelThumb";

    private RecyclerView rv;
    private RepostChainAdapter adapter;
    private List<RepostModel> chain = new ArrayList<>();
    private TextView tvEmpty, tvHeader;
    private ProgressBar progress;
    private String reelId;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_repost_chain);
        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);

        rv       = findViewById(R.id.rv_repost_chain);
        tvEmpty  = findViewById(R.id.tv_empty_chain);
        tvHeader = findViewById(R.id.tv_chain_header);
        progress = findViewById(R.id.progress_chain);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RepostChainAdapter(chain, this);
        rv.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        loadChain();
    }

    private void loadChain() {
        progress.setVisibility(View.VISIBLE);
        FirebaseDatabase.getInstance().getReference("repostChain").child(reelId)
            .orderByChild("timestamp")
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    chain.clear();
                    for (DataSnapshot c : snap.getChildren()) {
                        RepostModel m = c.getValue(RepostModel.class);
                        if (m != null) chain.add(m);
                    }
                    tvHeader.setText("Repost Chain — " + chain.size() + " spreads");
                    adapter.notifyDataSetChanged();
                    progress.setVisibility(View.GONE);
                    tvEmpty.setVisibility(chain.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    progress.setVisibility(View.GONE);
                }
            });
    }
}
