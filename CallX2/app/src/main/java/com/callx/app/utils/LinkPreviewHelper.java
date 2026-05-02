package com.callx.app.utils;

import android.os.AsyncTask;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feature 4: Link Preview
 * Fetches Open Graph / Twitter Card metadata from a URL in a background thread.
 */
public class LinkPreviewHelper {

    private static final String TAG = "LinkPreview";
    private static final int TIMEOUT = 5000;

    public interface Callback {
        void onResult(String title, String description, String imageUrl, String siteName);
        void onError();
    }

    public static class LinkData {
        public String url;
        public String title;
        public String description;
        public String imageUrl;
        public String siteName;
    }

    /** Extract first URL from text; returns null if none. */
    public static String extractUrl(String text) {
        if (text == null) return null;
        Pattern p = Pattern.compile(
                "https?://[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-\\.,@?^=%&:/~+#]*[\\w\\-\\@?^=%&/~+#])?",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(0) : null;
    }

    public static void fetch(String urlStr, Callback cb) {
        new AsyncTask<Void, Void, LinkData>() {
            @Override protected LinkData doInBackground(Void... v) {
                try {
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(TIMEOUT);
                    conn.setReadTimeout(TIMEOUT);
                    conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (compatible; CallXBot/1.0)");
                    conn.connect();
                    if (conn.getResponseCode() != 200) return null;
                    StringBuilder sb = new StringBuilder();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    String line;
                    int count = 0;
                    while ((line = br.readLine()) != null && count++ < 300) {
                        sb.append(line);
                        if (line.contains("</head>")) break;
                    }
                    br.close();
                    conn.disconnect();
                    String html = sb.toString();
                    LinkData data = new LinkData();
                    data.url      = urlStr;
                    data.title    = ogMeta(html, "og:title",       "title");
                    data.description = ogMeta(html, "og:description", "description");
                    data.imageUrl = ogMeta(html, "og:image",       null);
                    data.siteName = ogMeta(html, "og:site_name",   null);
                    if (data.siteName == null) {
                        try { data.siteName = new URL(urlStr).getHost(); }
                        catch (Exception ignored) {}
                    }
                    return data;
                } catch (Exception e) {
                    Log.e(TAG, "Fetch failed: " + urlStr, e);
                    return null;
                }
            }
            @Override protected void onPostExecute(LinkData d) {
                if (d != null) cb.onResult(d.title, d.description, d.imageUrl, d.siteName);
                else           cb.onError();
            }
        }.execute();
    }

    private static String ogMeta(String html, String ogProp, String fallbackTag) {
        // og: meta
        Pattern p = Pattern.compile(
                "<meta[^>]+property=[\"']" + Pattern.quote(ogProp) + "[\"'][^>]+content=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(1);
        // alt order
        p = Pattern.compile(
                "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']" + Pattern.quote(ogProp) + "[\"']",
                Pattern.CASE_INSENSITIVE);
        m = p.matcher(html);
        if (m.find()) return m.group(1);
        // fallback tag
        if (fallbackTag != null) {
            if (fallbackTag.equals("title")) {
                p = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
                m = p.matcher(html);
                if (m.find()) return m.group(1).trim();
            } else if (fallbackTag.equals("description")) {
                p = Pattern.compile(
                        "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']",
                        Pattern.CASE_INSENSITIVE);
                m = p.matcher(html);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }
}
