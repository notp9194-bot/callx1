package com.callx.app.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.calls.R;

import com.callx.app.adapters.CallHistoryAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.CallLogEntity;
import com.callx.app.models.CallLog;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.*;
import com.google.firebase.auth.FirebaseAuth;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.adapters.ContactCallHistoryAdapter;

import java.util.*;
import java.util.concurrent.Executors;

public class CallsFragment extends Fragment implements CallHistoryAdapter.SelectionListener {

    private final List<CallLog> allLogs = new ArrayList<>();
    private final List<CallLog> logs    = new ArrayList<>();
    private CallHistoryAdapter adapter;
    private View emptyState;

    private LinearLayout llOnlineUsers;
    private LinearLayout llOnlinePanel;
    private TextView tvNoOnline;
    private LinearLayout llSelectionBar;
    private TextView tvSelectedCount;

    // Filter chips
    private TextView chipAll, chipMissed, chipContacts, chipNonspam, chipSpam;
    private String activeFilter = "all";

    // Search
    private EditText etSearch;
    private String searchQuery = "";

    // Real-time online listeners — track per-uid so we can remove them
    private final Map<String, ValueEventListener> onlineListeners = new HashMap<>();
    private final Map<String, User> onlineUsersMap = new LinkedHashMap<>();
    private ValueEventListener contactsListener;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_calls, parent, false);

        RecyclerView rv = v.findViewById(R.id.rv_calls);
        emptyState      = v.findViewById(R.id.empty_calls);
        llOnlineUsers   = v.findViewById(R.id.ll_online_users);
        llOnlinePanel   = v.findViewById(R.id.ll_online_panel);
        tvNoOnline      = v.findViewById(R.id.tv_no_online);
        llSelectionBar  = v.findViewById(R.id.ll_selection_bar);
        tvSelectedCount = v.findViewById(R.id.tv_selected_count);

        chipAll      = v.findViewById(R.id.chip_all);
        chipMissed   = v.findViewById(R.id.chip_missed);
        chipContacts = v.findViewById(R.id.chip_contacts);
        chipNonspam  = v.findViewById(R.id.chip_nonspam);
        chipSpam     = v.findViewById(R.id.chip_spam);
        etSearch     = v.findViewById(R.id.et_search_calls);

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CallHistoryAdapter(logs, this);
        rv.setAdapter(adapter);

        // ── Contact click → BottomSheet (Reels-profile style) ──
        adapter.setOnContactClickListener((log, resolvedPhoto) ->
            showContactBottomSheet(log, resolvedPhoto));

        // Selection bar buttons
        v.findViewById(R.id.btn_cancel_selection_calls).setOnClickListener(x -> {
            adapter.clearSelection();
            if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
        });
        v.findViewById(R.id.btn_select_all_calls).setOnClickListener(x -> {
            adapter.selectAll();
            updateSelectionCount();
        });
        v.findViewById(R.id.btn_delete_selected_calls).setOnClickListener(x ->
            confirmDeleteSelected());

        View tvViewContacts = v.findViewById(R.id.tv_view_contacts);
        if (tvViewContacts != null) {
            tvViewContacts.setOnClickListener(x -> {
                if (getContext() != null)
                    startActivity(new Intent().setClassName(
                        getContext(), "com.callx.app.activities.AllContactsActivity"));
            });
        }

        setupChip(chipAll,      "all");
        setupChip(chipMissed,   "missed");
        setupChip(chipContacts, "contacts");
        setupChip(chipNonspam,  "nonspam");
        setupChip(chipSpam,     "spam");

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int i, int b, int c) {
                    searchQuery = s.toString().trim().toLowerCase();
                    applyFilter();
                }
                @Override public void afterTextChanged(Editable e) {}
            });
        }

        loadOnlineContactsRealTime();
        loadCallLogs();
        return v;
    }

    // ── Contact BottomSheet (Reels/Profile style) ──────────────────────────
    private void showContactBottomSheet(CallLog log, String resolvedPhoto) {
        if (getContext() == null) return;

        BottomSheetDialog sheet = new BottomSheetDialog(getContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        View sv = LayoutInflater.from(getContext())
            .inflate(R.layout.bottom_sheet_contact_call, null);
        sheet.setContentView(sv);

        CircleImageView ivAvatar  = sv.findViewById(R.id.iv_avatar_sheet);
        TextView tvName           = sv.findViewById(R.id.tv_name_sheet);
        TextView tvStatus         = sv.findViewById(R.id.tv_status_sheet);
        View onlineDot            = sv.findViewById(R.id.view_online_dot_sheet);
        ImageView storyRing       = sv.findViewById(R.id.iv_story_ring_sheet);
        View btnMessage           = sv.findViewById(R.id.btn_message_sheet);
        View btnVoice             = sv.findViewById(R.id.btn_voice_call_sheet);
        View btnVideo             = sv.findViewById(R.id.btn_video_call_sheet);
        View btnHistory           = sv.findViewById(R.id.btn_call_history_sheet);

        // Set name
        tvName.setText(log.partnerName != null ? log.partnerName : "Unknown");

        // Load avatar (use resolved photo if already cached, else load from Firebase)
        if (resolvedPhoto != null && !resolvedPhoto.isEmpty()) {
            Glide.with(getContext()).load(resolvedPhoto)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .into(ivAvatar);
        }

        // Check online status + fetch latest photo in one go
        if (log.partnerUid != null) {
            FirebaseUtils.getUserRef(log.partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        Boolean isOnline = snap.child("online").getValue(Boolean.class);
                        if (Boolean.TRUE.equals(isOnline)) {
                            if (onlineDot != null) onlineDot.setVisibility(View.VISIBLE);
                            if (tvStatus  != null) {
                                tvStatus.setText("Online");
                                tvStatus.setTextColor(getResources().getColor(
                                    R.color.brand_accent, null));
                            }
                        } else {
                            if (onlineDot != null) onlineDot.setVisibility(View.GONE);
                            if (tvStatus  != null) {
                                tvStatus.setText("Offline");
                                tvStatus.setTextColor(getResources().getColor(
                                    R.color.text_muted, null));
                            }
                        }
                        // Load fresh photo
                        String photo = snap.child("photoUrl").getValue(String.class);
                        String thumb = snap.child("thumbUrl").getValue(String.class);
                        String url   = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        if (url != null && !url.isEmpty() && getContext() != null && ivAvatar != null)
                            Glide.with(getContext()).load(url)
                                .apply(RequestOptions.circleCropTransform())
                                .placeholder(R.drawable.ic_person)
                                .into(ivAvatar);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        }

        // Avatar tap → fetch full photoUrl from Firebase → zoom dialog
        ivAvatar.setOnClickListener(x -> {
            if (getContext() == null || log.partnerUid == null) return;
            // Fetch full (non-thumb) photoUrl for best quality
            FirebaseUtils.getUserRef(log.partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String fullPhoto = snap.child("photoUrl").getValue(String.class);
                        // Fall back to resolvedPhoto (thumb) if full not available
                        String toShow = (fullPhoto != null && !fullPhoto.isEmpty())
                            ? fullPhoto : resolvedPhoto;
                        showAvatarZoom(toShow);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        showAvatarZoom(resolvedPhoto);
                    }
                });
        });

        // Message button
        btnMessage.setOnClickListener(x -> {
            sheet.dismiss();
            if (log.partnerUid == null || getContext() == null) return;
            Intent i = new Intent().setClassName(getContext().getPackageName(),
                "com.callx.app.activities.ChatActivity");
            i.putExtra("partnerUid",  log.partnerUid);
            i.putExtra("partnerName", log.partnerName != null ? log.partnerName : "");
            startActivity(i);
        });

        // Voice Call button
        btnVoice.setOnClickListener(x -> {
            sheet.dismiss();
            if (log.partnerUid == null || getContext() == null) return;
            Intent i = new Intent().setClassName(getContext().getPackageName(),
                "com.callx.app.activities.CallActivity");
            i.putExtra("partnerUid",  log.partnerUid);
            i.putExtra("partnerName", log.partnerName != null ? log.partnerName : "");
            i.putExtra("isCaller", true);
            i.putExtra("video", false);
            startActivity(i);
        });

        // Video Call button
        btnVideo.setOnClickListener(x -> {
            sheet.dismiss();
            if (log.partnerUid == null || getContext() == null) return;
            Intent i = new Intent().setClassName(getContext().getPackageName(),
                "com.callx.app.activities.CallActivity");
            i.putExtra("partnerUid",  log.partnerUid);
            i.putExtra("partnerName", log.partnerName != null ? log.partnerName : "");
            i.putExtra("isCaller", true);
            i.putExtra("video", true);
            startActivity(i);
        });

        // Call History button → open contact-specific call history sheet
        btnHistory.setOnClickListener(x -> {
            sheet.dismiss();
            showCallHistorySheet(log, resolvedPhoto);
        });

        sheet.show();
    }

    // ── Online contacts — REAL-TIME per-user listeners ─────────────────────
    /**
     * Attaches a ValueEventListener on every contact's user node.
     * This means the online strip updates instantly when anyone goes online/offline —
     * no polling, no single-value snapshot.
     */
    private void loadOnlineContactsRealTime() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null || getContext() == null) return;

        contactsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                // Detach old per-user listeners
                removeOnlineListeners();
                onlineUsersMap.clear();

                for (DataSnapshot c : snap.getChildren()) {
                    User u = c.getValue(User.class);
                    if (u == null) continue;
                    if (u.uid == null) u.uid = c.getKey();
                    if (u.uid == null) continue;
                    String contactUid = u.uid;
                    // Skip self
                    String myUid = FirebaseUtils.getCurrentUid();
                    if (myUid != null && myUid.equals(contactUid)) continue;

                    // Attach persistent listener for this contact
                    ValueEventListener perUserListener = new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot userSnap) {
                            Boolean online = userSnap.child("online").getValue(Boolean.class);
                            String  name   = userSnap.child("name").getValue(String.class);
                            String  photo  = userSnap.child("photoUrl").getValue(String.class);
                            String  thumb  = userSnap.child("thumbUrl").getValue(String.class);

                            if (name  != null) u.name     = name;
                            if (photo != null) u.photoUrl = photo;
                            if (thumb != null) u.thumbUrl = thumb;

                            if (Boolean.TRUE.equals(online)) {
                                onlineUsersMap.put(contactUid, u);
                            } else {
                                onlineUsersMap.remove(contactUid);
                            }
                            // Re-render every time any user's status changes
                            renderOnlineUsers(new ArrayList<>(onlineUsersMap.values()));
                        }
                        @Override public void onCancelled(DatabaseError e) {}
                    };
                    onlineListeners.put(contactUid, perUserListener);
                    FirebaseUtils.getUserRef(contactUid).addValueEventListener(perUserListener);
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getContactsRef(uid).addValueEventListener(contactsListener);
    }

    private void removeOnlineListeners() {
        for (Map.Entry<String, ValueEventListener> entry : onlineListeners.entrySet()) {
            FirebaseUtils.getUserRef(entry.getKey()).removeEventListener(entry.getValue());
        }
        onlineListeners.clear();
    }

    private void renderOnlineUsers(List<User> users) {
        if (getContext() == null || llOnlineUsers == null) return;
        llOnlineUsers.removeAllViews();
        if (users.isEmpty()) {
            if (llOnlinePanel != null) llOnlinePanel.setVisibility(View.GONE);
            return;
        }
        if (llOnlinePanel != null) llOnlinePanel.setVisibility(View.VISIBLE);
        LayoutInflater inf = LayoutInflater.from(getContext());
        for (User u : users) {
            View item = inf.inflate(R.layout.item_online_user, llOnlineUsers, false);
            CircleImageView iv = item.findViewById(R.id.iv_online_avatar);
            TextView tv = item.findViewById(R.id.tv_online_name);
            tv.setText(u.name != null ? u.name : "User");
            String onlineAvatar = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
            if (onlineAvatar != null && !onlineAvatar.isEmpty()) {
                Glide.with(getContext()).load(onlineAvatar)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(iv);
            }
            // Online avatar tap → open bottom sheet for quick call
            final User finalU = u;
            item.setOnClickListener(x -> {
                CallLog fakeLog = new CallLog();
                fakeLog.partnerUid  = finalU.uid;
                fakeLog.partnerName = finalU.name;
                String photo = (finalU.thumbUrl != null && !finalU.thumbUrl.isEmpty())
                    ? finalU.thumbUrl : finalU.photoUrl;
                showContactBottomSheet(fakeLog, photo);
            });
            llOnlineUsers.addView(item);
        }
    }

    // ── Filters + Search ───────────────────────────────────────────────────
    private void setupChip(TextView chip, String filter) {
        if (chip == null) return;
        chip.setOnClickListener(x -> {
            activeFilter = filter;
            updateChipUI();
            applyFilter();
        });
    }

    private void updateChipUI() {
        if (getContext() == null) return;
        setChipState(chipAll,      "all");
        setChipState(chipMissed,   "missed");
        setChipState(chipContacts, "contacts");
        setChipState(chipNonspam,  "nonspam");
        setChipState(chipSpam,     "spam");
    }

    private void setChipState(TextView chip, String filter) {
        if (chip == null) return;
        boolean selected = filter.equals(activeFilter);
        chip.setBackgroundResource(selected ? R.drawable.chip_selected : R.drawable.chip_unselected);
        chip.setTextColor(getResources().getColor(
            selected ? android.R.color.white : R.color.text_primary, null));
    }

    private void applyFilter() {
        logs.clear();
        for (CallLog l : allLogs) {
            if (!searchQuery.isEmpty()) {
                String name = l.partnerName != null ? l.partnerName.toLowerCase() : "";
                if (!name.contains(searchQuery)) continue;
            }
            String dir = l.direction == null ? "" : l.direction.toLowerCase();
            switch (activeFilter) {
                case "missed":
                    if (!dir.contains("missed")) continue;
                    break;
                case "contacts":
                    if (l.partnerName == null || l.partnerName.isEmpty()) continue;
                    break;
                case "spam":
                    if (!dir.contains("spam")) continue;
                    break;
                case "nonspam":
                    if (dir.contains("spam") || dir.contains("missed")) continue;
                    break;
                default:
                    break;
            }
            logs.add(l);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyState != null)
            emptyState.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Call Logs — Offline-First ──────────────────────────────────────────
    private void loadCallLogs() {
        if (getContext() != null) {
            AppDatabase db = AppDatabase.getInstance(getContext());
            Executors.newSingleThreadExecutor().execute(() -> {
                List<CallLogEntity> cached = db.callLogDao().getAllCallLogsSync();
                if (cached != null && !cached.isEmpty()) {
                    List<CallLog> roomLogs = new ArrayList<>();
                    for (CallLogEntity e : cached) {
                        CallLog l = new CallLog();
                        l.id          = e.id;
                        l.partnerUid  = e.partnerUid;
                        l.partnerName = e.partnerName;
                        l.direction   = e.direction;
                        l.mediaType   = e.mediaType;
                        l.timestamp   = e.timestamp;
                        l.duration    = e.duration;
                        roomLogs.add(l);
                    }
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (allLogs.isEmpty()) {
                                allLogs.addAll(roomLogs);
                                applyFilter();
                            }
                        });
                    }
                }
            });
        }

        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        FirebaseUtils.getCallsRef(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                allLogs.clear();
                List<CallLogEntity> toSave = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    CallLog l = c.getValue(CallLog.class);
                    if (l != null) {
                        if (l.id == null) l.id = c.getKey();
                        allLogs.add(l);
                        CallLogEntity entity = new CallLogEntity();
                        entity.id          = l.id;
                        entity.partnerUid  = l.partnerUid;
                        entity.partnerName = l.partnerName;
                        entity.direction   = l.direction;
                        entity.mediaType   = l.mediaType;
                        entity.timestamp   = l.timestamp;
                        entity.duration    = l.duration;
                        toSave.add(entity);
                    }
                }
                Collections.sort(allLogs, (a, b) -> {
                    long ta = a.timestamp == null ? 0 : a.timestamp;
                    long tb = b.timestamp == null ? 0 : b.timestamp;
                    return Long.compare(tb, ta);
                });
                applyFilter();

                if (getContext() != null && !toSave.isEmpty()) {
                    AppDatabase db = AppDatabase.getInstance(getContext());
                    Executors.newSingleThreadExecutor().execute(() ->
                        db.callLogDao().insertCallLogs(toSave));
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    // ── Contact-specific Call History BottomSheet ──────────────────────────
    /**
     * Shows a second bottom sheet listing all calls (voice + video) with this contact.
     * Each row shows: type icon, label, time, duration, and a quick-call icon.
     */
    private void showCallHistorySheet(CallLog contactLog, String resolvedPhoto) {
        if (getContext() == null || contactLog.partnerUid == null) return;

        // Filter allLogs to only this contact's calls
        List<CallLog> contactLogs = new ArrayList<>();
        for (CallLog l : allLogs) {
            if (contactLog.partnerUid.equals(l.partnerUid)) {
                contactLogs.add(l);
            }
        }

        BottomSheetDialog histSheet = new BottomSheetDialog(getContext(),
            com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);
        View sv = LayoutInflater.from(getContext())
            .inflate(R.layout.bottom_sheet_call_history, null);
        histSheet.setContentView(sv);

        CircleImageView ivAvatar = sv.findViewById(R.id.iv_history_avatar);
        TextView tvName          = sv.findViewById(R.id.tv_history_name);
        TextView tvCount         = sv.findViewById(R.id.tv_history_count);
        View     btnClose        = sv.findViewById(R.id.btn_close_history);
        RecyclerView rv          = sv.findViewById(R.id.rv_call_history_sheet);
        View llEmpty             = sv.findViewById(R.id.ll_history_empty);

        // Header
        tvName.setText(contactLog.partnerName != null ? contactLog.partnerName : "Unknown");
        int total = contactLogs.size();
        tvCount.setText(total + " call" + (total != 1 ? "s" : ""));

        // Avatar
        if (resolvedPhoto != null && !resolvedPhoto.isEmpty()) {
            Glide.with(getContext()).load(resolvedPhoto)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person)
                .into(ivAvatar);
        }

        // Avatar in history sheet → same full-photo zoom
        ivAvatar.setOnClickListener(x -> {
            if (getContext() == null || contactLog.partnerUid == null) return;
            FirebaseUtils.getUserRef(contactLog.partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String fullPhoto = snap.child("photoUrl").getValue(String.class);
                        String toShow = (fullPhoto != null && !fullPhoto.isEmpty())
                            ? fullPhoto : resolvedPhoto;
                        showAvatarZoom(toShow);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        showAvatarZoom(resolvedPhoto);
                    }
                });
        });

        // Close button
        btnClose.setOnClickListener(x -> histSheet.dismiss());

        // RecyclerView
        if (contactLogs.isEmpty()) {
            if (rv     != null) rv.setVisibility(View.GONE);
            if (llEmpty != null) llEmpty.setVisibility(View.VISIBLE);
        } else {
            if (llEmpty != null) llEmpty.setVisibility(View.GONE);
            rv.setLayoutManager(new LinearLayoutManager(getContext()));
            rv.setAdapter(new ContactCallHistoryAdapter(contactLogs));
        }

        histSheet.show();
    }

    // ── Full-screen avatar zoom (pinch-to-zoom, same as ProfileActivity) ──
    /**
     * Opens a full-screen dialog with PhotoView (pinch-to-zoom support).
     * Always uses full photoUrl — not thumb — for maximum quality.
     */
    private void showAvatarZoom(String photoUrl) {
        if (getContext() == null) return;

        android.app.Dialog dialog = new android.app.Dialog(
            getContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        android.widget.FrameLayout root = new android.widget.FrameLayout(getContext());
        root.setBackgroundColor(0xEE000000);

        com.github.chrisbanes.photoview.PhotoView photoView =
            new com.github.chrisbanes.photoview.PhotoView(getContext());
        android.widget.FrameLayout.LayoutParams ivLp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        photoView.setLayoutParams(ivLp);
        photoView.setMinimumScale(1f);
        photoView.setMediumScale(2f);
        photoView.setMaximumScale(5f);
        // Tap outside photo → dismiss
        photoView.setOnOutsidePhotoTapListener(v -> dialog.dismiss());

        // Close button — top right
        android.widget.ImageButton btnClose = new android.widget.ImageButton(getContext());
        float dp = getResources().getDisplayMetrics().density;
        int closeSizePx = (int)(40 * dp);
        android.widget.FrameLayout.LayoutParams closeLp =
            new android.widget.FrameLayout.LayoutParams(closeSizePx, closeSizePx);
        closeLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        closeLp.topMargin   = (int)(40 * dp);
        closeLp.rightMargin = (int)(16 * dp);
        btnClose.setLayoutParams(closeLp);
        btnClose.setImageResource(com.callx.app.calls.R.drawable.ic_close);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setOnClickListener(v -> dialog.dismiss());

        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(getContext())
                .load(photoUrl)
                .placeholder(com.callx.app.calls.R.drawable.ic_person)
                .error(com.callx.app.calls.R.drawable.ic_person)
                .into(photoView);
        } else {
            photoView.setImageResource(com.callx.app.calls.R.drawable.ic_person);
        }

        root.addView(photoView);
        root.addView(btnClose);
        dialog.setContentView(root);
        android.view.Window w = dialog.getWindow();
        if (w != null) w.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT);
        dialog.show();
    }

    // ── Cleanup — remove real-time listeners when fragment is destroyed ────
    @Override public void onDestroyView() {
        super.onDestroyView();
        removeOnlineListeners();
        String uid = FirebaseUtils.getCurrentUid();
        if (uid != null && contactsListener != null)
            FirebaseUtils.getContactsRef(uid).removeEventListener(contactsListener);
    }

    // ── Selection ──────────────────────────────────────────────────────────
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
        if (tvSelectedCount != null) tvSelectedCount.setText(count + " selected");
    }

    private void confirmDeleteSelected() {
        int count = adapter == null ? 0 : adapter.getSelectedCount();
        if (count == 0) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("Delete " + count + " call log" + (count > 1 ? "s" : "") + "?")
            .setMessage("Selected call history will be permanently deleted.")
            .setPositiveButton("Delete", (d, w) -> deleteSelected())
            .setNegativeButton("Cancel", null).show();
    }

    private void deleteSelected() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        List<CallLog> selected = adapter.getSelectedItems();
        for (CallLog l : selected)
            if (l.id != null) FirebaseUtils.getCallsRef(uid).child(l.id).removeValue();
        allLogs.removeAll(selected);
        adapter.clearSelection();
        applyFilter();
        if (llSelectionBar != null) llSelectionBar.setVisibility(View.GONE);
    }
}
