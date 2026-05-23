package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.x.R;

/**
 * XImageViewerActivity — Tweet images full-screen viewer.
 *
 * Fix: Glide ab DiskCacheStrategy.ALL use karta hai.
 *   ✅ Pehli baar: network se load + RAM + disk dono mein cache
 *   ✅ Dobaara open karo → disk cache se instant load, zero data use
 *   ✅ Feed mein jo thumbnail already load ho chuki thi, woh bhi cache mein hoti hai
 */
public class XImageViewerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_image_viewer);

        String imageUrl = getIntent().getStringExtra("image_url");
        ImageView ivFull = findViewById(R.id.iv_x_image_full);

        if (imageUrl != null && ivFull != null) {
            Glide.with(this)
                .load(imageUrl)
                .apply(new RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)  // RAM + disk cache
                    .centerInside())
                .into(ivFull);
        }

        if (ivFull != null) ivFull.setOnClickListener(v -> finish());
        View btnClose = findViewById(R.id.btn_x_viewer_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());
    }
}
