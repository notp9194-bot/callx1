package com.callx.app.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * XActiveSessionsActivity — Shows all active FCM sessions / tokens for this
 * X account, grouped by platform and device.
 *
 * Firebase path: x/fcm_tokens/{uid}/{tokenKey}
 *   Fields: token, updatedAt, platform ("android"/"ios"/"web"), deviceModel
 *
 * The user can revoke any non-current session by removing its token from
 * Firebase, which prevents future FCM notifications to that device.
 */
public class XActiveSessionsActivity extends AppCompatActivity {

    private RecyclerView rvSessions;
    private View layoutEmpty, layoutLoading;
    private SessionAdapter adapter;
    private String myUid;
    private String currentToken; // FCM token of this device

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_active_sessions);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        Toolbar toolbar = findViewById(R.id.toolbar_x_sessions);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Apps and Sessions");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rvSessions    = findViewById(R.id.rv_x_sessions);
        layoutEmpty   = findViewById(R.id.layout_empty_sessions);
        layoutLoading = findViewById(R.id.layout_loading_sessions);

        adapter = new SessionAdapter();
        rvSessions.setLayoutManager(new LinearLayoutManager(this));
        rvSessions.setAdapter(adapter);

        // Get current device FCM token
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    currentToken = token;
                    loadSessions();
                })
                .addOnFailureListener(e -> loadSessions());
    }

    private void loadSessions() {
        if (myUid.isEmpty()) return;
        if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);

        XFirebaseUtils.fcmTokenRef(myUid).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                        List<SessionInfo> sessions = new ArrayList<>();
                        // fcm_tokens/{uid} is a map: could be a single object or per-device keyed
                        // Handle both single-token and multi-token structures
                        if (snap.hasChild("token")) {
                            // Single token stored at fcm_tokens/{uid}
                            SessionInfo s = parseSession(snap, snap.getKey());
                            if (s != null) sessions.add(s);
                        } else {
                            // Multi-token: fcm_tokens/{uid}/{deviceKey}
                            for (DataSnapshot ds : snap.getChildren()) {
                                SessionInfo s = parseSession(ds, ds.getKey());
                                if (s != null) sessions.add(s);
                            }
                        }
                        sessions.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
                        adapter.setItems(sessions);
                        boolean empty = sessions.isEmpty();
                        if (rvSessions   != null) rvSessions.setVisibility(empty ? View.GONE : View.VISIBLE);
                        if (layoutEmpty  != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                        Toast.makeText(XActiveSessionsActivity.this,
                                "Failed to load sessions", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private SessionInfo parseSession(DataSnapshot ds, String key) {
        String token = ds.child("token").getValue(String.class);
        if (token == null || token.isEmpty()) return null;
        SessionInfo s = new SessionInfo();
        s.key       = key;
        s.token     = token;
        s.platform  = ds.child("platform").getValue(String.class);
        Long ts     = ds.child("updatedAt").getValue(Long.class);
        s.updatedAt = ts != null ? ts : 0;
        s.isCurrent = token.equals(currentToken);
        return s;
    }

    private void revokeSession(SessionInfo session) {
        if (session.isCurrent) {
            Toast.makeText(this, "Cannot revoke the current device session",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Revoke Session")
                .setMessage("This will log out that device. Continue?")
                .setPositiveButton("Revoke", (d, w) -> {
                    XFirebaseUtils.fcmTokenRef(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                                    if (snap.hasChild("token")) {
                                        // Single-token structure — remove the whole node
                                        XFirebaseUtils.fcmTokenRef(myUid).removeValue();
                                    } else {
                                        // Multi-token — remove just this key
                                        XFirebaseUtils.fcmTokenRef(myUid)
                                                .child(session.key).removeValue();
                                    }
                                    Toast.makeText(XActiveSessionsActivity.this,
                                            "Session revoked", Toast.LENGTH_SHORT).show();
                                    loadSessions();
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────

    static class SessionInfo {
        String key;
        String token;
        String platform;
        long   updatedAt;
        boolean isCurrent;
    }

    private class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.VH> {
        private final List<SessionInfo> items = new ArrayList<>();
        private final SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault());

        void setItems(List<SessionInfo> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_x_session, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            SessionInfo s = items.get(pos);
            String platform = s.platform != null ? capitalize(s.platform) : "Android";
            h.tvPlatform.setText(platform + (s.isCurrent ? " (this device)" : ""));
            h.tvLastActive.setText(s.updatedAt > 0
                    ? "Last active: " + sdf.format(new Date(s.updatedAt)) : "Unknown");
            h.btnRevoke.setVisibility(s.isCurrent ? View.GONE : View.VISIBLE);
            h.btnRevoke.setOnClickListener(v -> revokeSession(s));
        }

        @Override public int getItemCount() { return items.size(); }

        private String capitalize(String s) {
            if (s == null || s.isEmpty()) return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvPlatform, tvLastActive;
            Button   btnRevoke;
            VH(View v) {
                super(v);
                tvPlatform  = v.findViewById(R.id.tv_session_platform);
                tvLastActive= v.findViewById(R.id.tv_session_last_active);
                btnRevoke   = v.findViewById(R.id.btn_session_revoke);
            }
        }
    }
}
