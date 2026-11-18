package com.callx.app.adapters;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import com.callx.app.history.CallsFragment;
import com.callx.app.chatlist.ChatsFragment;
import com.callx.app.feed.ReelsFragment;
import com.callx.app.feed.StatusFragment;

public class ViewPagerAdapter extends FragmentStateAdapter {
    public ViewPagerAdapter(FragmentActivity fa) { super(fa); }
    @NonNull @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1: return new ReelsFragment();
            case 2: return new StatusFragment();
            case 3: return new CallsFragment();
            default: return new ChatsFragment();
        }
    }
    @Override public int getItemCount() { return 4; }

    // Groups moved into the Chats list (tab removed). Calls keeps its old stable
    // fragment id (4) so any fragment state saved by the previous 5-tab build can
    // never be re-attached to the wrong tab; the old id 3 (Groups) is simply
    // not contained anymore and gets dropped by FragmentStateAdapter.
    @Override public long getItemId(int position) { return position == 3 ? 4L : position; }
    @Override public boolean containsItem(long itemId) {
        return itemId == 0L || itemId == 1L || itemId == 2L || itemId == 4L;
    }
}
