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
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;

/**
 * v34: Community hub — 6 tabs: Feed, Announcements, Events, Groups, Members, Gallery.
 *
 * v34 additions:
 *  - Banner/cover image shown below toolbar via CollapsingToolbarLayout
 *  - Overflow menu: Rules, Bookmarks, Discover (new), Notifications, Manage, etc.
 *  - Community rules accessible from overflow → opens CommunityRulesActivity
 *  - Bookmarks accessible from overflow → opens CommunityBookmarksActivity
 *  - Discover link for unauthenticated / non-member view
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
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabCompose;
    private CommunityMemberAvatarStackView avatarStack;

    // v34: banner
    private android.widget.ImageView ivBanner;

    // Join gate
    private View groupJoinGate;
    private de.hdodenhof.circleimageview.CircleImageView ivGateIcon;
    private android.widget.TextView tvGateName, tvGateDescription, tvGateMemberCount, tvGateStatus;
    private com.google.android.material.button.MaterialButton btnGateJoin;
    private android.widget.ProgressBar progressGate;
    private boolean isMember = false;
    private boolean gateIsPrivate = false;
    private String pendingUname, pendingUphoto;

    // v171: authoritative-resolution overlay + guard
    private View groupResolvingAccess;
    private boolean initialResolved = false;

    private CommunityRepository repo;
    private CommunityEntity currentCommunity;

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
        ivBanner      = findViewById(R.id.iv_community_banner); // v34

        if (avatarStack != null) avatarStack.setOnClickListener(v -> viewPager.setCurrentItem(4));

        groupJoinGate     = findViewById(R.id.group_join_gate);
        ivGateIcon        = findViewById(R.id.iv_gate_icon);
        tvGateName        = findViewById(R.id.tv_gate_name);
        tvGateDescription = findViewById(R.id.tv_gate_description);
        tvGateMemberCount = findViewById(R.id.tv_gate_member_count);
        tvGateStatus      = findViewById(R.id.tv_gate_status);
        btnGateJoin       = findViewById(R.id.btn_gate_join);
        progressGate      = findViewById(R.id.progress_gate);

        groupResolvingAccess = findViewById(R.id.group_resolving_access);
    }

    private void setupToolbarNav() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Notifications
        menu.add(0, R.id.menu_notifications, 0, "Notifications")
                .setIcon(R.drawable.ic_notifications)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        // Search
        menu.add(0, R.id.menu_search, 1, "Search")
                .setIcon(R.drawable.ic_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        // Overflow items
        menu.add(0, R.id.menu_bookmarks,     10, "📌 Saved Posts");
        menu.add(0, R.id.menu_rules,         11, "📋 Rules");
        menu.add(0, R.id.menu_members,       12, "👥 Members");
        menu.add(0, R.id.menu_analytics,     13, "📊 Analytics");
        menu.add(0, R.id.menu_manage,        14, "⚙️ Manage");
        menu.add(0, R.id.menu_join_requests, 15, "📥 Join Requests");
        menu.add(0, R.id.menu_discover,      16, "🔍 Discover More");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) { finish(); return true; }

        if (id == R.id.menu_notifications) {
            startActivity(new Intent(this, CommunityNotificationsActivity.class)
                    .putExtra("communityId", communityId)); return true;
        }
        if (id == R.id.menu_search) {
            startActivity(new Intent(this, CommunitySearchActivity.class)
                    .putExtra("communityId", communityId)); return true;
        }
        if (id == R.id.menu_bookmarks) {
            startActivity(new Intent(this, CommunityBookmarksActivity.class)
                    .putExtra(CommunityBookmarksActivity.EXTRA_COMMUNITY_ID, communityId)); return true;
        }
        if (id == R.id.menu_rules) {
            boolean isAdmin = CommunityRole.isAdminOrOwner(myRole);
            startActivity(new Intent(this, CommunityRulesActivity.class)
                    .putExtra(CommunityRulesActivity.EXTRA_COMMUNITY_ID, communityId)
                    .putExtra(CommunityRulesActivity.EXTRA_IS_ADMIN, isAdmin)); return true;
        }
        if (id == R.id.menu_manage) {
            startActivity(new Intent(this, ManageCommunityActivity.class)
                    .putExtra(ManageCommunityActivity.EXTRA_COMMUNITY_ID, communityId)
                    .putExtra(ManageCommunityActivity.EXTRA_IS_OWNER,
                            CommunityRole.OWNER.equals(myRole))); return true;
        }
        if (id == R.id.menu_join_requests) {
            if (CommunityRole.isAdminOrOwner(myRole))
                startActivity(new Intent(this, CommunityJoinRequestsActivity.class)
                        .putExtra("communityId", communityId)); return true;
        }
        if (id == R.id.menu_analytics) {
            if (CommunityRole.isAdminOrOwner(myRole))
                startActivity(new Intent(this, CommunityAnalyticsActivity.class)
                        .putExtra("communityId", communityId)); return true;
        }
        if (id == R.id.menu_discover) {
            startActivity(new Intent(this, CommunityDiscoverActivity.class)); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ─── Access / join gate ───────────────────────────────────────────────────

    private void resolveAccess() {
        if (communityId == null) return;

        repo.observeCommunity(communityId).observe(this, community -> {
            if (community == null) return;
            currentCommunity = community;
            ownerUid = community.ownerUid;
            updateToolbarInfo(community);
            loadBanner(community);
        });

        // v171 fix: the previous approach decided member-vs-gate from Room's
        // local member-list cache, which starts empty until Firebase syncs
        // down — for a brand-new community (owner) or a community opened for
        // the first time (any viewer), that empty snapshot arrived first and
        // was misread as "not a member", flashing/closing to the join gate
        // even for confirmed members. Fix: ask Firebase directly, once, for
        // the authoritative answer before showing content OR the gate. Until
        // that answer comes back, group_resolving_access stays up covering
        // both states — this is the same "loading → resolved" pattern apps
        // like WhatsApp use for any permission/membership gate.
        repo.checkMembership(communityId, currentUid, confirmedMember -> runOnUiThread(() -> {
            initialResolved = true;
            isMember = confirmedMember;
            if (confirmedMember) {
                if (currentUid != null && currentUid.equals(ownerUid)) myRole = CommunityRole.OWNER;
                onJoinedConfirmed();
            } else {
                showJoinGate();
            }
            if (groupResolvingAccess != null) groupResolvingAccess.setVisibility(View.GONE);

            // Keep observing Room's live member list after the initial
            // authoritative decision — this keeps role/removal updates live
            // without letting a stale/empty first emission re-trigger the gate.
            repo.observeMembers(communityId).observe(this, members -> {
                if (!initialResolved) return;
                boolean found = false;
                if (members != null && currentUid != null) {
                    for (CommunityMemberEntity m : members) {
                        if (currentUid.equals(m.uid)) {
                            found  = true;
                            myRole = m.role != null ? m.role : CommunityRole.MEMBER;
                            break;
                        }
                    }
                }
                boolean wasMember = isMember;
                isMember = found;
                if (isMember && !wasMember) onJoinedConfirmed();
                else if (!isMember && wasMember) showJoinGate();
            });
        }));
    }
    }

    private void updateToolbarInfo(CommunityEntity c) {
        if (tvName != null) tvName.setText(c.name != null ? c.name : "");
        if (tvMemberCount != null)
            tvMemberCount.setText(c.memberCount + " members");
        if (ivIcon != null && c.iconUrl != null && !c.iconUrl.isEmpty())
            Glide.with(this).load(c.iconUrl).circleCrop()
                    .placeholder(R.drawable.ic_group).into(ivIcon);
    }

    private void loadBanner(CommunityEntity c) {
        if (ivBanner == null) return;
        if (c.bannerUrl != null && !c.bannerUrl.isEmpty()) {
            ivBanner.setVisibility(View.VISIBLE);
            Glide.with(this).load(c.bannerUrl).centerCrop()
                    .override(1200, 300).into(ivBanner);
        } else {
            ivBanner.setVisibility(View.GONE);
        }
    }

    private void showJoinGate() {
        if (groupJoinGate != null) groupJoinGate.setVisibility(View.VISIBLE);
        if (tabLayout   != null) tabLayout.setVisibility(View.GONE);
        if (viewPager   != null) viewPager.setVisibility(View.GONE);
        if (fabCompose  != null) fabCompose.setVisibility(View.GONE);

        if (currentCommunity != null) {
            if (ivGateIcon != null && currentCommunity.iconUrl != null)
                Glide.with(this).load(currentCommunity.iconUrl).circleCrop().into(ivGateIcon);
            if (tvGateName != null) tvGateName.setText(currentCommunity.name);
            if (tvGateDescription != null) tvGateDescription.setText(currentCommunity.description);
            if (tvGateMemberCount != null)
                tvGateMemberCount.setText(currentCommunity.memberCount + " members");
            gateIsPrivate = currentCommunity.isPrivate;
        }

        if (btnGateJoin != null) {
            btnGateJoin.setText(gateIsPrivate ? "Request to Join" : "Join Community");
            btnGateJoin.setOnClickListener(v -> onJoinTapped());
        }
    }

    private void onJoinTapped() {
        if (progressGate != null) progressGate.setVisibility(View.VISIBLE);
        if (btnGateJoin  != null) btnGateJoin.setEnabled(false);

        if (gateIsPrivate) {
            repo.sendJoinRequest(communityId, currentUid, pendingUname, pendingUphoto, null, (s, e) -> runOnUiThread(() -> {
                        if (progressGate != null) progressGate.setVisibility(View.GONE);
                        if (tvGateStatus != null) {
                            tvGateStatus.setVisibility(View.VISIBLE);
                            tvGateStatus.setText(s ? "Request sent — waiting for approval"
                                    : "Error: " + e);
                        }
                    }));
        } else {
            repo.addMember(communityId, currentUid, pendingUname, pendingUphoto,
                    CommunityRole.MEMBER, (s, e) -> runOnUiThread(() -> {
                        if (progressGate != null) progressGate.setVisibility(View.GONE);
                        if (!s && tvGateStatus != null) {
                            tvGateStatus.setVisibility(View.VISIBLE);
                            tvGateStatus.setText("Error: " + e);
                        }
                        if (btnGateJoin != null) btnGateJoin.setEnabled(true);
                    }));
        }
    }

    private void onJoinedConfirmed() {
        if (groupJoinGate != null) groupJoinGate.setVisibility(View.GONE);
        if (tabLayout     != null) tabLayout.setVisibility(View.VISIBLE);
        if (viewPager     != null) viewPager.setVisibility(View.VISIBLE);
        if (fabCompose    != null) {
            boolean canPost = !CommunityRole.MEMBER.equals(myRole)
                    || (currentCommunity != null && !currentCommunity.isPrivate);
            fabCompose.setVisibility(canPost ? View.VISIBLE : View.GONE);
        }

        if (viewPager.getAdapter() == null) setupTabsAndPager();
    }

    private void setupTabsAndPager() {
        String[] tabs = {"Feed","Announcements","Events","Groups","Members","Gallery"};
        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull @Override public Fragment createFragment(int pos) {
                switch (pos) {
                    case 0: return CommunityFeedFragment.newInstance(communityId);
                    case 1: return CommunityFeedFragment.newAnnouncementsInstance(communityId);
                    case 2: return CommunityEventsFragment.newInstance(communityId);
                    case 3: return CommunityGroupsFragment.newInstance(communityId);
                    case 4: return CommunityMembersFragment.newInstance(communityId);
                    case 5: return CommunityMediaGalleryFragment.newInstance(communityId);
                    default: return CommunityFeedFragment.newInstance(communityId);
                }
            }
            @Override public int getItemCount() { return tabs.length; }
        });
        new TabLayoutMediator(tabLayout, viewPager, (tab, pos) -> tab.setText(tabs[pos])).attach();

        if (fabCompose != null) fabCompose.setOnClickListener(v -> openComposer());
    }

    private void openComposer() {
        boolean canAnnounce = CommunityRole.isAdminOrOwner(myRole);
        startActivity(new Intent(this, CommunityPostComposerActivity.class)
                .putExtra(CommunityPostComposerActivity.EXTRA_COMMUNITY_ID, communityId)
                .putExtra(CommunityPostComposerActivity.EXTRA_CAN_ANNOUNCE, canAnnounce));
    }
}
