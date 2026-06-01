package com.callx.app.activities;

import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import java.io.*;
import java.util.*;

/**
 * StatusCollageCreatorActivity v26 — Combine 2/4/6 photos into one status collage.
 * Layouts: 2-horizontal, 2-vertical, 4-grid, 1-top-2-bottom, etc.
 * Returns: EXTRA_COLLAGE_PATH pointing to merged Bitmap PNG.
 */
public class StatusCollageCreatorActivity extends AppCompatActivity {
    public static final String EXTRA_COLLAGE_PATH = "collage_path";
    public static final String[] LAYOUTS = {"2 Side by Side","2 Top Bottom","4 Grid","1 Big + 2 Small"};
    private final List<Uri>   selectedUris = new ArrayList<>();
    private final List<ImageView> slots = new ArrayList<>();
    private int selectedLayout = 0;
    private GridLayout previewGrid;

    private final ActivityResultLauncher<String> pickerLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && selectedUris.size() < 6) {
                selectedUris.add(uri); refreshGrid();
            }
        });

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK); root.setPadding(dp(16),dp(8),dp(16),dp(24));

        // Title
        TextView title = new TextView(this); title.setText("🖼 Photo Collage");
        title.setTextColor(Color.WHITE); title.setTextSize(18); title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        // Layout selector
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout layoutRow = new LinearLayout(this); layoutRow.setOrientation(LinearLayout.HORIZONTAL);
        layoutRow.setPadding(0,dp(8),0,dp(8));
        for (int i = 0; i < LAYOUTS.length; i++) {
            final int idx = i;
            Button btn = new Button(this); btn.setText(LAYOUTS[i]); btn.setTextSize(11);
            btn.setOnClickListener(v -> { selectedLayout = idx; refreshLayoutSelector(layoutRow, idx); refreshGrid(); });
            layoutRow.addView(btn);
        }
        hsv.addView(layoutRow); root.addView(hsv);

        // Preview grid
        previewGrid = new GridLayout(this);
        previewGrid.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));
        root.addView(previewGrid);

        // Add photo button
        Button btnAdd = new Button(this); btnAdd.setText("+ Add Photo");
        btnAdd.setOnClickListener(v -> pickerLauncher.launch("image/*"));
        root.addView(btnAdd);

        // Create collage
        Button btnCreate = new Button(this); btnCreate.setText("✅ Create Collage");
        btnCreate.setBackgroundColor(Color.parseColor("#6200EE")); btnCreate.setTextColor(Color.WHITE);
        btnCreate.setOnClickListener(v -> { if (selectedUris.size() < 2) { Toast.makeText(this,"Pick at least 2 photos",Toast.LENGTH_SHORT).show(); return; } createCollage(); });
        root.addView(btnCreate);

        ScrollView sv = new ScrollView(this); sv.addView(root);
        setContentView(sv);
        refreshGrid();
    }

    private void refreshGrid() {
        previewGrid.removeAllViews(); slots.clear();
        int cols = selectedLayout == 0 ? 2 : selectedLayout == 1 ? 1 : selectedLayout == 2 ? 2 : 2;
        previewGrid.setColumnCount(cols);
        int needed = selectedLayout == 2 ? 4 : selectedLayout == 3 ? 3 : 2;
        for (int i = 0; i < needed; i++) {
            ImageView iv = new ImageView(this);
            int sz = dp(selectedLayout == 1 ? 340 : 170); iv.setLayoutParams(new ViewGroup.LayoutParams(sz, sz));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Color.DKGRAY);
            if (i < selectedUris.size()) Glide.with(this).load(selectedUris.get(i)).centerCrop().into(iv);
            final int idx = i;
            iv.setOnClickListener(v -> { if (idx < selectedUris.size()) selectedUris.remove(idx); else pickerLauncher.launch("image/*"); refreshGrid(); });
            slots.add(iv); previewGrid.addView(iv);
        }
    }

    private void refreshLayoutSelector(LinearLayout row, int selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            v.setBackgroundColor(i == selected ? Color.parseColor("#6200EE") : Color.TRANSPARENT);
        }
    }

    private void createCollage() {
        new Thread(() -> {
            try {
                int W = 1080, H = 1080;
                Bitmap result = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(result); c.drawColor(Color.BLACK);
                List<Bitmap> bmps = new ArrayList<>();
                for (Uri uri : selectedUris) {
                    Bitmap bmp = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
                    bmps.add(Bitmap.createScaledBitmap(bmp, W, H, true));
                }
                drawCollage(c, bmps, W, H);
                File out = new File(getCacheDir(), "collage_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) { result.compress(Bitmap.CompressFormat.JPEG, 90, fos); }
                runOnUiThread(() -> {
                    Intent res = new Intent(); res.putExtra(EXTRA_COLLAGE_PATH, out.getAbsolutePath());
                    setResult(RESULT_OK, res); finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,"Error: "+e.getMessage(),Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void drawCollage(Canvas c, List<Bitmap> bmps, int W, int H) {
        if (bmps.isEmpty()) return;
        switch (selectedLayout) {
            case 0: // 2 side by side
                if (bmps.size()>=1) c.drawBitmap(crop(bmps.get(0),W/2,H), 0, 0, null);
                if (bmps.size()>=2) c.drawBitmap(crop(bmps.get(1),W/2,H), W/2, 0, null);
                break;
            case 1: // 2 top-bottom
                if (bmps.size()>=1) c.drawBitmap(crop(bmps.get(0),W,H/2), 0, 0, null);
                if (bmps.size()>=2) c.drawBitmap(crop(bmps.get(1),W,H/2), 0, H/2, null);
                break;
            case 2: // 4 grid
                if (bmps.size()>=1) c.drawBitmap(crop(bmps.get(0),W/2,H/2), 0,   0,   null);
                if (bmps.size()>=2) c.drawBitmap(crop(bmps.get(1),W/2,H/2), W/2, 0,   null);
                if (bmps.size()>=3) c.drawBitmap(crop(bmps.get(2),W/2,H/2), 0,   H/2, null);
                if (bmps.size()>=4) c.drawBitmap(crop(bmps.get(3),W/2,H/2), W/2, H/2, null);
                break;
            case 3: // 1 big + 2 small
                if (bmps.size()>=1) c.drawBitmap(crop(bmps.get(0),W*2/3,H), 0, 0, null);
                if (bmps.size()>=2) c.drawBitmap(crop(bmps.get(1),W/3,H/2), W*2/3, 0,   null);
                if (bmps.size()>=3) c.drawBitmap(crop(bmps.get(2),W/3,H/2), W*2/3, H/2, null);
                break;
        }
    }

    private Bitmap crop(Bitmap src, int w, int h) {
        float scale = Math.max((float)w/src.getWidth(), (float)h/src.getHeight());
        int sw=(int)(src.getWidth()*scale), sh=(int)(src.getHeight()*scale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
        int x = (sw-w)/2, y = (sh-h)/2;
        return Bitmap.createBitmap(scaled, Math.max(0,x), Math.max(0,y), Math.min(w,sw), Math.min(h,sh));
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
