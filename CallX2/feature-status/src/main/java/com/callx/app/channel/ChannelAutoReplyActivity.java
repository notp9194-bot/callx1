package com.callx.app.channel;

import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

/**
 * ChannelAutoReplyActivity — Set a welcome message automatically sent to new followers.
 *
 * WhatsApp-level feature:
 *   - Toggle auto-reply on/off
 *   - Edit the welcome message text (up to 500 chars)
 *   - Preview how the message looks
 *   - Placeholder tokens: {name} → follower's name, {channel} → channel name
 *   - Confirmation before clearing
 *   - Changes saved to Firebase via ChannelViewModel
 */
public class ChannelAutoReplyActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private static final int MAX_CHARS = 500;

    private ChannelViewModel viewModel;
    private String channelId, channelName;

    private SwitchMaterial     switchEnabled;
    private TextInputEditText  etMessage;
    private TextView           tvCharCount, tvPreview;
    private MaterialButton     btnSave, btnClear, btnInsertName, btnInsertChannel;
    private View               layoutEditor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_auto_reply);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_auto_reply);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Welcome message");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        switchEnabled    = findViewById(R.id.switch_auto_reply_enabled);
        etMessage        = findViewById(R.id.et_auto_reply_message);
        tvCharCount      = findViewById(R.id.tv_auto_reply_char_count);
        tvPreview        = findViewById(R.id.tv_auto_reply_preview);
        btnSave          = findViewById(R.id.btn_auto_reply_save);
        btnClear         = findViewById(R.id.btn_auto_reply_clear);
        btnInsertName    = findViewById(R.id.btn_insert_name_token);
        btnInsertChannel = findViewById(R.id.btn_insert_channel_token);
        layoutEditor     = findViewById(R.id.layout_auto_reply_editor);

        // Char counter
        if (etMessage != null) {
            etMessage.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    int len = s.length();
                    if (tvCharCount != null) {
                        tvCharCount.setText(len + " / " + MAX_CHARS);
                        tvCharCount.setTextColor(len > MAX_CHARS ? 0xFFFF3B30 : 0xFF888888);
                    }
                    updatePreview(s.toString());
                    if (btnSave != null) btnSave.setEnabled(len > 0 && len <= MAX_CHARS);
                }
            });
        }

        // Toggle editor visibility
        if (switchEnabled != null) {
            switchEnabled.setOnCheckedChangeListener((btn, checked) -> {
                if (layoutEditor != null)
                    layoutEditor.setVisibility(checked ? View.VISIBLE : View.GONE);
            });
        }

        // Insert tokens
        if (btnInsertName != null) {
            btnInsertName.setOnClickListener(v -> insertToken("{name}"));
        }
        if (btnInsertChannel != null) {
            btnInsertChannel.setOnClickListener(v -> insertToken("{channel}"));
        }

        // Save
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                String msg = etMessage != null && etMessage.getText() != null
                    ? etMessage.getText().toString().trim() : "";
                if (msg.isEmpty()) { Toast.makeText(this, "Message cannot be empty.", Toast.LENGTH_SHORT).show(); return; }
                viewModel.setWelcomeMessage(channelId, msg);
                Toast.makeText(this, "Welcome message saved.", Toast.LENGTH_SHORT).show();
                finish();
            });
        }

        // Clear
        if (btnClear != null) {
            btnClear.setOnClickListener(v ->
                new AlertDialog.Builder(this)
                    .setTitle("Clear welcome message?")
                    .setMessage("New followers will no longer receive an auto-reply.")
                    .setPositiveButton("Clear", (d, w) -> {
                        viewModel.clearWelcomeMessage(channelId);
                        if (etMessage != null) etMessage.setText("");
                        if (switchEnabled != null) switchEnabled.setChecked(false);
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        }

        // Observe existing welcome message
        viewModel.welcomeMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                if (switchEnabled != null) switchEnabled.setChecked(true);
                if (etMessage != null) etMessage.setText(msg);
                if (layoutEditor != null) layoutEditor.setVisibility(View.VISIBLE);
            }
        });

        // Observe toast
        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        // Load current message
        viewModel.loadWelcomeMessage(channelId);
    }

    private void insertToken(String token) {
        if (etMessage == null) return;
        int start = Math.max(0, etMessage.getSelectionStart());
        int end   = Math.max(start, etMessage.getSelectionEnd());
        Editable e = etMessage.getText();
        if (e != null) {
            e.replace(start, end, token);
            etMessage.setSelection(start + token.length());
        }
    }

    private void updatePreview(String template) {
        if (tvPreview == null) return;
        String preview = template
            .replace("{name}", "Alex")
            .replace("{channel}", channelName != null ? channelName : "this channel");
        tvPreview.setText("Preview: " + preview);
    }
}
