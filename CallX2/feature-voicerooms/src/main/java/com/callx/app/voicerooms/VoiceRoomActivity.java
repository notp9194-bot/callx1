package com.callx.app.voicerooms;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.utils.AlertDialogStyler;
import com.callx.app.voicerooms.VrModel.Member;
import com.callx.app.voicerooms.VrModel.Msg;
import com.callx.app.voicerooms.VrModel.Room;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * VoiceRoomActivity — ek voice room ki poori screen (UI only).
 *
 * Abhi kya hai: banner, topic, speakers (speaking ring demo), audience grid,
 * pinned message, chat + reactions, gift/poll/music/people/more sheets,
 * mic + raise-hand states.
 *
 * Abhi kya NAHI hai (baad me jodenge): WebRTC/SFU audio, Firebase room state,
 * real roles/permissions, coins deduction. Jahan jodna hai wahan TODO(backend)
 * comments laga diye hain.
 */
public class VoiceRoomActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "vr_id";
    public static final String EXTRA_TITLE = "vr_title";
    public static final String EXTRA_TOPIC = "vr_topic";
    public static final String EXTRA_DESC = "vr_desc";
    public static final String EXTRA_IS_HOST = "vr_is_host";

    private static final int AUDIENCE_PREVIEW = 8; // screenshot: 2 rows of 5 with Invite + More

    private Room room;
    private boolean isHost;

    private final List<Member> speakers = new ArrayList<>();
    private final List<Member> audience = new ArrayList<>();
    private final List<Msg> chat = new ArrayList<>();

    private VrAdapters.SpeakerAdapter speakerAdapter;
    private VrAdapters.ChatAdapter chatAdapter;

    private NestedScrollView scroll;
    private EditText etMessage;
    private ImageView btnEmojiSend, btnMic;
    private TextView raiseLabel;

    private boolean micOn = true;
    private boolean handRaised = false;

    // Demo: speakers mein "kaun bol raha hai" wali ring ghumana
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int speakIdx = 0;
    private final Runnable speakTick = new Runnable() {
        @Override public void run() {
            if (!speakers.isEmpty()) {
                for (int tries = 0; tries < speakers.size(); tries++) {
                    speakIdx = (speakIdx + 1) % speakers.size();
                    if (!speakers.get(speakIdx).muted) break;
                }
                speakerAdapter.setSpeaking(speakers.get(speakIdx).uid);
            }
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vr_room);

        resolveRoom();
        bindTopBar();
        bindBanner();
        bindSpeakers();
        bindAudience();
        bindPinnedAndChat();
        bindBottom();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { confirmLeave(); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        handler.postDelayed(speakTick, 1200);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(speakTick);
    }

    // ───────────────────────── data ─────────────────────────
    private void resolveRoom() {
        Intent in = getIntent();
        isHost = in.getBooleanExtra(EXTRA_IS_HOST, false);
        String id = in.getStringExtra(EXTRA_ID);

        // Demo rooms me se dhundo; na mile (ya host ne naya banaya) to extras se banao.
        Room found = null;
        if (id != null && !isHost) {
            for (Room r : VrDemoData.rooms()) if (id.equals(r.id)) { found = r; break; }
        }
        if (found == null && !isHost && id == null) found = VrDemoData.defaultRoom();

        if (found != null) {
            room = found;
            speakers.addAll(room.speakers);
            audience.addAll(VrDemoData.audience());
            chat.addAll(VrDemoData.chat());
        } else {
            // Host ka naya room: sirf "You" speaker, khaali audience/chat (empty state demo)
            room = new Room();
            room.id = id;
            room.title = orDefault(in.getStringExtra(EXTRA_TITLE), "My Room");
            room.topic = orDefault(in.getStringExtra(EXTRA_TOPIC), "Talk");
            room.description = orDefault(in.getStringExtra(EXTRA_DESC), "Tap to add a topic");
            room.hostName = "You";
            room.listeners = 1;
            room.audienceCount = 0;
            speakers.add(new Member("You", VrModel.ROLE_HOST, true, false));
        }
    }

    private static String orDefault(String s, String d) {
        return TextUtils.isEmpty(s) ? d : s;
    }

    // ───────────────────────── top bar ─────────────────────────
    private void bindTopBar() {
        findViewById(R.id.vr_btn_back).setOnClickListener(v -> confirmLeave());
        findViewById(R.id.vr_btn_search).setOnClickListener(v ->
                VrSheets.showPeople(this, speakers, audience, room.audienceCount, true, true));
        findViewById(R.id.vr_btn_more_top).setOnClickListener(v -> openMore());
    }

    // ───────────────────────── banner + topic ─────────────────────────
    private void bindBanner() {
        ((TextView) findViewById(R.id.vr_banner_listeners)).setText(VrUtil.compact(room.listeners));
        ((TextView) findViewById(R.id.vr_banner_title)).setText(room.title);
        ((TextView) findViewById(R.id.vr_banner_topic)).setText(room.topic);
        ((TextView) findViewById(R.id.vr_banner_host)).setText("Hosted by " + room.hostName);
        findViewById(R.id.vr_banner_verified).setVisibility(
                room.hostVerified ? View.VISIBLE : View.GONE);

        View banner = findViewById(R.id.vr_banner);
        findViewById(R.id.vr_banner_close).setOnClickListener(v ->
                banner.animate().alpha(0f).setDuration(180)
                        .withEndAction(() -> banner.setVisibility(View.GONE)).start());

        ((TextView) findViewById(R.id.vr_topic_text)).setText(
                orDefault(room.description, "Tap to add a topic"));
        findViewById(R.id.vr_topic_row).setOnClickListener(v ->
                Toast.makeText(this, isHost ? "Edit topic (backend later)" : room.description,
                        Toast.LENGTH_SHORT).show());
    }

    // ───────────────────────── speakers ─────────────────────────
    private void bindSpeakers() {
        ((TextView) findViewById(R.id.vr_speakers_title)).setText("Speakers (" + speakers.size() + ")");
        findViewById(R.id.vr_speakers_see_all).setOnClickListener(v ->
                VrSheets.showPeople(this, speakers, audience, room.audienceCount, false, false));

        RecyclerView rv = findViewById(R.id.vr_rv_speakers);
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        speakerAdapter = new VrAdapters.SpeakerAdapter(speakers, m ->
                VrSheets.showPeople(this, speakers, audience, room.audienceCount, false, false));
        rv.setAdapter(speakerAdapter);
    }

    // ───────────────────────── audience grid ─────────────────────────
    private void bindAudience() {
        ((TextView) findViewById(R.id.vr_audience_title)).setText(
                "Audience (" + VrUtil.compact(room.audienceCount) + ")");

        List<Member> shown = new ArrayList<>(
                audience.subList(0, Math.min(AUDIENCE_PREVIEW, audience.size())));
        boolean showMore = audience.size() > AUDIENCE_PREVIEW;

        RecyclerView rv = findViewById(R.id.vr_rv_audience);
        rv.setLayoutManager(new GridLayoutManager(this, 5));
        rv.setNestedScrollingEnabled(false);
        rv.setAdapter(new VrAdapters.AudienceAdapter(shown, showMore,
                new VrAdapters.AudienceAdapter.Callbacks() {
                    @Override public void onInvite() { shareRoom(); }
                    @Override public void onMember(Member m) {
                        VrSheets.showPeople(VoiceRoomActivity.this, speakers, audience,
                                room.audienceCount, true, false);
                    }
                    @Override public void onMore() {
                        VrSheets.showPeople(VoiceRoomActivity.this, speakers, audience,
                                room.audienceCount, true, false);
                    }
                }));
    }

    // ───────────────────────── pinned + chat ─────────────────────────
    private void bindPinnedAndChat() {
        View pinned = findViewById(R.id.vr_pinned_bar);
        if (isHost) {
            pinned.setVisibility(View.GONE); // naye room me abhi koi pinned nahi
        } else {
            ((TextView) findViewById(R.id.vr_pinned_by)).setText(room.hostName + " pinned a message");
        }
        findViewById(R.id.vr_pinned_close).setOnClickListener(v -> pinned.setVisibility(View.GONE));

        scroll = findViewById(R.id.vr_scroll);
        RecyclerView rv = findViewById(R.id.vr_rv_chat);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setNestedScrollingEnabled(false);
        chatAdapter = new VrAdapters.ChatAdapter(chat);
        rv.setAdapter(chatAdapter);
    }

    private void addMessage(String text) {
        String time = new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date());
        // TODO(backend): push to voiceRooms/{roomId}/chat (limitToLast(50) listener)
        chat.add(new Msg("You", time, text, 0));
        chatAdapter.notifyItemInserted(chat.size() - 1);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ───────────────────────── bottom: input + action bar ─────────────────────────
    private void bindBottom() {
        etMessage = findViewById(R.id.vr_et_message);
        btnEmojiSend = findViewById(R.id.vr_btn_emoji_send);
        btnMic = findViewById(R.id.vr_btn_mic);
        raiseLabel = findViewById(R.id.vr_raise_label);

        // Gift
        findViewById(R.id.vr_btn_gift).setOnClickListener(v ->
                VrSheets.showGifts(this, "Everyone in the room", g -> {
                    // TODO(backend): server-side coin deduction (Cloud Function) before this
                    addMessage("sent a " + g.emoji + " " + g.name);
                    Toast.makeText(this, "Gift sent " + g.emoji, Toast.LENGTH_SHORT).show();
                }));

        // Emoji <-> Send icon swap
        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                boolean has = e.toString().trim().length() > 0;
                btnEmojiSend.setImageResource(has ? R.drawable.ic_send_fill : R.drawable.ic_emoji);
                btnEmojiSend.setColorFilter(getColor(has ? R.color.vr_primary : R.color.vr_text));
            }
        });
        btnEmojiSend.setOnClickListener(v -> {
            if (etMessage.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "Emoji picker coming soon", Toast.LENGTH_SHORT).show();
            } else {
                sendTyped();
            }
        });
        etMessage.setOnEditorActionListener((tv, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); return true; }
            return false;
        });

        // Mic
        applyMicUi();
        btnMic.setOnClickListener(v -> {
            micOn = !micOn;
            applyMicUi();
            // Speaker list me apna badge bhi update karo (host-created room me "You")
            for (int i = 0; i < speakers.size(); i++) {
                if ("you".equals(speakers.get(i).uid)) {
                    speakers.get(i).muted = !micOn;
                    speakerAdapter.notifyItemChanged(i);
                }
            }
            Toast.makeText(this, micOn ? "Mic on" : "Mic muted", Toast.LENGTH_SHORT).show();
        });

        // Action bar
        if (isHost) raiseLabel.setText("Requests");
        findViewById(R.id.vr_act_raise).setOnClickListener(v -> {
            if (isHost) {
                Toast.makeText(this, "No hand-raise requests yet", Toast.LENGTH_SHORT).show();
                return;
            }
            handRaised = !handRaised;
            raiseLabel.setText(handRaised ? "Lower\nHand" : "Raise\nHand");
            raiseLabel.setTextColor(getColor(handRaised ? R.color.vr_primary : R.color.vr_text));
            Toast.makeText(this, handRaised ? "Hand raised \u270B" : "Hand lowered",
                    Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.vr_act_poll).setOnClickListener(v ->
                VrSheets.showPoll(this, (q, opts, dur) ->
                        addMessage("\uD83D\uDCCA Poll: " + q + "  \u2022  "
                                + TextUtils.join(" / ", opts) + "  (" + dur + ")")));
        findViewById(R.id.vr_act_music).setOnClickListener(v -> VrSheets.showMusic(this));
        findViewById(R.id.vr_act_share).setOnClickListener(v -> shareRoom());
        findViewById(R.id.vr_act_more).setOnClickListener(v -> openMore());
    }

    private void sendTyped() {
        String t = etMessage.getText().toString().trim();
        if (t.isEmpty()) return;
        addMessage(t);
        etMessage.setText("");
    }

    private void applyMicUi() {
        if (micOn) {
            btnMic.setBackgroundResource(R.drawable.vr_bg_circle_primary);
            btnMic.setImageResource(R.drawable.ic_mic);
            btnMic.setColorFilter(getColor(R.color.vr_white));
        } else {
            btnMic.setBackgroundResource(R.drawable.vr_bg_circle_soft);
            btnMic.setImageResource(R.drawable.ic_mic_off);
            btnMic.setColorFilter(getColor(R.color.vr_primary));
        }
    }

    // ───────────────────────── actions ─────────────────────────
    private void openMore() {
        VrSheets.showMore(this, room.title, new VrSheets.MoreActions() {
            @Override public void onPeople() {
                VrSheets.showPeople(VoiceRoomActivity.this, speakers, audience,
                        room.audienceCount, false, false);
            }
            @Override public void onShare() { shareRoom(); }
            @Override public void onMuteAudio() {
                Toast.makeText(VoiceRoomActivity.this, "Room audio muted (UI demo)",
                        Toast.LENGTH_SHORT).show();
            }
            @Override public void onLeave() { finish(); }
        });
    }

    private void shareRoom() {
        // TODO(backend): real deep link (server already serves /u/, /g/ style pages)
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT,
                "Join \"" + room.title + "\" live on CallX \uD83C\uDF99\uFE0F  callx://room/" + room.id);
        startActivity(Intent.createChooser(send, "Invite to room"));
    }

    private void confirmLeave() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isHost ? "End room?" : "Leave room?")
                .setMessage(isHost
                        ? "The room will close for everyone."
                        : "You can rejoin anytime while the room is live.")
                .setNegativeButton("Stay", null)
                .setPositiveButton(isHost ? "End room" : "Leave", (d, w) -> finish())
                .create();
        AlertDialogStyler.showRounded(dialog);
    }
}
