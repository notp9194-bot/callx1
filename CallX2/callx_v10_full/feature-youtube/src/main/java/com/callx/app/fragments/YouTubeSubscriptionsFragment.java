package com.callx.app.fragments;

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
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shows videos from subscribed channels, sorted by recency. */
public class YouTubeSubscriptionsFragment extends Fragment {

    private RecyclerView       rvSubs;
    private YouTubeVideoAdapter adapter;
    private TextView           tvEmpty;
    private String             myUid;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_subscriptions, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        myUid    = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        rvSubs   = view.findViewById(R.id.rv_yt_subs_feed);
        tvEmpty  = view.findViewById(R.id.tv_yt_subs_empty);

        adapter = new YouTubeVideoAdapter(requireContext(), new ArrayList<>(), video ->
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
        List<YouTubeVideo> all = new ArrayList<>();
        final int[] done = {0};
        for (String uid : uids) {
            YouTubeFirebaseUtils.userVideosRef(uid)
                .orderByValue().limitToLast(5)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        List<String> ids = new ArrayList<>();
                        for (DataSnapshot ds : snap.getChildren()) ids.add(ds.getKey());
                        for (String id : ids) {
                            YouTubeFirebaseUtils.videoRef(id)
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(@NonNull DataSnapshot ds2) {
                                        YouTubeVideo v = ds2.getValue(YouTubeVideo.class);
                                        if (v != null) all.add(v);
                                    }
                                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                                });
                        }
                        done[0]++;
                        if (done[0] == uids.size()) {
                            all.sort((a, b) -> Long.compare(b.uploadedAt, a.uploadedAt));
                            adapter.setData(all);
                            if (tvEmpty != null)
                                tvEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        done[0]++;
                        if (done[0] == uids.size()) {
                            all.sort((a, b) -> Long.compare(b.uploadedAt, a.uploadedAt));
                            adapter.setData(all);
                        }
                    }
                });
        }
    }

    private void showEmpty() {
        if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
    }
}
