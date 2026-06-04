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
  import androidx.recyclerview.widget.ItemTouchHelper;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
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
      private ProgressBar   progress;
      private TextView      tvEmpty;
      private LinearLayout  tabsContainer;
      private EditText      etSearch;
      private TextView      btnMarkAll;
      private TextView      btnDeleteAll;
      private ImageButton   btnBack;

      private ReelNotifAdapter adapter;
      private final List<ReelNotifItem> allItems      = new ArrayList<>();
      private final List<ReelNotifItem> filteredItems = new ArrayList<>();

      private String myUid;
      private DatabaseReference notifRef;
      private ValueEventListener notifListener;
      private String currentFilter = null; // null = All

      // ── Data model ─────────────────────────────────────────────────────────
      static class ReelNotifItem {
          String key, type, title, body, senderUid, senderName, senderPhoto;
          String reelId, reelThumb;
          long   timestamp;
          boolean read;
      }

      @Override
      protected void onCreate(Bundle s) {
          super.onCreate(s);
          try { myUid = FirebaseUtils.getCurrentUid(); }
          catch (Exception e) { finish(); return; }

          notifRef = FirebaseUtils.db().getReference("reel_notifications").child(myUid);
          buildLayout();
          loadNotifications();
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
          btnBack.getDrawable().setTint(0xFFFFFFFF);
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

          // Progress
          progress = new ProgressBar(this);
          progress.setVisibility(View.GONE);
          root.addView(progress, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
              android.view.Gravity.CENTER_HORIZONTAL));

          // Empty state
          tvEmpty = new TextView(this);
          tvEmpty.setText("No reel activity yet");
          tvEmpty.setTextColor(0xFF666666);
          tvEmpty.setTextSize(15);
          tvEmpty.setGravity(android.view.Gravity.CENTER);
          tvEmpty.setPadding(0, dp(64), 0, 0);
          tvEmpty.setVisibility(View.GONE);
          root.addView(tvEmpty);

          // RecyclerView
          rv = new RecyclerView(this);
          rv.setLayoutManager(new LinearLayoutManager(this));
          adapter = new ReelNotifAdapter();
          rv.setAdapter(adapter);

          // Swipe-to-delete
          new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
              ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
              @Override public boolean onMove(@NonNull RecyclerView r,
                      @NonNull RecyclerView.ViewHolder h, @NonNull RecyclerView.ViewHolder t) { return false; }
              @Override public void onSwiped(@NonNull RecyclerView.ViewHolder h, int dir) {
                  int pos = h.getAdapterPosition();
                  if (pos >= 0 && pos < filteredItems.size()) {
                      ReelNotifItem item = filteredItems.get(pos);
                      filteredItems.remove(pos);
                      allItems.remove(item);
                      adapter.notifyItemRemoved(pos);
                      if (item.key != null) notifRef.child(item.key).removeValue();
                      if (filteredItems.isEmpty()) {
                          tvEmpty.setVisibility(View.VISIBLE);
                          rv.setVisibility(View.GONE);
                      }
                  }
              }
          }).attachToRecyclerView(rv);

          root.addView(rv, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

          setContentView(root);
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Data loading
      // ─────────────────────────────────────────────────────────────────────────
      private void loadNotifications() {
          progress.setVisibility(View.VISIBLE);
          notifListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (isFinishing() || isDestroyed()) return;
                  allItems.clear();
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
                          allItems.add(item);
                      } catch (Exception ignored) {}
                  }
                  allItems.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                  progress.setVisibility(View.GONE);
                  applyFilter();
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  if (!isFinishing()) progress.setVisibility(View.GONE);
              }
          };
          notifRef.orderByChild("timestamp").limitToLast(300)
              .addValueEventListener(notifListener);
      }

      private String val(DataSnapshot snap, String key) {
          Object v = snap.child(key).getValue();
          return v != null ? v.toString() : null;
      }

      // ─────────────────────────────────────────────────────────────────────────
      // Filtering
      // ─────────────────────────────────────────────────────────────────────────
      private void applyFilter() {
          String query = etSearch != null ? etSearch.getText().toString().trim() : "";
          filteredItems.clear();
          for (ReelNotifItem item : allItems) {
              boolean typeMatch = (currentFilter == null || currentFilter.equals(item.type));
              boolean searchMatch = query.isEmpty()
                  || (item.title != null && item.title.toLowerCase().contains(query.toLowerCase()))
                  || (item.body  != null && item.body.toLowerCase().contains(query.toLowerCase()))
                  || (item.senderName != null && item.senderName.toLowerCase().contains(query.toLowerCase()));
              if (typeMatch && searchMatch) filteredItems.add(item);
          }
          adapter.notifyDataSetChanged();
          tvEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
          rv.setVisibility(filteredItems.isEmpty() ? View.GONE : View.VISIBLE);
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
          for (ReelNotifItem item : allItems) {
              if (!item.read && item.key != null)
                  notifRef.child(item.key).child("read").setValue(true);
              item.read = true;
          }
          adapter.notifyDataSetChanged();
      }

      private void clearAll() {
          allItems.clear();
          filteredItems.clear();
          adapter.notifyDataSetChanged();
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
      // ─────────────────────────────────────────────────────────────────────────
      class ReelNotifAdapter extends RecyclerView.Adapter<ReelNotifAdapter.VH> {

          class VH extends RecyclerView.ViewHolder {
              View         dot;
              TextView     tvEmoji, tvTitle, tvBody, tvTime;
              CircleImageView ivAvatar;
              VH(View v) {
                  super(v);
                  dot      = v.findViewWithTag("dot");
                  tvEmoji  = v.findViewWithTag("emoji");
                  tvTitle  = v.findViewWithTag("title");
                  tvBody   = v.findViewWithTag("body");
                  tvTime   = v.findViewWithTag("time");
                  ivAvatar = v.findViewWithTag("avatar");
              }
          }

          @Override public VH onCreateViewHolder(ViewGroup parent, int viewType) {
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

              CircleImageView avatar = new CircleImageView(ReelNotificationsActivity.this);
              avatar.setTag("avatar");
              LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(40), dp(40));
              avLp.setMarginEnd(dp(10));
              avatar.setImageResource(R.drawable.ic_person);
              row.addView(avatar, avLp);

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
              return new VH(row);
          }

          @Override public void onBindViewHolder(VH h, int pos) {
              ReelNotifItem item = filteredItems.get(pos);

              // Unread dot
              h.dot.setVisibility(item.read ? View.INVISIBLE : View.VISIBLE);

              // Avatar — use senderPhoto if available, else fetch thumbUrl from Firebase
              if (item.senderPhoto != null && !item.senderPhoto.isEmpty()) {
                  Glide.with(ReelNotificationsActivity.this).load(item.senderPhoto)
                      .circleCrop()
                      .placeholder(R.drawable.ic_person).into(h.ivAvatar);
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
                              Glide.with(ReelNotificationsActivity.this).load(url)
                                  .circleCrop()
                                  .placeholder(R.drawable.ic_person).into(h.ivAvatar);
                          }
                      });
              } else {
                  h.ivAvatar.setImageResource(R.drawable.ic_person);
              }

              // Title & body
              h.tvTitle.setText(item.title != null ? item.title : "Reel Activity");
              h.tvBody.setText(item.body != null ? item.body : "");

              // Time
              h.tvTime.setText(relativeTime(item.timestamp));

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
                  if (!item.read && item.key != null) {
                      notifRef.child(item.key).child("read").setValue(true);
                      item.read = true;
                      notifyItemChanged(pos);
                  }
                  if (item.reelId != null && !item.reelId.isEmpty()) {
                      Intent i = new Intent(ReelNotificationsActivity.this,
                          SingleReelPlayerActivity.class);
                      i.putExtra("reel_id", item.reelId);
                      startActivity(i);
                  }
              });
          }

          @Override public int getItemCount() { return filteredItems.size(); }
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
  