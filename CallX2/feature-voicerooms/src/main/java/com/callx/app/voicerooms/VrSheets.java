package com.callx.app.voicerooms;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.voicerooms.VrModel.Gift;
import com.callx.app.voicerooms.VrModel.Member;
import com.callx.app.voicerooms.VrModel.Track;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VrSheets — Voice Room ke saare bottom sheets (People, Gift, Poll, Music,
 * More, Create room). Sab UI-only: callbacks sirf screen ko batate hain,
 * koi network / Firebase call nahi hai.
 */
final class VrSheets {
    private VrSheets() {}

    // ───────────────────────── shared helpers ─────────────────────────
    private static BottomSheetDialog sheet(Context c) {
        return new BottomSheetDialog(c, R.style.Theme_VoiceRoom_BottomSheetDialog);
    }

    private static void expand(BottomSheetDialog d) {
        BottomSheetBehavior<?> b = d.getBehavior();
        b.setSkipCollapsed(true);
        b.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    /** Selectable pill chip (Home categories, Create-room, Poll duration, People tabs). */
    static TextView chip(Context c, String label, boolean selected) {
        TextView t = new TextView(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, VrUtil.dp(c, 36));
        lp.setMarginEnd(VrUtil.dp(c, 8));
        t.setLayoutParams(lp);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(VrUtil.dp(c, 16), 0, VrUtil.dp(c, 16), 0);
        t.setTextSize(14);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        t.setText(label);
        styleChip(t, selected);
        return t;
    }

    static void styleChip(TextView t, boolean selected) {
        t.setBackgroundResource(selected ? R.drawable.vr_bg_chip_on : R.drawable.vr_bg_chip_off);
        t.setTextColor(t.getContext().getColor(selected ? R.color.vr_white : R.color.vr_text));
    }

    // ───────────────────────── People ─────────────────────────
    static void showPeople(Activity act, List<Member> speakers, List<Member> audience,
                           int audienceTotal, boolean startOnAudience, boolean focusSearch) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_participants, null, false);
        BottomSheetDialog d = sheet(act);
        int h = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.80f);
        d.setContentView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        TextView tabSpeakers = v.findViewById(R.id.vr_tab_speakers);
        TextView tabAudience = v.findViewById(R.id.vr_tab_audience);
        EditText search = v.findViewById(R.id.vr_people_search);
        RecyclerView rv = v.findViewById(R.id.vr_rv_people);

        tabSpeakers.setText("Speakers (" + speakers.size() + ")");
        tabAudience.setText("Audience (" + VrUtil.compact(audienceTotal) + ")");

        VrAdapters.PeopleAdapter adapter = new VrAdapters.PeopleAdapter();
        rv.setLayoutManager(new LinearLayoutManager(act));
        rv.setAdapter(adapter);

        final boolean[] onAudience = {startOnAudience};
        Runnable refresh = () -> {
            styleChip(tabSpeakers, !onAudience[0]);
            styleChip(tabAudience, onAudience[0]);
            String q = search.getText() == null ? "" :
                    search.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<Member> src = onAudience[0] ? audience : speakers;
            List<Member> out = new ArrayList<>();
            for (Member m : src) {
                if (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q)) out.add(m);
            }
            adapter.submit(out);
        };
        tabSpeakers.setOnClickListener(x -> { onAudience[0] = false; refresh.run(); });
        tabAudience.setOnClickListener(x -> { onAudience[0] = true; refresh.run(); });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { refresh.run(); }
        });
        refresh.run();

        d.show();
        expand(d);
        if (focusSearch) {
            if (d.getWindow() != null) {
                d.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
            search.requestFocus();
        }
    }

    // ───────────────────────── Gift ─────────────────────────
    interface GiftSent { void onGiftSent(Gift g); }

    static void showGifts(Activity act, String toLabel, GiftSent cb) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_gift, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);

        ((TextView) v.findViewById(R.id.vr_gift_to)).setText("To: " + toLabel);
        TextView send = v.findViewById(R.id.vr_gift_send);
        RecyclerView rv = v.findViewById(R.id.vr_rv_gifts);
        final Gift[] picked = {null};

        rv.setLayoutManager(new GridLayoutManager(act, 4));
        rv.setAdapter(new VrAdapters.GiftAdapter(VrDemoData.gifts(), g -> {
            picked[0] = g;
            send.setText("Send " + g.emoji + " " + g.name + "  \u2022  " + g.coins + " coins");
        }));
        send.setOnClickListener(x -> {
            if (picked[0] == null) {
                Toast.makeText(act, "Pick a gift first", Toast.LENGTH_SHORT).show();
                return;
            }
            d.dismiss();
            if (cb != null) cb.onGiftSent(picked[0]);
        });
        d.show();
        expand(d);
    }

    // ───────────────────────── Poll ─────────────────────────
    interface PollStarted { void onPollStarted(String question, List<String> options, String duration); }

    static void showPoll(Activity act, PollStarted cb) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_poll, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);

        EditText question = v.findViewById(R.id.vr_poll_question);
        LinearLayout options = v.findViewById(R.id.vr_poll_options);
        TextView addOption = v.findViewById(R.id.vr_poll_add_option);
        LinearLayout durations = v.findViewById(R.id.vr_poll_durations);
        TextView start = v.findViewById(R.id.vr_poll_start);

        addOptionField(act, options, "Option 1");
        addOptionField(act, options, "Option 2");
        addOption.setOnClickListener(x -> {
            if (options.getChildCount() >= 4) {
                Toast.makeText(act, "Max 4 options", Toast.LENGTH_SHORT).show();
                return;
            }
            addOptionField(act, options, "Option " + (options.getChildCount() + 1));
            if (options.getChildCount() >= 4) addOption.setVisibility(View.GONE);
        });

        final String[] labels = {"1 min", "5 min", "15 min"};
        final int[] dur = {1};
        final List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = chip(act, labels[i], i == dur[0]);
            c.setOnClickListener(x -> {
                dur[0] = idx;
                for (int k = 0; k < chips.size(); k++) styleChip(chips.get(k), k == idx);
            });
            chips.add(c);
            durations.addView(c);
        }

        start.setOnClickListener(x -> {
            String q = question.getText().toString().trim();
            List<String> opts = new ArrayList<>();
            for (int i = 0; i < options.getChildCount(); i++) {
                String s = ((EditText) options.getChildAt(i)).getText().toString().trim();
                if (!s.isEmpty()) opts.add(s);
            }
            if (q.isEmpty() || opts.size() < 2) {
                Toast.makeText(act, "Add a question and at least 2 options",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            d.dismiss();
            if (cb != null) cb.onPollStarted(q, opts, labels[dur[0]]);
        });
        d.show();
        expand(d);
    }

    private static void addOptionField(Context c, LinearLayout parent, String hint) {
        EditText e = new EditText(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = VrUtil.dp(c, 10);
        e.setLayoutParams(lp);
        e.setBackgroundResource(R.drawable.vr_bg_edit);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setMinHeight(VrUtil.dp(c, 52));
        e.setPadding(VrUtil.dp(c, 14), VrUtil.dp(c, 14), VrUtil.dp(c, 14), VrUtil.dp(c, 14));
        e.setTextSize(16);
        e.setTextColor(c.getColor(R.color.vr_text));
        e.setHintTextColor(c.getColor(R.color.vr_text_sub));
        parent.addView(e);
    }

    // ───────────────────────── Music ─────────────────────────
    static void showMusic(Activity act) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_music, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);

        List<Track> tracks = VrDemoData.tracks();
        TextView title = v.findViewById(R.id.vr_np_title);
        TextView artist = v.findViewById(R.id.vr_np_artist);
        ImageView play = v.findViewById(R.id.vr_np_play);
        ProgressBar progress = v.findViewById(R.id.vr_np_progress);
        RecyclerView rv = v.findViewById(R.id.vr_rv_tracks);

        title.setText(tracks.get(0).title);
        artist.setText(tracks.get(0).artist);

        final boolean[] playing = {true};
        play.setOnClickListener(x -> {
            playing[0] = !playing[0];
            play.setImageResource(playing[0] ? R.drawable.ic_pause : R.drawable.ic_play);
        });

        rv.setLayoutManager(new LinearLayoutManager(act));
        rv.setAdapter(new VrAdapters.TrackAdapter(tracks, t -> {
            title.setText(t.title);
            artist.setText(t.artist);
            progress.setProgress(0);
            playing[0] = true;
            play.setImageResource(R.drawable.ic_pause);
        }));

        v.findViewById(R.id.vr_music_add).setOnClickListener(x ->
                Toast.makeText(act, "Song picker will connect with the backend later",
                        Toast.LENGTH_SHORT).show());
        d.show();
        expand(d);
    }

    // ───────────────────────── More ─────────────────────────
    interface MoreActions {
        void onPeople();
        void onShare();
        void onMuteAudio();
        void onLeave();
    }

    static void showMore(Activity act, String roomTitle, MoreActions a) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_more, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);
        ((TextView) v.findViewById(R.id.vr_more_title)).setText(roomTitle);

        v.findViewById(R.id.vr_more_people).setOnClickListener(x -> { d.dismiss(); a.onPeople(); });
        v.findViewById(R.id.vr_more_share).setOnClickListener(x -> { d.dismiss(); a.onShare(); });
        v.findViewById(R.id.vr_more_mute).setOnClickListener(x -> { d.dismiss(); a.onMuteAudio(); });
        v.findViewById(R.id.vr_more_leave).setOnClickListener(x -> { d.dismiss(); a.onLeave(); });
        d.show();
        expand(d);
    }

    // ───────────────────────── Create room ─────────────────────────
    interface RoomCreated { void onCreated(String title, String topic, String category, boolean allowRequests); }

    static void showCreateRoom(Activity act, RoomCreated cb) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_create_room, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        EditText title = v.findViewById(R.id.vr_create_title);
        EditText topic = v.findViewById(R.id.vr_create_topic);
        LinearLayout chipRow = v.findViewById(R.id.vr_create_chips);
        SwitchMaterial allow = v.findViewById(R.id.vr_create_switch);

        // "All" sirf filter ke liye hai — room banate waqt category chahiye.
        final List<String> cats = new ArrayList<>();
        for (String c : VrDemoData.CATEGORIES) if (!"All".equals(c)) cats.add(c);
        final int[] sel = {0};
        final List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < cats.size(); i++) {
            final int idx = i;
            TextView c = chip(act, cats.get(i), i == 0);
            c.setOnClickListener(x -> {
                sel[0] = idx;
                for (int k = 0; k < chips.size(); k++) styleChip(chips.get(k), k == idx);
            });
            chips.add(c);
            chipRow.addView(c);
        }

        v.findViewById(R.id.vr_create_start).setOnClickListener(x -> {
            String t = title.getText().toString().trim();
            if (t.isEmpty()) {
                Toast.makeText(act, "Give your room a name", Toast.LENGTH_SHORT).show();
                return;
            }
            String tp = topic.getText().toString().trim();
            d.dismiss();
            if (cb != null) cb.onCreated(t, tp, cats.get(sel[0]), allow.isChecked());
        });
        d.show();
        expand(d);
    }
}
