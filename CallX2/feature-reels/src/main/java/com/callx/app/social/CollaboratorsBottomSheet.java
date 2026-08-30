package com.callx.app.social;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.followers.AvatarScrollPrefetchHelper;
import com.callx.app.followers.FollowAvatarBinder;
import com.callx.app.models.ReelModel;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import android.widget.FrameLayout;
import android.content.Intent;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.database.*;

import java.util.*;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * CollaboratorsBottomSheet — Instagram-style "Collaborators" sheet.
 *
 * Shown when the person taps the owner-name / avatar-stack row on a joint
 * post ("@owner and N others"). Lists the owner first, then every invited
 * collaborator (accepted collaborators shown normally, still-pending ones
 * labeled "Pending" so the poster can see invite status at a glance), each
 * with a Follow/Following button — mirrors screenshot 2's Collaborators
 * bottom sheet UI.
 */
public class CollaboratorsBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "CollaboratorsBottomSheet";

    private static final String ARG_REEL_ID     = "cbs_reel_id";
    private static final String ARG_OWNER_UID   = "cbs_owner_uid";
    private static final String ARG_OWNER_NAME  = "cbs_owner_name";
    private static final String ARG_OWNER_PHOTO = "cbs_owner_photo";

    public static CollaboratorsBottomSheet newInstance(String reelId, String ownerUid,
                                                         String ownerName, String ownerPhoto) {
        CollaboratorsBottomSheet f = new CollaboratorsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_REEL_ID,     reelId);
        args.putString(ARG_OWNER_UID,   ownerUid);
        args.putString(ARG_OWNER_NAME,  ownerName);
        args.putString(ARG_OWNER_PHOTO, ownerPhoto);
        f.setArguments(args);
        return f;
    }

    private RecyclerView rv;
    private ProgressBar  progressBar;
    private TextView     tvEmpty;

    private String reelId, ownerUid, ownerName, ownerPhoto, myUid;
    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Boolean> followingMap = new HashMap<>();
    private RowAdapter adapter;

    @Override
    public void onStart() {
        super.onStart();
        BottomSheetDialog d = (BottomSheetDialog) getDialog();
        if (d == null) return;
        FrameLayout bs = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bs == null) return;
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bs);
        behavior.setHideable(true);
        behavior.setSkipCollapsed(true);
        behavior.setFitToContents(true);
        behavior.setDraggable(true);
        behavior.setExpandedOffset(0);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_collaborators, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        myUid = FirebaseUtils.getCurrentUid();

        Bundle args = getArguments();
        if (args != null) {
            reelId     = args.getString(ARG_REEL_ID);
            ownerUid   = args.getString(ARG_OWNER_UID);
            ownerName  = args.getString(ARG_OWNER_NAME);
            ownerPhoto = args.getString(ARG_OWNER_PHOTO);
        }

        rv          = v.findViewById(R.id.rv_collaborators);
        progressBar = v.findViewById(R.id.progress_bar);
        tvEmpty     = v.findViewById(R.id.tv_empty);

        adapter = new RowAdapter(rows);
        LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        rv.setLayoutManager(lm);
        rv.setAdapter(adapter);
        // FIX (velocity-based prefetch): fast dismiss-scroll skips ahead, slow scroll warms rows — see FollowAvatarBinder.
        AvatarScrollPrefetchHelper.attach(rv, lm, new FollowAvatarBinder.AvatarSource() {
            @Override public String photo(int index) { return rows.get(index).photo; }
            @Override public long avatarVersion(int index) { return 0L; }
            @Override public int size() { return rows.size(); }
        });

        if (reelId == null) { showEmpty(); return; }
        loadMyFollowing();
    }

    private void loadMyFollowing() {
        if (myUid == null || myUid.isEmpty()) { loadCollaborators(); return; }
        FirebaseUtils.getReelFollowsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        Boolean val = child.getValue(Boolean.class);
                        if (Boolean.TRUE.equals(val) && child.getKey() != null) {
                            followingMap.put(child.getKey(), true);
                        }
                    }
                    loadCollaborators();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { loadCollaborators(); }
            });
    }

    private void loadCollaborators() {
        progressBar.setVisibility(View.VISIBLE);
        FirebaseDatabase.getInstance()
            .getReference("reels").child(reelId).child("collabMap")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded()) return;
                    rows.clear();

                    // Owner is always shown first.
                    if (ownerUid != null && !ownerUid.isEmpty()) {
                        rows.add(new Row(ownerUid, ownerName != null ? ownerName : "User",
                            "", ownerPhoto != null ? ownerPhoto : "", "owner"));
                    }

                    List<ReelModel.CollabCollaborator> collabs = new ArrayList<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        ReelModel.CollabCollaborator c = child.getValue(ReelModel.CollabCollaborator.class);
                        if (c != null && c.uid != null && !c.uid.isEmpty()
                                && !"declined".equals(c.status)) {
                            collabs.add(c);
                        }
                    }
                    collabs.sort((a, b) -> Long.compare(a.invitedAt, b.invitedAt));
                    for (ReelModel.CollabCollaborator c : collabs) {
                        rows.add(new Row(c.uid, c.displayName, c.handle, c.avatarUrl, c.status));
                    }

                    progressBar.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                    rv.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { showEmpty(); }
            });
    }

    private void showEmpty() {
        progressBar.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
        rv.setVisibility(View.GONE);
    }

    private void toggleFollow(Row row, Button btnFollow) {
        if (myUid == null || myUid.isEmpty()) return;
        boolean nowFollowing = !Boolean.TRUE.equals(followingMap.get(row.uid));
        followingMap.put(row.uid, nowFollowing);
        DatabaseReference followRef = FirebaseUtils.getReelFollowsRef(myUid).child(row.uid);
        if (nowFollowing) {
            followRef.setValue(true);
            btnFollow.setText("Following");
        } else {
            followRef.removeValue();
            btnFollow.setText("Follow");
        }
    }

    // ── Row model ──────────────────────────────────────────────────────────
    private static class Row {
        final String uid, name, handle, photo, status; // status: "owner" | "pending" | "accepted"
        Row(String uid, String name, String handle, String photo, String status) {
            this.uid = uid; this.name = name != null ? name : "";
            this.handle = handle != null ? handle : "";
            this.photo = photo != null ? photo : "";
            this.status = status != null ? status : "accepted";
        }
    }

    // ── Adapter — reuses item_reel_liker.xml row layout ──────────────────────
    private class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {
        final List<Row> data;
        RowAdapter(List<Row> d) { data = d; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reel_liker, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Row r = data.get(pos);
            h.tvName.setText(r.name.isEmpty() ? "User" : r.name);

            if (!r.handle.isEmpty()) {
                h.tvUsername.setVisibility(View.VISIBLE);
                h.tvUsername.setText("@" + r.handle);
            } else {
                h.tvUsername.setVisibility(View.GONE);
            }

            // Reuse the timestamp label slot to show role/status, matching
            // the reference screenshot's plain avatar+name+Follow row while
            // still surfacing invite state to the poster.
            if ("owner".equals(r.status)) {
                h.tvTimestamp.setVisibility(View.GONE);
            } else if ("pending".equals(r.status)) {
                h.tvTimestamp.setVisibility(View.VISIBLE);
                h.tvTimestamp.setText("Pending");
            } else {
                h.tvTimestamp.setVisibility(View.GONE);
            }

            // FIX (avatar pipeline parity): shared L2/L3 cache + density-aware tier decode instead of a flat Glide load — see FollowAvatarBinder.
            FollowAvatarBinder.bind(requireContext(), h.ivAvatar, r.photo, 0L, R.drawable.ic_person);

            h.ivVerified.setVisibility(View.GONE);
            h.btnMessage.setVisibility(View.GONE);

            if (r.uid.equals(myUid)) {
                h.btnFollow.setVisibility(View.GONE);
            } else {
                h.btnFollow.setVisibility(View.VISIBLE);
                boolean following = Boolean.TRUE.equals(followingMap.get(r.uid));
                h.btnFollow.setText(following ? "Following" : "Follow");
                h.btnFollow.setOnClickListener(v -> toggleFollow(r, h.btnFollow));
            }

            h.itemView.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(requireContext(), UserReelsActivity.class);
                    i.putExtra(UserReelsActivity.EXTRA_UID,   r.uid);
                    i.putExtra(UserReelsActivity.EXTRA_NAME,  r.name);
                    i.putExtra(UserReelsActivity.EXTRA_PHOTO, r.photo);
                    startActivity(i);
                    dismiss();
                } catch (Exception ignored) {}
            });
        }

        @Override public int getItemCount() { return data.size(); }

        // FIX (lifecycle-aware cancel): stop an in-flight request for a row that just scrolled off screen.
        @Override public void onViewRecycled(@NonNull VH h) {
            FollowAvatarBinder.cancel(requireContext(), h.ivAvatar);
        }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            ImageView       ivVerified;
            TextView        tvName, tvUsername, tvTimestamp;
            Button          btnFollow, btnMessage;
            VH(@NonNull View v) {
                super(v);
                ivAvatar    = v.findViewById(R.id.iv_avatar);
                ivVerified  = v.findViewById(R.id.iv_verified);
                tvName      = v.findViewById(R.id.tv_name);
                tvUsername  = v.findViewById(R.id.tv_username);
                tvTimestamp = v.findViewById(R.id.tv_timestamp);
                btnFollow   = v.findViewById(R.id.btn_follow);
                btnMessage  = v.findViewById(R.id.btn_message);
            }
        }
    }
}
