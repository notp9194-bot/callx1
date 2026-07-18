package com.callx.app.chatlist;

import android.app.AlertDialog;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.chatlist.ChatListAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

import java.util.*;
import java.util.concurrent.Executors;
import com.callx.app.conversation.ChatActivity;

/**
 * ChatsFragment v21 — Delete / Delete-All System
 *
 * CHANGES v21:
 *  1. Long-press on chat item → selection mode starts immediately.
 *     PrivacyDirectDialog ab REMOVED from long-press.
 *     (Privacy actions are now accessible via avatar long-press zoom dialog
 *      or a future context menu; this avoids conflicting with selection.)
 *  2. Selection bar: ✕ cancel | count | All | 🗑️ delete icon
 *  3. Delete Selected: Firebase contacts node se remove + Room DB cleanup
 *  4. Delete All: confirm dialog → clear everything → Firebase + Room
 *  5. adapter.setOnLongPressListener() NOT set anymore.
 */
public class ChatsFragment extends Fragment implements ChatListAdapter.SelectionListener {

    private final List<User> contacts = new ArrayList<>();
    private ChatListAdapter adapter;
    private View emptyState;

    private LinearLayout llSelectionBar;
    private TextView tvSelectedCount;

    private final Set<String> specialRequestUids = new HashSet<>();

    // FIX #MEM-3C: Listener references store karo taaki onDestroyView mein detach kar sakein.
    private DatabaseReference contactsRef;
    private ValueEventListener contactsListener;
    private DatabaseReference specialRequestsRef;
    private ValueEventListener specialRequestsListener;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);
        RecyclerView rv  = v.findViewById(R.id.rv_chats);
        emptyState       = v.findViewById(R.id.empty_state);
        llSelectionBar   = v.findViewById(R.id.ll_selection_bar);
        tvSelectedCount  = v.findViewById(R.id.tv_selected_count);

        View banner = v.findViewById(R.id.banner_requests);
        if (banner != null) banner.setVisibility(View.GONE);

        // v88: ChatListLayoutManager (custom LLM)
        //  • supportsPredictiveItemAnimations=false → single layout pass per update
        //  • getExtraLayoutSpace=screenHeight → rows pre-laid out before scroll
        //  • isMeasurementCacheEnabled=true → no re-measure for fixed-height rows
        ChatListLayoutManager llm = new ChatListLayoutManager(requireContext());
        llm.setInitialPrefetchItemCount(8);
        rv.setLayoutManager(llm);

        // v83: constructor no longer takes a list — submitList() is the write path
        adapter = new ChatListAdapter(this);
        rv.setAdapter(adapter);

        // Fixed-size rows: tell RV it never needs to re-measure the whole list
        // when an item changes — saves a full layout pass on every Firebase update.
        rv.setHasFixedSize(true);

        // Keep 20 off-screen VHs alive (was 12). On a 50-contact list this
        // virtually eliminates VH destruction/recreation during a scroll and
        // avoids re-attaching typing listeners + reloading Glide on every bind.
        rv.setItemViewCacheSize(20);

        // v87: Activity-scoped pool — survives Fragment View destruction on tab switches.
        // Pool is owned by RecyclerViewPoolViewModel (Activity lifecycle), so the 25 cached
        // ChatListAdapter.VHs are never thrown away when the user visits a distant tab.
        // NEVER pass this pool to GroupsFragment — VH layouts are incompatible.
        RecyclerViewPoolViewModel poolVm =
                new androidx.lifecycle.ViewModelProvider(requireActivity())
                        .get(RecyclerViewPoolViewModel.class);
        rv.setRecycledViewPool(poolVm.getChatsPool());

        // Pause Glide bitmap decoding during fast flings (resume on idle/drag).
        rv.addOnScrollListener(new GlideScrollListener(requireContext()));

        // v85+: null ItemAnimator — removes all animation overhead
        rv.setItemAnimator(null);

        // v86: disable over-scroll edge glow + nested scroll overhead
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setNestedScrollingEnabled(false);

        // v88: fine-tune touch slop — default gives precise single-tap detection
        // while still recognising flings cleanly
        rv.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_DEFAULT);

        // v88: Glide safety net — clear any in-flight request the moment a VH
        // is returned to the pool, before the next bind can fire with a stale URL
        rv.setRecyclerListener(holder -> {
            if (holder instanceof ChatListAdapter.VH) {
                ChatListAdapter.VH vh = (ChatListAdapter.VH) holder;
                if (vh.ivAvatar != null) {
                    try { Glide.with(vh.ivAvatar.getContext()).clear(vh.ivAvatar); }
                    catch (Exception ignored) {}
                }
            }
        });

        // Reduce clipping work on every scroll frame
        rv.setClipToPadding(false);
        rv.setClipChildren(false);

        // Avatar click → contact bottom sheet (same as Calls tab)
        adapter.setOnAvatarClickListener(u -> showContactBottomSheet(u));

        // v21: longPressListener NOT set → long-press now starts selection mode directly.

        // Selection bar buttons
        v.findViewById(R.id.btn_cancel_selection_chats).setOnClickListener(x -> {
            adapter.clearSelection();
            llSelectionBar.setVisibility(View.GONE);
        });

        v.findViewById(R.id.btn_select_all_chats).setOnClickListener(x -> {
            adapter.selectAll();
            updateSelectionCount();
        });

        v.findViewById(R.id.btn_delete_selected_chats).setOnClickListener(x ->
            confirmDeleteSelected());

        // v15 FIX 1: Pehle Room se load karo (offline ke liye instant display)
        loadFromRoom();

        // Phir Firebase listener lagao (online sync + Room update)
        loadContacts();
        loadSpecialRequests();
        return v;
    }

    // ─────────────────────────────────────────────────────────────
    // Room se offline-first load
    // ─────────────────────────────────────────────────────────────

    private void loadFromRoom() {
        if (getContext() == null) return;
        AppDatabase db = AppDatabase.getInstance(getContext());

        Executors.newSingleThreadExecutor().execute(() -> {
            List<ChatEntity> cached = db.chatDao().getAllChatsSync();
            if (cached == null || cached.isEmpty()) return;

            List<User> roomUsers = new ArrayList<>();
            for (ChatEntity e : cached) {
                User u = new User();
                u.uid = e.partnerUid;
                if (u.uid == null || u.uid.isEmpty()) continue;
                u.name     = e.partnerName;
                u.photoUrl = e.partnerPhoto;
                u.thumbUrl = e.partnerThumb;
                u.lastMessageAt = e.lastMessageAt;
                u.unread   = e.unread;
                u.lastMessage           = e.lastMessage;
                u.lastMessageType       = e.lastMessageType;
                u.lastMessageStatus     = e.lastMessageStatus;
                u.lastMessageSenderUid  = e.lastMessageSenderUid;
                u.lastMessageId         = e.lastMessageId;
                roomUsers.add(u);
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (contacts.isEmpty()) {
                        // FIX #5: Room first-load — contacts list is empty so diffUpdate
                        // is equivalent to notifyItemRangeInserted but cleaner via diff
                        sortByLatestMessage();
                        diffUpdateContacts(roomUsers);
                        if (emptyState != null)
                            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Firebase listener — online sync + Room save
    // ─────────────────────────────────────────────────────────────

    private void loadContacts() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        contactsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                // Build newList separately so diffUpdate can diff old vs new
                List<User> newList = new ArrayList<>();
                List<ChatEntity> toSave = new ArrayList<>();

                for (DataSnapshot c : snap.getChildren()) {
                    User u = c.getValue(User.class);
                    if (u != null) {
                        if (u.uid == null) u.uid = c.getKey();
                        if ((u.name == null || u.name.isEmpty()
                                || u.photoUrl == null) && u.uid != null) {
                            enrichContactFromUsers(u, uid);
                        }
                        newList.add(u);

                        ChatEntity entity = new ChatEntity();
                        entity.chatId       = uid + "_contact_" + (u.uid != null ? u.uid : "");
                        entity.type         = "private";
                        entity.partnerUid   = u.uid;
                        entity.partnerName  = u.name;
                        entity.partnerPhoto = u.photoUrl;
                        entity.partnerThumb = u.thumbUrl;
                        entity.lastMessage  = u.lastMessage;
                        entity.lastMessageAt = u.lastMessageAt;
                        entity.unread       = u.unread;
                        entity.lastMessageType      = u.lastMessageType;
                        entity.lastMessageStatus    = u.lastMessageStatus;
                        entity.lastMessageSenderUid = u.lastMessageSenderUid;
                        entity.lastMessageId        = u.lastMessageId;
                        entity.syncedAt     = System.currentTimeMillis();
                        toSave.add(entity);
                    }
                }

                if (getContext() != null && !toSave.isEmpty()) {
                    AppDatabase db = AppDatabase.getInstance(getContext());
                    Executors.newSingleThreadExecutor().execute(() ->
                        db.chatDao().insertChats(toSave));
                }

                // FIX #5: Sort newList, then diffUpdate — contacts list is the source of truth
                Collections.sort(newList, (a, b) -> {
                    boolean aS = a.uid != null && specialRequestUids.contains(a.uid);
                    boolean bS = b.uid != null && specialRequestUids.contains(b.uid);
                    if (aS != bS) return aS ? -1 : 1;
                    long la = a.lastMessageAt != null ? a.lastMessageAt :
                              (a.lastSeen != null ? a.lastSeen : 0L);
                    long lb = b.lastMessageAt != null ? b.lastMessageAt :
                              (b.lastSeen != null ? b.lastSeen : 0L);
                    return Long.compare(lb, la);
                });
                diffUpdateContacts(newList);
                if (emptyState != null)
                    emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        contactsRef = FirebaseUtils.getContactsRef(uid);
        contactsRef.addValueEventListener(contactsListener);
    }

    private void enrichContactFromUsers(User u, String myUid) {
        if (u.uid == null) return;
        FirebaseUtils.getUserRef(u.uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String name     = snap.child("name").getValue(String.class);
                    String photo    = snap.child("photoUrl").getValue(String.class);
                    String thumb    = snap.child("thumbUrl").getValue(String.class);
                    boolean changed = false;
                    if (name != null && !name.isEmpty() && (u.name == null || u.name.isEmpty())) {
                        u.name = name; changed = true;
                        FirebaseUtils.getContactsRef(myUid)
                            .child(u.uid).child("name").setValue(name);
                    }
                    if (photo != null && !photo.isEmpty() && u.photoUrl == null) {
                        u.photoUrl = photo; changed = true;
                        FirebaseUtils.getContactsRef(myUid)
                            .child(u.uid).child("photoUrl").setValue(photo);
                    }
                    if (thumb != null && !thumb.isEmpty() && u.thumbUrl == null) {
                        u.thumbUrl = thumb; changed = true;
                        FirebaseUtils.getContactsRef(myUid)
                            .child(u.uid).child("thumbUrl").setValue(thumb);
                    }
                    if (changed && adapter != null) {
                        // FIX #5: Find the specific position of this user and only rebind that row
                        for (int i = 0; i < contacts.size(); i++) {
                            if (u.uid != null && u.uid.equals(contacts.get(i).uid)) {
                                adapter.notifyItemChanged(i);
                                break;
                            }
                        }
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void loadSpecialRequests() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        specialRequestsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                specialRequestUids.clear();
                for (DataSnapshot c : snap.getChildren())
                    if (c.getKey() != null) specialRequestUids.add(c.getKey());
                if (adapter != null) {
                    adapter.setSpecialRequestSenders(specialRequestUids);
                    sortByLatestMessage();
                    // FIX #5: diffUpdate instead of notifyDataSetChanged
                    diffUpdateContacts(new ArrayList<>(contacts));
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        specialRequestsRef = FirebaseUtils.db().getReference("specialRequests").child(uid);
        specialRequestsRef.addValueEventListener(specialRequestsListener);
    }

    @Override
    public void onDestroyView() {
        if (contactsRef != null && contactsListener != null) {
            contactsRef.removeEventListener(contactsListener);
            contactsRef = null; contactsListener = null;
        }
        if (specialRequestsRef != null && specialRequestsListener != null) {
            specialRequestsRef.removeEventListener(specialRequestsListener);
            specialRequestsRef = null; specialRequestsListener = null;
        }
        super.onDestroyView();
    }

    private void sortByLatestMessage() {
        Collections.sort(contacts, (a, b) -> {
            boolean aS = a.uid != null && specialRequestUids.contains(a.uid);
            boolean bS = b.uid != null && specialRequestUids.contains(b.uid);
            if (aS != bS) return aS ? -1 : 1;
            long la = a.lastMessageAt != null ? a.lastMessageAt :
                      (a.lastSeen != null ? a.lastSeen : 0L);
            long lb = b.lastMessageAt != null ? b.lastMessageAt :
                      (b.lastSeen != null ? b.lastSeen : 0L);
            return Long.compare(lb, la);
        });
    }

    /**
     * v83: DiffUtil logic moved into ChatListAdapter.DIFF_CALLBACK + AsyncListDiffer.
     * The diff now runs on a background thread (AsyncListDiffer default executor)
     * so the main thread never blocks on calculateDiff(), no matter how large the
     * list grows. This method keeps its signature so all existing call-sites
     * compile without change — it just updates the local working copy and hands
     * the new list to the adapter's differ.
     */
    private void diffUpdateContacts(List<User> newList) {
        contacts.clear();
        contacts.addAll(newList);
        if (adapter != null) adapter.submitList(new ArrayList<>(contacts));
    }

    // ── SelectionListener callbacks ─────────────────────────────────────────

    @Override public void onSelectionStarted() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.VISIBLE);
        updateSelectionCount();
    }
    @Override public void onSelectionChanged() { updateSelectionCount(); }
    @Override public void onSelectionCleared() {
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }

    private void updateSelectionCount() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        int total = contacts.size();
        if (tvSelectedCount != null)
            tvSelectedCount.setText(count + " / " + total + " selected");
    }

    // ── v21 DELETE SELECTED ────────────────────────────────────────────────

    private void confirmDeleteSelected() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        if (count == 0) {
            Toast.makeText(getContext(), "Koi bhi select nahi kiya", Toast.LENGTH_SHORT).show();
            return;
        }
        com.callx.app.utils.AlertDialogStyler.showRounded(
            new AlertDialog.Builder(requireContext())
            .setTitle("Delete " + count + " chat" + (count > 1 ? "s" : "") + "?")
            .setMessage("Selected conversations aapki chat list se remove ho jayenge.")
            .setPositiveButton("Delete", (d, w) -> deleteSelected())
            .setNegativeButton("Cancel", null)
        .create(), com.callx.app.utils.AlertDialogStyler.DialogSize.COMPACT);
    }

    private void deleteSelected() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        List<User> selected = adapter.getSelectedItems();
        if (selected.isEmpty()) return;

        // Firebase se remove
        for (User u : selected) {
            if (u.uid != null) {
                FirebaseUtils.getContactsRef(myUid).child(u.uid).removeValue();
            }
        }

        // Room DB se remove (background thread)
        if (getContext() != null) {
            AppDatabase db = AppDatabase.getInstance(getContext());
            Executors.newSingleThreadExecutor().execute(() -> {
                for (User u : selected) {
                    if (u.uid != null) {
                        db.chatDao().deleteByPartnerUid(u.uid);
                    }
                }
            });
        }

        // Local list update
        contacts.removeAll(selected);
        adapter.clearSelection();
        // FIX #5: diffUpdate animates the removed rows instead of blinking the full list
        diffUpdateContacts(new ArrayList<>(contacts));

        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        if (emptyState != null)
            emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);

        int cnt = selected.size();
        Toast.makeText(getContext(),
            cnt + " chat" + (cnt > 1 ? "s" : "") + " delete ho gaye",
            Toast.LENGTH_SHORT).show();
    }

    // ── v21 DELETE ALL ─────────────────────────────────────────────────────

    /**
     * Delete All chats — accessible via 3-dot overflow menu in MainActivity/toolbar.
     * ChatsFragment exposes this as a public method so parent can call it.
     */
    public void confirmDeleteAll() {
        if (contacts.isEmpty()) {
            Toast.makeText(getContext(), "Koi chat nahi hai", Toast.LENGTH_SHORT).show();
            return;
        }
        com.callx.app.utils.AlertDialogStyler.showRounded(
            new AlertDialog.Builder(requireContext())
            .setTitle("Delete All Chats?")
            .setMessage("Aapki saari " + contacts.size() + " conversations chat list se remove ho jayengi.\n\nYe action undo nahi ho sakti.")
            .setPositiveButton("Delete All", (d, w) -> deleteAllChats())
            .setNegativeButton("Cancel", null)
        .create(), com.callx.app.utils.AlertDialogStyler.DialogSize.COMPACT);
    }

    private void deleteAllChats() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // Firebase contacts node clear karo
        FirebaseUtils.getContactsRef(myUid).removeValue();

        // Room DB completely clear karo
        if (getContext() != null) {
            AppDatabase db = AppDatabase.getInstance(getContext());
            Executors.newSingleThreadExecutor().execute(() ->
                db.chatDao().deleteAllChats());
        }

        // Local list clear karo
        List<User> empty = Collections.emptyList();
        adapter.clearSelection();
        // FIX #5: diffUpdate dispatches removeItem animations for each deleted row
        diffUpdateContacts(empty);

        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        if (emptyState != null) emptyState.setVisibility(View.VISIBLE);

        Toast.makeText(getContext(), "Saare chats delete ho gaye", Toast.LENGTH_SHORT).show();
    }

    // ── Contact Bottom Sheet ─────────────────────────────────────────────────

    private void showContactBottomSheet(User user) {
        if (getContext() == null || user == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(getContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        View sv = LayoutInflater.from(getContext())
            .inflate(R.layout.bottom_sheet_contact_call, null);
        sheet.setContentView(sv);

        CircleImageView ivAvatar = sv.findViewById(R.id.iv_avatar_sheet);
        TextView tvName          = sv.findViewById(R.id.tv_name_sheet);
        TextView tvStatus        = sv.findViewById(R.id.tv_status_sheet);
        View onlineDot           = sv.findViewById(R.id.view_online_dot_sheet);
        View btnMessage          = sv.findViewById(R.id.btn_message_sheet);
        View btnVoice            = sv.findViewById(R.id.btn_voice_call_sheet);
        View btnVideo            = sv.findViewById(R.id.btn_video_call_sheet);
        View btnHistory          = sv.findViewById(R.id.btn_call_history_sheet);

        // Social platform buttons
        View btnXSheet            = sv.findViewById(R.id.btn_x_sheet);
        View btnReelsSheet        = sv.findViewById(R.id.btn_reels_sheet);
        View btnYoutubeSheet      = sv.findViewById(R.id.btn_youtube_sheet);
        CircleImageView ivAnimX   = sv.findViewById(R.id.iv_anim_x_sheet);
        CircleImageView ivAnimReel= sv.findViewById(R.id.iv_anim_reel_sheet);
        CircleImageView ivAnimYt  = sv.findViewById(R.id.iv_anim_youtube_sheet);

        View layoutXRow      = sv.findViewById(R.id.layout_x_follow_row);
        View layoutReelsRow  = sv.findViewById(R.id.layout_reels_follow_row);
        View layoutYtRow     = sv.findViewById(R.id.layout_youtube_subscribe_row);
        TextView tvXCount    = sv.findViewById(R.id.tv_x_followers_count);
        TextView tvReelsCount= sv.findViewById(R.id.tv_reels_followers_count);
        TextView tvYtCount   = sv.findViewById(R.id.tv_youtube_subs_count);
        Button btnXFollow    = sv.findViewById(R.id.btn_x_follow_action);
        Button btnReelsFollow= sv.findViewById(R.id.btn_reels_follow_action);
        Button btnYtSub      = sv.findViewById(R.id.btn_youtube_subscribe_action);

        tvName.setText(user.name != null ? user.name : "User");

        String avatarUrl = (user.thumbUrl != null && !user.thumbUrl.isEmpty())
            ? user.thumbUrl : user.photoUrl;
        if (avatarUrl != null && !avatarUrl.isEmpty() && ivAvatar != null) {
            Glide.with(getContext()).load(avatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .override(96, 96)
                .into(ivAvatar);
        }

        if (user.uid != null) {
            FirebaseUtils.getUserRef(user.uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        Boolean online = snap.child("online").getValue(Boolean.class);
                        if (Boolean.TRUE.equals(online)) {
                            if (onlineDot != null) onlineDot.setVisibility(View.VISIBLE);
                            if (tvStatus  != null) {
                                tvStatus.setText("Online");
                                tvStatus.setTextColor(getResources().getColor(R.color.brand_accent, null));
                            }
                        } else {
                            if (onlineDot != null) onlineDot.setVisibility(View.GONE);
                            if (tvStatus  != null) {
                                tvStatus.setText("Offline");
                                tvStatus.setTextColor(getResources().getColor(R.color.text_muted, null));
                            }
                        }
                        String photo = snap.child("photoUrl").getValue(String.class);
                        String thumb = snap.child("thumbUrl").getValue(String.class);
                        String url   = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        if (url != null && !url.isEmpty() && getContext() != null && ivAvatar != null)
                            Glide.with(getContext()).load(url)
                                .apply(RequestOptions.circleCropTransform())
                                .override(96, 96)
                                .placeholder(R.drawable.ic_person).into(ivAvatar);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        }

        if (ivAvatar != null) {
            ivAvatar.setOnClickListener(x -> {
                if (user.uid == null || getContext() == null) return;
                FirebaseUtils.getUserRef(user.uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot snap) {
                            String full = snap.child("photoUrl").getValue(String.class);
                            showChatAvatarZoom((full != null && !full.isEmpty()) ? full : avatarUrl);
                        }
                        @Override public void onCancelled(DatabaseError e) {
                            showChatAvatarZoom(avatarUrl);
                        }
                    });
            });
        }

        if (btnMessage != null) {
            btnMessage.setOnClickListener(x -> {
                sheet.dismiss();
                if (user.uid == null || getContext() == null) return;
                final Context appCtx = getContext().getApplicationContext();
                String myUid = FirebaseAuth.getInstance().getUid();
                String chatId = myUid != null ? FirebaseUtils.getChatId(myUid, user.uid) : null;

                Runnable navigate = () -> {
                    if (getContext() == null) return;
                    Intent i = new Intent().setClassName(getContext().getPackageName(),
                        "com.callx.app.conversation.ChatActivity");
                    i.putExtra("partnerUid",   user.uid);
                    i.putExtra("partnerName",  user.name != null ? user.name : "");
                    i.putExtra("partnerPhoto", user.photoUrl != null ? user.photoUrl : "");
                    i.putExtra("partnerThumb", user.thumbUrl != null ? user.thumbUrl : "");
                    startActivity(i);
                };

                if (chatId == null) { navigate.run(); return; }

                // Same WhatsApp-style priming as the chat-list tap: read local
                // Room data first, then open — with a short safety cap so a
                // brand-new chat never feels stuck.
                final boolean[] navigated = {false};
                android.os.Handler safetyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable safetyFallback = () -> { if (!navigated[0]) { navigated[0] = true; navigate.run(); } };
                safetyHandler.postDelayed(safetyFallback, 150L);
                com.callx.app.repository.ChatRepository.getInstance(appCtx).primeChatFromRoom(chatId, () -> {
                    safetyHandler.removeCallbacks(safetyFallback);
                    if (!navigated[0]) { navigated[0] = true; navigate.run(); }
                });
            });
        }

        if (btnVoice != null) {
            btnVoice.setOnClickListener(x -> {
                sheet.dismiss();
                if (user.uid == null || getContext() == null) return;
                Intent i = new Intent().setClassName(getContext().getPackageName(),
                    "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid",  user.uid);
                i.putExtra("partnerName", user.name != null ? user.name : "");
                i.putExtra("isCaller", true);
                i.putExtra("video", false);
                startActivity(i);
            });
        }

        if (btnVideo != null) {
            btnVideo.setOnClickListener(x -> {
                sheet.dismiss();
                if (user.uid == null || getContext() == null) return;
                Intent i = new Intent().setClassName(getContext().getPackageName(),
                    "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid",  user.uid);
                i.putExtra("partnerName", user.name != null ? user.name : "");
                i.putExtra("isCaller", true);
                i.putExtra("video", true);
                startActivity(i);
            });
        }

        if (btnHistory != null) {
            btnHistory.setOnClickListener(x -> {
                sheet.dismiss();
                showChatCallHistorySheet(user);
            });
        }

        if (user.uid != null) {
            final CircleImageView[] peekViews = {ivAnimX, ivAnimReel, ivAnimYt};
            final Handler[] animHandler       = {new Handler(Looper.getMainLooper())};
            final boolean[] animRunning       = {false};
            final Runnable[] animRunnable     = {null};

            loadChatSocialButtons(user.uid, sv,
                btnXSheet, btnReelsSheet, btnYoutubeSheet,
                ivAnimX, ivAnimReel, ivAnimYt,
                layoutXRow, layoutReelsRow, layoutYtRow,
                tvXCount, tvReelsCount, tvYtCount,
                btnXFollow, btnReelsFollow, btnYtSub,
                sheet, peekViews, animHandler, animRunning, animRunnable);

            sheet.setOnDismissListener(d -> {
                animRunning[0] = false;
                animHandler[0].removeCallbacks(animRunnable[0]);
                for (CircleImageView iv : peekViews) {
                    if (iv != null) {
                        iv.setVisibility(View.INVISIBLE);
                        iv.setScaleX(0f); iv.setScaleY(0f); iv.setAlpha(0f);
                    }
                }
            });
        }

        sheet.show();
    }

    private void loadChatSocialButtons(
            String partnerUid, View sv,
            View btnXSheet, View btnReelsSheet, View btnYoutubeSheet,
            CircleImageView ivAnimX, CircleImageView ivAnimReel, CircleImageView ivAnimYt,
            View layoutXRow, View layoutReelsRow, View layoutYtRow,
            TextView tvXCount, TextView tvReelsCount, TextView tvYtCount,
            Button btnXFollow, Button btnReelsFollow, Button btnYtSub,
            BottomSheetDialog sheet,
            CircleImageView[] peekViews,
            Handler[] animHandler, boolean[] animRunning, Runnable[] animRunnable) {

        if (getContext() == null) return;
        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
        final FirebaseDatabase db = FirebaseDatabase.getInstance(DB);
        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        // ── X ──
        db.getReference("x/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;
                    String xPhoto = snap.child("photoUrl").getValue(String.class);
                    if (xPhoto != null && !xPhoto.isEmpty() && ivAnimX != null)
                        Glide.with(getContext()).load(xPhoto).circleCrop()
                            .override(96, 96)
                            .placeholder(R.drawable.ic_person).into(ivAnimX);
                    startChatAvatarPeekLoop(peekViews, animHandler, animRunning, animRunnable);

                    Long xF = snap.child("followerCount").getValue(Long.class);
                    long xFCount = xF != null ? xF : 0;
                    if (tvXCount != null) tvXCount.setText(formatCount(xFCount) + " Followers");
                    if (layoutXRow != null) layoutXRow.setVisibility(View.VISIBLE);

                    if (myUid != null && btnXFollow != null) {
                        db.getReference("x/followers").child(partnerUid).child(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot ds) {
                                    boolean[] isF = {ds.exists() && Boolean.TRUE.equals(ds.getValue(Boolean.class))};
                                    updateXBtn(btnXFollow, isF[0]);
                                    btnXFollow.setOnClickListener(v -> {
                                        isF[0] = !isF[0]; updateXBtn(btnXFollow, isF[0]);
                                        if (isF[0]) {
                                            db.getReference("x/followers").child(partnerUid).child(myUid).setValue(true);
                                            db.getReference("x/following").child(myUid).child(partnerUid).setValue(true);
                                            if (tvXCount != null) bumpCount(tvXCount, 1, "Followers");
                                        } else {
                                            db.getReference("x/followers").child(partnerUid).child(myUid).removeValue();
                                            db.getReference("x/following").child(myUid).child(partnerUid).removeValue();
                                            if (tvXCount != null) bumpCount(tvXCount, -1, "Followers");
                                        }
                                    });
                                }
                                @Override public void onCancelled(DatabaseError e) {}
                            });
                    }

                    if (btnXSheet != null) {
                        btnXSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            try {
                                Class<?> cls = Class.forName("com.callx.app.profile.XProfileSheet");
                                java.lang.reflect.Method m = cls.getMethod("showProfile",
                                    androidx.fragment.app.FragmentManager.class, String.class);
                                m.invoke(null, getParentFragmentManager(), partnerUid);
                            } catch (Exception ex) {
                                if (getContext() != null)
                                    Toast.makeText(getContext(), "X profile not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

        // ── Reels ──
        db.getReference("reels/users").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String rp    = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (rp != null && !rp.isEmpty() && ivAnimReel != null)
                        Glide.with(getContext()).load(rp).circleCrop()
                            .override(96, 96)
                            .placeholder(R.drawable.ic_person).into(ivAnimReel);

                    db.getReference("reels/followers").child(partnerUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot fSnap) {
                                long cnt = fSnap.getChildrenCount();
                                if (tvReelsCount != null) tvReelsCount.setText(formatCount(cnt) + " Followers");
                                if (layoutReelsRow != null) layoutReelsRow.setVisibility(View.VISIBLE);
                                if (myUid != null && btnReelsFollow != null) {
                                    boolean[] isF = {fSnap.hasChild(myUid)};
                                    updateReelsBtn(btnReelsFollow, isF[0]);
                                    btnReelsFollow.setOnClickListener(v -> {
                                        isF[0] = !isF[0]; updateReelsBtn(btnReelsFollow, isF[0]);
                                        if (isF[0]) {
                                            db.getReference("reels/followers").child(partnerUid).child(myUid).setValue(true);
                                            db.getReference("reels/following").child(myUid).child(partnerUid).setValue(true);
                                            if (tvReelsCount != null) bumpCount(tvReelsCount, 1, "Followers");
                                        } else {
                                            db.getReference("reels/followers").child(partnerUid).child(myUid).removeValue();
                                            db.getReference("reels/following").child(myUid).child(partnerUid).removeValue();
                                            if (tvReelsCount != null) bumpCount(tvReelsCount, -1, "Followers");
                                        }
                                    });
                                }
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });

                    if (btnReelsSheet != null) {
                        btnReelsSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            if (getContext() == null) return;
                            try {
                                Class<?> cls = Class.forName("com.callx.app.profile.UserReelsActivity");
                                Intent i = new Intent(getContext(), cls);
                                i.putExtra("uid", partnerUid);
                                startActivity(i);
                            } catch (ClassNotFoundException ex) {
                                Toast.makeText(getContext(), "Reels not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });

        // ── YouTube ──
        db.getReference("youtube/channels").child(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null || !snap.exists()) return;
                    String yt = snap.child("thumbUrl").getValue(String.class);
                    String yp = snap.child("photoUrl").getValue(String.class);
                    String ya = (yt != null && !yt.isEmpty()) ? yt : yp;
                    if (ya != null && !ya.isEmpty() && ivAnimYt != null)
                        Glide.with(getContext()).load(ya).circleCrop()
                            .override(96, 96)
                            .placeholder(R.drawable.ic_person).into(ivAnimYt);

                    Long subC = snap.child("subscriberCount").getValue(Long.class);
                    long subs = subC != null ? subC : 0;
                    if (tvYtCount != null) tvYtCount.setText(formatCount(subs) + " Subscribers");
                    if (layoutYtRow != null) layoutYtRow.setVisibility(View.VISIBLE);

                    if (myUid != null && btnYtSub != null) {
                        db.getReference("youtube/subscribers").child(partnerUid).child(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot ds) {
                                    boolean[] isS = {ds.exists() && Boolean.TRUE.equals(ds.getValue(Boolean.class))};
                                    updateYtBtn(btnYtSub, isS[0]);
                                    btnYtSub.setOnClickListener(v -> {
                                        isS[0] = !isS[0]; updateYtBtn(btnYtSub, isS[0]);
                                        if (isS[0]) {
                                            db.getReference("youtube/subscribers").child(partnerUid).child(myUid).setValue(true);
                                            if (tvYtCount != null) bumpCount(tvYtCount, 1, "Subscribers");
                                        } else {
                                            db.getReference("youtube/subscribers").child(partnerUid).child(myUid).removeValue();
                                            if (tvYtCount != null) bumpCount(tvYtCount, -1, "Subscribers");
                                        }
                                    });
                                }
                                @Override public void onCancelled(DatabaseError e) {}
                            });
                    }

                    if (btnYoutubeSheet != null) {
                        btnYoutubeSheet.setOnClickListener(v -> {
                            sheet.dismiss();
                            if (getContext() == null) return;
                            try {
                                Class<?> cls = Class.forName("com.callx.app.channel.YouTubeChannelActivity");
                                Intent i = new Intent(getContext(), cls);
                                i.putExtra("uid", partnerUid);
                                startActivity(i);
                            } catch (ClassNotFoundException ex) {
                                Toast.makeText(getContext(), "YouTube not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ── Avatar Peek Loop ───────────────────────────────────────────────────
    private void startChatAvatarPeekLoop(
            CircleImageView[] views, Handler[] handlerArr,
            boolean[] runningArr, Runnable[] runnableArr) {
        if (runningArr[0]) return;
        runningArr[0] = true;
        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f); iv.setScaleY(0f); iv.setAlpha(0f);
        }
        // Avatar peek loop animation removed for performance — show avatars statically
        runnableArr[0] = new Runnable() {
            int idx = 0;
            @Override public void run() {
                if (!runningArr[0] || getContext() == null) return;
                CircleImageView iv = views[idx % views.length]; idx++;
                if (iv == null) { handlerArr[0].postDelayed(this, 3000); return; }
                iv.setScaleX(1f); iv.setScaleY(1f); iv.setAlpha(1f);
                iv.setVisibility(View.VISIBLE);
                final Runnable me = this;
                handlerArr[0].postDelayed(() -> {
                    iv.setVisibility(View.INVISIBLE);
                    if (runningArr[0] && getContext() != null)
                        handlerArr[0].postDelayed(me, 1000);
                }, 3000);
            }
        };
        handlerArr[0].postDelayed(runnableArr[0], 1500);
    }

    // ── Button state helpers ───────────────────────────────────────────────
    private void updateXBtn(Button btn, boolean following) {
        if (btn == null) return;
        btn.setText(following ? "Following" : "Follow");
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(following ? 0xFF333333 : 0xFF000000));
    }
    private void updateReelsBtn(Button btn, boolean following) {
        if (btn == null) return;
        btn.setText(following ? "Following" : "Follow");
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(following ? 0xFF555555 : 0xFFDD2A7B));
    }
    private void updateYtBtn(Button btn, boolean subscribed) {
        if (btn == null) return;
        btn.setText(subscribed ? "Subscribed" : "Subscribe");
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(subscribed ? 0xFF333333 : 0xFFFF0000));
    }
    private void bumpCount(TextView tv, int delta, String label) {
        try {
            String text = tv.getText().toString().split(" ")[0].replace("K","000").replace("M","000000");
            long cur = Long.parseLong(text);
            tv.setText(formatCount(Math.max(0, cur + delta)) + " " + label);
        } catch (Exception ignored) {}
    }
    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ── Call History Sheet ──────────────────────────────────────────────────
    private void showChatCallHistorySheet(User user) {
        if (getContext() == null || user.uid == null) return;

        BottomSheetDialog histSheet = new BottomSheetDialog(getContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        View sv = LayoutInflater.from(getContext())
            .inflate(R.layout.bottom_sheet_call_history, null);
        histSheet.setContentView(sv);

        CircleImageView ivAvatar = sv.findViewById(R.id.iv_history_avatar);
        TextView tvName          = sv.findViewById(R.id.tv_history_name);
        TextView tvCount         = sv.findViewById(R.id.tv_history_count);
        View btnClose            = sv.findViewById(R.id.btn_close_history);
        androidx.recyclerview.widget.RecyclerView rv = sv.findViewById(R.id.rv_call_history_sheet);
        View llEmpty             = sv.findViewById(R.id.ll_history_empty);

        tvName.setText(user.name != null ? user.name : "User");
        tvCount.setText("Loading...");

        String avatarUrl = (user.thumbUrl != null && !user.thumbUrl.isEmpty())
            ? user.thumbUrl : user.photoUrl;
        if (avatarUrl != null && !avatarUrl.isEmpty() && ivAvatar != null) {
            Glide.with(getContext()).load(avatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .override(96, 96)
                .placeholder(R.drawable.ic_person).into(ivAvatar);
        }

        if (ivAvatar != null) {
            ivAvatar.setOnClickListener(x -> showChatAvatarZoom(avatarUrl));
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(x -> histSheet.dismiss());
        }

        String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) {
            tvCount.setText("0 calls");
            if (llEmpty != null) llEmpty.setVisibility(View.VISIBLE);
            histSheet.show();
            return;
        }

        FirebaseUtils.getCallsRef(myUid)
            .orderByChild("partnerUid").equalTo(user.uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null) return;
                    List<com.callx.app.models.CallLog> contactLogs = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        com.callx.app.models.CallLog l = c.getValue(com.callx.app.models.CallLog.class);
                        if (l != null) {
                            if (l.id == null) l.id = c.getKey();
                            contactLogs.add(l);
                        }
                    }
                    contactLogs.sort((a, b) -> {
                        long ta = a.timestamp != null ? a.timestamp : 0;
                        long tb = b.timestamp != null ? b.timestamp : 0;
                        return Long.compare(tb, ta);
                    });

                    int total = contactLogs.size();
                    if (tvCount != null) tvCount.setText(total + " call" + (total != 1 ? "s" : ""));

                    if (contactLogs.isEmpty()) {
                        if (rv     != null) rv.setVisibility(View.GONE);
                        if (llEmpty != null) llEmpty.setVisibility(View.VISIBLE);
                    } else {
                        if (llEmpty != null) llEmpty.setVisibility(View.GONE);
                        if (rv != null) {
                            rv.setLayoutManager(new LinearLayoutManager(getContext()));
                            rv.setAdapter(new ChatCallHistoryAdapter(contactLogs));
                        }
                    }
                }
                @Override public void onCancelled(DatabaseError e) {
                    if (tvCount != null) tvCount.setText("0 calls");
                    if (llEmpty != null) llEmpty.setVisibility(View.VISIBLE);
                }
            });

        histSheet.show();
    }

    // ── Inline adapter for call history sheet ──────────────────────────────
    private class ChatCallHistoryAdapter
            extends androidx.recyclerview.widget.RecyclerView.Adapter<ChatCallHistoryAdapter.VH> {

        private final List<com.callx.app.models.CallLog> logs;
        private final java.text.SimpleDateFormat fmt =
            new java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault());

        ChatCallHistoryAdapter(List<com.callx.app.models.CallLog> logs) { this.logs = logs; }

        @androidx.annotation.NonNull
        @Override
        public VH onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup parent, int viewType) {
            android.view.View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_call_history_sheet, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) {
            com.callx.app.models.CallLog l = logs.get(pos);
            android.content.Context ctx = h.itemView.getContext();

            boolean isVideo = "video".equals(l.mediaType);
            String dir = l.direction == null ? "" : l.direction.toLowerCase();

            String label; int iconColor; int iconRes;
            if (dir.contains("missed")) {
                label = isVideo ? "Missed Video" : "Missed Voice";
                iconColor = android.graphics.Color.parseColor("#EF4444");
                iconRes = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            } else if (dir.contains("incoming") || dir.contains("in")) {
                label = isVideo ? "Incoming Video" : "Incoming Voice";
                iconColor = android.graphics.Color.parseColor("#22C55E");
                iconRes = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            } else {
                label = isVideo ? "Outgoing Video" : "Outgoing Voice";
                iconColor = android.graphics.Color.parseColor("#5B5BF6");
                iconRes = isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone;
            }

            h.tvLabel.setText(label);
            h.tvLabel.setTextColor(iconColor);
            h.ivIcon.setImageResource(iconRes);
            h.ivIcon.setColorFilter(iconColor);
            h.tvTime.setText(l.timestamp != null ? fmt.format(new java.util.Date(l.timestamp)) : "—");

            if (l.duration != null && l.duration > 0) {
                long d = l.duration;
                h.tvDuration.setText(d >= 60 ? (d / 60) + "m " + (d % 60) + "s" : d + "s");
                h.tvDuration.setVisibility(android.view.View.VISIBLE);
            } else {
                h.tvDuration.setVisibility(android.view.View.GONE);
            }

            h.ivQuickCall.setImageResource(isVideo ? R.drawable.ic_video_call : R.drawable.ic_phone);
            h.ivQuickCall.setColorFilter(android.graphics.Color.parseColor("#5B5BF6"));
            h.ivQuickCall.setOnClickListener(v -> {
                if (l.partnerUid == null) return;
                android.content.Intent i = new android.content.Intent()
                    .setClassName(ctx.getPackageName(), "com.callx.app.call.CallActivity");
                i.putExtra("partnerUid",  l.partnerUid);
                i.putExtra("partnerName", l.partnerName != null ? l.partnerName : "");
                i.putExtra("isCaller", true);
                i.putExtra("video", isVideo);
                ctx.startActivity(i);
            });
        }

        @Override public int getItemCount() { return logs.size(); }

        class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            android.widget.ImageView ivIcon, ivQuickCall;
            android.widget.TextView tvLabel, tvTime, tvDuration;
            VH(android.view.View v) {
                super(v);
                ivIcon      = v.findViewById(R.id.iv_call_type_icon);
                tvLabel     = v.findViewById(R.id.tv_call_type_label);
                tvTime      = v.findViewById(R.id.tv_call_time);
                tvDuration  = v.findViewById(R.id.tv_call_duration);
                ivQuickCall = v.findViewById(R.id.iv_quick_call);
            }
        }
    }

    // ── Avatar Zoom ────────────────────────────────────────────────────────
    private void showChatAvatarZoom(String photoUrl) {
        if (getContext() == null) return;
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            getContext(), photoUrl, R.drawable.ic_person, R.drawable.ic_close);
    }
}
