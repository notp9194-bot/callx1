package com.callx.app.activities;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ReelWatermarkSettingsActivity — Add / manage branded watermark overlay on reels.
 *
 * Features:
 *  ✅ Toggle watermark on/off per reel (default on for creators)
 *  ✅ Watermark style: Username / Custom text / Logo image URL
 *  ✅ Position: Top-Left / Top-Right / Bottom-Left / Bottom-Right / Center
 *  ✅ Opacity slider (10 – 100%)
 *  ✅ Font size slider (10sp – 32sp)
 *  ✅ Text color picker (white / black / pink / custom hex)
 *  ✅ Preview card showing watermark placement
 *  ✅ Settings saved to users/{uid}/watermarkSettings in Firebase
 */
public class ReelWatermarkSettingsActivity extends AppCompatActivity {

    private static final String[] POSITIONS = {
        "Top Left", "Top Right", "Bottom Left", "Bottom Right", "Center"
    };
    private static final String[] COLORS = {"#FFFFFF", "#000000", "#FF3B5C", "#FFD700", "#00C8FF"};
    private static final String[] COLOR_NAMES = {"White", "Black", "Pink", "Gold", "Cyan"};

    private ImageButton btnBack;
    private Switch      swWatermarkEnabled;
    private RadioGroup  rgWatermarkType;
    private EditText    etCustomText, etLogoUrl;
    private Spinner     spPosition, spColor;
    private SeekBar     sbOpacity, sbFontSize;
    private TextView    tvOpacityVal, tvFontSizeVal;
    private TextView    tvPreview;
    private Button      btnSave;
    private LinearLayout layoutTextOptions, layoutLogoOptions;
    private ProgressBar progress;

    private String myUid;
    private DatabaseReference wmRef;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_watermark_settings);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        wmRef = FirebaseUtils.getUserRef(myUid).child("watermarkSettings");
        bindViews();
        loadSettings();
    }

    private void bindViews() {
        btnBack           = findViewById(R.id.btn_wm_back);
        swWatermarkEnabled= findViewById(R.id.sw_wm_enabled);
        rgWatermarkType   = findViewById(R.id.rg_wm_type);
        etCustomText      = findViewById(R.id.et_wm_custom_text);
        etLogoUrl         = findViewById(R.id.et_wm_logo_url);
        spPosition        = findViewById(R.id.sp_wm_position);
        spColor           = findViewById(R.id.sp_wm_color);
        sbOpacity         = findViewById(R.id.sb_wm_opacity);
        sbFontSize        = findViewById(R.id.sb_wm_font_size);
        tvOpacityVal      = findViewById(R.id.tv_wm_opacity_val);
        tvFontSizeVal     = findViewById(R.id.tv_wm_font_size_val);
        tvPreview         = findViewById(R.id.tv_wm_preview);
        btnSave           = findViewById(R.id.btn_wm_save);
        layoutTextOptions = findViewById(R.id.layout_wm_text_opts);
        layoutLogoOptions = findViewById(R.id.layout_wm_logo_opts);
        progress          = findViewById(R.id.progress_wm);

        btnBack.setOnClickListener(v -> finish());

        spPosition.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, POSITIONS));
        spColor.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, COLOR_NAMES));

        rgWatermarkType.setOnCheckedChangeListener((g, id) -> updateTypeVisibility());
        swWatermarkEnabled.setOnCheckedChangeListener((b, c) -> updatePreview());

        sbOpacity.setMax(90); sbOpacity.setProgress(80);
        sbOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvOpacityVal.setText((p + 10) + "%"); updatePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbFontSize.setMax(22); sbFontSize.setProgress(6);
        sbFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvFontSizeVal.setText((p + 10) + "sp"); updatePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        etCustomText.setOnFocusChangeListener((v, f) -> updatePreview());
        btnSave.setOnClickListener(v -> saveSettings());

        updateTypeVisibility();
        updatePreview();
    }

    private void updateTypeVisibility() {
        int id = rgWatermarkType.getCheckedRadioButtonId();
        if (id == R.id.rb_wm_username) {
            layoutTextOptions.setVisibility(View.GONE);
            layoutLogoOptions.setVisibility(View.GONE);
        } else if (id == R.id.rb_wm_custom_text) {
            layoutTextOptions.setVisibility(View.VISIBLE);
            layoutLogoOptions.setVisibility(View.GONE);
        } else {
            layoutTextOptions.setVisibility(View.GONE);
            layoutLogoOptions.setVisibility(View.VISIBLE);
        }
        updatePreview();
    }

    private void updatePreview() {
        String text;
        int id = rgWatermarkType.getCheckedRadioButtonId();
        if (id == R.id.rb_wm_username) text = "@" + FirebaseUtils.getCurrentName();
        else if (id == R.id.rb_wm_custom_text) text = etCustomText.getText() != null ? etCustomText.getText().toString() : "Custom Text";
        else text = "🖼 Logo";
        if (!swWatermarkEnabled.isChecked()) { tvPreview.setText("Watermark Off"); return; }
        int opacity = sbOpacity.getProgress() + 10;
        int fontSize = sbFontSize.getProgress() + 10;
        tvPreview.setText(text + " [" + POSITIONS[spPosition.getSelectedItemPosition()] + ", " + opacity + "%, " + fontSize + "sp]");
        tvPreview.setTextColor(android.graphics.Color.parseColor(COLORS[spColor.getSelectedItemPosition()]));
        tvPreview.setTextSize(fontSize);
        tvPreview.setAlpha((opacity) / 100f);
    }

    private void loadSettings() {
        progress.setVisibility(View.VISIBLE);
        wmRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                Boolean en = snap.child("enabled").getValue(Boolean.class);
                swWatermarkEnabled.setChecked(en == null || en);
                String type = snap.child("type").getValue(String.class);
                if ("custom_text".equals(type)) rgWatermarkType.check(R.id.rb_wm_custom_text);
                else if ("logo".equals(type)) rgWatermarkType.check(R.id.rb_wm_logo);
                else rgWatermarkType.check(R.id.rb_wm_username);
                String ct = snap.child("customText").getValue(String.class);
                if (ct != null) etCustomText.setText(ct);
                String lu = snap.child("logoUrl").getValue(String.class);
                if (lu != null) etLogoUrl.setText(lu);
                String pos = snap.child("position").getValue(String.class);
                for (int i = 0; i < POSITIONS.length; i++) if (POSITIONS[i].equals(pos)) { spPosition.setSelection(i); break; }
                Long op = snap.child("opacity").getValue(Long.class);
                if (op != null) sbOpacity.setProgress((int)(op - 10));
                Long fs = snap.child("fontSize").getValue(Long.class);
                if (fs != null) sbFontSize.setProgress((int)(fs - 10));
                String col = snap.child("color").getValue(String.class);
                for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equals(col)) { spColor.setSelection(i); break; }
                updateTypeVisibility();
                updatePreview();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isFinishing()) progress.setVisibility(View.GONE);
            }
        });
    }

    private void saveSettings() {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", swWatermarkEnabled.isChecked());
        int id = rgWatermarkType.getCheckedRadioButtonId();
        if (id == R.id.rb_wm_custom_text) m.put("type", "custom_text");
        else if (id == R.id.rb_wm_logo)   m.put("type", "logo");
        else                               m.put("type", "username");
        m.put("customText", etCustomText.getText() != null ? etCustomText.getText().toString() : "");
        m.put("logoUrl",    etLogoUrl.getText()    != null ? etLogoUrl.getText().toString()    : "");
        m.put("position",   POSITIONS[spPosition.getSelectedItemPosition()]);
        m.put("opacity",    sbOpacity.getProgress() + 10);
        m.put("fontSize",   sbFontSize.getProgress() + 10);
        m.put("color",      COLORS[spColor.getSelectedItemPosition()]);
        m.put("updatedAt",  System.currentTimeMillis());
        progress.setVisibility(View.VISIBLE);
        wmRef.updateChildren(m).addOnCompleteListener(t -> {
            if (!isFinishing()) {
                progress.setVisibility(View.GONE);
                Toast.makeText(this, t.isSuccessful() ? "Watermark settings saved!" : "Save failed", Toast.LENGTH_SHORT).show();
                if (t.isSuccessful()) finish();
            }
        });
    }
}
