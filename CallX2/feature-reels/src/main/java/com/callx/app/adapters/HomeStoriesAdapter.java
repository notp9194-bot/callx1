package com.callx.app.adapters;

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
  import com.callx.app.reels.R;
  import de.hdodenhof.circleimageview.CircleImageView;
  import java.util.List;

  /** Horizontal RecyclerView adapter for Stories bar. */
  public class HomeStoriesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

      private static final int TYPE_ADD_STORY = 0;
      private static final int TYPE_STORY     = 1;

      public static class StoryEntry {
          public String  uid, name, photo;
          public boolean hasUnseen;
          public boolean hasReelStory;
      }

      public interface OnStoryClickListener {
          void onAddStory();
          void onStoryClick(StoryEntry entry);
      }

      private final Context               ctx;
      private final List<StoryEntry>      items;
      private final OnStoryClickListener  listener;

      public HomeStoriesAdapter(Context ctx, List<StoryEntry> items, OnStoryClickListener listener) {
          this.ctx      = ctx;
          this.items    = items;
          this.listener = listener;
      }

      @Override public int getItemViewType(int pos) { return pos == 0 ? TYPE_ADD_STORY : TYPE_STORY; }
      @Override public int getItemCount() { return items.size() + 1; } // +1 for Add Story slot

      @NonNull @Override
      public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          LayoutInflater inf = LayoutInflater.from(ctx);
          if (viewType == TYPE_ADD_STORY) {
              View v = inf.inflate(R.layout.item_home_story, parent, false);
              return new AddStoryVH(v);
          }
          View v = inf.inflate(R.layout.item_home_story, parent, false);
          return new StoryVH(v);
      }

      @Override
      public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
          if (holder instanceof AddStoryVH) {
              ((AddStoryVH) holder).itemView.setOnClickListener(v -> listener.onAddStory());
              return;
          }
          StoryVH h = (StoryVH) holder;
          StoryEntry entry = items.get(pos - 1);

          Glide.with(ctx).load(entry.photo)
              .apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person))
              .into(h.ivAvatar);
          h.tvName.setText(entry.name != null ? entry.name : "");

          // Ring color: brand_primary = unseen, gray = seen
          if (entry.hasUnseen) {
              h.ivAvatar.setBorderColor(ctx.getResources().getColor(R.color.brand_primary, null));
              h.ivAvatar.setBorderWidth(3);
          } else {
              h.ivAvatar.setBorderColor(0xFF555555);
              h.ivAvatar.setBorderWidth(2);
          }

          h.itemView.setOnClickListener(v -> listener.onStoryClick(entry));
      }

      static class AddStoryVH extends RecyclerView.ViewHolder {
          AddStoryVH(@NonNull View v) { super(v); }
      }

      static class StoryVH extends RecyclerView.ViewHolder {
          CircleImageView ivAvatar;
          TextView        tvName;
          StoryVH(@NonNull View v) {
              super(v);
              ivAvatar = v.findViewById(R.id.iv_story_avatar);
              tvName   = v.findViewById(R.id.tv_story_name);
          }
      }
  }