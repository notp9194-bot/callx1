package com.callx.app.repost;

import com.callx.app.reels.R;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.RepostListAdapter;
import com.callx.app.models.RepostModel;
import androidx.annotation.NonNull;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the list of all users who reposted a given reel.
 * Supports: search, real-time updates, caption display, quote badge.
 */
public class ViewRepostsActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID = "reelId";

    private RecyclerView rv;
    private RepostListAdapter adapter;
    private List<RepostModel> allReposts = new ArrayList<>();
    private List<RepostModel> filteredReposts = new ArrayList<>();
    private EditText etSearch;
    private TextView tvCount, tvEmpty;
    private ProgressBar progress;
    private DatabaseReference dbRef;
    private String reelId;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_view_reposts);
        reelId = getIntent().getStringExtra(EXTRA_REEL_ID);

        rv       = findViewById(R.id.rv_reposts);
        etSearch = findViewById(R.id.et_search_reposts);
        tvCount  = findViewById(R.id.tv_repost_count);
        tvEmpty  = findViewById(R.id.tv_empty_reposts);
        progress = findViewById(R.id.progress_reposts);

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RepostListAdapter(filteredReposts, this);
        rv.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
            public void afterTextChanged(Editable e) {}
        });

        loadReposts();
    }

    private void loadReposts() {
        progress.setVisibility(View.VISIBLE);
        dbRef = FirebaseDatabase.getInstance().getReference("reelReposts").child(reelId);
        dbRef.orderByChild("timestamp").addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                allReposts.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    RepostModel m = child.getValue(RepostModel.class);
                    if (m != null) allReposts.add(0, m); // newest first
                }
                tvCount.setText(allReposts.size() + " Reposts");
                filterList(etSearch.getText().toString());
                progress.setVisibility(View.GONE);
                tvEmpty.setVisibility(allReposts.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                progress.setVisibility(View.GONE);
            }
        });
    }

    private void filterList(String query) {
        filteredReposts.clear();
        for (RepostModel m : allReposts) {
            if (query.isEmpty() ||
                (m.reposterName != null && m.reposterName.toLowerCase().contains(query.toLowerCase())) ||
                (m.caption      != null && m.caption.toLowerCase().contains(query.toLowerCase()))) {
                filteredReposts.add(m);
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filteredReposts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (dbRef != null) dbRef.removeEventListener((ValueEventListener) null);
    }
}
