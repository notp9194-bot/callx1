package com.callx.app.feed;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecyclerView adapter for the Home feed.
 *
 * Post rows are real ViewHolders. Secondary, dynamically-built Home rows
 * (suggestions, banners, and the pagination spinner) are hosted as view rows
 * so the existing Home section builders can keep their UI without bringing
 * back a permanently-growing LinearLayout.
 */
public final class HomeFeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Callback {
        void onBindPost(@NonNull View card, int feedIndex, @NonNull ReelModel reel);
        void onPostRecycled(@NonNull View card);
    }

    private static final int TYPE_POST = 1;
    private static final int TYPE_VIEW = 2;

    private static final class Item {
        final int type;
        final int feedIndex;
        final ReelModel reel;
        final View view;
        final long identity;

        static Item post(int index, ReelModel reel) {
            String id = reel != null && reel.reelId != null ? reel.reelId : String.valueOf(index);
            return new Item(TYPE_POST, index, reel, null,
                    31L * index + id.hashCode());
        }

        static Item view(View view) {
            return new Item(TYPE_VIEW, -1, null, view,
                    0x400000000L + System.identityHashCode(view));
        }

        private Item(int type, int feedIndex, ReelModel reel, View view, long identity) {
            this.type = type;
            this.feedIndex = feedIndex;
            this.reel = reel;
            this.view = view;
            this.identity = identity;
        }
    }

    private static final class PostHolder extends RecyclerView.ViewHolder {
        PostHolder(@NonNull View itemView) { super(itemView); }
    }

    private static final class ViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout host;
        ViewHolder(@NonNull FrameLayout host) {
            super(host);
            this.host = host;
        }
    }

    private final Callback callback;
    private final List<Item> items = new ArrayList<>();

    public HomeFeedAdapter(@NonNull HomeFragment fragment, @NonNull Callback callback) {
        this.callback = callback;
        setHasStableIds(true);
    }

    public void setPosts(@NonNull List<ReelModel> posts) {
        List<Item> next = new ArrayList<>(posts.size());
        for (int i = 0; i < posts.size(); i++) {
            ReelModel reel = posts.get(i);
            if (reel != null && reel.reelId != null) next.add(Item.post(i, reel));
        }
        replace(next);
    }

    public void appendPosts(@NonNull List<ReelModel> posts, int feedIndexStart) {
        if (posts.isEmpty()) return;
        List<Item> next = new ArrayList<>(items);
        int index = feedIndexStart;
        for (ReelModel reel : posts) {
            if (reel != null && reel.reelId != null) next.add(Item.post(index++, reel));
        }
        replace(next);
    }

    /** Adds a non-post Home row after the current post window. */
    public void addViewItem(@NonNull View view) {
        List<Item> next = new ArrayList<>(items);
        next.add(Item.view(view));
        replace(next);
    }

    public void removeViewItem(@NonNull View view) {
        List<Item> next = new ArrayList<>(items);
        for (int i = next.size() - 1; i >= 0; i--) {
            if (next.get(i).view == view) next.remove(i);
        }
        replace(next);
    }

    public void clear() {
        replace(Collections.emptyList());
    }

    public int getPostCount() {
        int count = 0;
        for (Item item : items) if (item.type == TYPE_POST) count++;
        return count;
    }

    private void replace(List<Item> next) {
        List<Item> old = new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return old.get(oldPos).identity == next.get(newPos).identity;
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                Item a = old.get(oldPos);
                Item b = next.get(newPos);
                return a.type == b.type && a.reel == b.reel && a.view == b.view
                        && a.feedIndex == b.feedIndex;
            }
        });
        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    @Override public long getItemId(int position) {
        return items.get(position).identity;
    }

    @Override public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_POST) {
            View card = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_home_feed_post, parent, false);
            return new PostHolder(card);
        }
        FrameLayout host = new FrameLayout(parent.getContext());
        host.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new ViewHolder(host);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item item = items.get(position);
        if (item.type == TYPE_POST) {
            callback.onBindPost(holder.itemView, item.feedIndex, item.reel);
            return;
        }
        ViewHolder viewHolder = (ViewHolder) holder;
        viewHolder.host.removeAllViews();
        if (item.view.getParent() instanceof ViewGroup) {
            ((ViewGroup) item.view.getParent()).removeView(item.view);
        }
        viewHolder.host.addView(item.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof PostHolder) callback.onPostRecycled(holder.itemView);
        if (holder instanceof ViewHolder) ((ViewHolder) holder).host.removeAllViews();
        super.onViewRecycled(holder);
    }

    @Override public int getItemCount() { return items.size(); }
}