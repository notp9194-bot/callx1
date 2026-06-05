package com.callx.app.broadcast;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.View;
  import android.widget.Toast;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.chat.R;
  import com.google.android.material.floatingactionbutton.FloatingActionButton;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.FirebaseDatabase;
  import com.google.firebase.database.ValueEventListener;
  import java.util.ArrayList;
  import java.util.List;

  /**
   * BroadcastListActivity — View and manage broadcast lists.
   * A broadcast sends one message to multiple users as individual 1:1 chats.
   *
   * Firebase path: broadcasts/{myUid}/{broadcastId}
   *   name: "My List"
   *   recipients: {uid1: true, uid2: true}
   */
  public class BroadcastListActivity extends AppCompatActivity {

      private RecyclerView rvBroadcasts;
      private BroadcastListAdapter adapter;
      private List<BroadcastList> broadcasts = new ArrayList<>();
      private String myUid;

      @Override protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_broadcast_list);
          myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

          rvBroadcasts = findViewById(R.id.rv_broadcasts);
          rvBroadcasts.setLayoutManager(new LinearLayoutManager(this));
          adapter = new BroadcastListAdapter(broadcasts, this::onBroadcastClick);
          rvBroadcasts.setAdapter(adapter);

          FloatingActionButton fab = findViewById(R.id.fab_new_broadcast);
          fab.setOnClickListener(v ->
              startActivity(new Intent(this, NewBroadcastActivity.class)));

          loadBroadcasts();
      }

      private void loadBroadcasts() {
          FirebaseDatabase.getInstance()
              .getReference("broadcasts").child(myUid)
              .addValueEventListener(new ValueEventListener() {
                  @Override public void onDataChange(DataSnapshot snapshot) {
                      broadcasts.clear();
                      for (DataSnapshot child : snapshot.getChildren()) {
                          BroadcastList bl = child.getValue(BroadcastList.class);
                          if (bl != null) { bl.id = child.getKey(); broadcasts.add(bl); }
                      }
                      adapter.notifyDataSetChanged();
                  }
                  @Override public void onCancelled(DatabaseError e) {}
              });
      }

      private void onBroadcastClick(BroadcastList bl) {
          Intent i = new Intent(this, SendBroadcastActivity.class);
          i.putExtra("broadcastId", bl.id);
          i.putExtra("broadcastName", bl.name);
          startActivity(i);
      }
  }