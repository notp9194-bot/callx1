package com.callx.app.fragments;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.activities.ChatActivity;
import com.callx.app.adapters.CallHistoryAdapter;
import com.callx.app.models.CallLog;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

public class CallsFragment extends Fragment {

    private final List<CallLog> logs = new ArrayList<>();
    private CallHistoryAdapter adapter;
    private View emptyState;
    private LinearLayout llOnlineUsers;
    private TextView tvNoOnline;
    private String myUid;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_calls, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_calls);
        emptyState    = v.findViewById(R.id.empty_calls);
        llOnlineUsers = v.findViewById(R.id.ll_online_users);
        tvNoOnline    = v.findViewById(R.id.tv_no_online);
        myUid = FirebaseUtils.getCurrentUid();
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CallHistoryAdapter(logs);
        rv.setAdapter(adapter);
        loadOnlineContacts();
        loadCallLogs();
        return v;
    }

    // Show contacts who are currently online — EXCLUDING self
    private void loadOnlineContacts() {
        if (myUid == null) return;
        FirebaseUtils.getContactsRef(myUid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<String> contactUids = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getKey();
                    if (uid != null && !uid.equals(myUid)) contactUids.add(uid);
                }
                checkOnlineStatus(contactUids);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private void checkOnlineStatus(List<String> uids) {
        if (uids.isEmpty()) { showNoOnline(); return; }
        final int[] done = {0};
        final List<User> onlineList = new ArrayList<>();
        for (String uid : uids) {
            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    Boolean online = snap.child("online").getValue(Boolean.class);
                    if (Boolean.TRUE.equals(online)) {
                        User u = new User();
                        u.uid      = uid;
                        u.name     = snap.child("name").getValue(String.class);
                        u.photoUrl = snap.child("photoUrl").getValue(String.class);
                        onlineList.add(u);
                    }
                    done[0]++;
                    if (done[0] >= uids.size()) renderOnlineUsers(onlineList);
                }
                @Override public void onCancelled(DatabaseError e) {
                    done[0]++;
                    if (done[0] >= uids.size()) renderOnlineUsers(onlineList);
                }
            });
        }
    }

    private void renderOnlineUsers(List<User> users) {
        if (getContext() == null || llOnlineUsers == null) return;
        llOnlineUsers.removeAllViews();
        if (users.isEmpty()) { showNoOnline(); return; }
        if (tvNoOnline != null) tvNoOnline.setVisibility(View.GONE);
        LayoutInflater inf = LayoutInflater.from(getContext());
        for (User u : users) {
            View item = inf.inflate(R.layout.item_online_user, llOnlineUsers, false);
            CircleImageView iv = item.findViewById(R.id.iv_online_avatar);
            TextView tv = item.findViewById(R.id.tv_online_name);
            tv.setText(u.name != null ? u.name.split(" ")[0] : "User");
            if (u.photoUrl != null && !u.photoUrl.isEmpty())
                Glide.with(getContext()).load(u.photoUrl)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(iv);
            final User fu = u;
            item.setOnClickListener(x -> openChat(fu));
            llOnlineUsers.addView(item);
        }
    }

    private void showNoOnline() {
        if (llOnlineUsers != null) llOnlineUsers.removeAllViews();
        if (tvNoOnline    != null) tvNoOnline.setVisibility(View.VISIBLE);
    }

    private void openChat(User u) {
        if (u.uid == null || getContext() == null) return;
        Intent i = new Intent(getContext(), ChatActivity.class);
        i.putExtra("partnerUid",  u.uid);
        i.putExtra("partnerName", u.name != null ? u.name : "");
        startActivity(i);
    }

    private void loadCallLogs() {
        if (myUid == null) return;
        FirebaseUtils.getCallsRef(myUid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                logs.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    CallLog l = c.getValue(CallLog.class);
                    if (l != null) { if (l.id == null) l.id = c.getKey(); logs.add(l); }
                }
                Collections.sort(logs, (a, b) -> {
                    long ta = a.timestamp == null ? 0 : a.timestamp;
                    long tb = b.timestamp == null ? 0 : b.timestamp;
                    return Long.compare(tb, ta);
                });
                if (adapter != null) adapter.notifyDataSetChanged();
                if (emptyState != null)
                    emptyState.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }
}
