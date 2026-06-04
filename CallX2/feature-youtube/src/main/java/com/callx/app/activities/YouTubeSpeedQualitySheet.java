package com.callx.app.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.callx.app.youtube.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * YouTubeSpeedQualitySheet
 * Player ke andar playback speed aur video quality choose karne ka bottom sheet.
 *
 * Usage in YouTubePlayerActivity:
 *   YouTubeSpeedQualitySheet.newInstance(currentSpeed, currentQuality)
 *       .setCallback((speed, quality) -> {
 *           player.setPlaybackSpeed(speed);
 *           // quality change logic
 *       })
 *       .show(getSupportFragmentManager(), "speed_quality");
 */
public class YouTubeSpeedQualitySheet extends BottomSheetDialogFragment {

    public interface Callback {
        void onSpeedSelected(float speed);
        void onQualitySelected(String quality);
    }

    private static final float[] SPEEDS   = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    private static final String[] SPEED_LABELS = {"0.25x","0.5x","0.75x","Normal","1.25x","1.5x","1.75x","2x"};
    private static final String[] QUALITIES = {"Auto","144p","240p","360p","480p","720p","1080p"};

    private float    currentSpeed   = 1.0f;
    private String   currentQuality = "Auto";
    private Callback callback;

    public static YouTubeSpeedQualitySheet newInstance(float speed, String quality) {
        YouTubeSpeedQualitySheet sheet = new YouTubeSpeedQualitySheet();
        Bundle b = new Bundle();
        b.putFloat("speed", speed);
        b.putString("quality", quality != null ? quality : "Auto");
        sheet.setArguments(b);
        return sheet;
    }

    public YouTubeSpeedQualitySheet setCallback(Callback cb) { this.callback = cb; return this; }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        // Speed section
        ViewGroup speedGroup = view.findViewById(R.id.ll_speed_options);
        if (speedGroup != null) {
            for (int i = 0; i < SPEEDS.length; i++) {
                final float speed = SPEEDS[i];
                View item = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_yt_settings_row, speedGroup, false);
                TextView tv = item.findViewById(R.id.tv_settings_row_label);
                TextView tvSub = item.findViewById(R.id.tv_settings_row_sub);
                if (tv != null) tv.setText(SPEED_LABELS[i]);
                if (tvSub != null) tvSub.setVisibility(View.GONE);

                // Highlight current selection
                if (Math.abs(speed - currentSpeed) < 0.01f && tv != null)
                    tv.setTextColor(0xFFFF0000);

                item.setOnClickListener(v -> {
                    if (callback != null) callback.onSpeedSelected(speed);
                    dismiss();
                });
                speedGroup.addView(item);
            }
        }

        // Quality section
        ViewGroup qualityGroup = view.findViewById(R.id.ll_quality_options);
        if (qualityGroup != null) {
            for (String q : QUALITIES) {
                View item = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_yt_settings_row, qualityGroup, false);
                TextView tv = item.findViewById(R.id.tv_settings_row_label);
                TextView tvSub = item.findViewById(R.id.tv_settings_row_sub);
                if (tv != null) tv.setText(q);
                if (tvSub != null) tvSub.setVisibility(View.GONE);
                if (q.equals(currentQuality) && tv != null)
                    tv.setTextColor(0xFFFF0000);

                item.setOnClickListener(v -> {
                    if (callback != null) callback.onQualitySelected(q);
                    dismiss();
                });
                qualityGroup.addView(item);
            }
        }
    }
}
