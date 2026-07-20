package com.callx.app.group;

import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * GroupReadByActivity — full-screen "Message Info" for group messages.
 *
 * Shows three sections in a single RecyclerView (no ViewPager — avoids
 * inflating three separate RV instances):
 *   • 👁 Read by X    — members who opened the chat after this message
 *   • ✓✓ Delivered to Y — delivered but not yet opened
 *   • ⏳ Pending Z    — not yet delivered (maybe offline)
 *
 * Stays live via GroupMessageReadObserver while the activity is open — if a
 * member opens the group chat while you're watching this screen, their row
 * moves from "Delivered" to "Read" in real time.
 *
 * Extras required:
 *   EXTRA_GROUP_ID   — String
 *   EXTRA_MSG_ID     — String  (Firebase key of the message)
 *   EXTRA_MSG_TEXT   — String? (preview text, optional)
 *   EXTRA_TOTAL_OTHERS — int   (number of other members at send time)
 *
 * Member names/photos: loaded once from groups/{groupId}/members.
 */
public class GroupReadByActivity extends AppCompatActivity {

    public static final String EXTRA_GROUP_ID     = "groupId";
    public static final String EXTRA_MSG_ID       = "msgId";
    public static final String EXTRA_MSG_TEXT     = "msgText";
    public static final String EXTRA_TOTAL_OTHERS = "totalOthers";

    private String groupId, msgId, currentUid;
    private int totalOthers;

    // Member metadata loaded from Firebase
    private final Map<String, String> memberNames  = new HashMap<>();
    private final Map<String, String> memberPhotos = new HashMap<>();

    // Current state
    private Map<String, Long> readBy      = new HashMap<>();
    private Map<String, Long> deliveredBy = new HashMap<>();

    private GroupMessageReadObserver observer;

    // UI
    private SectionAdapter adapter;
    private TextView tvMsgPreview;
    private ValueEventListener membersListener;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_read_by);

        groupId     = getIntent().getStringExtra(EXTRA_GROUP_ID);
        msgId       = getIntent().getStringExtra(EXTRA_MSG_ID);
        totalOthers = getIntent().getIntExtra(EXTRA_TOTAL_OTHERS, 0);

        if (groupId == null || msgId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Message info");
        }

        tvMsgPreview = findViewById(R.id.tv_msg_preview);
        String preview = getIntent().getStringExtra(EXTRA_MSG_TEXT);
        if (tvMsgPreview != null && preview != null) tvMsgPreview.setText(preview);

        RecyclerView rv = findViewById(R.id.rv_read_by);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SectionAdapter();
        rv.setAdapter(adapter);

        loadMembers();
    }

    private void loadMembers() {
        membersListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                for (DataSnapshot c : snap.getChildren()) {
                    String uid   = c.getKey();
                    Object nameO = c.child("name").getValue();
                    Object photoO= c.child("photoUrl").getValue();
                    if (uid != null && nameO != null)
                        memberNames.put(uid, nameO.toString());
                    if (uid != null && photoO != null)
                        memberPhotos.put(uid, photoO.toString());
                }
                startObserver();
            }
            @Override public void onCancelled(DatabaseError e) { startObserver(); }
        };
        FirebaseUtils.getGroupsRef().child(groupId).child("members").addListenerForSingleValueEvent(membersListener);
    }

    private void startObserver() {
        observer = new GroupMessageReadObserver(groupId, msgId, (rb, db) -> {
            readBy      = rb != null ? rb : new HashMap<>();
            deliveredBy = db != null ? db : new HashMap<>();
            rebuildList();
        });
        observer.attach();
    }

    private void rebuildList() {
        List<SectionAdapter.Row> rows = new ArrayList<>();

        // ── Read section ──────────────────────────────────────────────────
        List<String> readUids = new ArrayList<>(readBy.keySet());
        readUids.remove(currentUid);
        rows.add(new SectionAdapter.Row(SectionAdapter.TYPE_HEADER,
                "👁 READ BY (" + readUids.size() + "/" + totalOthers + ")", null, null, null));
        if (readUids.isEmpty()) {
            rows.add(new SectionAdapter.Row(SectionAdapter.TYPE_EMPTY, "No one yet", null, null, null));
        } else {
            readUids.sort((a, b) -> Long.compare(readBy.getOrDefault(b, 0L), readBy.getOrDefault(a, 0L)));
            for (String uid : readUids)
                rows.add(new SectionAdapter.Row(SectionAdapter.TYPE_MEMBER,
                        memberNames.getOrDefault(uid, "Member"),
                        memberPhotos.get(uid), readBy.get(uid), R.drawable.ic_double_tick_blue));
        }

        // ── Delivered section ─────────────────────────────────────────────
        List<String> deliveredUids = new ArrayList<>();
        for (String uid : deliveredBy.keySet())
            if (!readBy.containsKey(uid) && !uid.equals(currentUid)) deliveredUids.add(uid);

        rows.add(new SectionAdapter.Row(SectionAdapter.TYPE_HEADER,
                "✓✓ DELIVERED TO (" + deliveredUids.size() + ")", null, null, null));
        if (deliveredUids.isEmpty()) {
            rows.add(new SectionAdapter.Row(SectionAdapter.TYPE_EMPTY, "—", null, null, null));
        } else {
            for (String uid : deliveredUids)
                rows.add(new SectionAdapter.Row(SectionAdapter.TYPE_MEMBER,
                        memberNames.getOrDefault(uid, "Member"),
                        memberPhotos.get(uid), deliveredBy.get(uid), R.drawable.ic_double_tick));
        }

        // ── Pending section ────────────────────────────────────────────────
        List<String> pendingUids = new ArrayList<>();
        for (String uid : memberNames.keySet()) {
            if (!uid.equals(currentUid) && !readBy.containsKey(uid) && !deliveredBy.containsKey(uid))
                pendingUids.add(uid);
        }
        if (!pendingUids.isEmpty()) {
            rows.add(new SectionAdapter.Row(SectionAdapter.TYPE_HEADER,
                    "⏳ PENDING (" + pendingUids.size() + ")", null, null, null));
            for (String uid : pendingUids)
                rows.add(new SectionAdapter.Row(SectionAdapter.TYPE_MEMBER,
                        memberNames.getOrDefault(uid, "Member"),
                        memberPhotos.get(uid), null, 0));
        }

        runOnUiThread(() -> adapter.setRows(rows));
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (observer != null) observer.detach();
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // ── Flat-section RecyclerView adapter ────────────────────────────────

    static class SectionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        static final int TYPE_HEADER = 0;
        static final int TYPE_MEMBER = 1;
        static final int TYPE_EMPTY  = 2;

        static class Row {
            final int type; final String label; final String photoUrl;
            final Long timestamp; final Integer tickRes;
            Row(int type, String label, String photoUrl, Long ts, Integer tick) {
                this.type = type; this.label = label; this.photoUrl = photoUrl;
                this.timestamp = ts; this.tickRes = tick;
            }
        }

        private final List<Row> rows = new ArrayList<>();

        void setRows(List<Row> newRows) { rows.clear(); rows.addAll(newRows); notifyDataSetChanged(); }

        @Override public int getItemViewType(int pos) { return rows.get(pos).type; }
        @Override public int getItemCount() { return rows.size(); }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LayoutInflater inf = LayoutInflater.from(p.getContext());
            if (t == TYPE_HEADER) {
                View v = inf.inflate(R.layout.item_message_info_header, p, false);
                return new HeaderVH(v);
            } else {
                View v = inf.inflate(R.layout.item_group_read_by_member, p, false);
                return new MemberVH(v);
            }
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            Row row = rows.get(pos);
            if (h instanceof HeaderVH) {
                ((HeaderVH) h).tvHeader.setText(row.label);
            } else if (h instanceof MemberVH) {
                ((MemberVH) h).bind(row);
            }
        }

        static class HeaderVH extends RecyclerView.ViewHolder {
            TextView tvHeader;
            HeaderVH(View v) { super(v); tvHeader = v.findViewById(R.id.tv_info_header); }
        }

        static class MemberVH extends RecyclerView.ViewHolder {
            de.hdodenhof.circleimageview.CircleImageView ivAvatar;
            TextView tvName, tvTime;
            android.widget.ImageView ivTick;
            private static final java.text.SimpleDateFormat FMT =
                    new java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault());

            MemberVH(View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_avatar);
                tvName   = v.findViewById(R.id.tv_name);
                tvTime   = v.findViewById(R.id.tv_time);
                ivTick   = v.findViewById(R.id.iv_tick);
            }

            void bind(Row row) {
                tvName.setText(row.label);
                tvTime.setText(row.timestamp != null && row.timestamp > 0
                        ? FMT.format(new java.util.Date(row.timestamp)) : "Pending");
                if (ivTick != null) {
                    if (row.tickRes != null && row.tickRes != 0) {
                        ivTick.setVisibility(View.VISIBLE);
                        ivTick.setImageResource(row.tickRes);
                    } else {
                        ivTick.setVisibility(View.GONE);
                    }
                }
                if (ivAvatar != null) {
                    if (row.photoUrl != null && !row.photoUrl.isEmpty()) {
                        com.bumptech.glide.Glide.with(ivAvatar).load(row.photoUrl)
                                .placeholder(R.drawable.ic_person).into(ivAvatar);
                    } else {
                        ivAvatar.setImageResource(R.drawable.ic_person);
                    }
                }
            }
        }
    }
}
