package com.callx.app.activities;

import android.content.Intent;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.util.Locale;

/**
 * ReelEffectsActivity — Live Camera Effects & Filters Screen.
 *
 * Features:
 *  ✅ 16 real-time color-grade effects (Normal, Vivid, Fade, Warm, Cool,
 *     Drama, Vintage, Mono, Noir, Juno, Lark, Clarendon, Gingham, Moon,
 *     Ludwig, Aden)
 *  ✅ Beauty slider (skin smoothing level)
 *  ✅ Brightness / Contrast / Saturation fine-tune sliders
 *  ✅ Preview via ColorMatrixColorFilter on a live thumbnail
 *  ✅ Face-sticker overlays selector (Heart Eyes, Sunglasses, Star, Crown,
 *     Dog Ear, Rainbow — represented as emoji badges for now)
 *  ✅ "Apply" returns selected effect config to ReelCameraActivity
 *  ✅ Reset button clears all adjustments
 */
public class ReelEffectsActivity extends AppCompatActivity {

    public static final String RESULT_EFFECT_NAME  = "result_effect_name";
    public static final String RESULT_BRIGHTNESS   = "result_effect_brightness";
    public static final String RESULT_CONTRAST     = "result_effect_contrast";
    public static final String RESULT_SATURATION   = "result_effect_saturation";
    public static final String RESULT_BEAUTY_LEVEL = "result_effect_beauty";
    public static final String RESULT_STICKER_ID   = "result_sticker_id";

    private static final String[] EFFECT_NAMES = {
        "Normal", "Vivid", "Fade", "Warm", "Cool", "Drama",
        "Vintage", "Mono", "Noir", "Juno", "Lark", "Clarendon",
        "Gingham", "Moon", "Ludwig", "Aden"
    };

    private static final String[] STICKER_EMOJIS = {
        "😍", "😎", "⭐", "👑", "🐶", "🌈", "🔥", "💫"
    };
    private static final String[] STICKER_IDS = {
        "heart_eyes", "sunglasses", "star", "crown",
        "dog_ear", "rainbow", "fire", "sparkle"
    };

    private ImageView    ivLivePreview;
    private RecyclerView rvEffects, rvStickers;
    private SeekBar      sbBrightness, sbContrast, sbSaturation, sbBeauty;
    private TextView     tvEffectName, tvBeautyLabel;
    private View         btnApply, btnBack, btnReset;

    private String selectedEffect  = "Normal";
    private String selectedSticker = "";
    private float  brightness = 0f, contrast = 1f, saturation = 1f, beautyLevel = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_effects);
        bindViews();
        buildEffectStrip();
        buildStickerStrip();
        setupSliders();
        setupClickListeners();
    }

    private void bindViews() {
        ivLivePreview = findViewById(R.id.iv_effects_preview);
        rvEffects     = findViewById(R.id.rv_effects_strip);
        rvStickers    = findViewById(R.id.rv_sticker_strip);
        sbBrightness  = findViewById(R.id.sb_effects_brightness);
        sbContrast    = findViewById(R.id.sb_effects_contrast);
        sbSaturation  = findViewById(R.id.sb_effects_saturation);
        sbBeauty      = findViewById(R.id.sb_effects_beauty);
        tvEffectName  = findViewById(R.id.tv_effect_name);
        tvBeautyLabel = findViewById(R.id.tv_beauty_label);
        btnApply      = findViewById(R.id.btn_effects_apply);
        btnBack       = findViewById(R.id.btn_effects_back);
        btnReset      = findViewById(R.id.btn_effects_reset);

        sbBrightness.setMax(200); sbBrightness.setProgress(100);
        sbContrast.setMax(200);   sbContrast.setProgress(100);
        sbSaturation.setMax(200); sbSaturation.setProgress(100);
        sbBeauty.setMax(100);     sbBeauty.setProgress(0);
    }

    private void buildEffectStrip() {
        rvEffects.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvEffects.setAdapter(new EffectStripAdapter());
    }

    private void buildStickerStrip() {
        rvStickers.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvStickers.setAdapter(new StickerStripAdapter());
    }

    private void setupSliders() {
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (sb == sbBrightness) brightness  = (p - 100) / 100f * 80f;
                if (sb == sbContrast)   contrast    = p / 100f;
                if (sb == sbSaturation) saturation  = p / 100f;
                if (sb == sbBeauty) {
                    beautyLevel = p / 100f;
                    if (tvBeautyLabel != null)
                        tvBeautyLabel.setText("Beauty: " + p + "%");
                }
                applyColorMatrix();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        sbBrightness.setOnSeekBarChangeListener(listener);
        sbContrast.setOnSeekBarChangeListener(listener);
        sbSaturation.setOnSeekBarChangeListener(listener);
        sbBeauty.setOnSeekBarChangeListener(listener);
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnReset.setOnClickListener(v -> {
            selectedEffect  = "Normal";
            selectedSticker = "";
            brightness  = 0f; contrast = 1f; saturation = 1f; beautyLevel = 0f;
            sbBrightness.setProgress(100);
            sbContrast.setProgress(100);
            sbSaturation.setProgress(100);
            sbBeauty.setProgress(0);
            tvEffectName.setText("Normal");
            applyColorMatrix();
            Toast.makeText(this, "Reset to default", Toast.LENGTH_SHORT).show();
        });

        btnApply.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(RESULT_EFFECT_NAME,  selectedEffect);
            result.putExtra(RESULT_BRIGHTNESS,   brightness);
            result.putExtra(RESULT_CONTRAST,     contrast);
            result.putExtra(RESULT_SATURATION,   saturation);
            result.putExtra(RESULT_BEAUTY_LEVEL, beautyLevel);
            result.putExtra(RESULT_STICKER_ID,   selectedSticker);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void applyPreset(String name) {
        selectedEffect = name;
        tvEffectName.setText(name);
        switch (name) {
            case "Vivid":     sbSaturation.setProgress(165); sbContrast.setProgress(110); break;
            case "Fade":      sbContrast.setProgress(72);    sbSaturation.setProgress(78); break;
            case "Warm":      sbSaturation.setProgress(120); sbBrightness.setProgress(108); break;
            case "Cool":      sbSaturation.setProgress(105); sbBrightness.setProgress(93); break;
            case "Drama":     sbContrast.setProgress(145);   sbSaturation.setProgress(85); break;
            case "Vintage":   sbContrast.setProgress(78);    sbSaturation.setProgress(65); break;
            case "Mono":      sbSaturation.setProgress(0);                                  break;
            case "Noir":      sbSaturation.setProgress(0);   sbContrast.setProgress(145);  break;
            case "Juno":      sbSaturation.setProgress(135); sbBrightness.setProgress(110); break;
            case "Lark":      sbBrightness.setProgress(118); sbContrast.setProgress(88);   break;
            case "Clarendon": sbContrast.setProgress(132);   sbSaturation.setProgress(132); break;
            case "Gingham":   sbSaturation.setProgress(85);  sbContrast.setProgress(92);   break;
            case "Moon":      sbSaturation.setProgress(0);   sbBrightness.setProgress(112); break;
            case "Ludwig":    sbContrast.setProgress(108);   sbSaturation.setProgress(115); break;
            case "Aden":      sbSaturation.setProgress(88);  sbBrightness.setProgress(105); break;
            default:
                sbBrightness.setProgress(100);
                sbContrast.setProgress(100);
                sbSaturation.setProgress(100);
                break;
        }
        applyColorMatrix();
    }

    private void applyColorMatrix() {
        if (ivLivePreview == null) return;
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(saturation);
        ColorMatrix bright = new ColorMatrix(new float[]{
            contrast, 0, 0, 0, brightness,
            0, contrast, 0, 0, brightness,
            0, 0, contrast, 0, brightness,
            0, 0, 0, 1, 0
        });
        cm.postConcat(bright);
        ivLivePreview.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    // ── Effect strip adapter ──────────────────────────────────────────────

    private class EffectStripAdapter extends RecyclerView.Adapter<EffectStripAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_effect_chip, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            String name = EFFECT_NAMES[pos];
            h.tvName.setText(name);
            h.vSelected.setVisibility(name.equals(selectedEffect) ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                applyPreset(name);
                notifyDataSetChanged();
            });
        }

        @Override public int getItemCount() { return EFFECT_NAMES.length; }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName;
            View     vSelected;
            VH(View v) {
                super(v);
                tvName    = v.findViewById(R.id.tv_effect_chip_name);
                vSelected = v.findViewById(R.id.v_effect_selected);
            }
        }
    }

    // ── Sticker strip adapter ─────────────────────────────────────────────

    private class StickerStripAdapter extends RecyclerView.Adapter<StickerStripAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            int size = dpToPx(72);
            RecyclerView.LayoutParams lp =
                new RecyclerView.LayoutParams(size, size);
            lp.setMarginEnd(dpToPx(8));
            tv.setLayoutParams(lp);
            tv.setTextSize(32);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setBackgroundResource(R.drawable.bg_speed_chip);
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tv.setText(STICKER_EMOJIS[pos]);
            boolean sel = STICKER_IDS[pos].equals(selectedSticker);
            h.tv.setAlpha(sel ? 1.0f : 0.6f);
            h.tv.setScaleX(sel ? 1.2f : 1.0f);
            h.tv.setScaleY(sel ? 1.2f : 1.0f);
            h.tv.setOnClickListener(v -> {
                if (STICKER_IDS[pos].equals(selectedSticker)) {
                    selectedSticker = "";
                    Toast.makeText(ReelEffectsActivity.this, "Sticker removed",
                        Toast.LENGTH_SHORT).show();
                } else {
                    selectedSticker = STICKER_IDS[pos];
                    Toast.makeText(ReelEffectsActivity.this,
                        "Sticker: " + STICKER_EMOJIS[pos], Toast.LENGTH_SHORT).show();
                }
                notifyDataSetChanged();
            });
        }

        @Override public int getItemCount() { return STICKER_EMOJIS.length; }

        class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(TextView v) { super(v); tv = v; }
        }
    }

    private int dpToPx(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }
}
