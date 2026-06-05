package com.callx.app.chat.search;

  import android.os.Bundle;
  import android.text.Editable;
  import android.text.TextWatcher;
  import android.view.View;
  import android.widget.EditText;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.chat.R;
  import com.callx.app.db.AppDatabase;
  import com.callx.app.db.entity.MessageEntity;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.concurrent.Executors;

  /**
   * ChatSearchActivity — Search messages within a specific chat.
   *
   * Launched from ChatActivity toolbar search icon.
   * Intent extras: chatId, partnerName
   *
   * Queries Room DB locally (instant, no Firebase round-trip).
   * Highlights matching text in results.
   */
  public class ChatSearchActivity extends AppCompatActivity {

      private String chatId;
      private ChatSearchAdapter adapter;
      private List<MessageEntity> results = new ArrayList<>();
      private AppDatabase db;

      @Override protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_chat_search);
          chatId = getIntent().getStringExtra("chatId");
          db     = AppDatabase.getInstance(this);

          RecyclerView rv = findViewById(R.id.rv_results);
          rv.setLayoutManager(new LinearLayoutManager(this));
          adapter = new ChatSearchAdapter(results);
          rv.setAdapter(adapter);

          EditText etSearch = findViewById(R.id.et_search);
          etSearch.addTextChangedListener(new TextWatcher() {
              @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}
              @Override public void onTextChanged(CharSequence s,int st,int b,int c){}
              @Override public void afterTextChanged(Editable s) { search(s.toString()); }
          });

          findViewById(R.id.btn_back).setOnClickListener(v -> finish());
      }

      private void search(String query) {
          if (query.length() < 2) { results.clear(); adapter.notifyDataSetChanged(); return; }
          Executors.newSingleThreadExecutor().execute(() -> {
              List<MessageEntity> found = db.messageDao().searchMessages(chatId, "%" + query + "%");
              runOnUiThread(() -> {
                  results.clear();
                  results.addAll(found);
                  adapter.setQuery(query);
                  adapter.notifyDataSetChanged();
              });
          });
      }
  }