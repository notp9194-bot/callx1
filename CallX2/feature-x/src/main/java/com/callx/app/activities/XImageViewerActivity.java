package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.x.R;

public class XImageViewerActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_image_viewer);

        String imageUrl = getIntent().getStringExtra("image_url");
        ImageView ivFull = findViewById(R.id.iv_x_image_full);
        if (imageUrl != null && ivFull != null) {
            Glide.with(this).load(imageUrl).into(ivFull);
        }
        // Tap anywhere to close
        ivFull.setOnClickListener(v -> finish());
        View btnClose = findViewById(R.id.btn_x_viewer_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());
    }
}
