package com.callx.app.utils;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.*;

/** StatusLinkPreviewHelper v26 — FIX: reads full HTML (up to 128KB), improved OG parsing. */
public final class StatusLinkPreviewHelper {
    private static final String TAG = "LinkPreview";
    private static final int MAX_BYTES = 131072; // 128KB FIX (was 32KB)
    public interface Callback { void onResult(LinkPreview p); void onError(String e); }
    public static class LinkPreview {
        public String url, title, description, imageUrl, domain, faviconUrl;
        public boolean isValid() { return title != null && !title.isEmpty(); }
    }
    public static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group() : null;
    }
    public static boolean containsUrl(String text) { return extractUrl(text) != null; }
    public static void fetch(String urlStr, Callback cb) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET"); conn.setConnectTimeout(6000); conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent","Mozilla/5.0 (Android 14)");
                conn.setRequestProperty("Accept","text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
                conn.setInstanceFollowRedirects(true); conn.connect();
                // FIX: read up to 128KB
                String charset = "UTF-8";
                String ct = conn.getContentType();
                if (ct != null && ct.contains("charset=")) charset = ct.split("charset=")[1].split(";")[0].trim();
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[MAX_BYTES];
                int total = 0, n;
                while (total < MAX_BYTES && (n = is.read(buf, total, MAX_BYTES - total)) != -1) total += n;
                conn.disconnect();
                String html = new String(buf, 0, total, Charset.forName(charset));
                cb.onResult(parse(urlStr, html));
            } catch (Exception e) {
                Log.w(TAG,"fetch failed: "+e.getMessage()); cb.onError(e.getMessage());
            }
        });
    }
    private static LinkPreview parse(String urlStr, String html) {
        LinkPreview p = new LinkPreview();
        p.url    = urlStr; p.domain = domain(urlStr);
        p.faviconUrl = "https://www.google.com/s2/favicons?domain=" + p.domain + "&sz=64";
        // FIX: try og: then twitter: then standard meta
        p.title       = firstNonNull(og(html,"og:title"), tw(html,"twitter:title"), tag(html,"title"));
        p.description = firstNonNull(og(html,"og:description"), tw(html,"twitter:description"), meta(html,"description"));
        p.imageUrl    = firstNonNull(og(html,"og:image"), tw(html,"twitter:image"), og(html,"og:image:secure_url"));
        return p;
    }
    private static String og(String h, String prop) {
        Matcher m = Pattern.compile("<meta[^>]+property=[\"']"+Pattern.quote(prop)+"[\"'][^>]+content=[\"']([^\"']{1,500})[\"']",
                Pattern.CASE_INSENSITIVE).matcher(h);
        if (m.find()) return unescape(m.group(1));
        m = Pattern.compile("<meta[^>]+content=[\"']([^\"']{1,500})[\"'][^>]+property=[\"']"+Pattern.quote(prop)+"[\"']",
                Pattern.CASE_INSENSITIVE).matcher(h);
        return m.find() ? unescape(m.group(1)) : null;
    }
    private static String tw(String h, String n) {
        Matcher m = Pattern.compile("<meta[^>]+name=[\"']"+n+"[\"'][^>]+content=[\"']([^\"']{1,500})[\"']",
                Pattern.CASE_INSENSITIVE).matcher(h);
        if (m.find()) return unescape(m.group(1));
        m = Pattern.compile("<meta[^>]+content=[\"']([^\"']{1,500})[\"'][^>]+name=[\"']"+n+"[\"']",
                Pattern.CASE_INSENSITIVE).matcher(h);
        return m.find() ? unescape(m.group(1)) : null;
    }
    private static String tag(String h, String t) {
        Matcher m = Pattern.compile("<"+t+"[^>]*>([^<]{1,300})</"+t+">",Pattern.CASE_INSENSITIVE).matcher(h);
        return m.find() ? m.group(1).trim() : null;
    }
    private static String meta(String h, String n) { return tw(h, n); }
    private static String unescape(String s) {
        if (s == null) return null;
        return s.trim().replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")
                .replace("&quot;","\"").replace("&#39;","'").replace("&nbsp;"," ");
    }
    private static String domain(String url) {
        try { String h = new URL(url).getHost(); return h.startsWith("www.") ? h.substring(4) : h; } catch(Exception e){return url;}
    }
    @SafeVarargs private static <T> T firstNonNull(T... vals) {
        for (T v : vals) if (v != null && !v.toString().isEmpty()) return v; return null;
    }
}
