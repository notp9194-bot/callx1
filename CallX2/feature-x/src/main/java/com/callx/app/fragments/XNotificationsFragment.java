package com.callx.app.fragments;

  import android.os.Bundle;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import androidx.annotation.NonNull;
  import androidx.annotation.Nullable;
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
  import java.util.ArrayList;
  import java.util.Collections;
  import java.util.List;

  public class XNotificationsFragment extends Fragment {

      private RecyclerView recyclerView;
      private SwipeRefreshLayout swipeRefresh;
      private XNotificationAdapter adapter;
      private ValueEventListener notifListener;
      private String myUid;

      @Nullable @Override
      public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
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

          adapter = new XNotificationAdapter(requireContext());
          recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
          recyclerView.setAdapter(adapter);

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
                  Collections.sort(list, (a, b) -> Long.compare(b.timestamp, a.timestamp));
                  adapter.setItems(list);
                  swipeRefresh.setRefreshing(false);
                  // Mark all as read
                  for (DataSnapshot ds : snap.getChildren()) {
                      ds.getRef().child("read").setValue(true);
                  }
                  // Reset unread count
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