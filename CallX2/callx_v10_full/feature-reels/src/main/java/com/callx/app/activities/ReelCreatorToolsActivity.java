package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;

/**
 * ReelCreatorToolsActivity — Production-level Creator Tools Screen.
 *
 * Features:
 *  ✅ Green Screen mode toggle (chroma key background replacement)
 *  ✅ Countdown timer selector (Off / 3s / 10s before recording)
 *  ✅ Align tool — ghost overlay of last frame to match transitions
 *  ✅ Ghost / Onion skin toggle for multi-clip alignment
 *  ✅ Q&A mode — enables Q&A sticker overlay on reel
 *  ✅ Poll sticker — add a poll to the reel
 *  ✅ Text-to-Speech — auto voiceover from caption text
 *  ✅ Grid overlay toggle (rule-of-thirds guide)
 *  ✅ Level indicator toggle (horizon guide)
 *  ✅ Returns selected tool config to ReelCameraActivity
 */
public class ReelCreatorToolsActivity extends AppCompatActivity {

    public static final String RESULT_COUNTDOWN_SEC  = "result_countdown_sec";
    public static final String RESULT_GREEN_SCREEN   = "result_green_screen";
    public static final String RESULT_ALIGN_MODE     = "result_align_mode";
    public static final String RESULT_QA_MODE        = "result_qa_mode";
    public static final String RESULT_POLL_MODE      = "result_poll_mode";
    public static final String RESULT_TTS_MODE       = "result_tts_mode";
    public static final String RESULT_GRID_OVERLAY   = "result_grid_overlay";
    public static final String RESULT_LEVEL_GUIDE    = "result_level_guide";

    private TextView    btnBack, btnApply;
    private Switch      swGreenScreen, swAlignTool, swQaMode, swPollMode, swTts, swGrid, swLevel;
    private TextView    tvCountdownValue;
    private ImageButton btnCountdownMinus, btnCountdownPlus;
    private TextView    tvGreenScreenHint, tvAlignHint;

    private int     countdownSec  = 0;
    private boolean greenScreen   = false;
    private boolean alignMode     = false;
    private boolean qaMode        = false;
    private boolean pollMode      = false;
    private boolean ttsMode       = false;
    private boolean gridOverlay   = false;
    private boolean levelGuide    = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_creator_tools);
        bindViews();
        setupClickListeners();
    }

    private void bindViews() {
        btnBack          = findViewById(R.id.btn_tools_back);
        btnApply         = findViewById(R.id.btn_tools_apply);
        swGreenScreen    = findViewById(R.id.sw_green_screen);
        swAlignTool      = findViewById(R.id.sw_align_tool);
        swQaMode         = findViewById(R.id.sw_qa_mode);
        swPollMode       = findViewById(R.id.sw_poll_mode);
        swTts            = findViewById(R.id.sw_tts);
        swGrid           = findViewById(R.id.sw_grid_overlay);
        swLevel          = findViewById(R.id.sw_level_guide);
        tvCountdownValue = findViewById(R.id.tv_countdown_value);
        btnCountdownMinus= findViewById(R.id.btn_countdown_minus);
        btnCountdownPlus = findViewById(R.id.btn_countdown_plus);
        tvGreenScreenHint= findViewById(R.id.tv_green_screen_hint);
        tvAlignHint      = findViewById(R.id.tv_align_hint);
        updateCountdownLabel();
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnCountdownMinus.setOnClickListener(v -> {
            if (countdownSec == 10) countdownSec = 3;
            else if (countdownSec == 3) countdownSec = 0;
            updateCountdownLabel();
        });

        btnCountdownPlus.setOnClickListener(v -> {
            if (countdownSec == 0) countdownSec = 3;
            else if (countdownSec == 3) countdownSec = 10;
            updateCountdownLabel();
        });

        swGreenScreen.setOnCheckedChangeListener((v, checked) -> {
            greenScreen = checked;
            tvGreenScreenHint.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        swAlignTool.setOnCheckedChangeListener((v, checked) -> {
            alignMode = checked;
            tvAlignHint.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        swQaMode.setOnCheckedChangeListener((v, checked) -> {
            qaMode = checked;
            if (checked && pollMode) {
                swPollMode.setChecked(false);
                pollMode = false;
                Toast.makeText(this, "Q&A and Poll cannot be used together", Toast.LENGTH_SHORT).show();
            }
        });

        swPollMode.setOnCheckedChangeListener((v, checked) -> {
            pollMode = checked;
            if (checked && qaMode) {
                swQaMode.setChecked(false);
                qaMode = false;
            }
        });

        swTts.setOnCheckedChangeListener((v, checked) -> ttsMode = checked);
        swGrid.setOnCheckedChangeListener((v, checked) -> gridOverlay = checked);
        swLevel.setOnCheckedChangeListener((v, checked) -> levelGuide = checked);

        btnApply.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(RESULT_COUNTDOWN_SEC, countdownSec);
            result.putExtra(RESULT_GREEN_SCREEN,  greenScreen);
            result.putExtra(RESULT_ALIGN_MODE,    alignMode);
            result.putExtra(RESULT_QA_MODE,       qaMode);
            result.putExtra(RESULT_POLL_MODE,     pollMode);
            result.putExtra(RESULT_TTS_MODE,      ttsMode);
            result.putExtra(RESULT_GRID_OVERLAY,  gridOverlay);
            result.putExtra(RESULT_LEVEL_GUIDE,   levelGuide);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void updateCountdownLabel() {
        if (countdownSec == 0) tvCountdownValue.setText("Off");
        else tvCountdownValue.setText(countdownSec + "s");
    }
}
