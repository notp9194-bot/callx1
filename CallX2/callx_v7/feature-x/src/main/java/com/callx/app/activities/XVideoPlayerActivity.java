package com.callx.app.activities;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.MediaController;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.x.R;

public class XVideoPlayerActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_video_player);

        String videoUrl = getIntent().getStringExtra("video_url");
        VideoView vv = findViewById(R.id.vv_x_player);
        if (videoUrl != null && vv != null) {
            MediaController mc = new MediaController(this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            vv.setVideoURI(Uri.parse(videoUrl));
            vv.requestFocus();
            vv.start();
        }
        View btnClose = findViewById(R.id.btn_x_player_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());
    }
}
