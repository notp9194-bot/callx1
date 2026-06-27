package com.callx.app.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.callx.app.reels.R;

/**
 * ReelABRSettingsActivity — Video quality settings for reels
 *
 * Features:
 *  ✅ Auto / 360p / 480p / 720p / 1080p quality selection
 *  ✅ Separate settings for Wi-Fi vs Mobile data
 *  ✅ Data saver toggle (forces 360p on mobile)
 *  ✅ Current bandwidth display
 *  ✅ Persisted in SharedPreferences
 *
 * Usage:
 *   startActivity(new Intent(context, ReelABRSettingsActivity.class));
 *
 * Read saved cap anywhere:
 *   AdaptiveStreamingManager.QualityCap cap = ReelABRSettingsActivity.getSavedCap(context, onWifi);
 */
public class ReelABRSettingsActivity extends AppCompatActivity {

    private static final String PREFS         = "abr_settings";
    private static final String KEY_WIFI_CAP  = "wifi_cap";
    private static final String KEY_DATA_CAP  = "data_cap";
    private static final String KEY_DATA_SAVE = "data_saver";

    private RadioGroup  rgWifi, rgData;
    private Switch      swDataSaver;
    private TextView    tvBandwidth, tvDataSaverDesc;
    private LinearLayout layoutDataQuality;

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

        rgWifi          = findViewById(R.id.rg_wifi_quality);
        rgData          = findViewById(R.id.rg_data_quality);
        swDataSaver     = findViewById(R.id.sw_data_saver);
        tvBandwidth     = findViewById(R.id.tv_bandwidth);
        tvDataSaverDesc = findViewById(R.id.tv_data_saver_desc);
        layoutDataQuality = findViewById(R.id.layout_data_quality);

        loadSavedSettings();
        showCurrentBandwidth();

        swDataSaver.setOnCheckedChangeListener((btn, checked) -> {
            layoutDataQuality.setVisibility(checked ? View.GONE : View.VISIBLE);
            tvDataSaverDesc.setVisibility(checked ? View.VISIBLE : View.GONE);
            saveDataSaver(checked);
        });

        rgWifi.setOnCheckedChangeListener((group, id) -> saveWifiCap(radioIdToCap(id)));
        rgData.setOnCheckedChangeListener((group, id) -> saveDataCap(radioIdToCap(id)));
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void loadSavedSettings() {
        SharedPreferences p = prefs();
        boolean dataSaver   = p.getBoolean(KEY_DATA_SAVE, false);
        String wifiCap      = p.getString(KEY_WIFI_CAP, AdaptiveStreamingManager.QualityCap.AUTO.name());
        String dataCap      = p.getString(KEY_DATA_CAP, AdaptiveStreamingManager.QualityCap.Q480P.name());

        swDataSaver.setChecked(dataSaver);
        layoutDataQuality.setVisibility(dataSaver ? View.GONE : View.VISIBLE);
        tvDataSaverDesc.setVisibility(dataSaver ? View.VISIBLE : View.GONE);

        rgWifi.check(capToRadioId(AdaptiveStreamingManager.QualityCap.valueOf(wifiCap)));
        rgData.check(capToRadioId(AdaptiveStreamingManager.QualityCap.valueOf(dataCap)));
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

    // ── Bandwidth display ─────────────────────────────────────────────────────

    private void showCurrentBandwidth() {
        AdaptiveStreamingManager mgr = AdaptiveStreamingManager.get(this);
        long bw = mgr.currentBandwidthKbps();
        if (bw <= 0) {
            tvBandwidth.setText("Bandwidth: measuring…");
        } else if (bw >= 1_000) {
            tvBandwidth.setText(String.format("Current: %.1f Mbps", bw / 1000.0));
        } else {
            tvBandwidth.setText("Current: " + bw + " Kbps");
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /** Read the user's preferred cap for the current network type */
    public static AdaptiveStreamingManager.QualityCap getSavedCap(Context ctx, boolean isWifi) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean dataSaver   = p.getBoolean(KEY_DATA_SAVE, false);
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
            case Q360P:  return R.id.rb_360p;
            case Q480P:  return R.id.rb_480p;
            case Q720P:  return R.id.rb_720p;
            case Q1080P: return R.id.rb_1080p;
            default:     return R.id.rb_auto;
        }
    }

    private AdaptiveStreamingManager.QualityCap radioIdToCap(int id) {
        if (id == R.id.rb_360p)  return AdaptiveStreamingManager.QualityCap.Q360P;
        if (id == R.id.rb_480p)  return AdaptiveStreamingManager.QualityCap.Q480P;
        if (id == R.id.rb_720p)  return AdaptiveStreamingManager.QualityCap.Q720P;
        if (id == R.id.rb_1080p) return AdaptiveStreamingManager.QualityCap.Q1080P;
        return AdaptiveStreamingManager.QualityCap.AUTO;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
