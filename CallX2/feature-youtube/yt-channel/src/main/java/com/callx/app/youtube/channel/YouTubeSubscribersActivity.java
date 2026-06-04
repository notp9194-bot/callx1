package com.callx.app.youtube.channel;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.youtube.channel.R;

public class YouTubeSubscribersActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_liked);
        View btnBack = findViewById(R.id.btn_yt_liked_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
    }
}
