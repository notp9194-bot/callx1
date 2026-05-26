package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.activities.YouTubeCommentsActivity;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Vertical snap-scroll Shorts feed with inline Like / Comment / Share buttons.
 */
public class YouTubeShortsFragment extends Fragment {

    private RecyclerView       rvShorts;
    private YouTubeVideoAdapter adapter;
    private ValueEventListener shortsListener;
    private String myUid = "";

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_shorts, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        if (FirebaseAuth.getInstance().getCurrentUser() != null)
            myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        rvShorts = view.findViewById(R.id.rv_yt_shorts);

        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvShorts.setLayoutManager(llm);
        new PagerSnapHelper().attachToRecyclerView(rvShorts);

        adapter = new YouTubeVideoAdapter(requireActivity(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));

        adapter.setShortsCallbacks(new YouTubeVideoAdapter.ShortsCallbacks() {
            @Override public void onLike(YouTubeVideo video, ImageButton btn) {
                toggleShortLike(video, btn);
            }
            @Override public void onComment(YouTubeVideo video) {
                startActivity(new Intent(requireContext(), YouTubeCommentsActivity.class)
                    .putExtra("video_id", video.videoId));
            }
            @Override public void onShare(YouTubeVideo video) {
                String msg = (video.title != null ? video.title : "") +
                    "\nhttps://callx.app/short?v=" + video.videoId;
                Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, msg);
                startActivity(Intent.createChooser(share, "Share Short"));
                YouTubeFirebaseUtils.videoRef(video.videoId).child("shareCount")
                    .setValue(ServerValue.increment(1));
            }
        });

        rvShorts.setAdapter(adapter);
        loadShorts();
    }

    private void toggleShortLike(YouTubeVideo video, ImageButton btn) {
        if (myUid.isEmpty()) { Toast.makeText(requireContext(),"Sign in",Toast.LENGTH_SHORT).show(); return; }
        DatabaseReference likeRef = YouTubeFirebaseUtils.videoLikesRef(video.videoId).child(myUid);
        likeRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (snap.exists()) {
                    likeRef.removeValue();
                    YouTubeFirebaseUtils.videoRef(video.videoId).child("likeCount")
                        .setValue(ServerValue.increment(-1));
                    if (btn != null) btn.setImageResource(R.drawable.ic_yt_like);
                } else {
                    likeRef.setValue(true);
                    YouTubeFirebaseUtils.likedVideosRef(myUid).child(video.videoId)
                        .setValue(System.currentTimeMillis());
                    YouTubeFirebaseUtils.videoRef(video.videoId).child("likeCount")
                        .setValue(ServerValue.increment(1));
                    if (btn != null) btn.setImageResource(R.drawable.ic_yt_like_filled);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void loadShorts() {
        shortsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v != null && v.isShort && "public".equals(v.visibility)
                            && v.videoUrl != null && !v.videoUrl.trim().isEmpty())
                        list.add(0, v);
                }
                adapter.setData(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(50)
            .addValueEventListener(shortsListener);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (shortsListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(shortsListener);
    }
}
