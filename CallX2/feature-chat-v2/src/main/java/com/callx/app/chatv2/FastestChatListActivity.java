package com.callx.app.chatv2;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.ChatEntity;

/**
 * Entry point for the "⚡ Fastest Chat" overflow-menu item (Chats tab
 * 3-dot menu, MainActivity). Lists existing 1:1 chats (same Room source
 * as the normal Chats tab) — tapping one opens FastChatActivity, the
 * native C++/OpenGL-rendered conversation screen. Purely additive: does
 * not read from or modify ChatListAdapter/ChatsFragment/ChatActivity.
 */
public class FastestChatListActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private final List<ChatEntity> chats = new ArrayList<>();
    private Adapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("⚡ Fastest Chat");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        recyclerView.setAdapter(adapter);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(toolbar, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(recyclerView, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        AppDatabase.getInstance(getApplicationContext()).chatDao().getAllChats()
                .observe(this, list -> {
                    chats.clear();
                    if (list != null) {
                        for (ChatEntity c : list) {
                            if ("private".equals(c.type) && c.partnerUid != null) {
                                chats.add(c);
                            }
                        }
                    }
                    adapter.notifyDataSetChanged();
                });
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            TextView name;
            TextView last;
            VH(View v) {
                super(v);
                name = v.findViewById(android.R.id.text1);
                last = v.findViewById(android.R.id.text2);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_2, parent, false);
            int pad = (int) (16 * getResources().getDisplayMetrics().density);
            v.setPadding(pad, pad, pad, pad);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ChatEntity chat = chats.get(position);
            holder.name.setText(chat.partnerName != null ? chat.partnerName : "Unknown");
            holder.last.setText(chat.lastMessage != null ? chat.lastMessage : "");
            holder.itemView.setOnClickListener(v -> {
                Intent i = new Intent(FastestChatListActivity.this, FastChatActivity.class);
                i.putExtra(FastChatActivity.EXTRA_PARTNER_UID, chat.partnerUid);
                i.putExtra(FastChatActivity.EXTRA_PARTNER_NAME, chat.partnerName);
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return chats.size();
        }
    }
}
