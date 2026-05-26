package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Subscriptions tab — videos from channels the user follows, newest first.
 */
public class YouTubeSubscriptionsFragment extends Fragment {

    private RecyclerView       rvSubs;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar        pbLoading;
    private TextView           tvEmpty;
    private YouTubeVideoAdapter adapter;
    private String myUid;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_subscriptions, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        rvSubs       = view.findViewById(R.id.rv_yt_subs_feed);
        swipeRefresh = view.findViewById(R.id.srl_yt_subs);
        pbLoading    = view.findViewById(R.id.pb_yt_subs);
        tvEmpty      = view.findViewById(R.id.tv_yt_subs_empty);

        adapter = new YouTubeVideoAdapter(requireActivity(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvSubs.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvSubs.setAdapter(adapter);

        if (swipeRefresh != null)
            swipeRefresh.setOnRefreshListener(this::loadFeed);

        loadFeed();
    }

    private void loadFeed() {
        if (myUid.isEmpty()) {
            showEmpty(true);
            return;
        }
        showLoading(true);
        YouTubeFirebaseUtils.subscriptionsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> channelUids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) channelUids.add(ds.getKey());

                    if (channelUids.isEmpty()) {
                        showEmpty(true);
                        showLoading(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                        return;
                    }
                    fetchVideosFromChannels(channelUids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    showLoading(false);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                }
            });
    }

    private void fetchVideosFromChannels(List<String> channelUids) {
        List<YouTubeVideo> allVideos = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger(0);
        int total = channelUids.size();

        for (String uid : channelUids) {
            YouTubeFirebaseUtils.userVideosRef(uid)
                .orderByValue().limitToLast(5)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        List<String> ids = new ArrayList<>();
                        for (DataSnapshot ds : snap.getChildren())
                            if (ds.getKey() != null) ids.add(ds.getKey());

                        AtomicInteger vidDone = new AtomicInteger(0);
                        if (ids.isEmpty()) {
                            if (done.incrementAndGet() == total) postResult(allVideos);
                            return;
                        }
                        for (String id : ids) {
                            YouTubeFirebaseUtils.videoRef(id).addListenerForSingleValueEvent(
                                new ValueEventListener() {
                                    @Override public void onDataChange(@NonNull DataSnapshot vs) {
                                        YouTubeVideo v = vs.getValue(YouTubeVideo.class);
                                        if (v != null && "public".equals(v.visibility)) allVideos.add(v);
                                        if (vidDone.incrementAndGet() == ids.size())
                                            if (done.incrementAndGet() == total) postResult(allVideos);
                                    }
                                    @Override public void onCancelled(@NonNull DatabaseError e) {
                                        if (vidDone.incrementAndGet() == ids.size())
                                            if (done.incrementAndGet() == total) postResult(allVideos);
                                    }
                                });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (done.incrementAndGet() == total) postResult(allVideos);
                    }
                });
        }
    }

    private void postResult(List<YouTubeVideo> videos) {
        // Sort newest first
        Collections.sort(videos, (a, b) -> Long.compare(b.uploadedAt, a.uploadedAt));
        requireActivity().runOnUiThread(() -> {
            adapter.setData(videos);
            showLoading(false);
            showEmpty(videos.isEmpty());
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        });
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showEmpty(boolean show) {
        if (tvEmpty != null) tvEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
