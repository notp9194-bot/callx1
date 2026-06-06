package com.callx.app.chat.gif;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.chat.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GifPickerActivity — Full GIF search & browse using GIPHY API.
 *
 * Returns via setResult(RESULT_OK, intent) with extras:
 *   "gif_url"         — full GIF URL (send in message)
 *   "gif_preview_url" — downsized preview URL (thumbnail)
 *   "gif_title"       — title/caption of the GIF
 *
 * Usage in ChatActivity:
 *   startActivityForResult(new Intent(this, GifPickerActivity.class), REQ_GIF);
 *   onActivityResult → if resultCode==RESULT_OK, read "gif_url" extra.
 */
public class GifPickerActivity extends AppCompatActivity {

    private static final String GIPHY_BASE    = "https://api.giphy.com/v1/gifs";
    private static final String GIPHY_API_KEY = "PolH67XHOhQnLy2rZcMMz5wSjd5ynraL";
    private static final int    GRID_COLUMNS  = 2;
    private static final int    FETCH_LIMIT   = 24;

    private EditText     etSearch;
    private RecyclerView rvGifs;
    private ProgressBar  pbLoading;
    private TextView     tvEmpty;
    private GifAdapter   adapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        android.widget.Toast.makeText(this,
            "DEBUG GifPicker: onCreate START", android.widget.Toast.LENGTH_SHORT).show();
        super.onCreate(savedInstanceState);

        try {
            android.widget.Toast.makeText(this,
                "DEBUG GifPicker: setContentView calling...", android.widget.Toast.LENGTH_SHORT).show();
            setContentView(R.layout.activity_gif_picker);
            android.widget.Toast.makeText(this,
                "DEBUG GifPicker: setContentView OK", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                "DEBUG GifPicker CRASH in setContentView: " + e.getClass().getSimpleName()
                + " — " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            android.util.Log.e("GifDebug", "setContentView failed", e);
            finish();
            return;
        }

        try {
            etSearch  = findViewById(R.id.et_gif_search);
            rvGifs    = findViewById(R.id.rv_gifs);
            pbLoading = findViewById(R.id.pb_gif_loading);
            tvEmpty   = findViewById(R.id.tv_gif_empty);

            android.widget.Toast.makeText(this,
                "DEBUG GifPicker: views found — etSearch=" + (etSearch != null)
                + " rvGifs=" + (rvGifs != null), android.widget.Toast.LENGTH_SHORT).show();

            if (etSearch == null || rvGifs == null) {
                android.widget.Toast.makeText(this,
                    "DEBUG GifPicker ERROR: required view is NULL — check layout IDs",
                    android.widget.Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            ImageButton btnBack = findViewById(R.id.btn_gif_back);
            if (btnBack != null) btnBack.setOnClickListener(v -> finish());

            adapter = new GifAdapter(this::onGifSelected);
            rvGifs.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
            rvGifs.setAdapter(adapter);

            etSearch.addTextChangedListener(new TextWatcher() {
                private final Runnable searchRunnable = () -> {
                    String q = etSearch.getText().toString().trim();
                    if (q.length() >= 2) fetchSearch(q);
                    else if (q.isEmpty()) fetchTrending();
                };
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    mainHandler.removeCallbacksAndMessages(null);
                    mainHandler.postDelayed(searchRunnable, 350);
                }
            });

            android.widget.Toast.makeText(this,
                "DEBUG GifPicker: setup complete, fetching trending GIFs...",
                android.widget.Toast.LENGTH_SHORT).show();
            fetchTrending();

        } catch (Exception e) {
            android.widget.Toast.makeText(this,
                "DEBUG GifPicker CRASH in setup: " + e.getClass().getSimpleName()
                + " — " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            android.util.Log.e("GifDebug", "GifPickerActivity setup crash", e);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    // ── Fetch ─────────────────────────────────────────────────────────────

    private void fetchTrending() {
        String url = GIPHY_BASE + "/trending"
                + "?api_key=" + GIPHY_API_KEY
                + "&limit=" + FETCH_LIMIT
                + "&rating=g";
        fetch(url);
    }

    private void fetchSearch(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            String url = GIPHY_BASE + "/search"
                    + "?api_key=" + GIPHY_API_KEY
                    + "&q=" + encoded
                    + "&limit=" + FETCH_LIMIT
                    + "&rating=g";
            fetch(url);
        } catch (Exception ignored) {}
    }

    private void fetch(String urlStr) {
        showLoading(true);
        executor.submit(() -> {
            List<GifItem> items = new ArrayList<>();
            try {
                HttpURLConnection conn =
                        (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("Accept", "application/json");

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }

                JSONObject root = new JSONObject(sb.toString());
                JSONArray data  = root.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject gif = data.optJSONObject(i);
                        if (gif == null) continue;
                        GifItem item = parseGifItem(gif);
                        if (item != null) items.add(item);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("GifPicker", "fetch error: " + e.getMessage());
            }
            final List<GifItem> result = items;
            mainHandler.post(() -> {
                showLoading(false);
                adapter.setItems(result);
                if (tvEmpty != null) {
                    tvEmpty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        });
    }

    private GifItem parseGifItem(JSONObject gif) {
        try {
            String id    = gif.optString("id");
            String title = gif.optString("title", "");
            JSONObject images = gif.optJSONObject("images");
            if (images == null) return null;

            // Full GIF — original or downsized_large
            JSONObject original    = images.optJSONObject("original");
            JSONObject downsized   = images.optJSONObject("downsized");
            JSONObject fixedHeight = images.optJSONObject("fixed_height");
            JSONObject smallGif    = images.optJSONObject("fixed_height_downsampled");

            String fullUrl    = null;
            String previewUrl = null;

            if (fixedHeight != null) fullUrl = fixedHeight.optString("url");
            if (fullUrl == null && original != null) fullUrl = original.optString("url");
            if (fullUrl == null && downsized != null) fullUrl = downsized.optString("url");

            if (smallGif != null) previewUrl = smallGif.optString("url");
            if (previewUrl == null) previewUrl = fullUrl;

            if (fullUrl == null || fullUrl.isEmpty()) return null;

            GifItem item = new GifItem();
            item.id         = id;
            item.title      = title;
            item.url        = fullUrl;
            item.previewUrl = previewUrl;

            // Aspect ratio for proportional grid cells
            if (fixedHeight != null) {
                item.width  = fixedHeight.optInt("width",  200);
                item.height = fixedHeight.optInt("height", 200);
            }
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Selection ─────────────────────────────────────────────────────────

    private void onGifSelected(GifItem item) {
        Intent result = new Intent();
        result.putExtra("gif_url",         item.url);
        result.putExtra("gif_preview_url", item.previewUrl);
        result.putExtra("gif_title",       item.title != null ? item.title : "");
        setResult(RESULT_OK, result);
        finish();
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Data model
    // ══════════════════════════════════════════════════════════════════════

    public static class GifItem {
        public String id;
        public String title;
        public String url;
        public String previewUrl;
        public int    width;
        public int    height;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Adapter
    // ══════════════════════════════════════════════════════════════════════

    static class GifAdapter extends RecyclerView.Adapter<GifAdapter.GVH> {

        interface OnGifClick { void onClick(GifItem item); }

        private final List<GifItem> items = new ArrayList<>();
        private final OnGifClick    click;

        GifAdapter(OnGifClick c) { this.click = c; }

        void setItems(List<GifItem> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public GVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView iv = new ImageView(parent.getContext());
            int cellSize = parent.getContext().getResources()
                    .getDisplayMetrics().widthPixels / 2 - 12;
            iv.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, cellSize));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setPadding(4, 4, 4, 4);
            return new GVH(iv);
        }

        @Override
        public void onBindViewHolder(@NonNull GVH h, int pos) {
            GifItem item = items.get(pos);
            Glide.with(h.iv.getContext())
                    .asGif()
                    .load(item.previewUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(android.R.color.darker_gray)
                    .centerCrop()
                    .into(h.iv);
            h.iv.setOnClickListener(v -> click.onClick(item));
            h.iv.setContentDescription(item.title);
        }

        @Override public int getItemCount() { return items.size(); }

        static class GVH extends RecyclerView.ViewHolder {
            final ImageView iv;
            GVH(ImageView v) { super(v); iv = v; }
        }
    }
}
