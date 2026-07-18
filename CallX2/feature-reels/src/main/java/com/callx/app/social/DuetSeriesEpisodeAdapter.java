package com.callx.app.social;

  import android.content.Context;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;

  import com.bumptech.glide.Glide;
  import com.callx.app.models.ReelModel;
  import com.callx.app.reels.R;

  import java.util.ArrayList;
  import java.util.List;

  /**
   * DuetSeriesEpisodeAdapter — Vertical list of episodes in a Duet Series.
   * Each row: episode thumbnail, "Part N" label, caption, duration.
   */
  public class DuetSeriesEpisodeAdapter
          extends RecyclerView.Adapter<DuetSeriesEpisodeAdapter.EpisodeViewHolder> {

      public interface OnEpisodeClickListener {
          void onEpisodeClick(ReelModel reel, int position);
      }

      private final Context ctx;
      private final List<ReelModel> episodes = new ArrayList<>();
      private OnEpisodeClickListener listener;

      public DuetSeriesEpisodeAdapter(Context ctx) {
          this.ctx = ctx;
      }

      public void setOnEpisodeClickListener(OnEpisodeClickListener l) {
          this.listener = l;
      }

      public void setEpisodes(List<ReelModel> list) {
          episodes.clear();
          episodes.addAll(list);
          notifyDataSetChanged();
      }

      public void addEpisode(ReelModel reel) {
          episodes.add(reel);
          notifyItemInserted(episodes.size() - 1);
      }

      @NonNull
      @Override
      public EpisodeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          View v = LayoutInflater.from(ctx).inflate(R.layout.item_duet_series_episode, parent, false);
          return new EpisodeViewHolder(v);
      }

      @Override
      public void onBindViewHolder(@NonNull EpisodeViewHolder holder, int position) {
          ReelModel reel = episodes.get(position);
          holder.tvEpisodeLabel.setText("Part " + reel.seriesEpisodeNumber);
          holder.tvCaption.setText(reel.caption != null ? reel.caption : "");
          holder.tvViews.setText(formatCount(reel.viewsCount) + " views");
          holder.tvDuration.setText(formatDuration(reel.duration));

          Glide.with(ctx)
               .load(reel.thumbUrl)
               .placeholder(R.color.brand_primary)
               .centerCrop()
               .override(720, 720)
               .into(holder.ivThumb);

          holder.itemView.setOnClickListener(v -> {
              if (listener != null) listener.onEpisodeClick(reel, position);
          });
      }

      @Override
      public int getItemCount() { return episodes.size(); }

      static class EpisodeViewHolder extends RecyclerView.ViewHolder {
          ImageView ivThumb;
          TextView tvEpisodeLabel, tvCaption, tvViews, tvDuration;

          EpisodeViewHolder(View v) {
              super(v);
              ivThumb        = v.findViewById(R.id.iv_episode_thumb);
              tvEpisodeLabel = v.findViewById(R.id.tv_episode_label);
              tvCaption      = v.findViewById(R.id.tv_episode_caption);
              tvViews        = v.findViewById(R.id.tv_episode_views);
              tvDuration     = v.findViewById(R.id.tv_episode_duration);
          }
      }

      private String formatCount(int n) {
          if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000f);
          if (n >= 1_000) return String.format("%.1fK", n / 1_000f);
          return String.valueOf(n);
      }

      private String formatDuration(int seconds) {
          if (seconds <= 0) return "";
          return seconds / 60 + ":" + String.format("%02d", seconds % 60);
      }
  }
  