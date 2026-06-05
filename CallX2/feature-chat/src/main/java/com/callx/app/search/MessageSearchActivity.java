package com.callx.app.search;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MessageSearchActivity — Global + In-Chat Message Search.
 *
 * Modes:
 *   1. Global Search (chatId == null): Search across ALL chats in Room DB.
 *   2. In-Chat Search (chatId != null): Search within a specific conversation.
 *
 * Features:
 *   • Real-time search as user types (300ms debounce)
 *   • Highlights matched text in results
 *   • Shows sender name, chat name, timestamp
 *   • Tapping a result opens ChatActivity scrolled to that message
 *
 * Usage:
 *   // Global search
 *   startActivity(new Intent(ctx, MessageSearchActivity.class));
 *
 *   // In-chat search
 *   Intent i = new Intent(ctx, MessageSearchActivity.class);
 *   i.putExtra("chatId", chatId);
 *   i.putExtra("chatName", partnerName);
 *   startActivity(i);
 */
public class MessageSearchActivity extends AppCompatActivity {

    private static final int DEBOUNCE_MS = 300;
    private static final int MIN_QUERY_LENGTH = 2;

    private EditText etSearch;
    private RecyclerView rvResults;
    private TextView tvEmpty;
    private View progressBar;

    private MessageSearchAdapter adapter;
    private AppDatabase db;
    private ExecutorService executor;

    private String chatId;   // null = global search
    private String chatName;

    private final Runnable searchRunnable = this::performSearch;
    private android.os.Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_search);

        chatId   = getIntent().getStringExtra("chatId");
        chatName = getIntent().getStringExtra("chatName");

        db       = AppDatabase.getInstance(this);
        executor = Executors.newSingleThreadExecutor();
        handler  = new android.os.Handler(android.os.Looper.getMainLooper());

        setupViews();
        setupSearch();
    }

    private void setupViews() {
        etSearch    = findViewById(R.id.et_search);
        rvResults   = findViewById(R.id.rv_results);
        tvEmpty     = findViewById(R.id.tv_empty);
        progressBar = findViewById(R.id.progress_bar);

        ImageView ivBack = findViewById(R.id.iv_back);
        TextView  tvTitle = findViewById(R.id.tv_title);

        ivBack.setOnClickListener(v -> finish());
        tvTitle.setText(chatId != null ? "Search in " + chatName : "Search Messages");

        adapter = new MessageSearchAdapter(chatId != null ? chatId : null, this::onResultTapped);
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setAdapter(adapter);

        etSearch.requestFocus();
    }

    private void setupSearch() {
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                handler.removeCallbacks(searchRunnable);
                if (s.length() >= MIN_QUERY_LENGTH) {
                    handler.postDelayed(searchRunnable, DEBOUNCE_MS);
                } else {
                    adapter.submitList(new ArrayList<>());
                    tvEmpty.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void performSearch() {
        String query = etSearch.getText().toString().trim();
        if (query.length() < MIN_QUERY_LENGTH) return;

        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        rvResults.setVisibility(View.GONE);

        String likeQuery = "%" + query + "%";

        executor.execute(() -> {
            List<MessageEntity> results;
            if (chatId != null) {
                results = db.messageDao().searchInChat(chatId, likeQuery);
            } else {
                results = db.messageDao().searchGlobal(likeQuery);
            }

            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (results.isEmpty()) {
                    tvEmpty.setText("No messages found for \"" + query + "\"");
                    tvEmpty.setVisibility(View.VISIBLE);
                    rvResults.setVisibility(View.GONE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                    rvResults.setVisibility(View.VISIBLE);
                    adapter.setQuery(query);
                    adapter.submitList(results);
                }
            });
        });
    }

    private void onResultTapped(MessageEntity msg) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("chatId",    msg.chatId);
        intent.putExtra("scrollToMessageId", msg.id);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(searchRunnable);
        executor.shutdown();
    }
}
