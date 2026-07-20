package com.callx.app.bots;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.models.BotCommand;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * BotSettingsActivity — Admin panel to manage bot slash-commands for a group.
 *
 * Shows:
 *  • Built-in commands (read-only)
 *  • Custom commands — admin can add / edit / enable-disable / delete
 *  FAB → dialog to add a new custom command
 *
 * Firebase: groups/{groupId}/botCommands/{commandName}
 */
public class BotSettingsActivity extends AppCompatActivity {

    public static final String EXTRA_GROUP_ID   = "groupId";
    public static final String EXTRA_GROUP_NAME = "groupName";

    private String groupId, currentUid;
    private CustomAdapter customAdapter;
    private ValueEventListener customListener;
    private TextView tvNoCustom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bot_settings);

        groupId = getIntent().getStringExtra(EXTRA_GROUP_ID);
        if (groupId == null || FirebaseAuth.getInstance().getCurrentUser() == null) { finish(); return; }
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String gn = getIntent().getStringExtra(EXTRA_GROUP_NAME);
            getSupportActionBar().setTitle((gn != null ? gn + " · " : "") + "Bot Commands");
        }

        // Built-in list (static)
        RecyclerView rvBuiltIn = findViewById(R.id.rv_builtin_commands);
        BuiltInAdapter biAdapter = new BuiltInAdapter();
        rvBuiltIn.setLayoutManager(new LinearLayoutManager(this));
        rvBuiltIn.setAdapter(biAdapter);
        biAdapter.submitList(Arrays.asList(BotCommand.BUILT_INS));

        // Custom list (Firebase-driven)
        RecyclerView rvCustom = findViewById(R.id.rv_custom_commands);
        customAdapter = new CustomAdapter(this::onLongClick);
        rvCustom.setLayoutManager(new LinearLayoutManager(this));
        rvCustom.setAdapter(customAdapter);
        tvNoCustom = findViewById(R.id.tv_no_custom);

        FloatingActionButton fab = findViewById(R.id.fab_add_command);
        fab.setOnClickListener(v -> showDialog(null));

        listenCustom();
    }

    private void listenCustom() {
        customListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<BotCommand> list = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    BotCommand bc = c.getValue(BotCommand.class);
                    if (bc != null) { if (bc.command == null) bc.command = c.getKey(); list.add(bc); }
                }
                customAdapter.submitList(new ArrayList<>(list));
                tvNoCustom.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getGroupsRef().child(groupId).child("botCommands").addValueEventListener(customListener);
    }

    private void onLongClick(BotCommand bc) {
        new AlertDialog.Builder(this)
                .setTitle("/" + bc.command)
                .setItems(new String[]{"Edit", bc.enabled ? "Disable" : "Enable", "Delete"}, (d, w) -> {
                    if (w == 0) showDialog(bc);
                    else if (w == 1) toggle(bc);
                    else confirmDelete(bc);
                }).show();
    }

    private void showDialog(BotCommand existing) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_add_bot_command, null);
        EditText etCmd  = v.findViewById(R.id.et_cmd_name);
        EditText etDesc = v.findViewById(R.id.et_cmd_desc);
        EditText etResp = v.findViewById(R.id.et_cmd_response);
        if (existing != null) {
            etCmd.setText(existing.command); etCmd.setEnabled(false);
            etDesc.setText(existing.description);
            etResp.setText(existing.response);
        }
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add Custom Command" : "Edit /" + existing.command)
                .setView(v)
                .setPositiveButton("Save", (d, w) -> {
                    String cmd  = etCmd.getText().toString().trim().toLowerCase().replaceAll("[^a-z0-9_]","");
                    String desc = etDesc.getText().toString().trim();
                    String resp = etResp.getText().toString().trim();
                    if (cmd.isEmpty())  { Toast.makeText(this, "Command name required", Toast.LENGTH_SHORT).show(); return; }
                    if (resp.isEmpty()) { Toast.makeText(this, "Response text required", Toast.LENGTH_SHORT).show(); return; }
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("command", cmd); map.put("description", desc.isEmpty() ? "Custom command" : desc);
                    map.put("response", resp); map.put("kind", "custom"); map.put("enabled", true);
                    map.put("createdBy", currentUid);
                    if (existing == null) map.put("createdAt", System.currentTimeMillis());
                    FirebaseUtils.getGroupsRef().child(groupId).child("botCommands").child(cmd)
                            .updateChildren(map, (e, r) -> Toast.makeText(this, e == null ? "Saved" : "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void toggle(BotCommand bc) {
        FirebaseUtils.getGroupsRef().child(groupId).child("botCommands").child(bc.command).child("enabled").setValue(!bc.enabled);
    }

    private void confirmDelete(BotCommand bc) {
        new AlertDialog.Builder(this).setTitle("Delete /" + bc.command + "?")
                .setPositiveButton("Delete", (d, w) ->
                        FirebaseUtils.getGroupsRef().child(groupId).child("botCommands").child(bc.command).removeValue())
                .setNegativeButton("Cancel", null).show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (customListener != null)
            FirebaseUtils.getGroupsRef().child(groupId).child("botCommands").removeEventListener(customListener);
    }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(i);
    }

    // ── Built-in adapter (read-only) ──────────────────────────────────────
    static class BuiltInAdapter extends ListAdapter<BotCommand, BuiltInAdapter.VH> {
        BuiltInAdapter() { super(DIFF); }
        @Override public @androidx.annotation.NonNull VH onCreateViewHolder(@androidx.annotation.NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_bot_command, p, false));
        }
        @Override public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) { h.bind(getItem(pos)); }
        static class VH extends RecyclerView.ViewHolder {
            TextView tvCmd, tvDesc;
            VH(View v) { super(v); tvCmd = v.findViewById(R.id.tv_cmd); tvDesc = v.findViewById(R.id.tv_desc); }
            void bind(BotCommand bc) { tvCmd.setText("/" + bc.command); tvDesc.setText(bc.description); }
        }
        static final DiffUtil.ItemCallback<BotCommand> DIFF = new DiffUtil.ItemCallback<BotCommand>() {
            @Override public boolean areItemsTheSame(@androidx.annotation.NonNull BotCommand a, @androidx.annotation.NonNull BotCommand b) { return a.command.equals(b.command); }
            @Override public boolean areContentsTheSame(@androidx.annotation.NonNull BotCommand a, @androidx.annotation.NonNull BotCommand b) { return true; }
        };
    }

    // ── Custom adapter ────────────────────────────────────────────────────
    static class CustomAdapter extends ListAdapter<BotCommand, CustomAdapter.VH> {
        interface LCL { void onLongClick(BotCommand bc); }
        final LCL lcl;
        CustomAdapter(LCL l) { super(DIFF); this.lcl = l; }
        @Override public @androidx.annotation.NonNull VH onCreateViewHolder(@androidx.annotation.NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_bot_command, p, false));
        }
        @Override public void onBindViewHolder(@androidx.annotation.NonNull VH h, int pos) { h.bind(getItem(pos)); }
        class VH extends RecyclerView.ViewHolder {
            TextView tvCmd, tvDesc; View badge;
            VH(View v) { super(v); tvCmd = v.findViewById(R.id.tv_cmd); tvDesc = v.findViewById(R.id.tv_desc); badge = v.findViewById(R.id.badge_disabled); }
            void bind(BotCommand bc) {
                tvCmd.setText("/" + bc.command);
                tvDesc.setText(bc.description != null ? bc.description : bc.response);
                if (badge != null) badge.setVisibility(bc.enabled ? View.GONE : View.VISIBLE);
                itemView.setAlpha(bc.enabled ? 1f : 0.5f);
                itemView.setOnLongClickListener(v -> { if (lcl != null) lcl.onLongClick(bc); return true; });
            }
        }
        static final DiffUtil.ItemCallback<BotCommand> DIFF = new DiffUtil.ItemCallback<BotCommand>() {
            @Override public boolean areItemsTheSame(@androidx.annotation.NonNull BotCommand a, @androidx.annotation.NonNull BotCommand b) { return a.command.equals(b.command); }
            @Override public boolean areContentsTheSame(@androidx.annotation.NonNull BotCommand a, @androidx.annotation.NonNull BotCommand b) { return a.enabled == b.enabled; }
        };
    }
}
