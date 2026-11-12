package com.callx.app.voicerooms;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.utils.AlertDialogStyler;
import com.callx.app.voicerooms.VrModel.Member;
import com.callx.app.voicerooms.VrModel.Msg;
import com.callx.app.voicerooms.VrModel.Poll;
import com.callx.app.voicerooms.VrModel.Room;
import com.google.android.material.appbar.AppBarLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * VoiceRoomActivity — ek voice room ki screen (v794: asli backend).
 *
 * Data: VrRepo.RoomWatch (Firebase voiceRooms/{id}/...). Audio: VrSession (LiveKit, app-wide,
 * isliye minimize karne par bhi chalta hai). Permissions: server (VrApi) — app sirf request bhejta hai.
 * Deep link: callx://room/{id} ya https://callx-server.onrender.com/room/{id}.
 */
public class VoiceRoomActivity extends AppCompatActivity
        implements VrRepo.RoomListener, VrSession.Observer {

    public static final String EXTRA_ID = "vr_id";

    private static final int AUDIENCE_PREVIEW = 8;
    private static final int CHAT_CAP = 50;

    private String roomId;
    private String myUid = "";
    private Room room;                    // latest meta
    private boolean isHost, isSpeaker;
    private boolean sessionStarted, leaving, ended;

    private final List<Member> speakers = new ArrayList<>();
    private final List<Member> audience = new ArrayList<>();
    private final List<Member> requests = new ArrayList<>();
    private final List<Msg> chat = new ArrayList<>();
    private Poll poll;
    private boolean handRaised;

    private VrAdapters.SpeakerAdapter speakerAdapter;
    private VrAdapters.AudienceAdapter audienceAdapter;
    private VrAdapters.ChatAdapter chatAdapter;
    private VrRepo.RoomWatch watch;

    private AppBarLayout appBar;
    private RecyclerView rvChat;
    private ViewGroup fxLayer;
    private View chatEmpty, audienceEmpty, pinnedBar, pollBar;
    private TextView newMsgsPill, speakersTitle, audienceTitle, raiseLabel;
    private ImageView raiseIcon, btnEmojiSend, btnMic;
    private EditText etMessage;

    private int appBarOffset = 0;
    private int unseen = 0;
    private boolean pinnedHiddenLocally;
    private String pinnedText;

    private final Random rng = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> micPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vr_room);

        roomId = resolveRoomId(getIntent());
        String uid = VrRepo.myUid();
        if (roomId == null || uid == null) {
            Toast.makeText(this, "Room not available", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        myUid = uid;

        micPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) toggleMic();
                    else Toast.makeText(this, "Microphone permission is needed to speak",
                            Toast.LENGTH_SHORT).show();
                });

        room = new Room();
        room.id = roomId;
        room.title = "Voice room";
        room.topic = "";
        room.description = "";
        room.hostName = "";

        bindViews();
        bindTopBar();
        bindSpeakers();
        bindAudience();
        bindPinnedAndChat();
        bindBottom();
        applyRole();
        applyMicUi();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { minimize(); }
        });

        watch = new VrRepo.RoomWatch(roomId, this);
        watch.start();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String id = resolveRoomId(intent);
        if (id != null && !id.equals(roomId)) {
            // Doosra room khulna hai: pehle ye activity band, naya khule
            setIntent(intent);
            finish();
            startActivity(intent);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        VrSession.get().setObserver(this);
        if (VrSession.get().isActiveFor(roomId)) {
            applyMicUi();
            speakerAdapter.setSpeakingSet(VrSession.get().speaking());
        }
    }

    @Override protected void onPause() {
        super.onPause();
        VrSession.get().clearObserver(this);
    }

    @Override protected void onDestroy() {
        if (watch != null) watch.stop();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static String resolveRoomId(Intent in) {
        String id = in.getStringExtra(EXTRA_ID);
        if (!TextUtils.isEmpty(id)) return id;
        Uri d = in.getData();
        if (d != null) {
            if ("room".equals(d.getHost()) && d.getPathSegments().size() >= 1) return d.getPathSegments().get(0);
            List<String> seg = d.getPathSegments();
            if (seg.size() >= 2 && "room".equals(seg.get(0))) return seg.get(1);
        }
        return null;
    }

    // ───────────────────────── views ─────────────────────────
    private void bindViews() {
        appBar = findViewById(R.id.vr_appbar);
        rvChat = findViewById(R.id.vr_rv_chat);
        fxLayer = findViewById(R.id.vr_fx_layer);
        chatEmpty = findViewById(R.id.vr_chat_empty);
        audienceEmpty = findViewById(R.id.vr_audience_empty);
        pinnedBar = findViewById(R.id.vr_pinned_bar);
        pollBar = findViewById(R.id.vr_poll_bar);
        newMsgsPill = findViewById(R.id.vr_new_msgs);
        speakersTitle = findViewById(R.id.vr_speakers_title);
        audienceTitle = findViewById(R.id.vr_audience_title);
        raiseLabel = findViewById(R.id.vr_raise_label);
        raiseIcon = findViewById(R.id.vr_raise_icon);
        etMessage = findViewById(R.id.vr_et_message);
        btnEmojiSend = findViewById(R.id.vr_btn_emoji_send);
        btnMic = findViewById(R.id.vr_btn_mic);
        pinnedBar.setVisibility(View.GONE);
    }

    private void bindTopBar() {
        findViewById(R.id.vr_btn_back).setOnClickListener(v -> minimize());
        findViewById(R.id.vr_btn_search).setOnClickListener(v ->
                VrSheets.showPeople(this, speakers, audience, room.audienceCount, true, true));
        findViewById(R.id.vr_btn_more_top).setOnClickListener(v -> openMore());
        ((VrMicView) findViewById(R.id.vr_banner_mic)).setFloating(true);
        View banner = findViewById(R.id.vr_banner);
        findViewById(R.id.vr_banner_close).setOnClickListener(v ->
                banner.animate().alpha(0f).setDuration(180)
                        .withEndAction(() -> banner.setVisibility(View.GONE)).start());
        findViewById(R.id.vr_topic_row).setOnClickListener(v -> {
            if (isHost) editTopic();
            else if (!TextUtils.isEmpty(room.description))
                Toast.makeText(this, room.description, Toast.LENGTH_SHORT).show();
        });
    }

    private void renderBanner() {
        ((TextView) findViewById(R.id.vr_banner_listeners)).setText(VrUtil.compact(totalListeners()));
        ((TextView) findViewById(R.id.vr_banner_title)).setText(room.title);
        ((TextView) findViewById(R.id.vr_banner_topic)).setText(room.topic);
        ((TextView) findViewById(R.id.vr_banner_host)).setText("Hosted by " + room.hostName);
        findViewById(R.id.vr_banner_verified).setVisibility(room.hostVerified ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.vr_topic_text)).setText(
                orDefault(room.description, isHost ? "Tap to add a topic" : "Welcome to the room"));
    }

    private int totalListeners() { return room.audienceCount + speakers.size(); }

    private static String orDefault(String s, String d) { return TextUtils.isEmpty(s) ? d : s; }

    private void editTopic() {
        EditText et = new EditText(this);
        et.setText(room.description);
        et.setHint("What's this room about?");
        et.setSingleLine(true);
        int pad = VrUtil.dp(this, 20);
        et.setPadding(pad, pad, pad, pad);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Room topic")
                .setView(et)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (x, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.length() > 140) t = t.substring(0, 140);
                    VrRepo.setDescription(roomId, t);
                }).create();
        AlertDialogStyler.showRounded(d);
    }

    // ───────────────────────── speakers / audience ─────────────────────────
    private void bindSpeakers() {
        findViewById(R.id.vr_speakers_see_all).setOnClickListener(v ->
                VrSheets.showPeople(this, speakers, audience, room.audienceCount, false, false));
        RecyclerView rv = findViewById(R.id.vr_rv_speakers);
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        speakerAdapter = new VrAdapters.SpeakerAdapter(speakers, this::openMember);
        rv.setAdapter(speakerAdapter);
    }

    private void bindAudience() {
        RecyclerView rv = findViewById(R.id.vr_rv_audience);
        rv.setLayoutManager(new GridLayoutManager(this, 5));
        rv.setNestedScrollingEnabled(false);
        audienceAdapter = new VrAdapters.AudienceAdapter(new ArrayList<>(), false,
                new VrAdapters.AudienceAdapter.Callbacks() {
                    @Override public void onInvite() { shareRoom(); }
                    @Override public void onMember(Member m) { openMember(m); }
                    @Override public void onMore() {
                        VrSheets.showPeople(VoiceRoomActivity.this, speakers, audience,
                                room.audienceCount, true, false);
                    }
                });
        rv.setAdapter(audienceAdapter);
    }

    private void refreshAudienceUi() {
        List<Member> shown = new ArrayList<>(audience.subList(0, Math.min(AUDIENCE_PREVIEW, audience.size())));
        audienceAdapter.submit(shown, audience.size() > AUDIENCE_PREVIEW || room.audienceCount > AUDIENCE_PREVIEW);
        audienceTitle.setText("Audience (" + VrUtil.compact(room.audienceCount) + ")");
        audienceEmpty.setVisibility(audience.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ───────────────────────── RoomListener (Firebase) ─────────────────────────
    @Override public void onMeta(Room r) {
        boolean first = !sessionStarted;
        room = r;
        renderBanner();
        refreshAudienceUi();
        recomputeRole();
        if (first && r.live) startSession();
    }

    @Override public void onRoomEnded(String reason) {
        if (ended) return;
        ended = true;
        if (!leaving) {
            Toast.makeText(this, "This room has ended", Toast.LENGTH_LONG).show();
            VrSession.get().leave(false);
        }
        finish();
    }

    @Override public void onBanned() {
        if (ended) return;
        ended = true;
        Toast.makeText(this, "You were removed from this room", Toast.LENGTH_LONG).show();
        VrSession.get().leave(false);
        finish();
    }

    @Override public void onSpeakers(List<Member> list) {
        speakers.clear();
        speakers.addAll(list);
        speakersTitle.setText("Speakers (" + speakers.size() + ")");
        speakerAdapter.notifyDataSetChanged();
        renderBanner();
        recomputeRole();
        // Host ne mute kiya ya demote hua => mic zabardasti band
        Member me = findSpeaker(myUid);
        if (me == null || me.hostMuted) VrSession.get().forceMicOff();
    }

    @Override public void onAudience(List<Member> list) {
        audience.clear();
        audience.addAll(list);
        refreshAudienceUi();
    }

    @Override public void onHandRaises(List<Member> raised) {
        requests.clear();
        requests.addAll(raised);
        boolean mine = false;
        for (Member m : raised) if (m.uid.equals(myUid)) mine = true;
        handRaised = mine;
        applyRole();
    }

    private Member findSpeaker(String uid) {
        for (Member m : speakers) if (m.uid.equals(uid)) return m;
        return null;
    }

    private void recomputeRole() {
        isHost = myUid.equals(room.hostUid);
        isSpeaker = findSpeaker(myUid) != null;
        applyRole();
        applyMicUi();
    }

    private void startSession() {
        sessionStarted = true;
        VrSession.get().start(this, roomId, room.title, room.hostName, room.hostUid,
                myUid.equals(room.hostUid), new VrSession.StartCb() {
                    @Override public void onStarted() { applyMicUi(); }
                    @Override public void onFailed(String message) {
                        if (isFinishing()) return;
                        Toast.makeText(VoiceRoomActivity.this, message, Toast.LENGTH_LONG).show();
                        ended = true;
                        finish();
                    }
                });
    }

    // ───────────────────────── VrSession.Observer ─────────────────────────
    @Override public void onSessionState() { applyMicUi(); applyRole(); }

    @Override public void onSpeaking(Set<String> uids) { speakerAdapter.setSpeakingSet(uids); }

    @Override public void onEnded(String reason) {
        if (ended) return;
        ended = true;
        Toast.makeText(this, "Disconnected from the room", Toast.LENGTH_LONG).show();
        finish();
    }

    // ───────────────────────── member actions (host controls → server) ─────────────────────────
    private void openMember(Member m) {
        VrSheets.showMember(this, m, isHost, new VrSheets.MemberActions() {
            @Override public void onToggleMute(Member x) {
                hostAction(x.hostMuted ? "unmute" : "mute", x, null);
            }
            @Override public void onMoveToAudience(Member x) { hostAction("demote", x, x.name + " moved to audience"); }
            @Override public void onInviteToSpeak(Member x) { promote(x); }
            @Override public void onRemove(Member x) { confirmRemove(x); }
            @Override public void onProfile(Member x) { openProfile(x); }
        });
    }

    private void promote(Member m) { hostAction("promote", m, m.name + " is now a speaker \uD83C\uDFA4"); }

    private void hostAction(String action, Member m, String okToast) {
        VrApi.hostAction(action, roomId, m.uid, new VrApi.Done() {
            @Override public void onDone(Map<String, Object> data) {
                if (okToast != null) Toast.makeText(VoiceRoomActivity.this, okToast, Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                Toast.makeText(VoiceRoomActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmRemove(Member m) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Remove " + m.name + "?")
                .setMessage("They won't be able to rejoin this room.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (x, w) -> hostAction("kick", m, m.name + " removed"))
                .create();
        AlertDialogStyler.showRounded(d);
    }

    private void openProfile(Member m) {
        try {
            Intent i = new Intent().setClassName(this, "com.callx.app.activities.UserProfileActivity");
            i.putExtra("uid", m.uid);
            i.putExtra("name", m.name);
            i.putExtra("photo", m.photoUrl == null ? "" : m.photoUrl);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Profile unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    // ───────────────────────── role UI ─────────────────────────
    private void applyRole() {
        if (audienceAdapter == null) return;
        audienceAdapter.setShowHands(isHost);
        boolean hl;
        if (isHost) {
            int n = requests.size();
            raiseLabel.setText(n > 0 ? "Requests\n(" + n + ")" : "Requests");
            hl = n > 0;
        } else if (isSpeaker) {
            raiseLabel.setText("Speaker");
            hl = true;
        } else {
            raiseLabel.setText(handRaised ? "Lower\nHand" : "Raise\nHand");
            hl = handRaised;
        }
        raiseIcon.setColorFilter(getColor(hl ? R.color.vr_primary : R.color.vr_text));
        raiseLabel.setTextColor(getColor(hl ? R.color.vr_primary : R.color.vr_text));
    }

    private void onRaiseClicked() {
        if (isHost) {
            VrSheets.showRequests(this, new ArrayList<>(requests), new VrSheets.RequestsCb() {
                @Override public void onAccept(Member m) { promote(m); }
                @Override public void onDecline(Member m) { VrRepo.declineHand(roomId, m.uid); }
            });
            return;
        }
        if (isSpeaker) {
            Toast.makeText(this, "You're a speaker \uD83C\uDFA4", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!room.allowRequests && !handRaised) {
            Toast.makeText(this, "Only invited speakers can talk in this room", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean next = !handRaised;
        VrRepo.setHand(roomId, next, (ok, err) -> {
            if (!ok) Toast.makeText(this, "Couldn't update hand", Toast.LENGTH_SHORT).show();
        });
        if (next) VrFx.wave(raiseIcon);
        Toast.makeText(this, next ? "Hand raised \u270B" : "Hand lowered", Toast.LENGTH_SHORT).show();
    }

    // ───────────────────────── pinned + chat + poll ─────────────────────────
    private void bindPinnedAndChat() {
        findViewById(R.id.vr_pinned_close).setOnClickListener(v -> {
            if (isHost) VrRepo.unpin(roomId);
            pinnedHiddenLocally = true;
            pinnedBar.setVisibility(View.GONE);
        });
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        chatAdapter = new VrAdapters.ChatAdapter(chat, (chip, m) ->
                VrFx.heartBurst(fxLayer, chip, rng.nextBoolean() ? "\uD83D\uDC9C" : "\u2764\uFE0F", 5));
        chatAdapter.setOnLike((m, liked) -> VrRepo.setLike(roomId, m.id, liked));
        chatAdapter.setOnLong(this::onChatLong);
        rvChat.setAdapter(chatAdapter);
        updateChatEmpty();

        appBar.addOnOffsetChangedListener((ab, offset) -> { appBarOffset = offset; maybeClearPill(); });
        rvChat.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { maybeClearPill(); }
        });
        newMsgsPill.setOnClickListener(v -> scrollToLatest());
        pollBar.setOnClickListener(v -> onPollClicked());
    }

    private void onChatLong(Msg m) {
        boolean mine = myUid.equals(m.uid);
        if (!isHost && !mine) return;
        List<String> items = new ArrayList<>();
        if (isHost && !"gift".equals(m.type)) items.add("Pin message");
        items.add("Delete message");
        AlertDialog d = new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (x, which) -> {
                    String it = items.get(which);
                    if (it.startsWith("Pin")) { VrRepo.pin(roomId, m.name, m.text); pinnedHiddenLocally = false; }
                    else VrRepo.deleteChat(roomId, m.id);
                }).create();
        AlertDialogStyler.showRounded(d);
    }

    @Override public void onPinned(String by, String text) {
        pinnedText = text;
        if (text == null || text.isEmpty() || pinnedHiddenLocally && !isHost) {
            if (text == null) pinnedHiddenLocally = false;
            pinnedBar.setVisibility(View.GONE);
            return;
        }
        if (pinnedHiddenLocally) return;
        ((TextView) findViewById(R.id.vr_pinned_by)).setText(by + " pinned");
        ((TextView) findViewById(R.id.vr_pinned_text)).setText(text);
        pinnedBar.setVisibility(View.VISIBLE);
    }

    @Override public void onPoll(Poll p) {
        poll = p;
        if (p == null) { pollBar.setVisibility(View.GONE); return; }
        pollBar.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.vr_poll_text)).setText("\uD83D\uDCCA " + p.question);
        ((TextView) findViewById(R.id.vr_poll_action)).setText(
                p.expired() ? "Results" : (p.myVote >= 0 ? "Voted" : "Vote"));
    }

    private void onPollClicked() {
        final Poll p = poll;
        if (p == null) return;
        if (!p.expired() && p.myVote < 0 && !isHost) {
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle(p.question)
                    .setItems(p.options.toArray(new String[0]), (x, which) ->
                            VrRepo.vote(roomId, p.id, which, (ok, err) -> {
                                if (!ok) Toast.makeText(this, "Couldn't vote", Toast.LENGTH_SHORT).show();
                            }))
                    .setNegativeButton("Close", null).create();
            AlertDialogStyler.showRounded(d);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int total = Math.max(1, p.total());
        for (int i = 0; i < p.options.size(); i++) {
            int c = p.counts[i];
            sb.append(i == p.myVote ? "\u2713 " : "   ").append(p.options.get(i)).append("  \u2014  ")
              .append(c).append(" (").append(c * 100 / total).append("%)\n");
        }
        sb.append("\n").append(p.total()).append(" votes").append(p.expired() ? " \u2022 ended" : " \u2022 live");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(p.question).setMessage(sb.toString())
                .setPositiveButton("OK", null).create();
        AlertDialogStyler.showRounded(d);
    }

    private boolean headerCollapsed() { return Math.abs(appBarOffset) >= appBar.getTotalScrollRange() - 2; }
    private boolean chatAtBottom() { return !rvChat.canScrollVertically(1); }

    private void maybeClearPill() {
        if (headerCollapsed() && chatAtBottom()) { unseen = 0; newMsgsPill.setVisibility(View.GONE); }
    }

    private void scrollToLatest() {
        unseen = 0;
        newMsgsPill.setVisibility(View.GONE);
        appBar.setExpanded(false, true);
        if (!chat.isEmpty()) rvChat.post(() -> rvChat.smoothScrollToPosition(chat.size() - 1));
    }

    private void updateChatEmpty() { chatEmpty.setVisibility(chat.isEmpty() ? View.VISIBLE : View.GONE); }

    private int indexOfMsg(String id) {
        for (int i = chat.size() - 1; i >= 0; i--) if (id != null && id.equals(chat.get(i).id)) return i;
        return -1;
    }

    @Override public void onChatAdded(Msg m) {
        if (indexOfMsg(m.id) >= 0) return;
        boolean own = myUid.equals(m.uid);
        boolean history = watch != null && m.ts < watch.openedAt() - 1500;
        boolean wasAtLatest = headerCollapsed() && chatAtBottom();

        chat.add(m);
        chatAdapter.notifyItemInserted(chat.size() - 1);
        if (chat.size() > CHAT_CAP) { chat.remove(0); chatAdapter.notifyItemRemoved(0); }
        updateChatEmpty();

        if (!history && "gift".equals(m.type) && !own && !TextUtils.isEmpty(m.giftEmoji)) {
            VrFx.giftBurst(fxLayer, m.giftEmoji);
        }
        if (history) {
            rvChat.scrollToPosition(chat.size() - 1);
        } else if (own) {
            scrollToLatest();
        } else if (wasAtLatest) {
            rvChat.scrollToPosition(chat.size() - 1);
        } else {
            unseen++;
            newMsgsPill.setText(unseen == 1 ? "\u2193 1 new message" : "\u2193 " + unseen + " new messages");
            newMsgsPill.setVisibility(View.VISIBLE);
        }
    }

    @Override public void onChatChanged(Msg m) {
        int i = indexOfMsg(m.id);
        if (i < 0) return;
        Msg old = chat.get(i);
        old.hearts = m.hearts;
        old.liked = m.liked;
        chatAdapter.notifyItemChanged(i);
    }

    @Override public void onChatRemoved(String id) {
        int i = indexOfMsg(id);
        if (i < 0) return;
        chat.remove(i);
        chatAdapter.notifyItemRemoved(i);
        updateChatEmpty();
    }

    // ───────────────────────── bottom: input + action bar ─────────────────────────
    private void bindBottom() {
        findViewById(R.id.vr_btn_gift).setOnClickListener(v -> openGifts());

        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                boolean has = e.toString().trim().length() > 0;
                btnEmojiSend.setImageResource(has ? R.drawable.ic_send_fill : R.drawable.ic_emoji);
                btnEmojiSend.setColorFilter(getColor(has ? R.color.vr_primary : R.color.vr_text));
                btnEmojiSend.setContentDescription(getString(has ? R.string.vr_cd_send : R.string.vr_cd_emoji));
            }
        });
        btnEmojiSend.setOnClickListener(v -> {
            if (etMessage.getText().toString().trim().isEmpty()) VrSheets.showEmoji(this, this::insertEmoji);
            else sendTyped();
        });
        etMessage.setOnEditorActionListener((tv, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_SEND) { sendTyped(); return true; }
            return false;
        });

        btnMic.setOnClickListener(v -> onMicClicked());

        findViewById(R.id.vr_act_raise).setOnClickListener(v -> onRaiseClicked());
        findViewById(R.id.vr_act_poll).setOnClickListener(v -> {
            if (!isHost) {
                if (poll != null) onPollClicked();
                else Toast.makeText(this, "Only the host can start a poll", Toast.LENGTH_SHORT).show();
                return;
            }
            VrSheets.showPoll(this, (q, opts, dur) -> {
                long ms = dur.startsWith("15") ? 15 * 60_000L : dur.startsWith("5") ? 5 * 60_000L : 60_000L;
                VrRepo.createPoll(roomId, q, opts, ms, (ok, err) -> {
                    if (!ok) Toast.makeText(this, "Couldn't start poll", Toast.LENGTH_SHORT).show();
                });
            });
        });
        findViewById(R.id.vr_act_music).setOnClickListener(v -> VrSheets.showMusic(this));
        findViewById(R.id.vr_act_share).setOnClickListener(v -> shareRoom());
        findViewById(R.id.vr_act_more).setOnClickListener(v -> openMore());
    }

    private void openGifts() {
        if (!sessionStarted) return;
        VrRepo.loadCoins(coins -> VrSheets.showGifts(this,
                isHost ? "Everyone in the room" : "Host \u2022 " + room.hostName, coins, g ->
                VrApi.gift(roomId, g.id, null, new VrApi.Done() {
                    @Override public void onDone(Map<String, Object> data) {
                        VrFx.giftBurst(fxLayer, g.emoji);
                    }
                    @Override public void onError(String message) {
                        Toast.makeText(VoiceRoomActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                })));
    }

    private void insertEmoji(String e) {
        Editable t = etMessage.getText();
        int pos = etMessage.getSelectionStart();
        if (pos < 0) pos = t.length();
        t.insert(pos, e);
    }

    private void sendTyped() {
        String t = etMessage.getText().toString().trim();
        if (t.isEmpty()) return;
        if (t.length() > 500) t = t.substring(0, 500);
        etMessage.setText("");
        VrRepo.sendChat(roomId, t, (ok, err) -> {
            if (!ok) Toast.makeText(this, "Message not sent", Toast.LENGTH_SHORT).show();
        });
    }

    // ───────────────────────── mic ─────────────────────────
    private void onMicClicked() {
        if (!isSpeaker && !isHost) {
            Toast.makeText(this, "Raise your hand to speak", Toast.LENGTH_SHORT).show();
            return;
        }
        Member me = findSpeaker(myUid);
        if (me != null && me.hostMuted) {
            Toast.makeText(this, "The host muted you", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!VrSession.get().micOn() && !VrSession.hasMicPermission(this)) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        toggleMic();
    }

    private void toggleMic() {
        boolean want = !VrSession.get().micOn();
        VrSession.get().setMic(want, (ok, err) -> {
            VrFx.bounce(btnMic);
            applyMicUi();
            if (!ok) Toast.makeText(this, err == null ? "Mic error" : err, Toast.LENGTH_SHORT).show();
        });
    }

    private void applyMicUi() {
        if (btnMic == null) return;
        boolean on = VrSession.get().isActiveFor(roomId) && VrSession.get().micOn();
        if (on) {
            btnMic.setBackgroundResource(R.drawable.vr_bg_circle_primary);
            btnMic.setImageResource(R.drawable.ic_mic);
            btnMic.setColorFilter(getColor(R.color.vr_white));
            btnMic.setContentDescription(getString(R.string.vr_cd_mic_mute));
        } else {
            btnMic.setBackgroundResource(R.drawable.vr_bg_circle_soft);
            btnMic.setImageResource(R.drawable.ic_mic_off);
            btnMic.setColorFilter(getColor(R.color.vr_primary));
            btnMic.setContentDescription(getString(R.string.vr_cd_mic_unmute));
        }
    }

    // ───────────────────────── more / share / leave ─────────────────────────
    private void openMore() {
        VrSheets.showMore(this, room.title, isHost, new VrSheets.MoreActions() {
            @Override public void onPeople() {
                VrSheets.showPeople(VoiceRoomActivity.this, speakers, audience, room.audienceCount, false, false);
            }
            @Override public void onShare() { shareRoom(); }
            @Override public void onLeave() { confirmLeave(); }
        });
    }

    private void shareRoom() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, "Join \"" + room.title + "\" live on CallX \uD83C\uDF99\uFE0F  "
                + "https://callx-server.onrender.com/room/" + roomId);
        startActivity(Intent.createChooser(send, "Invite to room"));
    }

    /** Back / chevron: room chhota — audio chalta rahega (VrSession + foreground service). */
    private void minimize() { finish(); }

    private void confirmLeave() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isHost ? "End room?" : "Leave room?")
                .setMessage(isHost ? "The room will close for everyone."
                        : "You can rejoin anytime while the room is live.")
                .setNegativeButton("Stay", null)
                .setPositiveButton(isHost ? "End room" : "Leave", (d, w) -> {
                    leaving = true;
                    VrSession.get().leave(isHost);
                    finish();
                }).create();
        AlertDialogStyler.showRounded(dialog);
    }
}
