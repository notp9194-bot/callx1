package com.callx.app.voicerooms;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.callx.app.voicerooms.VrModel.Room;

import java.util.ArrayList;
import android.widget.Toast;

import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * VoiceRoomsHomeActivity — live rooms ki list (v794: Firebase voiceRoomList, realtime).
 *
 * Mini player ab asli audio session (VrSession) dikhata hai — room minimize karne par
 * audio foreground service ke saath chalta rehta hai.
 *
 * Open karne ka tareeka (kisi bhi module se, class-name Intent):
 *   startActivity(new Intent(ctx, com.callx.app.voicerooms.VoiceRoomsHomeActivity.class));
 */
public class VoiceRoomsHomeActivity extends AppCompatActivity implements VrSession.Observer {

    private final List<Room> all = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private VrAdapters.RoomAdapter adapter;
    private RecyclerView rv;
    private SwipeRefreshLayout refresh;
    private View empty, skeleton, mini, startBtn;
    private EditText searchField;
    private ObjectAnimator skeletonPulse, miniDotPulse;

    private String category = "All";
    private String query = "";

    private VrRepo.RoomsWatch roomsWatch;
    private boolean firstLoad = true;
    private boolean creating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vr_home);

        empty = findViewById(R.id.vr_empty);
        skeleton = findViewById(R.id.vr_skeleton);
        mini = findViewById(R.id.vr_mini);
        startBtn = findViewById(R.id.vr_btn_start_room);
        refresh = findViewById(R.id.vr_refresh);
        searchField = findViewById(R.id.vr_home_search_field);

        findViewById(R.id.vr_home_back).setOnClickListener(v -> finish());
        findViewById(R.id.vr_home_search).setOnClickListener(v -> toggleSearch());

        // Category chips
        LinearLayout chipRow = findViewById(R.id.vr_chip_row);
        for (String c : VrCatalog.CATEGORIES) {
            TextView chip = VrSheets.chip(this, c, c.equals(category));
            chip.setOnClickListener(v -> {
                category = c;
                for (TextView t : chips) VrSheets.styleChip(t, t.getText().toString().equals(c));
                applyFilter();
            });
            chips.add(chip);
            chipRow.addView(chip);
        }

        // Rooms list
        rv = findViewById(R.id.vr_rv_rooms);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VrAdapters.RoomAdapter(r -> openRoom(r, false));
        rv.setAdapter(adapter);

        // Pull-to-refresh: list already realtime hai, bas listener dobara lagate hain
        refresh.setColorSchemeColors(getColor(R.color.vr_primary));
        refresh.setProgressBackgroundColorSchemeColor(getColor(R.color.vr_surface));
        refresh.setOnRefreshListener(() -> {
            stopWatching();
            startWatching();
            handler.postDelayed(() -> refresh.setRefreshing(false), 700);
        });

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        // Start a room
        startBtn.setOnClickListener(v ->
                VrSheets.showCreateRoom(this, (title, topic, cat, allow) -> createRoom(title, topic, cat, allow)));

        bindMini();
        loadInitial();
        startWatching();
    }

    @Override protected void onResume() {
        super.onResume();
        VrSession.get().setObserver(this);
        syncMini();
    }

    @Override protected void onPause() {
        super.onPause();
        VrSession.get().clearObserver(this);
    }

    // ───────────────────────── realtime rooms ─────────────────────────
    private void startWatching() {
        roomsWatch = VrRepo.watchRooms(new VrRepo.RoomsCb() {
            @Override public void onRooms(List<Room> rooms) {
                all.clear();
                all.addAll(rooms);
                if (firstLoad) {
                    firstLoad = false;
                    if (skeletonPulse != null) skeletonPulse.cancel();
                    skeleton.setVisibility(View.GONE);
                }
                applyFilter();
            }
            @Override public void onError(String message) {
                if (firstLoad) {
                    firstLoad = false;
                    if (skeletonPulse != null) skeletonPulse.cancel();
                    skeleton.setVisibility(View.GONE);
                    applyFilter();
                }
                Toast.makeText(VoiceRoomsHomeActivity.this, "Couldn't load rooms", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void stopWatching() {
        if (roomsWatch != null) { roomsWatch.stop(); roomsWatch = null; }
    }

    private void createRoom(String title, String topic, String cat, boolean allow) {
        if (creating) return;
        creating = true;
        VrApi.createRoom(title, topic, allow ? "Anyone can raise a hand to speak."
                : "Only invited speakers can talk.", cat, allow, new VrApi.Done() {
            @Override public void onDone(Map<String, Object> data) {
                creating = false;
                String id = VrApi.str(data, "roomId");
                if (id.isEmpty()) { Toast.makeText(VoiceRoomsHomeActivity.this,
                        "Couldn't start the room", Toast.LENGTH_SHORT).show(); return; }
                openRoomId(id);
            }
            @Override public void onError(String message) {
                creating = false;
                Toast.makeText(VoiceRoomsHomeActivity.this,
                        message == null || message.isEmpty() ? "Couldn't start the room" : message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopWatching();
        handler.removeCallbacksAndMessages(null);
        if (skeletonPulse != null) skeletonPulse.cancel();
        if (miniDotPulse != null) miniDotPulse.cancel();
        super.onDestroy();
    }

    // ───────────────────────── loading skeleton ─────────────────────────
    private void loadInitial() {
        skeleton.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        skeletonPulse = ObjectAnimator.ofFloat(skeleton, View.ALPHA, 1f, 0.45f);
        skeletonPulse.setDuration(750);
        skeletonPulse.setRepeatMode(ObjectAnimator.REVERSE);
        skeletonPulse.setRepeatCount(ObjectAnimator.INFINITE);
        skeletonPulse.start();
    }

    // ───────────────────────── search / filter ─────────────────────────
    private void toggleSearch() {
        boolean show = searchField.getVisibility() != View.VISIBLE;
        searchField.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            searchField.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
        } else {
            searchField.setText("");
        }
    }

    private void applyFilter() {
        List<Room> out = new ArrayList<>();
        for (Room r : all) {
            boolean catOk = "All".equals(category) || category.equals(r.category);
            boolean qOk = query.isEmpty()
                    || r.title.toLowerCase(Locale.ROOT).contains(query)
                    || (r.hostName != null && r.hostName.toLowerCase(Locale.ROOT).contains(query));
            if (catOk && qOk) out.add(r);
        }
        adapter.submit(out);
        empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ───────────────────────── open room ─────────────────────────
    private void openRoom(Room r, boolean unused) { openRoomId(r.id); }

    private void openRoomId(String id) {
        Intent i = new Intent(this, VoiceRoomActivity.class);
        i.putExtra(VoiceRoomActivity.EXTRA_ID, id);
        startActivity(i);
    }

    // ───────────────────────── mini player (asli session) ─────────────────────────
    private void bindMini() {
        ImageView micBtn = findViewById(R.id.vr_mini_mic);
        micBtn.setOnClickListener(v -> {
            VrSession ses = VrSession.get();
            if (!ses.isActive()) return;
            if (!ses.canPublish()) {
                Toast.makeText(this, "Open the room and raise your hand to speak", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!ses.micOn() && !VrSession.hasMicPermission(this)) {
                Toast.makeText(this, "Open the room to allow microphone access", Toast.LENGTH_SHORT).show();
                return;
            }
            ses.setMic(!ses.micOn(), (ok, err) -> { applyMiniMic(); VrFx.bounce(micBtn); });
        });
        findViewById(R.id.vr_mini_leave).setOnClickListener(v -> {
            VrSession.get().leave(false);   // mini player se host bhi sirf nikalta hai, room end nahi
            syncMini();
        });
        mini.setOnClickListener(v -> {
            if (VrSession.get().isActive()) openRoomId(VrSession.get().roomId());
        });
    }

    private boolean miniShown;

    private void syncMini() {
        VrSession ses = VrSession.get();
        if (ses.isActive()) showMini(ses); else hideMini();
    }

    private void showMini(VrSession ses) {
        ((TextView) findViewById(R.id.vr_mini_title)).setText(
                ses.title().isEmpty() ? "Voice room" : ses.title());
        ((TextView) findViewById(R.id.vr_mini_sub)).setText(
                "Hosted by " + ses.hostName() + (ses.isReconnecting() ? "  \u2022  reconnecting\u2026"
                        : "  \u2022  live"));
        applyMiniMic();
        if (miniShown) return;
        miniShown = true;

        mini.setVisibility(View.VISIBLE);
        mini.setTranslationY(VrUtil.dp(this, 40));
        mini.setAlpha(0f);
        mini.animate().translationY(0f).alpha(1f).setDuration(260).start();

        View dot = findViewById(R.id.vr_mini_dot);
        if (miniDotPulse != null) miniDotPulse.cancel();
        miniDotPulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f);
        miniDotPulse.setDuration(700);
        miniDotPulse.setRepeatMode(ObjectAnimator.REVERSE);
        miniDotPulse.setRepeatCount(ObjectAnimator.INFINITE);
        miniDotPulse.start();

        int lift = VrUtil.dp(this, 84);
        ((FrameLayout.LayoutParams) startBtn.getLayoutParams()).bottomMargin = VrUtil.dp(this, 24) + lift;
        startBtn.requestLayout();
        rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(), rv.getPaddingRight(),
                VrUtil.dp(this, 96) + lift);
    }

    private void hideMini() {
        if (miniDotPulse != null) miniDotPulse.cancel();
        mini.setVisibility(View.GONE);
        if (!miniShown) return;
        miniShown = false;
        ViewGroup.LayoutParams lp = startBtn.getLayoutParams();
        ((FrameLayout.LayoutParams) lp).bottomMargin = VrUtil.dp(this, 24);
        startBtn.requestLayout();
        rv.setPadding(rv.getPaddingLeft(), rv.getPaddingTop(), rv.getPaddingRight(), VrUtil.dp(this, 96));
    }

    private void applyMiniMic() {
        ImageView micBtn = findViewById(R.id.vr_mini_mic);
        boolean on = VrSession.get().isActive() && VrSession.get().micOn();
        micBtn.setImageResource(on ? R.drawable.ic_mic : R.drawable.ic_mic_off);
        micBtn.setContentDescription(getString(on ? R.string.vr_cd_mic_mute : R.string.vr_cd_mic_unmute));
    }

    // ───────────────────────── VrSession.Observer ─────────────────────────
    @Override public void onSessionState() { syncMini(); }
    @Override public void onSpeaking(java.util.Set<String> uids) {}
    @Override public void onEnded(String reason) {
        syncMini();
        Toast.makeText(this, "The voice room has ended", Toast.LENGTH_SHORT).show();
    }
}
