package com.callx.app.fragments;

import android.os.Bundle;
import android.view.*;
import android.widget.LinearLayout;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.callx.app.adapters.XNotificationAdapter;
import com.callx.app.models.XNotification;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

public class XNotificationsFragment extends Fragment {

    private RecyclerView         recyclerView;
    private SwipeRefreshLayout   swipeRefresh;
    private LinearLayout         llEmpty;
    private XNotificationAdapter adapter;
    private ValueEventListener   notifListener;
    private String myUid;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_x_notifications, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        recyclerView = view.findViewById(R.id.rv_x_notifications);
        swipeRefresh = view.findViewById(R.id.swipe_x_notifications);
        llEmpty      = view.findViewById(R.id.ll_xn_empty);

        adapter = new XNotificationAdapter(requireContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        swipeRefresh.setColorSchemeColors(requireContext().getColor(R.color.x_accent));
        swipeRefresh.setOnRefreshListener(this::load);
        load();
    }

    private void load() {
        if (isDetached() || myUid.isEmpty()) return;
        swipeRefresh.setRefreshing(true);

        if (notifListener != null)
            XFirebaseUtils.xNotificationsRef(myUid).removeEventListener(notifListener);

        notifListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;

                List<XNotification> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XNotification n = ds.getValue(XNotification.class);
                    if (n != null) { n.id = ds.getKey(); list.add(n); }
                }
                list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

                adapter.setItems(list);
                swipeRefresh.setRefreshing(false);

                // Show / hide empty state
                if (llEmpty != null)
                    llEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);

                // Mark all as read + reset badge counter
                for (DataSnapshot ds : snap.getChildren()) {
                    Boolean read = ds.child("read").getValue(Boolean.class);
                    if (!Boolean.TRUE.equals(read))
                        ds.getRef().child("read").setValue(true);
                }
                XFirebaseUtils.xUnreadNotifCountRef(myUid).setValue(0);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (isAdded()) swipeRefresh.setRefreshing(false);
            }
        };

        XFirebaseUtils.xNotificationsRef(myUid)
            .orderByChild("timestamp")
            .limitToLast(50)
            .addValueEventListener(notifListener);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (notifListener != null)
            XFirebaseUtils.xNotificationsRef(myUid).removeEventListener(notifListener);
    }
}
