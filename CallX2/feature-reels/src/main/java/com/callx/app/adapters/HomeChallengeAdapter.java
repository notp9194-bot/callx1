package com.callx.app.adapters;

  import android.content.Context;
  import android.content.Intent;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.annotation.NonNull;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.reels.R;
  import com.callx.app.activities.ReelCameraActivity;
  import com.callx.app.activities.ReelChallengeActivity;
  import java.util.List;

  /** Horizontal adapter for Featured Challenges row in HomeFragment. */
  public class HomeChallengeAdapter extends RecyclerView.Adapter<HomeChallengeAdapter.ChallengeVH> {

      public static class Challenge {
          public String id, tag, thumbUrl;
          public long   videoCount;
      }

      private final Context          ctx;
      private final List<Challenge>  items;

      public HomeChallengeAdapter(Context ctx, List<Challenge> items) {
          this.ctx   = ctx;
          this.items = items;
      }

      @NonNull @Override
      public ChallengeVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          View v = LayoutInflater.from(ctx).inflate(R.layout.item_home_challenge, parent, false);
          return new ChallengeVH(v);
      }

      @Override
      public void onBindViewHolder(@NonNull ChallengeVH h, int pos) {
          Challenge c = items.get(pos);
          Glide.with(ctx).load(c.thumbUrl).centerCrop().placeholder(android.R.color.darker_gray).into(h.ivThumb);
          h.tvTag.setText("#" + (c.tag != null ? c.tag : "Challenge"));
          h.tvCount.setText(formatCount(c.videoCount) + " videos");
          h.itemView.setOnClickListener(v -> {
              Intent i = new Intent(ctx, ReelChallengeActivity.class);
              i.putExtra("challengeId", c.id);
              i.putExtra("tag", c.tag);
              ctx.startActivity(i);
          });
          h.btnJoin.setOnClickListener(v -> {
              Intent i = new Intent(ctx, ReelCameraActivity.class);
              i.putExtra("challengeTag", c.tag);
              ctx.startActivity(i);
          });
      }

      @Override public int getItemCount() { return items.size(); }

      private String formatCount(long n) {
          if (n < 1000) return String.valueOf(n);
          if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
          return String.format("%.1fM", n / 1_000_000.0);
      }

      static class ChallengeVH extends RecyclerView.ViewHolder {
          ImageView ivThumb;
          TextView  tvTag, tvCount, btnJoin;
          ChallengeVH(@NonNull View v) {
              super(v);
              ivThumb = v.findViewById(R.id.iv_challenge_thumb);
              tvTag   = v.findViewById(R.id.tv_challenge_tag);
              tvCount = v.findViewById(R.id.tv_challenge_count);
              btnJoin = v.findViewById(R.id.btn_join_challenge);
          }
      }
  }