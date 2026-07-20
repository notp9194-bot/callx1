package com.callx.app.feed;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.base.BaseFragment;                    // ← WhatsApp-level BaseFragment
import com.callx.app.db.entity.ChannelEntity;
import com.callx.app.db.entity.StatusEntity;
import com.callx.app.models.StatusItem;
import com.callx.app.status.R;
import com.callx.app.feed.StatusListAdapter;
import com.callx.app.channel.ChannelSectionAdapter;
import com.callx.app.channel.ChannelViewerActivity;
import com.callx.app.channel.ExploreChannelsActivity;
import com.callx.app.channel.CreateChannelInfoSheet;
import com.callx.app.viewmodel.ChannelViewModel;          // ← core ViewModel
import com.callx.app.viewmodel.StatusViewModel;            // ← core ViewModel
import com.callx.app.utils.*;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * StatusFragment v28 — WhatsApp-level architecture (shared core + feature layer).
 *
 * KEY CHANGES from v27:
 *   • Extends BaseFragment (common auth guard, runSafely helpers)
 *   • ChannelViewModel drives the channels section — NO direct Firebase calls for channels
 *   • StatusViewModel drives status loading — replaces scattered Firebase listeners
 *   • ChannelSectionAdapter now uses ChannelEntity (from core) instead of Channel model
 *   • ConcatAdapter: StatusListAdapter + ChannelSectionAdapter (unchanged)
 *
 * Data flow:
 *   Firebase → ChannelRepository/StatusRepository → Room
 *              → ChannelViewModel/StatusViewModel → LiveData → this Fragment
 */
public class StatusFragment extends BaseFragment {

    private StatusListAdapter    statusAdapter;
    private ChannelSectionAdapter channelAdapter;

    private ChannelViewModel channelViewModel;
    private StatusViewModel  statusViewModel;

    // Legacy live Firebase listeners kept for now (StatusListAdapter still needs them
    // for per-contact seen/mute/close-friends logic — will migrate in next sprint)
    private final Map<String, List<StatusItem>> statusMap  = new LinkedHashMap<>();
    private final Map<String, Set<String>>       seenMap   = new HashMap<>();
    private final List<StatusItem>               myStatuses = new ArrayList<>();
    private ValueEventListener statusListener;
    private ValueEventListener seenListener;
    private ValueEventListener highlightsListener;
    private final java.util.Set<String> blockedUids = new java.util.HashSet<>();

    private com.callx.app.cache.StatusMediaPreloader mediaPreloader;
    private EditText etSearch;
    private String   searchQuery = "";

    // ── Lifecycle ─────────────────────────────────────────────────────────
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_status, parent, false);
        if (!isAuthenticated()) return v;

        // ── ViewModels ───────────────────────────────────────────────────
        channelViewModel = new ViewModelProvider(this).get(ChannelViewModel.class);
        statusViewModel  = new ViewModelProvider(this).get(StatusViewModel.class);

        // ── Search ───────────────────────────────────────────────────────
        etSearch = v.findViewById(R.id.et_status_search);
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    searchQuery = s.toString().trim().toLowerCase();
                    rebuildStatusAdapter();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        RecyclerView rv = v.findViewById(R.id.rv_status);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setItemAnimator(null);

        // ── Status adapter ───────────────────────────────────────────────
        statusAdapter = new StatusListAdapter(
            myUid(), myStatuses,
            () -> {
                if (myStatuses.isEmpty())
                    startActivity(new Intent(requireContext(),
                        com.callx.app.compose.NewStatusActivity.class));
                else {
                    Intent i = new Intent(requireContext(),
                        com.callx.app.viewer.StatusViewerActivity.class);
                    i.putExtra(com.callx.app.viewer.StatusViewerActivity.EXTRA_OWNER_UID, myUid());
                    i.putExtra(com.callx.app.viewer.StatusViewerActivity.EXTRA_OWNER_NAME, "My Status");
                    startActivity(i);
                }
            },
            () -> startActivity(new Intent(requireContext(),
                com.callx.app.compose.NewStatusActivity.class)),
            (ownerUid, ownerName) -> {
                Intent i = new Intent(requireContext(),
                    com.callx.app.viewer.StatusViewerActivity.class);
                i.putExtra(com.callx.app.viewer.StatusViewerActivity.EXTRA_OWNER_UID, ownerUid);
                i.putExtra(com.callx.app.viewer.StatusViewerActivity.EXTRA_OWNER_NAME, ownerName);
                startActivity(i);
            },
            (ownerUid, ownerName, isMuted) -> showContactContextMenu(ownerUid, ownerName, isMuted)
        );
        statusAdapter.setHighlightClickListener(album -> {
            if (getContext() != null) {
                Intent i = new Intent(requireContext(),
                    com.callx.app.highlights.StatusHighlightsActivity.class);
                i.putExtra("albumTitle", album.title);
                startActivity(i);
            }
        });

        // ── Channel section adapter ──────────────────────────────────────
        channelAdapter = new ChannelSectionAdapter();
        wireChannelAdapterCallbacks();

        // ── Observe ChannelViewModel LiveData (WhatsApp-level pattern) ───
        channelViewModel.followedChannels.observe(getViewLifecycleOwner(),
            followed -> channelAdapter.setFollowedChannels(followed));
        channelViewModel.suggestedChannels.observe(getViewLifecycleOwner(),
            suggested -> channelAdapter.setSuggestedChannels(suggested));
        channelViewModel.toastMessage.observe(getViewLifecycleOwner(), msg -> {
            if (msg != null && !msg.isEmpty() && getContext() != null)
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        });

        // ── ConcatAdapter ─────────────────────────────────────────────────
        ConcatAdapter concatAdapter = new ConcatAdapter(
            new ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
            statusAdapter, channelAdapter);
        rv.setAdapter(concatAdapter);

        // FABs
        com.google.android.material.floatingactionbutton.FloatingActionButton fabCamera =
            v.findViewById(R.id.fab_camera_status);
        com.google.android.material.floatingactionbutton.FloatingActionButton fabEdit =
            v.findViewById(R.id.fab_edit_status);
        View.OnClickListener openComposer =
            x -> startActivity(new Intent(requireContext(),
                com.callx.app.compose.NewStatusActivity.class));
        if (fabCamera != null) fabCamera.setOnClickListener(openComposer);
        if (fabEdit   != null) fabEdit.setOnClickListener(openComposer);
        if (fabCamera != null && fabEdit != null) {
            rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                    if (dy > 4)  { fabCamera.hide(); fabEdit.hide(); }
                    if (dy < -4) { fabCamera.show(); fabEdit.show(); }
                }
            });
        }

        View btnArchive = v.findViewById(R.id.btn_status_archive);
        if (btnArchive != null)
            btnArchive.setOnClickListener(x ->
                startActivity(new Intent(requireContext(),
                    com.callx.app.archive.StatusArchiveActivity.class)));

        return v;
    }

    @Override public void onStart() {
        super.onStart();
        if (mediaPreloader == null && getContext() != null)
            mediaPreloader = new com.callx.app.cache.StatusMediaPreloader(requireContext());

        // Channels: trigger Firebase → Room sync via ViewModel
        channelViewModel.refresh();

        // Status: legacy listeners (will move to StatusViewModel in next sprint)
        seedFromStatusCache();
        loadFromRoom();
        loadStatuses();
        loadHighlights();

        com.callx.app.cache.StatusCacheManager.getInstance(requireContext())
            .addObserver(statusCacheObserver);
    }

    @Override public void onStop() {
        removeListeners();
        if (getContext() != null)
            com.callx.app.cache.StatusCacheManager
                .getInstance(getContext()).removeObserver(statusCacheObserver);
        if (mediaPreloader != null) { mediaPreloader.shutdown(); mediaPreloader = null; }
        super.onStop();
    }

    // ── Channel adapter callbacks ─────────────────────────────────────────
    private void wireChannelAdapterCallbacks() {
        channelAdapter.onExploreClick = () -> {
            if (getContext() != null)
                startActivity(new Intent(requireContext(), ExploreChannelsActivity.class));
        };
        channelAdapter.onExploreMoreClick = channelAdapter.onExploreClick;
        channelAdapter.onChannelClick = ch -> {
            if (getContext() != null) {
                Intent i = new Intent(requireContext(), ChannelViewerActivity.class);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ID,       ch.id);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_NAME,     ch.name);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ICON,     ch.iconUrl);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_VERIFIED, ch.verified);
                i.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_FOLLOWERS, ch.followers);
                startActivity(i);
            }
        };
        channelAdapter.onFollowClick    = ch -> channelViewModel.followChannel(ch);
        channelAdapter.onUnfollowClick  = ch -> channelViewModel.unfollowChannel(ch);
        channelAdapter.onDismissSuggested = ch -> {};
        channelAdapter.onCreateChannelClick = () -> {
            if (getContext() != null)
                new CreateChannelInfoSheet()
                    .show(getParentFragmentManager(), CreateChannelInfoSheet.TAG);
        };

        // ── Mute / Unmute / Notification Settings (long-press menu callbacks) ─
        channelAdapter.onMuteClick = ch -> {
            if (getContext() == null) return;
            String[] options = {"For 8 hours", "For 1 week", "Always"};
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Mute " + (ch.name != null ? ch.name : "channel") + " notifications")
                .setItems(options, (d, which) -> {
                    long until;
                    if      (which == 0) until = System.currentTimeMillis() + 8L  * 3_600_000L;
                    else if (which == 1) until = System.currentTimeMillis() + 7L * 24L * 3_600_000L;
                    else                 until = 0L; // permanent
                    channelViewModel.muteChannel(ch, until);
                })
                .show();
        };

        channelAdapter.onUnmuteClick = ch -> {
            if (ch != null) channelViewModel.unmuteChannel(ch);
        };

        channelAdapter.onNotifSettingsClick = ch -> {
            if (getContext() != null && ch != null) {
                Intent i = new Intent(requireContext(),
                    com.callx.app.channel.ChannelNotificationSettingsActivity.class);
                i.putExtra(com.callx.app.channel.ChannelNotificationSettingsActivity.EXTRA_CHANNEL_ID,   ch.id);
                i.putExtra(com.callx.app.channel.ChannelNotificationSettingsActivity.EXTRA_CHANNEL_NAME, ch.name);
                startActivity(i);
            }
        };
    }

    // ── Status legacy section (unchanged — Firebase listeners) ────────────
    private final com.callx.app.cache.StatusCacheManager.StatusDataObserver statusCacheObserver =
        () -> { if (getActivity() != null) getActivity().runOnUiThread(this::seedFromStatusCache); };

    private void seedFromStatusCache() {
        if (!isAuthenticated() || getContext() == null) return;
        com.callx.app.cache.StatusCacheManager scm =
            com.callx.app.cache.StatusCacheManager.getInstance(getContext());
        Map<String, List<StatusItem>> cached = scm.getAllStatuses();
        if (cached.isEmpty()) return;
        for (Map.Entry<String, List<StatusItem>> e : cached.entrySet()) {
            String uid = e.getKey();
            List<StatusItem> items2 = e.getValue();
            if (myUid().equals(uid)) { if (myStatuses.isEmpty()) myStatuses.addAll(items2); }
            else { if (!statusMap.containsKey(uid)) statusMap.put(uid, items2); }
        }
        rebuildStatusAdapter();
    }

    private void loadFromRoom() {
        if (getContext() == null || !isAuthenticated()) return;
        com.callx.app.db.AppDatabase db = com.callx.app.db.AppDatabase.getInstance(getContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            long now = System.currentTimeMillis();
            List<com.callx.app.db.entity.StatusEntity> cached =
                db.statusDao().getActiveStatuses(now);
            db.statusDao().pruneExpired(now);
            if (cached == null || cached.isEmpty()) return;
            Map<String, List<StatusItem>> roomMap = new LinkedHashMap<>();
            List<StatusItem> roomMine = new ArrayList<>();
            for (com.callx.app.db.entity.StatusEntity e : cached) {
                StatusItem item = entityToItem(e);
                if (myUid().equals(e.ownerUid)) roomMine.add(item);
                else roomMap.computeIfAbsent(e.ownerUid, k -> new ArrayList<>()).add(item);
            }
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (statusMap.isEmpty() && myStatuses.isEmpty()) {
                        statusMap.putAll(roomMap);
                        myStatuses.addAll(roomMine);
                        rebuildStatusAdapter();
                    }
                });
            }
        });
    }

    private void loadStatuses() {
        if (!isAuthenticated()) return;
        FirebaseUtils.getBlocksRef(myUid()).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    blockedUids.clear();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) blockedUids.add(ds.getKey());
                    loadStatusContacts();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { loadStatusContacts(); }
            });
    }

    private void loadStatusContacts() {
        FirebaseUtils.getContactsRef(myUid()).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Set<String> watched = new HashSet<>();
                    watched.add(myUid());
                    for (DataSnapshot c : snap.getChildren())
                        if (c.getKey() != null) watched.add(c.getKey());
                    attachStatusListener(watched);
                    attachSeenListener();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    attachStatusListener(Collections.singleton(myUid()));
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
                    if (uid.equals(myUid())) myStatuses.addAll(items2);
                    else if (!items2.isEmpty() && !blockedUids.contains(uid))
                        statusMap.put(uid, items2);
                }
                rebuildStatusAdapter();
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
                rebuildStatusAdapter();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("statusSeen").child(myUid())
            .addValueEventListener(seenListener);
    }

    private void loadHighlights() {
        highlightsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<StatusListAdapter.HighlightAlbum> albums = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    String title    = child.child("title").getValue(String.class);
                    String coverUrl = child.child("coverUrl").getValue(String.class);
                    if (title != null) albums.add(new StatusListAdapter.HighlightAlbum(title, coverUrl));
                }
                if (getActivity() != null)
                    getActivity().runOnUiThread(() -> statusAdapter.updateHighlights(albums));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getStatusHighlightsRef(myUid()).addValueEventListener(highlightsListener);
    }

    private void removeListeners() {
        if (statusListener != null) {
            FirebaseUtils.getStatusRef().removeEventListener(statusListener);
            statusListener = null;
        }
        if (seenListener != null) {
            FirebaseUtils.db().getReference("statusSeen").child(myUid())
                .removeEventListener(seenListener);
            seenListener = null;
        }
        if (highlightsListener != null) {
            FirebaseUtils.getStatusHighlightsRef(myUid()).removeEventListener(highlightsListener);
            highlightsListener = null;
        }
    }

    private void rebuildStatusAdapter() {
        if (getContext() == null) return;
        Set<String> mutedSet = StatusMuteManager.getMutedSet(getContext());
        List<StatusListAdapter.Entry> unseen = new ArrayList<>();
        List<StatusListAdapter.Entry> seen   = new ArrayList<>();
        List<StatusListAdapter.Entry> muted  = new ArrayList<>();
        for (Map.Entry<String, List<StatusItem>> e : statusMap.entrySet()) {
            String uid = e.getKey();
            List<StatusItem> items2 = e.getValue();
            if (items2.isEmpty()) continue;
            StatusItem latest = items2.get(items2.size() - 1);
            if (!searchQuery.isEmpty()) {
                String name = latest.ownerName != null ? latest.ownerName.toLowerCase() : "";
                if (!name.contains(searchQuery)) continue;
            }
            Set<String> seenIds = seenMap.getOrDefault(uid, Collections.emptySet());
            int unseenCount = 0;
            for (StatusItem item : items2) if (!seenIds.contains(item.id)) unseenCount++;
            String latestReaction = null;
            if (latest.reactions != null && !latest.reactions.isEmpty())
                latestReaction = latest.reactions.values().iterator().next();
            boolean isMutedContact = mutedSet.contains(uid);
            boolean isCF = StatusCloseFriendsManager.isCloseFriend(getContext(), uid);
            StatusListAdapter.Entry entry = new StatusListAdapter.Entry(
                uid, latest.ownerName, latest.ownerPhoto,
                latest.timestamp, items2.size(), unseenCount,
                latest, isMutedContact, latestReaction, isCF);
            if (isMutedContact)       muted.add(entry);
            else if (unseenCount > 0) unseen.add(entry);
            else                      seen.add(entry);
        }
        Comparator<StatusListAdapter.Entry> byTime = (a, b) -> Long.compare(
            b.latestTimestamp == null ? 0 : b.latestTimestamp,
            a.latestTimestamp == null ? 0 : a.latestTimestamp);
        unseen.sort(byTime); seen.sort(byTime); muted.sort(byTime);
        statusAdapter.update(unseen, seen, muted);
        if (mediaPreloader != null) {
            for (StatusListAdapter.Entry en : unseen) {
                List<StatusItem> it = statusMap.get(en.ownerUid);
                if (it != null) mediaPreloader.preloadContactStatuses(it);
            }
        }
    }

    private void showContactContextMenu(String ownerUid, String ownerName, boolean isMuted) {
        if (getContext() == null) return;
        boolean isCF  = StatusCloseFriendsManager.isCloseFriend(getContext(), ownerUid);
        String muteLabel = isMuted ? "Unmute " + ownerName : "Mute " + ownerName;
        String cfLabel   = isCF   ? "Remove from Close Friends" : "Add to Close Friends ⭐";
        String[] options = {muteLabel, cfLabel, "Cancel"};
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(options, (d, which) -> {
                if (which == 0) {
                    StatusMuteManager.toggle(getContext(), ownerUid);
                    Toast.makeText(getContext(),
                        StatusMuteManager.isMuted(getContext(), ownerUid)
                            ? ownerName + " muted" : ownerName + " unmuted",
                        Toast.LENGTH_SHORT).show();
                    rebuildStatusAdapter();
                } else if (which == 1) {
                    StatusCloseFriendsManager.toggle(getContext(), myUid(), ownerUid);
                    Toast.makeText(getContext(),
                        StatusCloseFriendsManager.isCloseFriend(getContext(), ownerUid)
                            ? ownerName + " added to Close Friends ⭐"
                            : ownerName + " removed from Close Friends",
                        Toast.LENGTH_SHORT).show();
                    rebuildStatusAdapter();
                }
            }).show();
    }

    private void saveToRoom() {
        if (getContext() == null) return;
        List<com.callx.app.db.entity.StatusEntity> toSave = new ArrayList<>();
        for (StatusItem si : myStatuses) toSave.add(itemToEntity(si));
        for (List<StatusItem> items2 : statusMap.values())
            for (StatusItem si : items2) toSave.add(itemToEntity(si));
        if (!toSave.isEmpty()) {
            com.callx.app.db.AppDatabase db =
                com.callx.app.db.AppDatabase.getInstance(getContext());
            Executors.newSingleThreadExecutor().execute(() ->
                db.statusDao().insertStatuses(toSave));
        }
    }

    // ── Converters ────────────────────────────────────────────────────────
    private com.callx.app.db.entity.StatusEntity itemToEntity(StatusItem si) {
        com.callx.app.db.entity.StatusEntity e = new com.callx.app.db.entity.StatusEntity();
        e.id = si.id != null ? si.id : "";
        e.ownerUid = si.ownerUid; e.ownerName = si.ownerName; e.ownerPhoto = si.ownerPhoto;
        e.type = si.type; e.text = si.text; e.mediaUrl = si.mediaUrl;
        e.thumbnailUrl = si.thumbnailUrl; e.bgColor = si.bgColor;
        e.fontStyle = si.fontStyle; e.textColor = si.textColor;
        e.timestamp = si.timestamp; e.expiresAt = si.expiresAt; e.deleted = si.deleted;
        return e;
    }

    private StatusItem entityToItem(com.callx.app.db.entity.StatusEntity e) {
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
