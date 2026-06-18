package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import com.callx.app.activities.*;
import com.callx.app.adapters.StatusListAdapter;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * StatusFragment — Main "Status" tab of the app.
 *
 * Layout sections:
 *   [My Status row]          — Tap = view own; Long-press = options (delete/archive/highlights)
 *   [Section: Recent Updates] — Contacts with new (unseen) statuses; ordered by newest
 *   [Section: Viewed Updates] — Contacts whose statuses you've already seen
 *   [Muted Contacts section]  — shown if user has muted contacts
 *
 * Real-time Firebase listeners:
 *   - Own statuses: statuses/{myUid}
 *   - All contacts: statuses/{contactUid} for each contact in getContactsRef(myUid)
 *
 * DiffUtil animations on every list change.
 * StatusMuteManager filters muted contacts out of main list.
 * StatusPrivacyManager used for viewer-side filtering.
 */
public class StatusFragment extends Fragment {

    // ── Views ──────────────────────────────────────────────────────────────
    private RecyclerView             recyclerView;
    private StatusListAdapter        adapter;
    private ExtendedFloatingActionButton fabNew;
    private View                     emptyView;
    private ProgressBar              progressBar;
    private TextView                 tvEmpty;

    // ── Data ───────────────────────────────────────────────────────────────
    /** uid → list of that user's active StatusItems */
    private final Map<String, List<StatusItem>> statusMap = new ConcurrentHashMap<>();
    /** uid → display name (for ordering + adapter) */
    private final Map<String, String> nameMap = new ConcurrentHashMap<>();

    // ── Firebase ───────────────────────────────────────────────────────────
    private String myUid;
    private ValueEventListener contactsListener;
    /** uid → their status listener */
    private final Map<String, ValueEventListener> statusListeners = new ConcurrentHashMap<>();
    private ValueEventListener myStatusListener;

    // ── Threading ──────────────────────────────────────────────────────────
    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        // Assume layout: fragment_status.xml
        View v = inflater.inflate(
            getResources().getIdentifier("fragment_status", "layout",
                requireContext().getPackageName()),
            container, false);
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(
            getResId("rv_status_list"));
        fabNew       = view.findViewById(getResId("fab_new_status"));
        emptyView    = view.findViewById(getResId("layout_empty"));
        progressBar  = view.findViewById(getResId("progress_bar"));

        // Setup RecyclerView
        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        recyclerView.setLayoutManager(llm);
        recyclerView.setHasFixedSize(false);

        adapter = new StatusListAdapter(requireContext(), new StatusListAdapter.Callbacks() {
            @Override public void onMyStatusClick() { openMyStatus(); }
            @Override public void onMyStatusLongPress() { showMyStatusOptions(); }
            @Override public void onContactStatusClick(String contactUid, List<StatusItem> items) {
                openViewer(contactUid, items);
            }
            @Override public void onMuteToggle(String contactUid, boolean mute) {
                handleMuteToggle(contactUid, mute);
            }
        });
        recyclerView.setAdapter(adapter);

        // FAB → NewStatusActivity
        fabNew.setOnClickListener(v2 -> {
            Intent i = new Intent(requireContext(), NewStatusActivity.class);
            startActivity(i);
        });

        // Collapse FAB on scroll
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 8 && fabNew.isExtended()) fabNew.shrink();
                else if (dy < -8 && !fabNew.isExtended()) fabNew.extend();
            }
        });

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        if (myUid != null) {
            loadMyStatus();
            loadContacts();
        }
    }

    // ── My status ─────────────────────────────────────────────────────────
    private void loadMyStatus() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        myStatusListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<StatusItem> myItems = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    StatusItem item = child.getValue(StatusItem.class);
                    if (item == null) continue;
                    if (item.deleted) continue;
                    if (item.statusId == null) item.statusId = child.getKey();
                    myItems.add(item);
                }
                // Sort by timestamp desc
                myItems.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                statusMap.put(myUid, myItems);
                scheduleRebuild();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getUserStatusRef(myUid)
            .orderByChild("timestamp").startAt(cutoff)
            .addValueEventListener(myStatusListener);
    }

    // ── Contacts ──────────────────────────────────────────────────────────
    private void loadContacts() {
        contactsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Set<String> contactUids = new HashSet<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid  = c.getKey();
                    String name = c.child("name").getValue(String.class);
                    if (uid != null) {
                        contactUids.add(uid);
                        nameMap.put(uid, name != null ? name : "");
                    }
                }
                // Subscribe new contacts
                for (String uid : contactUids) {
                    if (!statusListeners.containsKey(uid)) watchContactStatus(uid);
                }
                // Remove stale listeners
                Iterator<String> it = statusListeners.keySet().iterator();
                while (it.hasNext()) {
                    String uid = it.next();
                    if (!contactUids.contains(uid)) {
                        FirebaseUtils.getUserStatusRef(uid)
                            .removeEventListener(statusListeners.get(uid));
                        statusMap.remove(uid);
                        it.remove();
                    }
                }
                scheduleRebuild();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getContactsRef(myUid).addValueEventListener(contactsListener);
    }

    private void watchContactStatus(String contactUid) {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<StatusItem> items = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    StatusItem item = child.getValue(StatusItem.class);
                    if (item == null) continue;
                    if (item.deleted || item.archived) continue;
                    if (item.expiresAt > 0 && System.currentTimeMillis() > item.expiresAt) continue;
                    if (item.statusId == null) item.statusId = child.getKey();
                    items.add(item);
                }
                items.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
                if (items.isEmpty()) statusMap.remove(contactUid);
                else                 statusMap.put(contactUid, items);
                scheduleRebuild();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getUserStatusRef(contactUid)
            .orderByChild("timestamp").startAt(cutoff)
            .addValueEventListener(listener);
        statusListeners.put(contactUid, listener);
    }

    // ── List rebuild ──────────────────────────────────────────────────────
    private volatile boolean rebuildPending = false;

    private void scheduleRebuild() {
        if (rebuildPending) return;
        rebuildPending = true;
        buildExecutor.execute(() -> {
            rebuildPending = false;
            buildListAndPost();
        });
    }

    private void buildListAndPost() {
        if (myUid == null || getContext() == null) return;

        StatusMuteManager muter = StatusMuteManager.get(requireContext());

        // My statuses
        List<StatusItem> myStatuses = statusMap.getOrDefault(myUid, Collections.emptyList());

        // Contacts — split into unseen / seen
        List<StatusListAdapter.ContactRow> unseen = new ArrayList<>();
        List<StatusListAdapter.ContactRow> seen   = new ArrayList<>();

        for (Map.Entry<String, List<StatusItem>> entry : statusMap.entrySet()) {
            String uid = entry.getKey();
            if (uid.equals(myUid)) continue;
            if (muter.isMuted(uid)) continue;

            List<StatusItem> items = entry.getValue();
            if (items.isEmpty()) continue;

            String name = nameMap.getOrDefault(uid, "Contact");
            boolean anyUnseen = false;
            int unseenCount = 0;
            for (StatusItem item : items) {
                if (!StatusSeenTracker.get().hasSeenLocally(item.statusId, myUid)) {
                    anyUnseen = true;
                    unseenCount++;
                }
            }

            // Timestamp of newest status for sorting
            long newestTs = items.get(items.size() - 1).timestamp;

            StatusListAdapter.ContactRow row = new StatusListAdapter.ContactRow(
                uid, name,
                items.get(0).ownerPhoto,
                items, anyUnseen, unseenCount, newestTs);

            if (anyUnseen) unseen.add(row);
            else           seen.add(row);
        }

        // Sort unseen by newest status desc, seen by newest desc
        Comparator<StatusListAdapter.ContactRow> byCtime =
            (a, b) -> Long.compare(b.newestTimestamp, a.newestTimestamp);
        unseen.sort(byCtime);
        seen.sort(byCtime);

        List<StatusListAdapter.ListItem> finalList = new ArrayList<>();
        finalList.add(new StatusListAdapter.ListItem(
            StatusListAdapter.TYPE_MY_STATUS, myStatuses));

        if (!unseen.isEmpty()) {
            finalList.add(new StatusListAdapter.ListItem(
                StatusListAdapter.TYPE_HEADER, "Recent updates"));
            for (StatusListAdapter.ContactRow r : unseen)
                finalList.add(new StatusListAdapter.ListItem(
                    StatusListAdapter.TYPE_CONTACT, r));
        }

        if (!seen.isEmpty()) {
            finalList.add(new StatusListAdapter.ListItem(
                StatusListAdapter.TYPE_HEADER, "Viewed updates"));
            for (StatusListAdapter.ContactRow r : seen)
                finalList.add(new StatusListAdapter.ListItem(
                    StatusListAdapter.TYPE_CONTACT, r));
        }

        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            adapter.submitList(finalList);
            boolean empty = unseen.isEmpty() && seen.isEmpty() && myStatuses.isEmpty();
            if (emptyView != null) emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (progressBar != null) progressBar.setVisibility(View.GONE);
        });
    }

    // ── Actions ───────────────────────────────────────────────────────────
    private void openMyStatus() {
        Intent i = new Intent(requireContext(), MyStatusActivity.class);
        startActivity(i);
    }

    private void showMyStatusOptions() {
        // Options: Add to Status, View My Status, Status Archive, Status Highlights
        String[] options = {"Add to Status", "View My Status", "Status Archive",
            "Status Highlights", "Privacy Settings"};
        new android.app.AlertDialog.Builder(requireContext())
            .setItems(options, (d, which) -> {
                switch (which) {
                    case 0: startActivity(new Intent(requireContext(), NewStatusActivity.class)); break;
                    case 1: openMyStatus(); break;
                    case 2: startActivity(new Intent(requireContext(), StatusArchiveActivity.class)); break;
                    case 3: startActivity(new Intent(requireContext(), StatusHighlightsActivity.class)); break;
                    case 4: openPrivacySettings(); break;
                }
            }).show();
    }

    private void openViewer(String contactUid, List<StatusItem> items) {
        Intent i = new Intent(requireContext(), StatusViewerActivity.class);
        i.putExtra(StatusViewerActivity.EXTRA_OWNER_UID, contactUid);
        startActivity(i);
    }

    private void handleMuteToggle(String uid, boolean mute) {
        StatusMuteManager mgr = StatusMuteManager.get(requireContext());
        if (mute) mgr.mute(uid);
        else      mgr.unmute(uid);
        scheduleRebuild();
    }

    private void openPrivacySettings() {
        Intent i = new Intent(requireContext(), StatusPrivacySettingsActivity.class);
        startActivity(i);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (myUid != null) {
            if (myStatusListener != null)
                FirebaseUtils.getUserStatusRef(myUid).removeEventListener(myStatusListener);
            if (contactsListener != null)
                FirebaseUtils.getContactsRef(myUid).removeEventListener(contactsListener);
        }
        for (Map.Entry<String, ValueEventListener> e : statusListeners.entrySet()) {
            FirebaseUtils.getUserStatusRef(e.getKey()).removeEventListener(e.getValue());
        }
        statusListeners.clear();
        buildExecutor.shutdownNow();
    }

    private int getResId(String name) {
        return getResources().getIdentifier(name, "id", requireContext().getPackageName());
    }
}
