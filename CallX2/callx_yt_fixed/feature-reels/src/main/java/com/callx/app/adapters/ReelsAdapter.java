package com.callx.app.adapters;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.callx.app.fragments.ReelPlayerFragment;
import com.callx.app.models.ReelModel;

import java.util.ArrayList;
import java.util.List;

public class ReelsAdapter extends FragmentStateAdapter {

    private final List<ReelModel> reels = new ArrayList<>();

    public ReelsAdapter(FragmentActivity fa) {
        super(fa);
    }

    public ReelsAdapter(Fragment fragment) {
        super(fragment);
    }

    public void setReels(List<ReelModel> newReels) {
        reels.clear();
        reels.addAll(newReels);
        notifyDataSetChanged();
    }

    public void addReels(List<ReelModel> more) {
        int start = reels.size();
        reels.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    public void prependReel(ReelModel reel) {
        reels.add(0, reel);
        notifyItemInserted(0);
    }

    public ReelModel get(int position) {
        return reels.get(position);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return ReelPlayerFragment.newInstance(reels.get(position));
    }

    @Override
    public int getItemCount() {
        return reels.size();
    }

    @Override
    public long getItemId(int position) {
        String id = reels.get(position).reelId;
        return id != null ? id.hashCode() : position;
    }

    @Override
    public boolean containsItem(long itemId) {
        for (ReelModel r : reels) {
            if (r.reelId != null && r.reelId.hashCode() == itemId) return true;
        }
        return false;
    }
}
