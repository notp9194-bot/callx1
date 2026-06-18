package com.callx.app.activities;

import android.content.Intent;
import com.callx.app.activities.StatusViewerActivity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.text.SimpleDateFormat;
import java.util.*;
import com.callx.app.feed.ReelPlayerFragment;
import com.callx.app.notifications.ReelNotificationsActivity;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.group.GroupChatActivity;

/**
 * AllNotificationsActivity — 6-tab unified notification center.
 *
 * Tabs: All | Chats | Status | Groups | Reels | Calls
 *
 * Fixes applied (v2):
 *  ✅ Status tab — loads from Firebase "statusNotifications/{myUid}"
 *  ✅ Group Calls — loads from Firebase "calls/{myUid}" including group_call_missed entries
 *  ✅ Reels avatar — uses fromThumb then fromPhoto (thumbUrl-priority)
 *  ✅ ALL avatars — thumbUrl preferred over photoUrl everywhere
 *  ✅ Chat/Comment body shown inside notification item
 *  ✅ Group message body (last message text) shown
 *  ✅ Improved modern UI: card style, section divider, richer layout
 */
public class AllNotificationsActivity extends AppCompatActivity {

    // ── Tab constants ──────────────────────────────────────────────────
    private static final int TAB_ALL    = 0;
    private static final int TAB_CHATS  = 1;
    private static final int TAB_STATUS = 2;
    private static final int TAB_GROUPS = 3;
    private static final int TAB_REELS  = 4;
    private static final int TAB_CALLS  = 5;

    private static final String[] TAB_LABELS = {"All", "Chats", "Status", "Groups", "Reels", "Calls"};
    private static final String[] TAB_ICONS  = {"🔔", "💬", "🟢", "👥", "🎬", "📞"};

    // ── State ──────────────────────────────────────────────────────────
    private String myUid;
    private final List<NotifItem>       allItems = new ArrayList<>();
    private final List<NotifItem>       shown    = new ArrayList<>();
    private final Map<String, UserMeta> cache    = new HashMap<>();

    private int   currentTab = TAB_ALL;
    private final int[] tabUnread = new int[6];

    // ── Views ──────────────────────────────────────────────────────────
    private RecyclerView  rv;
    private ProgressBar   progress;
    private View          tvEmpty;
    private LinearLayout  tabsContainer;
    private final TextView[] tabViews   = new TextView[6];
    private final TextView[] badgeViews = new TextView[6];
    private NotifAdapter  adapter;

    // ── Firebase listeners (kept for detach) ───────────────────────────
    private ValueEventListener chatListener, statusListener, statusListener2,
                               groupListener, groupListener2,
                               reelListener, callListener;
    private int loadedSources = 0;
    // chats, groups(unread)+groups(log), reels, calls, status(log)+status(statusNotifications) = 7 sources
    private static final int TOTAL_SOURCES = 7;

    // ──────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_notifications);

        myUid = FirebaseAuth.getInstance().getCurrentUser() == null
            ? null : FirebaseAuth.getInstance().getCurrentUser().getUid();
        if (myUid == null) { finish(); return; }

        bindViews();
        buildTabs();
        loadAll();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (chatListener    != null) FirebaseUtils.getContactsRef(myUid).removeEventListener(chatListener);
        if (groupListener   != null) FirebaseUtils.getUserGroupsRef(myUid).removeEventListener(groupListener);
        if (groupListener2  != null)
            FirebaseUtils.db().getReference("notification_log").child(myUid).removeEventListener(groupListener2);
        if (reelListener    != null)
            FirebaseUtils.db().getReference("reel_notifications").child(myUid).removeEventListener(reelListener);
        if (callListener    != null) FirebaseUtils.getCallsRef(myUid).removeEventListener(callListener);
        if (statusListener  != null)
            FirebaseUtils.db().getReference("statusNotifications").child(myUid).removeEventListener(statusListener);
        if (statusListener2 != null)
            FirebaseUtils.db().getReference("notification_log").child(myUid).removeEventListener(statusListener2);
    }

    // ── Bind views ─────────────────────────────────────────────────────
    private void bindViews() {
        rv            = findViewById(R.id.rv_all_notif);
        progress      = findViewById(R.id.progress_all_notif);
        tvEmpty       = findViewById(R.id.tv_all_notif_empty);
        tabsContainer = findViewById(R.id.ll_all_notif_tabs);

        findViewById(R.id.btn_all_notif_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_all_notif_mark_read).setOnClickListener(v -> markAllRead());

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new NotifAdapter();
        rv.setAdapter(adapter);
    }

    // ── Build tab bar ──────────────────────────────────────────────────
    private void buildTabs() {
        tabsContainer.removeAllViews();
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int idx = i;

            FrameLayout frame = new FrameLayout(this);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fp.setMarginEnd(8);
            frame.setLayoutParams(fp);

            TextView tv = new TextView(this);
            tv.setText(TAB_ICONS[i] + " " + TAB_LABELS[i]);
            tv.setTextColor(i == 0 ? 0xFFFFFFFF : 0xFF9E9E9E);
            tv.setTextSize(12.5f);
            tv.setPadding(28, 16, 28, 16);
            tv.setBackground(i == 0
                ? getDrawable(R.drawable.bg_tab_active)
                : getDrawable(R.drawable.bg_speed_chip));
            tv.setOnClickListener(v -> switchTab(idx));
            frame.addView(tv);

            TextView badge = new TextView(this);
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(8.5f);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(5, 2, 5, 2);
            badge.setBackground(getDrawable(R.drawable.bg_unread_badge));
            badge.setVisibility(View.GONE);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.gravity = Gravity.TOP | Gravity.END;
            badge.setLayoutParams(bp);
            frame.addView(badge);

            tabsContainer.addView(frame);
            tabViews[i]   = tv;
            badgeViews[i] = badge;
        }
    }

    private void switchTab(int idx) {
        currentTab = idx;
        for (int i = 0; i < tabViews.length; i++) {
            if (tabViews[i] == null) continue;
            boolean active = (i == idx);
            tabViews[i].setTextColor(active ? 0xFFFFFFFF : 0xFF9E9E9E);
            tabViews[i].setBackground(active
                ? getDrawable(R.drawable.bg_tab_active)
                : getDrawable(R.drawable.bg_speed_chip));
        }
        applyFilter();
    }

    // ── Load all data ──────────────────────────────────────────────────
    private void loadAll() {
        progress.setVisibility(View.VISIBLE);
        allItems.clear();
        loadedSources = 0;
        loadChatNotifs();
        loadGroupNotifs();        // source 1: unread counter from getUserGroupsRef
        loadGroupNotifsFromLog(); // source 2: notification_log type=group
        loadReelNotifs();
        loadCallNotifs();
        loadStatusNotifs();        // source 1: statusNotifications path
        loadStatusNotifsFromLog(); // source 2: notification_log type=status
    }

    /** Chat unread notifications from contacts */
    private void loadChatNotifs() {
        chatListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                removeCategory("chat");
                for (DataSnapshot c : snap.getChildren()) {
                    Long unread = c.child("unread").getValue(Long.class);
                    if (unread == null || unread <= 0) continue;
                    String uid      = c.getKey();
                    String name     = c.child("name").getValue(String.class);
                    String photo    = c.child("photoUrl").getValue(String.class);
                    String thumb    = c.child("thumbUrl").getValue(String.class);
                    String lastMsg  = c.child("lastMessage").getValue(String.class);
                    String lastType = c.child("lastMessageType").getValue(String.class);
                    Long   ts       = c.child("lastMessageAt").getValue(Long.class);

                    NotifItem item   = new NotifItem();
                    item.id          = "chat_" + uid;
                    item.category    = "chat";
                    item.fromUid     = uid;
                    item.fromName    = name;
                    item.fromPhoto   = photo;
                    item.fromThumb   = thumb;      // ✅ thumbUrl stored
                    item.title       = name != null ? name : "New message";
                    // ✅ Show last message preview
                    item.body        = buildChatBody(lastMsg, lastType, unread);
                    item.timestamp   = ts != null ? ts : System.currentTimeMillis();
                    item.read        = false;
                    allItems.add(item);
                }
                onSourceLoaded();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onSourceLoaded(); }
        };
        FirebaseUtils.getContactsRef(myUid).addValueEventListener(chatListener);
    }

    private String buildChatBody(String lastMsg, String lastType, long unread) {
        String preview = "";
        if (lastMsg != null && !lastMsg.isEmpty()) {
            preview = lastMsg.length() > 60 ? lastMsg.substring(0, 60) + "…" : lastMsg;
        } else if (lastType != null) {
            switch (lastType) {
                case "image":  preview = "📷 Photo";    break;
                case "video":  preview = "🎥 Video";    break;
                case "audio":
                case "voice":  preview = "🎤 Voice";    break;
                case "file":   preview = "📎 File";     break;
                default:       preview = "New message"; break;
            }
        }
        String badge = unread + " unread" + (unread > 1 ? " messages" : " message");
        return preview.isEmpty() ? badge : preview + "\n" + badge;
    }

    /** Group unread notifications — reads from getUserGroupsRef (unread counters) */
    private void loadGroupNotifs() {
        groupListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                removeCategory("group_unread"); // temp category for dedup
                for (DataSnapshot g : snap.getChildren()) {
                    Long unread = g.child("unread").getValue(Long.class);
                    if (unread == null || unread <= 0) continue;
                    String gid      = g.getKey();
                    String gname    = g.child("name").getValue(String.class);
                    String photo    = g.child("groupPhoto").getValue(String.class);
                    String lastMsg  = g.child("lastMessage").getValue(String.class);
                    String senderNm = g.child("lastSenderName").getValue(String.class);
                    String lastType = g.child("lastMessageType").getValue(String.class);
                    Long   ts       = g.child("lastMessageAt").getValue(Long.class);

                    NotifItem item = new NotifItem();
                    item.id        = "grp_unread_" + gid;
                    item.category  = "group";
                    item.fromUid   = null;
                    item.groupId   = gid;
                    item.fromName  = gname != null ? gname : "Group";
                    item.fromPhoto = photo;
                    item.fromThumb = photo;
                    item.title     = gname != null ? gname : "Group";
                    item.body      = buildGroupBody(senderNm, lastMsg, lastType, unread);
                    item.timestamp = ts != null ? ts : System.currentTimeMillis();
                    item.read      = false;
                    allItems.add(item);
                }
                onSourceLoaded();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onSourceLoaded(); }
        };
        FirebaseUtils.getUserGroupsRef(myUid).addValueEventListener(groupListener);
    }

    /** Group notifications from notification_log (type=group) — saved by NotificationFirebaseStore */
    private void loadGroupNotifsFromLog() {
        groupListener2 = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                // Remove only the log-sourced group entries (keep unread-counter entries)
                allItems.removeIf(it -> "group".equals(it.category)
                    && it.id != null && it.id.startsWith("grplog_"));
                for (DataSnapshot s : snap.getChildren()) {
                    String fUid    = firstNonNull(s.child("senderUid").getValue(String.class),
                                                  s.child("fromUid").getValue(String.class));
                    String fName   = firstNonNull(s.child("senderName").getValue(String.class),
                                                  s.child("fromName").getValue(String.class));
                    String fPhoto  = firstNonNull(s.child("senderPhoto").getValue(String.class),
                                                  s.child("fromPhoto").getValue(String.class));
                    String title   = s.child("title").getValue(String.class);
                    String body    = firstNonNull(s.child("body").getValue(String.class),
                                                  s.child("message").getValue(String.class));
                    String groupId = s.child("groupId").getValue(String.class);
                    Long   ts      = s.child("timestamp").getValue(Long.class);
                    Boolean read   = s.child("read").getValue(Boolean.class);

                    if (fUid == null && fName == null && groupId == null) continue;

                    NotifItem item = new NotifItem();
                    item.id        = "grplog_" + s.getKey();
                    item.category  = "group";
                    item.fromUid   = fUid;
                    item.fromName  = fName != null ? fName : (title != null ? title : "Group");
                    item.fromPhoto = fPhoto;
                    item.fromThumb = fPhoto;
                    item.groupId   = groupId;
                    item.title     = title != null ? title : (fName != null ? fName : "Group message");
                    item.body      = body != null ? body : "";
                    item.timestamp = ts != null ? ts : 0;
                    item.read      = Boolean.TRUE.equals(read);
                    allItems.add(item);
                }
                onSourceLoaded();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onSourceLoaded(); }
        };
        FirebaseUtils.db().getReference("notification_log").child(myUid)
            .orderByChild("type").equalTo("group")
            .addValueEventListener(groupListener2);
    }

    private String buildGroupBody(String senderName, String lastMsg, String lastType, long unread) {
        String preview = "";
        if (lastMsg != null && !lastMsg.isEmpty()) {
            String prefix = senderName != null ? senderName + ": " : "";
            String text   = lastMsg.length() > 55 ? lastMsg.substring(0, 55) + "…" : lastMsg;
            preview       = prefix + text;
        } else if (lastType != null) {
            String prefix = senderName != null ? senderName + ": " : "";
            switch (lastType) {
                case "image":  preview = prefix + "📷 Photo";  break;
                case "video":  preview = prefix + "🎥 Video";  break;
                case "audio":
                case "voice":  preview = prefix + "🎤 Voice";  break;
                case "file":   preview = prefix + "📎 File";   break;
                default:       preview = "";                   break;
            }
        }
        String badge = unread + " new message" + (unread > 1 ? "s" : "");
        return preview.isEmpty() ? badge : preview + "\n" + badge;
    }

    /** Status notifications — reads from statusNotifications/{uid} (primary save path) */
    private void loadStatusNotifs() {
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                // Remove only statusNotifications-sourced entries
                allItems.removeIf(it -> "status".equals(it.category)
                    && it.id != null && !it.id.startsWith("stlog_"));
                for (DataSnapshot s : snap.getChildren()) {
                    String  fUid   = firstNonNull(s.child("senderUid").getValue(String.class),
                                                  s.child("fromUid").getValue(String.class));
                    String  fName  = firstNonNull(s.child("senderName").getValue(String.class),
                                                  s.child("fromName").getValue(String.class));
                    String  fPhoto = firstNonNull(s.child("senderPhoto").getValue(String.class),
                                                  s.child("fromPhoto").getValue(String.class));
                    String  fThumb = firstNonNull(s.child("fromThumb").getValue(String.class),
                                                  s.child("senderThumb").getValue(String.class), fPhoto);
                    String  body   = firstNonNull(s.child("body").getValue(String.class),
                                                  s.child("message").getValue(String.class),
                                                  s.child("title").getValue(String.class));
                    String  type   = firstNonNull(s.child("statusType").getValue(String.class),
                                                  s.child("type").getValue(String.class));
                    Long    ts     = s.child("timestamp").getValue(Long.class);
                    Boolean read   = s.child("read").getValue(Boolean.class);

                    if (fUid == null && fName == null) continue;

                    NotifItem item = new NotifItem();
                    item.id        = s.getKey(); // raw key from statusNotifications
                    item.category  = "status";
                    item.fromUid   = fUid;
                    item.fromName  = fName;
                    item.fromPhoto = fPhoto;
                    item.fromThumb = fThumb != null ? fThumb : fPhoto;
                    item.notifType = type;
                    item.title     = fName != null ? fName : "Status";
                    item.body      = body != null ? body : statusBodyFor(type);
                    item.timestamp = ts != null ? ts : 0;
                    item.read      = Boolean.TRUE.equals(read);
                    allItems.add(item);
                }
                onSourceLoaded();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onSourceLoaded(); }
        };
        // PRIMARY path: statusNotifications/{uid} — this is where CallxMessagingService.showStatus() saves
        FirebaseUtils.db().getReference("statusNotifications").child(myUid)
            .addValueEventListener(statusListener);
    }

    /** Status notifications from notification_log (type=status) — secondary source via NotificationFirebaseStore */
    private void loadStatusNotifsFromLog() {
        statusListener2 = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                // Remove only log-sourced status entries
                allItems.removeIf(it -> "status".equals(it.category)
                    && it.id != null && it.id.startsWith("stlog_"));
                for (DataSnapshot s : snap.getChildren()) {
                    String  fUid   = firstNonNull(s.child("senderUid").getValue(String.class),
                                                  s.child("fromUid").getValue(String.class));
                    String  fName  = firstNonNull(s.child("senderName").getValue(String.class),
                                                  s.child("fromName").getValue(String.class));
                    String  fPhoto = firstNonNull(s.child("senderPhoto").getValue(String.class),
                                                  s.child("fromPhoto").getValue(String.class));
                    String  body   = firstNonNull(s.child("body").getValue(String.class),
                                                  s.child("message").getValue(String.class));
                    String  type   = firstNonNull(s.child("statusType").getValue(String.class),
                                                  s.child("type").getValue(String.class));
                    Long    ts     = s.child("timestamp").getValue(Long.class);
                    Boolean read   = s.child("read").getValue(Boolean.class);

                    if (fUid == null && fName == null) continue;

                    // Dedup: skip if same sender+timestamp already loaded from statusNotifications
                    final String dedupUid = fUid;
                    final Long dedupTs = ts;
                    boolean alreadyLoaded = false;
                    if (dedupUid != null && dedupTs != null) {
                        for (NotifItem existing : allItems) {
                            if ("status".equals(existing.category)
                                    && dedupUid.equals(existing.fromUid)
                                    && Math.abs(existing.timestamp - dedupTs) < 2000) {
                                alreadyLoaded = true;
                                break;
                            }
                        }
                    }
                    if (alreadyLoaded) continue;

                    NotifItem item = new NotifItem();
                    item.id        = "stlog_" + s.getKey();
                    item.category  = "status";
                    item.fromUid   = fUid;
                    item.fromName  = fName;
                    item.fromPhoto = fPhoto;
                    item.fromThumb = fPhoto;
                    item.notifType = type;
                    item.title     = fName != null ? fName : "Status";
                    item.body      = body != null ? body : statusBodyFor(type);
                    item.timestamp = ts != null ? ts : 0;
                    item.read      = Boolean.TRUE.equals(read);
                    allItems.add(item);
                }
                onSourceLoaded();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onSourceLoaded(); }
        };
        FirebaseUtils.db().getReference("notification_log").child(myUid)
            .orderByChild("type").equalTo("status")
            .addValueEventListener(statusListener2);
    }

    private String statusBodyFor(String type) {
        if (type == null) return "Posted a new status";
        switch (type) {
            case "image":    return "🖼️ Posted a photo status";
            case "video":    return "🎥 Posted a video status";
            case "text":     return "📝 Posted a text status";
            case "reaction": return "❤️ Reacted to your status";
            default:         return "Posted a new status";
        }
    }

    /** Reel notifications — actual Firebase fields: senderUid, senderName, senderPhoto, reel_id, message */
    private void loadReelNotifs() {
        reelListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                removeCategory("reel");
                for (DataSnapshot s : snap.getChildren()) {
                    Boolean read = s.child("read").getValue(Boolean.class);
                    String  type = s.child("type").getValue(String.class);
                    Long    ts   = s.child("timestamp").getValue(Long.class);

                    // ✅ Correct field names as saved by ReelPlayerFragment / ReelCommentNotifWorker
                    String fUid   = firstNonNull(s.child("senderUid").getValue(String.class),
                                                 s.child("fromUid").getValue(String.class));
                    String fName  = firstNonNull(s.child("senderName").getValue(String.class),
                                                 s.child("fromName").getValue(String.class));
                    String fPhoto = firstNonNull(s.child("senderPhoto").getValue(String.class),
                                                 s.child("fromPhoto").getValue(String.class));
                    // No separate thumb in reel_notifications — senderPhoto IS the thumb
                    String fThumb = fPhoto;
                    String reelId = firstNonNull(s.child("reel_id").getValue(String.class),
                                                 s.child("reelId").getValue(String.class));
                    String body   = firstNonNull(s.child("message").getValue(String.class),
                                                 s.child("body").getValue(String.class));
                    // comment text stored as "message" field or separate "comment" field
                    String comment = firstNonNull(s.child("comment_text").getValue(String.class),
                                                  s.child("comment").getValue(String.class));

                    NotifItem item = new NotifItem();
                    item.id        = s.getKey();
                    item.category  = "reel";
                    item.fromUid   = fUid;
                    item.fromName  = fName;
                    item.fromPhoto = fPhoto;
                    item.fromThumb = fThumb;
                    item.reelId    = reelId;
                    item.notifType = type;
                    item.title     = reelActivityLabel(type);
                    item.body      = buildReelBody(type, body, comment);
                    item.timestamp = ts != null ? ts : 0;
                    item.read      = Boolean.TRUE.equals(read);
                    allItems.add(item);
                }
                onSourceLoaded();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onSourceLoaded(); }
        };
        FirebaseUtils.db().getReference("reel_notifications").child(myUid)
            .orderByChild("timestamp").limitToLast(80)
            .addValueEventListener(reelListener);
    }

    private String buildReelBody(String type, String body, String comment) {
        if ("comment".equals(type) && comment != null && !comment.isEmpty()) {
            return "💬 \"" + (comment.length() > 60 ? comment.substring(0, 60) + "…" : comment) + "\"";
        }
        return body != null ? body : "";
    }

    /** Call notifications — reads from notification_log (type=call) where NotificationFirebaseStore saves them */
    private void loadCallNotifs() {
        callListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                removeCategory("call");
                for (DataSnapshot s : snap.getChildren()) {
                    // notification_log fields: senderUid, senderName, senderPhoto, title, body, groupId
                    String fUid    = firstNonNull(s.child("senderUid").getValue(String.class),
                                                  s.child("callerUid").getValue(String.class));
                    String fName   = firstNonNull(s.child("senderName").getValue(String.class),
                                                  s.child("callerName").getValue(String.class));
                    String fPhoto  = firstNonNull(s.child("senderPhoto").getValue(String.class),
                                                  s.child("callerPhoto").getValue(String.class));
                    String title   = s.child("title").getValue(String.class);
                    String body    = firstNonNull(s.child("body").getValue(String.class),
                                                  s.child("message").getValue(String.class));
                    String groupId = s.child("groupId").getValue(String.class);
                    String callType= firstNonNull(s.child("callType").getValue(String.class),
                                                  s.child("notifType").getValue(String.class));
                    Long   ts      = s.child("timestamp").getValue(Long.class);
                    Boolean read   = s.child("read").getValue(Boolean.class);

                    if (fUid == null && fName == null) continue;

                    boolean isVideo = callType != null && callType.contains("video");
                    boolean isGroup = groupId != null && !groupId.isEmpty();
                    boolean isMissed = title != null && title.toLowerCase().contains("missed");

                    NotifItem item = new NotifItem();
                    item.id        = s.getKey();
                    item.category  = "call";
                    item.fromUid   = fUid;
                    item.fromName  = fName;
                    item.fromPhoto = fPhoto;
                    item.fromThumb = fPhoto; // senderPhoto is already thumb in notification_log
                    item.groupId   = groupId;
                    item.notifType = isVideo ? "video" : "audio";
                    item.title     = title != null ? title
                        : (isMissed ? "Missed Call" : "Call");
                    item.body      = body != null ? body
                        : ((isVideo ? "📹 Video" : "📞 Audio") + " · " + (fName != null ? fName : "Unknown"));
                    item.timestamp = ts != null ? ts : 0;
                    item.read      = Boolean.TRUE.equals(read);
                    allItems.add(item);
                }
                onSourceLoaded();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onSourceLoaded(); }
        };
        // notification_log/{uid} is where NotificationFirebaseStore.save() writes call entries
        FirebaseUtils.db().getReference("notification_log").child(myUid)
            .orderByChild("type").equalTo("call")
            .addValueEventListener(callListener);
    }

    private synchronized void removeCategory(String cat) {
        allItems.removeIf(it -> cat.equals(it.category));
    }

    private synchronized void onSourceLoaded() {
        loadedSources++;
        runOnUiThread(() -> {
            allItems.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
            recalcBadges();
            progress.setVisibility(View.GONE);
            applyFilter();
        });
    }

    // ── Filter ─────────────────────────────────────────────────────────
    private void applyFilter() {
        shown.clear();
        for (NotifItem it : allItems) {
            if (matchesTab(it, currentTab)) shown.add(it);
        }
        boolean empty = shown.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private boolean matchesTab(NotifItem it, int tab) {
        switch (tab) {
            case TAB_ALL:    return true;
            case TAB_CHATS:  return "chat".equals(it.category);
            case TAB_STATUS: return "status".equals(it.category);
            case TAB_GROUPS: return "group".equals(it.category);
            case TAB_REELS:  return "reel".equals(it.category);
            case TAB_CALLS:  return "call".equals(it.category);
            default:         return true;
        }
    }

    // ── Badge calculation ──────────────────────────────────────────────
    private void recalcBadges() {
        Arrays.fill(tabUnread, 0);
        for (NotifItem it : allItems) {
            if (it.read) continue;
            tabUnread[TAB_ALL]++;
            switch (it.category) {
                case "chat":   tabUnread[TAB_CHATS]++;  break;
                case "status": tabUnread[TAB_STATUS]++; break;
                case "group":  tabUnread[TAB_GROUPS]++; break;
                case "reel":   tabUnread[TAB_REELS]++;  break;
                case "call":   tabUnread[TAB_CALLS]++;  break;
            }
        }
        for (int i = 0; i < badgeViews.length; i++) {
            TextView bv = badgeViews[i];
            if (bv == null) continue;
            if (tabUnread[i] > 0) {
                bv.setText(tabUnread[i] > 99 ? "99+" : String.valueOf(tabUnread[i]));
                bv.setVisibility(View.VISIBLE);
            } else {
                bv.setVisibility(View.GONE);
            }
        }
    }

    // ── Mark all read ──────────────────────────────────────────────────
    private void markAllRead() {
        for (NotifItem it : allItems) {
            if (!it.read) {
                it.read = true;
                if ("reel".equals(it.category) && it.id != null) {
                    FirebaseUtils.db().getReference("reel_notifications")
                        .child(myUid).child(it.id).child("read").setValue(true);
                } else if ("status".equals(it.category) && it.id != null) {
                    if (it.id.startsWith("stlog_")) {
                        // From notification_log
                        FirebaseUtils.db().getReference("notification_log")
                            .child(myUid).child(it.id.substring(6)).child("read").setValue(true);
                    } else {
                        // From statusNotifications
                        FirebaseUtils.db().getReference("statusNotifications")
                            .child(myUid).child(it.id).child("read").setValue(true);
                    }
                } else if ("group".equals(it.category) && it.id != null
                        && it.id.startsWith("grplog_")) {
                    FirebaseUtils.db().getReference("notification_log")
                        .child(myUid).child(it.id.substring(7)).child("read").setValue(true);
                }
            }
        }
        recalcBadges();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "All marked as read", Toast.LENGTH_SHORT).show();
    }

    // ── Navigation on item tap ──────────────────────────────────────────
    private void onItemTap(NotifItem it) {
        it.read = true;
        markReadInFirebase(it);
        recalcBadges();
        adapter.notifyDataSetChanged();
        navigate(it);
    }

    private void navigate(NotifItem it) {
        switch (it.category) {
            case "chat":
                if (it.fromUid != null) {
                    UserMeta m = cache.get(it.fromUid);
                    Intent c = new Intent(this, ChatActivity.class);
                    c.putExtra("uid",          it.fromUid);
                    c.putExtra("partnerUid",   it.fromUid);
                    c.putExtra("name",         m != null && m.name != null  ? m.name  : orEmpty(it.fromName));
                    c.putExtra("partnerName",  m != null && m.name != null  ? m.name  : orEmpty(it.fromName));
                    c.putExtra("photoUrl",     m != null && m.photo != null ? m.photo : orEmpty(it.fromPhoto));
                    c.putExtra("partnerPhoto", m != null && m.photo != null ? m.photo : orEmpty(it.fromPhoto));
                    c.putExtra("partnerThumb", m != null && m.thumb != null ? m.thumb : orEmpty(it.fromThumb));
                    startActivity(c);
                }
                break;

            case "group":
                if (it.groupId != null) {
                    Intent g = new Intent(this, GroupChatActivity.class);
                    g.putExtra("groupId",   it.groupId);
                    g.putExtra("groupName", it.fromName);
                    g.putExtra("groupPhoto", orEmpty(it.fromPhoto));
                    startActivity(g);
                }
                break;

            case "status":
                // Pass sender UID + name so StatusViewerActivity can load the correct status.
                // Without these extras StatusViewerActivity immediately calls finish().
                if (it.fromUid != null) {
                    Intent sv = new Intent(this, StatusViewerActivity.class);
                    sv.putExtra(StatusViewerActivity.EXTRA_OWNER_UID,  it.fromUid);
                    sv.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME,
                                it.fromName != null ? it.fromName : "Status");
                    startActivity(sv);
                } else {
                    // Fallback: open MainActivity on the Status tab
                    Intent fallback = new Intent(this, MainActivity.class);
                    fallback.putExtra("open_tab", "status");
                    fallback.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(fallback);
                }
                break;

            case "call":
                if (it.fromUid != null) {
                    Intent p = new Intent(this, ProfileActivity.class);
                    p.putExtra("uid", it.fromUid);
                    startActivity(p);
                }
                break;

            case "reel":
                try {
                    startActivity(new Intent(this,
                        Class.forName("com.callx.app.notifications.ReelNotificationsActivity")));
                } catch (ClassNotFoundException e) {
                    if (it.fromUid != null) {
                        Intent p = new Intent(this, ProfileActivity.class);
                        p.putExtra("uid", it.fromUid);
                        startActivity(p);
                    }
                }
                break;

            default:
                if (it.fromUid != null) {
                    Intent p = new Intent(this, ProfileActivity.class);
                    p.putExtra("uid", it.fromUid);
                    startActivity(p);
                }
                break;
        }
    }

    private void markReadInFirebase(NotifItem it) {
        if (it.id == null) return;
        if ("reel".equals(it.category)) {
            FirebaseUtils.db().getReference("reel_notifications")
                .child(myUid).child(it.id).child("read").setValue(true);
        } else if ("status".equals(it.category)) {
            if (it.id.startsWith("stlog_")) {
                FirebaseUtils.db().getReference("notification_log")
                    .child(myUid).child(it.id.substring(6)).child("read").setValue(true);
            } else {
                FirebaseUtils.db().getReference("statusNotifications")
                    .child(myUid).child(it.id).child("read").setValue(true);
            }
        } else if ("group".equals(it.category) && it.id.startsWith("grplog_")) {
            FirebaseUtils.db().getReference("notification_log")
                .child(myUid).child(it.id.substring(7)).child("read").setValue(true);
        }
    }

    // ── Adapter ────────────────────────────────────────────────────────
    class NotifAdapter extends RecyclerView.Adapter<NotifAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar;
            TextView  tvName, tvBody, tvTime, tvCat;
            View      dotUnread, cardRoot;
            VH(View v) {
                super(v);
                cardRoot  = v.findViewById(R.id.card_an_root);
                ivAvatar  = v.findViewById(R.id.iv_an_avatar);
                tvName    = v.findViewById(R.id.tv_an_name);
                tvBody    = v.findViewById(R.id.tv_an_body);
                tvTime    = v.findViewById(R.id.tv_an_time);
                tvCat     = v.findViewById(R.id.tv_an_cat);
                dotUnread = v.findViewById(R.id.dot_an_unread);
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_all_notification, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            NotifItem it = shown.get(pos);

            // ✅ Resolve best avatar URL — thumb priority, always use thumbUrl/thumbnail
            String avatarUrl = resolveAvatarUrl(it);

            if (h.ivAvatar != null) {
                if (avatarUrl != null && !avatarUrl.isEmpty()) {
                    Glide.with(h.ivAvatar.getContext())
                        .load(avatarUrl)
                        .circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .error(R.drawable.ic_person)
                        .into(h.ivAvatar);
                } else {
                    h.ivAvatar.setImageResource(R.drawable.ic_person);
                }
                final NotifItem itF = it;
                h.ivAvatar.setOnClickListener(v -> navigate(itF));
            }

            if (h.tvName != null)
                h.tvName.setText(it.fromName != null ? it.fromName : it.title);

            // ✅ Body shows chat/comment text
            if (h.tvBody != null) {
                h.tvBody.setText(it.body);
                h.tvBody.setVisibility(it.body != null && !it.body.isEmpty()
                    ? View.VISIBLE : View.GONE);
            }

            if (h.tvTime != null) h.tvTime.setText(formatTime(it.timestamp));

            if (h.tvCat != null) {
                h.tvCat.setText(catLabel(it.category));
                h.tvCat.setBackgroundColor(catColor(it.category));
            }

            // Unread highlight
            if (h.dotUnread != null)
                h.dotUnread.setVisibility(it.read ? View.GONE : View.VISIBLE);

            // Card background: slightly highlighted when unread
            if (h.cardRoot != null)
                h.cardRoot.setBackgroundColor(it.read ? Color.TRANSPARENT : 0x0F3B82F6);

            h.itemView.setOnClickListener(v -> {
                int p2 = h.getAdapterPosition();
                if (p2 != RecyclerView.NO_ID) onItemTap(shown.get(p2));
            });
        }

        @Override public int getItemCount() { return shown.size(); }
    }

    /** ✅ Resolve best avatar: cache thumbUrl > cache photoUrl > item thumbUrl > item photoUrl */
    private String resolveAvatarUrl(NotifItem it) {
        if (it.fromUid != null && cache.containsKey(it.fromUid)) {
            UserMeta m = cache.get(it.fromUid);
            if (m != null) {
                if (m.thumb != null && !m.thumb.isEmpty()) return m.thumb;
                if (m.photo != null && !m.photo.isEmpty()) return m.photo;
            }
        }
        if (it.fromThumb != null && !it.fromThumb.isEmpty()) return it.fromThumb;
        return it.fromPhoto;
    }

    // ── Helpers ────────────────────────────────────────────────────────
    private String catLabel(String cat) {
        if (cat == null) return "🔔";
        switch (cat) {
            case "chat":   return "💬 Chat";
            case "group":  return "👥 Group";
            case "status": return "🟢 Status";
            case "reel":   return "🎬 Reel";
            case "call":   return "📞 Call";
            default:       return "🔔 Notif";
        }
    }

    private int catColor(String cat) {
        if (cat == null) return 0xFF37474F;
        switch (cat) {
            case "chat":   return 0xFF1565C0;
            case "group":  return 0xFF00695C;
            case "status": return 0xFF2E7D32;
            case "reel":   return 0xFFAD1457;
            case "call":   return 0xFF4527A0;
            default:       return 0xFF37474F;
        }
    }

    private String reelActivityLabel(String type) {
        if (type == null) return "Reel notification";
        switch (type) {
            case "like":    return "❤️ Liked your reel";
            case "comment": return "💬 Commented on your reel";
            case "follow":  return "👤 Started following you";
            case "mention": return "📢 Mentioned you in a reel";
            case "share":   return "🔗 Shared your reel";
            default:        return "🎬 Reel activity";
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

    private String orEmpty(String s) { return s != null ? s : ""; }

    /** Returns first non-null, non-empty string among args */
    private static String firstNonNull(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    // ── Data models ────────────────────────────────────────────────────
    static class NotifItem {
        String  id;
        String  category;   // chat | group | status | reel | call
        String  fromUid;
        String  fromName;
        String  fromPhoto;
        String  fromThumb;  // ✅ thumbnail URL (preferred)
        String  groupId;
        String  reelId;
        String  notifType;
        String  title;
        String  body;
        long    timestamp;
        boolean read;
    }

    static class UserMeta {
        String name;
        String photo;
        String thumb;
    }

    public static int getTotalUnread(List<NotifItem> items) {
        int count = 0;
        for (NotifItem it : items) if (!it.read) count++;
        return count;
    }
}
