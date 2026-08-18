package com.callx.app.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.callx.app.reels.R;

/**
 * ReelABRSettingsActivity v2 — Production video quality settings
 *
 * Upgrades over v1:
 *  ✅ Live bandwidth auto-refresh every 3 seconds while screen is open
 *  ✅ Network type indicator (Wi-Fi / Mobile Data / Offline)
 *  ✅ Quality recommendation badge based on live bandwidth
 *  ✅ Bandwidth speed bar (visual gauge 0–100Mbps)
 *  ✅ Separate caps for Wi-Fi and Mobile Data
 *  ✅ Data Saver toggle → forces 360p on mobile
 *  ✅ Settings persist in SharedPreferences
 *
 * Usage:
 *   startActivity(new Intent(context, ReelABRSettingsActivity.class));
 *
 * Read saved cap anywhere:
 *   QualityCap cap = ReelABRSettingsActivity.getSavedCap(context, onWifi);
 */
public class ReelABRSettingsActivity extends AppCompatActivity {

    private static final String PREFS         = "abr_settings";
    private static final String KEY_WIFI_CAP  = "wifi_cap";
    private static final String KEY_DATA_CAP  = "data_cap";
    private static final String KEY_DATA_SAVE = "data_saver";

    private static final long BW_REFRESH_MS = 3_000L;

    // ── Views ─────────────────────────────────────────────────────────────────
    private RadioGroup   rgWifi, rgData;
    private Switch       swDataSaver;
    private TextView     tvBandwidth, tvDataSaverDesc, tvNetworkType, tvRecommended, tvQoeLifetime;
    private LinearLayout layoutDataQuality;
    private ProgressBar  pbBandwidthGauge;

    // ── Bandwidth refresh ─────────────────────────────────────────────────────
    private final Handler bwHandler  = new Handler(Looper.getMainLooper());
    private Runnable      bwRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_abr_settings);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Video Quality");
        }
        tb.setNavigationOnClickListener(v -> finish());

        rgWifi           = findViewById(R.id.rg_wifi_quality);
        rgData           = findViewById(R.id.rg_data_quality);
        swDataSaver      = findViewById(R.id.sw_data_saver);
        tvBandwidth      = findViewById(R.id.tv_bandwidth);
        tvDataSaverDesc  = findViewById(R.id.tv_data_saver_desc);
        tvNetworkType    = findViewById(R.id.tv_network_type);
        tvRecommended    = findViewById(R.id.tv_quality_recommendation);
        layoutDataQuality = findViewById(R.id.layout_data_quality);
        pbBandwidthGauge  = findViewById(R.id.pb_bandwidth_gauge);
        tvQoeLifetime     = findViewById(R.id.tv_qoe_lifetime);

        // Show persisted lifetime QoE stats immediately on open
        if (tvQoeLifetime != null) {
            tvQoeLifetime.setText(AdaptiveStreamingManager.get(this).getLifetimeQoeSummary());
        }

        loadSavedSettings();

        swDataSaver.setOnCheckedChangeListener((btn, checked) -> {
            layoutDataQuality.setVisibility(checked ? View.GONE : View.VISIBLE);
            tvDataSaverDesc.setVisibility(checked ? View.VISIBLE : View.GONE);
            saveDataSaver(checked);
        });

        rgWifi.setOnCheckedChangeListener((group, id) -> saveWifiCap(radioIdToCap(id)));
        rgData.setOnCheckedChangeListener((group, id) -> saveDataCap(radioIdToCap(id)));
    }

    @Override protected void onResume() {
        super.onResume();
        startBandwidthRefresh();
    }

    @Override protected void onPause() {
        super.onPause();
        stopBandwidthRefresh();
    }

    // ── Live bandwidth refresh ────────────────────────────────────────────────

    private void startBandwidthRefresh() {
        bwRunnable = new Runnable() {
            @Override public void run() {
                updateBandwidthUI();
                bwHandler.postDelayed(this, BW_REFRESH_MS);
            }
        };
        bwHandler.post(bwRunnable);
    }

    private void stopBandwidthRefresh() {
        if (bwRunnable != null) {
            bwHandler.removeCallbacks(bwRunnable);
            bwRunnable = null;
        }
    }

    private void updateBandwidthUI() {
        AdaptiveStreamingManager mgr = AdaptiveStreamingManager.get(this);
        long bwKbps = mgr.getEwmaBandwidthKbps(); // use EWMA, not raw spike

        // Network type label
        String netLabel = getNetworkTypeLabel();
        if (tvNetworkType != null) tvNetworkType.setText(netLabel);

        // Bandwidth text
        String bwText;
        int gaugeProgress; // 0–100 representing 0–20 Mbps
        if (bwKbps <= 0) {
            bwText        = "Measuring…";
            gaugeProgress = 0;
        } else if (bwKbps >= 1_000) {
            bwText = String.format("%.1f Mbps (avg)", bwKbps / 1000.0);
            gaugeProgress = (int) Math.min(100, bwKbps / 200); // 20 Mbps = 100%
        } else {
            bwText        = bwKbps + " Kbps (avg)";
            gaugeProgress = (int)(bwKbps / 20);
        }
        tvBandwidth.setText(bwText);
        if (pbBandwidthGauge != null) pbBandwidthGauge.setProgress(gaugeProgress);

        // Recommendation based on EWMA
        if (tvRecommended != null) {
            AdaptiveStreamingManager.QualityCap rec = mgr.recommendedCap(this);
            String recLabel = AdaptiveStreamingManager.capLabel(rec);
            tvRecommended.setText("Recommended: " + recLabel);
        }
    }

    private String getNetworkTypeLabel() {
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "Unknown";
        android.net.Network net = cm.getActiveNetwork();
        if (net == null) return "Offline";
        NetworkCapabilities nc = cm.getNetworkCapabilities(net);
        if (nc == null) return "Unknown";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     return "Wi-Fi";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Mobile Data";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        return "Connected";
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void loadSavedSettings() {
        SharedPreferences p = prefs();
        boolean dataSaver   = p.getBoolean(KEY_DATA_SAVE, false);

        String wifiCapName = p.getString(KEY_WIFI_CAP,
            AdaptiveStreamingManager.QualityCap.AUTO.name());
        String dataCapName = p.getString(KEY_DATA_CAP,
            AdaptiveStreamingManager.QualityCap.Q480P.name());

        swDataSaver.setChecked(dataSaver);
        layoutDataQuality.setVisibility(dataSaver ? View.GONE : View.VISIBLE);
        tvDataSaverDesc.setVisibility(dataSaver ? View.VISIBLE : View.GONE);

        try {
            rgWifi.check(capToRadioId(
                AdaptiveStreamingManager.QualityCap.valueOf(wifiCapName)));
        } catch (Exception ignored) {
            rgWifi.check(R.id.rb_wifi_auto);
        }
        try {
            rgData.check(capToDataRadioId(
                AdaptiveStreamingManager.QualityCap.valueOf(dataCapName)));
        } catch (Exception ignored) {
            rgData.check(R.id.rb_data_auto);
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private void saveWifiCap(AdaptiveStreamingManager.QualityCap cap) {
        prefs().edit().putString(KEY_WIFI_CAP, cap.name()).apply();
    }

    private void saveDataCap(AdaptiveStreamingManager.QualityCap cap) {
        prefs().edit().putString(KEY_DATA_CAP, cap.name()).apply();
    }

    private void saveDataSaver(boolean on) {
        prefs().edit().putBoolean(KEY_DATA_SAVE, on).apply();
    }

    // ── Static helper — used by ReelPlayerController ──────────────────────────

    /** Read the user's preferred quality cap for the given network type. */
    public static AdaptiveStreamingManager.QualityCap getSavedCap(Context ctx, boolean isWifi) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean dataSaver   = p.getBoolean(KEY_DATA_SAVE, false);
        // Data saver overrides all mobile settings
        if (!isWifi && dataSaver) return AdaptiveStreamingManager.QualityCap.Q360P;

        String key = isWifi ? KEY_WIFI_CAP : KEY_DATA_CAP;
        String def = isWifi
            ? AdaptiveStreamingManager.QualityCap.AUTO.name()
            : AdaptiveStreamingManager.QualityCap.Q480P.name();
        String saved = p.getString(key, def);
        try { return AdaptiveStreamingManager.QualityCap.valueOf(saved); }
        catch (Exception e) { return AdaptiveStreamingManager.QualityCap.AUTO; }
    }

    // ── Radio helpers ─────────────────────────────────────────────────────────

    private int capToRadioId(AdaptiveStreamingManager.QualityCap cap) {
        switch (cap) {
            case Q360P:  return R.id.rb_wifi_360p;
            case Q480P:  return R.id.rb_wifi_480p;
            case Q720P:  return R.id.rb_wifi_720p;
            case Q1080P: return R.id.rb_wifi_1080p;
            default:     return R.id.rb_wifi_auto;
        }
    }

    private int capToDataRadioId(AdaptiveStreamingManager.QualityCap cap) {
        switch (cap) {
            case Q360P:  return R.id.rb_data_360p;
            case Q480P:  return R.id.rb_data_480p;
            case Q720P:  return R.id.rb_data_720p;
            default:     return R.id.rb_data_auto;
        }
    }

    private AdaptiveStreamingManager.QualityCap radioIdToCap(int id) {
        if (id == R.id.rb_wifi_360p || id == R.id.rb_data_360p)
            return AdaptiveStreamingManager.QualityCap.Q360P;
        if (id == R.id.rb_wifi_480p || id == R.id.rb_data_480p)
            return AdaptiveStreamingManager.QualityCap.Q480P;
        if (id == R.id.rb_wifi_720p || id == R.id.rb_data_720p)
            return AdaptiveStreamingManager.QualityCap.Q720P;
        if (id == R.id.rb_wifi_1080p)
            return AdaptiveStreamingManager.QualityCap.Q1080P;
        return AdaptiveStreamingManager.QualityCap.AUTO;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
