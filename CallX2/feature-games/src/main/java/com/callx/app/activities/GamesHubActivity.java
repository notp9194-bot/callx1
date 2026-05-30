package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.games.R;

import java.util.ArrayList;
import java.util.List;

/**
 * GamesHubActivity
 *
 * Games ki list dikhata hai. Abhi sirf Bubble Pop hai,
 * baad mein aur games add kar sakte hain.
 *
 * Kisi bhi module se open karo via Class.forName reflection:
 *
 *   Intent i = new Intent();
 *   i.setClassName(context.getPackageName(), "com.callx.app.activities.GamesHubActivity");
 *   context.startActivity(i);
 *
 * Ya seedha GameActivity:
 *   Intent i = new Intent();
 *   i.setClassName(context.getPackageName(), "com.callx.app.activities.GameActivity");
 *   i.putExtra("url",   "https://callx-server.onrender.com/bubble-pop-game.html");
 *   i.putExtra("title", "Bubble Pop");
 *   context.startActivity(i);
 */
public class GamesHubActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_games_hub);

        ImageButton btnBack = findViewById(R.id.btn_games_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_games);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new GamesAdapter(buildGameList()));
    }

    // ── Game model ────────────────────────────────────────────────────────────
    public static class GameItem {
        public String emoji;
        public String name;
        public String description;
        public String url;

        public GameItem(String emoji, String name, String description, String url) {
            this.emoji       = emoji;
            this.name        = name;
            this.description = description;
            this.url         = url;
        }
    }

    // ── Games list — yahan aur games add karo ────────────────────────────────
    private List<GameItem> buildGameList() {
        List<GameItem> list = new ArrayList<>();
        list.add(new GameItem(
            "🫧",
            "Bubble Pop",
            "Colorful bubbles phodo aur high score banao! 🎯",
            "https://callx-server.onrender.com/bubble-pop-game.html"
        ));
        // Yahan future games add karo:
        // list.add(new GameItem("🐍", "Snake", "Classic snake game!", "https://..."));
        return list;
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    private class GamesAdapter extends RecyclerView.Adapter<GamesAdapter.VH> {

        private final List<GameItem> items;

        GamesAdapter(List<GameItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_game_card, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            GameItem item = items.get(position);
            h.tvEmoji.setText(item.emoji);
            h.tvName.setText(item.name);
            h.tvDesc.setText(item.description);

            View.OnClickListener openGame = v -> {
                Intent i = new Intent(GamesHubActivity.this, GameActivity.class);
                i.putExtra(GameActivity.EXTRA_URL,   item.url);
                i.putExtra(GameActivity.EXTRA_TITLE, item.name);
                startActivity(i);
            };

            h.itemView.setOnClickListener(openGame);
            h.btnPlay.setOnClickListener(openGame);
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvEmoji, tvName, tvDesc;
            View     btnPlay;

            VH(@NonNull View v) {
                super(v);
                tvEmoji = v.findViewById(R.id.tv_game_emoji);
                tvName  = v.findViewById(R.id.tv_game_name);
                tvDesc  = v.findViewById(R.id.tv_game_desc);
                btnPlay = v.findViewById(R.id.btn_game_play);
            }
        }
    }
}
