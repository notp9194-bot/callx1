package com.callx.app.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

public class NetworkUtils {

    public static boolean isOnline(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    public enum Quality { FAST, SLOW, OFFLINE }

    public static Quality getNetworkQuality(Context ctx) {
        if (!isOnline(ctx)) return Quality.OFFLINE;

        ConnectivityManager cm = (ConnectivityManager)
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return Quality.SLOW;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return Quality.OFFLINE;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return Quality.SLOW;

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Quality.FAST;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return Quality.FAST;
            return Quality.SLOW;
        }
        return Quality.SLOW;
    }
}
