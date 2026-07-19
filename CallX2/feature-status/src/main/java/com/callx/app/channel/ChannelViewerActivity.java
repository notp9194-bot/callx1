package com.callx.app.channel;

import com.callx.app.channel.ChannelReplyActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.db.entity.ChannelPostEntity;
import com.callx.app.models.ChannelPost;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ChannelViewerActivity — WhatsApp-level complete channel viewer (v2).
 *
 * ALL features:
 *   ✓ Post feed: text, image, video, link, poll, audio, document, deleted
 *   ✓ Pinned post banner at top (dismissible, admin can unpin)
 *   ✓ Follow / unfollow (with confirmation; also accessible via overflow menu)
 *   ✓ Mute / unmute with duration picker
 *   ✓ Notification settings shortcut
 *   ✓ Edit channel (owner/admin only → ChannelEditActivity)
 *   ✓ Admin panel shortcut → ChannelAdminActivity
 *   ✓ Analytics shortcut → ChannelAnalyticsActivity
 *   ✓ Channel info bottom sheet (tap header)
 *   ✓ Share channel link via Android share sheet
 *   ✓ Report channel (follower action)
 *   ✓ Search within channel posts (toolbar SearchView)
 *   ✓ New post FAB (owner/admin only) → ChannelPostComposerActivity
 *   ✓ Long-press post menu: react, forward, copy, pin/unpin (admin), edit (admin), delete (admin), report
 *   ✓ Reactions summary → tap → ChannelReactionsDetailActivity
 *   ✓ React (emoji picker sheet) / remove reaction
 *   ✓ Forward post → ForwardPostActivity (with channelId + postId for forward-count tracking)
 *   ✓ Poll voting
 *   ✓ View count increment on first-seen (debounced per post per session)
 *   ✓ Pagination (scroll to bottom loads older posts)
 *   ✓ Mark channel read on leave
 *   ✓ Firebase sync started on create, stopped on destroy
 *   ✓ Observed LiveData: channel entity, posts, pinned post, search results, toasts
 */
public class ChannelViewerActivity extends AppCompatActivity
        implements ChannelPostAdapter.PostActionListener {

    public static final String EXTRA_CHANNEL_ID        = "channelId";
    public static final String EXTRA_CHANNEL_NAME      = "channelName";
    public static final String EXTRA_CHANNEL_ICON      = "channelIcon";
    public static final String EXTRA_CHANNEL_VERIFIED  = "channelVerified";
    public static final String EXTRA_CHANNEL_FOLLOWERS = "channelFollowers";
    public static final String EXTRA_OWNER_UID         = "ownerUid";

    private static final int RC_NEW_POST    = 1001;
    private static final int RC_EDIT_CHAN   = 1002;

    private String        channelId;
    private String        channelName;
    private ChannelEntity channelEntity;
    private boolean       isFollowing    = false;
    private boolean       isMuted        = false;
    private boolean       isAdminOrOwner = false;
    private boolean       isOwner        = false;
    private long          latestPostTimestamp = 0;
    private boolean       isSearchMode   = false;

    private ChannelViewModel   viewModel;
    private ChannelPostAdapter postAdapter;
    private RecyclerView       rvPosts;
    private View               emptyState;
    private FloatingActionButton fabNewPost;
    private MaterialButton     btnFollowToggle;
    private TextView           tvFollowers;

    // Pinned post banner
    private MaterialCardView   cardPinned;
    private TextView           tvPinnedText, tvPinnedType;
    private View               btnUnpin;
    private String             currentPinnedPostId;

    // Menu items
    private MenuItem menuItemMute, menuItemSearch, menuItemInfo,
                     menuItemAdmin, menuItemAnalytics, menuItemEdit,
                     menuItemNotifSettings, menuItemUnfollow, menuItemReport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_viewer);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        String iconUrl   = getIntent().getStringExtra(EXTRA_CHANNEL_ICON);
        boolean verified = getIntent().getBooleanExtra(EXTRA_CHANNEL_VERIFIED, false);
        long followers   = getIntent().getLongExtra(EXTRA_CHANNEL_FOLLOWERS, 0L);

        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        // ── Toolbar ───────────────────────────────────────────────────────
        Toolbar toolbar = findViewById(R.id.toolbar_channel);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // ── Header views ─────────────────────────────────────────────────
        TextView tvName     = findViewById(R.id.tv_channel_viewer_name);
        TextView tvVerified = findViewById(R.id.tv_channel_viewer_verified);
        tvFollowers         = findViewById(R.id.tv_channel_viewer_followers);
        CircleImageView ivIcon = findViewById(R.id.iv_channel_viewer_icon);
        btnFollowToggle     = findViewById(R.id.btn_follow_toggle);

        if (tvName     != null) tvName.setText(channelName != null ? channelName : "");
        if (tvVerified != null) tvVerified.setVisibility(verified ? View.VISIBLE : View.GONE);
        if (tvFollowers!= null) tvFollowers.setText(formatFollowers(followers));

        if (ivIcon != null) {
            if (iconUrl != null && !iconUrl.isEmpty()) {
                Glide.with(this).load(iconUrl)
                    .placeholder(R.drawable.bg_channel_avatar_default)
                    .circleCrop().override(128, 128).into(ivIcon);
            } else {
                ivIcon.setImageResource(R.drawable.bg_channel_avatar_default);
            }
        }

        // Tap header → open channel info bottom sheet
        View headerArea = findViewById(R.id.layout_channel_header_info);
        if (headerArea != null) headerArea.setOnClickListener(v -> openChannelInfo());
        if (ivIcon     != null) ivIcon.setOnClickListener(v -> openChannelInfo());

        // ── Pinned post banner ────────────────────────────────────────────
        cardPinned    = findViewById(R.id.card_pinned_post);
        tvPinnedText  = findViewById(R.id.tv_pinned_post_text);
        tvPinnedType  = findViewById(R.id.tv_pinned_post_type);
        btnUnpin      = findViewById(R.id.btn_unpin_post);

        if (cardPinned != null) {
            cardPinned.setVisibility(View.GONE);
            // Tap pinned banner → scroll to pinned post in feed
            cardPinned.setOnClickListener(v -> scrollToPinnedPost());
            if (btnUnpin != null) {
                btnUnpin.setOnClickListener(v -> {
                    if (currentPinnedPostId != null && isAdminOrOwner) {
                        new AlertDialog.Builder(this)
                            .setTitle("Unpin post?")
                            .setPositiveButton("Unpin", (d, w) ->
                                viewModel.unpinPost(channelId, currentPinnedPostId))
                            .setNegativeButton("Cancel", null).show();
                    }
                });
            }
        }

        // ── Posts RecyclerView ───────────────────────────────────────────
        rvPosts    = findViewById(R.id.rv_channel_posts);
        emptyState = findViewById(R.id.layout_channel_empty);
        fabNewPost = findViewById(R.id.fab_new_post);

        LinearLayoutManager llm = new LinearLayoutManager(this);
        rvPosts.setLayoutManager(llm);

        postAdapter = new ChannelPostAdapter(this, viewModel.getMyUid(), this);
        rvPosts.setAdapter(postAdapter);

        // Pagination: load older posts when scrolled near the bottom of the feed
        rvPosts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !rv.canScrollVertically(1) && postAdapter.getItemCount() > 0) {
                    long oldest = postAdapter.getOldestTimestamp();
                    if (oldest > 0) viewModel.loadMorePosts(channelId, oldest);
                }
            }
        });

        // ── Follow toggle ────────────────────────────────────────────────
        if (btnFollowToggle != null) btnFollowToggle.setOnClickListener(v -> toggleFollow());

        // ── New post FAB ─────────────────────────────────────────────────
        if (fabNewPost != null) {
            fabNewPost.setVisibility(View.GONE);
            fabNewPost.setOnClickListener(v -> openPostComposer());
        }

        // ── Observe: channel entity ────────────────────────────────────
        viewModel.getChannel(channelId).observe(this, ch -> {
            if (ch == null) return;
            channelEntity  = ch;
            isFollowing    = ch.isFollowed;
            isMuted        = ch.isMuted;
            isAdminOrOwner = viewModel.isAdminOrOwner(ch);
            isOwner        = viewModel.isOwner(ch);

            if (tvFollowers != null) tvFollowers.setText(formatFollowers(ch.followers));
            if (tvName      != null && ch.name != null && !ch.name.isEmpty()) tvName.setText(ch.name);
            if (tvVerified  != null) tvVerified.setVisibility(ch.verified ? View.VISIBLE : View.GONE);

            updateFollowButton();
            if (fabNewPost != null) fabNewPost.setVisibility(isAdminOrOwner ? View.VISIBLE : View.GONE);

            // Unpin button only visible to admin
            if (btnUnpin != null) btnUnpin.setVisibility(isAdminOrOwner ? View.VISIBLE : View.GONE);

            invalidateOptionsMenu(); // re-draw menu with correct visibility

            if (ch.iconUrl != null && !ch.iconUrl.isEmpty() && ivIcon != null)
                Glide.with(this).load(ch.iconUrl)
                    .placeholder(R.drawable.bg_channel_avatar_default)
                    .circleCrop().override(128, 128).into(ivIcon);
        });

        // ── Observe: pinned post ──────────────────────────────────────────
        viewModel.getPinnedPost(channelId).observe(this, pinned -> {
            if (pinned != null && !pinned.isDeleted && cardPinned != null) {
                currentPinnedPostId = pinned.id;
                cardPinned.setVisibility(View.VISIBLE);
                if (tvPinnedType != null) tvPinnedType.setText(
                    pinned.type != null ? capitalize(pinned.type) : "Post");
                if (tvPinnedText != null) {
                    String preview = pinned.text != null && !pinned.text.isEmpty()
                        ? (pinned.text.length() > 80 ? pinned.text.substring(0, 80) + "…" : pinned.text)
                        : "[" + (pinned.type != null ? pinned.type : "post") + "]";
                    tvPinnedText.setText(preview);
                }
            } else {
                currentPinnedPostId = null;
                if (cardPinned != null) cardPinned.setVisibility(View.GONE);
            }
        });

        // ── Observe: posts ────────────────────────────────────────────────
        viewModel.getChannelPosts(channelId).observe(this, posts -> {
            if (isSearchMode) return; // don't overwrite search results
            if (posts == null || posts.isEmpty()) {
                if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
                if (rvPosts   != null) rvPosts.setVisibility(View.GONE);
                return;
            }
            if (emptyState != null) emptyState.setVisibility(View.GONE);
            if (rvPosts   != null) rvPosts.setVisibility(View.VISIBLE);

            List<ChannelPost> converted = new ArrayList<>();
            for (ChannelPostEntity e : posts) {
                if (!e.isDeleted && e.scheduledAt == 0) {
                    latestPostTimestamp = Math.max(latestPostTimestamp, e.timestamp);
                }
                converted.add(entityToModel(e));
            }
            postAdapter.setOwnerUid(channelEntity != null ? channelEntity.ownerUid : "");
            postAdapter.setPosts(converted);

            // Increment view count for the newest post (first visible)
            if (!converted.isEmpty() && converted.get(0).id != null) {
                viewModel.incrementPostView(channelId, converted.get(0).id);
            }
        });

        // ── Observe: search results ────────────────────────────────────
        viewModel.searchResults.observe(this, results -> {
            if (!isSearchMode || results == null) return;
            List<ChannelPost> converted = new ArrayList<>();
            for (ChannelPostEntity e : results) converted.add(entityToModel(e));
            postAdapter.setPosts(converted);
            if (emptyState != null) emptyState.setVisibility(converted.isEmpty() ? View.VISIBLE : View.GONE);
            if (rvPosts   != null) rvPosts.setVisibility(converted.isEmpty() ? View.GONE : View.VISIBLE);
            TextView tvEmptySub = findViewById(R.id.tv_channel_empty_sub);
            if (tvEmptySub != null) tvEmptySub.setText("No posts match your search");
        });

        // ── Observe: toasts ────────────────────────────────────────────
        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        // ── Start Firebase sync ─────────────────────────────────────────
        viewModel.startSyncingPosts(channelId);
    }

    // ── Options Menu ──────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_channel_viewer, menu);
        menuItemMute         = menu.findItem(R.id.action_mute_channel);
        menuItemSearch       = menu.findItem(R.id.action_search_posts);
        menuItemInfo         = menu.findItem(R.id.action_channel_info);
        menuItemAdmin        = menu.findItem(R.id.action_admin_panel);
        menuItemAnalytics    = menu.findItem(R.id.action_analytics);
        menuItemEdit         = menu.findItem(R.id.action_edit_channel);
        menuItemNotifSettings= menu.findItem(R.id.action_notification_settings);
        menuItemUnfollow     = menu.findItem(R.id.action_unfollow_channel);
        menuItemReport       = menu.findItem(R.id.action_report_channel);

        refreshMenuState(menu);

        // ── Search ────────────────────────────────────────────────────
        if (menuItemSearch != null) {
            SearchView sv = (SearchView) menuItemSearch.getActionView();
            if (sv != null) {
                sv.setQueryHint("Search posts…");
                sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override public boolean onQueryTextSubmit(String q) {
                        if (!q.isEmpty()) { isSearchMode = true; viewModel.searchPosts(channelId, q); }
                        return true;
                    }
                    @Override public boolean onQueryTextChange(String q) {
                        if (q.isEmpty()) {
                            isSearchMode = false;
                            // Re-observe the full post list
                            viewModel.getChannelPosts(channelId);
                        }
                        return false;
                    }
                });
                menuItemSearch.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override public boolean onMenuItemActionExpand(MenuItem item) { return true; }
                    @Override public boolean onMenuItemActionCollapse(MenuItem item) {
                        isSearchMode = false;
                        return true;
                    }
                });
            }
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        refreshMenuState(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    private void refreshMenuState(Menu menu) {
        if (menuItemMute  != null) menuItemMute.setTitle(isMuted ? "Unmute notifications" : "Mute notifications");
        if (menuItemAdmin != null) menuItemAdmin.setVisible(isAdminOrOwner);
        if (menuItemAnalytics != null) menuItemAnalytics.setVisible(isAdminOrOwner);
        if (menuItemEdit  != null) menuItemEdit.setVisible(isAdminOrOwner);
        if (menuItemUnfollow != null) menuItemUnfollow.setVisible(isFollowing && !isAdminOrOwner);
        if (menuItemReport   != null) menuItemReport.setVisible(!isAdminOrOwner);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if      (id == R.id.action_mute_channel)        handleMuteToggle();
        else if (id == R.id.action_channel_info)         openChannelInfo();
        else if (id == R.id.action_share_channel)        shareChannelLink();
        else if (id == R.id.action_admin_panel)          openAdminPanel();
        else if (id == R.id.action_analytics)            openAnalytics();
        else if (id == R.id.action_edit_channel)         openEditChannel();
        else if (id == R.id.action_notification_settings) openNotificationSettings();
        else if (id == R.id.action_unfollow_channel)     confirmUnfollow();
        else if (id == R.id.action_report_channel)       showReportChannelDialog();
        return super.onOptionsItemSelected(item);
    }

    // ── PostActionListener implementation ────────────────────────────────

    @Override
    public void onReact(ChannelPost post) { showReactionPicker(post); }

    @Override
    public void onForward(ChannelPost post) { forwardPost(post); }

    @Override
    public void onCopy(ChannelPost post) {
        if (post.text == null || post.text.isEmpty()) return;
        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("post", post.text));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onEdit(ChannelPost post) {
        Intent i = new Intent(this, ChannelPostComposerActivity.class);
        i.putExtra(ChannelPostComposerActivity.EXTRA_CHANNEL_ID,    channelId);
        i.putExtra(ChannelPostComposerActivity.EXTRA_CHANNEL_NAME,  channelName);
        i.putExtra(ChannelPostComposerActivity.EXTRA_EDIT_POST_ID,  post.id);
        i.putExtra(ChannelPostComposerActivity.EXTRA_EDIT_POST_TEXT, post.text);
        startActivity(i);
    }

    @Override
    public void onDelete(ChannelPost post) {
        new AlertDialog.Builder(this)
            .setTitle("Delete post?")
            .setMessage("This post will be permanently deleted for all followers.")
            .setPositiveButton("Delete", (d, w) -> viewModel.deletePost(post))
            .setNegativeButton("Cancel", null).show();
    }

    @Override
    public void onReport(ChannelPost post) {
        String[] reasons = {"Spam", "Misleading info", "Hate speech", "Harmful content", "Other"};
        new AlertDialog.Builder(this)
            .setTitle("Report post")
            .setItems(reasons, (d, which) ->
                viewModel.reportPost(channelId, post.id, reasons[which]))
            .show();
    }

    @Override
    public void onVotePoll(ChannelPost post, int optionIndex) {
        viewModel.voteOnPoll(post, optionIndex);
    }

    @Override
    public void onViewMedia(ChannelPost post) {
        Intent i = new Intent(this, ChannelMediaViewerActivity.class);
        i.putExtra(ChannelMediaViewerActivity.EXTRA_MEDIA_URL,  post.mediaUrl);
        i.putExtra(ChannelMediaViewerActivity.EXTRA_MEDIA_TYPE, post.type);
        i.putExtra(ChannelMediaViewerActivity.EXTRA_POST_TEXT,  post.text);
        startActivity(i);
    }

    @Override
    public void onPinPost(ChannelPost post) {
        if (!isAdminOrOwner) return;
        if (post.isPinned) {
            new AlertDialog.Builder(this)
                .setTitle("Unpin post?")
                .setPositiveButton("Unpin", (d, w) ->
                    viewModel.unpinPost(channelId, post.id))
                .setNegativeButton("Cancel", null).show();
        } else {
            new AlertDialog.Builder(this)
                .setTitle("Pin this post?")
                .setMessage("This post will be pinned at the top of the channel for all followers.")
                .setPositiveButton("Pin", (d, w) ->
                    viewModel.pinPost(channelId, post.id))
                .setNegativeButton("Cancel", null).show();
        }
    }

    @Override
    public void onReactionsDetail(ChannelPost post) {
        if (post.reactions == null || post.reactions.isEmpty()) return;
        Intent i = new Intent(this, ChannelReactionsDetailActivity.class);
        i.putExtra(ChannelReactionsDetailActivity.EXTRA_POST_ID,        post.id);
        i.putExtra(ChannelReactionsDetailActivity.EXTRA_REACTIONS_JSON, reactionsToJson(post.reactions));
        startActivity(i);
    }

    @Override
    public void onReply(ChannelPost post) {
        Intent i = new Intent(this, ChannelReplyActivity.class);
        i.putExtra("channelId", channelId);
        i.putExtra("postId", post.id);
        startActivity(i);
    }

    public void onViewCount(ChannelPost post) {
        viewModel.incrementPostView(channelId, post.id);
    }

    // ── Navigation helpers ────────────────────────────────────────────────

    private void openPostComposer() {
        Intent i = new Intent(this, ChannelPostComposerActivity.class);
        i.putExtra(ChannelPostComposerActivity.EXTRA_CHANNEL_ID,   channelId);
        i.putExtra(ChannelPostComposerActivity.EXTRA_CHANNEL_NAME, channelName);
        startActivityForResult(i, RC_NEW_POST);
    }

    private void openChannelInfo() {
        if (channelEntity == null) return;
        ChannelViewerInfoBottomSheet sheet =
            ChannelViewerInfoBottomSheet.newInstance(channelEntity);
        sheet.show(getSupportFragmentManager(), ChannelViewerInfoBottomSheet.TAG);
    }

    private void openAdminPanel() {
        Intent i = new Intent(this, ChannelAdminActivity.class);
        i.putExtra(ChannelAdminActivity.EXTRA_CHANNEL_ID,   channelId);
        i.putExtra(ChannelAdminActivity.EXTRA_CHANNEL_NAME, channelName);
        i.putExtra(ChannelAdminActivity.EXTRA_OWNER_UID,
            channelEntity != null ? channelEntity.ownerUid : "");
        startActivity(i);
    }

    private void openAnalytics() {
        Intent i = new Intent(this, ChannelAnalyticsActivity.class);
        i.putExtra(ChannelAnalyticsActivity.EXTRA_CHANNEL_ID,   channelId);
        i.putExtra(ChannelAnalyticsActivity.EXTRA_CHANNEL_NAME, channelName);
        startActivity(i);
    }

    private void openEditChannel() {
        if (channelEntity == null) return;
        Intent i = new Intent(this, ChannelEditActivity.class);
        i.putExtra(ChannelEditActivity.EXTRA_CHANNEL_ID,       channelId);
        i.putExtra(ChannelEditActivity.EXTRA_CHANNEL_NAME,     channelEntity.name);
        i.putExtra(ChannelEditActivity.EXTRA_CHANNEL_DESC,     channelEntity.description);
        i.putExtra(ChannelEditActivity.EXTRA_CHANNEL_ICON,     channelEntity.iconUrl);
        i.putExtra(ChannelEditActivity.EXTRA_CHANNEL_CATEGORY, channelEntity.category);
        i.putExtra(ChannelEditActivity.EXTRA_CHANNEL_PRIVATE,  channelEntity.isPrivate);
        startActivityForResult(i, RC_EDIT_CHAN);
    }

    private void openNotificationSettings() {
        Intent i = new Intent(this, ChannelNotificationSettingsActivity.class);
        i.putExtra("channelId",   channelId);
        i.putExtra("channelName", channelName);
        startActivity(i);
    }

    private void shareChannelLink() {
        String link = "https://callx.app/channel/" + channelId;
        if (channelEntity != null && channelEntity.inviteLink != null
                && !channelEntity.inviteLink.isEmpty()) {
            link = channelEntity.inviteLink;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "Follow " + channelName + " on CallX:\n" + link);
        startActivity(Intent.createChooser(share, "Share channel via"));
    }

    // ── Follow / Unfollow ─────────────────────────────────────────────────

    private void toggleFollow() {
        if (!isFollowing) {
            viewModel.followChannel(buildMinimalEntity());
        } else {
            confirmUnfollow();
        }
    }

    private void confirmUnfollow() {
        new AlertDialog.Builder(this)
            .setTitle("Unfollow " + channelName + "?")
            .setMessage("You'll no longer receive updates from this channel.")
            .setPositiveButton("Unfollow", (d, w) ->
                viewModel.unfollowChannel(buildMinimalEntity()))
            .setNegativeButton("Cancel", null).show();
    }

    // ── Mute ─────────────────────────────────────────────────────────────

    private void handleMuteToggle() {
        if (!isMuted) {
            String[] durations = {"8 hours", "1 week", "Always"};
            long[]   ms = { 8L * 3_600_000L, 7L * 24L * 3_600_000L, 0L };
            new AlertDialog.Builder(this)
                .setTitle("Mute notifications for")
                .setItems(durations, (d, which) -> {
                    long until = ms[which] > 0 ? System.currentTimeMillis() + ms[which] : 0L;
                    viewModel.muteChannel(buildMinimalEntity(), until);
                    isMuted = true;
                    if (menuItemMute != null) menuItemMute.setTitle("Unmute notifications");
                }).show();
        } else {
            viewModel.unmuteChannel(buildMinimalEntity());
            isMuted = false;
            if (menuItemMute != null) menuItemMute.setTitle("Mute notifications");
        }
    }

    // ── Report ────────────────────────────────────────────────────────────

    private void showReportChannelDialog() {
        String[] reasons = {"Spam", "Misleading info", "Harmful content", "Impersonation", "Other"};
        new AlertDialog.Builder(this)
            .setTitle("Report channel")
            .setItems(reasons, (d, which) ->
                viewModel.reportChannel(channelId, reasons[which]))
            .show();
    }

    // ── Reaction picker ───────────────────────────────────────────────────

    private void showReactionPicker(ChannelPost post) {
        String myEmoji = post.reactions != null
            ? post.reactions.get(viewModel.getMyUid()) : null;
        ReactionPickerBottomSheet sheet =
            ReactionPickerBottomSheet.newInstance(post.id, myEmoji);
        sheet.setOnEmojiSelected((emoji, postId) -> {
            if (emoji == null) viewModel.removeReaction(channelId, postId);
            else               viewModel.reactToPost(channelId, postId, emoji);
        });
        sheet.show(getSupportFragmentManager(), ReactionPickerBottomSheet.TAG);
    }

    // ── Forward post ──────────────────────────────────────────────────────

    private void forwardPost(ChannelPost post) {
        Intent i = new Intent(this, ForwardPostActivity.class);
        i.putExtra(ForwardPostActivity.EXTRA_POST_TEXT,     post.text     != null ? post.text     : "");
        i.putExtra(ForwardPostActivity.EXTRA_POST_MEDIA_URL,post.mediaUrl != null ? post.mediaUrl : "");
        i.putExtra(ForwardPostActivity.EXTRA_POST_TYPE,     post.type     != null ? post.type     : "text");
        i.putExtra(ForwardPostActivity.EXTRA_CHANNEL_NAME,  channelName   != null ? channelName   : "");
        i.putExtra(ForwardPostActivity.EXTRA_CHANNEL_ID,    channelId);
        i.putExtra(ForwardPostActivity.EXTRA_POST_ID,       post.id);
        startActivity(i);
    }

    // ── Pinned post scroll ────────────────────────────────────────────────

    private void scrollToPinnedPost() {
        if (currentPinnedPostId == null) return;
        List<ChannelPost> posts = postAdapter.getPosts();
        for (int idx = 0; idx < posts.size(); idx++) {
            if (currentPinnedPostId.equals(posts.get(idx).id)) {
                rvPosts.smoothScrollToPosition(idx);
                return;
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private void updateFollowButton() {
        if (btnFollowToggle == null) return;
        btnFollowToggle.setText(isFollowing ? "Following" : "Follow");
        btnFollowToggle.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                isFollowing ? 0xFFEBEBEB : 0xFFDCF5E7));
        btnFollowToggle.setTextColor(isFollowing ? 0xFF333333 : 0xFF1A773A);
    }

    private ChannelEntity buildMinimalEntity() {
        if (channelEntity != null) return channelEntity;
        ChannelEntity ch = new ChannelEntity();
        ch.id        = channelId;
        ch.name      = channelName;
        ch.isFollowed= isFollowing;
        ch.isMuted   = isMuted;
        return ch;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── Entity → Model conversion ─────────────────────────────────────────

    private ChannelPost entityToModel(ChannelPostEntity e) {
        ChannelPost p = new ChannelPost();
        p.id               = e.id;
        p.channelId        = e.channelId;
        p.authorUid        = e.authorUid;
        p.authorName       = e.authorName;
        p.authorIconUrl    = e.authorIconUrl;
        p.text             = e.text;
        p.type             = e.type;
        p.mediaUrl         = e.mediaUrl;
        p.thumbnailUrl     = e.thumbnailUrl;
        p.mediaWidth       = e.mediaWidth;
        p.mediaHeight      = e.mediaHeight;
        p.linkUrl          = e.linkUrl;
        p.linkTitle        = e.linkTitle;
        p.linkDescription  = e.linkDescription;
        p.linkImageUrl     = e.linkImageUrl;
        p.linkDomain       = e.linkDomain;
        p.pollQuestion     = e.pollQuestion;
        p.pollMultiSelect  = e.pollMultiSelect;
        p.pollExpiresAt    = e.pollExpiresAt;
        p.audioUrl         = e.audioUrl;
        p.audioDurationMs  = e.audioDurationMs;
        p.audioWaveformJson= e.audioWaveformJson;
        p.documentUrl      = e.documentUrl;
        p.documentName     = e.documentName;
        p.documentSizeBytes= e.documentSizeBytes;
        p.documentMimeType = e.documentMimeType;
        p.isPinned         = e.isPinned;
        p.scheduledAt      = e.scheduledAt;
        p.isDraft          = e.isDraft;
        p.timestamp        = e.timestamp;
        p.editedAt         = e.editedAt;
        p.isDeleted        = e.isDeleted;
        p.viewCount        = e.viewCount;
        p.forwardCount     = e.forwardCount;
        p.replyCount       = e.replyCount;
        p.allowReactions   = e.allowReactions;
        p.allowForward     = e.allowForward;
        p.reactions        = parseReactionsJson(e.reactionsJson);
        p.pollOptions      = parsePollOptionsJson(e.pollOptionsJson);
        p.pollVotes        = parsePollVotesJson(e.pollVotesJson);
        return p;
    }

    // ── JSON helpers ──────────────────────────────────────────────────────

    private Map<String, String> parseReactionsJson(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            if (json == null || json.isEmpty() || "{}".equals(json.trim())) return map;
            String s = json.trim();
            if (s.startsWith("{")) s = s.substring(1);
            if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);
            for (String entry : s.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                int colon = entry.lastIndexOf(":");
                if (colon > 0) {
                    String k = entry.substring(0, colon).replaceAll("\"", "").trim();
                    String v = entry.substring(colon + 1).replaceAll("\"", "").trim();
                    if (!k.isEmpty()) map.put(k, v);
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private List<String> parsePollOptionsJson(String json) {
        List<String> list = new ArrayList<>();
        try {
            if (json == null || json.isEmpty() || "[]".equals(json.trim())) return list;
            String s = json.trim();
            if (s.startsWith("[")) s = s.substring(1);
            if (s.endsWith("]"))   s = s.substring(0, s.length() - 1);
            for (String opt : s.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))
                list.add(opt.replaceAll("\"", "").trim());
        } catch (Exception ignored) {}
        return list;
    }

    private Map<String, Long> parsePollVotesJson(String json) {
        Map<String, Long> map = new HashMap<>();
        try {
            if (json == null || json.isEmpty() || "{}".equals(json.trim())) return map;
            String s = json.trim();
            if (s.startsWith("{")) s = s.substring(1);
            if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);
            for (String entry : s.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                int colon = entry.lastIndexOf(":");
                if (colon > 0) {
                    String k = entry.substring(0, colon).replaceAll("\"", "").trim();
                    String v = entry.substring(colon + 1).trim();
                    try { map.put(k, Long.parseLong(v)); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private String reactionsToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":\"").append(e.getValue()).append("\"");
            first = false;
        }
        return sb.append("}").toString();
    }

    // ── Formatting ────────────────────────────────────────────────────────

    private String formatFollowers(long n) {
        if (n >= 1_000_000_000L) return (n / 1_000_000_000L) + "B followers";
        if (n >= 1_000_000L)     return String.format("%.1fM followers", n / 1_000_000.0);
        if (n >= 1_000L)         return String.format("%.1fK followers", n / 1_000.0);
        return n + " followers";
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        // Posts + channel update automatically via LiveData
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (latestPostTimestamp > 0)
            viewModel.markChannelRead(channelId, latestPostTimestamp);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        viewModel.stopSyncingPosts(channelId);
    }
}
