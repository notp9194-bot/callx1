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

    // v33: Join gate — feed/members/groups/events stay hidden until this
    // resolves the current user as a confirmed member.
    private View groupJoinGate;
    private de.hdodenhof.circleimageview.CircleImageView ivGateIcon;
    private android.widget.TextView tvGateName, tvGateDescription, tvGateMemberCount, tvGateStatus;
    private com.google.android.material.button.MaterialButton btnGateJoin;
    private android.widget.ProgressBar progressGate;
    private boolean isMember = false;
    private boolean gateIsPrivate = false;
    private String pendingUname, pendingUphoto;

    private CommunityRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        pendingUname = FirebaseAuth.getInstance().getCurrentUser() != null
                && FirebaseAuth.getInstance().getCurrentUser().getDisplayName() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getDisplayName() : "Member";
        pendingUphoto = FirebaseAuth.getInstance().getCurrentUser() != null
                && FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl().toString() : null;
        repo = CommunityRepository.getInstance(this);

        if (communityId == null || communityId.isEmpty() || currentUid == null) { finish(); return; }

        bindViews();
        setupToolbarNav();
        resolveAccess();
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

        groupJoinGate     = findViewById(R.id.group_join_gate);
        ivGateIcon        = findViewById(R.id.iv_gate_icon);
        tvGateName        = findViewById(R.id.tv_gate_name);
        tvGateDescription = findViewById(R.id.tv_gate_description);
        tvGateMemberCount = findViewById(R.id.tv_gate_member_count);
        tvGateStatus      = findViewById(R.id.tv_gate_status);
        btnGateJoin       = findViewById(R.id.btn_gate_join);
        progressGate      = findViewById(R.id.progress_gate);
    }

    // ─────────────────────────────────────────────────────────────
    // v33: JOIN GATE
    // ─────────────────────────────────────────────────────────────

    /** Step 1 — before anything else loads, find out if the caller is a member. */
    private void resolveAccess() {
        tabLayout.setVisibility(View.GONE);
        viewPager.setVisibility(View.GONE);
        fabCompose.setVisibility(View.GONE);
        groupJoinGate.setVisibility(View.VISIBLE);
        setGateLoading(true);

        repo.checkMembership(communityId, currentUid, member -> runOnUiThread(() -> {
            isMember = member;
            if (member) {
                enterAsMember();
            } else {
                loadGatePreview();
            }
        }));
    }

    /** Not a member yet — fetch preview info (or fall back to a private-locked card) and show Join/Request UI. */
    private void loadGatePreview() {
        repo.fetchCommunityPreview(communityId, new CommunityRepository.PreviewCallback() {
            @Override public void onLoaded(CommunityEntity c) {
                runOnUiThread(() -> {
                    gateIsPrivate = c.isPrivate;
                    setGateLoading(false);
                    tvGateName.setText(c.name != null && !c.name.isEmpty() ? c.name : "Community");
                    tvGateDescription.setText(c.description != null ? c.description : "");
                    tvGateDescription.setVisibility(c.description != null && !c.description.isEmpty() ? View.VISIBLE : View.GONE);
                    tvGateMemberCount.setText(c.memberCount + (c.memberCount == 1 ? " member" : " members"));
                    if (c.iconUrl != null && !c.iconUrl.isEmpty()) {
                        Glide.with(CommunityActivity.this).load(c.iconUrl).circleCrop()
                                .placeholder(R.drawable.ic_group).into(ivGateIcon);
                    }
                    if (c.isPrivate) checkPendingThenRenderButton(); else renderJoinButton();
                });
            }
            @Override public void onPrivateLocked() {
                runOnUiThread(() -> {
                    gateIsPrivate = true;
                    setGateLoading(false);
                    tvGateName.setText("Private Community");
                    tvGateDescription.setVisibility(View.GONE);
                    tvGateMemberCount.setText("Only members can see who's inside");
                    checkPendingThenRenderButton();
                });
            }
        });
    }

    private void checkPendingThenRenderButton() {
        repo.hasPendingJoinRequest(communityId, currentUid, pending -> runOnUiThread(() -> {
            if (Boolean.TRUE.equals(pending)) {
                renderPendingState();
            } else {
                renderRequestButton();
            }
        }));
    }

    private void setGateLoading(boolean loading) {
        progressGate.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnGateJoin.setVisibility(loading ? View.GONE : View.VISIBLE);
        tvGateStatus.setVisibility(View.GONE);
    }

    private void renderJoinButton() {
        btnGateJoin.setText("Join Community");
        btnGateJoin.setEnabled(true);
        btnGateJoin.setVisibility(View.VISIBLE);
        btnGateJoin.setOnClickListener(v -> doJoin());
    }

    private void renderRequestButton() {
        btnGateJoin.setText("Request to Join");
        btnGateJoin.setEnabled(true);
        btnGateJoin.setVisibility(View.VISIBLE);
        btnGateJoin.setOnClickListener(v -> doRequestJoin());
    }

    private void renderPendingState() {
        btnGateJoin.setText("Request Pending");
        btnGateJoin.setEnabled(false);
        btnGateJoin.setVisibility(View.VISIBLE);
        tvGateStatus.setText("Your join request is waiting for admin approval.");
        tvGateStatus.setVisibility(View.VISIBLE);
    }

    private void doJoin() {
        btnGateJoin.setEnabled(false);
        setGateLoading(true);
        repo.addMember(communityId, currentUid, pendingUname, pendingUphoto, CommunityRole.MEMBER,
                (success, error) -> runOnUiThread(() -> {
                    if (success) {
                        isMember = true;
                        enterAsMember();
                    } else {
                        setGateLoading(false);
                        renderJoinButton();
                        android.widget.Toast.makeText(this, "Couldn't join: " + error, android.widget.Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private void doRequestJoin() {
        btnGateJoin.setEnabled(false);
        setGateLoading(true);
        repo.sendJoinRequest(communityId, currentUid, pendingUname, pendingUphoto, null,
                (success, error) -> runOnUiThread(() -> {
                    setGateLoading(false);
                    if (success) {
                        renderPendingState();
                        android.widget.Toast.makeText(this, "Join request sent", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        renderRequestButton();
                        android.widget.Toast.makeText(this, "Couldn't send request: " + error, android.widget.Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    /** Confirmed member — reveal the real tabs (Feed/Announce/Events/Groups/Members) and start the normal observers. */
    private void enterAsMember() {
        groupJoinGate.setVisibility(View.GONE);
        tabLayout.setVisibility(View.VISIBLE);
        viewPager.setVisibility(View.VISIBLE);

        setupTabsAndPager();
        observeCommunity();
        observeMyRole();
        observeNotificationBadge();

        fabCompose.setOnClickListener(v -> openComposer(viewPager.getCurrentItem() == 1));
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
        if (!isMember) return true; // v33: nothing in the overflow menu is reachable before joining
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
