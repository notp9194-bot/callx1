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
 * More, Create room). Sheets sirf UI hain: callbacks screen ko batate hain,
 * network / Firebase kaam Activity (VrRepo / VrApi) karti hai.
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

    static void showGifts(Activity act, String toLabel, long coinBalance, GiftSent cb) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_gift, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);

        ((TextView) v.findViewById(R.id.vr_gift_to)).setText(
                "To: " + toLabel + "   \u2022   Balance: " + coinBalance + " coins");
        TextView send = v.findViewById(R.id.vr_gift_send);
        RecyclerView rv = v.findViewById(R.id.vr_rv_gifts);
        final Gift[] picked = {null};

        rv.setLayoutManager(new GridLayoutManager(act, 4));
        rv.setAdapter(new VrAdapters.GiftAdapter(VrCatalog.gifts(), g -> {
            picked[0] = g;
            send.setText("Send " + g.emoji + " " + g.name + "  \u2022  " + g.coins + " coins");
        }));
        send.setOnClickListener(x -> {
            if (picked[0] == null) {
                Toast.makeText(act, act.getString(R.string.vr_pick_a_gift_first), Toast.LENGTH_SHORT).show();
                return;
            }
            if (picked[0].coins > coinBalance) {
                Toast.makeText(act, "Not enough coins (" + coinBalance + " available)",
                        Toast.LENGTH_SHORT).show();
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
                Toast.makeText(act, act.getString(R.string.vr_max_4_options), Toast.LENGTH_SHORT).show();
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

        List<Track> tracks = VrCatalog.tracks();
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
        void onLeave();
    }

    static void showMore(Activity act, String roomTitle, boolean isHost, MoreActions a) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_more, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);
        ((TextView) v.findViewById(R.id.vr_more_title)).setText(roomTitle);
        ((TextView) v.findViewById(R.id.vr_more_leave_text)).setText(act.getString(
                isHost ? R.string.vr_end_room : R.string.vr_leave_quietly));

        v.findViewById(R.id.vr_more_people).setOnClickListener(x -> { d.dismiss(); a.onPeople(); });
        v.findViewById(R.id.vr_more_share).setOnClickListener(x -> { d.dismiss(); a.onShare(); });
        // v794: "Preview as host/listener" (demo) aur "Mute room audio" (abhi wired nahi) hata diye
        v.findViewById(R.id.vr_more_mute).setVisibility(View.GONE);
        v.findViewById(R.id.vr_more_role).setVisibility(View.GONE);
        v.findViewById(R.id.vr_more_leave).setOnClickListener(x -> { d.dismiss(); a.onLeave(); });
        d.show();
        expand(d);
    }

    // ───────────────────────── Emoji picker ─────────────────────────
    interface EmojiPicked { void onPicked(String emoji); }

    static void showEmoji(Activity act, EmojiPicked cb) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_emoji, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);
        RecyclerView rv = v.findViewById(R.id.vr_rv_emoji);
        rv.setLayoutManager(new GridLayoutManager(act, 7));
        // Sheet khula rehta hai taaki ek saath kai emoji daale ja sakein.
        rv.setAdapter(new VrAdapters.EmojiAdapter(VrCatalog.emojis(), e -> {
            if (cb != null) cb.onPicked(e);
        }));
        d.show();
        expand(d);
    }

    // ───────────────────────── Member actions ─────────────────────────
    interface MemberActions {
        void onToggleMute(Member m);
        void onMoveToAudience(Member m);
        void onInviteToSpeak(Member m);
        void onRemove(Member m);
        void onProfile(Member m);
    }

    private static void addRow(Activity act, LinearLayout parent, BottomSheetDialog d,
                               int iconRes, String label, boolean danger, Runnable action) {
        View row = LayoutInflater.from(act).inflate(R.layout.item_vr_action_row, parent, false);
        ImageView icon = row.findViewById(R.id.vr_ar_icon);
        TextView tv = row.findViewById(R.id.vr_ar_label);
        icon.setImageResource(iconRes);
        icon.setColorFilter(act.getColor(danger ? R.color.vr_danger : R.color.vr_primary));
        tv.setText(label);
        tv.setTextColor(act.getColor(danger ? R.color.vr_danger : R.color.vr_text));
        row.setOnClickListener(x -> { d.dismiss(); action.run(); });
        parent.addView(row);
    }

    static void showMember(Activity act, Member m, boolean viewerIsHost, MemberActions cb) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_member, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);

        VrAdapters.bindAvatar(v.findViewById(R.id.vr_mem_avatar), m);
        ((TextView) v.findViewById(R.id.vr_mem_name)).setText(m.name);
        v.findViewById(R.id.vr_mem_verified).setVisibility(m.verified ? View.VISIBLE : View.GONE);
        boolean host = VrModel.ROLE_HOST.equals(m.role);
        boolean speaker = VrModel.ROLE_SPEAKER.equals(m.role);
        ((TextView) v.findViewById(R.id.vr_mem_role)).setText(
                host ? "Host" : speaker ? "Speaker"
                        : (m.handRaised ? "\u270B Wants to speak" : "Listener"));

        LinearLayout actions = v.findViewById(R.id.vr_mem_actions);
        if (viewerIsHost && !host) {
            if (speaker) {
                addRow(act, actions, d, m.hostMuted ? R.drawable.ic_mic : R.drawable.ic_mic_off,
                        m.hostMuted ? "Allow speaker to unmute" : "Mute speaker", false,
                        () -> cb.onToggleMute(m));
                addRow(act, actions, d, R.drawable.ic_group, "Move to audience", false,
                        () -> cb.onMoveToAudience(m));
            } else {
                addRow(act, actions, d, R.drawable.ic_mic,
                        m.handRaised ? "Accept request to speak" : "Invite to speak", false,
                        () -> cb.onInviteToSpeak(m));
            }
        }
        addRow(act, actions, d, R.drawable.ic_person, "View profile", false, () -> cb.onProfile(m));
        if (viewerIsHost && !host) {
            addRow(act, actions, d, R.drawable.ic_close, "Remove from room", true, () -> cb.onRemove(m));
        }
        d.show();
        expand(d);
    }

    // ───────────────────────── Speaker requests (host) ─────────────────────────
    interface RequestsCb {
        void onAccept(Member m);
        void onDecline(Member m);
    }

    static void showRequests(Activity act, List<Member> requests, RequestsCb cb) {
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_vr_requests, null, false);
        BottomSheetDialog d = sheet(act);
        d.setContentView(v);

        TextView title = v.findViewById(R.id.vr_req_title);
        RecyclerView rv = v.findViewById(R.id.vr_rv_requests);
        View empty = v.findViewById(R.id.vr_req_empty);

        final VrAdapters.RequestAdapter[] adapter = new VrAdapters.RequestAdapter[1];
        Runnable refresh = () -> {
            title.setText("Speaker requests (" + requests.size() + ")");
            empty.setVisibility(requests.isEmpty() ? View.VISIBLE : View.GONE);
            rv.setVisibility(requests.isEmpty() ? View.GONE : View.VISIBLE);
            adapter[0].notifyDataSetChanged();
        };
        adapter[0] = new VrAdapters.RequestAdapter(requests, new VrAdapters.RequestAdapter.Actions() {
            @Override public void onAccept(Member m) {
                requests.remove(m);
                cb.onAccept(m);
                refresh.run();
            }
            @Override public void onDecline(Member m) {
                requests.remove(m);
                cb.onDecline(m);
                refresh.run();
            }
        });
        rv.setLayoutManager(new LinearLayoutManager(act));
        rv.setAdapter(adapter[0]);
        refresh.run();
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
        for (String c : VrCatalog.CATEGORIES) if (!"All".equals(c)) cats.add(c);
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
                Toast.makeText(act, act.getString(R.string.vr_give_your_room_a), Toast.LENGTH_SHORT).show();
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
