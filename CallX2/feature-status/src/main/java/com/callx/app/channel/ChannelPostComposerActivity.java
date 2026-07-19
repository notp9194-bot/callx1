package com.callx.app.channel;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;

/**
 * ChannelPostComposerActivity — minimal text-post composer for a channel.
 *
 * Opened from ChannelViewerActivity's "New Post" FAB (owner only).
 * On success, finishes; the caller's Room-backed LiveData picks the new
 * post up automatically once ChannelRepository writes it — no result
 * code needed.
 */
public class ChannelPostComposerActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private ChannelViewModel viewModel;
    private String channelId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_post_composer);

        channelId = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        String channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);

        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_post_composer);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(
                    channelName != null ? channelName : "New post");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        EditText etText = findViewById(R.id.et_post_text);
        MaterialButton btnPost = findViewById(R.id.btn_post_submit);

        btnPost.setOnClickListener(v -> {
            String text = etText.getText() != null ? etText.getText().toString() : "";
            if (TextUtils.isEmpty(text.trim())) {
                Toast.makeText(this, "Write something first", Toast.LENGTH_SHORT).show();
                return;
            }
            btnPost.setEnabled(false);
            viewModel.createPost(channelId, text);
        });

        viewModel.loading.observe(this, loading -> {
            if (loading != null) btnPost.setEnabled(!loading);
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg == null || msg.isEmpty()) return;
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            if ("Posted".equals(msg)) finish();
        });
    }
}
