package com.callx.app.fragments;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.activities.NewGroupActivity;
import com.callx.app.adapters.GroupAdapter;
import com.callx.app.models.Group;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;
public class GroupsFragment extends Fragment {
    private final List<Group> groups = new ArrayList<>();
    private GroupAdapter adapter;
    private View emptyState;
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_groups, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_groups);
        emptyState = v.findViewById(R.id.empty_groups);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new GroupAdapter(groups);
        rv.setAdapter(adapter);
        FloatingActionButton fab = v.findViewById(R.id.fab_new_group);
        fab.setOnClickListener(x ->
            startActivity(new Intent(getContext(), NewGroupActivity.class)));
        load();
        return v;
    }
    private void load() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return;
        FirebaseUtils.getUserGroupsRef(uid)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    groups.clear();
                    if (!snap.hasChildren()) {
                        adapter.notifyDataSetChanged();
                        emptyState.setVisibility(View.VISIBLE);
                        return;
                    }
                    emptyState.setVisibility(View.GONE);
                    final int[] pending = {(int) snap.getChildrenCount()};
                    for (DataSnapshot g : snap.getChildren()) {
                        String gid = g.getKey();
                        FirebaseUtils.getGroupsRef().child(gid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(DataSnapshot ds) {
                                    Group gr = ds.getValue(Group.class);
                                    if (gr != null) {
                                        if (gr.id == null) gr.id = ds.getKey();
                                        groups.add(gr);
                                    }
                                    if (--pending[0] == 0) adapter.notifyDataSetChanged();
                                }
                                @Override public void onCancelled(DatabaseError e) {
                                    if (--pending[0] == 0) adapter.notifyDataSetChanged();
                                }
                            });
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
}
