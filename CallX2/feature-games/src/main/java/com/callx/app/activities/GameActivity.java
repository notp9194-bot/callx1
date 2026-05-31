package com.callx.app.activities;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.callx.app.games.R;
import com.google.android.material.snackbar.Snackbar;

/**
 * GameActivity
 *
 * Ek URL ko fullscreen WebView mein load karta hai.
 * Features:
 *  - Hardware acceleration
 *  - JavaScript enabled
 *  - Full-screen (status/nav bars hidden)
 *  - Top progress bar (YouTube-style thin line)
 *  - Loading overlay (game controller emoji + spinner)
 *  - Error overlay with Retry button
 *  - Back press → WebView history traverse, last pe Activity finish
 *  - Internet check before loading
 *  - No-internet Snackbar
 *  - Landscape mode support via EXTRA_LANDSCAPE = true
 *
 * Usage:
 *   Intent i = new Intent(context, GameActivity.class);
 *   i.putExtra("url",       "https://callx-server.onrender.com/car-racing-3d.html");
 *   i.putExtra("title",     "Highway Rush 3D");
 *   i.putExtra("landscape", true);   // optional — default false (portrait)
 *   startActivity(i);
 */
public class GameActivity extends AppCompatActivity {

    private static final String TAG = "GameActivity";

    // ── Intent extras ─────────────────────────────────────────────────────────
    public static final String EXTRA_URL       = "url";
    public static final String EXTRA_TITLE     = "title";
    public static final String EXTRA_LANDSCAPE = "landscape";

    // ── Views ──────────────────────────────────────────────────────────────────
    private WebView          webView;
    private ProgressBar      pbTop;
    private LinearLayout     llLoading;
    private LinearLayout     llError;
    private TextView         tvErrorTitle;
    private TextView         tvErrorMsg;
    private Button           btnRetry;
    private TextView         btnBackError;
    private CoordinatorLayout rootLayout;

    private String  gameUrl   = "";
    private String  gameTitle = "Game";
    private boolean errorShown = false;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Orientation — landscape games (e.g. car racing) ───────────────
        boolean wantLandscape = getIntent().getBooleanExtra(EXTRA_LANDSCAPE, false);
        if (wantLandscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        // Full-screen flags — no status bar, no nav bar
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        setContentView(R.layout.activity_game);

        // ── View bindings ──────────────────────────────────────────────────
        webView      = findViewById(R.id.webview_game);
        pbTop        = findViewById(R.id.pb_game_top);
        llLoading    = findViewById(R.id.ll_game_loading);
        llError      = findViewById(R.id.ll_game_error);
        tvErrorTitle = findViewById(R.id.tv_game_error_title);
        tvErrorMsg   = findViewById(R.id.tv_game_error_msg);
        btnRetry     = findViewById(R.id.btn_game_retry);
        btnBackError = findViewById(R.id.btn_game_back_error);
        rootLayout   = (CoordinatorLayout) webView.getParent();

        // ── Intent data ───────────────────────────────────────────────────
        gameUrl   = getIntent().getStringExtra(EXTRA_URL);
        gameTitle = getIntent().getStringExtra(EXTRA_TITLE);
        if (gameUrl == null || gameUrl.isEmpty())
            gameUrl = "https://callx-server.onrender.com/bubble-pop-game.html";
        if (gameTitle == null || gameTitle.isEmpty())
            gameTitle = "Game";

        setupWebView();
        setupButtons();
        loadGame();
    }

    // ── WebView setup ─────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();

        // JavaScript — required for HTML5 games
        s.setJavaScriptEnabled(true);

        // DOM storage — many games use localStorage for scores
        s.setDomStorageEnabled(true);

        // Zoom controls off — game controls karte hain touch events
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);

        // Fit screen exactly — no extra whitespace
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        // Media auto-play (game sounds)
        s.setMediaPlaybackRequiresUserGesture(false);

        // Cache — game assets faster load honge
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Mixed content — HTTP resources over HTTPS (game assets)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Hardware acceleration already on at Activity level (manifest)

        // ── WebViewClient — page events ─────────────────────────────────
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                errorShown = false;
                showLoading(true);
                pbTop.setVisibility(View.VISIBLE);
                pbTop.setProgress(10);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                showLoading(false);
                pbTop.setVisibility(View.GONE);
                if (!errorShown) showError(false);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Only handle main frame errors (ignore sub-resource 404s)
                if (request.isForMainFrame()) {
                    errorShown = true;
                    showLoading(false);
                    pbTop.setVisibility(View.GONE);
                    String msg = isInternetAvailable()
                        ? "Server se connect nahi ho pa raha.\nThodi der baad retry karo."
                        : "Internet nahi hai. WiFi ya Data on karo.";
                    showErrorState("Game Load Nahi Hua 😵", msg);
                }
            }
        });

        // ── WebChromeClient — progress updates ──────────────────────────
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                pbTop.setProgress(newProgress);
                if (newProgress == 100) {
                    pbTop.setVisibility(View.GONE);
                }
            }
        });
    }

    // ── Button listeners ──────────────────────────────────────────────────────
    private void setupButtons() {
        btnRetry.setOnClickListener(v -> loadGame());
        btnBackError.setOnClickListener(v -> finish());
    }

    // ── Load game ─────────────────────────────────────────────────────────────
    private void loadGame() {
        showError(false);
        if (!isInternetAvailable()) {
            showLoading(false);
            showErrorState(
                "Internet Nahi Hai 📡",
                "WiFi ya Mobile Data on karo, phir retry karo.");
            showNoInternetSnackbar();
            return;
        }
        showLoading(true);
        webView.loadUrl(gameUrl);
    }

    // ── Visibility helpers ────────────────────────────────────────────────────
    private void showLoading(boolean show) {
        llLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showError(boolean show) {
        llError.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showErrorState(String title, String msg) {
        tvErrorTitle.setText(title);
        tvErrorMsg.setText(msg);
        showError(true);
    }

    private void showNoInternetSnackbar() {
        Snackbar.make(rootLayout, "🌐  Internet nahi hai!", Snackbar.LENGTH_LONG)
            .setBackgroundTint(0xFF6C3CF7)
            .setTextColor(0xFFFFFFFF)
            .setAction("Retry", v -> loadGame())
            .setActionTextColor(0xFFFF6B6B)
            .show();
    }

    // ── Internet check ────────────────────────────────────────────────────────
    private boolean isInternetAvailable() {
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    // ── Back press — WebView history first ───────────────────────────────────
    @Override
    public void onBackPressed() {
        if (llError.getVisibility() == View.VISIBLE) {
            finish();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    // ── Key events (hardware back key support) ────────────────────────────────
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
