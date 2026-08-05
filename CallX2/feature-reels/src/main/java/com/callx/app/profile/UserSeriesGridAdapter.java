package com.callx.app.profile;

  import android.content.Context;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;

  import com.bumptech.glide.Glide;
  import com.bumptech.glide.RequestManager;
  import com.bumptech.glide.load.DecodeFormat;
  import com.bumptech.glide.load.engine.DiskCacheStrategy;
  import com.bumptech.glide.request.RequestOptions;
  import com.callx.app.models.DuetSeriesModel;
  import com.callx.app.reels.R;

  import java.util.ArrayList;
  import java.util.List;

  /**
   * UserSeriesGridAdapter — 2-column grid of Duet Series cards on a creator's profile.
   *
   * Each card shows:
   *   • Cover thumbnail (first/latest episode thumb)
   *   • Series title
   *   • "N episodes" count
   *   • "N subscribers" count badge
   *   • "NEW" badge if episodeCount was recently updated (within 48 h)
   */
  public class UserSeriesGridAdapter
          extends RecyclerView.Adapter<UserSeriesGridAdapter.SeriesCardVH> {

      public interface OnSeriesClickListener {
          void onSeriesClick(DuetSeriesModel series);
      }

      // ULTRA (parity with ReelGridAdapter): one shared RequestManager +
      // RequestOptions built once instead of Glide.with(ctx) fresh per bind.
      // RGB_565 halves per-bitmap memory for these opaque centerCrop covers,
      // dontAnimate() skips the default crossfade drawable per bind.
      private static final RequestOptions COVER_OPTIONS = new RequestOptions()
              .diskCacheStrategy(DiskCacheStrategy.ALL)
              .format(DecodeFormat.PREFER_RGB_565)
              .centerCrop()
              .dontAnimate();

      private final Context ctx;
      private final RequestManager glideRequests;
      private final List<DuetSeriesModel> items = new ArrayList<>();
      private OnSeriesClickListener listener;

      public UserSeriesGridAdapter(Context ctx) {
          this.ctx = ctx;
          this.glideRequests = Glide.with(ctx);
      }

      public void setOnSeriesClickListener(OnSeriesClickListener l) { this.listener = l; }

      public void setItems(List<DuetSeriesModel> list) {
          items.clear();
          items.addAll(list);
          notifyDataSetChanged();
      }

      public void addItems(List<DuetSeriesModel> list) {
          int start = items.size();
          items.addAll(list);
          notifyItemRangeInserted(start, list.size());
      }

      public boolean isEmpty() { return items.isEmpty(); }

      // ULTRA: this adapter shares a RecycledViewPool with ReelGridAdapter
      // (rv_reels / rv_series only ever show one at a time — see
      // UserReelsActivity.gridSharedViewPool). RecycledViewPool keys purely
      // by the int viewType, so this MUST never collide with
      // ReelGridAdapter.TYPE_SKELETON/TYPE_REEL/TYPE_PINNED (0/1/2) or the
      // pool would hand back a SkeletonVH/ReelVH/PinnedVH here and crash on
      // cast. Offset well clear of that range.
      public static final int TYPE_SERIES_CARD = 100;

      @Override
      public int getItemViewType(int position) {
          return TYPE_SERIES_CARD;
      }

      @NonNull
      @Override
      public SeriesCardVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          View v = LayoutInflater.from(ctx).inflate(R.layout.item_duet_series_card, parent, false);
          return new SeriesCardVH(v);
      }

      @Override
      public void onBindViewHolder(@NonNull SeriesCardVH h, int pos) {
          DuetSeriesModel s = items.get(pos);

          h.tvTitle.setText(s.title != null ? s.title : "");
          h.tvEpisodes.setText(s.episodeCount + " eps");
          h.tvSubscribers.setText(formatCount(s.subscriberCount) + " subs");

          // "NEW" badge: series created within last 72 hours
          long ageMs = System.currentTimeMillis() - s.createdAt;
          h.tvNewBadge.setVisibility(ageMs < 72 * 3600_000L ? View.VISIBLE : View.GONE);

          if (s.coverThumbUrl != null && !s.coverThumbUrl.isEmpty()) {
              glideRequests
                   .load(s.coverThumbUrl)
                   .apply(COVER_OPTIONS)
                   .placeholder(R.color.brand_primary)
                   .override(480, 853)
                   .into(h.ivCover);
          } else {
              h.ivCover.setImageResource(R.color.brand_primary);
          }

          h.itemView.setOnClickListener(v -> {
              if (listener != null) listener.onSeriesClick(s);
          });
      }

      @Override
      public int getItemCount() { return items.size(); }

      static class SeriesCardVH extends RecyclerView.ViewHolder {
          ImageView ivCover;
          TextView  tvTitle, tvEpisodes, tvSubscribers, tvNewBadge;

          SeriesCardVH(View v) {
              super(v);
              ivCover        = v.findViewById(R.id.iv_series_card_cover);
              tvTitle        = v.findViewById(R.id.tv_series_card_title);
              tvEpisodes     = v.findViewById(R.id.tv_series_card_episodes);
              tvSubscribers  = v.findViewById(R.id.tv_series_card_subscribers);
              tvNewBadge     = v.findViewById(R.id.tv_series_card_new_badge);
          }
      }

      private String formatCount(int n) {
          if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000f);
          if (n >= 1_000) return String.format("%.0fK", n / 1_000f);
          return String.valueOf(n);
      }
  }
  