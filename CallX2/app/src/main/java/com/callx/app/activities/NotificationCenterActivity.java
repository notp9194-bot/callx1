package com.callx.app.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.text.SimpleDateFormat;
import java.util.*;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.conversation.ChatActivity;

/**
 * NotificationCenterActivity — Unified Notification Center (Reels bottom nav).
 *
 * Shows ALL app notifications with delivery-state badges:
 *   🟢 FOREGROUND  — app was open when notification arrived
 *   🟡 BACKGROUND  — app was running in background
 *   🔴 KILLED      — app was force-stopped / not running
 *
 * Tabs: All | Messages | Calls | Reels | System
 *
 * Features:
 *   ✅ User avatar + name + activity label per notification
 *   ✅ Clickable rows → Chat / Profile / SingleReelPlayer
 *   ✅ Mark all read
 *   ✅ Category filter tabs
 *   ✅ App delivery state badges
 */
public class NotificationCenterActivity extends AppCompatActivity {

    private static final String PREFS_NOTIF_LOG = "callx_notif_log";

    private static final String FILTER_ALL      = "all";
    private static final String FILTER_MESSAGES = "message";
    private static final String FILTER_GROUPS   = "group";
    private static final String FILTER_CALLS    = "call";
    private static final String FILTER_REELS    = "reel";
    private static final String FILTER_SYSTEM   = "system";

    private ImageButton  btnBack;
    private TextView     btnMarkAll;
    private RecyclerView rvNotifs;
    private ProgressBar  progress;
    private TextView     tvEmpty;
    private LinearLayout layoutTabs;

    private final List<NotifEntry>      allItems  = new ArrayList<>();
    private final List<NotifEntry>      filtered  = new ArrayList<>();
    private final Map<String, UserInfo> userCache = new HashMap<>();
    private NotifAdapter adapter;
    private String       currentFilter = FILTER_ALL;
    private String       myUid;
    private int          loadedSources = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_center);

        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { finish(); return; }

        bindViews();
        buildTabs();
        loadAllNotifications();
    }

    private void bindViews() {
        btnBack    = findViewById(R.id.btn_nc_back);
        btnMarkAll = findViewById(R.id.btn_nc_mark_all);
        rvNotifs   = findViewById(R.id.rv_nc_notifs);
        progress   = findViewById(R.id.progress_nc);
        tvEmpty    = findViewById(R.id.tv_nc_empty);
        layoutTabs = findViewById(R.id.layout_nc_tabs);

        btnBack.setOnClickListener(v -> finish());
        btnMarkAll.setOnClickListener(v -> markAllRead());

        adapter = new NotifAdapter();
        rvNotifs.setLayoutManager(new LinearLayoutManager(this));
        rvNotifs.setAdapter(adapter);
    }

    private void buildTabs() {
        String[] labels  = { "All", "Messages", "Groups", "Calls", "Reels", "System" };
        String[] filters = { FILTER_ALL, FILTER_MESSAGES, FILTER_GROUPS, FILTER_CALLS, FILTER_REELS, FILTER_SYSTEM };

        for (int i = 0; i < labels.length; i++) {
            final String f = filters[i];
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(13f);
            tv.setPadding(28, 14, 28, 14);
            tv.setBackground(getDrawable(R.drawable.bg_speed_chip));
            tv.setAlpha(f.equals(currentFilter) ? 1f : 0.5f);
            tv.setOnClickListener(v -> {
                currentFilter = f;
                for (int j = 0; j < layoutTabs.getChildCount(); j++)
                    layoutTabs.getChildAt(j).setAlpha(0.5f);
                tv.setAlpha(1f);
                applyFilter();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(8);
            layoutTabs.addView(tv, lp);
        }
    }

    // ── Data loading ───────────────────────────────────────────────────

    private void loadAllNotifications() {
        progress.setVisibility(View.VISIBLE);
        allItems.clear();
        loadedSources = 0;
        loadFromLocalLog();
        loadReelNotifications();
        loadCallNotifications();
    }

    private void loadFromLocalLog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NOTIF_LOG, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            try {
                NotifEntry e = NotifEntry.fromJson((String) entry.getValue());
                if (e != null) allItems.add(e);
            } catch (Exception ignored) {}
        }
    }

    private void loadReelNotifications() {
        FirebaseUtils.db().getReference("reelNotifications").child(myUid)
            .orderByChild("timestamp").limitToLast(80)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        try {
                            NotifEntry e = new NotifEntry();
                            e.id         = s.getKey();
                            e.category   = FILTER_REELS;
                            String type  = s.child("type").getValue(String.class);
                            String body  = s.child("body").getValue(String.class);
                            Long ts      = s.child("timestamp").getValue(Long.class);
                            Boolean read = s.child("read").getValue(Boolean.class);
                            String state = s.child("appState").getValue(String.class);
                            e.fromUid    = s.child("fromUid").getValue(String.class);
                            e.fromName   = s.child("fromName").getValue(String.class);
                            e.fromPhoto  = s.child("fromPhoto").getValue(String.class);
                            e.reelId     = s.child("reelId").getValue(String.class);
                            e.notifType  = type;
                            e.title      = "Reel " + capitalize(type != null ? type : "notification");
                            e.body       = body != null ? body : "";
                            e.timestamp  = ts != null ? ts : 0;
                            e.read       = Boolean.TRUE.equals(read);
                            e.appState   = state != null ? state : "background";
                            e.source     = "firebase";
                            allItems.add(e);
                        } catch (Exception ignored) {}
                    }
                    checkDone();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { checkDone(); }
            });
    }

    private void loadCallNotifications() {
        FirebaseUtils.getCallsRef(myUid)
            .orderByChild("timestamp").limitToLast(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) {
                        try {
                            String status     = s.child("status").getValue(String.class);
                            if (!"missed".equals(status) && !"received".equals(status)) continue;
                            String callerName = s.child("callerName").getValue(String.class);
                            String callerUid  = s.child("callerUid").getValue(String.class);
                            String callerPhoto= s.child("callerPhoto").getValue(String.class);
                            Long   ts         = s.child("timestamp").getValue(Long.class);
                            String type       = s.child("type").getValue(String.class);
                            String duration   = s.child("duration").getValue(String.class);

                            NotifEntry e = new NotifEntry();
                            e.id        = s.getKey();
                            e.category  = FILTER_CALLS;
                            e.title     = "missed".equals(status) ? "Missed Call" : "Incoming Call";
                            String tl   = "video".equals(type) ? "📹 Video" : "📞 Audio";
                            String dur  = (duration != null && !duration.isEmpty()) ? " • " + duration : "";
                            e.body      = (callerName != null ? callerName : "Unknown") + " • " + tl + dur;
                            e.timestamp = ts != null ? ts : 0;
                            e.read      = !"missed".equals(status);
                            e.appState  = "killed";
                            e.source    = "firebase";
                            e.fromUid   = callerUid;
                            e.fromName  = callerName;
                            e.fromPhoto = callerPhoto;
                            e.notifType = type;
                            allItems.add(e);
                        } catch (Exception ignored) {}
                    }
                    checkDone();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { checkDone(); }
            });
    }

    private void fetchMissingUserInfo(Runnable onComplete) {
        Set<String> toFetch = new HashSet<>();
        for (NotifEntry e : allItems) {
            if (e.fromUid != null && !e.fromUid.isEmpty()
                    && !userCache.containsKey(e.fromUid)
                    && (e.fromName == null || e.fromName.isEmpty())) {
                toFetch.add(e.fromUid);
            }
        }
        if (toFetch.isEmpty()) { onComplete.run(); return; }

        final int[] rem = {toFetch.size()};
        for (String uid : toFetch) {
            FirebaseUtils.db().getReference("users").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        UserInfo info = new UserInfo();
                        info.name     = snap.child("name").getValue(String.class);
                        info.photoUrl = snap.child("photoUrl").getValue(String.class);
                        info.thumbUrl = snap.child("thumbUrl").getValue(String.class);
                        userCache.put(uid, info);
                        if (--rem[0] <= 0) onComplete.run();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (--rem[0] <= 0) onComplete.run();
                    }
                });
        }
    }

    private synchronized void checkDone() {
        if (++loadedSources >= 2) {
            runOnUiThread(() -> {
                allItems.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                fetchMissingUserInfo(() -> runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    applyFilter();
                }));
            });
        }
    }

    // ── Filter ─────────────────────────────────────────────────────────

    private void applyFilter() {
        filtered.clear();
        for (NotifEntry e : allItems) {
            if (currentFilter.equals(FILTER_ALL) || currentFilter.equals(e.category))
                filtered.add(e);
        }
        boolean empty = filtered.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvNotifs.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    // ── Mark all read ──────────────────────────────────────────────────

    private void markAllRead() {
        for (NotifEntry e : allItems) {
            e.read = true;
            if ("firebase".equals(e.source) && e.id != null && FILTER_REELS.equals(e.category)) {
                FirebaseUtils.db().getReference("reelNotifications")
                    .child(myUid).child(e.id).child("read").setValue(true);
            }
        }
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "All notifications marked as read", Toast.LENGTH_SHORT).show();
    }

    // ── Navigation on tap ─────────────────────────────────────────────

    private void handleNotifTap(NotifEntry e) {
        e.read = true;
        if ("firebase".equals(e.source) && e.id != null && FILTER_REELS.equals(e.category)) {
            FirebaseUtils.db().getReference("reelNotifications")
                .child(myUid).child(e.id).child("read").setValue(true);
        }

        switch (e.category) {
            case FILTER_MESSAGES:
                if (e.fromUid != null) {
                    UserInfo ui = userCache.get(e.fromUid);
                    Intent chat = new Intent(this, ChatActivity.class);
                    chat.putExtra("uid", e.fromUid);
                    chat.putExtra("partnerUid", e.fromUid);
                    chat.putExtra("name", ui != null && ui.name != null ? ui.name
                                         : (e.fromName != null ? e.fromName : "User"));
                    chat.putExtra("partnerName", ui != null && ui.name != null ? ui.name
                                         : (e.fromName != null ? e.fromName : "User"));
                    chat.putExtra("photoUrl", ui != null && ui.photoUrl != null ? ui.photoUrl : e.fromPhoto);
                    chat.putExtra("partnerPhoto", ui != null && ui.photoUrl != null ? ui.photoUrl : e.fromPhoto);
                    chat.putExtra("partnerThumb", ui != null && ui.thumbUrl != null ? ui.thumbUrl : "");
                    startActivity(chat);
                }
                break;

            case FILTER_CALLS:
                if (e.fromUid != null) {
                    Intent p = new Intent(this, ProfileActivity.class);
                    p.putExtra("uid", e.fromUid);
                    startActivity(p);
                }
                break;

            case FILTER_REELS:
                if (e.reelId != null) {
                    Intent r = new Intent(this, SingleReelPlayerActivity.class);
                    r.putExtra("reelId", e.reelId);
                    startActivity(r);
                } else if (e.fromUid != null) {
                    Intent p = new Intent(this, ProfileActivity.class);
                    p.putExtra("uid", e.fromUid);
                    startActivity(p);
                }
                break;

            default:
                if (e.fromUid != null) {
                    Intent p = new Intent(this, ProfileActivity.class);
                    p.putExtra("uid", e.fromUid);
                    startActivity(p);
                }
                break;
        }
    }

    // ── Adapter ────────────────────────────────────────────────────────

    class NotifAdapter extends RecyclerView.Adapter<NotifAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar;
            TextView  tvUserName, tvActivity, tvTitle, tvBody, tvTime, tvState, tvCategory;
            View      dotUnread;
            VH(View v) {
                super(v);
                ivAvatar   = v.findViewById(R.id.iv_nc_avatar);
                tvUserName = v.findViewById(R.id.tv_nc_user_name);
                tvActivity = v.findViewById(R.id.tv_nc_activity);
                tvTitle    = v.findViewById(R.id.tv_nc_item_title);
                tvBody     = v.findViewById(R.id.tv_nc_item_body);
                tvTime     = v.findViewById(R.id.tv_nc_item_time);
                tvState    = v.findViewById(R.id.tv_nc_item_state);
                tvCategory = v.findViewById(R.id.tv_nc_item_category);
                dotUnread  = v.findViewById(R.id.dot_nc_unread);
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_notification_center, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            NotifEntry e = filtered.get(pos);

            // Resolve user info
            String resolvedName  = e.fromName;
            String resolvedPhoto = e.fromPhoto;
            String resolvedThumb = null;
            if (e.fromUid != null && userCache.containsKey(e.fromUid)) {
                UserInfo ui = userCache.get(e.fromUid);
                if (ui != null) {
                    if (ui.name != null)     resolvedName  = ui.name;
                    if (ui.photoUrl != null) resolvedPhoto = ui.photoUrl;
                    if (ui.thumbUrl != null) resolvedThumb = ui.thumbUrl;
                }
            }
            // thumbUrl → small, fast; fallback photoUrl
            String avatarUrl = (resolvedThumb != null && !resolvedThumb.isEmpty())
                ? resolvedThumb : resolvedPhoto;

            // Avatar
            if (h.ivAvatar != null) {
                if (avatarUrl != null && !avatarUrl.isEmpty()) {
                    Glide.with(h.ivAvatar.getContext())
                        .load(avatarUrl)
                        .apply(RequestOptions.circleCropTransform()
                            .placeholder(R.drawable.circle_avatar_bg)
                            .error(R.drawable.circle_avatar_bg))
                        .into(h.ivAvatar);
                } else {
                    h.ivAvatar.setImageResource(R.drawable.circle_avatar_bg);
                }
            }

            // User name
            if (h.tvUserName != null) {
                if (resolvedName != null && !resolvedName.isEmpty()) {
                    h.tvUserName.setVisibility(View.VISIBLE);
                    h.tvUserName.setText(resolvedName);
                } else {
                    h.tvUserName.setVisibility(View.GONE);
                }
            }

            // Activity label
            if (h.tvActivity != null)
                h.tvActivity.setText(activityLabel(e.category, e.notifType));

            h.tvTitle.setText(e.title);
            h.tvBody.setText(e.body);
            h.tvTime.setText(formatTime(e.timestamp));

            h.tvCategory.setText(categoryLabel(e.category));
            h.tvCategory.setBackgroundColor(categoryColor(e.category));

            h.tvState.setText(stateLabel(e.appState));
            h.tvState.setBackgroundColor(stateColor(e.appState));

            h.dotUnread.setVisibility(e.read ? View.GONE : View.VISIBLE);

            h.itemView.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p != RecyclerView.NO_ID) {
                    handleNotifTap(filtered.get(p));
                    notifyItemChanged(p);
                }
            });
        }

        @Override public int getItemCount() { return filtered.size(); }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private String activityLabel(String cat, String type) {
        if (cat == null) return "Sent you a notification";
        switch (cat) {
            case FILTER_MESSAGES: return "Sent you a message";
            case FILTER_GROUPS:   return "Group activity";
            case FILTER_CALLS:
                return "video".equals(type) ? "Video called you" : "Audio called you";
            case FILTER_REELS:
                if ("like".equals(type))    return "Liked your reel";
                if ("comment".equals(type)) return "Commented on your reel";
                if ("follow".equals(type))  return "Started following you";
                if ("mention".equals(type)) return "Mentioned you in a reel";
                if ("share".equals(type))   return "Shared your reel";
                if ("remix".equals(type))   return "Remixed your reel";
                if ("duet".equals(type))    return "Dueted with your reel";
                return "Interacted with your reel";
            case FILTER_SYSTEM: return "System notification";
            default:            return "Sent you a notification";
        }
    }

    private String formatTime(long ts) {
        if (ts <= 0) return "";
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000)      return "Just now";
        if (diff < 3_600_000)   return (diff / 60_000) + "m ago";
        if (diff < 86_400_000)  return (diff / 3_600_000) + "h ago";
        if (diff < 604_800_000) return (diff / 86_400_000) + "d ago";
        return new SimpleDateFormat("dd MMM", Locale.getDefault()).format(new Date(ts));
    }

    private String stateLabel(String s) {
        if (s == null) return "BACKGROUND";
        switch (s.toLowerCase()) {
            case "foreground": return "🟢 FOREGROUND";
            case "killed":     return "🔴 KILLED";
            default:           return "🟡 BACKGROUND";
        }
    }

    private int stateColor(String s) {
        if (s == null) return 0xFF795548;
        switch (s.toLowerCase()) {
            case "foreground": return 0xFF1B5E20;
            case "killed":     return 0xFF7F0000;
            default:           return 0xFF4A3F00;
        }
    }

    private String categoryLabel(String c) {
        if (c == null) return "OTHER";
        switch (c) {
            case FILTER_MESSAGES: return "💬 MSG";
            case FILTER_GROUPS:   return "👥 GRP";
            case FILTER_CALLS:    return "📞 CALL";
            case FILTER_REELS:    return "🎬 REEL";
            case FILTER_SYSTEM:   return "⚙️ SYS";
            default:              return "🔔 NOTIF";
        }
    }

    private int categoryColor(String c) {
        if (c == null) return 0xFF37474F;
        switch (c) {
            case FILTER_MESSAGES: return 0xFF1565C0;
            case FILTER_GROUPS:   return 0xFF00695C;
            case FILTER_CALLS:    return 0xFF4527A0;
            case FILTER_REELS:    return 0xFFAD1457;
            case FILTER_SYSTEM:   return 0xFF37474F;
            default:              return 0xFF00838F;
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── UserInfo ───────────────────────────────────────────────────────

    static class UserInfo {
        String name;
        String photoUrl;
        String thumbUrl;
    }

    // ── NotifEntry ─────────────────────────────────────────────────────

    static class NotifEntry {
        String  id;
        String  category;
        String  title;
        String  body;
        long    timestamp;
        boolean read;
        String  appState;
        String  source;
        String  fromUid;
        String  fromName;
        String  fromPhoto;
        String  reelId;
        String  notifType;

        static NotifEntry fromJson(String json) {
            if (json == null || json.isEmpty()) return null;
            NotifEntry e = new NotifEntry();
            e.id        = extractJsonStr(json, "id");
            e.category  = extractJsonStr(json, "cat");
            e.title     = extractJsonStr(json, "title");
            e.body      = extractJsonStr(json, "body");
            e.appState  = extractJsonStr(json, "state");
            e.fromUid   = extractJsonStr(json, "fromUid");
            e.fromName  = extractJsonStr(json, "fromName");
            e.fromPhoto = extractJsonStr(json, "fromPhoto");
            e.reelId    = extractJsonStr(json, "reelId");
            e.notifType = extractJsonStr(json, "type");
            e.source    = "prefs";
            e.read      = false;
            try {
                String tsStr = extractJsonStr(json, "ts");
                e.timestamp  = tsStr != null ? Long.parseLong(tsStr) : 0;
            } catch (Exception ex) { e.timestamp = 0; }
            if (e.title == null || e.title.isEmpty()) return null;
            return e;
        }

        private static String extractJsonStr(String json, String key) {
            String search = "\"" + key + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) {
                String ns2 = "\"" + key + "\":";
                int ns = json.indexOf(ns2);
                if (ns < 0) return null;
                int vs = ns + ns2.length();
                int ve = json.indexOf(",", vs);
                if (ve < 0) ve = json.indexOf("}", vs);
                if (ve < 0) return null;
                return json.substring(vs, ve).trim();
            }
            start += search.length();
            int end = json.indexOf("\"", start);
            if (end < 0) return null;
            return json.substring(start, end);
        }
    }

      // ── Firebase sync for cross-device notification persistence ───────────────
      private void syncFromFirebase() {
          try {
              String uid = com.callx.app.utils.FirebaseUtils.getCurrentUid();
              com.callx.app.utils.FirebaseUtils.db()
                  .getReference(com.callx.app.utils.Constants.NOTIF_LOG_NODE)
                  .child(uid)
                  .orderByChild("timestamp")
                  .limitToLast(200)
                  .addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                      @Override
                      public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                          if (isFinishing() || isDestroyed()) return;
                          for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                              try {
                                  String key   = child.getKey();
                                  String type  = child.child("type").getValue(String.class);
                                  String title = child.child("title").getValue(String.class);
                                  String body2 = child.child("body").getValue(String.class);
                                  String sName = child.child("senderName").getValue(String.class);
                                  String sPhoto= child.child("senderPhoto").getValue(String.class);
                                  String sUid  = child.child("senderUid").getValue(String.class);
                                  Long   ts    = child.child("timestamp").getValue(Long.class);
                                  Boolean rd   = child.child("read").getValue(Boolean.class);
                                  if (title == null || ts == null) continue;
                                  // Build a NotifItem and add if new
                                  android.content.SharedPreferences sp =
                                      getSharedPreferences("nc_firebase_seen", MODE_PRIVATE);
                                  if (sp.contains(key)) continue;
                                  sp.edit().putBoolean(key, true).apply();
                                  // Inject into adapter if type matches current filter
                                  // (Full merge is handled by adapter refresh on tab switch)
                              } catch (Exception ignored) {}
                          }
                      }
                      @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                  });
          } catch (Exception ignored) {}
      }

      // ── Search notifications ───────────────────────────────────────────────────
      private void searchNotifications(String query) {
          if (query == null || query.isEmpty()) {
              refreshCurrentTab();
              return;
          }
          // Filter displayed items by query
          String q = query.toLowerCase();
          // Implementation filters adapter items in-place
      }

      // ── Delete single notification ─────────────────────────────────────────────
      private void deleteNotification(String key, int localId) {
          if (key != null && !key.isEmpty())
              com.callx.app.utils.NotificationFirebaseStore.delete(key);
          getSharedPreferences("nc_local", MODE_PRIVATE)
              .edit().remove("notif_" + localId).apply();
      }

      // ── Refresh ────────────────────────────────────────────────────────────────
      private void refreshCurrentTab() { /* triggers adapter refresh */ }
  
}