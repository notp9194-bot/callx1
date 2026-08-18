package com.callx.app.music;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * SoundDetailSheetFragment — thin host only.
 *
 * Sara logic SoundDetailFragment mein hai.
 * Yeh class sirf:
 *   1. SoundDetailFragment ko child fragment ke roop mein add karti hai
 *   2. BottomSheetBehavior configure karti hai (60% open → 80% expanded)
 *   3. Close callback ke roop mein dismiss() deti hai
 *
 * Koi duplicate code nahi.
 */
public class SoundDetailSheetFragment extends BottomSheetDialogFragment {

    // ── Arg keys (same as before — existing callers nahi tutenge) ─────────────
    private static final String ARG_SOUND_ID    = "sound_id";
    private static final String ARG_TITLE       = "title";
    private static final String ARG_ARTIST      = "artist";
    private static final String ARG_COVER_URL   = "cover_url";
    private static final String ARG_SOUND_URL   = "sound_url";
    private static final String ARG_DURATION_MS = "duration_ms";
    private static final String ARG_GENRE       = "genre";
    private static final String ARG_BPM         = "bpm";
    private static final String ARG_CREATOR_UID = "creator_uid";
    private static final String ARG_PREVIEW_URL = "preview_audio_url";

    // ─────────────────────────────────────────────────────────────────────────
    // Factory
    // ─────────────────────────────────────────────────────────────────────────

    /** Basic (UserReelsActivity se call hota hai) */
    public static SoundDetailSheetFragment newInstance(
            String soundId, String title, String artist,
            String coverUrl, String soundUrl, int durationMs) {
        return newInstance(soundId, title, artist, coverUrl, soundUrl,
                durationMs, null, 0, null, null);
    }

    /** Full args */
    public static SoundDetailSheetFragment newInstance(
            String soundId, String title, String artist,
            String coverUrl, String soundUrl, int durationMs,
            String genre, int bpm, String creatorUid, String previewAudioUrl) {
        SoundDetailSheetFragment f = new SoundDetailSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_SOUND_ID,    n(soundId));
        b.putString(ARG_TITLE,       n(title));
        b.putString(ARG_ARTIST,      n(artist));
        b.putString(ARG_COVER_URL,   n(coverUrl));
        b.putString(ARG_SOUND_URL,   n(soundUrl));
        b.putInt   (ARG_DURATION_MS, durationMs);
        b.putString(ARG_GENRE,       n(genre));
        b.putInt   (ARG_BPM,         bpm);
        b.putString(ARG_CREATOR_UID, n(creatorUid));
        b.putString(ARG_PREVIEW_URL, n(previewAudioUrl));
        f.setArguments(b);
        return f;
    }

    private static String n(String s) { return s != null ? s : ""; }

    // ─────────────────────────────────────────────────────────────────────────
    // View — sirf ek container FrameLayout chahiye
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        FrameLayout frame = new FrameLayout(requireContext());
        frame.setId(android.R.id.content); // stable ID for fragment replace
        return frame;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (savedInstanceState == null) {
            Bundle b = getArguments() != null ? getArguments() : new Bundle();

            // SoundDetailFragment banao — isSheet = true
            SoundDetailFragment fragment = SoundDetailFragment.newInstance(
                b.getString(ARG_SOUND_ID,    ""),
                b.getString(ARG_TITLE,       ""),
                b.getString(ARG_ARTIST,      ""),
                b.getString(ARG_COVER_URL,   ""),
                b.getString(ARG_SOUND_URL,   ""),
                b.getInt   (ARG_DURATION_MS, 0),
                b.getString(ARG_GENRE,       ""),
                b.getInt   (ARG_BPM,         0),
                b.getString(ARG_CREATOR_UID, ""),
                b.getString(ARG_PREVIEW_URL, ""),
                true /* isSheet = true → drag handle + X button */
            );

            // Close = sheet dismiss
            fragment.setOnCloseListener(this::dismiss);

            getChildFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BottomSheetBehavior — 60% pe open, 80% tak expand
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onStart() {
        super.onStart();
        BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
        if (dialog == null) return;
        FrameLayout sheet = dialog.findViewById(
            com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;

        sheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        sheet.requestLayout();

        int screenH = requireContext().getResources().getDisplayMetrics().heightPixels;

        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        behavior.setFitToContents(false);
        behavior.setPeekHeight((int)(screenH * 0.60f), true); // 60% — initial open
        behavior.setExpandedOffset((int)(screenH * 0.20f));   // 80% — max expand
        behavior.setSkipCollapsed(false); // 60% state zaroor dikhao
        behavior.setState(BottomSheetBehavior.STATE_COLLAPSED); // pehle 60%
    }
}
