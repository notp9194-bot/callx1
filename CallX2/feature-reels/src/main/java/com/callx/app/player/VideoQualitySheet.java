package com.callx.app.player;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.reels.R;
import com.callx.app.utils.NetworkUtils;
import com.callx.app.utils.VideoQualityPreferences;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * VideoQualitySheet — Bottom sheet for video quality selection.
 *
 * Features:
 *  ✅ Per-send quality override (LOW / STANDARD / HD / FULL_HD / ORIGINAL)
 *  ✅ Live estimated file size per quality tier
 *  ✅ WiFi-only HD toggle
 *  ✅ Data saver mode toggle
 *  ✅ "Remember for this chat" option
 *  ✅ Compression stats summary line
 *  ✅ Current codec display (AV1 / HEVC / H.264)
 *
 * Usage:
 *   VideoQualitySheet.show(getSupportFragmentManager(), chatId, durationMs,
 *       quality -> { compress(uri, quality); });
 */
public class VideoQualitySheet extends BottomSheetDialogFragment {

    public interface OnQualitySelected {
        void onSelected(VideoQualityPreferences.Quality quality);
    }

    private static final String ARG_CHAT_ID    = "chatId";
    private static final String ARG_DURATION   = "durationMs";

    private String                   chatId;
    private long                     durationMs;
    private OnQualitySelected        listener;
    private VideoQualityPreferences  prefs;

    public static VideoQualitySheet newInstance(String chatId, long durationMs) {
        VideoQualitySheet f = new VideoQualitySheet();
        Bundle args = new Bundle();
        args.putString(ARG_CHAT_ID,  chatId);
        args.putLong(ARG_DURATION,   durationMs);
        f.setArguments(args);
        return f;
    }

    public static void show(androidx.fragment.app.FragmentManager fm,
                            String chatId, long durationMs,
                            OnQualitySelected listener) {
        VideoQualitySheet sheet = newInstance(chatId, durationMs);
        sheet.setListener(listener);
        sheet.show(fm, "VideoQualitySheet");
    }

    public void setListener(OnQualitySelected l) { this.listener = l; }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            chatId     = getArguments().getString(ARG_CHAT_ID, "");
            durationMs = getArguments().getLong(ARG_DURATION, 0);
        }
        prefs = new VideoQualityPreferences(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_video_quality, container, false);
        bindViews(view);
        return view;
    }

    private void bindViews(View root) {
        Context ctx = requireContext();

        // Current quality
        VideoQualityPreferences.Quality currentQ =
            prefs.resolveEffectiveQuality(ctx, chatId);

        // Quality options
        RadioGroup   rgQuality = root.findViewById(R.id.rg_quality);
        RadioButton  rbAuto    = root.findViewById(R.id.rb_auto);
        RadioButton  rbLow     = root.findViewById(R.id.rb_low);
        RadioButton  rbStd     = root.findViewById(R.id.rb_standard);
        RadioButton  rbHd      = root.findViewById(R.id.rb_hd);
        RadioButton  rbFullHd  = root.findViewById(R.id.rb_full_hd);
        RadioButton  rbOrig    = root.findViewById(R.id.rb_original);

        // Estimated sizes
        TextView tvAutoSize    = root.findViewById(R.id.tv_auto_size);
        TextView tvLowSize     = root.findViewById(R.id.tv_low_size);
        TextView tvStdSize     = root.findViewById(R.id.tv_std_size);
        TextView tvHdSize      = root.findViewById(R.id.tv_hd_size);
        TextView tvFullHdSize  = root.findViewById(R.id.tv_full_hd_size);
        TextView tvOrigSize    = root.findViewById(R.id.tv_orig_size);

        // Toggles
        SwitchMaterial swWifiHd     = root.findViewById(R.id.sw_wifi_hd);
        SwitchMaterial swDataSaver  = root.findViewById(R.id.sw_data_saver);
        SwitchMaterial swRemember   = root.findViewById(R.id.sw_remember_chat);

        // Stats
        TextView tvStats = root.findViewById(R.id.tv_compression_stats);
        TextView tvCodec = root.findViewById(R.id.tv_active_codec);

        // Set estimated sizes
        long durationSec = durationMs / 1000;
        if (tvAutoSize   != null) tvAutoSize.setText(fmtSize(VideoQualityPreferences.estimateOutputBytes(VideoQualityPreferences.Quality.AUTO,    durationSec)));
        if (tvLowSize    != null) tvLowSize.setText(fmtSize(VideoQualityPreferences.estimateOutputBytes(VideoQualityPreferences.Quality.LOW,     durationSec)));
        if (tvStdSize    != null) tvStdSize.setText(fmtSize(VideoQualityPreferences.estimateOutputBytes(VideoQualityPreferences.Quality.STANDARD, durationSec)));
        if (tvHdSize     != null) tvHdSize.setText(fmtSize(VideoQualityPreferences.estimateOutputBytes(VideoQualityPreferences.Quality.HD,       durationSec)));
        if (tvFullHdSize != null) tvFullHdSize.setText(fmtSize(VideoQualityPreferences.estimateOutputBytes(VideoQualityPreferences.Quality.FULL_HD,  durationSec)));
        if (tvOrigSize   != null) tvOrigSize.setText("Original size");

        // Set current selection
        switch (currentQ) {
            case LOW:     if (rbLow    != null) rbLow.setChecked(true);    break;
            case HD:      if (rbHd     != null) rbHd.setChecked(true);     break;
            case FULL_HD: if (rbFullHd != null) rbFullHd.setChecked(true); break;
            case ORIGINAL:if (rbOrig   != null) rbOrig.setChecked(true);   break;
            case STANDARD:if (rbStd    != null) rbStd.setChecked(true);    break;
            default:      if (rbAuto   != null) rbAuto.setChecked(true);   break;
        }

        // Toggle states
        if (swWifiHd    != null) swWifiHd.setChecked(prefs.isHdOnWifiOnly());
        if (swDataSaver != null) swDataSaver.setChecked(prefs.isDataSaverMode());

        // Codec info
        if (tvCodec != null) {
            String codec = com.callx.app.utils.VideoCompressor.pickCodec(currentQ);
            String display = codec.replace("video/", "").toUpperCase();
            tvCodec.setText("Active codec: " + display
                + (com.callx.app.utils.VideoCompressor.hasHardwareEncoder(codec)
                   ? " (HW)" : " (SW)"));
        }

        // Stats
        if (tvStats != null) {
            tvStats.setText(prefs.getStatsSummary());
        }

        // Toggles
        if (swWifiHd != null)
            swWifiHd.setOnCheckedChangeListener((b, checked) ->
                prefs.setHdOnWifiOnly(checked));
        if (swDataSaver != null)
            swDataSaver.setOnCheckedChangeListener((b, checked) -> {
                prefs.setDataSaverMode(checked);
                // Force Low quality selection in data saver
                if (checked && rbLow != null) rbLow.setChecked(true);
            });

        // Send button
        View btnSend = root.findViewById(R.id.btn_send);
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> {
                VideoQualityPreferences.Quality selected = getSelected(rgQuality);
                // Remember per-chat?
                if (swRemember != null && swRemember.isChecked() && !chatId.isEmpty())
                    prefs.setChatQuality(chatId, selected);
                if (listener != null) listener.onSelected(selected);
                dismiss();
            });
        }
    }

    private VideoQualityPreferences.Quality getSelected(RadioGroup rg) {
        if (rg == null) return VideoQualityPreferences.Quality.STANDARD;
        int id = rg.getCheckedRadioButtonId();
        if (id == R.id.rb_low)     return VideoQualityPreferences.Quality.LOW;
        if (id == R.id.rb_hd)      return VideoQualityPreferences.Quality.HD;
        if (id == R.id.rb_full_hd) return VideoQualityPreferences.Quality.FULL_HD;
        if (id == R.id.rb_original)return VideoQualityPreferences.Quality.ORIGINAL;
        if (id == R.id.rb_auto)    return VideoQualityPreferences.Quality.AUTO;
        return VideoQualityPreferences.Quality.STANDARD;
    }

    private static String fmtSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024 * 1024) return String.format("~%.0f KB", bytes / 1024f);
        return String.format("~%.1f MB", bytes / (1024f * 1024f));
    }
}
