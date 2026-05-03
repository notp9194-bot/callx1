package com.callx.app.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.telephony.TelephonyManager;

/**
 * NetworkUtils — Network connectivity and quality helpers.
 *
 * Methods:
 *   isOnline(ctx)            → true if any network is available
 *   getNetworkQuality(ctx)   → Quality.FAST | MEDIUM | SLOW
 */
public class NetworkUtils {

    public enum Quality { FAST, MEDIUM, SLOW }

    /** Returns true if the device has an active network connection. */
    public static boolean isOnline(Context ctx) {
        if (ctx == null) return false;
        ConnectivityManager cm =
            (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        } else {
            //noinspection deprecation
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            //noinspection deprecation
            return info != null && info.isConnected();
        }
    }

    /**
     * Estimates network quality.
     *   WiFi / Ethernet      → FAST
     *   4G / LTE             → MEDIUM
     *   3G / 2G / EDGE / GPRS → SLOW
     *   No connection         → SLOW
     */
    public static Quality getNetworkQuality(Context ctx) {
        if (ctx == null) return Quality.SLOW;
        ConnectivityManager cm =
            (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return Quality.SLOW;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return Quality.SLOW;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return Quality.SLOW;

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return Quality.FAST;
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return getCellularQuality(ctx);
            }
        } else {
            //noinspection deprecation
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return Quality.SLOW;
            //noinspection deprecation
            int type = info.getType();
            //noinspection deprecation
            if (type == ConnectivityManager.TYPE_WIFI ||
                //noinspection deprecation
                type == ConnectivityManager.TYPE_ETHERNET) {
                return Quality.FAST;
            }
            //noinspection deprecation
            if (type == ConnectivityManager.TYPE_MOBILE) {
                return getCellularQuality(ctx);
            }
        }
        return Quality.MEDIUM;
    }

    /** Maps cellular subtype to quality bucket. */
    private static Quality getCellularQuality(Context ctx) {
        try {
            TelephonyManager tm =
                (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return Quality.MEDIUM;
            int subtype = tm.getNetworkType();
            switch (subtype) {
                // 4G / LTE / NR (5G)
                case TelephonyManager.NETWORK_TYPE_LTE:
                case TelephonyManager.NETWORK_TYPE_NR:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                    return Quality.MEDIUM;

                // 3G
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                    return Quality.SLOW;

                // 2G / EDGE / GPRS
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    return Quality.SLOW;

                default:
                    return Quality.MEDIUM;
            }
        } catch (SecurityException e) {
            // READ_PHONE_STATE permission nahi hai — safe default
            return Quality.MEDIUM;
        }
    }

    private NetworkUtils() {}
}
