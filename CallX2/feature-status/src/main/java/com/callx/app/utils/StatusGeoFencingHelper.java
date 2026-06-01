package com.callx.app.utils;

import com.callx.app.models.StatusItem;

/** StatusGeoFencingHelper v26 — Check if viewer is within status's geo-fence. */
public final class StatusGeoFencingHelper {
    private StatusGeoFencingHelper() {}

    /** Returns true if viewer location is within the status geo-fence (or no geo-fence set). */
    public static boolean canViewBasedOnLocation(StatusItem item, double viewerLat, double viewerLng) {
        if (item == null) return true;
        if (!item.hasGeoFence()) return true;   // no restriction
        double dist = distanceKm(viewerLat, viewerLng, item.geoFenceLat, item.geoFenceLng);
        return dist <= item.geoFenceRadiusKm;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                 + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                 *Math.sin(dLon/2)*Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }
}
