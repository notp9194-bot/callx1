package com.callx.app.messages;

  import android.content.Intent;
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
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.ArrayList;
  import java.util.Collections;
  import java.util.List;
import com.callx.app.search.XSearchActivity;

  public class XMessagesFragment extends Fragment {

      private RecyclerView recyclerView;
      private SwipeRefreshLayout swipeRefresh;
      private XMessagePreviewAdapter adapter;
      private ValueEventListener convListener;
      private String myUid;

      @Nullable @Override
      public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                               @Nullable Bundle savedInstanceState) {
          return inflater.inflate(R.layout.fragment_x_messages, container, false);
      }

      @Override
      public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
          super.onViewCreated(view, savedInstanceState);
          myUid = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

          recyclerView = view.findViewById(R.id.rv_x_messages);
          swipeRefresh = view.findViewById(R.id.swipe_x_messages);

          adapter = new XMessagePreviewAdapter(requireContext());
          recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
          recyclerView.setAdapter(adapter);

          view.findViewById(R.id.btn_x_new_dm).setOnClickListener(v ->
              startActivity(new Intent(requireContext(), XSearchActivity.class)
                  .putExtra("dm_mode", true)));

          swipeRefresh.setOnRefreshListener(this::load);
          load();
      }

      private void load() {
          if (myUid.isEmpty()) return;
          swipeRefresh.setRefreshing(true);
          if (convListener != null)
              XFirebaseUtils.xDmConversationsRef(myUid).removeEventListener(convListener);

          convListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  List<XMessagePreviewAdapter.ConversationPreview> list = new ArrayList<>();
                  for (DataSnapshot ds : snap.getChildren()) {
                      XMessagePreviewAdapter.ConversationPreview p
                          = new XMessagePreviewAdapter.ConversationPreview();
                      p.conversationId = ds.getKey();
                      p.otherUid       = ds.child("otherUid").getValue(String.class);
                      p.otherName      = ds.child("otherName").getValue(String.class);
                      p.otherHandle    = ds.child("otherHandle").getValue(String.class);
                      p.otherPhotoUrl  = ds.child("otherPhoto").getValue(String.class);
                      p.otherThumbUrl  = ds.child("otherThumb").getValue(String.class);
                      p.lastMessage    = ds.child("lastMessage").getValue(String.class);
                      Long ts = ds.child("lastMessageTs").getValue(Long.class);
                      p.lastTs         = ts != null ? ts : 0;
                      Boolean seen     = ds.child("seen").getValue(Boolean.class);
                      p.unread         = !Boolean.TRUE.equals(seen);
                      list.add(p);
                  }
                  Collections.sort(list, (a, b) -> Long.compare(b.lastTs, a.lastTs));
                  adapter.setItems(list);
                  swipeRefresh.setRefreshing(false);
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  if (isAdded()) swipeRefresh.setRefreshing(false);
              }
          };
          XFirebaseUtils.xDmConversationsRef(myUid)
              .orderByChild("lastMessageTs")
              .limitToLast(30)
              .addValueEventListener(convListener);
      }

      @Override public void onDestroyView() {
          super.onDestroyView();
          if (convListener != null)
              XFirebaseUtils.xDmConversationsRef(myUid).removeEventListener(convListener);
      }
  }