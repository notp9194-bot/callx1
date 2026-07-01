package com.callx.app.chat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.List;
import java.util.Locale;

/**
 * Minimal location picker — fetches current GPS fix via FusedLocationProvider,
 * reverse-geocodes it, then returns:
 *
 *   EXTRA_LAT      (double)  — latitude
 *   EXTRA_LNG      (double)  — longitude
 *   EXTRA_ADDRESS  (String)  — human-readable address (may be empty string)
 *
 * Usage:
 *   startActivityForResult(
 *       new Intent(this, LocationSharePickerActivity.class), REQUEST_LOCATION);
 *
 * RESULT_OK is only set when a valid location is obtained.
 */
public class LocationSharePickerActivity extends AppCompatActivity {

    public static final String EXTRA_LAT     = "location_lat";
    public static final String EXTRA_LNG     = "location_lng";
    public static final String EXTRA_ADDRESS = "location_address";

    private static final int REQ_PERM = 102;

    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;

    private ProgressBar progressBar;
    private TextView    tvStatus;
    private TextView    tvAddress;
    private Button      btnSend;

    private double resolvedLat = 0;
    private double resolvedLng = 0;
    private String resolvedAddress = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        // ── Build layout programmatically ─────────────────────────────────────
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Back button row
        LinearLayout toolbarRow = new LinearLayout(this);
        toolbarRow.setOrientation(LinearLayout.HORIZONTAL);
        toolbarRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView btnBack = new android.widget.ImageView(this);
        btnBack.setImageResource(com.callx.app.chat.R.drawable.ic_back);
        btnBack.setPadding(dp(4), dp(4), dp(16), dp(4));
        btnBack.setOnClickListener(v -> finish());
        toolbarRow.addView(btnBack, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Location bhejo");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbarRow.addView(tvTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams toolbarLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        toolbarLp.setMargins(0, 0, 0, dp(24));
        root.addView(toolbarRow, toolbarLp);

        // Location pin icon
        android.widget.ImageView ivPin = new android.widget.ImageView(this);
        ivPin.setImageResource(com.callx.app.chat.R.drawable.ic_location_pin);
        root.addView(ivPin, new LinearLayout.LayoutParams(dp(72), dp(72)));

        tvStatus = new TextView(this);
        tvStatus.setText("Aapki location dhoond raha hai…");
        tvStatus.setTextSize(15);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setTextColor(resolveAttrColor(android.R.attr.textColorPrimary));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(16), 0, dp(8));
        root.addView(tvStatus, statusLp);

        progressBar = new ProgressBar(this);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        tvAddress = new TextView(this);
        tvAddress.setGravity(Gravity.CENTER);
        tvAddress.setTextSize(13);
        tvAddress.setTextColor(resolveAttrColor(android.R.attr.textColorSecondary));
        tvAddress.setVisibility(View.GONE);
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        addrLp.setMargins(0, dp(12), 0, 0);
        root.addView(tvAddress, addrLp);

        btnSend = new Button(this);
        btnSend.setText("Is location ko bhejo");
        btnSend.setVisibility(View.GONE);
        btnSend.setOnClickListener(v -> sendResult());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, dp(24), 0, 0);
        root.addView(btnSend, btnLp);

        setContentView(root);

        checkPermissionAndFetch();
    }

    // ── Permission ────────────────────────────────────────────────────────────

    private void checkPermissionAndFetch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fetchLocation();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERM && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            fetchLocation();
        } else {
            Toast.makeText(this, "Location permission nahi mila", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ── Location fetch ────────────────────────────────────────────────────────

    private void fetchLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        // Try last known first (instant)
        fusedClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                onLocationObtained(location);
            } else {
                // Request fresh fix
                LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                        .setMaxUpdates(1)
                        .build();
                locationCallback = new LocationCallback() {
                    @Override
                    public void onLocationResult(@NonNull LocationResult res) {
                        if (!res.getLocations().isEmpty()) {
                            onLocationObtained(res.getLocations().get(0));
                        }
                        fusedClient.removeLocationUpdates(locationCallback);
                    }
                };
                fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
            }
        }).addOnFailureListener(e -> {
            tvStatus.setText("Location nahi mili — please try again");
            progressBar.setVisibility(View.GONE);
        });
    }

    private void onLocationObtained(Location loc) {
        resolvedLat = loc.getLatitude();
        resolvedLng = loc.getLongitude();

        progressBar.setVisibility(View.GONE);
        tvStatus.setText(String.format(Locale.getDefault(), "%.5f, %.5f", resolvedLat, resolvedLng));

        // Reverse geocode on background thread
        new Thread(() -> {
            String address = "";
            try {
                if (Geocoder.isPresent()) {
                    Geocoder geo = new Geocoder(this, Locale.getDefault());
                    List<Address> results = geo.getFromLocation(resolvedLat, resolvedLng, 1);
                    if (results != null && !results.isEmpty()) {
                        Address a = results.get(0);
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(a.getAddressLine(i));
                        }
                        address = sb.toString();
                    }
                }
            } catch (Exception ignored) {}
            final String finalAddress = address;
            runOnUiThread(() -> {
                resolvedAddress = finalAddress;
                if (!finalAddress.isEmpty()) {
                    tvAddress.setText(finalAddress);
                    tvAddress.setVisibility(View.VISIBLE);
                }
                btnSend.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    // ── Send result ───────────────────────────────────────────────────────────

    private void sendResult() {
        Intent result = new Intent();
        result.putExtra(EXTRA_LAT,     resolvedLat);
        result.putExtra(EXTRA_LNG,     resolvedLng);
        result.putExtra(EXTRA_ADDRESS, resolvedAddress);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fusedClient != null && locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int resolveAttrColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
