package com.callx.app.feed;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.reels.R;

import java.util.List;

/**
 * ReelPhotoSlideshowAdapter — drives the photo ViewPager2 inside ReelPlayerFragment.
 *
 * Each page shows a single full-screen photo loaded via Glide with a cross-fade
 * transition. Photos fill the screen using centerCrop (same as video resize=zoom).
 */
public class ReelPhotoSlideshowAdapter extends RecyclerView.Adapter<ReelPhotoSlideshowAdapter.PhotoVH> {

    private final List<String> photoUrls;

    public ReelPhotoSlideshowAdapter(List<String> photoUrls) {
        this.photoUrls = photoUrls;
    }

    @NonNull
    @Override
    public PhotoVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reel_photo_slide, parent, false);
        return new PhotoVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoVH holder, int position) {
        String url = photoUrls.get(position);
        Glide.with(holder.ivPhoto.getContext())
                .load(url)
                .transition(DrawableTransitionOptions.withCrossFade(300))
                .centerCrop()
                .placeholder(android.R.color.black)
                .into(holder.ivPhoto);
    }

    @Override
    public int getItemCount() {
        return photoUrls != null ? photoUrls.size() : 0;
    }

    static class PhotoVH extends RecyclerView.ViewHolder {
        final ImageView ivPhoto;
        PhotoVH(@NonNull View itemView) {
            super(itemView);
            ivPhoto = itemView.findViewById(R.id.iv_photo_slide);
        }
    }
}
