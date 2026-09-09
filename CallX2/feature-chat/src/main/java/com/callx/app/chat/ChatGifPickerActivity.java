package com.callx.app.chat;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
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
 * ChatGifPickerActivity — in-app GIF search via Tenor, mirrors
 * feature-x's XGifPickerActivity exactly (same Tenor endpoints, same
 * media_formats parsing), just wired to feature-chat's own R/layout so
 * feature-chat doesn't need a dependency on feature-x.
 *
 * Launched from ChatMediaController/GroupChatActivity's attach sheet
 * "GIF" option. Returns "gif_url" + "gif_preview_url" + "gif_is_video"
 * via setResult() — see WHATSAPP-STYLE GIF DELIVERY below.
 *
 * Uses the same Tenor demo key fallback as XGifPickerActivity — add a
 * real key to strings.xml (tenor_api_key) for production rate limits.
 *
 * ── WHATSAPP-STYLE GIF DELIVERY ──────────────────────────────────────────
 * Tenor's raw "gif" format is a huge, CPU-decoded-frame-by-frame file —
 * often 5-10x the bytes of the exact same clip as an h.264 mp4, and Glide
 * has to software-decode every frame instead of handing it to the
 * hardware video decoder. WhatsApp/Telegram never actually send a .gif —
 * they send Tenor's small mp4 rendition and loop-play it muted like a
 * video. This picker now does the same: "gif_url" is the "tinymp4"
 * rendition (falling back to "mp4", then finally the old raw "gif" only
 * if Tenor has no video rendition for a given result at all), and
 * "gif_is_video" tells the send/render pipeline which case it got so it
 * can route mp4 results through ExoPlayer (loop, muted, no controls)
 * instead of Glide's animated-GIF decoder — see MediaViewerActivity's
 * gif-is-video branch and Message#gifIsVideo's javadoc.
 *
 * The in-grid preview here still uses "tinygif" (already tiny — it's
 * only ever decoded once per visible cell, not re-downloaded per chat
 * message the way the sent GIF would be), so no change needed there.
 */
public class ChatGifPickerActivity extends AppCompatActivity {

    private static final String TENOR_BASE = "https://tenor.googleapis.com/v2";
    private EditText etSearch;
    private RecyclerView rvGifs;
    private ProgressBar pbGif;
    private GifAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_gif_picker);

        etSearch = findViewById(R.id.et_gif_search);
        rvGifs   = findViewById(R.id.rv_gifs);
        pbGif    = findViewById(R.id.pb_gif);

        View btnBack = findViewById(R.id.btn_gif_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new GifAdapter(this::onGifSelected);
        rvGifs.setLayoutManager(new GridLayoutManager(this, 2));
        rvGifs.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String q = s.toString().trim();
                if (q.length() >= 2) searchGifs(q);
                else if (q.isEmpty()) loadTrending();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadTrending();
    }

    // Ask Tenor for the video renditions (tinymp4/mp4) alongside gif/tinygif
    // in one call — mp4 is the send format now (see class doc), tinygif
    // stays the grid-preview format, and gif is kept only as a last-resort
    // fallback for the rare result that has no video rendition at all.
    private static final String MEDIA_FILTER = "tinymp4,mp4,tinygif,gif";

    private void loadTrending() {
        fetch(TENOR_BASE + "/featured?key=" + getTenorKey() + "&limit=24&media_filter=" + MEDIA_FILTER);
    }

    private void searchGifs(String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            fetch(TENOR_BASE + "/search?key=" + getTenorKey() + "&q=" + encoded + "&limit=24&media_filter=" + MEDIA_FILTER);
        } catch (Exception ignored) {}
    }

    private void fetch(String urlStr) {
        if (pbGif != null) pbGif.setVisibility(View.VISIBLE);
        executor.submit(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject root = new JSONObject(sb.toString());
                JSONArray results = root.getJSONArray("results");
                List<GifItem> items = new ArrayList<>();
                for (int i = 0; i < results.length(); i++) {
                    JSONObject res = results.getJSONObject(i);
                    JSONObject mediaFormats = res.optJSONObject("media_formats");
                    if (mediaFormats == null) continue;
                    JSONObject tinymp4 = mediaFormats.optJSONObject("tinymp4");
                    JSONObject mp4     = mediaFormats.optJSONObject("mp4");
                    JSONObject gif     = mediaFormats.optJSONObject("gif");
                    JSONObject tinygif = mediaFormats.optJSONObject("tinygif");
                    if (tinymp4 == null && mp4 == null && gif == null && tinygif == null) continue;
                    GifItem item = new GifItem();
                    item.id = res.optString("id");
                    // Send format: smallest video rendition first (WhatsApp-style —
                    // see class doc), then the bigger mp4, and only fall back to
                    // the heavy raw gif when Tenor genuinely has no video for this
                    // result.
                    if (tinymp4 != null) {
                        item.url = tinymp4.optString("url");
                        item.isVideo = true;
                    } else if (mp4 != null) {
                        item.url = mp4.optString("url");
                        item.isVideo = true;
                    } else if (gif != null) {
                        item.url = gif.optString("url");
                    }
                    // Grid-preview format: always the small animated tinygif —
                    // cheap regardless of which format the send URL ended up as.
                    if (tinygif != null) {
                        item.previewUrl = tinygif.optString("url");
                        if (item.url == null || item.url.isEmpty()) {
                            item.url = item.previewUrl; // no mp4/gif at all — last resort
                        }
                    }
                    if (item.previewUrl == null || item.previewUrl.isEmpty()) item.previewUrl = item.url;
                    if (item.url != null && !item.url.isEmpty()) items.add(item);
                }
                runOnUiThread(() -> {
                    adapter.setItems(items);
                    if (pbGif != null) pbGif.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (pbGif != null) pbGif.setVisibility(View.GONE); });
            }
        });
    }

    private void onGifSelected(GifItem item) {
        Intent result = new Intent();
        result.putExtra("gif_url", item.url);
        result.putExtra("gif_preview_url", item.previewUrl);
        result.putExtra("gif_is_video", item.isVideo);
        setResult(RESULT_OK, result);
        finish();
    }

    private String getTenorKey() {
        try {
            int resId = getResources().getIdentifier("tenor_api_key", "string", getPackageName());
            if (resId != 0) return getString(resId);
        } catch (Exception ignored) {}
        return "LIVDSRZULELA"; // Tenor demo key (low rate limit) — same fallback as XGifPickerActivity
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ── GifItem ───────────────────────────────────────────────────────────────

    static class GifItem {
        String id, url, previewUrl;
        /** true when {@code url} is a video (tinymp4/mp4) rendition rather than raw gif. */
        boolean isVideo;
    }

    // ── GifAdapter ────────────────────────────────────────────────────────────

    static class GifAdapter extends RecyclerView.Adapter<GifAdapter.GVH> {
        private final List<GifItem> items = new ArrayList<>();
        private final OnGifClick click;
        interface OnGifClick { void onClick(GifItem item); }
        GifAdapter(OnGifClick c) { this.click = c; }

        void setItems(List<GifItem> list) {
            items.clear(); items.addAll(list); notifyDataSetChanged();
        }

        @NonNull @Override
        public GVH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            ImageView iv = new ImageView(p.getContext());
            iv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 160));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return new GVH(iv);
        }

        @Override public void onBindViewHolder(@NonNull GVH h, int pos) {
            GifItem item = items.get(pos);
            Glide.with(h.iv.getContext())
                .asGif()
                .load(item.previewUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .override(720, 720)
                .into(h.iv);
            h.iv.setOnClickListener(v -> click.onClick(item));
        }

        @Override public int getItemCount() { return items.size(); }

        static class GVH extends RecyclerView.ViewHolder {
            ImageView iv;
            GVH(ImageView v) { super(v); iv = v; }
        }
    }
}
