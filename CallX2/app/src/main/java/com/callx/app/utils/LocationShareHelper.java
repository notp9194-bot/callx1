package com.callx.app.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Looper;
import android.util.Log;
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
 * Feature 2 (NEW): Location Sharing
 * Uses FusedLocationProviderClient for accurate one-shot location.
 * Performs reverse geocoding to get a human-readable address.
 * Generates a static map thumbnail URL (OpenStreetMap / Google Maps).
 */
public class LocationShareHelper {

    private static final String TAG = "LocationShare";

    public interface LocationCallback2 {
        void onLocation(double lat, double lng, String address, String mapUrl);
        void onError(String reason);
    }

    private final Context context;
    private final FusedLocationProviderClient client;

    public LocationShareHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.client  = LocationServices.getFusedLocationProviderClient(ctx);
    }

    public static boolean hasPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public void getCurrentLocation(LocationCallback2 cb) {
        if (!hasPermission(context)) {
            cb.onError("Location permission not granted");
            return;
        }
        try {
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                    .setMaxUpdates(1)
                    .build();
            client.requestLocationUpdates(req, new LocationCallback() {
                @Override public void onLocationResult(LocationResult res) {
                    client.removeLocationUpdates(this);
                    Location loc = res.getLastLocation();
                    if (loc == null) { cb.onError("Could not get location"); return; }
                    double lat = loc.getLatitude();
                    double lng = loc.getLongitude();
                    String addr = reverseGeocode(lat, lng);
                    String mapUrl = staticMapUrl(lat, lng);
                    cb.onLocation(lat, lng, addr, mapUrl);
                }
            }, Looper.getMainLooper());
        } catch (SecurityException e) {
            cb.onError("Permission denied");
        }
    }

    private String reverseGeocode(double lat, double lng) {
        try {
            Geocoder gc = new Geocoder(context, Locale.getDefault());
            List<Address> addrs = gc.getFromLocation(lat, lng, 1);
            if (addrs != null && !addrs.isEmpty()) {
                Address a = addrs.get(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(a.getAddressLine(i));
                }
                return sb.toString();
            }
        } catch (Exception e) { Log.e(TAG, "Geocode failed", e); }
        return String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }

    /** OpenStreetMap static tile thumbnail — no API key required. */
    public static String staticMapUrl(double lat, double lng) {
        int zoom = 15;
        int size = 300;
        return String.format(Locale.US,
                "https://staticmap.openstreetmap.de/staticmap.php?center=%.5f,%.5f&zoom=%d&size=%dx%d&maptype=osm&markers=%.5f,%.5f,red-pushpin",
                lat, lng, zoom, size, size, lat, lng);
    }

    /** Deep-link to open in Google Maps / native map app. */
    public static String googleMapsUrl(double lat, double lng) {
        return String.format(Locale.US,
                "https://www.google.com/maps/search/?api=1&query=%.5f,%.5f", lat, lng);
    }
}
