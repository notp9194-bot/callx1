package com.callx.app.adapters;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import com.callx.app.history.CallsFragment;
import com.callx.app.chatlist.ChatsFragment;
import com.callx.app.group.GroupsFragment;
import com.callx.app.feed.ReelsFragment;
import com.callx.app.feed.StatusFragment;
import com.callx.app.search.SearchFragment;

public class ViewPagerAdapter extends FragmentStateAdapter {
    public ViewPagerAdapter(FragmentActivity fa) { super(fa); }
    @NonNull @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1: return new ReelsFragment();
            case 2: return new SearchFragment();
            case 3: return new StatusFragment();
            case 4: return new GroupsFragment();
            case 5: return new CallsFragment();
            default: return new ChatsFragment();
        }
    }
    @Override public int getItemCount() { return 6; }
}
