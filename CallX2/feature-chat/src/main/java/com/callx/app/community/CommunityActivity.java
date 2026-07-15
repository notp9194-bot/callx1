package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.community.CommunityRole;
import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;

/**
 * CommunityActivity — the Community "home": toolbar (icon/name/member count)
 * + 4-tab ViewPager2 (Feed, Announcements, Groups, Members) + a context FAB
 * that only opens the post composer on the Feed/Announcements tabs.
 *
 * Launched from ChatProfileCardBinder's "View Community" button (feature-chat
 * internal, direct class reference — no reflection needed since both live
 * in this module) with EXTRA_COMMUNITY_ID.
 */
public class CommunityActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId;
    private String currentUid;
    private String myRole = CommunityRole.MEMBER;
    private String ownerUid;

    private androidx.appcompat.widget.Toolbar toolbar;
    private de.hdodenhof.circleimageview.CircleImageView ivIcon;
    private android.widget.TextView tvName, tvMemberCount;
    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private FloatingActionButton fabCompose;
    private CommunityMemberAvatarStackView avatarStack;

    private CommunityRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(this);

        if (communityId == null || communityId.isEmpty()) {
            finish();
            return;
        }

        bindViews();
        setupToolbarNav();
        setupTabsAndPager();
        observeCommunity();
        observeMyRole();

        fabCompose.setOnClickListener(v -> openComposer(viewPager.getCurrentItem() == 1));
    }

    private void bindViews() {
        toolbar        = findViewById(R.id.toolbar);
        ivIcon         = findViewById(R.id.iv_community_icon);
        tvName         = findViewById(R.id.tv_community_name);
        tvMemberCount  = findViewById(R.id.tv_member_count);
        tabLayout      = findViewById(R.id.tab_layout);
        viewPager      = findViewById(R.id.view_pager);
        fabCompose     = findViewById(R.id.fab_compose);
        avatarStack    = findViewById(R.id.view_member_avatar_stack);
        avatarStack.setOnClickListener(v -> viewPager.setCurrentItem(3)); // Members tab
    }

    private void setupToolbarNav() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        View overflow = findViewById(R.id.btn_overflow);
        overflow.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, overflow);
            popup.getMenu().add("Manage Community");
            popup.setOnMenuItemClickListener(item -> {
                if (!CommunityRole.isAdminOrOwner(myRole)) {
                    android.widget.Toast.makeText(this,
                            "Only admins can manage this community", android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                Intent i = new Intent(this, ManageCommunityActivity.class);
                i.putExtra(ManageCommunityActivity.EXTRA_COMMUNITY_ID, communityId);
                i.putExtra(ManageCommunityActivity.EXTRA_IS_OWNER, currentUid != null && currentUid.equals(ownerUid));
                startActivityForResult(i, 100);
                return true;
            });
            popup.show();
        });
    }

    private void setupTabsAndPager() {
        List<String> titles = new ArrayList<>();
        titles.add("Feed");
        titles.add("Announcements");
        titles.add("Groups");
        titles.add("Members");

        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 1: return CommunityAnnouncementsFragment.newInstance(communityId);
                    case 2: return CommunityGroupsFragment.newInstance(communityId);
                    case 3: return CommunityMembersFragment.newInstance(communityId);
                    default: return CommunityFeedFragment.newInstance(communityId);
                }
            }
            @Override public int getItemCount() { return 4; }
        });

        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(titles.get(position))).attach();

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                fabCompose.setVisibility(position == 0 || position == 1 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void observeCommunity() {
        repo.observeCommunity(communityId).observe(this, this::onCommunityLoaded);
        repo.fetchCommunity(communityId, c -> { /* refreshes Room; observeCommunity picks it up */ });
    }

    private void onCommunityLoaded(CommunityEntity c) {
        if (c == null) return;
        ownerUid = c.ownerUid;
        tvName.setText(c.name != null ? c.name : "Community");
        tvMemberCount.setText(c.memberCount + (c.memberCount == 1 ? " member" : " members"));
        if (c.iconUrl != null && !c.iconUrl.isEmpty()) {
            Glide.with(this).load(c.iconUrl).circleCrop()
                    .placeholder(R.drawable.ic_group).into(ivIcon);
        }
    }

    private void observeMyRole() {
        repo.observeMembers(communityId).observe(this, members -> {
            if (members == null) return;
            List<String> photos = new ArrayList<>();
            for (CommunityMemberEntity m : members) {
                if (m.photoUrl != null && !m.photoUrl.isEmpty()) photos.add(m.photoUrl);
                if (currentUid != null && currentUid.equals(m.uid)) {
                    myRole = m.role != null ? m.role : CommunityRole.MEMBER;
                }
            }
            avatarStack.bind(photos, members.size());
        });
    }

    private void openComposer(boolean announcementDefault) {
        if (announcementDefault && !CommunityRole.isAdminOrOwner(myRole)) {
            android.widget.Toast.makeText(this,
                    "Only admins can post announcements", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, CommunityPostComposerActivity.class);
        i.putExtra(CommunityPostComposerActivity.EXTRA_COMMUNITY_ID, communityId);
        i.putExtra(CommunityPostComposerActivity.EXTRA_IS_ANNOUNCEMENT, announcementDefault);
        i.putExtra(CommunityPostComposerActivity.EXTRA_CAN_ANNOUNCE, CommunityRole.isAdminOrOwner(myRole));
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // ManageCommunityActivity contract: RESULT_FIRST_USER means the owner disabled
        // the community — this Activity (and the chat header's "View Community"
        // button, next time it checks) should no longer be reachable.
        if (requestCode == 100 && resultCode == RESULT_FIRST_USER) {
            finish();
        }
    }
}
