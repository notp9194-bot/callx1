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
 * XLoggedDevicesActivity — Shows all devices where this X account is
 * currently logged in, with the ability to log out individual devices.
 *
 * Uses the same Firebase data source as XActiveSessionsActivity
 * (x/fcm_tokens/{uid}) but focuses on the device model / platform identity.
 *
 * Logging out a device removes its FCM token from Firebase, preventing
 * future push notifications to that device. The next time the user opens
 * the app on that device, it will re-register.
 */
public class XLoggedDevicesActivity extends AppCompatActivity {

    private RecyclerView rvDevices;
    private View layoutEmpty, layoutLoading;
    private DeviceAdapter adapter;
    private String myUid;
    private String currentToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_logged_devices);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        Toolbar toolbar = findViewById(R.id.toolbar_x_devices);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Logged-in Devices");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rvDevices     = findViewById(R.id.rv_x_devices);
        layoutEmpty   = findViewById(R.id.layout_empty_devices);
        layoutLoading = findViewById(R.id.layout_loading_devices);

        adapter = new DeviceAdapter();
        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        rvDevices.setAdapter(adapter);

        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> { currentToken = token; loadDevices(); })
                .addOnFailureListener(e -> loadDevices());
    }

    private void loadDevices() {
        if (myUid.isEmpty()) return;
        if (layoutLoading != null) layoutLoading.setVisibility(View.VISIBLE);

        XFirebaseUtils.fcmTokenRef(myUid).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                        List<DeviceInfo> devices = new ArrayList<>();

                        if (snap.hasChild("token")) {
                            DeviceInfo d = parseDevice(snap, snap.getKey());
                            if (d != null) devices.add(d);
                        } else {
                            for (DataSnapshot ds : snap.getChildren()) {
                                DeviceInfo d = parseDevice(ds, ds.getKey());
                                if (d != null) devices.add(d);
                            }
                        }
                        devices.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
                        adapter.setItems(devices);
                        boolean empty = devices.isEmpty();
                        if (rvDevices  != null) rvDevices.setVisibility(empty ? View.GONE : View.VISIBLE);
                        if (layoutEmpty!= null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (layoutLoading != null) layoutLoading.setVisibility(View.GONE);
                    }
                });
    }

    private DeviceInfo parseDevice(DataSnapshot ds, String key) {
        String token = ds.child("token").getValue(String.class);
        if (token == null || token.isEmpty()) return null;
        DeviceInfo d   = new DeviceInfo();
        d.key          = key;
        d.token        = token;
        d.platform     = ds.child("platform").getValue(String.class);
        d.deviceModel  = ds.child("deviceModel").getValue(String.class);
        Long ts        = ds.child("updatedAt").getValue(Long.class);
        d.updatedAt    = ts != null ? ts : 0;
        d.isCurrent    = token.equals(currentToken);
        return d;
    }

    private void logOutDevice(DeviceInfo device) {
        if (device.isCurrent) {
            Toast.makeText(this, "Cannot log out the current device here. Use the Sign Out option.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Log Out Device")
                .setMessage("This will stop notifications on that device. They will need to sign in again to use X.")
                .setPositiveButton("Log Out", (d, w) -> {
                    XFirebaseUtils.fcmTokenRef(myUid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                                    if (snap.hasChild("token")) {
                                        XFirebaseUtils.fcmTokenRef(myUid).removeValue();
                                    } else {
                                        XFirebaseUtils.fcmTokenRef(myUid).child(device.key).removeValue();
                                    }
                                    Toast.makeText(XLoggedDevicesActivity.this,
                                            "Device logged out", Toast.LENGTH_SHORT).show();
                                    loadDevices();
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {}
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────

    static class DeviceInfo {
        String  key, token, platform, deviceModel;
        long    updatedAt;
        boolean isCurrent;
    }

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {
        private final List<DeviceInfo> items = new ArrayList<>();
        private final SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault());

        void setItems(List<DeviceInfo> list) {
            items.clear(); items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_x_device, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            DeviceInfo d = items.get(pos);
            String model = d.deviceModel != null ? d.deviceModel :
                    (d.platform != null ? capitalize(d.platform) + " Device" : "Unknown Device");
            h.tvDeviceName.setText(model + (d.isCurrent ? " (this device)" : ""));
            h.tvPlatform.setText(d.platform != null ? capitalize(d.platform) : "Android");
            h.tvLastSeen.setText(d.updatedAt > 0
                    ? "Last active: " + sdf.format(new Date(d.updatedAt)) : "Unknown");
            h.btnLogOut.setVisibility(d.isCurrent ? View.GONE : View.VISIBLE);
            h.btnLogOut.setOnClickListener(v -> logOutDevice(d));
        }

        @Override public int getItemCount() { return items.size(); }

        private String capitalize(String s) {
            if (s == null || s.isEmpty()) return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDeviceName, tvPlatform, tvLastSeen;
            Button   btnLogOut;
            VH(View v) {
                super(v);
                tvDeviceName= v.findViewById(R.id.tv_device_name);
                tvPlatform  = v.findViewById(R.id.tv_device_platform);
                tvLastSeen  = v.findViewById(R.id.tv_device_last_seen);
                btnLogOut   = v.findViewById(R.id.btn_device_logout);
            }
        }
    }
}
