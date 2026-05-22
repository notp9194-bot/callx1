package com.callx.app.adapters;
  import android.content.Context; import android.view.*; import android.widget.*;
  import androidx.annotation.NonNull; import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide; import com.bumptech.glide.request.RequestOptions;
  import com.callx.app.reels.R; import de.hdodenhof.circleimageview.CircleImageView; import java.util.List;
  public class HomeStoriesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
      private static final int TYPE_ADD=0,TYPE_STORY=1;
      public static class StoryEntry{public String uid,name,photo; public boolean hasUnseen;}
      public interface OnStoryClickListener{void onAddStory(); void onStoryClick(StoryEntry e);}
      private final Context ctx; private final List<StoryEntry> items; private final OnStoryClickListener listener;
      public HomeStoriesAdapter(Context ctx,List<StoryEntry> items,OnStoryClickListener l){this.ctx=ctx;this.items=items;this.listener=l;}
      @Override public int getItemViewType(int pos){return pos==0?TYPE_ADD:TYPE_STORY;}
      @Override public int getItemCount(){return items.size()+1;}
      @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p,int vt){
          View v=LayoutInflater.from(ctx).inflate(R.layout.item_home_story,p,false);
          return vt==TYPE_ADD?new AddVH(v):new StoryVH(v);
      }
      @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int pos){
          if(holder instanceof AddVH){holder.itemView.setOnClickListener(v->listener.onAddStory());return;}
          StoryVH h=(StoryVH)holder; StoryEntry e=items.get(pos-1);
          Glide.with(ctx).load(e.photo).apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person)).into(h.ivAvatar);
          h.tvName.setText(e.name!=null?e.name:"");
          if(e.hasUnseen){h.ivAvatar.setBorderColor(ctx.getResources().getColor(R.color.brand_primary,null));h.ivAvatar.setBorderWidth(3);}
          else{h.ivAvatar.setBorderColor(0xFF555555);h.ivAvatar.setBorderWidth(2);}
          holder.itemView.setOnClickListener(v->listener.onStoryClick(e));
      }
      static class AddVH extends RecyclerView.ViewHolder{AddVH(@NonNull View v){super(v);}}
      static class StoryVH extends RecyclerView.ViewHolder{
          CircleImageView ivAvatar; TextView tvName;
          StoryVH(@NonNull View v){super(v);ivAvatar=v.findViewById(R.id.iv_story_avatar);tvName=v.findViewById(R.id.tv_story_name);}
      }
  }