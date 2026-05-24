package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.youtube.R;

public class YouTubeSubscribersActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_liked);
        View btnBack = findViewById(R.id.btn_yt_liked_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
    }
}
