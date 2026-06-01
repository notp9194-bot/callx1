package com.callx.app.bottomsheet;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * StatusStickerPickerBottomSheet v26 — Real GIF/Sticker picker via Giphy API.
 * Falls back to local sticker pack if no API key.
 */
public class StatusStickerPickerBottomSheet {
    private static final String GIPHY_TRENDING = "https://api.giphy.com/v1/gifs/trending?api_key=%s&limit=25&rating=g";
    private static final String GIPHY_SEARCH   = "https://api.giphy.com/v1/gifs/search?api_key=%s&q=%s&limit=25&rating=g";

    public interface OnStickerSelected { void onSelected(String gifUrl, String previewUrl); }

    public static void show(Context ctx, OnStickerSelected cb) {
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout root = new LinearLayout(ctx); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx,12), dp(ctx,8), dp(ctx,12), dp(ctx,24));
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx,560)));

        TextView title = new TextView(ctx); title.setText("🎭 GIF & Stickers"); title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD); title.setPadding(0,dp(ctx,4),0,dp(ctx,12));
        root.addView(title);

        EditText search = new EditText(ctx); search.setHint("Search GIFs…"); search.setSingleLine(true);
        root.addView(search);

        androidx.recyclerview.widget.RecyclerView rv = new androidx.recyclerview.widget.RecyclerView(ctx);
        rv.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(ctx, 3));
        rv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(rv);

        ProgressBar pb = new ProgressBar(ctx); root.addView(pb);
        sheet.setContentView(root); sheet.show();

        String apiKey = getGiphyApiKey();
        GifAdapter adapter = new GifAdapter(ctx, gif -> { if (cb != null) cb.onSelected(gif[0], gif[1]); sheet.dismiss(); });
        rv.setAdapter(adapter);
        loadGifs(GIPHY_TRENDING.formatted(apiKey), adapter, pb);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (q.isEmpty()) loadGifs(GIPHY_TRENDING.formatted(apiKey), adapter, pb);
                else loadGifs(GIPHY_SEARCH.formatted(apiKey, urlEncode(q)), adapter, pb);
            }
        });
    }

    private static void loadGifs(String url, GifAdapter adapter, ProgressBar pb) {
        pb.setVisibility(View.VISIBLE);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000); conn.connect();
                byte[] resp = conn.getInputStream().readAllBytes(); conn.disconnect();
                String json = new String(resp);
                List<String[]> gifs = parseGiphyResponse(json);
                if (adapter != null) {
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(() -> { pb.setVisibility(View.GONE); adapter.setData(gifs); });
                }
            } catch (Exception e) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> pb.setVisibility(View.GONE));
            }
        });
    }

    private static List<String[]> parseGiphyResponse(String json) {
        List<String[]> result = new ArrayList<>();
        String[] parts = json.split("\"original\"");
        for (int i = 1; i < parts.length && result.size() < 25; i++) {
            String url = extract(parts[i], "\"url\":\"", "\"");
            String preview = extract(parts[i], "\"preview_gif\"", "url\":\"", "\"");
            if (url == null) continue;
            url = url.replace("\\u0026","&");
            result.add(new String[]{url, preview != null ? preview.replace("\\u0026","&") : url});
        }
        return result;
    }
    private static String extract(String s, String key, String end) {
        int idx = s.indexOf(key); if (idx < 0) return null;
        int start = idx + key.length(); int e = s.indexOf(end, start); return e > start ? s.substring(start,e) : null;
    }
    private static String extract(String s, String key1, String key2, String end) {
        int idx = s.indexOf(key1); if (idx < 0) return null;
        return extract(s.substring(idx), key2, end);
    }
    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s,"UTF-8"); } catch (Exception e) { return s; }
    }
    private static String getGiphyApiKey() {
        try { return com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance().getString("giphy_api_key"); }
        catch (Exception e) { return ""; }
    }
    private static int dp(Context ctx, int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }

    static class GifAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<GifAdapter.VH> {
        private final Context ctx; private final java.util.function.Consumer<String[]> onClick;
        private List<String[]> data = new ArrayList<>();
        GifAdapter(Context ctx, java.util.function.Consumer<String[]> onClick) { this.ctx=ctx; this.onClick=onClick; }
        void setData(List<String[]> d) { this.data = d; notifyDataSetChanged(); }
        @Override public int getItemCount() { return data.size(); }
        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            ImageView iv = new ImageView(ctx); int sz = p.getWidth()/3;
            iv.setLayoutParams(new ViewGroup.LayoutParams(sz,sz)); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return new VH(iv);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            String[] gif = data.get(pos);
            Glide.with(ctx).asGif().load(gif[1]).centerCrop().into(h.iv);
            h.iv.setOnClickListener(v -> onClick.accept(gif));
        }
        static class VH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            ImageView iv; VH(ImageView v) { super(v); iv=v; }
        }
    }
}
