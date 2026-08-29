package com.callx.app.feed;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.callx.app.utils.Constants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * ReelFeedRankingClient
 *
 * Thin client for POST {Constants.SERVER_URL}/reels/rank — the server-side
 * Instagram-style ranker (see index.js: engagement + recency + seen-penalty
 * scoring, reading reelWatchHistory/{uid} which HomeFeedWatchTracker already
 * writes permanently).
 *
 * USAGE:
 * Call rank() with the candidate reelIds for the next page of the feed
 * (Home/Explore query result, straight from Firebase, in whatever order it
 * came back). The callback returns the SAME ids re-sorted best-first; feed
 * simply renders in that order instead of Firebase's natural order.
 *
 * This never blocks the feed — on any failure (network, timeout, non-200)
 * the callback returns null and the caller should just fall back to
 * rendering candidates in their original order, exactly as if ranking were
 * never called.
 */
public final class ReelFeedRankingClient {

    private static final String TAG = "ReelFeedRanking";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build();

    private static final Handler UI = new Handler(Looper.getMainLooper());

    public interface RankCallback {
        /** rankedReelIds — same ids passed in, re-sorted best-first. */
        void onRanked(List<String> rankedReelIds);
        /** Called on any failure — caller should fall back to original order. */
        void onFailed();
    }

    private ReelFeedRankingClient() {}

    public static void rank(String uid, List<String> candidateReelIds, RankCallback callback) {
        if (uid == null || candidateReelIds == null || candidateReelIds.isEmpty()) {
            if (callback != null) callback.onFailed();
            return;
        }

        JSONObject body = new JSONObject();
        try {
            body.put("uid", uid);
            JSONArray arr = new JSONArray();
            for (String id : candidateReelIds) arr.put(id);
            body.put("candidates", arr);
        } catch (Exception e) {
            if (callback != null) callback.onFailed();
            return;
        }

        Request request = new Request.Builder()
            .url(Constants.SERVER_URL + "/reels/rank")
            .post(RequestBody.create(body.toString(), JSON))
            .build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "rank request failed: " + e.getMessage());
                postFailed(callback);
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) { postFailed(callback); return; }
                    JSONObject json = new JSONObject(r.body().string());
                    JSONArray ranked = json.optJSONArray("ranked");
                    if (ranked == null) { postFailed(callback); return; }

                    List<String> result = new ArrayList<>(ranked.length());
                    for (int i = 0; i < ranked.length(); i++) {
                        JSONObject entry = ranked.optJSONObject(i);
                        if (entry != null) {
                            String reelId = entry.optString("reelId", null);
                            if (reelId != null) result.add(reelId);
                        }
                    }
                    postRanked(callback, result);
                } catch (Exception e) {
                    Log.w(TAG, "rank response parse failed: " + e.getMessage());
                    postFailed(callback);
                }
            }
        });
    }

    private static void postRanked(RankCallback callback, List<String> ids) {
        if (callback == null) return;
        UI.post(() -> callback.onRanked(ids));
    }

    private static void postFailed(RankCallback callback) {
        if (callback == null) return;
        UI.post(callback::onFailed);
    }
}
