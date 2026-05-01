package com.callx.app.fragments;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.activities.NewStatusActivity;
import com.callx.app.adapters.StatusListAdapter;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public class StatusFragment extends Fragment {
    private final List<StatusItem> statuses = new ArrayList<>();
    private StatusListAdapter adapter;
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_status, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_status);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new StatusListAdapter(statuses);
        rv.setAdapter(adapter);
        FloatingActionButton fab = v.findViewById(R.id.fab_new_status);
        fab.setOnClickListener(x ->
            startActivity(new Intent(getContext(), NewStatusActivity.class)));
        load();
        return v;
    }
    private void load() {
        FirebaseUtils.getStatusRef().addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                statuses.clear();
                long now = System.currentTimeMillis();
                // Group by ownerUid - keep latest per user
                Map<String, StatusItem> latestByUser = new HashMap<>();
                for (DataSnapshot user : snap.getChildren()) {
                    StatusItem latest = null;
                    for (DataSnapshot st : user.getChildren()) {
                        StatusItem item = st.getValue(StatusItem.class);
                        if (item == null) continue;
                        if (item.expiresAt != null && item.expiresAt < now) continue;
                        if (latest == null ||
                            (item.timestamp != null && latest.timestamp != null
                                && item.timestamp > latest.timestamp)) {
                            latest = item;
                        }
                    }
                    if (latest != null) latestByUser.put(user.getKey(), latest);
                }
                statuses.addAll(latestByUser.values());
                adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }
}
