package com.callx.app.community;

import android.os.Bundle;
import android.text.TextUtils;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.callx.app.chat.R;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * v31: Community search — Posts / Members / Groups tabs.
 * SearchView filters all tabs in real-time.
 */
public class CommunitySearchActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId;
    private SearchView searchView;
    private ViewPager2 viewPager;
    private TabLayout tabLayout;

    // Hold fragment refs to forward queries
    private CommunitySearchResultsFragment postsFragment;
    private CommunitySearchResultsFragment membersFragment;
    private CommunitySearchResultsFragment groupsFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_search);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        searchView = toolbar.findViewById(R.id.search_view);
        tabLayout  = findViewById(R.id.tab_layout);
        viewPager  = findViewById(R.id.view_pager);

        postsFragment   = CommunitySearchResultsFragment.newInstance(communityId, "posts");
        membersFragment = CommunitySearchResultsFragment.newInstance(communityId, "members");
        groupsFragment  = CommunitySearchResultsFragment.newInstance(communityId, "groups");

        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 1:  return membersFragment;
                    case 2:  return groupsFragment;
                    default: return postsFragment;
                }
            }
            @Override
            public int getItemCount() { return 3; }
        });

        new TabLayoutMediator(tabLayout, viewPager, (tab, pos) -> {
            switch (pos) {
                case 0: tab.setText("Posts");   break;
                case 1: tab.setText("Members"); break;
                case 2: tab.setText("Groups");  break;
            }
        }).attach();

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                forwardQuery(query);
                return true;
            }
            @Override
            public boolean onQueryTextChange(String newText) {
                forwardQuery(newText);
                return true;
            }
        });
    }

    private void forwardQuery(String query) {
        String q = query != null ? query.trim() : "";
        if (postsFragment   != null) postsFragment.onQueryChanged(q);
        if (membersFragment != null) membersFragment.onQueryChanged(q);
        if (groupsFragment  != null) groupsFragment.onQueryChanged(q);
    }
}
