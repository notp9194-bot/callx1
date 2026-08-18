package com.callx.app.editor;

import android.content.Intent;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.util.*;

/**
 * ReelFiltersActivity — Production-level Filters & Effects Screen.
 *
 * Features:
 *  ✅ 12 colour-grading filters (Normal, Vivid, Fade, Warm, Cool, etc.)
 *  ✅ Beauty slider (smooth skin, brighten)
 *  ✅ Brightness / Contrast / Saturation sliders
 *  ✅ Filter thumbnail strip (horizontal RecyclerView)
 *  ✅ Live preview on thumbnail image
 *  ✅ Apply filter → returns filter name to caller
 */
public class ReelFiltersActivity extends AppCompatActivity {

    public static final String EXTRA_THUMBNAIL_URI    = "filter_thumb_uri";
    /** Optional: pre-select this filter when the screen opens (for "re-edit" flow). */
    public static final String EXTRA_CURRENT_FILTER    = "current_filter";
    public static final String EXTRA_CURRENT_BRIGHTNESS= "current_brightness";
    public static final String EXTRA_CURRENT_CONTRAST  = "current_contrast";
    public static final String EXTRA_CURRENT_SATURATION= "current_saturation";
    public static final String EXTRA_CURRENT_BEAUTY    = "current_beauty";
    public static final String RESULT_FILTER_NAME  = "result_filter_name";
    public static final String RESULT_BRIGHTNESS   = "result_brightness";
    public static final String RESULT_CONTRAST     = "result_contrast";
    public static final String RESULT_SATURATION   = "result_saturation";
    public static final String RESULT_BEAUTY_LEVEL = "result_beauty_level";

    private ImageButton btnBack, btnApply;
    private ImageView   ivFilterPreview;
    private SeekBar     sbBrightness, sbContrast, sbSaturation, sbBeauty;
    private TextView    tvFilterName;
    private RecyclerView rvFilters;

    private String selectedFilter = "Normal";
    private float brightness = 0f, contrast = 1f, saturation = 1f, beautyLevel = 0f;

    private static final String[] FILTER_NAMES = {
        "Normal", "Vivid", "Fade", "Warm", "Cool", "Drama",
        "Vintage", "Mono", "Noir", "Juno", "Lark", "Clarendon"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_filters);

        bindViews();
        setupFilterStrip();
        setupSliders();
        restoreCurrentFilter(); // ✅ pre-select previously chosen filter + slider values
        setupClickListeners();
        loadThumbnail();
    }

    private void bindViews() {
        btnBack         = findViewById(R.id.btn_filter_back);
        btnApply        = findViewById(R.id.btn_filter_apply);
        ivFilterPreview = findViewById(R.id.iv_filter_preview);
        sbBrightness    = findViewById(R.id.sb_brightness);
        sbContrast      = findViewById(R.id.sb_contrast);
        sbSaturation    = findViewById(R.id.sb_saturation);
        sbBeauty        = findViewById(R.id.sb_beauty);
        tvFilterName    = findViewById(R.id.tv_filter_name);
        rvFilters       = findViewById(R.id.rv_filters);

        sbBrightness.setMax(200); sbBrightness.setProgress(100);
        sbContrast.setMax(200);   sbContrast.setProgress(100);
        sbSaturation.setMax(200); sbSaturation.setProgress(100);
        sbBeauty.setMax(100);     sbBeauty.setProgress(0);
    }

    /**
     * If the caller already had a filter selected (e.g. camera re-open),
     * restore it so the user continues from where they left off instead of
     * always landing on "Normal".
     */
    private void restoreCurrentFilter() {
        Intent i = getIntent();
        String cur = i.getStringExtra(EXTRA_CURRENT_FILTER);
        if (cur != null && !cur.isEmpty() && !cur.equals("Normal")) {
            // Restore slider values FIRST (so applyPresetFilter's setProgress calls
            // don't overwrite them with preset defaults when re-opening same filter)
            float b = i.getFloatExtra(EXTRA_CURRENT_BRIGHTNESS, 0f);
            float c = i.getFloatExtra(EXTRA_CURRENT_CONTRAST,   1f);
            float s = i.getFloatExtra(EXTRA_CURRENT_SATURATION, 1f);
            float bv= i.getFloatExtra(EXTRA_CURRENT_BEAUTY,     0f);
            // Convert back to SeekBar progress (reverse of the slider math)
            sbBrightness.setProgress(Math.round(b / 80f * 100f + 100f));
            sbContrast  .setProgress(Math.round(c * 100f));
            sbSaturation.setProgress(Math.round(s * 100f));
            sbBeauty    .setProgress(Math.round(bv * 100f));
            // Update internal state
            brightness  = b;
            contrast    = c;
            saturation  = s;
            beautyLevel = bv;
            // Mark filter chip as selected
            selectedFilter = cur;
            if (tvFilterName != null) tvFilterName.setText(cur);
            applyColorMatrix();
            // Refresh chip strip to highlight the active filter
            if (rvFilters.getAdapter() != null) rvFilters.getAdapter().notifyDataSetChanged();
        }
    }

    private void loadThumbnail() {
        String uriStr = getIntent().getStringExtra(EXTRA_THUMBNAIL_URI);
        if (uriStr != null && !uriStr.isEmpty()) {
            try { ivFilterPreview.setImageURI(Uri.parse(uriStr)); }
            catch (Exception ignored) {}
        }
    }

    private void setupFilterStrip() {
        rvFilters.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvFilters.setAdapter(new FilterChipAdapter());
    }

    private void setupSliders() {
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (sb == sbBrightness) brightness = (p - 100) / 100f * 80f;
                if (sb == sbContrast)   contrast   = p / 100f;
                if (sb == sbSaturation) saturation = p / 100f;
                if (sb == sbBeauty)     beautyLevel = p / 100f;
                applyColorMatrix();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb)  {}
        };
        sbBrightness.setOnSeekBarChangeListener(listener);
        sbContrast.setOnSeekBarChangeListener(listener);
        sbSaturation.setOnSeekBarChangeListener(listener);
        sbBeauty.setOnSeekBarChangeListener(listener);
    }

    private void applyColorMatrix() {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(saturation);
        ColorMatrix bright = new ColorMatrix(new float[]{
            contrast, 0, 0, 0, brightness,
            0, contrast, 0, 0, brightness,
            0, 0, contrast, 0, brightness,
            0, 0, 0, 1, 0
        });
        cm.postConcat(bright);
        ivFilterPreview.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    private void applyPresetFilter(String name) {
        selectedFilter = name;
        tvFilterName.setText(name);
        switch (name) {
            case "Vivid":     sbSaturation.setProgress(160); sbBrightness.setProgress(110); break;
            case "Fade":      sbContrast.setProgress(75);    sbSaturation.setProgress(80);  break;
            case "Warm":      sbSaturation.setProgress(120); sbBrightness.setProgress(105); break;
            case "Cool":      sbSaturation.setProgress(110); sbBrightness.setProgress(95);  break;
            case "Drama":     sbContrast.setProgress(140);   sbSaturation.setProgress(90);  break;
            case "Vintage":   sbContrast.setProgress(80);    sbSaturation.setProgress(70);  break;
            case "Mono":      sbSaturation.setProgress(0);                                   break;
            case "Noir":      sbSaturation.setProgress(0);   sbContrast.setProgress(140);   break;
            case "Juno":      sbSaturation.setProgress(130); sbBrightness.setProgress(108); break;
            case "Lark":      sbBrightness.setProgress(115); sbContrast.setProgress(90);    break;
            case "Clarendon": sbContrast.setProgress(130);   sbSaturation.setProgress(130); break;
            default:
                sbBrightness.setProgress(100);
                sbContrast.setProgress(100);
                sbSaturation.setProgress(100);
                break;
        }
        applyColorMatrix();
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnApply.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(RESULT_FILTER_NAME,  selectedFilter);
            result.putExtra(RESULT_BRIGHTNESS,   brightness);
            result.putExtra(RESULT_CONTRAST,     contrast);
            result.putExtra(RESULT_SATURATION,   saturation);
            result.putExtra(RESULT_BEAUTY_LEVEL, beautyLevel);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private class FilterChipAdapter extends RecyclerView.Adapter<FilterChipAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            TextView tvName;
            View     indicator;
            VH(View v) {
                super(v);
                tvName    = v.findViewById(R.id.tv_filter_chip_name);
                indicator = v.findViewById(R.id.v_filter_selected);
            }
        }
        @Override public VH onCreateViewHolder(android.view.ViewGroup p, int t) {
            View v = getLayoutInflater().inflate(R.layout.item_filter_chip, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int i) {
            String name = FILTER_NAMES[i];
            h.tvName.setText(name);
            h.indicator.setVisibility(name.equals(selectedFilter) ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                applyPresetFilter(name);
                notifyDataSetChanged();
            });
        }
        @Override public int getItemCount() { return FILTER_NAMES.length; }
    }
}
