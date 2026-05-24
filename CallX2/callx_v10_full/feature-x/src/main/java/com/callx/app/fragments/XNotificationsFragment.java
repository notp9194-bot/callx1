package com.callx.app.fragments;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
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

/**
 * XNotificationsFragment — tabs: All | Mentions | Likes | Reposts | Follows
 */
public class XNotificationsFragment extends Fragment {

    private static final String[] TABS  = {"All", "Mentions", "Likes", "Reposts", "Follows"};
    private static final String[] TYPES = {null, "mention", "like", "retweet", "follow"};

    private RecyclerView         recyclerView;
    private SwipeRefreshLayout   swipeRefresh;
    private LinearLayout         llEmpty;
    private LinearLayout         llTabs;
    private XNotificationAdapter adapter;
    private ValueEventListener   notifListener;
    private String myUid;
    private int activeTab = 0;
    private List<XNotification> allNotifications = new ArrayList<>();

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
        llTabs       = view.findViewById(R.id.ll_xn_tabs);

        adapter = new XNotificationAdapter(requireContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        swipeRefresh.setColorSchemeColors(requireContext().getColor(R.color.x_accent));
        swipeRefresh.setOnRefreshListener(this::load);

        buildTabs(view);
        load();
    }

    // ── Tabs ─────────────────────────────────────────────────────────────────

    private void buildTabs(View root) {
        if (llTabs == null) return;
        llTabs.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView tv = new TextView(requireContext());
            tv.setText(TABS[i]);
            tv.setPadding(32, 20, 32, 20);
            tv.setTextSize(14f);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setSelected(i == activeTab);
            tv.setTextColor(requireContext().getColorStateList(R.color.x_tab_text_selector));
            tv.setOnClickListener(v -> {
                activeTab = idx;
                updateTabSelection();
                applyFilter();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            tv.setGravity(android.view.Gravity.CENTER);
            llTabs.addView(tv);
        }
    }

    private void updateTabSelection() {
        if (llTabs == null) return;
        for (int i = 0; i < llTabs.getChildCount(); i++) {
            llTabs.getChildAt(i).setSelected(i == activeTab);
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void load() {
        if (isDetached() || myUid.isEmpty()) return;
        swipeRefresh.setRefreshing(true);

        if (notifListener != null)
            XFirebaseUtils.xNotificationsRef(myUid).removeEventListener(notifListener);

        notifListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                allNotifications.clear();
                for (DataSnapshot ds : snap.getChildren()) {
                    XNotification n = ds.getValue(XNotification.class);
                    if (n != null) { n.id = ds.getKey(); allNotifications.add(n); }
                }
                allNotifications.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

                // Mark all read
                for (DataSnapshot ds : snap.getChildren()) {
                    if (!Boolean.TRUE.equals(ds.child("read").getValue(Boolean.class)))
                        ds.getRef().child("read").setValue(true);
                }
                XFirebaseUtils.xUnreadNotifCountRef(myUid).setValue(0);

                swipeRefresh.setRefreshing(false);
                applyFilter();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (isAdded()) swipeRefresh.setRefreshing(false);
            }
        };

        XFirebaseUtils.xNotificationsRef(myUid)
            .orderByChild("timestamp")
            .limitToLast(100)
            .addValueEventListener(notifListener);
    }

    private void applyFilter() {
        if (!isAdded()) return;
        String filterType = TYPES[activeTab]; // null = all
        List<XNotification> filtered = new ArrayList<>();
        for (XNotification n : allNotifications) {
            if (filterType == null || filterType.equals(n.type)) filtered.add(n);
        }
        adapter.setItems(filtered);
        if (llEmpty != null)
            llEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (notifListener != null)
            XFirebaseUtils.xNotificationsRef(myUid).removeEventListener(notifListener);
    }
}
