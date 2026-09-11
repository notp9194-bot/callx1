package com.callx.app.group;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.conversation.info.MaxHeightRecyclerView;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JoinRequestsBottomSheet — admin-only review screen for
 * groups/{groupId}/joinRequests, opened from GroupInfoActivity's
 * "Join Requests" row (see JoinGroupActivity#requestToJoin for where these
 * requests get created when groupSettings/approvalRequired is on).
 *
 * Approve -> adds the requester to "members" (same shape
 * AddGroupMembersBottomSheet writes), removes the request, posts a system
 * message + audit log entry, and nudges Sender Key redistribution so the
 * new member can decrypt going forward.
 * Reject  -> just removes the request; logged to the audit trail but does
 * not post anything into the group chat (matches WhatsApp — a rejected
 * requester is never told, or announced, in the group).
 */
public class JoinRequestsBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "JoinRequestsBottomSheet";
    private static final String ARG_GROUP_ID   = "groupId";
    private static final String ARG_GROUP_NAME = "groupName";

    private String groupId, groupName;
    private final List<RequestItem> requests = new ArrayList<>();
    private RequestsAdapter adapter;
    private TextView tvEmpty;
    private ProgressBar progress;
    private RecyclerView rv;
    private ValueEventListener requestsListener;

    public static JoinRequestsBottomSheet newInstance(String groupId, String groupName) {
        JoinRequestsBottomSheet sheet = new JoinRequestsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_GROUP_ID, groupId);
        args.putString(ARG_GROUP_NAME, groupName);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_join_requests, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        groupId   = getArguments() != null ? getArguments().getString(ARG_GROUP_ID) : null;
        groupName = getArguments() != null ? getArguments().getString(ARG_GROUP_NAME) : "Group";

        v.findViewById(R.id.iv_join_requests_close).setOnClickListener(x -> dismiss());

        rv       = v.findViewById(R.id.rv_join_requests);
        progress = v.findViewById(R.id.progress_join_requests);
        tvEmpty  = v.findViewById(R.id.tv_no_join_requests);

        ((MaxHeightRecyclerView) rv).setMaxHeightPx(
                (int) (getResources().getDisplayMetrics().heightPixels * 0.5f));
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new RequestsAdapter(requests, this::approve, this::reject);
        rv.setAdapter(adapter);

        if (groupId == null) { dismiss(); return; }

        requestsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                requests.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid  = c.getKey();
                    String name = c.child("name").getValue(String.class);
                    Long requestedAt = c.child("requestedAt").getValue(Long.class);
                    if (uid == null) continue;
                    requests.add(new RequestItem(uid, name != null ? name : "Member",
                            requestedAt != null ? requestedAt : 0L));
                }
                progress.setVisibility(View.GONE);
                if (requests.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    rv.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    rv.setVisibility(View.VISIBLE);
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                tvEmpty.setText("Couldn't load join requests");
                tvEmpty.setVisibility(View.VISIBLE);
            }
        };
        FirebaseUtils.getGroupJoinRequestsRef(groupId).addValueEventListener(requestsListener);
    }

    @Override
    public void onDestroyView() {
        if (groupId != null && requestsListener != null) {
            FirebaseUtils.getGroupJoinRequestsRef(groupId).removeEventListener(requestsListener);
        }
        super.onDestroyView();
    }

    // ── Approve ───────────────────────────────────────────────────────────
    private void approve(RequestItem item) {
        Map<String, Object> memberData = new HashMap<>();
        memberData.put("name", item.name);
        memberData.put("role", "member");
        memberData.put("joinedAt", System.currentTimeMillis());

        Map<String, Object> updates = new HashMap<>();
        updates.put("groups/" + groupId + "/members/" + item.uid, memberData);
        updates.put("groups/" + groupId + "/joinRequests/" + item.uid, null);
        updates.put("userGroups/" + item.uid + "/" + groupId, true);

        FirebaseUtils.db().getReference().updateChildren(updates)
                .addOnCompleteListener(t -> {
                    if (getContext() == null) return;
                    if (!t.isSuccessful()) {
                        Toast.makeText(getContext(), "Could not approve request", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    postSystemMessage(item.name + " joined the group");
                    postAuditLog("approve_join_request", item.name);
                    // Nudge existing members to redistribute their Sender
                    // Key to the newly-approved member, same as
                    // AddGroupMembersBottomSheet does for a direct add.
                    com.callx.app.utils.PushNotify.notifyGroupKeyRotate(groupId, null);
                    Toast.makeText(getContext(), item.name + " approved", Toast.LENGTH_SHORT).show();
                });
    }

    // ── Reject ────────────────────────────────────────────────────────────
    private void reject(RequestItem item) {
        FirebaseUtils.getGroupJoinRequestRef(groupId, item.uid).removeValue()
                .addOnCompleteListener(t -> {
                    if (getContext() == null) return;
                    if (t.isSuccessful()) {
                        postAuditLog("reject_join_request", item.name);
                        Toast.makeText(getContext(), "Request declined", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), "Could not decline request", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void postSystemMessage(String text) {
        DatabaseReference sysRef = FirebaseUtils.getGroupMessagesRef(groupId).push();
        Map<String, Object> sys = new HashMap<>();
        sys.put("id",        sysRef.getKey());
        sys.put("senderId",  "system");
        sys.put("senderName","System");
        sys.put("text",      text);
        sys.put("type",      "system");
        sys.put("timestamp", System.currentTimeMillis());
        sysRef.setValue(sys);
    }

    private void postAuditLog(String action, String detail) {
        Map<String, Object> audit = new HashMap<>();
        audit.put("action",    action);
        audit.put("detail",    detail);
        audit.put("byUid",     FirebaseUtils.getCurrentUid());
        audit.put("byName",    FirebaseUtils.getCurrentName());
        audit.put("timestamp", System.currentTimeMillis());
        FirebaseUtils.getGroupAuditLogRef(groupId).push().setValue(audit);
    }

    // ── Model + adapter ───────────────────────────────────────────────────
    private static class RequestItem {
        final String uid, name;
        final long requestedAt;
        RequestItem(String uid, String name, long requestedAt) {
            this.uid = uid; this.name = name; this.requestedAt = requestedAt;
        }
    }

    private interface RequestAction { void act(RequestItem item); }

    private static class RequestsAdapter extends RecyclerView.Adapter<RequestsAdapter.VH> {
        private final List<RequestItem> items;
        private final RequestAction onApprove, onReject;

        RequestsAdapter(List<RequestItem> items, RequestAction onApprove, RequestAction onReject) {
            this.items = items;
            this.onApprove = onApprove;
            this.onReject = onReject;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_join_request, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            RequestItem item = items.get(pos);
            h.tvName.setText(item.name);
            h.btnApprove.setOnClickListener(v -> onApprove.act(item));
            h.btnReject.setOnClickListener(v -> onReject.act(item));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName;
            ImageButton btnApprove, btnReject;
            VH(@NonNull View v) {
                super(v);
                tvName     = v.findViewById(R.id.tv_request_name);
                btnApprove = v.findViewById(R.id.btn_approve_request);
                btnReject  = v.findViewById(R.id.btn_reject_request);
            }
        }
    }
}
