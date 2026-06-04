package com.callx.app.youtube.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.content.Intent;
import com.callx.app.youtube.player.YouTubePlayerActivity;
import com.callx.app.youtube.core.adapters.YouTubeVideoAdapter;
import com.callx.app.youtube.core.models.YouTubeVideo;
import com.callx.app.youtube.core.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.core.utils.YouTubePrefs;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.content.Context;
import android.os.Build;
import com.callx.app.youtube.home.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

public class YouTubeHomeFragment extends Fragment {

    private RecyclerView        rvFeed;
    private SwipeRefreshLayout  swipeRefresh;
    private ProgressBar         pbLoading;   // ← new
    private View                llEmpty;     // ← new
    private YouTubeVideoAdapter adapter;
    private ValueEventListener  feedListener;
    private YouTubePrefs        ytPrefs;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_home, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        ytPrefs      = new YouTubePrefs(requireContext());
        rvFeed       = view.findViewById(R.id.rv_yt_home_feed);
        swipeRefresh = view.findViewById(R.id.srl_yt_home);
        pbLoading    = view.findViewById(R.id.pb_yt_home);
        llEmpty      = view.findViewById(R.id.ll_yt_empty);

        adapter = new YouTubeVideoAdapter(requireActivity(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvFeed.setAdapter(adapter);
        applyPlaybackInFeeds();

        swipeRefresh.setOnRefreshListener(() -> {
            detachFeedListener();
            loadFeed();
        });

        loadFeed();
    }

    private void loadFeed() {
        showLoading(true);
        feedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                boolean restrictedMode = ytPrefs.isRestrictedMode();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v == null) continue;
                    if (v.isShort) continue;
                    if (!"public".equals(v.visibility)) continue;
                    if (v.videoUrl == null || v.videoUrl.trim().isEmpty()) continue;
                    // Restricted mode: skip videos marked as mature/age-restricted
                    if (restrictedMode && v.isAgeRestricted) continue;
                    list.add(0, v);
                }
                adapter.setData(list);
                showLoading(false);
                showEmpty(list.isEmpty());
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                showLoading(false);
                showEmpty(true);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(30)
            .addValueEventListener(feedListener);
    }

    private void showLoading(boolean show) {
        if (pbLoading != null)
            pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (swipeRefresh != null)
            swipeRefresh.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
    }

    private void showEmpty(boolean show) {
        if (llEmpty != null)
            llEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        if (rvFeed != null)
            rvFeed.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void detachFeedListener() {
        if (feedListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(feedListener);
        feedListener = null;
    }

    /**
     * Playback in feeds setting:
     * 0 = On (always autoplay muted previews)
     * 1 = Wi-Fi only
     * 2 = Off (no autoplay in feed)
     */
    private void applyPlaybackInFeeds() {
        if (rvFeed == null || adapter == null) return;
        int setting = ytPrefs.getPlaybackInFeeds();
        boolean shouldAutoplay;
        switch (setting) {
            case 0: shouldAutoplay = true;               break;
            case 1: shouldAutoplay = isOnWifi();         break;
            default: shouldAutoplay = false;             break;
        }
        adapter.setFeedAutoplay(shouldAutoplay);
    }

    private boolean isOnWifi() {
        try {
            Context ctx = getContext();
            if (ctx == null) return false;
            ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network nw = cm.getActiveNetwork();
                if (nw == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        detachFeedListener();
    }
}
