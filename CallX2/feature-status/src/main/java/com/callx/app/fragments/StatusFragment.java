package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.status.R;
import com.callx.app.activities.NewStatusActivity;
import com.callx.app.activities.StatusViewerActivity;
import com.callx.app.adapters.StatusListAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusHighlightsManager;
import com.callx.app.utils.StatusMuteManager;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.firebase.database.*;
import java.util.*;
import com.callx.app.cache.StatusCacheManager;
import com.callx.app.cache.StatusMediaPreloader;
import java.util.concurrent.Executors;

/**
 * StatusFragment — Production-grade status screen.
 *
 * Sections (top to bottom):
 *   [0]  My Status row (always at top)
 *   [H]  Highlights row (horizontal scroll of own highlight albums — if any exist)
 *   [1…] Recent updates — contacts with NEW (unseen) statuses, sorted latest-first
 *        Muted contacts are FILTERED OUT from both sections
 *   [N…] Viewed updates — contacts whose statuses have all been seen
 *
 * New in this version:
 *   ✅ StatusMuteManager integration — muted contacts hidden from both sections
 *   ✅ Highlights row — shows owner's highlight albums above "Recent updates"
 *   ✅ Firebase mute sync on onStart (cross-device consistency)
 *   ✅ Room offline-first fallback (unchanged)
 *   ✅ StatusCacheManager observer (unchanged)
 *   ✅ StatusMediaPreloader (unchanged)
 */
public class StatusFragment extends Fragment {

    public static final int TYPE_MY_STATUS      = 0;
    public static final int TYPE_SECTION_HEADER = 1;
    public static final int TYPE_CONTACT_STATUS = 2;

    // ── State ─────────────────────────────────────────────────────────────

    private StatusListAdapter adapter;

    private final Map<String, List<StatusItem>> statusMap  = new LinkedHashMap<>();
    private final Map<String, Set<String>>      seenMap    = new HashMap<>();
    private final List<StatusItem>              myStatuses = new ArrayList<>();
    private final List<StatusHighlightsManager.Album> highlights = new ArrayList<>();

    private ValueEventListener statusListener;
    private ValueEventListener seenListener;
    private String myUid;

    private StatusMediaPreloader mediaPreloader;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_status, parent, false);

        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { return v; }

        RecyclerView rv = v.findViewById(R.id.rv_status);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setItemAnimator(null);

        adapter = new StatusListAdapter(
                myUid,
                myStatuses,
                highlights,
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
                },
                /* onHighlightClick */ (album) -> {
                    // Open StatusViewerActivity with highlights items
                    // For now we pass ownerUid + a special flag;
                    // StatusViewerActivity can be extended to handle highlights if needed.
                    // Basic: open with myUid + album name
                    Intent i = new Intent(requireContext(), StatusViewerActivity.class);
                    i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID,  myUid);
                    i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, album.name);
                    i.putExtra("highlightAlbumId", album.id);
                    startActivity(i);
                }
        );
        rv.setAdapter(adapter);

        ExtendedFloatingActionButton fab = v.findViewById(R.id.fab_new_status);
        fab.setOnClickListener(x ->
                startActivity(new Intent(requireContext(), NewStatusActivity.class)));

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
        if (mediaPreloader == null && getContext() != null) {
            mediaPreloader = new StatusMediaPreloader(requireContext());
        }
        // Sync muted list from Firebase (cross-device consistency)
        if (getContext() != null) StatusMuteManager.syncFromFirebase(requireContext());

        seedFromStatusCache();
        loadFromRoom();
        loadStatuses();
        loadHighlights();

        StatusCacheManager.getInstance(requireContext()).addObserver(statusCacheObserver);
    }

    @Override
    public void onStop() {
        removeListeners();
        if (getContext() != null)
            StatusCacheManager.getInstance(getContext()).removeObserver(statusCacheObserver);
        if (mediaPreloader != null) { mediaPreloader.shutdown(); mediaPreloader = null; }
        super.onStop();
    }

    // ── Highlights ────────────────────────────────────────────────────────

    /**
     * Load the current user's highlight albums and refresh adapter.
     */
    private void loadHighlights() {
        if (myUid == null || getContext() == null) return;
        StatusHighlightsManager.loadAlbums(myUid, albums -> {
            highlights.clear();
            highlights.addAll(albums);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> adapter.notifyHighlightsChanged());
            }
        });
    }

    // ── StatusCacheManager observer ───────────────────────────────────────

    private final StatusCacheManager.StatusDataObserver statusCacheObserver = () -> {
        if (getActivity() != null)
            getActivity().runOnUiThread(this::seedFromStatusCache);
    };

    private void seedFromStatusCache() {
        if (myUid == null || getContext() == null) return;
        StatusCacheManager scm = StatusCacheManager.getInstance(getContext());
        java.util.Map<String, java.util.List<StatusItem>> cached = scm.getAllStatuses();
        if (cached.isEmpty()) return;
        for (java.util.Map.Entry<String, java.util.List<StatusItem>> e : cached.entrySet()) {
            String uid = e.getKey();
            java.util.List<StatusItem> items = e.getValue();
            if (myUid.equals(uid)) {
                if (myStatuses.isEmpty()) myStatuses.addAll(items);
            } else {
                if (!statusMap.containsKey(uid)) statusMap.put(uid, items);
            }
        }
        rebuildAdapter();
    }

    // ── Room offline-first ────────────────────────────────────────────────

    private void loadFromRoom() {
        if (getContext() == null || myUid == null) return;
        AppDatabase db = AppDatabase.getInstance(getContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            long now = System.currentTimeMillis();
            List<StatusEntity> cached = db.statusDao().getActiveStatuses(now);
            db.statusDao().pruneExpired(now);
            if (cached == null || cached.isEmpty()) return;
            Map<String, List<StatusItem>> roomMap = new LinkedHashMap<>();
            List<StatusItem> roomMine = new ArrayList<>();
            for (StatusEntity e : cached) {
                StatusItem item = entityToItem(e);
                if (myUid.equals(e.ownerUid)) roomMine.add(item);
                else roomMap.computeIfAbsent(e.ownerUid, k -> new ArrayList<>()).add(item);
            }
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (statusMap.isEmpty() && myStatuses.isEmpty()) {
                        statusMap.putAll(roomMap);
                        myStatuses.addAll(roomMine);
                        rebuildAdapter();
                    }
                });
            }
        });
    }

    // ── Firebase data loading ─────────────────────────────────────────────

    private void loadStatuses() {
        if (myUid == null) return;
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
                    attachStatusListener(Collections.singleton(myUid));
                }
            });
    }

    private void attachStatusListener(Set<String> watchedUids) {
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                long now = System.currentTimeMillis();
                statusMap.clear();
                myStatuses.clear();
                for (DataSnapshot userSnap : snap.getChildren()) {
                    String uid = userSnap.getKey();
                    if (uid == null || !watchedUids.contains(uid)) continue;
                    List<StatusItem> items = new ArrayList<>();
                    for (DataSnapshot stSnap : userSnap.getChildren()) {
                        StatusItem item = stSnap.getValue(StatusItem.class);
                        if (item == null || item.deleted) continue;
                        if (item.expiresAt != null && item.expiresAt < now) continue;
                        items.add(item);
                    }
                    items.sort((a, b) -> Long.compare(
                        a.timestamp == null ? 0 : a.timestamp,
                        b.timestamp == null ? 0 : b.timestamp));
                    if (uid.equals(myUid)) myStatuses.addAll(items);
                    else if (!items.isEmpty()) statusMap.put(uid, items);
                }
                rebuildAdapter();

                // Persist to Room
                if (getContext() != null) {
                    List<StatusEntity> toSave = new ArrayList<>();
                    for (StatusItem si : myStatuses) toSave.add(itemToEntity(si));
                    for (List<StatusItem> items : statusMap.values())
                        for (StatusItem si : items) toSave.add(itemToEntity(si));
                    if (!toSave.isEmpty()) {
                        AppDatabase db = AppDatabase.getInstance(getContext());
                        Executors.newSingleThreadExecutor().execute(() ->
                            db.statusDao().insertStatuses(toSave));
                    }
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getStatusRef().addValueEventListener(statusListener);
    }

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
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db()
            .getReference("statusSeen").child(myUid)
            .addValueEventListener(seenListener);
    }

    private void removeListeners() {
        if (statusListener != null) {
            FirebaseUtils.getStatusRef().removeEventListener(statusListener);
            statusListener = null;
        }
        if (seenListener != null && myUid != null) {
            FirebaseUtils.db()
                .getReference("statusSeen").child(myUid)
                .removeEventListener(seenListener);
            seenListener = null;
        }
    }

    // ── Adapter rebuild ───────────────────────────────────────────────────

    private void rebuildAdapter() {
        if (getContext() == null) return;
        List<StatusListAdapter.Entry> unseen = new ArrayList<>();
        List<StatusListAdapter.Entry> seen   = new ArrayList<>();

        for (Map.Entry<String, List<StatusItem>> e : statusMap.entrySet()) {
            String uid = e.getKey();

            // ── Mute filter — skip muted contacts ──
            if (StatusMuteManager.isMuted(requireContext(), uid)) continue;

            List<StatusItem> items = e.getValue();
            if (items.isEmpty()) continue;
            Set<String> seenIds = seenMap.getOrDefault(uid, Collections.emptySet());
            int unseenCount = 0;
            for (StatusItem item : items) {
                if (!seenIds.contains(item.id)) unseenCount++;
            }
            StatusItem latest = items.get(items.size() - 1);
            StatusListAdapter.Entry entry = new StatusListAdapter.Entry(
                    uid, latest.ownerName, latest.ownerPhoto,
                    latest.timestamp, items.size(), unseenCount, latest);
            if (unseenCount > 0) unseen.add(entry);
            else                 seen.add(entry);
        }

        Comparator<StatusListAdapter.Entry> byTime =
                (a, b) -> Long.compare(
                        b.latestTimestamp == null ? 0 : b.latestTimestamp,
                        a.latestTimestamp == null ? 0 : a.latestTimestamp);
        unseen.sort(byTime);
        seen.sort(byTime);
        adapter.update(unseen, seen);

        // Media preload
        if (mediaPreloader != null && !statusMap.isEmpty()) {
            for (StatusListAdapter.Entry entry : unseen) {
                List<StatusItem> items = statusMap.get(entry.ownerUid);
                if (items != null) mediaPreloader.preloadContactStatuses(items);
            }
            for (StatusListAdapter.Entry entry : seen) {
                List<StatusItem> items = statusMap.get(entry.ownerUid);
                if (items != null) mediaPreloader.preloadContactStatuses(items);
            }
        }
    }

    // ── Converters ────────────────────────────────────────────────────────

    private StatusEntity itemToEntity(StatusItem si) {
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

    private StatusItem entityToItem(StatusEntity e) {
        StatusItem item   = new StatusItem();
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
        return item;
    }
}
