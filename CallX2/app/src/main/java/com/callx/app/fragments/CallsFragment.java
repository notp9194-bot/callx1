package com.callx.app.fragments;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.adapters.CallHistoryAdapter;
import com.callx.app.models.CallLog;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
public class CallsFragment extends Fragment {
    private final List<CallLog> logs = new ArrayList<>();
    private CallHistoryAdapter adapter;
    private View emptyState;
    private CircleImageView ivMyAvatar;
    private TextView tvMyName;
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_calls, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_calls);
        emptyState = v.findViewById(R.id.empty_calls);
        ivMyAvatar = v.findViewById(R.id.iv_my_avatar);
        tvMyName = v.findViewById(R.id.tv_my_name);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CallHistoryAdapter(logs);
        rv.setAdapter(adapter);
        loadMyProfile();
        load();
        return v;
    }
    private void loadMyProfile() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        FirebaseUtils.getUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (getContext() == null) return;
                    String name = snap.child("name").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    if (name != null && !name.isEmpty()) {
                        tvMyName.setText(name);
                    }
                    if (photo != null && !photo.isEmpty()) {
                        Glide.with(getContext())
                            .load(photo)
                            .apply(RequestOptions.circleCropTransform())
                            .placeholder(R.drawable.ic_person)
                            .error(R.drawable.ic_person)
                            .into(ivMyAvatar);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private void load() {
        String uid = FirebaseUtils.getCurrentUid();
        FirebaseUtils.getCallsRef(uid)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    logs.clear();
                    for (DataSnapshot c : snap.getChildren()) {
                        CallLog l = c.getValue(CallLog.class);
                        if (l != null) {
                            if (l.id == null) l.id = c.getKey();
                            logs.add(l);
                        }
                    }
                    Collections.sort(logs, new Comparator<CallLog>() {
                        @Override public int compare(CallLog a, CallLog b) {
                            long ta = a.timestamp == null ? 0 : a.timestamp;
                            long tb = b.timestamp == null ? 0 : b.timestamp;
                            return Long.compare(tb, ta);
                        }
                    });
                    adapter.notifyDataSetChanged();
                    emptyState.setVisibility(logs.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
}
