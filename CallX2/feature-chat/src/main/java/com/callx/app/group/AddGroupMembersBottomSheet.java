package com.callx.app.group;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.callx.app.chat.R;
import com.callx.app.conversation.info.MaxHeightRecyclerView;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AddGroupMembersBottomSheet — replaces GroupInfoActivity's old "Add Member"
 * flow (a raw EditText where an admin had to type a stranger's exact
 * CallX ID/UID). Matches WhatsApp's actual "Add participants" behavior:
 *
 *  - Picks from your own contacts (people you already know on the app),
 *    never by hand-typed ID — you can't add someone whose UID you don't
 *    have memorized, same as WhatsApp only offers your phone contacts.
 *  - Multi-select with checkboxes (MemberSelectAdapter, already used by
 *    NewGroupActivity's own contact picker) — add several people in one
 *    go instead of one round-trip dialog per person.
 *  - People already in the group are filtered out of the list entirely,
 *    so there's nothing to accidentally re-add.
 *  - A live search box narrows the contact list by name.
 *  - One combined system message for the whole batch ("You added Aman,
 *    Priya and 2 others"), the way WhatsApp posts a single group-events
 *    line instead of one line per person added.
 */
public class AddGroupMembersBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "AddGroupMembersBottomSheet";
    private static final String ARG_GROUP_ID = "groupId";
    private static final String ARG_EXCLUDED = "excludedUids";

    /** Implemented by GroupInfoActivity to refresh its member list/UI once the batch add lands. */
    public interface Listener {
        void onMembersAdded(List<String> addedUids, List<String> addedNames);
    }

    private Listener listener;
    private String groupId;
    private final List<User> allContacts = new ArrayList<>();
    private final List<User> visibleContacts = new ArrayList<>();
    private final Set<String> selectedUids = new HashSet<>();
    private MemberSelectAdapter adapter;

    public static AddGroupMembersBottomSheet newInstance(String groupId, ArrayList<String> excludedUids) {
        AddGroupMembersBottomSheet sheet = new AddGroupMembersBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_GROUP_ID, groupId);
        args.putStringArrayList(ARG_EXCLUDED, excludedUids);
        sheet.setArguments(args);
        return sheet;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_add_members, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        groupId = getArguments() != null ? getArguments().getString(ARG_GROUP_ID) : null;
        ArrayList<String> excluded = getArguments() != null
                ? getArguments().getStringArrayList(ARG_EXCLUDED) : new ArrayList<>();
        Set<String> excludedSet = new HashSet<>(excluded != null ? excluded : new ArrayList<>());

        v.findViewById(R.id.iv_add_members_close).setOnClickListener(x -> dismiss());

        MaxHeightRecyclerView rv = v.findViewById(R.id.rv_add_members);
        ProgressBar progress = v.findViewById(R.id.progress_add_members);
        TextView tvEmpty = v.findViewById(R.id.tv_no_contacts);
        MaterialButton btnConfirm = v.findViewById(R.id.btn_add_members_confirm);
        EditText etSearch = v.findViewById(R.id.et_search_contacts);

        rv.setMaxHeightPx((int) (getResources().getDisplayMetrics().heightPixels * 0.5f));
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new MemberSelectAdapter(visibleContacts, selectedUids) {
            @Override
            public void onBindViewHolder(@NonNull VH h, int pos) {
                super.onBindViewHolder(h, pos);
                // Re-attach so the confirm button's label/enabled state
                // tracks the live selection count, WhatsApp-style.
                h.itemView.post(() -> updateConfirmButton(btnConfirm));
            }
        };
        rv.setAdapter(adapter);

        String currentUid = FirebaseUtils.getCurrentUid();
        FirebaseUtils.getContactsRef(currentUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                allContacts.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    User u = c.getValue(User.class);
                    if (u == null) continue;
                    if (u.uid == null) u.uid = c.getKey();
                    if (u.uid == null || excludedSet.contains(u.uid) || u.uid.equals(currentUid)) continue;
                    allContacts.add(u);
                }
                applyFilter(etSearch.getText().toString());
                progress.setVisibility(View.GONE);
                if (allContacts.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                } else {
                    rv.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                tvEmpty.setText("Couldn't load contacts");
                tvEmpty.setVisibility(View.VISIBLE);
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnConfirm.setOnClickListener(x -> confirmAdd(btnConfirm));
        updateConfirmButton(btnConfirm);
    }

    private void applyFilter(String query) {
        visibleContacts.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        for (User u : allContacts) {
            String name = u.name != null ? u.name.toLowerCase(Locale.getDefault()) : "";
            if (q.isEmpty() || name.contains(q)) visibleContacts.add(u);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateConfirmButton(MaterialButton btn) {
        int count = selectedUids.size();
        btn.setEnabled(count > 0);
        btn.setText(count == 0 ? "Add" : "Add (" + count + ")");
    }

    private void confirmAdd(MaterialButton btnConfirm) {
        if (selectedUids.isEmpty() || groupId == null) return;
        btnConfirm.setEnabled(false);
        btnConfirm.setText("Adding...");

        List<String> addedUids = new ArrayList<>();
        List<String> addedNames = new ArrayList<>();
        for (User u : allContacts) {
            if (selectedUids.contains(u.uid)) {
                addedUids.add(u.uid);
                addedNames.add(u.name != null && !u.name.isEmpty() ? u.name : "Member");
            }
        }

        long now = System.currentTimeMillis();
        int pending = addedUids.size();
        final int[] remaining = {pending};
        for (int i = 0; i < addedUids.size(); i++) {
            String uid = addedUids.get(i);
            java.util.Map<String, Object> memberData = new java.util.HashMap<>();
            memberData.put("name", addedNames.get(i));
            memberData.put("role", "member");
            memberData.put("addedAt", now);
            FirebaseUtils.getGroupMembersRef(groupId).child(uid).setValue(memberData)
                    .addOnCompleteListener(t -> {
                        remaining[0]--;
                        if (remaining[0] == 0) onBatchAddComplete(addedUids, addedNames);
                    });
            FirebaseUtils.db().getReference("userGroups").child(uid).child(groupId).setValue(true);
        }
    }

    private void onBatchAddComplete(List<String> addedUids, List<String> addedNames) {
        if (listener != null) listener.onMembersAdded(addedUids, addedNames);
        if (getContext() != null) {
            Toast.makeText(getContext(),
                    addedNames.size() == 1 ? addedNames.get(0) + " added"
                            : addedNames.size() + " participants added",
                    Toast.LENGTH_SHORT).show();
        }
        dismissAllowingStateLoss();
    }
}
