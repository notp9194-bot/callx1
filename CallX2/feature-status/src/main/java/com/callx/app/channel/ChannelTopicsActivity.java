package com.callx.app.channel;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.*;
import com.google.android.material.textfield.TextInputEditText;
import java.util.ArrayList;
import java.util.List;

/**
 * ChannelTopicsActivity — Manage topic tags for a channel.
 *
 * Admins/owners can:
 *   - View the current topic tag list
 *   - Add new topic tags (max 10 tags, 30 chars each)
 *   - Remove tags by tapping the close icon on each chip
 *   - Save the updated tag list to Firebase via ChannelViewModel
 *
 * Topic tags appear as filter chips on the ChannelViewerActivity feed.
 */
public class ChannelTopicsActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private static final int MAX_TAGS     = 10;
    private static final int MAX_TAG_LEN  = 30;

    private ChannelViewModel  viewModel;
    private String            channelId;
    private final List<String> currentTags = new ArrayList<>();

    private ChipGroup         chipGroup;
    private TextInputEditText etNewTag;
    private MaterialButton    btnAddTag, btnSave;
    private TextView          tvTagCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_topics);

        channelId = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_topics);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Topic tags");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        chipGroup  = findViewById(R.id.chip_group_topics);
        etNewTag   = findViewById(R.id.et_new_topic_tag);
        btnAddTag  = findViewById(R.id.btn_add_topic_tag);
        btnSave    = findViewById(R.id.btn_save_topics);
        tvTagCount = findViewById(R.id.tv_topic_tag_count);

        if (btnAddTag != null) {
            btnAddTag.setOnClickListener(v -> {
                String tag = etNewTag != null && etNewTag.getText() != null
                    ? etNewTag.getText().toString().trim().toLowerCase() : "";
                if (tag.isEmpty()) return;
                if (tag.length() > MAX_TAG_LEN) {
                    Toast.makeText(this, "Tag max " + MAX_TAG_LEN + " chars.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (currentTags.size() >= MAX_TAGS) {
                    Toast.makeText(this, "Max " + MAX_TAGS + " tags.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!currentTags.contains(tag)) {
                    currentTags.add(tag);
                    addChip(tag);
                    updateTagCount();
                }
                if (etNewTag != null) etNewTag.setText("");
            });
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                viewModel.setTopicTags(channelId, new ArrayList<>(currentTags));
                finish();
            });
        }

        // Load existing tags from channel entity
        viewModel.getChannel(channelId).observe(this, ch -> {
            if (ch == null) return;
            currentTags.clear();
            if (chipGroup != null) chipGroup.removeAllViews();
            if (ch.topicTags != null) {
                for (String tag : ch.topicTags) {
                    currentTags.add(tag);
                    addChip(tag);
                }
            }
            updateTagCount();
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    private void addChip(String tag) {
        if (chipGroup == null) return;
        Chip chip = new Chip(this);
        chip.setText("#" + tag);
        chip.setCloseIconVisible(true);
        chip.setOnCloseIconClickListener(v -> {
            currentTags.remove(tag);
            chipGroup.removeView(chip);
            updateTagCount();
        });
        chipGroup.addView(chip);
    }

    private void updateTagCount() {
        if (tvTagCount != null) {
            tvTagCount.setText(currentTags.size() + " / " + MAX_TAGS + " topics");
        }
        if (btnAddTag != null) btnAddTag.setEnabled(currentTags.size() < MAX_TAGS);
    }
}
