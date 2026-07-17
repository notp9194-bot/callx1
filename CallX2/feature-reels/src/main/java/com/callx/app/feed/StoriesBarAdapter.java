package com.callx.app.feed;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.models.FeedStory;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.List;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * StoriesBarAdapter — powers the horizontal RecyclerView of story circles
 * at the very top of the Home feed.
 *
 * Item layout: item_feed_story_thumb.xml
 * Story click → StoryViewActivity (via Class.forName for cross-module safety)
 */
public class StoriesBarAdapter extends RecyclerView.Adapter<StoriesBarAdapter.StoryVH> {

    private final List<FeedStory> stories = new ArrayList<>();
    private final String myUid;
    private OnAddStoryClickListener addStoryListener;

    public interface OnAddStoryClickListener {
        void onAddStoryClicked();
    }

    public StoriesBarAdapter(String myUid) {
        this.myUid = myUid;
    }

    public void setAddStoryListener(OnAddStoryClickListener l) {
        this.addStoryListener = l;
    }

    public void setStories(List<FeedStory> list) {
        stories.clear();
        stories.addAll(list);
        notifyDataSetChanged();
    }

    public void prependMyStory(FeedStory my) {
        // Remove existing my story if present
        for (int i = 0; i < stories.size(); i++) {
            if (stories.get(i).isMyStory) { stories.remove(i); break; }
        }
        stories.add(0, my);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public StoryVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_feed_story_thumb, parent, false);
        return new StoryVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull StoryVH h, int position) {
        FeedStory story = stories.get(position);
        h.bind(story);
    }

    @Override
    public int getItemCount() { return stories.size(); }

    // ── ViewHolder ─────────────────────────────────────────────────────────

    class StoryVH extends RecyclerView.ViewHolder {

        CircleImageView ivAvatar;
        View            vRingActive, vRingSeen, vRingMyStory;
        TextView        tvName;
        View            ivAddBadge;

        StoryVH(@NonNull View itemView) {
            super(itemView);
            ivAvatar     = itemView.findViewById(R.id.iv_story_avatar);
            vRingActive  = itemView.findViewById(R.id.v_ring_active);
            vRingSeen    = itemView.findViewById(R.id.v_ring_seen);
            vRingMyStory = itemView.findViewById(R.id.v_ring_my_story);
            tvName       = itemView.findViewById(R.id.tv_story_name);
            ivAddBadge   = itemView.findViewById(R.id.iv_story_add_badge);
        }

        void bind(FeedStory story) {
            Context ctx = itemView.getContext();

            // Name
            String label = story.isMyStory ? "Your Story"
                         : (story.ownerName != null && !story.ownerName.isEmpty()
                                ? story.ownerName : "User");
            tvName.setText(label);

            // Avatar
            if (story.ownerPhotoUrl != null && !story.ownerPhotoUrl.isEmpty()) {
                Glide.with(ctx)
                     .load(story.ownerPhotoUrl)
                     .apply(RequestOptions.circleCropTransform())
                     .placeholder(R.drawable.ic_person)
                     .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_person);
            }

            // Ring visibility
            if (vRingMyStory != null) vRingMyStory.setVisibility(story.isMyStory ? View.VISIBLE : View.GONE);
            if (vRingActive  != null) vRingActive .setVisibility(!story.isMyStory && story.hasUnseen  ? View.VISIBLE : View.GONE);
            if (vRingSeen    != null) vRingSeen   .setVisibility(!story.isMyStory && !story.hasUnseen ? View.VISIBLE : View.GONE);

            // "+" add badge for my story
            if (ivAddBadge != null) ivAddBadge.setVisibility(story.isMyStory ? View.VISIBLE : View.GONE);

            // Click
            itemView.setOnClickListener(v -> {
                if (story.isMyStory) {
                    if (addStoryListener != null) addStoryListener.onAddStoryClicked();
                    return;
                }
                // Open story viewer
                try {
                    Intent intent = new Intent(ctx, Class.forName("com.callx.app.compose.StatusViewerActivity"));
                    intent.putExtra("ownerUid", story.ownerUid);
                    ctx.startActivity(intent);
                } catch (ClassNotFoundException e) {
                    // Fallback: open StoryViewActivity in this module
                    try {
                        Intent intent = new Intent(ctx, StoryViewActivity.class);
                        intent.putExtra("ownerUid", story.ownerUid);
                        intent.putExtra("ownerName", story.ownerName);
                        intent.putExtra("ownerPhoto", story.ownerPhotoUrl);
                        ctx.startActivity(intent);
                    } catch (Exception ignored) {}
                }
            });
        }
    }
}
