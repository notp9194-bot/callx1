package com.callx.app.feed;
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
  import com.callx.app.feed.StatusListAdapter;
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
  import com.callx.app.compose.NewStatusActivity;
  import com.callx.app.archive.StatusArchiveActivity;
  import com.callx.app.viewer.StatusViewerActivity;
  import com.callx.app.utils.StatusCloseFriendsManager;
  import com.callx.app.utils.StatusMuteManager;
  import com.callx.app.highlights.StatusHighlightsActivity;
  /**
   * StatusFragment v26 — Comprehensive status feed.
   *
   * FIXES v26:
   *   FIX: isCloseFriend flag passed to StatusListAdapter.Entry (was always false / missing)
   *   FIX: Highlights loaded from Firebase and shown via adapter.updateHighlights()
   *   FIX: Adapter highlight click → opens StatusHighlightsActivity
   *
   * ORIGINAL (v25):
   *   Search bar, Mute support, Long-press menu, Archive shortcut,
   *   Reaction preview, Expiry label, Empty state, Firebase + Room,
   *   StatusCacheManager, Media preloader.
   */
  public class StatusFragment extends Fragment {
      private StatusListAdapter adapter;
      private final Map<String, List<StatusItem>> statusMap = new LinkedHashMap<>();
      private final Map<String, Set<String>>      seenMap   = new HashMap<>();
      private final List<StatusItem>              myStatuses = new ArrayList<>();
      private ValueEventListener statusListener;
      private ValueEventListener seenListener;
      private ValueEventListener highlightsListener; // FIX: new
      private String myUid;
      private final java.util.Set<String> blockedUids = new java.util.HashSet<>();
      private StatusMediaPreloader mediaPreloader;
      private EditText etSearch;
      private String   searchQuery = "";
      // FIX (screenshot parity): Channels section, purely local (no backend yet).
      private com.callx.app.channels.ChannelsRepository channelsRepo;
      // ── Lifecycle ─────────────────────────────────────────────────────────
      @Nullable @Override
      public View onCreateView(@NonNull LayoutInflater inflater,
                               @Nullable ViewGroup parent,
                               @Nullable Bundle savedInstanceState) {
          View v = inflater.inflate(R.layout.fragment_status, parent, false);
          try { myUid = FirebaseUtils.getCurrentUid(); }
          catch (Exception e) { return v; }
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
              (ownerUid, ownerName, isMuted) -> showContactContextMenu(ownerUid, ownerName, isMuted)
          );
          // FIX: Highlight click → open StatusHighlightsActivity
          adapter.setHighlightClickListener(album -> {
              if (getContext() != null) {
                  Intent i = new Intent(requireContext(), StatusHighlightsActivity.class);
                  i.putExtra("albumTitle", album.title);
                  startActivity(i);
              }
          });
          rv.setAdapter(adapter);
          loadMyPhotoUrl(); // FIX (screenshot parity): populate "Add status" tile avatar

          // FIX (screenshot parity): Channels section wiring
          channelsRepo = new com.callx.app.channels.ChannelsRepository(requireContext());
          adapter.setChannelsListener(new StatusListAdapter.ChannelsListener() {
              @Override public void onChannelClick(com.callx.app.channels.ChannelItem channel) {
                  startActivity(com.callx.app.channels.ChannelViewActivity.intent(requireContext(), channel.id));
              }
              @Override public void onFollowClick(com.callx.app.channels.ChannelItem channel) {
                  channelsRepo.setFollowing(channel.id, true);
                  refreshChannels();
              }
              @Override public void onDismissClick(com.callx.app.channels.ChannelItem channel) {
                  channelsRepo.dismissSuggestion(channel.id);
                  refreshChannels();
              }
              @Override public void onExploreClick() {
                  if (getContext() == null) return;
                  com.callx.app.channels.ChannelsExploreBottomSheet
                          .newInstance(channelsRepo, StatusFragment.this::refreshChannels)
                          .show(getParentFragmentManager(), "explore_channels");
              }
              @Override public void onFindLabelToggle() {
                  boolean expanded = !channelsRepo.isSuggestionsExpanded();
                  channelsRepo.setSuggestionsExpanded(expanded);
                  refreshChannels();
              }
          });
          refreshChannels();
          View.OnClickListener openComposer = x -> startActivity(new Intent(requireContext(), NewStatusActivity.class));
          com.google.android.material.floatingactionbutton.FloatingActionButton fabCamera =
                  v.findViewById(R.id.fab_camera_status);
          com.google.android.material.floatingactionbutton.FloatingActionButton fabEdit =
                  v.findViewById(R.id.fab_edit_status);
          if (fabCamera != null) fabCamera.setOnClickListener(openComposer);
          if (fabEdit != null) fabEdit.setOnClickListener(openComposer);
          if (fabCamera != null && fabEdit != null) {
              rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                  @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                      if (dy > 4) { fabCamera.hide(); fabEdit.hide(); }
                      if (dy < -4) { fabCamera.show(); fabEdit.show(); }
                  }
              });
          }
          View btnArchive = v.findViewById(R.id.btn_status_archive);
          if (btnArchive != null)
              btnArchive.setOnClickListener(x ->
                  startActivity(new Intent(requireContext(), StatusArchiveActivity.class)));
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
          loadHighlights(); // FIX: new
          refreshChannels(); // FIX (screenshot parity): pick up follow/read state changed elsewhere
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
      // FIX (screenshot parity): re-reads channel follow/dismiss/expand state and pushes
      // it into the adapter — called after any follow/unfollow/dismiss/toggle action.
      private void refreshChannels() {
          if (channelsRepo == null || adapter == null) return;
          adapter.setChannels(channelsRepo.getFollowed(), channelsRepo.getSuggestions(),
                  channelsRepo.isSuggestionsExpanded());
      }
      // ── FIX (screenshot parity): resolve own profile photo for "Add status" tile ─
      private void loadMyPhotoUrl() {
          if (myUid == null || myUid.isEmpty()) return;
          FirebaseUtils.getUserRef(myUid).child("photoUrl")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      String url = snap.getValue(String.class);
                      if (adapter != null) adapter.setMyPhotoUrl(url);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }
      // ── FIX: Load highlights from Firebase ───────────────────────────────
      private void loadHighlights() {
          if (myUid == null) return;
          highlightsListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  List<StatusListAdapter.HighlightAlbum> albums = new ArrayList<>();
                  for (DataSnapshot child : snap.getChildren()) {
                      String title    = child.child("title").getValue(String.class);
                      String coverUrl = child.child("coverUrl").getValue(String.class);
                      if (title != null)
                          albums.add(new StatusListAdapter.HighlightAlbum(title, coverUrl));
                  }
                  if (getActivity() != null)
                      getActivity().runOnUiThread(() -> adapter.updateHighlights(albums));
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          };
          FirebaseUtils.db()
              .getReference("statusHighlights")
              .child(myUid)
              .addValueEventListener(highlightsListener);
      }
      // ── Long-press context menu ───────────────────────────────────────────
      private void showContactContextMenu(String ownerUid, String ownerName, boolean isMuted) {
          if (getContext() == null) return;
          boolean isCF   = StatusCloseFriendsManager.isCloseFriend(getContext(), ownerUid);
          String muteLabel = isMuted ? "Unmute " + ownerName : "Mute " + ownerName;
          String cfLabel   = isCF   ? "Remove from Close Friends" : "Add to Close Friends \u2B50";
          String[] options = {muteLabel, cfLabel, "Cancel"};
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
                              ? ownerName + " added to Close Friends \u2B50"
                              : ownerName + " removed from Close Friends";
                      Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
                      rebuildAdapter(); // FIX: refresh CF badge in list
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
          final String uid = myUid;
          FirebaseUtils.getBlocksRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
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
          if (myUid == null) return;
          FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
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
                      else if (!items2.isEmpty() && !blockedUids.contains(uid)) statusMap.put(uid, items2);
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
          // FIX: remove highlights listener
          if (highlightsListener != null && myUid != null) {
              FirebaseUtils.db().getReference("statusHighlights").child(myUid)
                      .removeEventListener(highlightsListener);
              highlightsListener = null;
          }
      }
      // ── Adapter rebuild with search + mute + isCloseFriend ────────────────
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
              // FIX: populate isCloseFriend
              boolean isCF = StatusCloseFriendsManager.isCloseFriend(getContext(), uid);
              StatusListAdapter.Entry entry = new StatusListAdapter.Entry(
                      uid, latest.ownerName, latest.ownerPhoto,
                      latest.timestamp, items2.size(), unseenCount,
                      latest, isMutedContact, latestReaction, isCF);
              if (isMutedContact)         muted.add(entry);
              else if (unseenCount > 0)   unseen.add(entry);
              else                        seen.add(entry);
          }
          Comparator<StatusListAdapter.Entry> byTime = (a, b) -> Long.compare(
                  b.latestTimestamp == null ? 0 : b.latestTimestamp,
                  a.latestTimestamp == null ? 0 : a.latestTimestamp);
          unseen.sort(byTime); seen.sort(byTime); muted.sort(byTime);
          adapter.update(unseen, seen, muted);
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