package com.callx.app.adapters;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.*;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.R;
import com.callx.app.activities.CallActivity;
import com.callx.app.activities.ChatActivity;
import com.callx.app.models.CallLog;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
public class CallHistoryAdapter extends RecyclerView.Adapter<CallHistoryAdapter.VH> {
    private final List<CallLog> logs;
    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
    public CallHistoryAdapter(List<CallLog> logs) { this.logs = logs; }
    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_call_history, parent, false);
        return new VH(v);
    }
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        CallLog l = logs.get(pos);
        Context ctx = h.itemView.getContext();
        h.tvName.setText(l.partnerName == null ? "Unknown" : l.partnerName);

        String when = l.timestamp != null ? fmt.format(new Date(l.timestamp)) : "";
        String dur = (l.duration != null && l.duration > 0)
            ? FileUtils.formatDuration(l.duration) : "";
        String type = "video".equals(l.mediaType) ? "Video" : "Voice";
        String meta = type + "  •  " + when + (dur.isEmpty() ? "" : "  •  " + dur);
        h.tvMeta.setText(meta);

        String dir = l.direction == null ? "" : l.direction.toLowerCase();
        if (dir.contains("missed")) {
            h.ivDirection.setColorFilter(Color.parseColor("#EF4444"));
            h.ivDirection.setImageResource(R.drawable.ic_phone_off);
        } else if (dir.contains("incoming") || dir.contains("in")) {
            h.ivDirection.setColorFilter(Color.parseColor("#22C55E"));
            h.ivDirection.setImageResource(R.drawable.ic_phone);
        } else {
            h.ivDirection.setColorFilter(Color.parseColor("#5B5BF6"));
            h.ivDirection.setImageResource(R.drawable.ic_phone);
        }

        // Load partner avatar from Firebase
        if (l.partnerUid != null && !l.partnerUid.isEmpty()) {
            FirebaseUtils.getUserRef(l.partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        String photo = snap.child("photoUrl").getValue(String.class);
                        if (photo != null && !photo.isEmpty() && ctx != null) {
                            Glide.with(ctx)
                                .load(photo)
                                .apply(RequestOptions.circleCropTransform())
                                .placeholder(R.drawable.ic_person)
                                .error(R.drawable.ic_person)
                                .into(h.ivAvatar);
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        }

        // Avatar click → open ChatActivity
        h.ivAvatar.setOnClickListener(v -> openChat(ctx, l));
        // Name click → open ChatActivity
        h.tvName.setOnClickListener(v -> openChat(ctx, l));
        h.tvName.setClickable(true);
        h.tvName.setFocusable(true);

        // Voice call back
        h.btnCallBack.setOnClickListener(v -> {
            Intent i = new Intent(ctx, CallActivity.class);
            i.putExtra("partnerUid", l.partnerUid);
            i.putExtra("partnerName", l.partnerName);
            i.putExtra("isCaller", true);
            i.putExtra("video", false);
            ctx.startActivity(i);
        });

        // Video call back
        h.btnVideoCall.setOnClickListener(v -> {
            Intent i = new Intent(ctx, CallActivity.class);
            i.putExtra("partnerUid", l.partnerUid);
            i.putExtra("partnerName", l.partnerName);
            i.putExtra("isCaller", true);
            i.putExtra("video", true);
            ctx.startActivity(i);
        });
    }

    private void openChat(Context ctx, CallLog l) {
        if (l.partnerUid == null || l.partnerUid.isEmpty()) return;
        Intent i = new Intent(ctx, ChatActivity.class);
        i.putExtra("partnerUid", l.partnerUid);
        i.putExtra("partnerName", l.partnerName != null ? l.partnerName : "");
        ctx.startActivity(i);
    }

    @Override public int getItemCount() { return logs.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvMeta;
        ImageButton btnCallBack, btnVideoCall;
        ImageView ivDirection;
        CircleImageView ivAvatar;
        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tv_name);
            tvMeta = v.findViewById(R.id.tv_meta);
            btnCallBack = v.findViewById(R.id.btn_call_back);
            btnVideoCall = v.findViewById(R.id.btn_video_call);
            ivDirection = v.findViewById(R.id.iv_direction);
            ivAvatar = v.findViewById(R.id.iv_avatar);
        }
    }
}
