package com.callx.app.adapters;

import android.content.Context;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

/**
 * SeenByAdapter — Shows who has seen a status, with their reaction emoji if any.
 *
 * Data:
 *   seenByMap:   Map<uid, timestamp>    — ordered by newest first
 *   reactionsMap: Map<uid, emoji>       — which emoji each viewer reacted with
 *
 * For each viewer: loads their name + avatar from Firebase users/{uid}
 * Shows: avatar | name | time since seen | reaction emoji (if any)
 */
public class SeenByAdapter extends RecyclerView.Adapter<SeenByAdapter.ViewHolder> {

    private final Context              ctx;
    private List<String>               uids      = new ArrayList<>();
    private Map<String, Long>          seenTimes = new LinkedHashMap<>();
    private Map<String, String>        reactions = new HashMap<>();
    /** uid → resolved display name */
    private final Map<String, String>  nameCache = new HashMap<>();
    /** uid → resolved photo URL */
    private final Map<String, String>  photoCache = new HashMap<>();

    public SeenByAdapter(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * Update data. Triggers reload of user profiles for any new UIDs.
     */
    public void setData(Map<String, Long> seenByMap, Map<String, String> reactionsMap) {
        this.seenTimes = seenByMap;
        this.reactions = reactionsMap != null ? reactionsMap : new HashMap<>();
        this.uids      = new ArrayList<>(seenByMap.keySet());
        // Load profiles for any UIDs not yet cached
        for (String uid : uids) {
            if (!nameCache.containsKey(uid)) loadUserProfile(uid);
        }
        notifyDataSetChanged();
    }

    private void loadUserProfile(String uid) {
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String name  = snap.child("name").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    nameCache.put(uid,  name  != null ? name  : "User");
                    photoCache.put(uid, photo != null ? photo : "");
                    int pos = uids.indexOf(uid);
                    if (pos >= 0) notifyItemChanged(pos);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Build row programmatically
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(16, 12, 16, 12);
        row.setLayoutParams(new RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT));

        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
            new de.hdodenhof.circleimageview.CircleImageView(ctx);
        ivAvatar.setId(android.R.id.icon);
        int sz = (int)(40 * ctx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(sz, sz);
        ivAvatar.setLayoutParams(avLp);
        row.addView(ivAvatar);

        LinearLayout textGroup = new LinearLayout(ctx);
        textGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tgLp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tgLp.leftMargin = 14;
        textGroup.setLayoutParams(tgLp);

        TextView tvName = new TextView(ctx);
        tvName.setId(android.R.id.text1);
        tvName.setTextSize(14);
        tvName.setTypeface(null, android.graphics.Typeface.BOLD);
        textGroup.addView(tvName);

        TextView tvTime = new TextView(ctx);
        tvTime.setId(android.R.id.text2);
        tvTime.setTextSize(12);
        tvTime.setTextColor(0xFF9E9E9E);
        textGroup.addView(tvTime);

        row.addView(textGroup);

        TextView tvReact = new TextView(ctx);
        tvReact.setId(android.R.id.summary);
        tvReact.setTextSize(22);
        tvReact.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(tvReact);

        return new ViewHolder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        String uid = uids.get(pos);
        Long   ts  = seenTimes.get(uid);
        String emoji = reactions.get(uid);

        // Avatar
        String photo = photoCache.get(uid);
        if (photo != null && !photo.isEmpty()) {
            Glide.with(ctx).load(photo).circleCrop()
                .placeholder(android.R.drawable.ic_menu_report_image)
                .into(h.ivAvatar);
        } else {
            h.ivAvatar.setImageResource(android.R.drawable.ic_menu_report_image);
        }

        // Name
        String name = nameCache.get(uid);
        h.tvName.setText(name != null ? name : "Loading…");

        // Time
        h.tvTime.setText(ts != null ? formatAgo(ts) : "");

        // Reaction
        if (emoji != null && !emoji.isEmpty()) {
            h.tvReact.setVisibility(View.VISIBLE);
            h.tvReact.setText(emoji);
        } else {
            h.tvReact.setVisibility(View.GONE);
        }
    }

    @Override public int getItemCount() { return uids.size(); }

    private String formatAgo(long ts) {
        long diff = System.currentTimeMillis() - ts;
        long mins = diff / 60000;
        if (mins < 1)  return "just now";
        if (mins < 60) return mins + "m ago";
        long hrs = mins / 60;
        if (hrs < 24)  return hrs + "h ago";
        return android.text.format.DateFormat.format("MMM d",
            new java.util.Date(ts)).toString();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        de.hdodenhof.circleimageview.CircleImageView ivAvatar;
        TextView tvName, tvTime, tvReact;
        ViewHolder(@NonNull View v) {
            super(v);
            ivAvatar = v.findViewById(android.R.id.icon);
            tvName   = v.findViewById(android.R.id.text1);
            tvTime   = v.findViewById(android.R.id.text2);
            tvReact  = v.findViewById(android.R.id.summary);
        }
    }
}
