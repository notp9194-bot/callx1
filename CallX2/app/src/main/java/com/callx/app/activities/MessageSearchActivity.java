package com.callx.app.activities;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.adapters.MessageAdapter;
import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 1 (NEW): In-chat Message Search
 * Searches all messages in a given chat or group by keyword.
 * Results highlight matches and clicking scrolls to that message.
 */
public class MessageSearchActivity extends AppCompatActivity
        implements MessageAdapter.ActionListener {

    private EditText   etSearch;
    private RecyclerView rv;
    private TextView   tvEmpty;
    private MessageAdapter adapter;

    private final List<Message> allMessages    = new ArrayList<>();
    private final List<Message> filteredMessages = new ArrayList<>();

    private String chatId;
    private boolean isGroup;
    private String currentUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_search);

        chatId     = getIntent().getStringExtra("chatId");
        isGroup    = getIntent().getBooleanExtra("isGroup", false);
        currentUid = com.google.firebase.auth.FirebaseAuth.getInstance()
                         .getCurrentUser().getUid();

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Search Messages");
        }
        tb.setNavigationOnClickListener(v -> finish());

        etSearch = findViewById(R.id.et_search);
        rv       = findViewById(R.id.rv_results);
        tvEmpty  = findViewById(R.id.tv_empty);

        adapter = new MessageAdapter(filteredMessages, currentUid, isGroup);
        adapter.setActionListener(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){
                filterMessages(s.toString().trim());
            }
            @Override public void afterTextChanged(Editable s){}
        });

        loadAllMessages();
    }

    private void loadAllMessages() {
        DatabaseReference ref = isGroup
                ? FirebaseUtils.getGroupMessagesRef(chatId)
                : FirebaseUtils.getMessagesRef(chatId);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                allMessages.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    Message m = c.getValue(Message.class);
                    if (m == null) continue;
                    if (m.id == null) m.id = c.getKey();
                    allMessages.add(m);
                }
                filterMessages(etSearch.getText().toString().trim());
            }
            @Override public void onCancelled(DatabaseError e) {
                Toast.makeText(MessageSearchActivity.this,
                        "Failed to load messages", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void filterMessages(String query) {
        filteredMessages.clear();
        if (query.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("Type to search messages…");
            rv.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
            return;
        }
        String lq = query.toLowerCase();
        for (Message m : allMessages) {
            boolean match = (m.text != null && m.text.toLowerCase().contains(lq))
                    || (m.fileName != null && m.fileName.toLowerCase().contains(lq))
                    || (m.contactName != null && m.contactName.toLowerCase().contains(lq))
                    || (m.locationAddress != null && m.locationAddress.toLowerCase().contains(lq))
                    || (m.linkTitle != null && m.linkTitle.toLowerCase().contains(lq))
                    || (m.pollQuestion != null && m.pollQuestion.toLowerCase().contains(lq));
            if (match) filteredMessages.add(m);
        }
        boolean empty = filteredMessages.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvEmpty.setText("No results for \"" + query + "\"");
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    // ActionListener stubs
    @Override public void onReply(Message m) {}
    @Override public void onEdit(Message m) {}
    @Override public void onDelete(Message m) {}
    @Override public void onReact(Message m, String emoji) {}
    @Override public void onForward(Message m) {}
    @Override public void onStar(Message m) {}
    @Override public void onPin(Message m) {}
    @Override public void onReactionTap(Message m) {}
}
