package com.callx.app.channels;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.status.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.List;

/** "Explore channels" — full catalogue of channels with a Follow/Following toggle per
 *  row, opened from the "Explore" button in the Channels section header. */
public class ChannelsExploreBottomSheet extends BottomSheetDialogFragment {

    public interface Listener { void onChannelsChanged(); }

    private ChannelsRepository repo;
    private Listener listener;

    public static ChannelsExploreBottomSheet newInstance(ChannelsRepository repo, Listener listener) {
        ChannelsExploreBottomSheet sheet = new ChannelsExploreBottomSheet();
        sheet.repo = repo;
        sheet.listener = listener;
        return sheet;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_channels_explore, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (repo == null) { dismiss(); return; }

        RecyclerView rv = view.findViewById(R.id.rv_explore_channels);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));

        List<ChannelItem> all = repo.getAll();
        ChannelsExploreAdapter adapter = new ChannelsExploreAdapter(all, channel -> {
            repo.setFollowing(channel.id, !channel.following);
            rv.getAdapter().notifyDataSetChanged();
            if (listener != null) listener.onChannelsChanged();
        });
        rv.setAdapter(adapter);
    }
}
