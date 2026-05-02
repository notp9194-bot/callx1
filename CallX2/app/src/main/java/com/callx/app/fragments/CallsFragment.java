package com.callx.app.fragments;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.adapters.CallHistoryAdapter;
import com.callx.app.models.CallLog;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
public class CallsFragment extends Fragment {
    private final List<CallLog> logs = new ArrayList<>();
    private CallHistoryAdapter adapter;
    private View emptyState;
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_calls, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_calls);
        emptyState = v.findViewById(R.id.empty_calls);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CallHistoryAdapter(logs);
        rv.setAdapter(adapter);
        load();
        return v;
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
