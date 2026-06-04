package com.callx.app.youtube.player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.youtube.player.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * YouTubeSpeedQualitySheet — playback speed aur video quality choose karna
 *
 * Usage:
 *   YouTubeSpeedQualitySheet.newInstance(currentSpeed, currentQuality)
 *       .setCallback((speed, quality) -> { ... })
 *       .show(getSupportFragmentManager(), "sq");
 */
public class YouTubeSpeedQualitySheet extends BottomSheetDialogFragment {

    public interface Callback {
        void onSpeedSelected(float speed);
        void onQualitySelected(String quality);
    }

    private static final float[]  SPEEDS        = {0.25f,0.5f,0.75f,1.0f,1.25f,1.5f,1.75f,2.0f};
    private static final String[] SPEED_LABELS  = {"0.25x","0.5x","0.75x","Normal","1.25x","1.5x","1.75x","2x"};
    private static final String[] QUALITIES     = {"Auto","144p","240p","360p","480p","720p","1080p"};

    private float  currentSpeed   = 1.0f;
    private String currentQuality = "Auto";
    private Callback callback;

    public static YouTubeSpeedQualitySheet newInstance(float speed, String quality) {
        YouTubeSpeedQualitySheet s = new YouTubeSpeedQualitySheet();
        Bundle b = new Bundle();
        b.putFloat("speed", speed);
        b.putString("quality", quality != null ? quality : "Auto");
        s.setArguments(b);
        return s;
    }

    public YouTubeSpeedQualitySheet setCallback(Callback cb) { this.callback = cb; return this; }

    @Override public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        if (getArguments() != null) {
            currentSpeed   = getArguments().getFloat("speed", 1.0f);
            currentQuality = getArguments().getString("quality", "Auto");
        }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.bottom_sheet_yt_speed_quality, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        View btnClose = view.findViewById(R.id.btn_sq_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> dismiss());

        // Speed options — build rows programmatically (TextView only, no reuse of settings_row layout)
        ViewGroup speedGroup = view.findViewById(R.id.ll_speed_options);
        if (speedGroup != null) {
            for (int i = 0; i < SPEEDS.length; i++) {
                final float speed = SPEEDS[i];
                final String label = SPEED_LABELS[i];
                View row = buildRow(label, Math.abs(speed - currentSpeed) < 0.01f);
                row.setOnClickListener(v -> { if (callback != null) callback.onSpeedSelected(speed); dismiss(); });
                speedGroup.addView(row);
            }
        }

        // Quality options
        ViewGroup qualGroup = view.findViewById(R.id.ll_quality_options);
        if (qualGroup != null) {
            for (String q : QUALITIES) {
                View row = buildRow(q, q.equals(currentQuality));
                row.setOnClickListener(v -> { if (callback != null) callback.onQualitySelected(q); dismiss(); });
                qualGroup.addView(row);
            }
        }
    }

    /** Build a simple selectable row — no external layout dependency */
    private View buildRow(String label, boolean selected) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setMinimumHeight(dp(52));
        row.setClickable(true);
        row.setFocusable(true);
        int[] attrs = {android.R.attr.selectableItemBackground};
        android.content.res.TypedArray ta = requireContext().obtainStyledAttributes(attrs);
        row.setBackground(ta.getDrawable(0));
        ta.recycle();

        // Checkmark when selected
        TextView tvCheck = new TextView(requireContext());
        tvCheck.setText(selected ? "✓  " : "    ");
        tvCheck.setTextColor(selected ? 0xFFFF0000 : 0x00000000);
        tvCheck.setTextSize(14);
        row.addView(tvCheck);

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextColor(selected ? 0xFFFF0000 : 0xFFFFFFFF);
        tvLabel.setTextSize(14);
        android.widget.LinearLayout.LayoutParams lp =
            new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvLabel.setLayoutParams(lp);
        row.addView(tvLabel);

        return row;
    }

    private int dp(int val) {
        return Math.round(val * requireContext().getResources().getDisplayMetrics().density);
    }
}
