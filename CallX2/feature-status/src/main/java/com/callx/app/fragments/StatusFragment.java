package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.status.R;
import com.callx.app.activities.*;
import com.callx.app.adapters.StatusListAdapter;
import com.callx.app.cache.StatusCacheManager;
import com.callx.app.cache.StatusMediaPreloader;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * StatusFragment v25 — Comprehensive status feed.
 *
 * NEW features:
 *   ✅ Search bar — filter contacts by name
 *   ✅ Mute support — muted contacts in separate "Muted" section
 *   ✅ Long-press → Mute / Unmute / Close friends context menu
 *   ✅ Archive shortcut button in "My Status" area
 *   ✅ Reaction preview in adapter entry
 *   ✅ Expiry label in "My Status" row
 *   ✅ Close Friends badge on matching contacts
 *   ✅ Empty state with "Add status" CTA
 *   ✅ Real-time updates via Firebase + Room fallback + StatusCacheManager
 *   ✅ Media preloader (Reels pattern)
 */
public class StatusFragment extends Fragment {

    private StatusListAdapter adapter;
    private final Map<String, List<StatusItem>> statusMap = new LinkedHashMap<>();
    private final Map<String, Set<String>>      seenMap   = new HashMap<>();
    private final List<StatusItem>              myStatuses = new ArrayList<>();

    private ValueEventListener statusListener;
    private ValueEventListener seenListener;
    private String myUid;

    private StatusMediaPreloader mediaPreloader;

    // Search
    private EditText etSearch;
    private String   searchQuery = "";

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_status, parent, false);

        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { return v; }

        // Search bar
        etSearch = v.findViewById(R.id.et_status_search);
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    searchQuery = s.toString().trim().toLowerCase();
                    rebuildAdapter();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        RecyclerView rv = v.findViewById(R.id.rv_status);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setItemAnimator(null);

        adapter = new StatusListAdapter(
            myUid, myStatuses,
            () -> {
                if (myStatuses.isEmpty())
                    startActivity(new Intent(requireContext(), NewStatusActivity.class));
                else {
                    Intent i = new Intent(requireContext(), StatusViewerActivity.class);
                    i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, myUid);
                    i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, "My Status");
                    startActivity(i);
                }
            },
            () -> startActivity(new Intent(requireContext(), NewStatusActivity.class)),
            (ownerUid, ownerName) -> {
                Intent i = new Intent(requireContext(), StatusViewerActivity.class);
                i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, ownerUid);
                i.putExtra(StatusViewerActivity.EXTRA_OWNER_NAME, ownerName);
                startActivity(i);
            },
            // Long-press → mute / unmute / close friends menu
            (ownerUid, ownerName, isMuted) -> showContactContextMenu(ownerUid, ownerName, isMuted)
        );
        rv.setAdapter(adapter);

        ExtendedFloatingActionButton fab = v.findViewById(R.id.fab_new_status);
        if (fab != null) {
            fab.setOnClickListener(x -> startActivity(new Intent(requireContext(), NewStatusActivity.class)));
            rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                    if (dy > 4) fab.shrink();
                    if (dy < -4) fab.extend();
                }
            });
        }

        // Archive shortcut
        View btnArchive = v.findViewById(R.id.btn_status_archive);
        if (btnArchive != null) {
            btnArchive.setOnClickListener(x ->
                startActivity(new Intent(requireContext(), StatusArchiveActivity.class)));
        }

        return v;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mediaPreloader == null && getContext() != null)
            mediaPreloader = new StatusMediaPreloader(requireContext());
        seedFromStatusCache();
        loadFromRoom();
        loadStatuses();
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

    // ── Long-press context menu ───────────────────────────────────────────

    private void showContactContextMenu(String ownerUid, String ownerName, boolean isMuted) {
        if (getContext() == null) return;
        boolean isCF = StatusCloseFriendsManager.isCloseFriend(getContext(), ownerUid);
        String muteLabel    = isMuted ? "Unmute " + ownerName : "Mute " + ownerName;
        String cfLabel      = isCF   ? "Remove from Close Friends" : "Add to Close Friends ⭐";
        String[] options    = {muteLabel, cfLabel, "Cancel"};

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(options, (d, which) -> {
                if (which == 0) {
                    StatusMuteManager.toggle(getContext(), ownerUid);
                    String msg = StatusMuteManager.isMuted(getContext(), ownerUid)
                            ? ownerName + " muted" : ownerName + " unmuted";
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                    rebuildAdapter();
                } else if (which == 1) {
                    StatusCloseFriendsManager.toggle(getContext(), myUid, ownerUid);
                    String msg = StatusCloseFriendsManager.isCloseFriend(getContext(), ownerUid)
                            ? ownerName + " added to Close Friends ⭐"
                            : ownerName + " removed from Close Friends";
                    Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                }
            }).show();
    }

    // ── StatusCacheManager observer ───────────────────────────────────────

    private final StatusCacheManager.StatusDataObserver statusCacheObserver = () -> {
        if (getActivity() != null) getActivity().runOnUiThread(this::seedFromStatusCache);
    };

    private void seedFromStatusCache() {
        if (myUid == null || getContext() == null) return;
        StatusCacheManager scm = StatusCacheManager.getInstance(getContext());
        Map<String, List<StatusItem>> cached = scm.getAllStatuses();
        if (cached.isEmpty()) return;
        for (Map.Entry<String, List<StatusItem>> e : cached.entrySet()) {
            String uid = e.getKey();
            List<StatusItem> items2 = e.getValue();
            if (myUid.equals(uid)) { if (myStatuses.isEmpty()) myStatuses.addAll(items2); }
            else { if (!statusMap.containsKey(uid)) statusMap.put(uid, items2); }
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

    // ── Firebase live ─────────────────────────────────────────────────────

    private void loadStatuses() {
        if (myUid == null) return;
        FirebaseUtils.getContactsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Set<String> watched = new HashSet<>();
                    watched.add(myUid);
                    for (DataSnapshot c : snap.getChildren()) if (c.getKey() != null) watched.add(c.getKey());
                    attachStatusListener(watched);
                    attachSeenListener();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    attachStatusListener(Collections.singleton(myUid));
                }
            });
    }

    private void attachStatusListener(Set<String> watchedUids) {
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                long now = System.currentTimeMillis();
                statusMap.clear(); myStatuses.clear();
                for (DataSnapshot userSnap : snap.getChildren()) {
                    String uid = userSnap.getKey();
                    if (uid == null || !watchedUids.contains(uid)) continue;
                    List<StatusItem> items2 = new ArrayList<>();
                    for (DataSnapshot stSnap : userSnap.getChildren()) {
                        StatusItem item = stSnap.getValue(StatusItem.class);
                        if (item == null || item.deleted) continue;
                        if (item.expiresAt != null && item.expiresAt < now) continue;
                        items2.add(item);
                    }
                    items2.sort((a, b) -> Long.compare(
                            a.timestamp == null ? 0 : a.timestamp,
                            b.timestamp == null ? 0 : b.timestamp));
                    if (uid.equals(myUid)) myStatuses.addAll(items2);
                    else if (!items2.isEmpty()) statusMap.put(uid, items2);
                }
                rebuildAdapter();
                saveToRoom();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getStatusRef().addValueEventListener(statusListener);
    }

    private void attachSeenListener() {
        seenListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                seenMap.clear();
                for (DataSnapshot ownerSnap : snap.getChildren()) {
                    String ownerUid = ownerSnap.getKey();
                    if (ownerUid == null) continue;
                    Set<String> seenIds = new HashSet<>();
                    for (DataSnapshot idSnap : ownerSnap.getChildren())
                        if (idSnap.getKey() != null) seenIds.add(idSnap.getKey());
                    seenMap.put(ownerUid, seenIds);
                }
                rebuildAdapter();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("statusSeen").child(myUid).addValueEventListener(seenListener);
    }

    private void removeListeners() {
        if (statusListener != null) { FirebaseUtils.getStatusRef().removeEventListener(statusListener); statusListener = null; }
        if (seenListener != null && myUid != null) {
            FirebaseUtils.db().getReference("statusSeen").child(myUid).removeEventListener(seenListener);
            seenListener = null;
        }
    }

    // ── Adapter rebuild with search + mute ───────────────────────────────

    private void rebuildAdapter() {
        if (getContext() == null) return;
        Set<String> mutedSet = StatusMuteManager.getMutedSet(getContext());

        List<StatusListAdapter.Entry> unseen = new ArrayList<>();
        List<StatusListAdapter.Entry> seen   = new ArrayList<>();
        List<StatusListAdapter.Entry> muted  = new ArrayList<>();

        for (Map.Entry<String, List<StatusItem>> e : statusMap.entrySet()) {
            String uid = e.getKey();
            List<StatusItem> items2 = e.getValue();
            if (items2.isEmpty()) continue;

            // Search filter
            StatusItem latest = items2.get(items2.size() - 1);
            if (!searchQuery.isEmpty()) {
                String name = latest.ownerName != null ? latest.ownerName.toLowerCase() : "";
                if (!name.contains(searchQuery)) continue;
            }

            Set<String> seenIds = seenMap.getOrDefault(uid, Collections.emptySet());
            int unseenCount = 0;
            for (StatusItem item : items2) if (!seenIds.contains(item.id)) unseenCount++;

            // Compute latest reaction across all viewers
            String latestReaction = null;
            if (latest.reactions != null && !latest.reactions.isEmpty()) {
                latestReaction = latest.reactions.values().iterator().next();
            }

            boolean isMuted = mutedSet.contains(uid);
            StatusListAdapter.Entry entry = new StatusListAdapter.Entry(
                    uid, latest.ownerName, latest.ownerPhoto,
                    latest.timestamp, items2.size(), unseenCount,
                    latest, isMuted, latestReaction);

            if (isMuted)           muted.add(entry);
            else if (unseenCount > 0) unseen.add(entry);
            else                   seen.add(entry);
        }

        Comparator<StatusListAdapter.Entry> byTime = (a, b) -> Long.compare(
                b.latestTimestamp == null ? 0 : b.latestTimestamp,
                a.latestTimestamp == null ? 0 : a.latestTimestamp);
        unseen.sort(byTime); seen.sort(byTime); muted.sort(byTime);

        adapter.update(unseen, seen, muted);

        // Media preload (unseen first)
        if (mediaPreloader != null) {
            for (StatusListAdapter.Entry en : unseen) {
                List<StatusItem> it = statusMap.get(en.ownerUid);
                if (it != null) mediaPreloader.preloadContactStatuses(it);
            }
            for (StatusListAdapter.Entry en : seen) {
                List<StatusItem> it = statusMap.get(en.ownerUid);
                if (it != null) mediaPreloader.preloadContactStatuses(it);
            }
        }
    }

    // ── Room save ─────────────────────────────────────────────────────────

    private void saveToRoom() {
        if (getContext() == null) return;
        List<StatusEntity> toSave = new ArrayList<>();
        for (StatusItem si : myStatuses) toSave.add(itemToEntity(si));
        for (List<StatusItem> items2 : statusMap.values()) for (StatusItem si : items2) toSave.add(itemToEntity(si));
        if (!toSave.isEmpty()) {
            AppDatabase db = AppDatabase.getInstance(getContext());
            Executors.newSingleThreadExecutor().execute(() -> db.statusDao().insertStatuses(toSave));
        }
    }

    // ── Converters ────────────────────────────────────────────────────────

    private StatusEntity itemToEntity(StatusItem si) {
        StatusEntity e = new StatusEntity();
        e.id = si.id != null ? si.id : ""; e.ownerUid = si.ownerUid; e.ownerName = si.ownerName;
        e.ownerPhoto = si.ownerPhoto; e.type = si.type; e.text = si.text;
        e.mediaUrl = si.mediaUrl; e.thumbnailUrl = si.thumbnailUrl;
        e.bgColor = si.bgColor; e.fontStyle = si.fontStyle; e.textColor = si.textColor;
        e.timestamp = si.timestamp; e.expiresAt = si.expiresAt; e.deleted = si.deleted;
        return e;
    }

    private StatusItem entityToItem(StatusEntity e) {
        StatusItem item = new StatusItem();
        item.id = e.id; item.ownerUid = e.ownerUid; item.ownerName = e.ownerName;
        item.ownerPhoto = e.ownerPhoto; item.type = e.type; item.text = e.text;
        item.mediaUrl = e.mediaUrl; item.thumbnailUrl = e.thumbnailUrl;
        item.bgColor = e.bgColor; item.fontStyle = e.fontStyle; item.textColor = e.textColor;
        item.timestamp = e.timestamp; item.expiresAt = e.expiresAt;
        item.deleted = e.deleted != null && e.deleted;
        return item;
    }
}
