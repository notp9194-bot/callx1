package com.callx.app.community;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Stub media gallery tab — shows community photos and videos.
 */
public class CommunityMediaGalleryFragment extends Fragment {

    private static final String ARG_COMMUNITY_ID = "communityId";

    public static CommunityMediaGalleryFragment newInstance(String communityId) {
        CommunityMediaGalleryFragment f = new CommunityMediaGalleryFragment();
        Bundle b = new Bundle();
        b.putString(ARG_COMMUNITY_ID, communityId);
        f.setArguments(b);
        return f;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        android.widget.FrameLayout fl = new android.widget.FrameLayout(requireContext());
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        tv.setText("Media gallery coming soon");
        tv.setGravity(android.view.Gravity.CENTER);
        fl.addView(tv, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        return fl;
    }
}
