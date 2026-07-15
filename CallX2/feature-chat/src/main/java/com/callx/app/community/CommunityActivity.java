package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityEntity;
import com.callx.app.db.entity.CommunityMemberEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;

/**
 * v31: Community hub — 6 tabs: Feed, Announcements, Events, Groups, Members, Gallery.
 * Toolbar has notification bell (with unread badge), search, and overflow menu.
 *
 * New in v31:
 *  - Events tab (CommunityEventsFragment)
 *  - Gallery shortcut
 *  - Search button
 *  - Notification bell with badge
 *  - Join requests badge (admin/owner)
 */
public class CommunityActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId;
    private String currentUid;
    private String myRole = CommunityRole.MEMBER;
    private String ownerUid;
    private boolean isPrivate;

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

        if (communityId == null || communityId.isEmpty()) { finish(); return; }

        bindViews();
        setupToolbarNav();
        setupTabsAndPager();
        observeCommunity();
        observeMyRole();
        observeNotificationBadge();

        fabCompose.setOnClickListener(v -> openComposer(viewPager.getCurrentItem() == 1));
    }

    private void bindViews() {
        toolbar       = findViewById(R.id.toolbar);
        ivIcon        = findViewById(R.id.iv_community_icon);
        tvName        = findViewById(R.id.tv_community_name);
        tvMemberCount = findViewById(R.id.tv_member_count);
        tabLayout     = findViewById(R.id.tab_layout);
        viewPager     = findViewById(R.id.view_pager);
        fabCompose    = findViewById(R.id.fab_compose);
        avatarStack   = findViewById(R.id.view_member_avatar_stack);
        if (avatarStack != null) avatarStack.setOnClickListener(v -> viewPager.setCurrentItem(4));
    }

    private void setupToolbarNav() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_community, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_search_community) {
            openSearch();
            return true;
        } else if (id == R.id.action_notifications) {
            openNotifications();
            return true;
        } else if (id == R.id.action_manage_community) {
            if (CommunityRole.isAdminOrOwner(myRole)) openManage();
            return true;
        } else if (id == R.id.action_media_gallery) {
            openGallery();
            return true;
        } else if (id == R.id.action_analytics) {
            openAnalytics();
            return true;
        } else if (id == R.id.action_join_requests) {
            openJoinRequests();
            return true;
        } else if (id == R.id.action_moderation_log) {
            openModerationLog();
            return true;
        } else if (id == R.id.action_scheduled_posts) {
            openScheduledPosts();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupTabsAndPager() {
        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @Override
            @NonNull
            public Fragment createFragment(int pos) {
                switch (pos) {
                    case 0:  return CommunityFeedFragment.newInstance(communityId);
                    case 1:  return CommunityAnnouncementsFragment.newInstance(communityId);
                    case 2:  return CommunityEventsFragment.newInstance(communityId);
                    case 3:  return CommunityGroupsFragment.newInstance(communityId);
                    case 4:  return CommunityMembersFragment.newInstance(communityId);
                    default: return CommunityFeedFragment.newInstance(communityId);
                }
            }
            @Override
            public int getItemCount() { return 5; }
        });

        new TabLayoutMediator(tabLayout, viewPager, (tab, pos) -> {
            switch (pos) {
                case 0: tab.setText("Feed");          break;
                case 1: tab.setText("Announce");      break;
                case 2: tab.setText("Events");        break;
                case 3: tab.setText("Groups");        break;
                case 4: tab.setText("Members");       break;
            }
        }).attach();

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // Show FAB only on Feed (0) and Announcements (1)
                fabCompose.setVisibility(position <= 1 ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void observeCommunity() {
        repo.observeCommunity(communityId).observe(this, this::onCommunityLoaded);
    }

    private void onCommunityLoaded(CommunityEntity c) {
        if (c == null) return;
        ownerUid  = c.ownerUid;
        isPrivate = c.isPrivate;
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
            if (avatarStack != null) avatarStack.bind(photos, members.size());
        });
    }

    private void observeNotificationBadge() {
        if (currentUid == null) return;
        repo.observeUnreadNotificationCount(currentUid).observe(this, count -> {
            // Update notification bell badge
            if (toolbar != null && count != null && count > 0) {
                // Badge on notification icon
                invalidateOptionsMenu();
            }
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

    private void openSearch() {
        Intent i = new Intent(this, CommunitySearchActivity.class);
        i.putExtra(CommunitySearchActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }

    private void openNotifications() {
        Intent i = new Intent(this, CommunityNotificationsActivity.class);
        i.putExtra(CommunityNotificationsActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }

    private void openGallery() {
        Intent i = new Intent(this, CommunityMediaGalleryActivity.class);
        i.putExtra(CommunityMediaGalleryActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }

    private void openAnalytics() {
        if (!CommunityRole.OWNER.equals(myRole)) {
            android.widget.Toast.makeText(this, "Only the owner can view analytics",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, CommunityAnalyticsDashboardActivity.class);
        i.putExtra(CommunityAnalyticsDashboardActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }

    private void openJoinRequests() {
        Intent i = new Intent(this, CommunityJoinRequestsActivity.class);
        i.putExtra(CommunityJoinRequestsActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }

    private void openModerationLog() {
        Intent i = new Intent(this, CommunityModerationLogActivity.class);
        i.putExtra(CommunityModerationLogActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }

    private void openScheduledPosts() {
        Intent i = new Intent(this, CommunityScheduledPostsActivity.class);
        i.putExtra(CommunityScheduledPostsActivity.EXTRA_COMMUNITY_ID, communityId);
        startActivity(i);
    }

    private void openManage() {
        Intent i = new Intent(this, ManageCommunityActivity.class);
        i.putExtra(ManageCommunityActivity.EXTRA_COMMUNITY_ID, communityId);
        i.putExtra(ManageCommunityActivity.EXTRA_IS_OWNER, CommunityRole.OWNER.equals(myRole));
        startActivityForResult(i, 100);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 100 && resultCode == RESULT_FIRST_USER) finish();
    }
}
