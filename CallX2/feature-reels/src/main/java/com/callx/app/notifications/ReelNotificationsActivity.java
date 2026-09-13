package com.callx.app.notifications;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.profile.UserReelsActivity;

  import android.content.Intent;
  import android.os.Bundle;
  import android.text.Editable;
  import android.text.TextWatcher;
  import android.view.*;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.AsyncListDiffer;
  import androidx.recyclerview.widget.DiffUtil;
  import androidx.recyclerview.widget.ItemTouchHelper;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.reels.R;
  import com.callx.app.notifications.ReelNotificationHelper;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import de.hdodenhof.circleimageview.CircleImageView;
  import java.text.SimpleDateFormat;
  import java.util.*;

  /**
   * ReelNotificationsActivity — Full production-grade reel notification inbox.
   *
   * Features:
   *  ✅ All 40+ reel notification types displayed
   *  ✅ Type-filter tabs: All / Likes / Comments / Mentions / Followers / Reels / Challenges / Recos / Sales
   *  ✅ Search bar (filter by sender name or message)
   *  ✅ Swipe-to-delete individual notifications
   *  ✅ Mark all as read
   *  ✅ Delete all notifications
   *  ✅ Unread dot indicator per item
   *  ✅ Firebase real-time sync (reel_notifications/{uid})
   *  ✅ Avatar download with Glide
   *  ✅ Empty state
   *  ✅ Open reel on tap
   *  ✅ Cross-device sync via Firebase
   */
  public class ReelNotificationsActivity extends AppCompatActivity {

      // Filter tabs
      private static final String[] TAB_LABELS = {
          "All", "Likes", "Comments", "Mentions", "Follows", "Shares", "Reposts", "Challenges", "Recos", "Sales"
      };
      private static final String[] TAB_TYPES = {
          null, "like", "comment", "mention", "follow", "share", "repost", "challenge_update", "reel_recommended", "product_tag_sale"
      };

      private RecyclerView  rv;
      private ReelNotifSkeletonView skeletonView;
      private TextView      tvEmpty;
      private LinearLayout  tabsContainer;
      private EditText      etSearch;
      private TextView      btnMarkAll;
      private TextView      btnDeleteAll;
      private ImageButton   btnBack;
      private LinearLayout  followersSection;
      private LinearLayout  followersRow;

      private ReelNotifAdapter adapter;
      private final List<ReelNotifItem> allItems      = new ArrayList<>();
      private final List<ReelNotifItem> filteredItems = new ArrayList<>();

      // ── Pagination ────────────────────────────────────────────────────────
      private static final int PAGE_SIZE = 50;
      private final List<ReelNotifItem> liveItems  = new ArrayList<>(); // realtime-synced newest page
      private final List<ReelNotifItem> olderItems = new ArrayList<>(); // fetched once per page, on scroll
      private boolean isLoadingMore    = false;
      private boolean noMoreOlderData  = false;
      private long    oldestLoadedTimestamp = Long.MAX_VALUE;
      private ProgressBar footerProgress;
      private LinearLayoutManager layoutManager;

      private String myUid;
      private DatabaseReference notifRef;
      private ValueEventListener notifListener;
      private String currentFilter = null; // null = All

      // ── Row-level mute (long-press → "Mute this person") ────────────────────
      // Mirrors path shape of reel_notifications: reel_notification_mutes/{myUid}/{senderUid} = true.
      private final Set<String> mutedSenders = new HashSet<>();
      private DatabaseReference muteRef;

      // ── Data model ─────────────────────────────────────────────────────────
      static class ReelNotifItem {
          String key, type, title, body, senderUid, senderName, senderPhoto;
          String reelId, reelThumb;
          long   timestamp;
          boolean read;

          // Instagram-style aggregation: raw like/comment rows for the same
          // reel are folded into one display row (see buildGroupedWithHeaders).
          // Null/empty for a plain, ungrouped row.
          List<ReelNotifItem> groupMembers;

          // Up to 3 distinct sender photos from groupMembers (dedup by uid,
          // newest-first) — feeds the overlapping avatar stack for grouped
          // rows. Null/empty for a plain, ungrouped row.
          List<String> groupAvatarPhotos;

          // UI-only cache for the inline Follow Back/Following button on
          // "follow" rows — null until the first Firebase check resolves.
          Boolean followingSender;

          // Section-header rows ("Today" / "This Week" / "Earlier") inserted
          // by buildGroupedWithHeaders — everything else on this item is unused.
          boolean isHeader;
          String  headerLabel;
      }

      @Override
      protected void onCreate(Bundle s) {
          super.onCreate(s);
          try { myUid = FirebaseUtils.getCurrentUid(); }
          catch (Exception e) { finish(); return; }

          notifRef = FirebaseUtils.db().getReference("reel_notifications").child(myUid);
          muteRef  = FirebaseUtils.db().getReference("reel_notification_mutes").child(myUid);
          buildLayout();
          loadMutedSenders();
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Layout build
      // ─────────────────────────────────────────────────────────────────────────
      private void buildLayout() {
          LinearLayout root = new LinearLayout(this);
          root.setOrientation(LinearLayout.VERTICAL);
          root.setBackgroundColor(0xFF111111);

          // Toolbar
          LinearLayout tb = new LinearLayout(this);
          tb.setOrientation(LinearLayout.HORIZONTAL);
          tb.setGravity(android.view.Gravity.CENTER_VERTICAL);
          tb.setBackgroundColor(0xFF1A1A1A);
          tb.setPadding(dp(4), 0, dp(8), 0);
          tb.setElevation(dp(4));

          btnBack = new ImageButton(this);
          btnBack.setImageResource(R.drawable.ic_arrow_back);
          btnBack.setBackground(null);
          com.callx.app.utils.IconResolver.tintExistingOnMedia(btnBack);
          btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
          btnBack.setOnClickListener(v -> finish());
          tb.addView(btnBack);

          TextView tvTitle = new TextView(this);
          tvTitle.setText("Reel Activity");
          tvTitle.setTextColor(0xFFFFFFFF);
          tvTitle.setTextSize(17);
          tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
          tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
              ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
          tvTitle.setPadding(dp(8), 0, 0, 0);
          tb.addView(tvTitle);

          btnMarkAll = new TextView(this);
          btnMarkAll.setText("Mark read");
          btnMarkAll.setTextColor(0xFFFF3B5C);
          btnMarkAll.setTextSize(12);
          btnMarkAll.setPadding(dp(8), dp(4), dp(4), dp(4));
          btnMarkAll.setOnClickListener(v -> markAllRead());
          tb.addView(btnMarkAll);

          btnDeleteAll = new TextView(this);
          btnDeleteAll.setText(" Clear");
          btnDeleteAll.setTextColor(0xFF888888);
          btnDeleteAll.setTextSize(12);
          btnDeleteAll.setPadding(dp(4), dp(4), dp(8), dp(4));
          btnDeleteAll.setOnClickListener(v -> clearAll());
          tb.addView(btnDeleteAll);

          root.addView(tb, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

          // Search bar
          LinearLayout searchRow = new LinearLayout(this);
          searchRow.setOrientation(LinearLayout.HORIZONTAL);
          searchRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
          searchRow.setBackgroundColor(0xFF1A1A1A);
          searchRow.setPadding(dp(12), dp(6), dp(12), dp(8));

          etSearch = new EditText(this);
          etSearch.setHint("Search notifications…");
          etSearch.setHintTextColor(0xFF666666);
          etSearch.setTextColor(0xFFFFFFFF);
          etSearch.setTextSize(14);
          etSearch.setBackgroundColor(0xFF2A2A2A);
          etSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
          etSearch.setSingleLine(true);
          etSearch.addTextChangedListener(new TextWatcher() {
              public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
              public void onTextChanged(CharSequence s, int a, int b, int c) { applySearch(s.toString()); }
              public void afterTextChanged(Editable s) {}
          });
          searchRow.addView(etSearch, new LinearLayout.LayoutParams(0,
              ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

          root.addView(searchRow);

          // Filter tabs
          HorizontalScrollView hsv = new HorizontalScrollView(this);
          hsv.setHorizontalScrollBarEnabled(false);
          hsv.setBackgroundColor(0xFF1A1A1A);

          tabsContainer = new LinearLayout(this);
          tabsContainer.setOrientation(LinearLayout.HORIZONTAL);
          tabsContainer.setGravity(android.view.Gravity.CENTER_VERTICAL);
          tabsContainer.setPadding(dp(8), dp(6), dp(8), dp(6));

          for (int i = 0; i < TAB_LABELS.length; i++) {
              final int idx = i;
              final String type = TAB_TYPES[i];
              TextView chip = new TextView(this);
              chip.setText(TAB_LABELS[i]);
              chip.setTextSize(12);
              chip.setPadding(dp(14), dp(6), dp(14), dp(6));
              chip.setTextColor(i == 0 ? 0xFFFFFFFF : 0xFF888888);
              chip.setBackground(getDrawable(i == 0
                  ? R.drawable.bg_unread_badge
                  : android.R.drawable.list_selector_background));
              LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                  ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
              chipLp.setMarginEnd(dp(6));
              chip.setLayoutParams(chipLp);
              chip.setTag("chip_" + i);
              chip.setOnClickListener(v -> {
                  currentFilter = type;
                  updateTabSelection(idx);
                  applyFilter();
              });
              tabsContainer.addView(chip);
          }
          hsv.addView(tabsContainer);
          root.addView(hsv, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

          View divider = new View(this);
          divider.setBackgroundColor(0xFF222222);
          root.addView(divider, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, 1));

          // "New followers" carousel — horizontal strip of the most recent
          // distinct follow-notification senders, hidden until populated.
          followersSection = new LinearLayout(this);
          followersSection.setOrientation(LinearLayout.VERTICAL);
          followersSection.setBackgroundColor(0xFF111111);
          followersSection.setVisibility(View.GONE);

          TextView followersHeader = new TextView(this);
          followersHeader.setText("New followers");
          followersHeader.setTextColor(0xFFFFFFFF);
          followersHeader.setTextSize(13);
          followersHeader.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
          followersHeader.setPadding(dp(16), dp(12), dp(16), dp(6));
          followersSection.addView(followersHeader);

          HorizontalScrollView followersScroll = new HorizontalScrollView(this);
          followersScroll.setHorizontalScrollBarEnabled(false);
          followersRow = new LinearLayout(this);
          followersRow.setOrientation(LinearLayout.HORIZONTAL);
          followersRow.setPadding(dp(12), 0, dp(12), dp(12));
          followersScroll.addView(followersRow);
          followersSection.addView(followersScroll);

          View followersDivider = new View(this);
          followersDivider.setBackgroundColor(0xFF222222);
          followersSection.addView(followersDivider, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, 1));

          root.addView(followersSection);

          // Empty state
          tvEmpty = new TextView(this);
          tvEmpty.setText("No reel activity yet");
          tvEmpty.setTextColor(0xFF666666);
          tvEmpty.setTextSize(15);
          tvEmpty.setGravity(android.view.Gravity.CENTER);
          tvEmpty.setPadding(0, dp(64), 0, 0);
          tvEmpty.setVisibility(View.GONE);
          root.addView(tvEmpty);

          // RecyclerView — GONE until the skeleton loader resolves into real
          // content (applyFilter() flips this once data/empty-state is known).
          rv = new RecyclerView(this);
          rv.setVisibility(View.GONE);
          layoutManager = new LinearLayoutManager(this);
          rv.setLayoutManager(layoutManager);
          adapter = new ReelNotifAdapter();
          rv.setAdapter(adapter);
          // FIX (velocity-based prefetch): fast fling past notifications skips ahead, slow scroll warms sender avatars — see FollowAvatarBinder.
          com.callx.app.followers.AvatarScrollPrefetchHelper.attach(rv, layoutManager,
              new com.callx.app.followers.FollowAvatarBinder.AvatarSource() {
                  @Override public String photo(int index) { return filteredItems.get(index).senderPhoto; }
                  @Override public long avatarVersion(int index) { return 0L; }
                  @Override public int size() { return filteredItems.size(); }
              });

          // Infinite scroll — fetch the next older page a few rows before the
          // user actually hits the bottom, so it's already there by the time they arrive.
          rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
              @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                  if (dy <= 0 || isLoadingMore || noMoreOlderData) return;
                  int lastVisible = layoutManager.findLastVisibleItemPosition();
                  if (lastVisible >= adapter.getItemCount() - 6) loadMoreOlder();
              }
          });

          // Swipe-to-delete (section headers don't swipe)
          new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
              ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
              @Override public boolean onMove(@NonNull RecyclerView r,
                      @NonNull RecyclerView.ViewHolder h, @NonNull RecyclerView.ViewHolder t) { return false; }
              @Override public int getSwipeDirs(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
                  int pos = h.getAdapterPosition();
                  if (pos < 0 || pos >= filteredItems.size() || filteredItems.get(pos).isHeader) return 0;
                  return super.getSwipeDirs(r, h);
              }
              @Override public void onSwiped(@NonNull RecyclerView.ViewHolder h, int dir) {
                  int pos = h.getAdapterPosition();
                  if (pos < 0 || pos >= filteredItems.size()) return;
                  ReelNotifItem item = filteredItems.get(pos);

                  // Right-swipe on a "follow" row = follow back, non-destructive —
                  // row stays put, only its follow state (and button) update.
                  boolean isFollowRow = "follow".equals(item.type)
                          && item.senderUid != null && !item.senderUid.isEmpty()
                          && !item.senderUid.equals(myUid);
                  if (dir == ItemTouchHelper.RIGHT && isFollowRow) {
                      boolean newState = item.followingSender == null || !item.followingSender;
                      item.followingSender = newState;
                      DatabaseReference ref = FirebaseUtils.getReelFollowsRef(myUid).child(item.senderUid);
                      DatabaseReference followerRef = FirebaseUtils.getReelFollowersRef(item.senderUid).child(myUid);
                      if (newState) { ref.setValue(true); followerRef.setValue(true); }
                      else          { ref.removeValue();  followerRef.removeValue(); }
                      Toast.makeText(ReelNotificationsActivity.this,
                          newState
                              ? "Following " + (item.senderName != null ? item.senderName : "user")
                              : "Unfollowed " + (item.senderName != null ? item.senderName : "user"),
                          Toast.LENGTH_SHORT).show();
                      adapter.notifyItemChanged(pos);
                      return;
                  }

                  // Otherwise: delete, same as before.
                  // PERF: remove from the backing list and let AsyncListDiffer
                  // diff + animate the removal, instead of a manual
                  // notifyItemRemoved() racing a differ that doesn't know
                  // about the mutation.
                  filteredItems.remove(pos);
                  adapter.submitList(filteredItems);
                  // A grouped row folds several raw notifications — delete every
                  // one of them, not just the single key kept for display.
                  if (item.groupMembers != null && !item.groupMembers.isEmpty()) {
                      for (ReelNotifItem m : item.groupMembers) {
                          removeFromBackingLists(m);
                          if (m.key != null) notifRef.child(m.key).removeValue();
                      }
                  } else {
                      removeFromBackingLists(item);
                      if (item.key != null) notifRef.child(item.key).removeValue();
                  }
                  boolean hasContent = false;
                  for (ReelNotifItem it : filteredItems) { if (!it.isHeader) { hasContent = true; break; } }
                  if (!hasContent) {
                      filteredItems.clear();
                      adapter.submitList(filteredItems);
                      tvEmpty.setVisibility(View.VISIBLE);
                      rv.setVisibility(View.GONE);
                  }
              }
          }).attachToRecyclerView(rv);

          // rv + a small "loading more" spinner pinned to the bottom while paging.
          FrameLayout listContainer = new FrameLayout(this);
          listContainer.addView(rv, new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

          // Shimmer skeleton — shown while the first Firebase page is still
          // loading, instead of a plain centered ProgressBar (mirrors
          // CommentSkeletonView's shader-sweep idiom). Opaque background so
          // it fully covers the empty rv underneath.
          skeletonView = new ReelNotifSkeletonView(this);
          skeletonView.setBackgroundColor(0xFF111111);
          listContainer.addView(skeletonView, new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

          footerProgress = new ProgressBar(this);
          footerProgress.setVisibility(View.GONE);
          FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
          footerLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
          footerLp.bottomMargin = dp(12);
          listContainer.addView(footerProgress, footerLp);

          root.addView(listContainer, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

          setContentView(root);
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Row-level mute
      // ─────────────────────────────────────────────────────────────────────────
      /** Fetches the muted-sender set once before the realtime listener attaches,
       *  so a freshly-muted person's old notifications don't flash on screen. */
      private void loadMutedSenders() {
          muteRef.get().addOnSuccessListener(snap -> {
              if (isFinishing() || isDestroyed()) return;
              mutedSenders.clear();
              for (DataSnapshot c : snap.getChildren()) {
                  Boolean v = c.getValue(Boolean.class);
                  if (v != null && v) mutedSenders.add(c.getKey());
              }
              loadNotifications();
          }).addOnFailureListener(e -> { if (!isFinishing()) loadNotifications(); });
      }

      /** Long-press quick action (mirrors Instagram's row long-press menu):
       *  mute/unmute future activity notifications from this sender. Also
       *  hides that sender's existing rows immediately via applyFilter(). */
      private void showMuteMenu(ReelNotifItem item) {
          String uid = item.senderUid;
          if (uid == null || uid.isEmpty()) return;
          boolean isMuted = mutedSenders.contains(uid);
          String name = item.senderName != null && !item.senderName.isEmpty() ? item.senderName : "this person";
          new android.app.AlertDialog.Builder(this)
              .setTitle(isMuted ? "Unmute " + name + "?" : "Mute " + name + "?")
              .setMessage(isMuted
                  ? "You'll start seeing activity notifications from " + name + " again."
                  : "You won't be notified about likes, comments, follows or other activity from " + name + " anymore.")
              .setPositiveButton(isMuted ? "Unmute" : "Mute", (d, w) -> toggleMute(uid, !isMuted))
              .setNegativeButton("Cancel", null)
              .show();
      }

      private void toggleMute(String uid, boolean mute) {
          if (mute) { mutedSenders.add(uid);    muteRef.child(uid).setValue(true); }
          else      { mutedSenders.remove(uid); muteRef.child(uid).removeValue(); }
          Toast.makeText(this, mute ? "Notifications muted" : "Notifications unmuted", Toast.LENGTH_SHORT).show();
          applyFilter();
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Data loading
      // ─────────────────────────────────────────────────────────────────────────
      private void loadNotifications() {
          skeletonView.setVisibility(View.VISIBLE);
          skeletonView.start();
          notifListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (isFinishing() || isDestroyed()) return;
                  liveItems.clear();
                  parseSnapshotChildren(snap, liveItems);
                  rebuildAllItems();
                  skeletonView.stop();
                  skeletonView.setVisibility(View.GONE);
                  updateFollowersCarousel();
                  applyFilter();
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  if (!isFinishing()) {
                      skeletonView.stop();
                      skeletonView.setVisibility(View.GONE);
                  }
              }
          };
          // Realtime window: only the newest page stays live-synced. Anything
          // older is fetched once per page as the user scrolls — see loadMoreOlder().
          notifRef.orderByChild("timestamp").limitToLast(PAGE_SIZE)
              .addValueEventListener(notifListener);
      }

      /** Parses one Firebase snapshot's children into ReelNotifItems, appending to `target`. */
      private void parseSnapshotChildren(DataSnapshot snap, List<ReelNotifItem> target) {
          for (DataSnapshot child : snap.getChildren()) {
              try {
                  ReelNotifItem item = new ReelNotifItem();
                  item.key         = child.getKey();
                  item.type        = val(child, "type");
                  item.title       = val(child, "title");
                  item.body        = val(child, "body");
                  item.senderUid   = val(child, "senderUid");
                  if (item.senderUid == null) item.senderUid = val(child, "from_uid");
                  item.senderName  = val(child, "senderName");
                  if (item.senderName == null) item.senderName = val(child, "from_name");
                  item.senderPhoto = val(child, "senderPhoto");
                  item.reelId      = val(child, "reelId");
                  if (item.reelId == null) item.reelId = val(child, "reel_id");
                  item.reelThumb   = val(child, "reelThumb");

                  // Build title & body from type + message/senderName
                  String message = val(child, "message");
                  String sender  = item.senderName != null ? item.senderName : "Someone";
                  switch (item.type != null ? item.type : "") {
                      case "like":
                          item.title = sender;
                          item.body  = "liked your reel ❤️";
                          break;
                      case "comment":
                          item.title = sender;
                          item.body  = message != null ? message.replace(sender + " commented: ", "") : "commented on your reel 💬";
                          break;
                      case "reply":
                          item.title = sender;
                          item.body  = message != null ? message.replace(sender + " replied: ", "") : "replied to your comment";
                          break;
                      case "repost":
                          item.title = sender;
                          item.body  = "reposted your reel 🔁";
                          break;
                      case "follow":
                          item.title = sender;
                          item.body  = "started following you";
                          break;
                      default:
                          item.title = val(child, "title") != null ? val(child, "title") : sender;
                          item.body  = message != null ? message : val(child, "body");
                  }
                  Long ts = child.child("timestamp").getValue(Long.class);
                  item.timestamp   = ts != null ? ts : 0L;
                  Boolean r = child.child("read").getValue(Boolean.class);
                  item.read        = r != null && r;
                  target.add(item);
              } catch (Exception ignored) {}
          }
      }

      /** Merges the live (realtime) page with every older page fetched so far, deduped by key. */
      private void rebuildAllItems() {
          LinkedHashMap<String, ReelNotifItem> merged = new LinkedHashMap<>();
          for (ReelNotifItem it : liveItems)  if (it.key != null) merged.put(it.key, it);
          for (ReelNotifItem it : olderItems) if (it.key != null && !merged.containsKey(it.key)) merged.put(it.key, it);
          allItems.clear();
          allItems.addAll(merged.values());
          allItems.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
          oldestLoadedTimestamp = allItems.isEmpty() ? Long.MAX_VALUE : allItems.get(allItems.size() - 1).timestamp;
      }

      /** Removes an item from every backing list (live page, older pages, merged view) by identity. */
      private void removeFromBackingLists(ReelNotifItem it) {
          if (it == null) return;
          liveItems.remove(it);
          olderItems.remove(it);
          allItems.remove(it);
      }

      /** Infinite scroll: fetches the next older page once (not live-synced) and appends it. */
      private void loadMoreOlder() {
          if (isLoadingMore || noMoreOlderData || allItems.isEmpty() || myUid == null) return;
          isLoadingMore = true;
          if (footerProgress != null) footerProgress.setVisibility(View.VISIBLE);
          notifRef.orderByChild("timestamp").endAt((double) (oldestLoadedTimestamp - 1)).limitToLast(PAGE_SIZE)
              .get()
              .addOnSuccessListener(snap -> {
                  isLoadingMore = false;
                  if (isFinishing() || isDestroyed()) return;
                  if (footerProgress != null) footerProgress.setVisibility(View.GONE);
                  List<ReelNotifItem> batch = new ArrayList<>();
                  parseSnapshotChildren(snap, batch);
                  noMoreOlderData = batch.size() < PAGE_SIZE;
                  olderItems.addAll(batch);
                  rebuildAllItems();
                  applyFilter();
              })
              .addOnFailureListener(e -> {
                  isLoadingMore = false;
                  if (footerProgress != null) footerProgress.setVisibility(View.GONE);
              });
      }

      private String val(DataSnapshot snap, String key) {
          Object v = snap.child(key).getValue();
          return v != null ? v.toString() : null;
      }

      // ─────────────────────────────────────────────────────────────────────────
      // "New followers" carousel
      // ─────────────────────────────────────────────────────────────────────────
      private void updateFollowersCarousel() {
          LinkedHashMap<String, ReelNotifItem> recent = new LinkedHashMap<>();
          for (ReelNotifItem item : allItems) {
              if (!"follow".equals(item.type)) continue;
              if (item.senderUid == null || item.senderUid.isEmpty()) continue;
              if (recent.containsKey(item.senderUid)) continue;
              recent.put(item.senderUid, item);
              if (recent.size() >= 15) break; // allItems is already newest-first
          }
          followersRow.removeAllViews();
          if (recent.isEmpty()) {
              followersSection.setVisibility(View.GONE);
              return;
          }
          followersSection.setVisibility(View.VISIBLE);
          for (ReelNotifItem f : recent.values()) followersRow.addView(buildFollowerChip(f));
      }

      private View buildFollowerChip(ReelNotifItem f) {
          LinearLayout col = new LinearLayout(this);
          col.setOrientation(LinearLayout.VERTICAL);
          col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
          LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
              dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
          colLp.setMarginEnd(dp(10));
          col.setLayoutParams(colLp);

          CircleImageView av = new CircleImageView(this);
          av.setImageResource(R.drawable.ic_person);
          if (f.senderPhoto != null && !f.senderPhoto.isEmpty()) {
              com.callx.app.followers.FollowAvatarBinder.bind(this, av, f.senderPhoto, 0L, R.drawable.ic_person);
          }
          col.addView(av, new LinearLayout.LayoutParams(dp(56), dp(56)));

          TextView name = new TextView(this);
          name.setText(f.senderName != null ? f.senderName : "");
          name.setTextColor(0xFFCCCCCC);
          name.setTextSize(11);
          name.setMaxLines(1);
          name.setEllipsize(android.text.TextUtils.TruncateAt.END);
          name.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
          LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
              dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
          nameLp.topMargin = dp(4);
          col.addView(name, nameLp);

          col.setOnClickListener(v -> {
              if (f.senderUid == null || f.senderUid.isEmpty()) return;
              Intent p = new Intent(this, com.callx.app.profile.UserReelsActivity.class);
              p.putExtra("uid",   f.senderUid);
              p.putExtra("name",  f.senderName  != null ? f.senderName  : "");
              p.putExtra("photo", f.senderPhoto != null ? f.senderPhoto : "");
              startActivity(p);
          });
          return col;
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Follow Back / Following pill — shared styling for the inline button on
      // "follow" rows (mirrors FollowButtonStyler's look used elsewhere in the app).
      // ─────────────────────────────────────────────────────────────────────────
      private void styleFollowBtn(Button btn, boolean isFollowing) {
          btn.setText(isFollowing ? "Following" : "Follow Back");
          float r = 14f * getResources().getDisplayMetrics().density;
          android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
          bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
          bg.setCornerRadius(r);
          int primary = com.callx.app.utils.FollowButtonStyler.primaryColor(this);
          if (isFollowing) {
              bg.setColor(0xFF2A2A2A);
              bg.setStroke(0, 0xFF2A2A2A);
              btn.setTextColor(0xFFFFFFFF);
          } else {
              bg.setColor(primary);
              bg.setStroke(0, primary);
              btn.setTextColor(com.callx.app.utils.FollowButtonStyler.textColor(this));
          }
          btn.setBackground(bg);
      }

      private String typeEmoji(String type) {
          if (type == null) return "🔔";
          switch (type) {
              case "like":              return "❤️";
              case "comment":
              case "reply":             return "💬";
              case "follow":            return "👤";
              case "mention":           return "📌";
              case "share":             return "↗️";
              case "repost":            return "🔁";
              case "challenge_update":  return "🏆";
              case "reel_recommended":  return "▶️";
              case "product_tag_sale":  return "🛍️";
              default:                  return "🔔";
          }
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Inline reel preview — tapping a like/comment row's thumbnail plays the
      // reel in a small modal (Media3 ExoPlayer) instead of jumping straight
      // into the full-screen player.
      // ─────────────────────────────────────────────────────────────────────────
      private void showReelPreview(ReelNotifItem item) {
          if (item.reelId == null || item.reelId.isEmpty() || isFinishing()) return;

          android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
          FrameLayout container = new FrameLayout(this);
          container.setBackgroundColor(0xFF000000);

          androidx.media3.ui.PlayerView playerView = new androidx.media3.ui.PlayerView(this);
          playerView.setUseController(false);
          container.addView(playerView, new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

          ProgressBar loading = new ProgressBar(this);
          FrameLayout.LayoutParams loadingLp = new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
          loadingLp.gravity = android.view.Gravity.CENTER;
          container.addView(loading, loadingLp);

          ImageButton btnClose = new ImageButton(this);
          btnClose.setImageResource(R.drawable.ic_close);
          btnClose.setBackground(null);
          com.callx.app.utils.IconResolver.tintExistingOnMedia(btnClose);
          FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(40), dp(40));
          closeLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
          closeLp.setMargins(0, dp(24), dp(12), 0);
          container.addView(btnClose, closeLp);

          TextView btnOpenFull = new TextView(this);
          btnOpenFull.setText("View full reel  ▸");
          btnOpenFull.setTextColor(0xFFFFFFFF);
          btnOpenFull.setTextSize(13);
          btnOpenFull.setBackgroundColor(0x99000000);
          btnOpenFull.setPadding(dp(18), dp(10), dp(18), dp(10));
          FrameLayout.LayoutParams openLp = new FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
          openLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
          openLp.setMargins(0, 0, 0, dp(32));
          container.addView(btnOpenFull, openLp);

          dialog.setContentView(container);

          androidx.media3.exoplayer.ExoPlayer player =
              new androidx.media3.exoplayer.ExoPlayer.Builder(this).build();
          playerView.setPlayer(player);
          player.setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_ONE);

          com.google.firebase.database.FirebaseDatabase.getInstance()
              .getReference("reels").child(item.reelId).child("videoUrl")
              .get().addOnSuccessListener(snap -> {
                  loading.setVisibility(View.GONE);
                  String url = snap.getValue(String.class);
                  if (url != null && !url.isEmpty() && !isFinishing()) {
                      player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url));
                      player.prepare();
                      player.setPlayWhenReady(true);
                  }
              }).addOnFailureListener(e -> loading.setVisibility(View.GONE));

          btnClose.setOnClickListener(v -> dialog.dismiss());
          btnOpenFull.setOnClickListener(v -> {
              dialog.dismiss();
              Intent i = new Intent(ReelNotificationsActivity.this, SingleReelPlayerActivity.class);
              i.putExtra("reel_id", item.reelId);
              startActivity(i);
          });
          dialog.setOnDismissListener(d -> player.release());
          dialog.show();
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Filtering
      // ─────────────────────────────────────────────────────────────────────────
      private void applyFilter() {
          String query = etSearch != null ? etSearch.getText().toString().trim() : "";
          List<ReelNotifItem> rawMatched = new ArrayList<>();
          for (ReelNotifItem item : allItems) {
              boolean typeMatch = (currentFilter == null || currentFilter.equals(item.type));
              boolean notMuted  = item.senderUid == null || !mutedSenders.contains(item.senderUid);
              boolean searchMatch = query.isEmpty()
                  || (item.title != null && item.title.toLowerCase().contains(query.toLowerCase()))
                  || (item.body  != null && item.body.toLowerCase().contains(query.toLowerCase()))
                  || (item.senderName != null && item.senderName.toLowerCase().contains(query.toLowerCase()));
              if (typeMatch && notMuted && searchMatch) rawMatched.add(item);
          }

          filteredItems.clear();
          filteredItems.addAll(buildGroupedWithHeaders(rawMatched));
          adapter.submitList(filteredItems);

          boolean hasContent = false;
          for (ReelNotifItem it : filteredItems) { if (!it.isHeader) { hasContent = true; break; } }
          tvEmpty.setVisibility(hasContent ? View.GONE : View.VISIBLE);
          rv.setVisibility(hasContent ? View.VISIBLE : View.GONE);
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Instagram-style aggregation: fold same-reel like/comment rows into one
      // "SenderA, SenderB and N others liked your reel" row, then stamp
      // Today / This Week / Earlier section headers over the result.
      // ─────────────────────────────────────────────────────────────────────────
      private List<ReelNotifItem> buildGroupedWithHeaders(List<ReelNotifItem> raw) {
          LinkedHashMap<String, ReelNotifItem> groupMap = new LinkedHashMap<>();
          List<ReelNotifItem> ordered = new ArrayList<>();

          for (ReelNotifItem item : raw) {
              boolean groupable = ("like".equals(item.type) || "comment".equals(item.type))
                      && item.reelId != null && !item.reelId.isEmpty();
              if (!groupable) { ordered.add(item); continue; }

              String gk = item.type + "_" + item.reelId;
              ReelNotifItem g = groupMap.get(gk);
              if (g == null) {
                  g = new ReelNotifItem();
                  g.type = item.type;
                  g.reelId = item.reelId;
                  g.groupMembers = new ArrayList<>();
                  g.read = true;
                  groupMap.put(gk, g);
                  ordered.add(g);
              }
              g.groupMembers.add(item);
              g.timestamp = Math.max(g.timestamp, item.timestamp);
              if (!item.read) g.read = false;
              if (item.reelThumb != null && !item.reelThumb.isEmpty()) g.reelThumb = item.reelThumb;
          }

          // Turn each group's raw members into the aggregated title/body text.
          for (ReelNotifItem g : ordered) {
              if (g.groupMembers == null || g.groupMembers.isEmpty()) continue;
              List<String> names = new ArrayList<>();
              for (ReelNotifItem m : g.groupMembers) {
                  String n = m.senderName != null ? m.senderName : "Someone";
                  if (!names.contains(n)) names.add(n);
              }
              ReelNotifItem first = g.groupMembers.get(0);
              g.senderUid   = first.senderUid;
              g.senderPhoto = first.senderPhoto;
              g.key         = first.key; // used only as a stable RecyclerView item id

              // Overlapping avatar stack: up to 3 distinct senders' photos,
              // newest-first (groupMembers is already newest-first), dedup
              // by uid so the same person liking + re-liking doesn't repeat.
              g.groupAvatarPhotos = new ArrayList<>();
              Set<String> seenUids = new HashSet<>();
              for (ReelNotifItem m : g.groupMembers) {
                  if (m.senderUid == null || !seenUids.add(m.senderUid)) continue;
                  if (m.senderPhoto != null && !m.senderPhoto.isEmpty()) {
                      g.groupAvatarPhotos.add(m.senderPhoto);
                  }
                  if (g.groupAvatarPhotos.size() >= 3) break;
              }

              String verb = "like".equals(g.type) ? "liked your reel ❤️" : "commented on your reel 💬";
              ReelNotifItem mostRecent = g.groupMembers.get(0); // raw list is already newest-first
              String recentCommentText = null;
              if ("comment".equals(g.type) && mostRecent.body != null
                      && !mostRecent.body.equals("commented on your reel 💬")) {
                  recentCommentText = mostRecent.body;
              }

              if (names.size() == 1) {
                  g.title = names.get(0);
                  g.body  = verb;
              } else if (names.size() == 2) {
                  g.title = names.get(0) + " and " + names.get(1);
                  g.body  = verb;
              } else if (recentCommentText != null) {
                  // 3+ comments: show the latest one's actual text, not just a count.
                  String trimmed = recentCommentText.length() > 60
                      ? recentCommentText.substring(0, 60) + "…" : recentCommentText;
                  g.title = names.get(0) + ", " + names.get(1);
                  g.body  = "and " + (names.size() - 2) + " others — \"" + trimmed + "\"";
              } else {
                  g.title = names.get(0) + ", " + names.get(1);
                  g.body  = "and " + (names.size() - 2) + " others " + verb;
              }
          }

          ordered.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

          List<ReelNotifItem> withHeaders = new ArrayList<>();
          String lastBucket = null;
          for (ReelNotifItem item : ordered) {
              String bucket = timeBucket(item.timestamp);
              if (!bucket.equals(lastBucket)) {
                  ReelNotifItem header = new ReelNotifItem();
                  header.isHeader = true;
                  header.headerLabel = bucket;
                  withHeaders.add(header);
                  lastBucket = bucket;
              }
              withHeaders.add(item);
          }
          return withHeaders;
      }

      private String timeBucket(long ts) {
          if (ts == 0) return "Earlier";
          Calendar now  = Calendar.getInstance();
          Calendar then = Calendar.getInstance();
          then.setTimeInMillis(ts);
          boolean sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                  && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
          if (sameDay) return "Today";
          if (System.currentTimeMillis() - ts < 7L * 86400000L) return "This Week";
          return "Earlier";
      }

      private void applySearch(String query) { applyFilter(); }

      private void updateTabSelection(int selectedIdx) {
          for (int i = 0; i < tabsContainer.getChildCount(); i++) {
              View chip = tabsContainer.getChildAt(i);
              if (chip instanceof TextView) {
                  boolean sel = (i == selectedIdx);
                  ((TextView)chip).setTextColor(sel ? 0xFFFFFFFF : 0xFF888888);
                  chip.setBackground(getDrawable(sel
                      ? R.drawable.bg_unread_badge
                      : android.R.drawable.list_selector_background));
              }
          }
      }

      private void markAllRead() {
          // PERF: one multi-path update instead of a setValue() per unread
          // notification (was up to PAGE_SIZE separate Firebase writes) —
          // Instagram batches its "mark all read" the same way.
          Map<String, Object> updates = new HashMap<>();
          for (ReelNotifItem item : allItems) {
              if (!item.read && item.key != null) updates.put(item.key + "/read", true);
              item.read = true;
          }
          if (!updates.isEmpty()) notifRef.updateChildren(updates);
          // PERF: items are mutated by reference (same as follow-state
          // toggles), so re-diffing them against themselves would be a
          // no-op — go straight to a payload-only partial rebind instead
          // of notifyDataSetChanged(), same as
          // UserListAdapter.refreshAllFollowStates().
          adapter.refreshAllReadStates();
      }

      private void clearAll() {
          allItems.clear();
          liveItems.clear();
          olderItems.clear();
          filteredItems.clear();
          noMoreOlderData = true;
          oldestLoadedTimestamp = Long.MAX_VALUE;
          adapter.submitList(filteredItems);
          notifRef.removeValue();
          tvEmpty.setVisibility(View.VISIBLE);
          rv.setVisibility(View.GONE);
      }

      @Override protected void onDestroy() {
          if (notifRef != null && notifListener != null)
              notifRef.removeEventListener(notifListener);
          super.onDestroy();
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Adapter
      //
      // PERF (reused from FollowConnectionsActivity/UserListAdapter's diffing
      // pass — see its class doc for the original writeup):
      //  ✅ AsyncListDiffer instead of raw notifyDataSetChanged() — every
      //     filter tap, search keystroke, mark-all-read, and page load used
      //     to force-rebind (and Glide re-decode) every visible row; the
      //     diff now runs off the main thread and only touches rows whose
      //     content actually changed.
      //  ✅ Payload-based partial bind for "read state only" changes (mark
      //     all as read) — skips avatar reload, title/body rebind and
      //     listener reattachment; only the unread dot + row tint repaint.
      //  ✅ In-place field mutations that don't change list shape (tap to
      //     mark one row read, swipe to follow back) still go through a
      //     direct notifyItemChanged(pos, payload) instead of the differ —
      //     same reasoning as UserListAdapter.notifyFollowStateChanged():
      //     the item is mutated by reference, so re-diffing it against
      //     itself would be a no-op.
      // ─────────────────────────────────────────────────────────────────────────
      class ReelNotifAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

          private static final int TYPE_HEADER = 0;
          private static final int TYPE_ITEM   = 1;

          /** Payload marker for a "read state only" partial rebind. */
          private static final String PAYLOAD_READ_STATE = "read_state";

          private final AsyncListDiffer<ReelNotifItem> differ =
              new AsyncListDiffer<>(this, new DiffUtil.ItemCallback<ReelNotifItem>() {
                  @Override
                  public boolean areItemsTheSame(@NonNull ReelNotifItem a, @NonNull ReelNotifItem b) {
                      if (a.isHeader || b.isHeader) {
                          return a.isHeader == b.isHeader && Objects.equals(a.headerLabel, b.headerLabel);
                      }
                      return a.key != null && a.key.equals(b.key);
                  }

                  @Override
                  public boolean areContentsTheSame(@NonNull ReelNotifItem a, @NonNull ReelNotifItem b) {
                      if (a.isHeader) return Objects.equals(a.headerLabel, b.headerLabel);
                      return a.read == b.read
                          && Objects.equals(a.title, b.title)
                          && Objects.equals(a.body, b.body)
                          && Objects.equals(a.senderPhoto, b.senderPhoto)
                          && Objects.equals(a.reelThumb, b.reelThumb)
                          && Objects.equals(a.followingSender, b.followingSender)
                          && Objects.equals(a.groupAvatarPhotos, b.groupAvatarPhotos)
                          && a.timestamp == b.timestamp;
                  }

                  @Override
                  public Object getChangePayload(@NonNull ReelNotifItem a, @NonNull ReelNotifItem b) {
                      // Only the read flag flipped — skip avatar reload/text
                      // rebind, just repaint the dot + row tint.
                      boolean onlyReadChanged = a.read != b.read
                          && Objects.equals(a.title, b.title)
                          && Objects.equals(a.body, b.body)
                          && Objects.equals(a.senderPhoto, b.senderPhoto)
                          && Objects.equals(a.reelThumb, b.reelThumb)
                          && Objects.equals(a.followingSender, b.followingSender)
                          && Objects.equals(a.groupAvatarPhotos, b.groupAvatarPhotos);
                      return onlyReadChanged ? PAYLOAD_READ_STATE : null;
                  }
              });

          /** Diff-and-dispatch a new filtered/paged list — AsyncListDiffer
           *  diffs it against the current list on a background thread and
           *  dispatches minimal insert/remove/move calls instead of a full
           *  notifyDataSetChanged() rebind. */
          void submitList(List<ReelNotifItem> list) {
              differ.submitList(list != null ? new ArrayList<>(list) : new ArrayList<>());
          }

          ReelNotifItem getItem(int pos) { return differ.getCurrentList().get(pos); }

          /** Read state toggled in bulk elsewhere (mark all as read) — repaint
           *  every row's dot/tint via payload instead of a full rebind. */
          void refreshAllReadStates() {
              notifyItemRangeChanged(0, getItemCount(), PAYLOAD_READ_STATE);
          }

          // Section-header row: just a small label ("Today" / "This Week" / "Earlier").
          class HeaderVH extends RecyclerView.ViewHolder {
              TextView tvLabel;
              HeaderVH(View v) {
                  super(v);
                  tvLabel = v.findViewWithTag("header_label");
              }
          }

          class VH extends RecyclerView.ViewHolder {
              View         dot;
              TextView     tvEmoji, tvTitle, tvBody, tvTime, tvTypeBadge;
              CircleImageView ivAvatar, ivAvatarStack2, ivAvatarStack3;
              ImageView    ivStoryRing;
              ImageView    ivReelThumb;
              Button       btnFollow;
              VH(View v) {
                  super(v);
                  dot      = v.findViewWithTag("dot");
                  tvEmoji  = v.findViewWithTag("emoji");
                  tvTitle  = v.findViewWithTag("title");
                  tvBody   = v.findViewWithTag("body");
                  tvTime   = v.findViewWithTag("time");
                  ivAvatar = v.findViewWithTag("avatar");
                  ivAvatarStack2 = v.findViewWithTag("avatar_stack_2");
                  ivAvatarStack3 = v.findViewWithTag("avatar_stack_3");
                  ivStoryRing = v.findViewWithTag("story_ring");
                  ivReelThumb = v.findViewWithTag("reel_thumb");
                  tvTypeBadge = v.findViewWithTag("type_badge");
                  btnFollow   = v.findViewWithTag("btn_follow");
              }
          }

          @Override public int getItemViewType(int position) {
              return getItem(position).isHeader ? TYPE_HEADER : TYPE_ITEM;
          }

          @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
              if (viewType == TYPE_HEADER) {
                  LinearLayout headerRow = new LinearLayout(ReelNotificationsActivity.this);
                  headerRow.setOrientation(LinearLayout.HORIZONTAL);
                  headerRow.setBackgroundColor(0xFF111111);
                  headerRow.setPadding(dp(16), dp(14), dp(16), dp(6));

                  TextView tvLabel = new TextView(ReelNotificationsActivity.this);
                  tvLabel.setTag("header_label");
                  tvLabel.setTextColor(0xFFFFFFFF);
                  tvLabel.setTextSize(15);
                  tvLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                  headerRow.addView(tvLabel);
                  return new HeaderVH(headerRow);
              }
              return new VH(buildItemRow());
          }

          private LinearLayout buildItemRow() {
              LinearLayout row = new LinearLayout(ReelNotificationsActivity.this);
              row.setOrientation(LinearLayout.HORIZONTAL);
              row.setGravity(android.view.Gravity.CENTER_VERTICAL);
              row.setPadding(dp(12), dp(12), dp(16), dp(12));
              row.setBackground(getDrawable(android.R.drawable.list_selector_background));

              View dot = new View(ReelNotificationsActivity.this);
              dot.setTag("dot");
              dot.setBackgroundColor(0xFFFF3B5C);
              LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
              dotLp.setMarginEnd(dp(8));
              row.addView(dot, dotLp);

              // Avatar + story ring — same gradient/seen/hidden ring
              // HomeFragment's feed post avatar and Stories tray already
              // use, wrapped here since this row is built in code rather
              // than XML — see StoryRingApplier.
              // Widened to fit the overlapping 3-avatar stack (Instagram-style
              // "liked by A, B and N others" rows) — single-sender rows just
              // use the first slot and leave the rest GONE.
              FrameLayout avatarWrap = new FrameLayout(ReelNotificationsActivity.this);
              LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(dp(64), dp(46));
              wrapLp.setMarginEnd(dp(10));
              row.addView(avatarWrap, wrapLp);

              ImageView storyRing = new ImageView(ReelNotificationsActivity.this);
              storyRing.setTag("story_ring");
              storyRing.setVisibility(View.GONE);
              FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(dp(46), dp(46));
              ringLp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
              avatarWrap.addView(storyRing, ringLp);

              CircleImageView avatar = new CircleImageView(ReelNotificationsActivity.this);
              avatar.setTag("avatar");
              FrameLayout.LayoutParams avLp = new FrameLayout.LayoutParams(dp(40), dp(40));
              avLp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
              avatar.setImageResource(R.drawable.ic_person);
              avatarWrap.addView(avatar, avLp);

              // Stack slots 2 & 3 — GONE until a grouped row (2+ distinct
              // senders) binds them. A white border creates the classic
              // overlapping-circle separation against the sibling avatar.
              CircleImageView avatarStack2 = new CircleImageView(ReelNotificationsActivity.this);
              avatarStack2.setTag("avatar_stack_2");
              avatarStack2.setBorderWidth(dp(2));
              avatarStack2.setBorderColor(0xFF111111);
              avatarStack2.setVisibility(View.GONE);
              FrameLayout.LayoutParams stack2Lp = new FrameLayout.LayoutParams(dp(32), dp(32));
              stack2Lp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
              stack2Lp.setMarginStart(dp(20));
              avatarWrap.addView(avatarStack2, stack2Lp);

              CircleImageView avatarStack3 = new CircleImageView(ReelNotificationsActivity.this);
              avatarStack3.setTag("avatar_stack_3");
              avatarStack3.setBorderWidth(dp(2));
              avatarStack3.setBorderColor(0xFF111111);
              avatarStack3.setVisibility(View.GONE);
              FrameLayout.LayoutParams stack3Lp = new FrameLayout.LayoutParams(dp(32), dp(32));
              stack3Lp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
              stack3Lp.setMarginStart(dp(38));
              avatarWrap.addView(avatarStack3, stack3Lp);

              // Small type badge (❤️/💬/👤/…) pinned to the avatar's corner —
              // same idea as Instagram's per-row activity-type icon.
              TextView typeBadge = new TextView(ReelNotificationsActivity.this);
              typeBadge.setTag("type_badge");
              typeBadge.setTextSize(9);
              typeBadge.setGravity(android.view.Gravity.CENTER);
              android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
              badgeBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
              badgeBg.setColor(0xFF1A1A1A);
              badgeBg.setStroke(dp(1), 0xFF111111);
              typeBadge.setBackground(badgeBg);
              FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp(16), dp(16));
              badgeLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
              badgeLp.setMarginStart(dp(24));
              avatarWrap.addView(typeBadge, badgeLp);

              LinearLayout col = new LinearLayout(ReelNotificationsActivity.this);
              col.setOrientation(LinearLayout.VERTICAL);
              col.setLayoutParams(new LinearLayout.LayoutParams(0,
                  ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

              TextView tvTitle = new TextView(ReelNotificationsActivity.this);
              tvTitle.setTag("title");
              tvTitle.setTextColor(0xFFFFFFFF);
              tvTitle.setTextSize(13);
              tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
              tvTitle.setMaxLines(1);
              tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
              col.addView(tvTitle);

              TextView tvBody = new TextView(ReelNotificationsActivity.this);
              tvBody.setTag("body");
              tvBody.setTextColor(0xFFBBBBBB);
              tvBody.setTextSize(12);
              tvBody.setMaxLines(2);
              tvBody.setEllipsize(android.text.TextUtils.TruncateAt.END);
              col.addView(tvBody);

              TextView tvTime = new TextView(ReelNotificationsActivity.this);
              tvTime.setTag("time");
              tvTime.setTextColor(0xFF666666);
              tvTime.setTextSize(11);
              col.addView(tvTime);

              row.addView(col);

              // Follow Back / Following — only shown & populated for "follow" rows.
              Button btnFollow = new Button(ReelNotificationsActivity.this);
              btnFollow.setTag("btn_follow");
              btnFollow.setTextSize(11);
              btnFollow.setAllCaps(false);
              btnFollow.setMinWidth(0);
              btnFollow.setMinimumWidth(0);
              btnFollow.setPadding(dp(14), 0, dp(14), 0);
              btnFollow.setVisibility(View.GONE);
              LinearLayout.LayoutParams followLp = new LinearLayout.LayoutParams(
                  ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
              followLp.setMarginStart(dp(8));
              row.addView(btnFollow, followLp);

              // Reel thumbnail (like Instagram shows the liked/commented-on post
              // thumbnail on the right) — only populated/shown for like & comment rows.
              ImageView reelThumb = new ImageView(ReelNotificationsActivity.this);
              reelThumb.setTag("reel_thumb");
              reelThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
              reelThumb.setVisibility(View.GONE);
              LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(40), dp(48));
              thumbLp.setMarginStart(dp(8));
              row.addView(reelThumb, thumbLp);

              return row;
          }

          /** Payload-aware partial bind — PERF: when only the read state
           *  changed (mark all as read), skip the full bind (avatar Glide
           *  load, follow-state Firebase check, title/body/time rebind,
           *  listener reattachment) and touch only the dot + row tint.
           *  Falls back to a full bind for any other payload or a cold bind. */
          @Override
          public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos, @NonNull List<Object> payloads) {
              if (!payloads.isEmpty() && payloads.contains(PAYLOAD_READ_STATE) && holder instanceof VH) {
                  ReelNotifItem item = getItem(pos);
                  VH h = (VH) holder;
                  android.graphics.drawable.Drawable rowRipple =
                      getDrawable(android.R.drawable.list_selector_background);
                  android.graphics.drawable.ColorDrawable rowTint =
                      new android.graphics.drawable.ColorDrawable(item.read ? 0x00000000 : 0xFF191922);
                  h.itemView.setBackground(new android.graphics.drawable.LayerDrawable(
                      new android.graphics.drawable.Drawable[] { rowTint, rowRipple }));
                  h.dot.setVisibility(item.read ? View.INVISIBLE : View.VISIBLE);
                  return;
              }
              super.onBindViewHolder(holder, pos, payloads);
          }

          @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int pos) {
              ReelNotifItem item = getItem(pos);

              if (holder instanceof HeaderVH) {
                  ((HeaderVH) holder).tvLabel.setText(item.headerLabel);
                  return;
              }
              VH h = (VH) holder;

              // Unread visual weight — unread rows get a subtle background
              // tint (in addition to the small dot); read rows stay plain.
              // Layered so the existing press ripple still shows on top.
              android.graphics.drawable.Drawable rowRipple =
                  getDrawable(android.R.drawable.list_selector_background);
              android.graphics.drawable.ColorDrawable rowTint =
                  new android.graphics.drawable.ColorDrawable(item.read ? 0x00000000 : 0xFF191922);
              h.itemView.setBackground(new android.graphics.drawable.LayerDrawable(
                  new android.graphics.drawable.Drawable[] { rowTint, rowRipple }));

              // Unread dot
              h.dot.setVisibility(item.read ? View.INVISIBLE : View.VISIBLE);

              // Avatar — grouped rows with 2+ distinct senders get an
              // Instagram-style overlapping avatar stack instead of a single
              // photo + "and N others" text; everything else keeps the
              // original single-avatar + story-ring treatment.
              boolean stackMode = item.groupAvatarPhotos != null && item.groupAvatarPhotos.size() > 1;
              if (stackMode) {
                  h.ivStoryRing.setVisibility(View.GONE);
                  com.bumptech.glide.Glide.with(ReelNotificationsActivity.this)
                      .load(item.groupAvatarPhotos.get(0))
                      .placeholder(R.drawable.ic_person)
                      .into(h.ivAvatar);
                  h.ivAvatarStack2.setVisibility(View.VISIBLE);
                  com.bumptech.glide.Glide.with(ReelNotificationsActivity.this)
                      .load(item.groupAvatarPhotos.get(1))
                      .placeholder(R.drawable.ic_person)
                      .into(h.ivAvatarStack2);
                  if (item.groupAvatarPhotos.size() >= 3) {
                      h.ivAvatarStack3.setVisibility(View.VISIBLE);
                      com.bumptech.glide.Glide.with(ReelNotificationsActivity.this)
                          .load(item.groupAvatarPhotos.get(2))
                          .placeholder(R.drawable.ic_person)
                          .into(h.ivAvatarStack3);
                  } else {
                      h.ivAvatarStack3.setVisibility(View.GONE);
                      com.bumptech.glide.Glide.with(ReelNotificationsActivity.this).clear(h.ivAvatarStack3);
                  }
              } else {
                  h.ivAvatarStack2.setVisibility(View.GONE);
                  h.ivAvatarStack3.setVisibility(View.GONE);
                  com.bumptech.glide.Glide.with(ReelNotificationsActivity.this).clear(h.ivAvatarStack2);
                  com.bumptech.glide.Glide.with(ReelNotificationsActivity.this).clear(h.ivAvatarStack3);

                  // Avatar — use senderPhoto if available, else fetch thumbUrl from Firebase.
                  // FIX (avatar pipeline parity): shared L2/L3 cache + density-aware tier decode instead of a flat Glide load — see FollowAvatarBinder.
                  if (item.senderPhoto != null && !item.senderPhoto.isEmpty()) {
                      com.callx.app.followers.FollowAvatarBinder.bind(
                          ReelNotificationsActivity.this, h.ivAvatar, item.senderPhoto, 0L, R.drawable.ic_person);
                  } else if (item.senderUid != null && !item.senderUid.isEmpty()) {
                      h.ivAvatar.setImageResource(R.drawable.ic_person);
                      // Sender ka Reels profile avatar load karo (reels/users/{uid})
                      com.google.firebase.database.FirebaseDatabase.getInstance()
                          .getReference("reels/users").child(item.senderUid)
                          .get().addOnSuccessListener(snap -> {
                              String thumb = snap.child("thumbUrl").getValue(String.class);
                              String photo = snap.child("photoUrl").getValue(String.class);
                              String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                              if (url != null && !url.isEmpty() && !isFinishing()) {
                                  item.senderPhoto = url;
                                  com.callx.app.followers.FollowAvatarBinder.bind(
                                      ReelNotificationsActivity.this, h.ivAvatar, url, 0L, R.drawable.ic_person);
                              }
                          });
                  } else {
                      h.ivAvatar.setImageResource(R.drawable.ic_person);
                  }

                  // Same gradient/seen/hidden story ring HomeFragment's feed post
                  // avatar and Stories tray already use — see StoryRingApplier.
                  com.callx.app.utils.StoryRingApplier.applyWithClick(ReelNotificationsActivity.this, h.ivStoryRing, item.senderUid);
              }

              // Title & body
              h.tvTitle.setText(item.title != null ? item.title : "Reel Activity");
              h.tvBody.setText(item.body != null ? item.body : "");

              // Type badge (❤️/💬/👤/…) on the avatar corner
              h.tvTypeBadge.setText(typeEmoji(item.type));

              // Time
              h.tvTime.setText(relativeTime(item.timestamp));

              // Follow Back / Following — only on "follow" rows, and never for
              // a stray self-follow notification.
              boolean isFollowRow = "follow".equals(item.type)
                      && item.senderUid != null && !item.senderUid.isEmpty()
                      && !item.senderUid.equals(myUid);
              if (isFollowRow) {
                  h.btnFollow.setVisibility(View.VISIBLE);
                  if (item.followingSender == null) {
                      styleFollowBtn(h.btnFollow, false);
                      FirebaseUtils.getReelFollowsRef(myUid).child(item.senderUid).get()
                          .addOnSuccessListener(snap -> {
                              Boolean v = snap.getValue(Boolean.class);
                              item.followingSender = v != null && v;
                              if (!isFinishing()) styleFollowBtn(h.btnFollow, item.followingSender);
                          });
                  } else {
                      styleFollowBtn(h.btnFollow, item.followingSender);
                  }
                  h.btnFollow.setOnClickListener(v -> {
                      String targetUid = item.senderUid;
                      boolean newState = item.followingSender == null || !item.followingSender;
                      item.followingSender = newState;
                      DatabaseReference ref = FirebaseUtils.getReelFollowsRef(myUid).child(targetUid);
                      DatabaseReference followerRef = FirebaseUtils.getReelFollowersRef(targetUid).child(myUid);
                      if (newState) { ref.setValue(true); followerRef.setValue(true); }
                      else          { ref.removeValue();  followerRef.removeValue(); }
                      styleFollowBtn(h.btnFollow, newState);
                  });
              } else {
                  h.btnFollow.setVisibility(View.GONE);
                  h.btnFollow.setOnClickListener(null);
              }

              // Reel thumbnail — like/comment rows only (Instagram shows the
              // liked/commented-on post next to the notification text).
              boolean showThumb = ("like".equals(item.type) || "comment".equals(item.type))
                      && item.reelThumb != null && !item.reelThumb.isEmpty();
              if (showThumb) {
                  h.ivReelThumb.setVisibility(View.VISIBLE);
                  com.bumptech.glide.Glide.with(ReelNotificationsActivity.this)
                      .load(item.reelThumb)
                      .placeholder(R.drawable.ic_person)
                      .into(h.ivReelThumb);
                  h.ivReelThumb.setOnClickListener(v -> showReelPreview(item));
              } else {
                  h.ivReelThumb.setVisibility(View.GONE);
                  h.ivReelThumb.setOnClickListener(null);
                  com.bumptech.glide.Glide.with(ReelNotificationsActivity.this).clear(h.ivReelThumb);
              }

              // Avatar click → open sender's reel profile (same as avatar click in reel player)
              h.ivAvatar.setOnClickListener(v -> {
                  if (item.senderUid != null && !item.senderUid.isEmpty()) {
                      Intent p = new Intent(ReelNotificationsActivity.this,
                          com.callx.app.profile.UserReelsActivity.class);
                      p.putExtra("uid",   item.senderUid);
                      p.putExtra("name",  item.senderName  != null ? item.senderName  : "");
                      p.putExtra("photo", item.senderPhoto != null ? item.senderPhoto : "");
                      startActivity(p);
                  }
              });

              // Click → open reel
              h.itemView.setOnClickListener(v -> {
                  if (!item.read) {
                      if (item.groupMembers != null && !item.groupMembers.isEmpty()) {
                          // PERF: single multi-path update for every folded
                          // notification in this group, instead of a
                          // setValue() per raw member.
                          Map<String, Object> updates = new HashMap<>();
                          for (ReelNotifItem m : item.groupMembers) {
                              if (m.key != null) updates.put(m.key + "/read", true);
                              m.read = true;
                          }
                          if (!updates.isEmpty()) notifRef.updateChildren(updates);
                      } else if (item.key != null) {
                          notifRef.child(item.key).child("read").setValue(true);
                      }
                      item.read = true;
                      notifyItemChanged(pos, PAYLOAD_READ_STATE);
                  }
                  if (item.reelId != null && !item.reelId.isEmpty()) {
                      Intent i = new Intent(ReelNotificationsActivity.this,
                          SingleReelPlayerActivity.class);
                      i.putExtra("reel_id", item.reelId);
                      startActivity(i);
                  }
              });

              // Long-press → mute/unmute this sender's activity notifications
              // (row-level quick action, mirrors Instagram's long-press menu).
              h.itemView.setOnLongClickListener(v -> {
                  showMuteMenu(item);
                  return true;
              });
          }

          @Override public int getItemCount() { return differ.getCurrentList().size(); }

          // FIX (lifecycle-aware cancel): stop an in-flight request for a row that just scrolled off screen.
          @Override public void onViewRecycled(RecyclerView.ViewHolder holder) {
              if (holder instanceof VH) {
                  VH h = (VH) holder;
                  com.callx.app.followers.FollowAvatarBinder.cancel(ReelNotificationsActivity.this, h.ivAvatar);
                  com.bumptech.glide.Glide.with(ReelNotificationsActivity.this).clear(h.ivReelThumb);
                  com.bumptech.glide.Glide.with(ReelNotificationsActivity.this).clear(h.ivAvatarStack2);
                  com.bumptech.glide.Glide.with(ReelNotificationsActivity.this).clear(h.ivAvatarStack3);
              }
          }
      }

      private String relativeTime(long ts) {
          if (ts == 0) return "";
          long diff = System.currentTimeMillis() - ts;
          if (diff < 60000)        return "just now";
          if (diff < 3600000)      return (diff / 60000) + "m ago";
          if (diff < 86400000)     return (diff / 3600000) + "h ago";
          if (diff < 604800000)    return (diff / 86400000) + "d ago";
          return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(ts));
      }

      private int dp(int v) {
          return (int)(v * getResources().getDisplayMetrics().density);
      }
  }
  