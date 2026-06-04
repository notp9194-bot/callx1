package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;
import com.callx.app.chat.databinding.ActivityLocationPickerBinding;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LocationPickerActivity — Pick and send a location in chat.
 *
 * Features:
 *   ✅ Google Maps full-screen picker
 *   ✅ My Location button — jump to current GPS position
 *   ✅ Tap anywhere on map → drop pin → reverse geocode address
 *   ✅ Address search bar — type to search, results drop on map
 *   ✅ Static map thumbnail URL generated (Google Static Maps API)
 *      → shown as preview bubble in chat
 *   ✅ "Send Location" bottom sheet shows address + coordinates before send
 *   ✅ Deep link: tapping bubble in chat opens Google Maps / Apple Maps
 *
 * Result keys returned to ChatActivity:
 *   RESULT_LAT       — double  (latitude)
 *   RESULT_LNG       — double  (longitude)
 *   RESULT_ADDRESS   — String  (human-readable address)
 *   RESULT_THUMB_URL — String  (static map thumbnail URL)
 *
 * Launch from ChatActivity (attach menu):
 *   Intent i = new Intent(this, LocationPickerActivity.class);
 *   startActivityForResult(i, REQ_LOCATION);
 *
 * Handle result:
 *   if (requestCode == REQ_LOCATION && resultCode == RESULT_OK) {
 *       double lat     = data.getDoubleExtra(LocationPickerActivity.RESULT_LAT, 0);
 *       double lng     = data.getDoubleExtra(LocationPickerActivity.RESULT_LNG, 0);
 *       String address = data.getStringExtra(LocationPickerActivity.RESULT_ADDRESS);
 *       String thumb   = data.getStringExtra(LocationPickerActivity.RESULT_THUMB_URL);
 *       sendLocationMessage(lat, lng, address, thumb);
 *   }
 */
public class LocationPickerActivity extends AppCompatActivity implements OnMapReadyCallback {

    // ── Result keys ────────────────────────────────────────────────────────
    public static final String RESULT_LAT       = "latitude";
    public static final String RESULT_LNG       = "longitude";
    public static final String RESULT_ADDRESS   = "address";
    public static final String RESULT_THUMB_URL = "thumbUrl";

    // ── Permissions ────────────────────────────────────────────────────────
    private static final int REQ_LOCATION_PERM = 601;

    // ── Map config ─────────────────────────────────────────────────────────
    private static final float DEFAULT_ZOOM = 15f;
    private static final float WORLD_ZOOM   = 2f;

    // ── Views ──────────────────────────────────────────────────────────────
    private ActivityLocationPickerBinding binding;

    // ── Map ────────────────────────────────────────────────────────────────
    private GoogleMap   map;
    private Marker      selectedMarker;
    private LatLng      selectedLatLng;
    private String      selectedAddress = "";

    // ── Location ───────────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedLocationClient;

    // ── Threading ──────────────────────────────────────────────────────────
    private final ExecutorService ioExecutor  = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // ── Search debounce ────────────────────────────────────────────────────
    private Runnable searchRunnable;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLocationPickerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        setupToolbar();
        setupSearchBar();
        setupMapFragment();
        setupBottomSheet();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────
    // SETUP
    // ─────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        binding.tvTitle.setText("Share Location");
    }

    private void setupSearchBar() {
        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                binding.btnClearSearch.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                if (q.length() >= 3) scheduleSearch(q);
            }
        });

        binding.btnClearSearch.setOnClickListener(v -> {
            binding.etSearch.setText("");
            binding.btnClearSearch.setVisibility(View.GONE);
        });
    }

    private void setupMapFragment() {
        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) mapFragment.getMapAsync(this);
    }

    private void setupBottomSheet() {
        binding.btnMyLocation.setOnClickListener(v -> requestCurrentLocation());
        binding.btnSendLocation.setOnClickListener(v -> sendSelectedLocation());
        hideSendBar();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MAP READY
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;
        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setMyLocationButtonEnabled(false); // using our own button

        // Tap on map → drop pin
        map.setOnMapClickListener(latLng -> {
            selectedLatLng = latLng;
            dropPin(latLng);
            reverseGeocode(latLng);
        });

        // Try to show current location immediately
        enableMyLocation();
        requestCurrentLocation();
    }

    // ─────────────────────────────────────────────────────────────────────
    // LOCATION PERMISSION & FETCH
    // ─────────────────────────────────────────────────────────────────────

    private void enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            if (map != null) map.setMyLocationEnabled(true);
        }
    }

    private void requestCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                 Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOCATION_PERM);
            return;
        }
        fetchLastLocation();
    }

    private void fetchLastLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        binding.progressLocation.setVisibility(View.VISIBLE);

        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            binding.progressLocation.setVisibility(View.GONE);

            if (location != null) {
                LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                selectedLatLng = latLng;

                if (map != null) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM));
                    dropPin(latLng);
                    reverseGeocode(latLng);
                }
            } else {
                // last location null — show world view
                if (map != null) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(20.5937, 78.9629), WORLD_ZOOM)); // India center
                }
                Toast.makeText(this, "Enable GPS for current location", Toast.LENGTH_SHORT).show();
            }
        }).addOnFailureListener(e -> {
            binding.progressLocation.setVisibility(View.GONE);
            Toast.makeText(this, "Location unavailable", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION_PERM
                && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation();
            fetchLastLocation();
        } else {
            Toast.makeText(this, "Location permission needed", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // MAP INTERACTIONS
    // ─────────────────────────────────────────────────────────────────────

    private void dropPin(LatLng latLng) {
        if (map == null) return;
        if (selectedMarker != null) selectedMarker.remove();
        selectedMarker = map.addMarker(new MarkerOptions()
                .position(latLng)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))
                .title("Selected location"));
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, DEFAULT_ZOOM));
    }

    // ─────────────────────────────────────────────────────────────────────
    // REVERSE GEOCODING
    // ─────────────────────────────────────────────────────────────────────

    private void reverseGeocode(LatLng latLng) {
        showSendBar(null, latLng); // show bar immediately with coords
        binding.tvAddress.setText("Getting address...");

        ioExecutor.execute(() -> {
            String address = resolveAddress(latLng);
            mainHandler.post(() -> {
                selectedAddress = address;
                showSendBar(address, latLng);
            });
        });
    }

    private String resolveAddress(LatLng latLng) {
        try {
            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(
                    latLng.latitude, latLng.longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address a = addresses.get(0);
                StringBuilder sb = new StringBuilder();
                // Build address: subLocality, Locality, Country
                if (a.getSubLocality() != null)   sb.append(a.getSubLocality()).append(", ");
                if (a.getLocality() != null)       sb.append(a.getLocality()).append(", ");
                if (a.getCountryName() != null)    sb.append(a.getCountryName());
                String result = sb.toString().replaceAll(", $", "");
                return result.isEmpty()
                        ? String.format("%.4f, %.4f", latLng.latitude, latLng.longitude)
                        : result;
            }
        } catch (IOException e) {
            // Geocoder unavailable (no network or service unavailable)
        }
        return String.format("%.4f, %.4f", latLng.latitude, latLng.longitude);
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADDRESS SEARCH
    // ─────────────────────────────────────────────────────────────────────

    private void scheduleSearch(String query) {
        if (searchRunnable != null) mainHandler.removeCallbacks(searchRunnable);
        searchRunnable = () -> geocodeAddress(query);
        mainHandler.postDelayed(searchRunnable, 600);
    }

    private void geocodeAddress(String query) {
        ioExecutor.execute(() -> {
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocationName(query, 3);
                if (addresses != null && !addresses.isEmpty()) {
                    Address a = addresses.get(0);
                    LatLng latLng = new LatLng(a.getLatitude(), a.getLongitude());

                    // Build display address
                    StringBuilder sb = new StringBuilder();
                    if (a.getSubLocality() != null) sb.append(a.getSubLocality()).append(", ");
                    if (a.getLocality() != null)    sb.append(a.getLocality()).append(", ");
                    if (a.getCountryName() != null) sb.append(a.getCountryName());
                    String address = sb.toString().replaceAll(", $", "");

                    mainHandler.post(() -> {
                        selectedLatLng  = latLng;
                        selectedAddress = address;
                        dropPin(latLng);
                        showSendBar(address, latLng);
                    });
                }
            } catch (IOException e) {
                mainHandler.post(() ->
                        Toast.makeText(this, "Search unavailable — tap map to pin", Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // BOTTOM SEND BAR
    // ─────────────────────────────────────────────────────────────────────

    private void showSendBar(String address, LatLng latLng) {
        binding.layoutSendBar.setVisibility(View.VISIBLE);
        binding.tvAddress.setText(address != null && !address.isEmpty()
                ? address
                : String.format("%.4f, %.4f", latLng.latitude, latLng.longitude));
        binding.tvCoords.setText(
                String.format(Locale.US, "%.6f, %.6f", latLng.latitude, latLng.longitude));
    }

    private void hideSendBar() {
        binding.layoutSendBar.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SEND LOCATION
    // ─────────────────────────────────────────────────────────────────────

    private void sendSelectedLocation() {
        if (selectedLatLng == null) {
            Toast.makeText(this, "Tap the map to select a location", Toast.LENGTH_SHORT).show();
            return;
        }

        // Build Google Static Maps thumbnail URL
        // Displays a 400×200 map centered on pin (no API key needed for low-res)
        String thumbUrl = buildStaticMapUrl(selectedLatLng);

        Intent result = new Intent();
        result.putExtra(RESULT_LAT,       selectedLatLng.latitude);
        result.putExtra(RESULT_LNG,       selectedLatLng.longitude);
        result.putExtra(RESULT_ADDRESS,   selectedAddress);
        result.putExtra(RESULT_THUMB_URL, thumbUrl);
        setResult(RESULT_OK, result);
        finish();
    }

    /**
     * Builds a Google Static Maps API URL for a 400×200 thumbnail.
     *
     * NOTE: Replace YOUR_MAPS_API_KEY with your actual key, OR use OpenStreetMap
     * tile URL as a free alternative (see below).
     *
     * Google Static Maps:
     *   https://maps.googleapis.com/maps/api/staticmap?center=LAT,LNG&zoom=14
     *   &size=400x200&markers=color:purple|LAT,LNG&key=YOUR_KEY
     *
     * FREE alternative (OpenStreetMap via staticmap.openstreetmap.de):
     *   https://staticmap.openstreetmap.de/staticmap.php?center=LAT,LNG
     *   &zoom=14&size=400x200&markers=LAT,LNG,ol-marker-blue
     */
    private String buildStaticMapUrl(LatLng latLng) {
        double lat = latLng.latitude;
        double lng = latLng.longitude;

        // FREE option: OpenStreetMap staticmap (no key needed)
        return String.format(Locale.US,
                "https://staticmap.openstreetmap.de/staticmap.php"
                + "?center=%.6f,%.6f&zoom=14&size=400x200"
                + "&markers=%.6f,%.6f,ol-marker-blue",
                lat, lng, lat, lng);

        // Google Static Maps (needs API key):
        // String key = BuildConfig.MAPS_STATIC_KEY;
        // return String.format(Locale.US,
        //     "https://maps.googleapis.com/maps/api/staticmap"
        //     + "?center=%.6f,%.6f&zoom=14&size=400x200"
        //     + "&markers=color:purple%%7C%.6f,%.6f&key=%s",
        //     lat, lng, lat, lng, key);
    }
}
