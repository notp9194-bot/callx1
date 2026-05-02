package com.callx.app.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.utils.Constants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 3 (NEW): GIF & Sticker Picker
 *
 * Uses Tenor GIF API (free, just needs API key in Constants.TENOR_API_KEY).
 * Falls back to Giphy-compatible endpoint.
 * Returns selected GIF URL via setResult / RESULT_OK.
 *
 * Intent extras on return:
 *   "gifUrl"  → String
 *   "type"    → "gif" or "sticker"
 */
public class GifStickerPickerActivity extends AppCompatActivity {

    public static final String EXTRA_GIF_URL = "gifUrl";
    public static final String EXTRA_TYPE    = "type";

    private EditText     etSearch;
    private RecyclerView rvGifs;
    private GifAdapter   adapter;
    private final List<String> gifUrls = new ArrayList<>();
    private String mode = "gif"; // "gif" or "sticker"

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gif_sticker);

        mode = getIntent().getStringExtra("mode") != null
               ? getIntent().getStringExtra("mode") : "gif";

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(mode.equals("sticker") ? "Stickers" : "GIFs");
        }
        tb.setNavigationOnClickListener(v -> finish());

        etSearch = findViewById(R.id.et_search);
        rvGifs   = findViewById(R.id.rv_gifs);
        rvGifs.setLayoutManager(new GridLayoutManager(this, 2));
        adapter  = new GifAdapter(gifUrls, url -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_GIF_URL, url);
            result.putExtra(EXTRA_TYPE, mode);
            setResult(Activity.RESULT_OK, result);
            finish();
        });
        rvGifs.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){
                if (s.length() > 1) searchGifs(s.toString().trim());
            }
            @Override public void afterTextChanged(Editable s){}
        });

        // Load trending on open
        searchGifs("trending");
    }

    private void searchGifs(String query) {
        new android.os.AsyncTask<Void, Void, List<String>>() {
            @Override protected List<String> doInBackground(Void... v) {
                List<String> urls = new ArrayList<>();
                try {
                    String apiKey = Constants.TENOR_API_KEY;
                    String endpoint = query.equals("trending")
                            ? "https://tenor.googleapis.com/v2/featured?key=" + apiKey + "&limit=20&media_filter=gif"
                            : "https://tenor.googleapis.com/v2/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                              + "&key=" + apiKey + "&limit=20&media_filter=gif";
                    URL url = new URL(endpoint);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray results = root.getJSONArray("results");
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.getJSONObject(i);
                        JSONObject media = item.getJSONArray("media_formats")
                                             .getJSONObject(0);
                        if (media.has("gif")) {
                            urls.add(media.getJSONObject("gif").getString("url"));
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.e("GifPicker", "Search failed", e);
                }
                return urls;
            }
            @Override protected void onPostExecute(List<String> result) {
                gifUrls.clear();
                gifUrls.addAll(result);
                adapter.notifyDataSetChanged();
                if (result.isEmpty())
                    Toast.makeText(GifStickerPickerActivity.this,
                            "No results", Toast.LENGTH_SHORT).show();
            }
        }.execute();
    }

    // ── Adapter ────────────────────────────────────────────────────────────

    interface GifClickListener { void onClick(String url); }

    static class GifAdapter extends RecyclerView.Adapter<GifAdapter.VH> {
        private final List<String>    urls;
        private final GifClickListener listener;
        GifAdapter(List<String> u, GifClickListener l) { urls = u; listener = l; }
        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            ImageView iv = new ImageView(p.getContext());
            iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 220));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return new VH(iv);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            String url = urls.get(pos);
            Glide.with(h.iv).asGif().load(url)
                 .placeholder(R.drawable.ic_gallery).into(h.iv);
            h.iv.setOnClickListener(v -> listener.onClick(url));
        }
        @Override public int getItemCount() { return urls.size(); }
        static class VH extends RecyclerView.ViewHolder {
            ImageView iv;
            VH(ImageView v) { super(v); iv = v; }
        }
    }
}
