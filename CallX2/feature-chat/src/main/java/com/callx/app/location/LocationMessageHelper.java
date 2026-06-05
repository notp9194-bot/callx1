package com.callx.app.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * LocationMessageHelper — Static + Live location sharing in chat.
 *
 * Message Types:
 *   • "location"      — Static location snapshot (lat/lng/address in text field)
 *   • "live_location" — Live location (updates every 30s for N minutes)
 *
 * Message format (stored in Message.text as pipe-separated):
 *   "location|lat|lng|address"
 *   e.g. "location|28.6139|77.2090|New Delhi, India"
 *
 * Live location additional fields:
 *   Message.liveLocationExpiry = System.currentTimeMillis() + durationMs
 *   Update path: chats/{chatId}/liveLocations/{uid}/lat, lng, updatedAt
 *
 * Usage in ChatActivity:
 *   LocationMessageHelper.getCurrentLocation(ctx, (lat, lng, address) -> {
 *       sendLocationMessage(lat, lng, address, false);
 *   });
 */
public class LocationMessageHelper {

    private static final String TAG = "LocationHelper";

    public interface LocationCallback2 {
        void onLocation(double lat, double lng, String address);
        void onError(String reason);
    }

    /**
     * Fetch current location once (static location message).
     * Requires ACCESS_FINE_LOCATION permission.
     */
    public static void getCurrentLocation(@NonNull Context ctx,
                                          @NonNull LocationCallback2 callback) {
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onError("Location permission not granted");
            return;
        }

        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(ctx);

        client.getLastLocation().addOnSuccessListener(loc -> {
            if (loc != null) {
                String address = reverseGeocode(ctx, loc.getLatitude(), loc.getLongitude());
                callback.onLocation(loc.getLatitude(), loc.getLongitude(), address);
            } else {
                requestFreshLocation(ctx, client, callback);
            }
        }).addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    private static void requestFreshLocation(Context ctx,
                                              FusedLocationProviderClient client,
                                              LocationCallback2 callback) {
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMaxUpdates(1)
                .build();

        client.requestLocationUpdates(req, new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                client.removeLocationUpdates(this);
                Location loc = result.getLastLocation();
                if (loc != null) {
                    String address = reverseGeocode(ctx, loc.getLatitude(), loc.getLongitude());
                    callback.onLocation(loc.getLatitude(), loc.getLongitude(), address);
                } else {
                    callback.onError("Could not get location");
                }
            }
        }, Looper.getMainLooper());
    }

    /**
     * Encode location into message text field.
     * Format: "location|lat|lng|address"
     */
    public static String encodeLocation(double lat, double lng, String address) {
        return "location|" + lat + "|" + lng + "|" + (address != null ? address : "");
    }

    /**
     * Decode location from message text.
     * Returns double[]{lat, lng} or null if invalid.
     */
    public static double[] decodeLatLng(String text) {
        if (text == null || !text.startsWith("location|")) return null;
        String[] parts = text.split("\\|");
        if (parts.length < 3) return null;
        try {
            return new double[]{Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String decodeAddress(String text) {
        if (text == null || !text.startsWith("location|")) return "";
        String[] parts = text.split("\\|", 4);
        return parts.length >= 4 ? parts[3] : "";
    }

    /** Google Maps URL for opening location. */
    public static String mapsUrl(double lat, double lng) {
        return "https://maps.google.com/?q=" + lat + "," + lng;
    }

    private static String reverseGeocode(Context ctx, double lat, double lng) {
        try {
            android.location.Geocoder geocoder = new android.location.Geocoder(ctx,
                    java.util.Locale.getDefault());
            java.util.List<android.location.Address> addrs =
                    geocoder.getFromLocation(lat, lng, 1);
            if (addrs != null && !addrs.isEmpty()) {
                android.location.Address addr = addrs.get(0);
                StringBuilder sb = new StringBuilder();
                if (addr.getLocality() != null)    sb.append(addr.getLocality()).append(", ");
                if (addr.getCountryName() != null) sb.append(addr.getCountryName());
                return sb.toString().trim();
            }
        } catch (Exception e) {
            Log.w(TAG, "Geocoder failed: " + e.getMessage());
        }
        return lat + ", " + lng;
    }
}
