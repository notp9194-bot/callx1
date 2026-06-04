package com.callx.app.youtube.library;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.callx.app.youtube.library.YouTubeDownloadsActivity;
import com.callx.app.youtube.library.YouTubeHistoryActivity;
import com.callx.app.youtube.library.YouTubeLikedVideosActivity;
import com.callx.app.youtube.library.YouTubeWatchLaterActivity;
import com.callx.app.youtube.library.R;

/** Library tab: Watch Later, History, Liked Videos shortcuts. */
public class YouTubeLibraryFragment extends Fragment {

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_library, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        view.findViewById(R.id.btn_yt_downloads).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeDownloadsActivity.class)));

        view.findViewById(R.id.btn_yt_watch_later).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeWatchLaterActivity.class)));

        view.findViewById(R.id.btn_yt_history).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeHistoryActivity.class)));

        view.findViewById(R.id.btn_yt_liked).setOnClickListener(v ->
            startActivity(new Intent(requireContext(), YouTubeLikedVideosActivity.class)));
    }
}
