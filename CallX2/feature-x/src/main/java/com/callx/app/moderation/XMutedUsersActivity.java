package com.callx.app.moderation;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.models.XUser;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * XMutedUsersActivity — list of users muted by me, with Unmute option.
 */
public class XMutedUsersActivity extends AppCompatActivity {

    private RecyclerView rv;
    private ProgressBar  pb;
    private TextView     tvEmpty;
    private XBlockedUsersActivity.UserListAdapter adapter;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_muted_users);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        rv      = findViewById(R.id.rv_muted);
        pb      = findViewById(R.id.pb_muted);
        tvEmpty = findViewById(R.id.tv_muted_empty);

        View btnBack = findViewById(R.id.btn_muted_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new XBlockedUsersActivity.UserListAdapter("Unmute", this::unmute);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        loadMutedUsers();
    }

    private void loadMutedUsers() {
        if (pb != null) pb.setVisibility(View.VISIBLE);
        XFirebaseUtils.userMutedRef(myUid).get()
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
                                if (tvEmpty != null) tvEmpty.setVisibility(users.isEmpty() ? View.VISIBLE : View.GONE);
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

    private void unmute(XUser user) {
        XFirebaseUtils.userMutedRef(myUid).child(user.uid).removeValue();
        adapter.removeUser(user.uid);
        Toast.makeText(this, "@" + user.handle + " unmuted", Toast.LENGTH_SHORT).show();
    }
}
