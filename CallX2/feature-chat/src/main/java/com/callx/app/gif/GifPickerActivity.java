package com.callx.app.gif;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.chat.R;

import com.giphy.sdk.core.GPHCore;
import com.giphy.sdk.core.models.Media;
import com.giphy.sdk.core.models.enums.MediaType;
import com.giphy.sdk.core.models.enums.RatingType;
import com.giphy.sdk.core.network.api.GPHApiClient;
import com.giphy.sdk.core.network.response.ListMediaResponse;

import java.util.ArrayList;
import java.util.List;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

/**
 * GifPickerActivity — GIPHY-powered GIF picker.
 *
 * Online:  GIPHY API → trending GIFs on open, search on type.
 * Offline: Shows last-loaded GIFs from Glide disk cache.
 *          No crash — user sees cached GIFs or empty-state message.
 *
 * Result:
 *   EXTRA_GIF_ID        → GIPHY gif id (String)
 *   EXTRA_GIF_URL       → direct CDN URL for sending (String)
 *   EXTRA_GIF_PREVIEW   → static still preview URL (String)
 *
 * Usage:
 *   startActivityForResult(new Intent(this, GifPickerActivity.class), REQ_GIF);
 */
public class GifPickerActivity extends AppCompatActivity {

    public static final String EXTRA_GIF_ID      = "gif_id";
    public static final String EXTRA_GIF_URL     = "gif_url";
    public static final String EXTRA_GIF_PREVIEW = "gif_preview_url";

    // YOUR GIPHY API KEY — replace with real key from developers.giphy.com
    // Free key: sign up → create app → copy API key
    private static final String GIPHY_API_KEY = "PolH67XHOhQnLy2rZcMMz5wSjd5ynraL";

    private static final int    PAGE_SIZE   = 24;
    private static final long   SEARCH_DEBOUNCE_MS = 400;

    private RecyclerView rvGifs;
    private EditText     etSearch;
    private TextView     tvEmpty;
    private View         progressBar;

    private GifGridAdapter adapter;
    private GPHApiClient   giphyClient;

    private final android.os.Handler handler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private Runnable searchRunnable;

    // ─────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gif_picker);

        rvGifs      = findViewById(R.id.rv_gifs);
        etSearch    = findViewById(R.id.et_gif_search);
        tvEmpty     = findViewById(R.id.tv_gif_empty);
        progressBar = findViewById(R.id.gif_progress);

        // Back button
        ImageView btnBack = findViewById(R.id.btn_gif_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // GIPHY SDK init
        GPHCore.INSTANCE.configure(this, GIPHY_API_KEY, true, null);
        giphyClient = new GPHApiClient(GIPHY_API_KEY);

        // Grid: 2 columns
        GridLayoutManager lm = new GridLayoutManager(this, 2);
        rvGifs.setLayoutManager(lm);

        adapter = new GifGridAdapter(this, gif -> {
            // User tapped a GIF → return result
            String gifId      = gif.getId();
            String gifUrl     = getGifUrl(gif);
            String previewUrl = getPreviewUrl(gif);

            Intent result = new Intent();
            result.putExtra(EXTRA_GIF_ID,      gifId);
            result.putExtra(EXTRA_GIF_URL,     gifUrl);
            result.putExtra(EXTRA_GIF_PREVIEW, previewUrl);
            setResult(Activity.RESULT_OK, result);
            finish();
        });
        rvGifs.setAdapter(adapter);

        // Search input with debounce
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
                searchRunnable = () -> {
                    String q = s.toString().trim();
                    if (q.isEmpty()) loadTrending();
                    else             searchGifs(q);
                };
                handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Load trending on open
        loadTrending();
    }

    // ─────────────────────────────────────────────────────────
    // API calls
    // ─────────────────────────────────────────────────────────

    private void loadTrending() {
        showProgress(true);
        giphyClient.trending(
            MediaType.gif,
            PAGE_SIZE,
            0,
            RatingType.g,
            (Function2<ListMediaResponse, Throwable, Unit>) (response, error) -> {
                runOnUiThread(() -> {
                    showProgress(false);
                    if (error != null) {
                        // Offline — adapter shows cached thumbnails from previous loads
                        showEmpty(adapter.getItemCount() == 0);
                        return;
                    }
                    if (response != null && response.getData() != null) {
                        adapter.setGifs(response.getData());
                        showEmpty(response.getData().isEmpty());
                    }
                });
                return null;
            }
        );
    }

    private void searchGifs(String query) {
        showProgress(true);
        giphyClient.search(
            query,
            MediaType.gif,
            PAGE_SIZE,
            0,
            RatingType.g,
            "en",
            (Function2<ListMediaResponse, Throwable, Unit>) (response, error) -> {
                runOnUiThread(() -> {
                    showProgress(false);
                    if (error != null) {
                        showEmpty(adapter.getItemCount() == 0);
                        Toast.makeText(this, "Offline — search unavailable", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (response != null && response.getData() != null) {
                        adapter.setGifs(response.getData());
                        showEmpty(response.getData().isEmpty());
                    }
                });
                return null;
            }
        );
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    /** Best URL to send as the actual GIF message (mp4 preferred — smaller, faster). */
    private String getGifUrl(Media gif) {
        try {
            // Prefer downsized mp4 for fast sending
            if (gif.getImages() != null
                    && gif.getImages().getDownsized() != null
                    && gif.getImages().getDownsized().getGifUrl() != null) {
                return gif.getImages().getDownsized().getGifUrl();
            }
            if (gif.getImages() != null
                    && gif.getImages().getOriginal() != null
                    && gif.getImages().getOriginal().getGifUrl() != null) {
                return gif.getImages().getOriginal().getGifUrl();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Static preview — shown before GIF loads (fast, offline-cacheable). */
    private String getPreviewUrl(Media gif) {
        try {
            if (gif.getImages() != null
                    && gif.getImages().getFixedWidthStill() != null
                    && gif.getImages().getFixedWidthStill().getGifUrl() != null) {
                return gif.getImages().getFixedWidthStill().getGifUrl();
            }
            if (gif.getImages() != null
                    && gif.getImages().getPreviewGif() != null
                    && gif.getImages().getPreviewGif().getGifUrl() != null) {
                return gif.getImages().getPreviewGif().getGifUrl();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void showProgress(boolean show) {
        if (progressBar != null)
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showEmpty(boolean show) {
        if (tvEmpty != null)
            tvEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
