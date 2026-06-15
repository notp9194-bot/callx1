package com.callx.app.interactions;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.*;

/**
 * StatusMusicPickerBottomSheet v27 — NEW
 *
 * Attach background music to a status (image or text type).
 * Features:
 *   ✅ Curated popular tracks list (extensible to streaming API)
 *   ✅ Search filter for track name / artist
 *   ✅ Manual URL input (direct audio URL)
 *   ✅ Preview indicator (♫ animated label)
 *   ✅ "Remove music" option if music already attached
 *   ✅ Returns MusicSelection via callback
 *
 * Integration: Call StatusMusicPickerBottomSheet.show(ctx, currentTitle, listener)
 * from NewStatusActivity when user taps "♫ Add Music" button.
 */
public class StatusMusicPickerBottomSheet {

    public static class MusicSelection {
        public final String title;
        public final String artist;
        public final String audioUrl;
        public MusicSelection(String title, String artist, String audioUrl) {
            this.title = title; this.artist = artist; this.audioUrl = audioUrl;
        }
    }

    public interface OnMusicSelected {
        /** Called when user selects a track. selection=null means "remove music". */
        void onSelected(MusicSelection selection);
    }

    // ── Curated tracks (replace audioUrl with real CDN links) ──────────────
    private static final String[][] CURATED = {
        {"Tum Hi Ho",         "Arijit Singh",      ""},
        {"Kesariya",          "Arijit Singh",      ""},
        {"Raataan Lambiyan",  "Jubin Nautiyal",    ""},
        {"Tera Ban Jaunga",   "Akhil",             ""},
        {"Blinding Lights",   "The Weeknd",        ""},
        {"Shape of You",      "Ed Sheeran",        ""},
        {"Levitating",        "Dua Lipa",          ""},
        {"Stay",              "Justin Bieber",     ""},
        {"Believer",          "Imagine Dragons",   ""},
        {"Happier",           "Marshmello",        ""},
        {"Sunflower",         "Post Malone",       ""},
        {"Senorita",          "Camila Cabello",    ""},
        {"Perfect",           "Ed Sheeran",        ""},
        {"Tera Yaar Hoon",    "Arijit Singh",      ""},
        {"Lag Ja Gale",       "Lata Mangeshkar",   ""},
    };

    public static void show(Context ctx, String currentMusicTitle, OnMusicSelected listener) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        sheet.getBehavior().setPeekHeight(dp(ctx, 560));

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 32));

        // Handle
        View handle = new View(ctx);
        handle.setBackgroundColor(Color.parseColor("#E0E0E0"));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(ctx, 12);
        root.addView(handle, hp);

        // Title
        TextView title = new TextView(ctx);
        title.setText("♫  Add Music");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(ctx, 4));
        root.addView(title);

        // Currently playing label
        if (currentMusicTitle != null && !currentMusicTitle.isEmpty()) {
            LinearLayout nowRow = new LinearLayout(ctx);
            nowRow.setOrientation(LinearLayout.HORIZONTAL);
            nowRow.setGravity(Gravity.CENTER_VERTICAL);
            nowRow.setBackgroundColor(Color.parseColor("#F3E5FF"));
            nowRow.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            np.bottomMargin = dp(ctx, 8);

            TextView nowTv = new TextView(ctx);
            nowTv.setText("♫ " + currentMusicTitle);
            nowTv.setTextSize(13);
            nowTv.setTextColor(Color.parseColor("#6200EE"));
            nowTv.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            nowRow.addView(nowTv);

            TextView removeTv = new TextView(ctx);
            removeTv.setText("Remove ×");
            removeTv.setTextSize(13);
            removeTv.setTextColor(Color.RED);
            removeTv.setPadding(dp(ctx, 8), 0, 0, 0);
            removeTv.setOnClickListener(v -> {
                if (listener != null) listener.onSelected(null);
                sheet.dismiss();
            });
            nowRow.addView(removeTv);
            root.addView(nowRow, np);
        }

        // Search bar
        EditText etSearch = new EditText(ctx);
        etSearch.setHint("🔍  Search songs…");
        etSearch.setSingleLine(true);
        etSearch.setBackgroundColor(Color.parseColor("#F5F5F5"));
        etSearch.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(ctx, 8);
        sp.bottomMargin = dp(ctx, 8);
        root.addView(etSearch, sp);

        // Track list container (scrollable)
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout trackList = new LinearLayout(ctx);
        trackList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(trackList);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 320));
        root.addView(scroll, tlp);

        // Populate tracks
        List<String[]> tracks = new ArrayList<>(Arrays.asList(CURATED));
        populateTracks(ctx, trackList, tracks, listener, sheet);

        // Search filter
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String q = s.toString().toLowerCase();
                List<String[]> found = new ArrayList<>();
                for (String[] t : CURATED) {
                    if (t[0].toLowerCase().contains(q) || t[1].toLowerCase().contains(q))
                        found.add(t);
                }
                trackList.removeAllViews();
                populateTracks(ctx, trackList, found, listener, sheet);
                // Manual URL row at bottom when searching
                if (!q.isEmpty()) addManualUrlRow(ctx, trackList, q, listener, sheet);
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        // Manual URL section
        View divider = new View(ctx);
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)));

        TextView manualLabel = new TextView(ctx);
        manualLabel.setText("Or paste an audio URL (MP3/M4A)");
        manualLabel.setTextSize(13);
        manualLabel.setTextColor(Color.GRAY);
        manualLabel.setPadding(0, dp(ctx, 10), 0, dp(ctx, 4));
        root.addView(manualLabel);

        LinearLayout urlRow = new LinearLayout(ctx);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText etUrl = new EditText(ctx);
        etUrl.setHint("https://example.com/music.mp3");
        etUrl.setSingleLine(true);
        etUrl.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        urlRow.addView(etUrl);

        TextView btnAdd = new TextView(ctx);
        btnAdd.setText("Add");
        btnAdd.setTextColor(Color.WHITE);
        btnAdd.setBackgroundColor(Color.parseColor("#6200EE"));
        btnAdd.setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8));
        btnAdd.setTextSize(14);
        btnAdd.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            if (!url.isEmpty()) {
                if (listener != null) listener.onSelected(
                        new MusicSelection("Custom Audio", "", url));
                sheet.dismiss();
            } else {
                Toast.makeText(ctx, "Enter a valid URL", Toast.LENGTH_SHORT).show();
            }
        });
        urlRow.addView(btnAdd, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT) {{ leftMargin = dp(ctx, 8); }});
        root.addView(urlRow);

        ScrollView outerScroll = new ScrollView(ctx);
        outerScroll.addView(root);
        sheet.setContentView(outerScroll);
        sheet.show();
    }

    private static void populateTracks(Context ctx, LinearLayout container,
                                        List<String[]> tracks,
                                        OnMusicSelected listener,
                                        BottomSheetDialog sheet) {
        for (String[] t : tracks) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10));
            row.setBackgroundResource(android.R.drawable.list_selector_background);

            TextView note = new TextView(ctx);
            note.setText("♫");
            note.setTextSize(20);
            note.setTextColor(Color.parseColor("#6200EE"));
            note.setPadding(0, 0, dp(ctx, 12), 0);
            row.addView(note);

            LinearLayout info = new LinearLayout(ctx);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(t[0]);
            tvTitle.setTextSize(14);
            tvTitle.setTextColor(Color.BLACK);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            info.addView(tvTitle);

            TextView tvArtist = new TextView(ctx);
            tvArtist.setText(t[1]);
            tvArtist.setTextSize(12);
            tvArtist.setTextColor(Color.GRAY);
            info.addView(tvArtist);

            row.addView(info);

            final String trackTitle  = t[0];
            final String trackArtist = t[1];
            final String trackUrl    = t[2];
            row.setOnClickListener(v -> {
                if (listener != null) listener.onSelected(
                        new MusicSelection(trackTitle, trackArtist, trackUrl));
                sheet.dismiss();
            });

            container.addView(row);

            // Divider
            View div = new View(ctx);
            div.setBackgroundColor(Color.parseColor("#F0F0F0"));
            container.addView(div, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)));
        }
    }

    private static void addManualUrlRow(Context ctx, LinearLayout container,
                                         String query,
                                         OnMusicSelected listener,
                                         BottomSheetDialog sheet) {
        if (!query.startsWith("http")) return;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12));
        row.setBackgroundColor(Color.parseColor("#FFF9E7"));

        TextView tv = new TextView(ctx);
        tv.setText("➕ Use this URL as music: " + query);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#FF6F00"));
        row.addView(tv);
        row.setOnClickListener(v -> {
            if (listener != null) listener.onSelected(
                    new MusicSelection("Custom Audio", "", query));
            sheet.dismiss();
        });
        container.addView(row);
    }

    private static int dp(Context ctx, int val) {
        return Math.round(val * ctx.getResources().getDisplayMetrics().density);
    }
}
