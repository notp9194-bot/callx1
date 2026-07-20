package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
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
import com.callx.app.channel.canvas.ChannelPostGlidePreloader;
import com.callx.app.channel.canvas.ChannelPostHeightCache;
import com.callx.app.channel.canvas.ChannelPostLayoutPrewarmer;
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
 * ChannelViewerActivity — WhatsApp-level complete channel viewer (v5).
 *
 * NEW IN v5 (canvas upgrade):
 *   ✓ RecyclerView GPU/cache perf settings applied in configureRecyclerView()
 *   ✓ Bug fix: filterPostsByTopic() / resetTopicFilter() now use postAdapter
 *     (was incorrectly referencing an undefined `adapter` field in v4)
 *   ✓ observeMilestones() and bindTopicChips() are now wired in onCreate()
 *   ✓ ChannelPostAdapter.stopAudio() called onPause() / onDestroy()
 *   ✓ postAdapter.setIsAdminOrOwner() set when channel entity is observed
 *
 * ALL features (carried from v4):
 *   ✓ Post feed: text, image, video, link, poll, audio, document, broadcast, event, deleted
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
 *   ✓ Long-press post menu: react, forward, copy, save, pin/unpin, edit, delete, report
 *   ✓ Reactions summary → tap → ChannelReactionsDetailActivity
 *   ✓ React (emoji picker sheet) / remove reaction
 *   ✓ Forward post → ForwardPostActivity (with channelId + postId for forward-count tracking)
 *   ✓ Poll voting + poll results navigation → ChannelPollResultsActivity
 *   ✓ Saved posts / bookmarks → ChannelHighlightsActivity
 *   ✓ Admin broadcast → ChannelBroadcastActivity
 *   ✓ View count increment on first-seen (debounced per post per session)
 *   ✓ Pagination (scroll to bottom loads older posts)
 *   ✓ Mark channel read on leave
 *   ✓ Firebase sync started on create, stopped on destroy
 *   ✓ Observed LiveData: channel entity, posts, pinned post, search results, toasts
 *   ✓ Scheduled posts shortcut → ChannelScheduledPostsActivity
 *   ✓ RSVP event support
 *   ✓ Topic tag chip filter row
 *   ✓ Milestone celebration
 */
public class ChannelViewerActivity extends AppCompatActivity
        implements ChannelPostAdapter.PostActionListener,
                   android.content.ComponentCallbacks2 {

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

    private ChannelViewModel           viewModel;
    private ChannelPostAdapter         postAdapter;
    private RecyclerView               rvPosts;
    private ChannelPostLayoutPrewarmer layoutPrewarmer;  // background StaticLayout pre-builder
    private ChannelPostGlidePreloader  glidePreloader;   // Glide memory-cache primer
    private View               emptyState;
    private FloatingActionButton fabNewPost;
    private MaterialButton     btnFollowToggle;
    private MaterialCardView   cardPinnedPost;
    private TextView           tvPinnedText;
    private ImageButton        btnUnpin;
    private CircleImageView    ivChannelIcon;
    private TextView           tvChannelName, tvChannelFollowers, tvChannelVerified;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_viewer);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        // ── Toolbar ──────────────────────────────────────────────────────────
        Toolbar toolbar = findViewById(R.id.toolbar_channel);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        // ── Header views ─────────────────────────────────────────────────────
        ivChannelIcon     = findViewById(R.id.iv_channel_viewer_icon);
        tvChannelName     = findViewById(R.id.tv_channel_viewer_name);
        tvChannelFollowers= findViewById(R.id.tv_channel_viewer_followers);
        tvChannelVerified = findViewById(R.id.tv_channel_viewer_verified);
        btnFollowToggle   = findViewById(R.id.btn_follow_toggle);

        // Set initial values from intent extras
        if (channelName != null && tvChannelName != null) tvChannelName.setText(channelName);
        String channelIcon = getIntent().getStringExtra(EXTRA_CHANNEL_ICON);
        if (channelIcon != null && !channelIcon.isEmpty() && ivChannelIcon != null) {
            Glide.with(this).load(channelIcon).circleCrop().into(ivChannelIcon);
        }
        long followers = getIntent().getLongExtra(EXTRA_CHANNEL_FOLLOWERS, 0);
        if (tvChannelFollowers != null) tvChannelFollowers.setText(formatFollowers(followers));
        boolean verified = getIntent().getBooleanExtra(EXTRA_CHANNEL_VERIFIED, false);
        if (tvChannelVerified != null) tvChannelVerified.setVisibility(verified ? View.VISIBLE : View.GONE);

        // Tap header → channel info bottom sheet
        View headerLayout = findViewById(R.id.layout_channel_header_info);
        if (headerLayout != null)
            headerLayout.setOnClickListener(v -> openChannelInfo());

        // ── Pinned post banner ───────────────────────────────────────────────
        cardPinnedPost = findViewById(R.id.card_pinned_post);
        tvPinnedText   = findViewById(R.id.tv_pinned_post_text);
        btnUnpin       = findViewById(R.id.btn_unpin_post);

        // ── RecyclerView (canvas adapter, fully tuned for perf) ───────────────
        rvPosts    = findViewById(R.id.rv_channel_posts);
        emptyState = findViewById(R.id.layout_channel_empty);

        String ownerUid = getIntent().getStringExtra(EXTRA_OWNER_UID);
        postAdapter = new ChannelPostAdapter(this, viewModel.getMyUid(), this);
        if (ownerUid != null) postAdapter.setOwnerUid(ownerUid);

        configureRecyclerView(ownerUid);

        // ── FAB ──────────────────────────────────────────────────────────────
        fabNewPost = findViewById(R.id.fab_new_post);
        if (fabNewPost != null) fabNewPost.setOnClickListener(v -> openPostComposer());

        // ── Follow button ────────────────────────────────────────────────────
        if (btnFollowToggle != null) btnFollowToggle.setOnClickListener(v -> toggleFollow());

        // ── Observe channel ──────────────────────────────────────────────────
        viewModel.getChannel(channelId).observe(this, ch -> {
            if (ch == null) return;
            channelEntity = ch;
            isFollowing    = ch.isFollowed;
            isMuted        = ch.isMuted;
            isAdminOrOwner = viewModel.isAdminOrOwner(ch);
            isOwner        = viewModel.getMyUid() != null
                && viewModel.getMyUid().equals(ch.ownerUid);

            // Propagate admin state to adapter
            postAdapter.setIsAdminOrOwner(isAdminOrOwner);

            // Update header
            if (tvChannelName     != null) tvChannelName.setText(ch.name);
            if (tvChannelFollowers!= null) tvChannelFollowers.setText(formatFollowers(ch.followers));
            if (tvChannelVerified != null)
                tvChannelVerified.setVisibility(ch.verified ? View.VISIBLE : View.GONE);
            if (ch.iconUrl != null && !ch.iconUrl.isEmpty() && ivChannelIcon != null) {
                Glide.with(this).load(ch.iconUrl).circleCrop().into(ivChannelIcon);
            }

            // Follow button
            updateFollowButton();

            // FAB visibility: admin/owner only
            if (fabNewPost != null)
                fabNewPost.setVisibility(isAdminOrOwner ? View.VISIBLE : View.GONE);

            // Unpin button visibility
            if (btnUnpin != null) btnUnpin.setVisibility(isAdminOrOwner ? View.VISIBLE : View.GONE);

            // Adapter owner
            if (ch.ownerUid != null) postAdapter.setOwnerUid(ch.ownerUid);

            // Wire topic chips now that the channel entity is ready
            bindTopicChips(ch);
        });

        // ── Observe posts ────────────────────────────────────────────────────
        viewModel.getChannelPosts(channelId).observe(this, posts -> {
            if (posts == null || posts.isEmpty()) {
                emptyState.setVisibility(View.VISIBLE);
                postAdapter.setPosts(new ArrayList<>());
                return;
            }
            emptyState.setVisibility(View.GONE);
            List<ChannelPost> cpList = new ArrayList<>();
            for (ChannelPostEntity e : posts) {
                if (e.scheduledAt > 0 || e.isDraft) continue; // skip scheduled/drafts
                ChannelPost cp = entityToModel(e);
                cpList.add(cp);
                if (cp.timestamp > latestPostTimestamp) latestPostTimestamp = cp.timestamp;
            }
            postAdapter.setPosts(cpList);
        });

        // ── Observe pinned post ───────────────────────────────────────────────
        viewModel.getPinnedPost(channelId).observe(this, pinned -> {
            if (pinned == null || cardPinnedPost == null) {
                if (cardPinnedPost != null) cardPinnedPost.setVisibility(View.GONE);
                return;
            }
            cardPinnedPost.setVisibility(View.VISIBLE);
            if (tvPinnedText != null) {
                String preview = pinned.text != null && !pinned.text.isEmpty()
                    ? pinned.text : typeLabel(pinned.type);
                tvPinnedText.setText(preview);
            }
            if (btnUnpin != null) {
                btnUnpin.setOnClickListener(v -> {
                    viewModel.unpinPost(channelId, pinned.id);
                    cardPinnedPost.setVisibility(View.GONE);
                });
            }
            // Tap pinned banner → scroll to that post
            cardPinnedPost.setOnClickListener(v -> scrollToPost(pinned.id));
        });

        // ── Observe search results ────────────────────────────────────────────
        viewModel.searchResults.observe(this, results -> {
            if (isSearchMode && results != null) {
                List<ChannelPost> found = new ArrayList<>();
                for (ChannelPostEntity e : results) found.add(entityToModel(e));
                postAdapter.setPosts(found);
                emptyState.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });

        // ── Observe toast ────────────────────────────────────────────────────
        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty())
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        // ── v5: Wire milestone observer ──────────────────────────────────────
        observeMilestones();

        // ── Start Firebase sync ───────────────────────────────────────────────
        viewModel.startSyncingPosts(channelId);
    }

    /**
     * Applies all RecyclerView performance settings for the canvas-based feed.
     *
     * KEY SETTINGS:
     *   setHasFixedSize(true) — tells RecyclerView the adapter size changes
     *     are handled by the adapter (not via requestLayout on the RV itself),
     *     skipping a full layout pass on every add/remove. Our DiffUtil dispatch
     *     drives individual item changes, so this is safe.
     *
     *   setItemViewCacheSize(20) — keeps 20 off-screen canvas views in the
     *     scrap cache before they go to the recycled pool. Since all CanvasVH
     *     holders are the same type, these are immediately reusable without
     *     a rebind if the user flicks back up — eliminates Glide re-fetches
     *     for the 20 most recently seen posts on reversal.
     *
     *   setDrawingCacheEnabled(false) on RV — not needed; LAYER_TYPE_HARDWARE
     *     on each ChannelPostCanvasView already handles compositing.
     *
     *   RecycledViewPool maxScrap(0, 25) — the default pool cap is 5 views per
     *     type. With a single item type and a pool cap of 5, deep flings create
     *     redundant onCreateViewHolder() calls (new Paint/TextPaint init for
     *     each). Raising it to 25 means a 50-post fling never allocates past
     *     the warm pool. We pre-populate the pool with 5 views so the first
     *     visible frame also benefits.
     */
    /**
     * configureRecyclerView — ultra-optimized feed setup.
     *
     * Summary of every setting and why it helps:
     *
     *   setItemAnimator(null) — the default DefaultItemAnimator runs a cross-fade
     *     animation for every PAYLOAD_ENGAGEMENT update (reaction count tap). On a
     *     channel with active engagement that is several unnecessary animations per
     *     second. Channel feeds are broadcast feeds, not chat — no animation needed.
     *
     *   setInitialPrefetchItemCount(5) — tells LinearLayoutManager to pre-bind 5
     *     items during RenderThread idle time (between frame renders). These binds
     *     happen with CPU slack the GPU hasn't used, so they're essentially free
     *     and mean items are already measured by the time the user scrolls to them.
     *
     *   setOverScrollMode(OVER_SCROLL_NEVER) — disables the edge-glow effect.
     *     The EdgeEffect allocates a gradient shader and calls canvas.drawCircle()
     *     on every over-scroll frame. Zero cost when disabled.
     *
     *   OnFlingListener — detected when velocity > 4000 px/s (a true fast fling).
     *     During fling we call postAdapter.setFlingActive(true) which pauses all
     *     Glide requests. Images are not visible during fast flings (frames drop);
     *     there is no point decoding them. On SCROLL_STATE_IDLE we resume Glide
     *     and trigger a layout pass so newly settled items load immediately.
     *
     *   scroll state listener — also triggers pre-warmer for the next 10 items
     *     on idle so the StaticLayouts are ready before the user scrolls there.
     *
     *   setHasFixedSize(true) — RecyclerView skips calling requestLayout() on the
     *     parent when items are inserted/removed (safe because the RV itself is
     *     always full-height in this layout).
     *
     *   setItemViewCacheSize(20) — scrap-cache holds 20 recently-scrolled VHs
     *     without rebinding. Scroll-back over ≤20 posts costs zero bind work.
     *
     *   RecycledViewPool maxScrap(0, 25), pre-warmed 5 — see v5 notes.
     */
    private void configureRecyclerView(String ownerUid) {
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setInitialPrefetchItemCount(5);    // prefetch 5 items during RenderThread idle
        lm.setRecycleChildrenOnDetach(true);  // recycle all children when RV detaches
        rvPosts.setLayoutManager(lm);
        rvPosts.setAdapter(postAdapter);

        rvPosts.setItemAnimator(null);                          // no item-change animations
        rvPosts.setHasFixedSize(true);
        rvPosts.setItemViewCacheSize(20);
        rvPosts.setOverScrollMode(RecyclerView.OVER_SCROLL_NEVER); // no edge-glow overhead

        // Widen the recycled pool for the single canvas view type.
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(0, 25);
        rvPosts.setRecycledViewPool(pool);

        // Pre-warm the pool with 5 holders so the first scroll frame is ready.
        rvPosts.post(() -> {
            for (int i = 0; i < 5; i++) {
                ChannelPostAdapter.CanvasVH vh =
                        (ChannelPostAdapter.CanvasVH) postAdapter.createViewHolder(rvPosts, 0);
                pool.putRecycledView(vh);
            }
        });

        // ── Fling detection — pause Glide during fast flings ─────────────────
        rvPosts.setOnFlingListener(new RecyclerView.OnFlingListener() {
            private static final int FLING_THRESHOLD_PX = 4000;
            @Override
            public boolean onFling(int velocityX, int velocityY) {
                if (Math.abs(velocityY) > FLING_THRESHOLD_PX) {
                    postAdapter.setFlingActive(true);
                }
                return false; // let RecyclerView handle the actual fling physics
            }
        });

        // ── Scroll state changes ─────────────────────────────────────────────
        rvPosts.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    // Resume Glide — images load for the newly settled viewport.
                    postAdapter.setFlingActive(false);

                    LinearLayoutManager llm = (LinearLayoutManager) rv.getLayoutManager();
                    if (llm != null) {
                        int last = llm.findLastVisibleItemPosition();
                        // Pre-warm StaticLayouts + Glide bitmaps for next window.
                        postAdapter.prewarmFrom(last + 1, last + 11);
                        if (glidePreloader != null) {
                            glidePreloader.preloadRange(
                                    postAdapter.getPosts(), last + 1, last + 9);
                        }
                    }
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // Pagination: load older posts when near the bottom.
                LinearLayoutManager llm = (LinearLayoutManager) rv.getLayoutManager();
                if (llm != null && llm.findLastCompletelyVisibleItemPosition()
                        >= postAdapter.getItemCount() - 3) {
                    long oldest = postAdapter.getOldestTimestamp();
                    if (oldest > 0) viewModel.loadOlderPosts(channelId, oldest);
                }
            }
        });

        // ── Prewarmer init: wait for first layout to know the container width ─
        rvPosts.post(() -> {
            if (rvPosts.getWidth() > 0 && layoutPrewarmer == null) {
                layoutPrewarmer = ChannelPostLayoutPrewarmer.createForContext(
                        ChannelViewerActivity.this);
                postAdapter.setPrewarmer(layoutPrewarmer);
                postAdapter.prewarmFrom(0, 15);
            }
            if (glidePreloader == null) {
                glidePreloader = new ChannelPostGlidePreloader(ChannelViewerActivity.this);
                // Preload bitmaps for visible + next window immediately.
                glidePreloader.preloadRange(postAdapter.getPosts(), 0, 12);
            }
        });
    }

    // ── Options menu ─────────────────────────────────────────────────────────

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_channel_viewer, menu);

        // SearchView setup
        MenuItem searchItem = menu.findItem(R.id.action_channel_search);
        if (searchItem != null) {
            SearchView sv = (SearchView) searchItem.getActionView();
            if (sv != null) {
                sv.setQueryHint("Search posts…");
                sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override public boolean onQueryTextSubmit(String q) { return false; }
                    @Override public boolean onQueryTextChange(String q) {
                        isSearchMode = !q.isEmpty();
                        if (q.isEmpty()) {
                            isSearchMode = false;
                            viewModel.getChannelPosts(channelId); // resets
                        } else {
                            viewModel.searchPosts(channelId, q);
                        }
                        return true;
                    }
                });
                searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                    @Override public boolean onMenuItemActionExpand(MenuItem item) { return true; }
                    @Override public boolean onMenuItemActionCollapse(MenuItem item) {
                        isSearchMode = false;
                        return true;
                    }
                });
            }
        }

        // Hide admin-only items for non-admins
        updateMenuVisibility(menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_channel_mute) {
            showMuteDurationPicker(); return true;
        }
        if (id == R.id.action_channel_highlights) {
            openHighlights(); return true;
        }
        if (id == R.id.action_channel_notif_settings) {
            openNotifSettings(); return true;
        }
        if (id == R.id.action_channel_edit) {
            openEditChannel(); return true;
        }
        if (id == R.id.action_channel_admin) {
            openAdminPanel(); return true;
        }
        if (id == R.id.action_channel_analytics) {
            openAnalytics(); return true;
        }
        if (id == R.id.action_channel_scheduled) {
            openScheduled(); return true;
        }
        if (id == R.id.action_channel_broadcast) {
            openBroadcast(); return true;
        }
        if (id == R.id.action_channel_unfollow) {
            confirmUnfollow(); return true;
        }
        if (id == R.id.action_channel_report) {
            reportChannel(); return true;
        }
        if (id == R.id.action_channel_share) {
            shareChannel(); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateMenuVisibility(Menu menu) {
        boolean admin = isAdminOrOwner;
        if (menu.findItem(R.id.action_channel_edit)      != null)
            menu.findItem(R.id.action_channel_edit).setVisible(admin);
        if (menu.findItem(R.id.action_channel_admin)     != null)
            menu.findItem(R.id.action_channel_admin).setVisible(admin);
        if (menu.findItem(R.id.action_channel_analytics) != null)
            menu.findItem(R.id.action_channel_analytics).setVisible(admin);
        if (menu.findItem(R.id.action_channel_scheduled) != null)
            menu.findItem(R.id.action_channel_scheduled).setVisible(admin);
        if (menu.findItem(R.id.action_channel_broadcast) != null)
            menu.findItem(R.id.action_channel_broadcast).setVisible(admin);
        if (menu.findItem(R.id.action_channel_unfollow)  != null)
            menu.findItem(R.id.action_channel_unfollow).setVisible(isFollowing && !admin);
        if (menu.findItem(R.id.action_channel_report)    != null)
            menu.findItem(R.id.action_channel_report).setVisible(!admin);
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private void openChannelInfo() {
        if (channelEntity == null) return;
        ChannelViewerInfoBottomSheet sheet =
            ChannelViewerInfoBottomSheet.newInstance(channelEntity.id, channelEntity.name,
                channelEntity.description, channelEntity.inviteLink, channelEntity.followers,
                channelEntity.isFollowing, channelEntity.isMuted, channelEntity.isAdmin);
        sheet.show(getSupportFragmentManager(), ChannelViewerInfoBottomSheet.TAG);
    }

    private void openPostComposer() {
        Intent i = new Intent(this, ChannelPostComposerActivity.class);
        i.putExtra(ChannelPostComposerActivity.EXTRA_CHANNEL_ID,   channelId);
        i.putExtra(ChannelPostComposerActivity.EXTRA_CHANNEL_NAME, channelName);
        startActivityForResult(i, RC_NEW_POST);
    }

    private void openEditChannel() {
        Intent i = new Intent(this, ChannelEditActivity.class);
        i.putExtra(ChannelEditActivity.EXTRA_CHANNEL_ID, channelId);
        startActivityForResult(i, RC_EDIT_CHAN);
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

    private void openScheduled() {
        Intent i = new Intent(this, ChannelScheduledPostsActivity.class);
        i.putExtra(ChannelScheduledPostsActivity.EXTRA_CHANNEL_ID,   channelId);
        i.putExtra(ChannelScheduledPostsActivity.EXTRA_CHANNEL_NAME, channelName);
        startActivity(i);
    }

    private void openBroadcast() {
        Intent i = new Intent(this, ChannelBroadcastActivity.class);
        i.putExtra(ChannelBroadcastActivity.EXTRA_CHANNEL_ID,   channelId);
        i.putExtra(ChannelBroadcastActivity.EXTRA_CHANNEL_NAME, channelName);
        startActivity(i);
    }

    private void openHighlights() {
        Intent i = new Intent(this, ChannelHighlightsActivity.class);
        i.putExtra(ChannelHighlightsActivity.EXTRA_CHANNEL_ID,   channelId);
        i.putExtra(ChannelHighlightsActivity.EXTRA_CHANNEL_NAME, channelName);
        startActivity(i);
    }

    private void openNotifSettings() {
        Intent i = new Intent(this, ChannelNotificationSettingsActivity.class);
        i.putExtra(ChannelNotificationSettingsActivity.EXTRA_CHANNEL_ID,   channelId);
        i.putExtra(ChannelNotificationSettingsActivity.EXTRA_CHANNEL_NAME, channelName);
        startActivity(i);
    }

    private void shareChannel() {
        String link = channelEntity != null && channelEntity.inviteLink != null
            ? channelEntity.inviteLink : "https://callx.app/channel/" + channelId;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "Follow " + channelName + " on CallX: " + link);
        startActivity(Intent.createChooser(share, "Share channel"));
    }

    private void scrollToPost(String postId) {
        List<ChannelPost> posts = postAdapter.getPosts();
        for (int i = 0; i < posts.size(); i++) {
            if (postId.equals(posts.get(i).id)) {
                rvPosts.smoothScrollToPosition(i);
                break;
            }
        }
    }

    // ── Follow / unfollow ─────────────────────────────────────────────────────

    private void toggleFollow() {
        if (channelEntity == null) return;
        if (isFollowing) confirmUnfollow();
        else viewModel.followChannel(channelEntity);
    }

    private void confirmUnfollow() {
        if (channelEntity == null) return;
        new AlertDialog.Builder(this)
            .setTitle("Unfollow " + channelName + "?")
            .setMessage("You will stop receiving updates from this channel.")
            .setPositiveButton("Unfollow", (d, w) -> viewModel.unfollowChannel(channelEntity))
            .setNegativeButton("Cancel", null).show();
    }

    private void updateFollowButton() {
        if (btnFollowToggle == null) return;
        if (isFollowing) {
            btnFollowToggle.setText("Following");
            btnFollowToggle.setStrokeColorResource(R.color.colorPrimary);
        } else {
            btnFollowToggle.setText("Follow");
        }
    }

    // ── Mute ─────────────────────────────────────────────────────────────────

    private void showMuteDurationPicker() {
        if (channelEntity == null) return;
        if (isMuted) {
            viewModel.unmuteChannel(channelEntity);
            Toast.makeText(this, "Unmuted", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = {"8 hours", "1 week", "Always"};
        new AlertDialog.Builder(this)
            .setTitle("Mute " + channelName + " for…")
            .setItems(opts, (d, which) -> {
                long dur = which == 0 ? 8 * 3600_000L
                         : which == 1 ? 7 * 86400_000L
                         : Long.MAX_VALUE;
                viewModel.muteChannel(channelEntity, dur);
            }).show();
    }

    // ── Report ────────────────────────────────────────────────────────────────

    private void reportChannel() {
        new AlertDialog.Builder(this)
            .setTitle("Report channel")
            .setMessage("Report " + channelName + " for inappropriate content?")
            .setPositiveButton("Report", (d, w) -> viewModel.reportChannel(channelId))
            .setNegativeButton("Cancel", null).show();
    }

    // ── PostActionListener callbacks ──────────────────────────────────────────

    @Override public void onReact(ChannelPost post) {
        ReactionPickerBottomSheet sheet = ReactionPickerBottomSheet.newInstance(
            post.id, post.getMyReaction(viewModel.getMyUid()));
        sheet.setOnEmojiSelected((emoji, postId) -> {
            if (emoji == null) viewModel.removeReaction(channelId, postId);
            else               viewModel.reactToPost(channelId, postId, emoji);
        });
        sheet.show(getSupportFragmentManager(), ReactionPickerBottomSheet.TAG);
    }

    @Override public void onForward(ChannelPost post) {
        Intent i = new Intent(this, ForwardPostActivity.class);
        i.putExtra(ForwardPostActivity.EXTRA_POST_TEXT,      post.text);
        i.putExtra(ForwardPostActivity.EXTRA_POST_MEDIA_URL, post.mediaUrl != null ? post.mediaUrl : post.audioUrl);
        i.putExtra(ForwardPostActivity.EXTRA_POST_TYPE,      post.type);
        i.putExtra(ForwardPostActivity.EXTRA_CHANNEL_NAME,   channelName);
        i.putExtra(ForwardPostActivity.EXTRA_CHANNEL_ID,     channelId);
        i.putExtra(ForwardPostActivity.EXTRA_POST_ID,        post.id);
        startActivity(i);
    }

    @Override public void onCopy(ChannelPost post) {
        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            String copyText = post.text != null && !post.text.isEmpty()
                ? post.text : (post.linkUrl != null ? post.linkUrl : "");
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Post", copyText));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onDelete(ChannelPost post) {
        new AlertDialog.Builder(this)
            .setTitle("Delete post?")
            .setMessage("This post will be permanently deleted.")
            .setPositiveButton("Delete", (d, w) -> viewModel.deletePost(channelId, post.id))
            .setNegativeButton("Cancel", null).show();
    }

    @Override public void onEdit(ChannelPost post) {
        Intent i = new Intent(this, ChannelPostComposerActivity.class);
        i.putExtra(ChannelPostComposerActivity.EXTRA_CHANNEL_ID,    channelId);
        i.putExtra(ChannelPostComposerActivity.EXTRA_CHANNEL_NAME,  channelName);
        i.putExtra(ChannelPostComposerActivity.EXTRA_EDIT_POST_ID,  post.id);
        i.putExtra(ChannelPostComposerActivity.EXTRA_EDIT_POST_TEXT, post.text);
        startActivity(i);
    }

    @Override public void onReport(ChannelPost post) {
        new AlertDialog.Builder(this)
            .setTitle("Report post?")
            .setMessage("Report this post for inappropriate content?")
            .setPositiveButton("Report", (d, w) -> viewModel.reportPost(channelId, post.id))
            .setNegativeButton("Cancel", null).show();
    }

    @Override public void onVotePoll(ChannelPost post, int optionIndex) {
        viewModel.votePoll(channelId, post.id, optionIndex);
    }

    @Override public void onViewMedia(ChannelPost post) {
        Intent i = new Intent(this, ChannelMediaViewerActivity.class);
        i.putExtra(ChannelMediaViewerActivity.EXTRA_MEDIA_URL,   post.mediaUrl);
        i.putExtra(ChannelMediaViewerActivity.EXTRA_MEDIA_TYPE,  post.type);
        i.putExtra(ChannelMediaViewerActivity.EXTRA_POST_TEXT,   post.text);
        i.putExtra(ChannelMediaViewerActivity.EXTRA_CHANNEL_NAME, channelName);
        i.putExtra(ChannelMediaViewerActivity.EXTRA_CHANNEL_ID,  channelId);
        i.putExtra(ChannelMediaViewerActivity.EXTRA_POST_ID,     post.id);
        startActivity(i);
    }

    @Override public void onPinPost(ChannelPost post) {
        if (post.isPinned) viewModel.unpinPost(channelId, post.id);
        else               viewModel.pinPost(channelId, post.id);
    }

    @Override public void onReactionsDetail(ChannelPost post) {
        Intent i = new Intent(this, ChannelReactionsDetailActivity.class);
        org.json.JSONObject reactionsJson = new org.json.JSONObject();
        if (post.reactions != null) {
            for (Map.Entry<String, String> e : post.reactions.entrySet()) {
                try { reactionsJson.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
            }
        }
        i.putExtra(ChannelReactionsDetailActivity.EXTRA_REACTIONS_JSON, reactionsJson.toString());
        i.putExtra(ChannelReactionsDetailActivity.EXTRA_POST_ID,        post.id);
        startActivity(i);
    }

    @Override public void onViewCount(ChannelPost post) {
        viewModel.incrementViewCount(channelId, post.id);
    }

    @Override public void onReply(ChannelPost post) {
        Intent i = new Intent(this, ChannelReplyActivity.class);
        i.putExtra(ChannelReplyActivity.EXTRA_CHANNEL_ID,   channelId);
        i.putExtra(ChannelReplyActivity.EXTRA_POST_ID,      post.id);
        i.putExtra(ChannelReplyActivity.EXTRA_POST_TEXT,    post.text);
        i.putExtra(ChannelReplyActivity.EXTRA_POST_TYPE,    post.type);
        i.putExtra(ChannelReplyActivity.EXTRA_CHANNEL_NAME, channelName);
        i.putExtra(ChannelReplyActivity.EXTRA_IS_ADMIN,     isAdminOrOwner);
        startActivity(i);
    }

    // New callback: save/bookmark post
    public void onSavePost(ChannelPost post) {
        ChannelHighlightsActivity.toggleBookmark(this, channelId, post.id);
    }

    // New callback: poll results
    public void onPollResults(ChannelPost post) {
        if (!"poll".equals(post.type)) return;
        Intent i = new Intent(this, ChannelPollResultsActivity.class);
        i.putExtra(ChannelPollResultsActivity.EXTRA_CHANNEL_ID,    channelId);
        i.putExtra(ChannelPollResultsActivity.EXTRA_POST_ID,       post.id);
        i.putExtra(ChannelPollResultsActivity.EXTRA_POLL_QUESTION, post.pollQuestion);
        if (post.pollOptions != null) {
            i.putExtra(ChannelPollResultsActivity.EXTRA_POLL_OPTIONS,
                post.pollOptions.toArray(new String[0]));
        }
        i.putExtra(ChannelPollResultsActivity.EXTRA_POLL_MULTI,    post.pollMultiSelect);
        i.putExtra(ChannelPollResultsActivity.EXTRA_POLL_EXPIRES,  post.pollExpiresAt);
        i.putExtra(ChannelPollResultsActivity.EXTRA_IS_ADMIN,      isAdminOrOwner);
        startActivity(i);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChannelPost entityToModel(ChannelPostEntity e) {
        ChannelPost p = new ChannelPost();
        p.id            = e.id;
        p.channelId     = e.channelId;
        p.authorUid     = e.authorUid;
        p.authorName    = e.authorName;
        p.authorIconUrl = e.authorIconUrl;
        p.text          = e.text;
        p.type          = e.type;
        p.mediaUrl      = e.mediaUrl;
        p.thumbnailUrl  = e.thumbnailUrl;
        p.linkUrl       = e.linkUrl;
        p.linkTitle     = e.linkTitle;
        p.linkDescription = e.linkDescription;
        p.linkImageUrl  = e.linkImageUrl;
        p.linkDomain    = e.linkDomain;
        p.pollQuestion  = e.pollQuestion;
        p.audioUrl      = e.audioUrl;
        p.audioDurationMs = e.audioDurationMs;
        p.audioWaveformJson = e.audioWaveformJson;
        p.documentUrl   = e.documentUrl;
        p.documentName  = e.documentName;
        p.documentSizeBytes = e.documentSizeBytes;
        p.documentMimeType  = e.documentMimeType;
        p.timestamp     = e.timestamp;
        p.viewCount     = e.viewCount;
        p.forwardCount  = e.forwardCount;
        p.replyCount    = e.replyCount;
        p.isDeleted     = e.isDeleted;
        p.isPinned      = e.isPinned;
        p.allowReactions = e.allowReactions;
        p.allowForward   = e.allowForward;
        p.pollMultiSelect = e.pollMultiSelect;
        p.pollExpiresAt   = e.pollExpiresAt;

        // Parse poll options
        if (e.pollOptionsJson != null && !e.pollOptionsJson.isEmpty()) {
            p.pollOptions = parsePollOptions(e.pollOptionsJson);
        }
        // Parse reactions
        if (e.reactionsJson != null && !e.reactionsJson.isEmpty()) {
            p.reactions = parseReactionsJson(e.reactionsJson);
        }
        // Parse poll votes
        if (e.pollVotesJson != null && !e.pollVotesJson.isEmpty()) {
            p.pollVotes = parsePollVotes(e.pollVotesJson);
        }
        return p;
    }

    private List<String> parsePollOptions(String json) {
        List<String> opts = new ArrayList<>();
        try {
            String s = json.trim();
            if (s.startsWith("[")) s = s.substring(1);
            if (s.endsWith("]"))   s = s.substring(0, s.length() - 1);
            for (String part : s.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                opts.add(part.replaceAll("\"", "").trim());
            }
        } catch (Exception ignored) {}
        return opts;
    }

    private Map<String, String> parseReactionsJson(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
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

    private Map<String, Long> parsePollVotes(String json) {
        Map<String, Long> map = new LinkedHashMap<>();
        try {
            String s = json.trim();
            if (s.startsWith("{")) s = s.substring(1);
            if (s.endsWith("}"))   s = s.substring(0, s.length() - 1);
            for (String entry : s.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                int colon = entry.lastIndexOf(":");
                if (colon > 0) {
                    String k = entry.substring(0, colon).replaceAll("\"", "").trim();
                    String v = entry.substring(colon + 1).replaceAll("\"", "").trim();
                    try { map.put(k, Long.parseLong(v)); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private String formatFollowers(long n) {
        if (n >= 1_000_000_000L) return (n / 1_000_000_000L) + "B followers";
        if (n >= 1_000_000L)     return String.format("%.1fM followers", n / 1_000_000.0);
        if (n >= 1_000L)         return String.format("%.1fK followers", n / 1_000.0);
        return n + " followers";
    }

    private String typeLabel(String type) {
        if (type == null) return "Post";
        switch (type) {
            case "image":    return "📷 Image";
            case "video":    return "🎬 Video";
            case "audio":    return "🎵 Voice note";
            case "document": return "📄 Document";
            case "poll":     return "📊 Poll";
            case "link":     return "🔗 Link";
            case "event":    return "🗓 Event";
            case "broadcast":return "📢 Broadcast";
            default:         return "Post";
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        // Posts + channel update automatically via LiveData
    }

    @Override protected void onPause() {
        super.onPause();
        ChannelPostAdapter.stopAudio();
        // Pause Glide while this screen is not visible.
        postAdapter.setFlingActive(false); // resumes requests — they'll be paused by Glide lifecycle
    }

    @Override protected void onStop() {
        super.onStop();
        if (latestPostTimestamp > 0)
            viewModel.markChannelRead(channelId, latestPostTimestamp);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ChannelPostAdapter.stopAudio();
        viewModel.stopSyncingPosts(channelId);
        if (layoutPrewarmer != null) {
            layoutPrewarmer.shutdown();
            layoutPrewarmer = null;
        }
        if (isChangingConfigurations()) {
            // Rotation — container width changes, height cache entries become stale.
            ChannelPostHeightCache.get().invalidateAll();
        }
    }

    // ── ComponentCallbacks2 — memory pressure handling ────────────────────────
    // Called by Android when the system is low on memory. We release our caches
    // (height map, prewarmed StaticLayouts) to give the system breathing room.
    // Glide manages its own LruCache and handles trim itself.
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            ChannelPostHeightCache.get().invalidateAll();
            if (layoutPrewarmer != null) layoutPrewarmer.clear();
            if (glidePreloader   != null) glidePreloader.onNewList();
        }
    }

    @Override public void onConfigurationChanged(@NonNull android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
    }

    // ── RSVP event callback (called by canvas view via OnPostClickListener) ──

    public void onRsvpEvent(ChannelPost post, String status) {
        viewModel.rsvpEvent(channelId, post.id, status);
        Toast.makeText(this, "RSVP: " + status.replace("_", " "), Toast.LENGTH_SHORT).show();
    }

    // ── Share post to status ───────────────────────────────────────────────

    public void onShareToStatus(ChannelPost post) {
        viewModel.sharePostToStatus(post);
    }

    // ── Milestone observer (wired in onCreate()) ───────────────────────────
    // BUG FIX (v5): was defined but never called — now wired in onCreate().

    private void observeMilestones() {
        viewModel.milestoneReached.observe(this, count -> {
            if (count != null && count > 0 && channelName != null) {
                ChannelMilestoneHelper.celebrate(this, count, channelName);
                // Also push an analytics event
                viewModel.pushAnalytics(channelId, "milestone", count);
            }
        });
    }

    // ── Topic filter chip row (wired from channel observer) ───────────────
    // BUG FIX (v5): filterPostsByTopic / resetTopicFilter used undefined `adapter`
    //               field — fixed to use `postAdapter` throughout.

    private void bindTopicChips(ChannelEntity ch) {
        com.google.android.material.chip.ChipGroup cgTopics =
            findViewById(R.id.chip_group_topic_filter);
        if (cgTopics == null || ch == null) return;
        if (ch.topicTagsJson == null || ch.topicTagsJson.isEmpty()) {
            cgTopics.setVisibility(View.GONE); return;
        }
        List<String> tags = parseJsonArray(ch.topicTagsJson);
        if (tags.isEmpty()) { cgTopics.setVisibility(View.GONE); return; }

        cgTopics.removeAllViews();
        // "All" chip
        com.google.android.material.chip.Chip all =
            new com.google.android.material.chip.Chip(this);
        all.setText("All"); all.setCheckable(true); all.setChecked(true);
        all.setOnCheckedChangeListener((btn, c) -> { if (c) resetTopicFilter(); });
        cgTopics.addView(all);

        for (String tag : tags) {
            com.google.android.material.chip.Chip chip =
                new com.google.android.material.chip.Chip(this);
            chip.setText("#" + tag);
            chip.setCheckable(true);
            chip.setOnCheckedChangeListener((btn, c) -> { if (c) filterPostsByTopic(tag); });
            cgTopics.addView(chip);
        }
        cgTopics.setVisibility(View.VISIBLE);
    }

    private void filterPostsByTopic(String tag) {
        // FIX: was `adapter.getPosts()` — undefined field. Corrected to postAdapter.
        List<ChannelPost> filtered = new ArrayList<>();
        for (ChannelPost p : postAdapter.getPosts()) {
            if (p.topicTags != null && p.topicTags.contains(tag)) filtered.add(p);
        }
        postAdapter.setPosts(filtered);
    }

    private void resetTopicFilter() {
        // FIX: was `adapter.setPosts()` — undefined field. Corrected to postAdapter.
        viewModel.getChannelPosts(channelId).observe(this, posts -> {
            if (posts == null) return;
            List<ChannelPost> list = new ArrayList<>();
            for (com.callx.app.db.entity.ChannelPostEntity e : posts) list.add(entityToModel(e));
            postAdapter.setPosts(list);
        });
    }

    private List<String> parseJsonArray(String json) {
        List<String> tags = new ArrayList<>();
        if (json == null) return tags;
        try {
            String s = json.trim().replaceAll("[\\[\\]\"\\s]", "");
            for (String t : s.split(",")) if (!t.isEmpty()) tags.add(t);
        } catch (Exception ignored) {}
        return tags;
    }
}
