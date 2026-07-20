package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * v34: Discover public communities.
 *
 * Features:
 *  - Lists all non-private communities from Firebase
 *  - Real-time search by name
 *  - Filter chips by category
 *  - Join directly from card (public) or Request to Join (private)
 *  - Shows member count, post count, category badge, verified tick
 */
public class CommunityDiscoverActivity extends AppCompatActivity {

    private static final String[] CATEGORIES = {"All","Tech","Sports","Gaming","Music","Art","Food","Health","Education","Other"};

    private RecyclerView rvCommunities;
    private EditText etSearch;
    private ChipGroup chipGroup;
    private View progressBar, emptyState;

    private DiscoverAdapter adapter;
    private List<CommunityEntity> allItems  = new ArrayList<>();
    private String selectedCategory = "All";
    private String searchQuery      = "";

    private CommunityRepository repo;
    private String currentUid;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_discover);

        currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Discover Communities");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rvCommunities = findViewById(R.id.rv_discover_communities);
        etSearch      = findViewById(R.id.et_discover_search);
        chipGroup     = findViewById(R.id.chip_group_categories);
        progressBar   = findViewById(R.id.progress_discover);
        emptyState    = findViewById(R.id.empty_discover);

        adapter = new DiscoverAdapter();
        rvCommunities.setLayoutManager(new LinearLayoutManager(this));
        rvCommunities.setItemAnimator(null);
        rvCommunities.setAdapter(adapter);

        buildCategoryChips();
        setupSearch();
        loadCommunities();
    }

    private void buildCategoryChips() {
        for (String cat : CATEGORIES) {
            Chip chip = new Chip(this);
            chip.setText(cat);
            chip.setCheckable(true);
            chip.setChecked(cat.equals("All"));
            chip.setOnClickListener(v -> {
                selectedCategory = cat;
                applyFilter();
            });
            chipGroup.addView(chip);
        }
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            @Override public void afterTextChanged(Editable s){}
            @Override public void onTextChanged(CharSequence s,int st,int b,int c) {
                searchQuery = s.toString().trim().toLowerCase();
                applyFilter();
            }
        });
    }

    private void loadCommunities() {
        progressBar.setVisibility(View.VISIBLE);
        // Load all communities; filter client-side (small dataset for communities)
        FirebaseDatabase.getInstance().getReference("communities")
                .orderByChild("isPrivate").equalTo(false)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@Nullable DataSnapshot snapshot) {
                        List<CommunityEntity> list = new ArrayList<>();
                        if (snapshot != null) {
                            for (DataSnapshot cs : snapshot.getChildren()) {
                                CommunityEntity c = parseCommunity(cs);
                                if (c != null && c.name != null && !c.name.isEmpty()) list.add(c);
                            }
                        }
                        runOnUiThread(() -> {
                            allItems = list;
                            progressBar.setVisibility(View.GONE);
                            applyFilter();
                        });
                    }
                    @Override public void onCancelled(@Nullable DatabaseError error) {
                        runOnUiThread(() -> progressBar.setVisibility(View.GONE));
                    }
                });
    }

    private void applyFilter() {
        List<CommunityEntity> result = new ArrayList<>();
        for (CommunityEntity c : allItems) {
            boolean matchSearch = searchQuery.isEmpty()
                    || (c.name != null && c.name.toLowerCase().contains(searchQuery))
                    || (c.description != null && c.description.toLowerCase().contains(searchQuery));
            boolean matchCat = "All".equals(selectedCategory)
                    || selectedCategory.equals(c.category);
            if (matchSearch && matchCat) result.add(c);
        }
        adapter.setItems(result);
        emptyState.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
        rvCommunities.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Nullable
    private CommunityEntity parseCommunity(DataSnapshot s) {
        if (s == null || !s.exists()) return null;
        CommunityEntity c = new CommunityEntity();
        c.id          = s.getKey() != null ? s.getKey() : "";
        c.name        = strVal(s, "name");
        c.description = strVal(s, "description");
        c.iconUrl     = strVal(s, "iconUrl");
        c.bannerUrl   = strVal(s, "bannerUrl");
        c.category    = strVal(s, "category");
        c.isVerified  = Boolean.TRUE.equals(s.child("isVerified").getValue(Boolean.class));
        Long mc = s.child("memberCount").getValue(Long.class);
        c.memberCount = mc != null ? mc : 0L;
        Long pc = s.child("postCount").getValue(Long.class);
        c.postCount   = pc != null ? pc : 0L;
        Boolean priv  = s.child("isPrivate").getValue(Boolean.class);
        c.isPrivate   = priv != null && priv;
        return c;
    }

    private static String strVal(DataSnapshot s, String key) {
        String v = s.child(key).getValue(String.class); return v != null ? v : "";
    }

    // ─── Adapter ─────────────────────────────────────────────────────────────

    class DiscoverAdapter extends RecyclerView.Adapter<DiscoverAdapter.VH> {
        private List<CommunityEntity> items = new ArrayList<>();

        void setItems(List<CommunityEntity> list) {
            items = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_community_discover, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            CommunityEntity c = items.get(pos);

            if (c.iconUrl != null && !c.iconUrl.isEmpty())
                Glide.with(h.ivIcon.getContext()).load(c.iconUrl)
                        .circleCrop().placeholder(R.drawable.ic_group).into(h.ivIcon);
            else h.ivIcon.setImageResource(R.drawable.ic_group);

            h.tvName.setText(c.name != null ? c.name : "");
            h.tvDescription.setText(c.description != null ? c.description : "");
            h.tvMeta.setText(c.memberCount + " members • " + c.postCount + " posts");
            h.tvCategory.setVisibility(c.category != null && !c.category.isEmpty() ? View.VISIBLE : View.GONE);
            h.tvCategory.setText(c.category != null ? c.category : "");
            h.tvVerified.setVisibility(c.isVerified ? View.VISIBLE : View.GONE);
            h.tvPrivate.setVisibility(c.isPrivate ? View.VISIBLE : View.GONE);

            h.btnJoin.setText(c.isPrivate ? "Request" : "Join");
            h.btnJoin.setOnClickListener(v -> joinOrRequest(c));

            h.itemView.setOnClickListener(v -> openCommunity(c.id));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            TextView tvName, tvDescription, tvMeta, tvCategory, tvVerified, tvPrivate;
            com.google.android.material.button.MaterialButton btnJoin;
            VH(@NonNull View v) {
                super(v);
                ivIcon        = v.findViewById(R.id.iv_discover_icon);
                tvName        = v.findViewById(R.id.tv_discover_name);
                tvDescription = v.findViewById(R.id.tv_discover_description);
                tvMeta        = v.findViewById(R.id.tv_discover_meta);
                tvCategory    = v.findViewById(R.id.tv_discover_category);
                tvVerified    = v.findViewById(R.id.tv_discover_verified);
                tvPrivate     = v.findViewById(R.id.tv_discover_private);
                btnJoin       = v.findViewById(R.id.btn_discover_join);
            }
        }
    }

    private void joinOrRequest(CommunityEntity c) {
        if (currentUid == null) return;
        String uid    = currentUid;
        String uname  = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getDisplayName() : "";
        String uphoto = FirebaseAuth.getInstance().getCurrentUser() != null
                && FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl().toString() : null;

        if (c.isPrivate) {
            repo.sendJoinRequest(c.id, uid, uname, uphoto, (success, error) ->
                    runOnUiThread(() -> Toast.makeText(this,
                            success ? "Request sent!" : "Error: " + error, Toast.LENGTH_SHORT).show()));
        } else {
            repo.addMember(c.id, uid, uname, uphoto, CommunityRole.MEMBER,
                    (success, error) -> runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(this, "Joined " + c.name + "!", Toast.LENGTH_SHORT).show();
                            openCommunity(c.id);
                        } else {
                            Toast.makeText(this, "Error: " + error, Toast.LENGTH_SHORT).show();
                        }
                    }));
        }
    }

    private void openCommunity(String communityId) {
        Intent i = new Intent(this, CommunityActivity.class);
        i.putExtra(CommunityActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }
}
