package com.callx.app.settings;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.media.crop.MediaCropActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * ReelWatermarkSettingsActivity — Add / manage branded watermark overlay on reels.
 *
 * Features:
 *  ✅ Toggle watermark on/off per reel (default on for creators)
 *  ✅ Watermark style: Username / Custom text / Logo image
 *  ✅ Logo can be picked straight from the gallery and cropped — reuses
 *     :core's shared {@link MediaCropActivity} (same WhatsApp-grade crop
 *     screen chat/status/reel-editor use), then the cropped image is
 *     uploaded via {@link CloudinaryUploader} and the returned hosted URL
 *     is written into et_wm_logo_url — so the existing logoUrl storage
 *     schema, live preview, and ReelVideoExportEngine bake path all keep
 *     working completely unchanged. Pasting a URL by hand still works too.
 *  ✅ Position: Top-Left / Top-Right / Bottom-Left / Bottom-Right / Center — corner choices
 *     are the STARTING spot only; the baked export then jitters the watermark between
 *     corners over time (Instagram/TikTok-style) so a single crop can't remove it. Center
 *     stays fixed, since a corner crop can't touch it anyway.
 *  ✅ Opacity slider (10 – 100%)
 *  ✅ Font size slider (10sp – 32sp)
 *  ✅ Text color picker (white / black / pink / custom hex)
 *  ✅ Preview card — real live overlay render (actual text/color/size/opacity/
 *     position, or the actual fetched logo image), not just a text summary
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
    private TextView    tvPreview, tvPreviewOff, tvPreviewCaption;
    private ImageView   ivPreview;
    private Button      btnSave;
    private LinearLayout layoutTextOptions, layoutLogoOptions;
    private ProgressBar progress;

    // ✅ NEW: gallery pick + crop + upload for the logo watermark
    private ImageView   ivLogoPickPreview;
    private Button      btnPickLogo;
    private ProgressBar progressLogoUpload;
    private ActivityResultLauncher<String> galleryPicker;
    private ActivityResultLauncher<Intent> cropLauncher;

    // ✅ NEW: per-platform (Feed/Reels vs Stories) variant
    private Switch       swStoryCustomize;
    private LinearLayout layoutPlatformTabs;
    private Button       btnTabFeed, btnTabStory;
    private TextView     tvPreviewSurface;
    /** Which variant the shared position/opacity/font-size widgets currently reflect. */
    private boolean editingStoryTab = false;
    /** Feed/Reels values — always the "base" values (backward-compatible top-level fields). */
    private String feedPosition = "Bottom Right";
    private int feedOpacity = 80, feedFontSize = 16;
    /** Stories-only override values — only saved/used when swStoryCustomize is checked. */
    private String storyPosition = "Bottom Right";
    private int storyOpacity = 80, storyFontSize = 16;

    private String myUid;
    private DatabaseReference wmRef;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_reel_watermark_settings);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        wmRef = FirebaseUtils.getUserRef(myUid).child("watermarkSettings");
        registerLogoPickLaunchers();
        bindViews();
        loadSettings();
    }

    // ── Gallery pick → crop → upload ────────────────────────────────────
    // Must be registered before onStart, so this runs from onCreate ahead
    // of bindViews (which wires the button's click listener).

    private void registerLogoPickLaunchers() {
        galleryPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) openLogoCropScreen(uri);
            });
        cropLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String uriStr = result.getData().getStringExtra(MediaCropActivity.RESULT_CROPPED_URI);
                    if (uriStr != null) uploadPickedLogo(Uri.parse(uriStr));
                }
            });
    }

    private void openLogoCropScreen(Uri sourceUri) {
        // Reuses core's shared MediaCropActivity (same crop screen chat /
        // status / reel-editor use) via className Intent, same pattern as
        // ReelPhotoEditorActivity — no compile dependency needed beyond :core.
        Intent i = new Intent();
        i.setClassName(getPackageName(), "com.callx.app.media.crop.MediaCropActivity");
        i.putExtra(MediaCropActivity.EXTRA_IMAGE_URI, sourceUri.toString());
        cropLauncher.launch(i);
    }

    private void uploadPickedLogo(Uri croppedUri) {
        // Show the cropped image immediately (no wait for upload) so the
        // creator gets instant feedback that the pick worked.
        ivLogoPickPreview.setAlpha(1f);
        com.bumptech.glide.Glide.with(this).load(croppedUri).into(ivLogoPickPreview);
        progressLogoUpload.setVisibility(View.VISIBLE);
        CloudinaryUploader.upload(this, croppedUri, "watermark_logos", "image",
            new CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(CloudinaryUploader.Result result) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        progressLogoUpload.setVisibility(View.GONE);
                        // Feeds the existing logoUrl flow untouched: TextWatcher
                        // on etLogoUrl already drives updatePreview() and
                        // saveSettings() already reads etLogoUrl.
                        etLogoUrl.setText(result.secureUrl);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        progressLogoUpload.setVisibility(View.GONE);
                        Toast.makeText(ReelWatermarkSettingsActivity.this,
                            "Logo upload failed: " + message, Toast.LENGTH_SHORT).show();
                    });
                }
            });
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
        tvPreviewOff      = findViewById(R.id.tv_wm_preview_off);
        tvPreviewCaption  = findViewById(R.id.tv_wm_preview_caption);
        ivPreview         = findViewById(R.id.iv_wm_preview);
        btnSave           = findViewById(R.id.btn_wm_save);
        layoutTextOptions = findViewById(R.id.layout_wm_text_opts);
        layoutLogoOptions = findViewById(R.id.layout_wm_logo_opts);
        progress          = findViewById(R.id.progress_wm);
        ivLogoPickPreview = findViewById(R.id.iv_wm_logo_pick_preview);
        btnPickLogo       = findViewById(R.id.btn_wm_pick_logo);
        progressLogoUpload= findViewById(R.id.progress_wm_logo_upload);
        swStoryCustomize  = findViewById(R.id.sw_wm_story_customize);
        layoutPlatformTabs= findViewById(R.id.ll_wm_platform_tabs);
        btnTabFeed        = findViewById(R.id.btn_wm_tab_feed);
        btnTabStory       = findViewById(R.id.btn_wm_tab_story);
        tvPreviewSurface  = findViewById(R.id.tv_wm_preview_surface);

        btnBack.setOnClickListener(v -> finish());
        btnPickLogo.setOnClickListener(v -> galleryPicker.launch("image/*"));

        swStoryCustomize.setOnCheckedChangeListener((b, checked) -> {
            layoutPlatformTabs.setVisibility(checked ? View.VISIBLE : View.GONE);
            tvPreviewSurface.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked && storyFontSize == feedFontSize && storyPosition.equals(feedPosition)
                    && storyOpacity == feedOpacity) {
                // First time customizing — seed Stories with a smaller mark
                // (Instagram-style) rather than an identical copy the creator
                // would have to shrink themselves.
                storyFontSize = Math.max(10, feedFontSize - 4);
            }
            if (!checked) {
                // Turning it off collapses back to a single (Feed) setting —
                // stop editing the Story tab if that's what was showing.
                if (editingStoryTab) selectPlatformTab(false);
            }
        });
        btnTabFeed.setOnClickListener(v -> selectPlatformTab(false));
        btnTabStory.setOnClickListener(v -> selectPlatformTab(true));

        spPosition.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, POSITIONS));
        spColor.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, COLOR_NAMES));
        // ✅ FIX: position/color pickers never refreshed the preview before —
        // only opacity/font-size/type/enabled/custom-text did. A real overlay
        // preview needs to react to EVERY control that changes how/where it
        // renders, so both spinners are now wired up too.
        AdapterView.OnItemSelectedListener onSpinnerPick = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { updatePreview(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        spPosition.setOnItemSelectedListener(onSpinnerPick);
        spColor.setOnItemSelectedListener(onSpinnerPick);

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

        // ✅ FIX: live-update as the creator TYPES, not just on focus-loss —
        // and etLogoUrl now drives the preview too (it never did before).
        android.text.TextWatcher onTextChange = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { updatePreview(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        };
        etCustomText.addTextChangedListener(onTextChange);
        etLogoUrl.addTextChangedListener(onTextChange);
        btnSave.setOnClickListener(v -> saveSettings());

        updateTypeVisibility();
        updatePreview();
    }

    // ── Feed/Reels ↔ Stories tab switching ──────────────────────────────
    // spPosition/sbOpacity/sbFontSize are shared widgets — switching tabs
    // saves their current values into whichever variant was being edited,
    // then loads the newly-selected variant's values into the same widgets.

    private void selectPlatformTab(boolean story) {
        if (story == editingStoryTab) return;
        captureCurrentTabValues();
        editingStoryTab = story;
        btnTabFeed.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(story ? "#2A2A2A" : "#FF3B5C")));
        btnTabStory.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(story ? "#FF3B5C" : "#2A2A2A")));
        tvPreviewSurface.setText(story ? "Previewing: Stories" : "Previewing: Feed / Reels");
        loadCurrentTabValues();
        updatePreview();
    }

    private void captureCurrentTabValues() {
        String pos = POSITIONS[spPosition.getSelectedItemPosition()];
        int opacity = sbOpacity.getProgress() + 10;
        int fontSize = sbFontSize.getProgress() + 10;
        if (editingStoryTab) { storyPosition = pos; storyOpacity = opacity; storyFontSize = fontSize; }
        else                 { feedPosition  = pos; feedOpacity  = opacity; feedFontSize  = fontSize; }
    }

    private void loadCurrentTabValues() {
        String pos = editingStoryTab ? storyPosition : feedPosition;
        int opacity = editingStoryTab ? storyOpacity : feedOpacity;
        int fontSize = editingStoryTab ? storyFontSize : feedFontSize;
        for (int i = 0; i < POSITIONS.length; i++) if (POSITIONS[i].equals(pos)) { spPosition.setSelection(i); break; }
        sbOpacity.setProgress(opacity - 10);
        sbFontSize.setProgress(fontSize - 10);
        tvOpacityVal.setText(opacity + "%");
        tvFontSizeVal.setText(fontSize + "sp");
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

    /**
     * ✅ Real visual overlay preview (upgrade — replaces the old bracketed
     * text-summary, e.g. "@name [Bottom Right, 90%, 16sp]"): renders the
     * ACTUAL watermark — real text/color/size/opacity via tv_wm_preview at
     * its real corner gravity, or the ACTUAL fetched logo image via
     * iv_wm_preview — laid out exactly like tv_watermark_overlay /
     * iv_watermark_overlay do on a real reel in ReelPlayerFragment. Text and
     * logo remain mutually exclusive (Instagram-level rule — see point 4):
     * exactly one of tvPreview/ivPreview is ever visible at a time, and
     * neither is ever a circular avatar+username combo badge.
     */
    private void updatePreview() {
        if (!swWatermarkEnabled.isChecked()) {
            tvPreview.setVisibility(View.GONE);
            ivPreview.setVisibility(View.GONE);
            tvPreviewOff.setVisibility(View.VISIBLE);
            tvPreviewCaption.setText("Turn the watermark on to see a live preview.");
            return;
        }
        tvPreviewOff.setVisibility(View.GONE);

        int id = rgWatermarkType.getCheckedRadioButtonId();
        int opacity = sbOpacity.getProgress() + 10;
        int fontSize = sbFontSize.getProgress() + 10;
        float alpha = opacity / 100f;
        String posLabel = POSITIONS[spPosition.getSelectedItemPosition()];
        int gravity = gravityForPosition(posLabel);

        if (id == R.id.rb_wm_logo) {
            tvPreview.setVisibility(View.GONE);
            String logoUrl = etLogoUrl.getText() != null ? etLogoUrl.getText().toString().trim() : "";
            if (logoUrl.isEmpty()) {
                ivPreview.setVisibility(View.GONE);
                tvPreviewCaption.setText("Paste a logo image URL above to preview it here.");
                return;
            }
            setPreviewGravity(ivPreview, gravity);
            ivPreview.setAlpha(alpha);
            ivPreview.setVisibility(View.VISIBLE);
            com.bumptech.glide.Glide.with(ivPreview).load(logoUrl).into(ivPreview);
        } else {
            ivPreview.setVisibility(View.GONE);
            String text;
            if (id == R.id.rb_wm_custom_text) {
                text = etCustomText.getText() != null ? etCustomText.getText().toString() : "";
                if (text.isEmpty()) text = "@" + FirebaseUtils.getCurrentName();
            } else {
                text = "@" + FirebaseUtils.getCurrentName();
            }
            tvPreview.setText(text);
            tvPreview.setTextSize(fontSize);
            tvPreview.setAlpha(alpha);
            tvPreview.setTextColor(android.graphics.Color.parseColor(COLORS[spColor.getSelectedItemPosition()]));
            setPreviewGravity(tvPreview, gravity);
            tvPreview.setVisibility(View.VISIBLE);
        }
        String moveHint = "Center".equals(posLabel) ? "" : " — moves between corners while playing (crop-resist)";
        tvPreviewCaption.setText(posLabel + " • " + opacity + "% opacity" + moveHint);
    }

    private int gravityForPosition(@Nullable String position) {
        if (position == null) return Gravity.BOTTOM | Gravity.END;
        switch (position) {
            case "Top Left":     return Gravity.TOP | Gravity.START;
            case "Top Right":    return Gravity.TOP | Gravity.END;
            case "Bottom Left":  return Gravity.BOTTOM | Gravity.START;
            case "Center":       return Gravity.CENTER;
            case "Bottom Right":
            default:              return Gravity.BOTTOM | Gravity.END;
        }
    }

    private void setPreviewGravity(View v, int gravity) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) lp).gravity = gravity;
            v.setLayoutParams(lp);
        }
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
                if (lu != null && !lu.isEmpty()) {
                    etLogoUrl.setText(lu);
                    ivLogoPickPreview.setAlpha(1f);
                    com.bumptech.glide.Glide.with(ReelWatermarkSettingsActivity.this).load(lu).into(ivLogoPickPreview);
                }
                String pos = snap.child("position").getValue(String.class);
                for (int i = 0; i < POSITIONS.length; i++) if (POSITIONS[i].equals(pos)) { spPosition.setSelection(i); break; }
                Long op = snap.child("opacity").getValue(Long.class);
                if (op != null) sbOpacity.setProgress((int)(op - 10));
                Long fs = snap.child("fontSize").getValue(Long.class);
                if (fs != null) sbFontSize.setProgress((int)(fs - 10));
                String col = snap.child("color").getValue(String.class);
                for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equals(col)) { spColor.setSelection(i); break; }

                // ✅ NEW: capture the just-loaded values as the Feed/Reels
                // variant, then load any saved Stories override on top.
                feedPosition = pos != null ? pos : POSITIONS[spPosition.getSelectedItemPosition()];
                feedOpacity = (op != null ? op.intValue() : sbOpacity.getProgress() + 10);
                feedFontSize = (fs != null ? fs.intValue() : sbFontSize.getProgress() + 10);
                storyPosition = feedPosition; storyOpacity = feedOpacity; storyFontSize = feedFontSize;

                DataSnapshot storySnap = snap.child("story");
                Boolean storyCustomized = storySnap.child("customized").getValue(Boolean.class);
                boolean customized = Boolean.TRUE.equals(storyCustomized);
                swStoryCustomize.setChecked(customized);
                layoutPlatformTabs.setVisibility(customized ? View.VISIBLE : View.GONE);
                tvPreviewSurface.setVisibility(customized ? View.VISIBLE : View.GONE);
                if (customized) {
                    String sPos = storySnap.child("position").getValue(String.class);
                    Long sOp = storySnap.child("opacity").getValue(Long.class);
                    Long sFs = storySnap.child("fontSize").getValue(Long.class);
                    if (sPos != null) storyPosition = sPos;
                    if (sOp != null) storyOpacity = sOp.intValue();
                    if (sFs != null) storyFontSize = sFs.intValue();
                }
                editingStoryTab = false; // always resume on the Feed/Reels tab

                updateTypeVisibility();
                updatePreview();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isFinishing()) progress.setVisibility(View.GONE);
            }
        });
    }

    private void saveSettings() {
        captureCurrentTabValues(); // flush whichever tab (Feed/Story) is currently on-screen
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", swWatermarkEnabled.isChecked());
        int id = rgWatermarkType.getCheckedRadioButtonId();
        if (id == R.id.rb_wm_custom_text) m.put("type", "custom_text");
        else if (id == R.id.rb_wm_logo)   m.put("type", "logo");
        else                               m.put("type", "username");
        m.put("customText", etCustomText.getText() != null ? etCustomText.getText().toString() : "");
        m.put("logoUrl",    etLogoUrl.getText()    != null ? etLogoUrl.getText().toString()    : "");
        m.put("position",   feedPosition);
        m.put("opacity",    feedOpacity);
        m.put("fontSize",   feedFontSize);
        m.put("color",      COLORS[spColor.getSelectedItemPosition()]);
        m.put("updatedAt",  System.currentTimeMillis());
        // ✅ NEW: Stories-specific size/position, only when the creator opted in —
        // null clears any previously-saved override (Firebase removes that child).
        if (swStoryCustomize.isChecked()) {
            Map<String, Object> story = new HashMap<>();
            story.put("customized", true);
            story.put("position",   storyPosition);
            story.put("opacity",    storyOpacity);
            story.put("fontSize",   storyFontSize);
            m.put("story", story);
        } else {
            m.put("story", null);
        }
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
