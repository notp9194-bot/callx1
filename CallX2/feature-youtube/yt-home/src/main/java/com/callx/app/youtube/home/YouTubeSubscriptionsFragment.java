package com.callx.app.youtube.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.youtube.core.adapters.YouTubeVideoAdapter;
import com.callx.app.youtube.core.models.YouTubeVideo;
import com.callx.app.youtube.core.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.home.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shows videos from subscribed channels, sorted by recency.
 *
 * FIX: Race condition — done[0]++ tha but inner async videoRef fetches
 * count nahi hote the. Ab AtomicInteger + total video count track karein.
 * Outer loop sirf channel-level done track karta tha, inner video fetches
 * nahi — setData bahut pehle call ho jaata tha with empty list.
 */
public class YouTubeSubscriptionsFragment extends Fragment {

    private RecyclerView        rvSubs;
    private YouTubeVideoAdapter adapter;
    private TextView            tvEmpty;
    private String              myUid;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_subscriptions, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        myUid   = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        rvSubs  = view.findViewById(R.id.rv_yt_subs_feed);
        tvEmpty = view.findViewById(R.id.tv_yt_subs_empty);

        adapter = new YouTubeVideoAdapter(requireActivity(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvSubs.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvSubs.setAdapter(adapter);

        loadSubscriptionFeed();
    }

    private void loadSubscriptionFeed() {
        if (myUid.isEmpty()) { showEmpty(); return; }
        YouTubeFirebaseUtils.subscriptionsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Set<String> uids = new HashSet<>();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) uids.add(ds.getKey());
                    if (uids.isEmpty()) { showEmpty(); return; }
                    fetchVideosFromChannels(uids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { showEmpty(); }
            });
    }

    private void fetchVideosFromChannels(Set<String> uids) {
        // Step 1: collect all video IDs from all channels
        List<String> allIds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger channelsDone = new AtomicInteger(0);

        for (String uid : uids) {
            YouTubeFirebaseUtils.userVideosRef(uid)
                .orderByValue().limitToLast(5)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        for (DataSnapshot ds : snap.getChildren())
                            if (ds.getKey() != null) allIds.add(ds.getKey());

                        // Step 2: when all channels done, fetch video details
                        if (channelsDone.incrementAndGet() == uids.size()) {
                            fetchVideoDetails(allIds);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (channelsDone.incrementAndGet() == uids.size()) {
                            fetchVideoDetails(allIds);
                        }
                    }
                });
        }
    }

    private void fetchVideoDetails(List<String> ids) {
        if (ids.isEmpty()) { showEmpty(); return; }

        List<YouTubeVideo> all = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger(0);
        int total = ids.size();

        for (String id : ids) {
            YouTubeFirebaseUtils.videoRef(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        YouTubeVideo v = snap.getValue(YouTubeVideo.class);
                        if (v != null && "public".equals(v.visibility)
                                && v.videoUrl != null && !v.videoUrl.trim().isEmpty())
                            all.add(v);

                        if (done.incrementAndGet() == total) {
                            all.sort((a, b) -> Long.compare(b.uploadedAt, a.uploadedAt));
                            if (getActivity() != null) {
                                requireActivity().runOnUiThread(() -> {
                                    adapter.setData(new ArrayList<>(all));
                                    if (tvEmpty != null)
                                        tvEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
                                });
                            }
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (done.incrementAndGet() == total && getActivity() != null) {
                            requireActivity().runOnUiThread(() -> {
                                all.sort((a, b) -> Long.compare(b.uploadedAt, a.uploadedAt));
                                adapter.setData(new ArrayList<>(all));
                            });
                        }
                    }
                });
        }
    }

    private void showEmpty() {
        if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
    }
}
