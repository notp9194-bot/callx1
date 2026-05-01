package com.callx.app.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class StarredMessagesActivity extends AppCompatActivity {

    private RecyclerView rv;
    private final List<Message> starred = new ArrayList<>();
    private StarAdapter adapter;
    private String chatId;
    private boolean isGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_starred_messages);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("⭐ Starred Messages");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        chatId  = getIntent().getStringExtra("chatId");
        isGroup = getIntent().getBooleanExtra("isGroup", false);

        rv = findViewById(R.id.rv_starred);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StarAdapter(starred);
        rv.setAdapter(adapter);

        loadStarred();
    }

    private void loadStarred() {
        DatabaseReference ref = isGroup
                ? FirebaseUtils.getGroupMessagesRef(chatId)
                : FirebaseUtils.getMessagesRef(chatId);

        ref.orderByChild("starred").equalTo(true)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        starred.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            Message m = c.getValue(Message.class);
                            if (m != null) {
                                if (m.id == null) m.id = c.getKey();
                                starred.add(m);
                            }
                        }
                        adapter.notifyDataSetChanged();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    static class StarAdapter extends RecyclerView.Adapter<StarAdapter.VH> {
        private final List<Message> items;
        private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());

        StarAdapter(List<Message> items) { this.items = items; }

        @Override public VH onCreateViewHolder(ViewGroup parent, int vt) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_2, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            Message m = items.get(pos);
            String who = m.senderName != null ? m.senderName : "Unknown";
            String body = m.text != null && !m.text.isEmpty() ? m.text
                    : (m.type != null ? "[" + m.type + "]" : "[Media]");
            h.text1.setText(who + ": " + body);
            h.text2.setText(m.timestamp != null ? fmt.format(new Date(m.timestamp)) : "");
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView text1, text2;
            VH(View v) {
                super(v);
                text1 = v.findViewById(android.R.id.text1);
                text2 = v.findViewById(android.R.id.text2);
            }
        }
    }
}
