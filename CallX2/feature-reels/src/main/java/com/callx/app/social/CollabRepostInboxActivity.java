package com.callx.app.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.models.CollabRepostModel;
import com.callx.app.reels.R;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * CollabRepostInboxActivity — Inbox for pending Collab Repost invites.
 *
 * Firebase path: collabRepostInvites/{myUid}/{inviteId}
 *
 * Features:
 *  ✅ Real-time listener — new invites appear instantly
 *  ✅ Sorted by newest first
 *  ✅ Status badges: PENDING / ACCEPTED / DECLINED / EXPIRED / CANCELLED
 *  ✅ Thumbnail + initiator avatar + dual caption preview
 *  ✅ Tap → opens CollabRepostAcceptActivity (pending only)
 *  ✅ Marks invite as collaboratorSeen=true on load
 *  ✅ Delete / dismiss expired or declined invites with swipe
 *  ✅ Empty state illustration
 *  ✅ Unread count badge driven by collaboratorSeen flag
 */
public class CollabRepostInboxActivity extends AppCompatActivity {

    private RecyclerView  rvInvites;
    private ProgressBar   progressBar;
    private TextView      tvEmpty;
    private LinearLayout  llHeader;

    private String          myUid;
    private ValueEventListener listener;
    private DatabaseReference invitesRef;

    private final List<CollabRepostModel> invites = new ArrayList<>();
    private InboxAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { finish(); return; }

        buildLayout();
        loadInvites();
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D0D);

        // Toolbar
        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xFF161616);
        tb.setPadding(dp(4), 0, dp(12), 0);

        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setBackground(null);
        btnBack.getDrawable().setTint(0xFFFFFFFF);
        btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        btnBack.setOnClickListener(v -> finish());
        tb.addView(btnBack);

        llHeader = new LinearLayout(this);
        llHeader.setOrientation(LinearLayout.VERTICAL);
        llHeader.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Collab Repost Invites");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(dp(6), 0, 0, 0);
        llHeader.addView(tvTitle);
        tb.addView(llHeader);
        root.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        View divider = new View(this);
        divider.setBackgroundColor(0xFF222222);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // Info banner
        TextView tvInfo = new TextView(this);
        tvInfo.setText("🤝 Invites expire after 48 hours. Tap a PENDING invite to accept or decline.");
        tvInfo.setTextColor(0xFF888888);
        tvInfo.setTextSize(12);
        tvInfo.setPadding(dp(14), dp(10), dp(14), dp(10));
        tvInfo.setBackgroundColor(0xFF111111);
        root.addView(tvInfo);

        // Progress
        progressBar = new ProgressBar(this);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.topMargin = dp(24);
        root.addView(progressBar, pLp);

        // Empty state
        tvEmpty = new TextView(this);
        tvEmpty.setText("No collab repost invites yet.\nWhen someone invites you to co-repost a reel, it'll appear here.");
        tvEmpty.setTextColor(0xFF666666);
        tvEmpty.setTextSize(14);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(dp(32), dp(40), dp(32), dp(40));
        tvEmpty.setVisibility(View.GONE);
        root.addView(tvEmpty);

        // RecyclerView
        rvInvites = new RecyclerView(this);
        rvInvites.setLayoutManager(new LinearLayoutManager(this));
        adapter = new InboxAdapter(invites);
        rvInvites.setAdapter(adapter);

        // Swipe-to-dismiss (decline / remove)
        ItemTouchHelper ith = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) { return false; }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getAdapterPosition();
                if (pos < 0 || pos >= invites.size()) return;
                CollabRepostModel m = invites.get(pos);
                if ("pending".equals(m.status)) {
                    Toast.makeText(CollabRepostInboxActivity.this,
                        "Swipe right on an accepted/declined/expired invite to remove it.",
                        Toast.LENGTH_SHORT).show();
                    adapter.notifyItemChanged(pos);
                    return;
                }
                // Remove from local list + Firebase
                invites.remove(pos);
                adapter.notifyItemRemoved(pos);
                if (m.collabRepostId != null && myUid != null)
                    FirebaseDatabase.getInstance(Constants.DB_URL)
                        .getReference("collabRepostInvites")
                        .child(myUid)
                        .child(m.collabRepostId)
                        .removeValue();
            }
        });
        ith.attachToRecyclerView(rvInvites);
        root.addView(rvInvites, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void loadInvites() {
        progressBar.setVisibility(View.VISIBLE);
        invitesRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("collabRepostInvites").child(myUid);

        listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                invites.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    try {
                        CollabRepostModel m = c.getValue(CollabRepostModel.class);
                        if (m != null) {
                            if (m.collabRepostId == null || m.collabRepostId.isEmpty())
                                m.collabRepostId = c.getKey();
                            // Auto-expire
                            if ("pending".equals(m.status) && m.isExpired()) {
                                m.status = "expired";
                                c.getRef().child("status").setValue("expired");
                            }
                            invites.add(m);
                            // Mark seen
                            if (!m.collaboratorSeen)
                                c.getRef().child("collaboratorSeen").setValue(true);
                        }
                    } catch (Exception ignored) {}
                }
                // Sort newest first
                invites.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
                progressBar.setVisibility(View.GONE);
                tvEmpty.setVisibility(invites.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(CollabRepostInboxActivity.this,
                    "Failed to load invites.", Toast.LENGTH_SHORT).show();
            }
        };
        invitesRef.addValueEventListener(listener);
    }

    @Override protected void onDestroy() {
        if (invitesRef != null && listener != null)
            invitesRef.removeEventListener(listener);
        super.onDestroy();
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    class InboxAdapter extends RecyclerView.Adapter<InboxAdapter.VH> {
        final List<CollabRepostModel> list;
        InboxAdapter(List<CollabRepostModel> list) { this.list = list; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(0xFF1A1A1A);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(dp(10), dp(8), dp(10), 0);
            card.setLayoutParams(cardLp);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));

            // Top row: initiator avatar + name + time
            LinearLayout topRow = new LinearLayout(parent.getContext());
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            CircleImageView ivAv = new CircleImageView(parent.getContext());
            ivAv.setTag("av");
            ivAv.setImageResource(R.drawable.ic_person);
            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(40), dp(40));
            avLp.setMarginEnd(dp(10));
            topRow.addView(ivAv, avLp);

            LinearLayout nameCol = new LinearLayout(parent.getContext());
            nameCol.setOrientation(LinearLayout.VERTICAL);
            nameCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvName = new TextView(parent.getContext());
            tvName.setTag("name");
            tvName.setTextColor(0xFFFFFFFF);
            tvName.setTextSize(14);
            tvName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            nameCol.addView(tvName);

            TextView tvSub = new TextView(parent.getContext());
            tvSub.setText("invited you to Collab Repost");
            tvSub.setTextColor(0xFF9F7AEA);
            tvSub.setTextSize(12);
            nameCol.addView(tvSub);
            topRow.addView(nameCol);

            // Status badge
            TextView tvBadge = new TextView(parent.getContext());
            tvBadge.setTag("badge");
            tvBadge.setTextSize(10);
            tvBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tvBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
            topRow.addView(tvBadge);
            card.addView(topRow);

            // Thumbnail + captions row
            LinearLayout midRow = new LinearLayout(parent.getContext());
            midRow.setOrientation(LinearLayout.HORIZONTAL);
            midRow.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            midLp.topMargin = dp(10);

            ImageView ivThumb = new ImageView(parent.getContext());
            ivThumb.setTag("thumb");
            ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(60), dp(80));
            thumbLp.setMarginEnd(dp(10));
            midRow.addView(ivThumb, thumbLp);

            LinearLayout capCol = new LinearLayout(parent.getContext());
            capCol.setOrientation(LinearLayout.VERTICAL);
            capCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvOrigCap = new TextView(parent.getContext());
            tvOrigCap.setTag("orig_cap");
            tvOrigCap.setTextColor(0xFFAAAAAA);
            tvOrigCap.setTextSize(12);
            tvOrigCap.setMaxLines(2);
            tvOrigCap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            capCol.addView(tvOrigCap);

            TextView tvInitCap = new TextView(parent.getContext());
            tvInitCap.setTag("init_cap");
            tvInitCap.setTextColor(0xFFCCCCCC);
            tvInitCap.setTextSize(12);
            tvInitCap.setMaxLines(2);
            tvInitCap.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams initCapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            initCapLp.topMargin = dp(4);
            capCol.addView(tvInitCap, initCapLp);

            // Expiry / time
            TextView tvTime = new TextView(parent.getContext());
            tvTime.setTag("time");
            tvTime.setTextColor(0xFF666666);
            tvTime.setTextSize(11);
            LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            timeLp.topMargin = dp(6);
            capCol.addView(tvTime, timeLp);
            midRow.addView(capCol);
            card.addView(midRow, midLp);

            // Action button (only for pending)
            Button btnAccept = new Button(parent.getContext());
            btnAccept.setTag("btn_accept");
            btnAccept.setText("Review Invite →");
            btnAccept.setTextColor(0xFFFFFFFF);
            btnAccept.setBackgroundColor(0xFF7C3AED);
            btnAccept.setTextSize(13);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
            btnLp.topMargin = dp(10);
            card.addView(btnAccept, btnLp);

            return new VH(card);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            CollabRepostModel m = list.get(pos);

            // Avatar
            if (m.initiatorPhoto != null && !m.initiatorPhoto.isEmpty())
                Glide.with(h.av).load(m.initiatorPhoto).circleCrop()
                    .override(96, 96)
                    .placeholder(R.drawable.ic_person).into(h.av);
            else h.av.setImageResource(R.drawable.ic_person);

            h.tvName.setText(m.initiatorName != null ? m.initiatorName : "Unknown");

            // Thumbnail
            if (m.originalThumbUrl != null && !m.originalThumbUrl.isEmpty())
                Glide.with(h.ivThumb).load(m.originalThumbUrl).centerCrop().override(720, 720).into(h.ivThumb);
            else h.ivThumb.setImageResource(R.drawable.ic_reels);

            h.tvOrigCap.setText(m.originalCaption != null && !m.originalCaption.isEmpty()
                ? "Original: " + m.originalCaption : "Original reel");
            h.tvInitCap.setText(m.initiatorCaption != null && !m.initiatorCaption.isEmpty()
                ? "\"" + m.initiatorCaption + "\"" : "");

            // Time
            long diff = System.currentTimeMillis() - m.createdAt;
            String ago = formatAge(diff);
            String expLabel = "";
            if ("pending".equals(m.status)) {
                long expiresIn = m.expiresAt - System.currentTimeMillis();
                if (expiresIn > 0)
                    expLabel = " · expires in " + formatAge(-expiresIn);
                else
                    expLabel = " · EXPIRED";
            }
            h.tvTime.setText(ago + expLabel);

            // Badge
            String effectiveStatus = (m.isExpired() && "pending".equals(m.status)) ? "expired" : m.status;
            styleBadge(h.badge, effectiveStatus);

            // Accept button
            boolean isPending = "pending".equals(m.status) && !m.isExpired();
            h.btnAccept.setVisibility(isPending ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                if (!isPending) return;
                Intent i = new Intent(CollabRepostInboxActivity.this, CollabRepostAcceptActivity.class);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_COLLAB_REPOST_ID, m.collabRepostId);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_REEL_ID,          m.originalReelId);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_OWNER_UID,        m.originalOwnerUid);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_OWNER_NAME,       m.originalOwnerName);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_THUMB_URL,        m.originalThumbUrl);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_VIDEO_URL,        m.originalVideoUrl);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_ORIG_CAPTION,     m.originalCaption);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_INITIATOR_UID,    m.initiatorUid);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_INITIATOR_NAME,   m.initiatorName);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_INITIATOR_PHOTO,  m.initiatorPhoto);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_INITIATOR_CAP,    m.initiatorCaption);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_AUDIENCE,         m.audienceType);
                i.putExtra(CollabRepostAcceptActivity.EXTRA_MEDIA_TYPE,       m.mediaType);
                startActivity(i);
            });
            h.btnAccept.setOnClickListener(v -> h.itemView.performClick());
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView av; ImageView ivThumb;
            TextView tvName, tvOrigCap, tvInitCap, tvTime, badge;
            Button btnAccept;
            VH(View v) {
                super(v);
                av        = v.findViewWithTag("av");
                ivThumb   = v.findViewWithTag("thumb");
                tvName    = v.findViewWithTag("name");
                tvOrigCap = v.findViewWithTag("orig_cap");
                tvInitCap = v.findViewWithTag("init_cap");
                tvTime    = v.findViewWithTag("time");
                badge     = v.findViewWithTag("badge");
                btnAccept = v.findViewWithTag("btn_accept");
            }
        }

        private void styleBadge(TextView badge, String status) {
            switch (status) {
                case "accepted":
                    badge.setText("✓ ACCEPTED");
                    badge.setTextColor(0xFF22C55E);
                    badge.setBackgroundColor(0xFF14532D);
                    break;
                case "declined":
                    badge.setText("✕ DECLINED");
                    badge.setTextColor(0xFFFF3B5C);
                    badge.setBackgroundColor(0xFF4C0519);
                    break;
                case "expired":
                    badge.setText("⏰ EXPIRED");
                    badge.setTextColor(0xFFFFAA00);
                    badge.setBackgroundColor(0xFF3F2700);
                    break;
                case "cancelled":
                    badge.setText("⊘ CANCELLED");
                    badge.setTextColor(0xFF888888);
                    badge.setBackgroundColor(0xFF222222);
                    break;
                default:
                    badge.setText("● PENDING");
                    badge.setTextColor(0xFF7C3AED);
                    badge.setBackgroundColor(0xFF2A1A3E);
            }
        }

        private String formatAge(long ms) {
            if (ms < 0) ms = -ms;
            if (ms < 60_000)      return (ms / 1000) + "s";
            if (ms < 3_600_000)   return (ms / 60_000) + "m";
            if (ms < 86_400_000)  return (ms / 3_600_000) + "h";
            return (ms / 86_400_000) + "d";
        }
    }
}
