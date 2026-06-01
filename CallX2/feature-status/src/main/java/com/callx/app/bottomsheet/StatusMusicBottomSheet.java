package com.callx.app.bottomsheet;

import android.content.Context;
import android.media.MediaPlayer;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.database.*;
import com.callx.app.utils.FirebaseUtils;
import java.util.*;

/**
 * StatusMusicBottomSheet v26 — Pick a song to attach to status.
 * Shows trending songs from Firebase; preview playback; set start-time offset.
 */
public class StatusMusicBottomSheet {
    public interface OnMusicSelected {
        void onSelected(String songId, String title, String artist, String audioUrl, int startSec);
    }
    public static class Song {
        public String id, title, artist, audioUrl, thumbUrl; public int durationSec;
    }

    public static void show(Context ctx, OnMusicSelected cb) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = makeRoot(ctx);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx,600)));

        TextView title = makeTv(ctx,"🎵 Add Music to Status", 17, true);
        title.setPadding(0, dp(ctx,4), 0, dp(ctx,16));
        root.addView(title);

        // Search bar
        EditText search = new EditText(ctx); search.setHint("Search songs…"); search.setSingleLine(true);
        root.addView(search);

        // Song list
        LinearLayout list = new LinearLayout(ctx); list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        sv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        sv.addView(list); root.addView(sv);

        ProgressBar pb = new ProgressBar(ctx); root.addView(pb);

        sheet.setContentView(new ScrollView(ctx) {{ addView(root); }});
        sheet.show();

        final MediaPlayer[] player = {null};
        // Load trending songs from Firebase
        FirebaseUtils.db().getReference("trendingSongs").limitToFirst(30)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    pb.setVisibility(View.GONE);
                    List<Song> songs = new ArrayList<>();
                    for (DataSnapshot c : snap.getChildren()) {
                        Song s = new Song();
                        s.id = c.getKey(); s.title = c.child("title").getValue(String.class);
                        s.artist = c.child("artist").getValue(String.class);
                        s.audioUrl = c.child("audioUrl").getValue(String.class);
                        s.thumbUrl = c.child("thumbUrl").getValue(String.class);
                        Long dur = c.child("durationSec").getValue(Long.class);
                        s.durationSec = dur != null ? dur.intValue() : 30;
                        if (s.title != null) songs.add(s);
                    }
                    renderSongs(ctx, list, songs, player, cb, sheet);
                    search.addTextChangedListener(new android.text.TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                            String q = s.toString().toLowerCase();
                            List<Song> filtered = new ArrayList<>();
                            for (Song song : songs) {
                                String t = (song.title != null ? song.title : "") + (song.artist != null ? song.artist : "");
                                if (t.toLowerCase().contains(q)) filtered.add(song);
                            }
                            list.removeAllViews();
                            renderSongs(ctx, list, filtered, player, cb, sheet);
                        }
                        @Override public void afterTextChanged(android.text.Editable s) {}
                    });
                }
                @Override public void onCancelled(DatabaseError e) { pb.setVisibility(View.GONE); }
            });

        sheet.setOnDismissListener(d -> { if (player[0] != null) { player[0].stop(); player[0].release(); player[0] = null; } });
    }

    private static void renderSongs(Context ctx, LinearLayout list, List<Song> songs,
                                     MediaPlayer[] player, OnMusicSelected cb, BottomSheetDialog sheet) {
        for (Song song : songs) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(ctx,10), 0, dp(ctx,10));

            // Play preview button
            Button btnPlay = new Button(ctx); btnPlay.setText("▶");
            btnPlay.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx,40), dp(ctx,40)));
            row.addView(btnPlay);

            // Title + artist
            LinearLayout info = new LinearLayout(ctx); info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infolp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            infolp.setMarginStart(dp(ctx,10)); info.setLayoutParams(infolp);
            TextView tvTitle = makeTv(ctx, song.title != null ? song.title : "Unknown", 14, true);
            TextView tvArtist = makeTv(ctx, song.artist != null ? song.artist : "", 12, false);
            tvArtist.setTextColor(android.graphics.Color.GRAY);
            info.addView(tvTitle); info.addView(tvArtist);
            row.addView(info);

            // Select button
            Button btnSel = new Button(ctx); btnSel.setText("Use");
            row.addView(btnSel);

            // Play preview
            btnPlay.setOnClickListener(v -> {
                if (song.audioUrl == null) { Toast.makeText(ctx, "Preview not available", Toast.LENGTH_SHORT).show(); return; }
                if (player[0] != null) { player[0].stop(); player[0].release(); player[0] = null; btnPlay.setText("▶"); }
                if ("▶".equals(btnPlay.getText().toString())) {
                    try {
                        player[0] = new MediaPlayer();
                        player[0].setDataSource(song.audioUrl);
                        player[0].setOnPreparedListener(mp -> mp.start());
                        player[0].setOnCompletionListener(mp -> btnPlay.setText("▶"));
                        player[0].prepareAsync();
                        btnPlay.setText("⏸");
                    } catch (Exception e) { Toast.makeText(ctx, "Playback error", Toast.LENGTH_SHORT).show(); }
                } else { if (player[0] != null) { player[0].pause(); btnPlay.setText("▶"); } }
            });

            btnSel.setOnClickListener(v -> {
                if (player[0] != null) { player[0].stop(); player[0].release(); player[0] = null; }
                if (cb != null) cb.onSelected(song.id, song.title, song.artist, song.audioUrl, 0);
                sheet.dismiss();
            });

            list.addView(row);
            View div = new View(ctx);
            div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            div.setBackgroundColor(android.graphics.Color.parseColor("#11000000")); list.addView(div);
        }
    }

    private static LinearLayout makeRoot(Context ctx) {
        LinearLayout r = new LinearLayout(ctx); r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(ctx,20), dp(ctx,12), dp(ctx,20), dp(ctx,32)); return r;
    }
    private static TextView makeTv(Context ctx, String text, int size, boolean bold) {
        TextView tv = new TextView(ctx); tv.setText(text); tv.setTextSize(size);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD); return tv;
    }
    private static int dp(Context ctx, int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }
}
