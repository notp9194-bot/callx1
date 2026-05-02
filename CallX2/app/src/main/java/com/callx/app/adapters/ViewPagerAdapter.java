package com.callx.app.adapters;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import com.callx.app.fragments.CallsFragment;
import com.callx.app.fragments.ChatsFragment;
import com.callx.app.fragments.GroupsFragment;
import com.callx.app.fragments.StatusFragment;
public class ViewPagerAdapter extends FragmentStateAdapter {
    public ViewPagerAdapter(FragmentActivity fa) { super(fa); }
    @NonNull @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1: return new StatusFragment();
            case 2: return new GroupsFragment();
            case 3: return new CallsFragment();
            default: return new ChatsFragment();
        }
    }
    @Override public int getItemCount() { return 4; }
}
