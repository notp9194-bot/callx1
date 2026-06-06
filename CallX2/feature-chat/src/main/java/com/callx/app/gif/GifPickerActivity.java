package com.callx.app.gif;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.giphy.sdk.core.models.Media;
import com.giphy.sdk.core.models.enums.MediaType;
import com.giphy.sdk.core.models.enums.RatingType;
import com.giphy.sdk.core.network.api.CompletionHandler;
import com.giphy.sdk.core.network.api.GPHApiClient;
import com.giphy.sdk.core.network.response.ListMediaResponse;

/**
 * GifPickerActivity — GIPHY GIF picker.
 * Online  : trending on open, live search with debounce.
 * Offline : Glide disk cache serves previously loaded GIFs; no crash.
 *
 * Result extras:
 *   EXTRA_GIF_ID      — GIPHY gif id
 *   EXTRA_GIF_URL     — CDN gif URL (send this in message)
 *   EXTRA_GIF_PREVIEW — static still preview URL
 */
public class GifPickerActivity extends AppCompatActivity {

    public static final String EXTRA_GIF_ID      = "gif_id";
    public static final String EXTRA_GIF_URL     = "gif_url";
    public static final String EXTRA_GIF_PREVIEW = "gif_preview_url";

    // ← Replace with your key from developers.giphy.com (free)
    private static final String GIPHY_API_KEY       = "PolH67XHOhQnLy2rZcMMz5wSjd5ynraL";
    private static final int    PAGE_SIZE           = 24;
    private static final long   DEBOUNCE_MS         = 400;

    private RecyclerView  rvGifs;
    private EditText      etSearch;
    private TextView      tvEmpty;
    private View          progressBar;

    private GifGridAdapter adapter;
    private GPHApiClient   client;

    private final Handler  handler        = new Handler(Looper.getMainLooper());
    private       Runnable searchRunnable;

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gif_picker);

        rvGifs      = findViewById(R.id.rv_gifs);
        etSearch    = findViewById(R.id.et_gif_search);
        tvEmpty     = findViewById(R.id.tv_gif_empty);
        progressBar = findViewById(R.id.gif_progress);

        ImageView btnBack = findViewById(R.id.btn_gif_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // GIPHY REST client — no SDK-wide init needed for Java REST client
        client = new GPHApiClient(GIPHY_API_KEY);

        rvGifs.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new GifGridAdapter(this, gif -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_GIF_ID,      gif.getId());
            result.putExtra(EXTRA_GIF_URL,     GifUtils.getGifUrl(gif));
            result.putExtra(EXTRA_GIF_PREVIEW, GifUtils.getPreviewUrl(gif));
            setResult(Activity.RESULT_OK, result);
            finish();
        });
        rvGifs.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
                String q = s.toString().trim();
                searchRunnable = q.isEmpty() ? GifPickerActivity.this::loadTrending
                                             : () -> searchGifs(q);
                handler.postDelayed(searchRunnable, DEBOUNCE_MS);
            }
        });

        loadTrending();
    }

    // ─────────────────────────────────────────────────────────────────────
    // API
    // ─────────────────────────────────────────────────────────────────────

    private void loadTrending() {
        showProgress(true);
        client.trending(
            MediaType.gif,
            PAGE_SIZE,
            0,
            RatingType.g,
            (CompletionHandler<ListMediaResponse>) (response, error) -> {
                runOnUiThread(() -> {
                    showProgress(false);
                    if (error != null || response == null || response.getData() == null) {
                        showEmpty(adapter.getItemCount() == 0);
                        if (error != null)
                            Toast.makeText(this, "Offline mode", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    adapter.setGifs(response.getData());
                    showEmpty(response.getData().isEmpty());
                });
            }
        );
    }

    private void searchGifs(String query) {
        showProgress(true);
        client.search(
            query,
            MediaType.gif,
            PAGE_SIZE,
            0,
            RatingType.g,
            null,          // lang — null = default (en)
            null,          // country
            (CompletionHandler<ListMediaResponse>) (response, error) -> {
                runOnUiThread(() -> {
                    showProgress(false);
                    if (error != null || response == null || response.getData() == null) {
                        showEmpty(adapter.getItemCount() == 0);
                        if (error != null)
                            Toast.makeText(this, "Search unavailable offline", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    adapter.setGifs(response.getData());
                    showEmpty(response.getData().isEmpty());
                });
            }
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private void showProgress(boolean show) {
        if (progressBar != null)
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showEmpty(boolean show) {
        if (tvEmpty != null)
            tvEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
