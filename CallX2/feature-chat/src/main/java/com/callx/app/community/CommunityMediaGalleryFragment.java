package com.callx.app.community;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityPostEntity;
import com.callx.app.repository.CommunityRepository;

import java.util.List;

/**
 * Media gallery tab inside CommunityActivity's ViewPager. Was a
 * "coming soon" stub; now reuses the same Instagram-style 3-column grid
 * (CommunityMediaGalleryAdapter) and data source (observeMediaPosts) that
 * the standalone CommunityMediaGalleryActivity already used — this tab is
 * just an embedded, toolbar-less version of that screen.
 */
public class CommunityMediaGalleryFragment extends Fragment
        implements CommunityMediaGalleryAdapter.Listener {

    private static final String ARG_COMMUNITY_ID = "communityId";

    private String communityId;
    private RecyclerView rvGallery;
    private View layoutEmpty;
    private CommunityMediaGalleryAdapter adapter;
    private CommunityRepository repo;

    public static CommunityMediaGalleryFragment newInstance(String communityId) {
        CommunityMediaGalleryFragment f = new CommunityMediaGalleryFragment();
        Bundle b = new Bundle();
        b.putString(ARG_COMMUNITY_ID, communityId);
        f.setArguments(b);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        communityId = getArguments() != null ? getArguments().getString(ARG_COMMUNITY_ID) : null;
        repo = CommunityRepository.getInstance(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_community_media_gallery, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvGallery   = view.findViewById(R.id.rv_gallery_tab);
        layoutEmpty = view.findViewById(R.id.layout_empty_gallery_tab);

        GridLayoutManager glm = new GridLayoutManager(requireContext(), 3);
        rvGallery.setLayoutManager(glm);
        rvGallery.setHasFixedSize(true);
        rvGallery.setItemAnimator(null);

        adapter = new CommunityMediaGalleryAdapter(this);
        rvGallery.setAdapter(adapter);

        if (communityId != null) {
            repo.observeMediaPosts(communityId).observe(getViewLifecycleOwner(), this::onMediaLoaded);
        }
    }

    private void onMediaLoaded(List<CommunityPostEntity> posts) {
        if (!isAdded()) return;
        adapter.submitList(posts);
        boolean empty = posts == null || posts.isEmpty();
        rvGallery.setVisibility(empty ? View.GONE : View.VISIBLE);
        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onMediaClicked(CommunityPostEntity post) {
        Intent i = new Intent(requireContext(), CommunityFullscreenMediaActivity.class);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_MEDIA_URL, post.mediaUrl);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_MEDIA_TYPE, post.mediaType);
        i.putExtra(CommunityFullscreenMediaActivity.EXTRA_AUTHOR_NAME, post.authorName);
        startActivity(i);
    }
}
