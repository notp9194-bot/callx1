package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.activities.NewStatusActivity;
import com.callx.app.activities.StatusViewerActivity;
import com.callx.app.adapters.StatusListAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * StatusFragment — Production-grade status screen.
 *
 * Sections:
 *   [0]  My Status row (always at top; shows "Add" or current status ring)
 *   [1…] Recent updates — contacts with NEW (unseen) statuses, sorted latest-first
 *   [N…] Viewed updates — contacts whose statuses have all been seen
 *
 * Features:
 *   • Real-time Firebase listener with proper cleanup on stop
 *   • Seen / unseen ring differentiation
 *   • Status count badge per contact
 *   • My own status preview at top
 *   • Empty-state view when no contacts have active statuses
 *   • Section headers ("Recent updates" / "Viewed updates")
 */
public class StatusFragment extends Fragment {

    // ── Adapter section model ─────────────────────────────────────────────

    public static final int TYPE_MY_STATUS        = 0;
    public static final int TYPE_SECTION_HEADER   = 1;
    public static final int TYPE_CONTACT_STATUS   = 2;

    // ── State ─────────────────────────────────────────────────────────────

    private StatusListAdapter adapter;

    /** All valid (non-expired, non-deleted) statuses grouped by ownerUid */
    private final Map<String, List<StatusItem>> statusMap = new LinkedHashMap<>();

    /** Seen map: ownerUid → set of statusIds the current user has seen */
    private final Map<String, Set<String>> seenMap = new HashMap<>();

    /** My own latest status list */
    private final List<StatusItem> myStatuses = new ArrayList<>();

    private ValueEventListener statusListener;
    private ValueEventListener seenListener;
    private String myUid;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_status, parent, false);

        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            return v;
        }

        RecyclerView rv = v.findViewById(R.id.rv_status);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setItemAnimator(null); // smoother real-time updates

        adapter = new StatusListAdapter(
                myUid,
                myStatuses,
                /* onMyStatusClick */  () -> {
                    if (myStatuses.isEmpty()) {
                        startActivity(new Intent(requireContext(), NewStatusActivity.class));
                    } else {
                        Intent i = new Intent(requireContext(), StatusViewerActivity.class);
                        i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID,  myUid);
                        i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, "My Status");
                        startActivity(i);
                    }
                },
                /* onAddStatusClick */  () ->
                    startActivity(new Intent(requireContext(), NewStatusActivity.class)),
                /* onContactClick */   (ownerUid, ownerName) -> {
                    Intent i = new Intent(requireContext(), StatusViewerActivity.class);
                    i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID,  ownerUid);
                    i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, ownerName);
                    startActivity(i);
                }
        );
        rv.setAdapter(adapter);

        ExtendedFloatingActionButton fab = v.findViewById(R.id.fab_new_status);
        fab.setOnClickListener(x ->
                startActivity(new Intent(requireContext(), NewStatusActivity.class)));

        // Collapse FAB label when scrolling
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dy > 4)  fab.shrink();
                if (dy < -4) fab.extend();
            }
        });

        return v;
    }

    @Override
    public void onStart() {
        super.onStart();
        loadFromRoom();   // v17: offline-first
        loadStatuses();
    }

    @Override
    public void onStop() {
        removeListeners();
        super.onStop();
    }

    // ── v17: Room se offline-first load ───────────────────────────────────
    private void loadFromRoom() {
        if (getContext() == null || myUid == null) return;
        AppDatabase db = AppDatabase.getInstance(getContext());

        Executors.newSingleThreadExecutor().execute(() -> {
            long now = System.currentTimeMillis();
            List<StatusEntity> cached = db.statusDao().getActiveStatuses(now);
            // Expired statuses cleanup bhi karo background mein
            db.statusDao().pruneExpired(now);

            if (cached == null || cached.isEmpty()) return;

            // Cached statuses ko model mein convert karo
            Map<String, List<StatusItem>> roomMap = new LinkedHashMap<>();
            List<StatusItem> roomMine = new ArrayList<>();

            for (StatusEntity e : cached) {
                StatusItem item = new StatusItem();
                item.id           = e.id;
                item.ownerUid     = e.ownerUid;
                item.ownerName    = e.ownerName;
                item.ownerPhoto   = e.ownerPhoto;
                item.type         = e.type;
                item.text         = e.text;
                item.mediaUrl     = e.mediaUrl;
                item.thumbnailUrl = e.thumbnailUrl;
                item.bgColor      = e.bgColor;
                item.fontStyle    = e.fontStyle;
                item.textColor    = e.textColor;
                item.timestamp    = e.timestamp;
                item.expiresAt    = e.expiresAt;
                item.deleted      = e.deleted != null && e.deleted;

                if (myUid.equals(e.ownerUid)) {
                    roomMine.add(item);
                } else {
                    roomMap.computeIfAbsent(e.ownerUid, k -> new ArrayList<>()).add(item);
                }
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    // Sirf tab dikhao agar Firebase ne abhi kuch nahi diya
                    if (statusMap.isEmpty() && myStatuses.isEmpty()) {
                        statusMap.putAll(roomMap);
                        myStatuses.addAll(roomMine);
                        rebuildAdapter();
                    }
                });
            }
        });
    }

    // ── Data loading ──────────────────────────────────────────────────────

    private void loadStatuses() {
        if (myUid == null) return;

        // 1. Load contacts first, then watch statuses of those contacts + self
        FirebaseUtils.getContactsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot contactsSnap) {
                    Set<String> watchedUids = new HashSet<>();
                    watchedUids.add(myUid);
                    for (DataSnapshot c : contactsSnap.getChildren()) {
                        if (c.getKey() != null) watchedUids.add(c.getKey());
                    }
                    attachStatusListener(watchedUids);
                    attachSeenListener();
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    // Fall back: watch all statuses
                    attachStatusListener(Collections.singleton(myUid));
                }
            });
    }

    /**
     * Attach a single ValueEventListener on the root "status" node.
     * This is more efficient than N per-contact listeners when contacts > ~20.
     */
    private void attachStatusListener(Set<String> watchedUids) {
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                long now = System.currentTimeMillis();
                statusMap.clear();
                myStatuses.clear();

                for (DataSnapshot userSnap : snap.getChildren()) {
                    String uid = userSnap.getKey();
                    if (uid == null) continue;
                    if (!watchedUids.contains(uid)) continue;

                    List<StatusItem> items = new ArrayList<>();
                    for (DataSnapshot stSnap : userSnap.getChildren()) {
                        StatusItem item = stSnap.getValue(StatusItem.class);
                        if (item == null || item.deleted) continue;
                        if (item.expiresAt != null && item.expiresAt < now) continue;
                        items.add(item);
                    }
                    // Sort chronologically within each user
                    items.sort((a, b) -> {
                        long ta = a.timestamp == null ? 0 : a.timestamp;
                        long tb = b.timestamp == null ? 0 : b.timestamp;
                        return Long.compare(ta, tb);
                    });

                    if (uid.equals(myUid)) {
                        myStatuses.addAll(items);
                    } else if (!items.isEmpty()) {
                        statusMap.put(uid, items);
                    }
                }
                rebuildAdapter();

                // v17: Room mein save karo sab active statuses
                if (getContext() != null) {
                    List<StatusEntity> toSave = new ArrayList<>();
                    for (StatusItem si : myStatuses) {
                        toSave.add(statusItemToEntity(si));
                    }
                    for (List<StatusItem> items : statusMap.values()) {
                        for (StatusItem si : items) {
                            toSave.add(statusItemToEntity(si));
                        }
                    }
                    if (!toSave.isEmpty()) {
                        AppDatabase db = AppDatabase.getInstance(getContext());
                        Executors.newSingleThreadExecutor().execute(() ->
                            db.statusDao().insertStatuses(toSave));
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getStatusRef().addValueEventListener(statusListener);
    }

    /**
     * Watch which statuses the current user has already seen.
     * Format: statusSeen/{myUid}/{ownerUid}/{statusId} = timestamp
     */
    private void attachSeenListener() {
        seenListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                seenMap.clear();
                for (DataSnapshot ownerSnap : snap.getChildren()) {
                    String ownerUid = ownerSnap.getKey();
                    if (ownerUid == null) continue;
                    Set<String> seenIds = new HashSet<>();
                    for (DataSnapshot idSnap : ownerSnap.getChildren()) {
                        if (idSnap.getKey() != null) seenIds.add(idSnap.getKey());
                    }
                    seenMap.put(ownerUid, seenIds);
                }
                rebuildAdapter();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db()
            .getReference("statusSeen")
            .child(myUid)
            .addValueEventListener(seenListener);
    }

    private void removeListeners() {
        if (statusListener != null) {
            FirebaseUtils.getStatusRef().removeEventListener(statusListener);
            statusListener = null;
        }
        if (seenListener != null && myUid != null) {
            FirebaseUtils.db()
                .getReference("statusSeen")
                .child(myUid)
                .removeEventListener(seenListener);
            seenListener = null;
        }
    }

    // ── Adapter rebuild ───────────────────────────────────────────────────

    /**
     * Partition contacts into "unseen" (has at least 1 unseen item) and
     * "seen" (all items seen), sort each partition by latest timestamp desc,
     * and rebuild the adapter list.
     */
    // v17: StatusItem → StatusEntity converter
    private StatusEntity statusItemToEntity(StatusItem si) {
        StatusEntity e   = new StatusEntity();
        e.id             = si.id != null ? si.id : "";
        e.ownerUid       = si.ownerUid;
        e.ownerName      = si.ownerName;
        e.ownerPhoto     = si.ownerPhoto;
        e.type           = si.type;
        e.text           = si.text;
        e.mediaUrl       = si.mediaUrl;
        e.thumbnailUrl   = si.thumbnailUrl;
        e.bgColor        = si.bgColor;
        e.fontStyle      = si.fontStyle;
        e.textColor      = si.textColor;
        e.timestamp      = si.timestamp;
        e.expiresAt      = si.expiresAt;
        e.deleted        = si.deleted;
        return e;
    }

    private void rebuildAdapter() {
        List<StatusListAdapter.Entry> unseen = new ArrayList<>();
        List<StatusListAdapter.Entry> seen   = new ArrayList<>();

        for (Map.Entry<String, List<StatusItem>> e : statusMap.entrySet()) {
            String uid     = e.getKey();
            List<StatusItem> items = e.getValue();
            if (items.isEmpty()) continue;

            Set<String> seenIds = seenMap.getOrDefault(uid, Collections.emptySet());
            int unseenCount = 0;
            for (StatusItem item : items) {
                if (!seenIds.contains(item.id)) unseenCount++;
            }

            StatusItem latest = items.get(items.size() - 1);
            StatusListAdapter.Entry entry = new StatusListAdapter.Entry(
                    uid,
                    latest.ownerName,
                    latest.ownerPhoto,
                    latest.timestamp,
                    items.size(),
                    unseenCount,
                    latest
            );

            if (unseenCount > 0) unseen.add(entry);
            else                 seen.add(entry);
        }

        // Sort each section: most-recent first
        Comparator<StatusListAdapter.Entry> byTime =
                (a, b) -> Long.compare(
                        b.latestTimestamp == null ? 0 : b.latestTimestamp,
                        a.latestTimestamp == null ? 0 : a.latestTimestamp);
        unseen.sort(byTime);
        seen.sort(byTime);

        adapter.update(unseen, seen);
    }
}
