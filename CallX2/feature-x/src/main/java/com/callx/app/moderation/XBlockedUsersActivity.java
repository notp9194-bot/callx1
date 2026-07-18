package com.callx.app.moderation;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.XUser;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
import com.callx.app.profile.XProfileSheet;

/**
 * XBlockedUsersActivity — list of users blocked by me, with Unblock option.
 */
public class XBlockedUsersActivity extends AppCompatActivity {

    private RecyclerView rv;
    private ProgressBar  pb;
    private TextView     tvEmpty;
    private UserListAdapter adapter;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_blocked_users);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        rv      = findViewById(R.id.rv_blocked);
        pb      = findViewById(R.id.pb_blocked);
        tvEmpty = findViewById(R.id.tv_blocked_empty);

        View btnBack = findViewById(R.id.btn_blocked_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new UserListAdapter("Unblock", this::unblock);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        loadBlockedUsers();
    }

    private void loadBlockedUsers() {
        if (pb != null) pb.setVisibility(View.VISIBLE);
        XFirebaseUtils.userBlockedRef(myUid).get()
            .addOnSuccessListener(snap -> {
                List<String> uids = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) uids.add(ds.getKey());
                if (uids.isEmpty()) {
                    if (pb != null) pb.setVisibility(View.GONE);
                    if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                    return;
                }
                long[] pending = {uids.size()};
                List<XUser> users = new ArrayList<>();
                for (String uid : uids) {
                    final String u = uid;
                    XFirebaseUtils.xUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot ds) {
                            XUser user = ds.getValue(XUser.class);
                            if (user != null) { user.uid = u; users.add(user); }
                            if (--pending[0] <= 0) {
                                users.sort((a, b) -> (a.name != null ? a.name : "").compareTo(b.name != null ? b.name : ""));
                                adapter.setUsers(users);
                                if (pb != null) pb.setVisibility(View.GONE);
                                if (tvEmpty != null)
                                    tvEmpty.setVisibility(users.isEmpty() ? View.VISIBLE : View.GONE);
                            }
                        }
                        @Override public void onCancelled(DatabaseError e) {
                            if (--pending[0] <= 0) { if (pb != null) pb.setVisibility(View.GONE); }
                        }
                    });
                }
            })
            .addOnFailureListener(e -> { if (pb != null) pb.setVisibility(View.GONE); });
    }

    private void unblock(XUser user) {
        XFirebaseUtils.userBlockedRef(myUid).child(user.uid).removeValue();
        XFirebaseUtils.userBlockedRef(user.uid).child(myUid).removeValue();
        adapter.removeUser(user.uid);
        Toast.makeText(this, "@" + user.handle + " unblocked", Toast.LENGTH_SHORT).show();
    }

    // ── Generic user list adapter ─────────────────────────────────────────────

    static class UserListAdapter extends RecyclerView.Adapter<UserListAdapter.UVH> {
        private final List<XUser> users = new ArrayList<>();
        private final String actionLabel;
        private final OnAction action;
        interface OnAction { void run(XUser user); }

        UserListAdapter(String actionLabel, OnAction action) {
            this.actionLabel = actionLabel; this.action = action;
        }

        void setUsers(List<XUser> list) {
            users.clear(); users.addAll(list); notifyDataSetChanged();
        }

        void removeUser(String uid) {
            for (int i = 0; i < users.size(); i++) {
                if (uid.equals(users.get(i).uid)) { users.remove(i); notifyItemRemoved(i); return; }
            }
        }

        @NonNull @Override
        public UVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new UVH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_x_user_row, p, false));
        }

        @Override public void onBindViewHolder(@NonNull UVH h, int pos) { h.bind(users.get(pos)); }
        @Override public int getItemCount() { return users.size(); }

        class UVH extends RecyclerView.ViewHolder {
            ImageView ivAvatar;
            TextView tvName, tvHandle;
            Button btnAction;

            UVH(View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_x_user_avatar);
                tvName    = v.findViewById(R.id.tv_x_user_name);
                tvHandle  = v.findViewById(R.id.tv_x_user_handle);
                btnAction = v.findViewById(R.id.btn_x_user_action);
            }

            void bind(XUser user) {
                if (tvName   != null) tvName.setText(user.name != null ? user.name : "User");
                if (tvHandle != null) tvHandle.setText("@" + (user.handle != null ? user.handle : ""));
                if (ivAvatar != null) {
                    String url = (user.thumbUrl != null && !user.thumbUrl.isEmpty()) ? user.thumbUrl : user.photoUrl;
                    Glide.with(ivAvatar.getContext()).load(url).circleCrop()
                        .override(96, 96)
                        .placeholder(R.drawable.ic_person).into(ivAvatar);
                }
                if (btnAction != null) {
                    btnAction.setText(actionLabel);
                    btnAction.setOnClickListener(v -> action.run(user));
                }
                itemView.setOnClickListener(v -> {
                    if (v.getContext() instanceof FragmentActivity)
                        XProfileSheet.showProfile(
                            ((FragmentActivity) v.getContext()).getSupportFragmentManager(),
                            user.uid);
                });
            }
        }
    }
}
