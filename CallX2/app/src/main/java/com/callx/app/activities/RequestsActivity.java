package com.callx.app.activities;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.adapters.RequestAdapter;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;
public class RequestsActivity extends AppCompatActivity {
    private final List<User> requests = new ArrayList<>();
    private RequestAdapter adapter;
    private TextView empty;
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_requests);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        RecyclerView rv = findViewById(R.id.rv_requests);
        empty = findViewById(R.id.empty_requests);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RequestAdapter(requests);
        rv.setAdapter(adapter);
        load();
    }
    private void load() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) { finish(); return; }
        FirebaseUtils.getRequestsRef(uid).addValueEventListener(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                requests.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    User u = new User();
                    u.uid   = c.getKey();
                    u.name  = c.child("name").getValue(String.class);
                    if (u.name == null)
                        u.name = c.child("fromName").getValue(String.class);
                    u.about = c.child("about").getValue(String.class);
                    u.photoUrl = c.child("photoUrl").getValue(String.class);
                    if (u.name == null || u.name.isEmpty()) u.name = "User";
                    requests.add(u);
                }
                adapter.notifyDataSetChanged();
                empty.setVisibility(requests.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }
}
