package com.callx.app.community;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.entity.CommunityPostEntity;

import java.util.Collections;
import java.util.List;

/**
 * v31: Grid adapter for community media gallery — 3 columns, square cells.
 */
public class CommunityMediaGalleryAdapter
        extends RecyclerView.Adapter<CommunityMediaGalleryAdapter.VH> {

    public interface Listener {
        void onMediaClicked(CommunityPostEntity post);
    }

    private static final DiffUtil.ItemCallback<CommunityPostEntity> DIFF =
            new DiffUtil.ItemCallback<CommunityPostEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return a.id.equals(b.id);
                }
                @Override
                public boolean areContentsTheSame(@NonNull CommunityPostEntity a, @NonNull CommunityPostEntity b) {
                    return a.mediaUrl != null && a.mediaUrl.equals(b.mediaUrl);
                }
            };

    private final AsyncListDiffer<CommunityPostEntity> differ = new AsyncListDiffer<>(this, DIFF);
    private final Listener listener;

    public CommunityMediaGalleryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<CommunityPostEntity> list) {
        differ.submitList(list == null ? Collections.emptyList() : list);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_community_media_grid, parent, false);
        // Make cells square
        int size = parent.getMeasuredWidth() / 3;
        ViewGroup.LayoutParams params = v.getLayoutParams();
        params.height = size;
        v.setLayoutParams(params);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        CommunityPostEntity post = differ.getCurrentList().get(pos);

        Glide.with(h.ivThumbnail.getContext())
                .load(post.mediaUrl)
                .centerCrop()
                .placeholder(R.drawable.ic_photo_library)
                .into(h.ivThumbnail);

        boolean isVideo = "video".equals(post.mediaType);
        h.ivPlayIcon.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        h.viewVideoOverlay.setVisibility(isVideo ? View.VISIBLE : View.GONE);

        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onMediaClicked(post); });
    }

    @Override
    public void onViewRecycled(@NonNull VH h) {
        super.onViewRecycled(h);
        try { Glide.with(h.ivThumbnail.getContext()).clear(h.ivThumbnail); } catch (Exception ignored) {}
    }

    @Override
    public int getItemCount() { return differ.getCurrentList().size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivThumbnail, ivPlayIcon;
        View viewVideoOverlay;

        VH(@NonNull View itemView) {
            super(itemView);
            ivThumbnail      = itemView.findViewById(R.id.iv_thumbnail);
            ivPlayIcon       = itemView.findViewById(R.id.iv_play_icon);
            viewVideoOverlay = itemView.findViewById(R.id.view_video_overlay);
        }
    }
}
