package com.callx.app.feed;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.reels.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * ReelPlaybackOptionsSheet — opened by long-pressing a video reel.
 *
 *   View fullscreen   → Cinema Mode (same toggle as the 3-dot menu item)
 *   Speed             → .5x / 1x / 1.5x / 2x
 *   Auto scroll       → advance to next reel when this one ends
 *   Closed Captions   → select the stream's caption track (if it has one)
 *
 * State is passed in via arguments; every change is reported to the parent
 * ReelPlayerFragment through {@link Listener} (no listener field to lose on
 * recreation). Playback keeps running behind the sheet so speed changes are
 * visible immediately.
 */
public class ReelPlaybackOptionsSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReelPlaybackOptionsSheet";

    private static final String ARG_SPEED_INDEX = "speed_index";
    private static final String ARG_AUTO_SCROLL = "auto_scroll";
    private static final String ARG_CAPTIONS    = "captions";
    private static final String ARG_CINEMA_ON   = "cinema_on";

    /** Labels for the segmented control — order matches ReelPlayerController.SPEED_STEPS. */
    private static final String[] SPEED_LABELS = {".5x", "1x", "1.5x", "2x"};

    public interface Listener {
        /** "View fullscreen" tapped — toggle Cinema Mode. */
        void onViewFullscreenToggle();
        void onSpeedSelected(int speedIndex);
        void onAutoScrollChanged(boolean enabled);
        /** Toggles captions; returns the resulting on/off state. */
        boolean onCaptionsToggled();
    }

    private Listener listener;
    private int speedIndex;
    private boolean autoScroll;
    private boolean captions;
    private boolean cinemaOn;

    public static ReelPlaybackOptionsSheet newInstance(int speedIndex, boolean autoScroll,
                                                       boolean captions, boolean cinemaOn) {
        ReelPlaybackOptionsSheet s = new ReelPlaybackOptionsSheet();
        Bundle a = new Bundle();
        a.putInt(ARG_SPEED_INDEX, speedIndex);
        a.putBoolean(ARG_AUTO_SCROLL, autoScroll);
        a.putBoolean(ARG_CAPTIONS, captions);
        a.putBoolean(ARG_CINEMA_ON, cinemaOn);
        s.setArguments(a);
        return s;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.ReelMoreBottomSheetTheme);
        Bundle a = getArguments();
        if (a != null) {
            speedIndex = a.getInt(ARG_SPEED_INDEX, 1);
            autoScroll = a.getBoolean(ARG_AUTO_SCROLL, false);
            captions   = a.getBoolean(ARG_CAPTIONS, false);
            cinemaOn   = a.getBoolean(ARG_CINEMA_ON, false);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_reel_playback_options, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        if (getParentFragment() instanceof Listener) {
            listener = (Listener) getParentFragment();
        } else if (getActivity() instanceof Listener) {
            listener = (Listener) getActivity();
        }

        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> b = ((BottomSheetDialog) getDialog()).getBehavior();
            b.setState(BottomSheetBehavior.STATE_EXPANDED);
            b.setSkipCollapsed(true);
        }

        // ── View fullscreen (Cinema Mode) ─────────────────────────────────
        TextView tvFullscreen = v.findViewById(R.id.tv_fullscreen);
        if (cinemaOn) tvFullscreen.setText("Exit fullscreen");
        v.findViewById(R.id.row_fullscreen).setOnClickListener(x -> {
            if (listener != null) listener.onViewFullscreenToggle();
            dismissAllowingStateLoss();
        });

        // ── Speed segments ────────────────────────────────────────────────
        LinearLayout seg = v.findViewById(R.id.ll_speed_segments);
        final TextView[] chips = new TextView[SPEED_LABELS.length];
        for (int i = 0; i < SPEED_LABELS.length; i++) {
            final int idx = i;
            TextView t = new TextView(requireContext());
            t.setText(SPEED_LABELS[i]);
            t.setTextSize(14f);
            t.setGravity(Gravity.CENTER);
            t.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(40)));
            t.setOnClickListener(x -> {
                speedIndex = idx;
                paintSpeed(chips);
                if (listener != null) listener.onSpeedSelected(idx);
            });
            chips[i] = t;
            seg.addView(t);
        }
        paintSpeed(chips);

        // ── Auto scroll ───────────────────────────────────────────────────
        SwitchMaterial sw = v.findViewById(R.id.sw_auto_scroll);
        sw.setThumbTintList(new ColorStateList(
            new int[][]{{android.R.attr.state_checked}, {}},
            new int[]{0xFFFFFFFF, 0xFFB0B3BA}));
        sw.setTrackTintList(new ColorStateList(
            new int[][]{{android.R.attr.state_checked}, {}},
            new int[]{0xFF3897F0, 0xFF3A3D45}));
        sw.setChecked(autoScroll);
        sw.setOnCheckedChangeListener((btn, on) -> {
            autoScroll = on;
            if (listener != null) listener.onAutoScrollChanged(on);
        });
        v.findViewById(R.id.row_auto_scroll).setOnClickListener(x -> sw.toggle());

        // ── Closed Captions ───────────────────────────────────────────────
        TextView tvCc = v.findViewById(R.id.tv_captions_state);
        tvCc.setText(captions ? "On" : "Off");
        v.findViewById(R.id.row_captions).setOnClickListener(x -> {
            if (listener == null) return;
            captions = listener.onCaptionsToggled();
            tvCc.setText(captions ? "On" : "Off");
        });
    }

    /** Selected segment = dark pill (bg_pill_dark_translucent), others transparent. */
    private void paintSpeed(TextView[] chips) {
        for (int i = 0; i < chips.length; i++) {
            boolean sel = i == speedIndex;
            chips[i].setBackgroundResource(sel ? R.drawable.bg_pill_dark_translucent : 0);
            chips[i].setTextColor(sel ? 0xFFFFFFFF : 0xB3FFFFFF);
            chips[i].setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private int dp(int v) {
        return Math.round(v * requireContext().getResources().getDisplayMetrics().density);
    }
}
