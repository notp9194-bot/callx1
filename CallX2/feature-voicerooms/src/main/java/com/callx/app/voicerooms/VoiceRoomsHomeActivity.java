package com.callx.app.voicerooms;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.voicerooms.VrModel.Room;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VoiceRoomsHomeActivity — live rooms ki list (UI only, VrDemoData se).
 *
 * Open karne ka tareeka (kisi bhi module se, class-name Intent):
 *   startActivity(new Intent(ctx, com.callx.app.voicerooms.VoiceRoomsHomeActivity.class));
 */
public class VoiceRoomsHomeActivity extends AppCompatActivity {

    private final List<Room> all = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    private VrAdapters.RoomAdapter adapter;
    private TextView empty;
    private EditText searchField;
    private String category = "All";
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vr_home);

        all.addAll(VrDemoData.rooms());
        empty = findViewById(R.id.vr_empty);
        searchField = findViewById(R.id.vr_home_search_field);

        findViewById(R.id.vr_home_back).setOnClickListener(v -> finish());
        findViewById(R.id.vr_home_search).setOnClickListener(v -> toggleSearch());

        // Category chips
        LinearLayout chipRow = findViewById(R.id.vr_chip_row);
        for (String c : VrDemoData.CATEGORIES) {
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
        RecyclerView rv = findViewById(R.id.vr_rv_rooms);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VrAdapters.RoomAdapter(r -> openRoom(r, false));
        rv.setAdapter(adapter);

        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        // Start a room
        findViewById(R.id.vr_btn_start_room).setOnClickListener(v ->
                VrSheets.showCreateRoom(this, (title, topic, cat, allow) -> {
                    Room r = new Room();
                    r.id = "mine_" + System.currentTimeMillis();
                    r.title = title;
                    r.topic = topic.isEmpty() ? cat : topic;
                    r.description = allow
                            ? "Anyone can raise a hand to speak."
                            : "Only invited speakers can talk.";
                    r.category = cat;
                    r.hostName = "You";
                    r.listeners = 1;
                    r.audienceCount = 0;
                    openRoom(r, true);
                }));

        applyFilter();
    }

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
                    || r.hostName.toLowerCase(Locale.ROOT).contains(query);
            if (catOk && qOk) out.add(r);
        }
        adapter.submit(out);
        empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Room screen kholo. isHost=true => "Start a room" se bana naya room. */
    private void openRoom(Room r, boolean isHost) {
        Intent i = new Intent(this, VoiceRoomActivity.class);
        i.putExtra(VoiceRoomActivity.EXTRA_ID, r.id);
        i.putExtra(VoiceRoomActivity.EXTRA_TITLE, r.title);
        i.putExtra(VoiceRoomActivity.EXTRA_TOPIC, r.topic);
        i.putExtra(VoiceRoomActivity.EXTRA_DESC, r.description);
        i.putExtra(VoiceRoomActivity.EXTRA_IS_HOST, isHost);
        startActivity(i);
    }
}
