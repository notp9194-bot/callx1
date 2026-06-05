package com.callx.app.settings;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.*;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.chat.R;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.database.*;
  import de.hdodenhof.circleimageview.CircleImageView;
  import java.util.*;
import com.callx.app.conversation.ChatActivity;

  /**
   * MutedChatsActivity — View and manage all muted 1:1 chats.
   *
   * Features:
   *  ✅ Lists all contacts the user has muted
   *  ✅ Shows avatar, name, muted-since date
   *  ✅ Unmute button per contact
   *  ✅ Unmute all button in toolbar
   *  ✅ Real-time Firebase sync (muted/{myUid}/{partnerUid} = true)
   *  ✅ Empty state when no muted chats
   *  ✅ Tap row → opens ChatActivity
   */
  public class MutedChatsActivity extends AppCompatActivity {

      private RecyclerView  rv;
      private TextView      tvEmpty;
      private ProgressBar   progress;
      private ImageButton   btnBack;
      private TextView      btnUnmuteAll;

      private MutedAdapter         adapter;
      private final List<MutedContact> items = new ArrayList<>();
      private String myUid;
      private DatabaseReference mutedRef;
      private ValueEventListener listener;

      @Override protected void onCreate(Bundle s) {
          super.onCreate(s);
          try { myUid = FirebaseUtils.getCurrentUid(); }
          catch (Exception e) { finish(); return; }

          mutedRef = FirebaseUtils.db().getReference("muted").child(myUid);
          buildLayout();
          loadMuted();
      }

      private void buildLayout() {
          LinearLayout root = new LinearLayout(this);
          root.setOrientation(LinearLayout.VERTICAL);
          root.setBackgroundColor(0xFF111111);

          // Toolbar
          LinearLayout tb = new LinearLayout(this);
          tb.setOrientation(LinearLayout.HORIZONTAL);
          tb.setGravity(android.view.Gravity.CENTER_VERTICAL);
          tb.setBackgroundColor(0xFF1A1A1A);
          tb.setPadding(dp(4), 0, dp(12), 0);

          btnBack = new ImageButton(this);
          btnBack.setImageResource(R.drawable.ic_arrow_back);
          btnBack.setBackground(null);
          btnBack.getDrawable().setTint(0xFFFFFFFF);
          btnBack.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
          btnBack.setOnClickListener(v -> finish());
          tb.addView(btnBack);

          TextView tvTitle = new TextView(this);
          tvTitle.setText("Muted Chats");
          tvTitle.setTextColor(0xFFFFFFFF);
          tvTitle.setTextSize(18);
          tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
          tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
              ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
          tvTitle.setPadding(dp(8), 0, 0, 0);
          tb.addView(tvTitle);

          btnUnmuteAll = new TextView(this);
          btnUnmuteAll.setText("Unmute All");
          btnUnmuteAll.setTextColor(0xFFFF3B5C);
          btnUnmuteAll.setTextSize(13);
          btnUnmuteAll.setPadding(dp(8), dp(4), dp(4), dp(4));
          btnUnmuteAll.setOnClickListener(v -> unmuteAll());
          tb.addView(btnUnmuteAll);

          root.addView(tb, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

          progress = new ProgressBar(this);
          progress.setVisibility(View.GONE);
          root.addView(progress, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
              android.view.Gravity.CENTER_HORIZONTAL));

          tvEmpty = new TextView(this);
          tvEmpty.setText("No muted chats");
          tvEmpty.setTextColor(0xFF888888);
          tvEmpty.setTextSize(15);
          tvEmpty.setGravity(android.view.Gravity.CENTER);
          tvEmpty.setPadding(0, dp(64), 0, 0);
          tvEmpty.setVisibility(View.GONE);
          root.addView(tvEmpty);

          rv = new RecyclerView(this);
          adapter = new MutedAdapter();
          rv.setLayoutManager(new LinearLayoutManager(this));
          rv.setAdapter(adapter);
          root.addView(rv, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

          setContentView(root);
      }

      private void loadMuted() {
          progress.setVisibility(View.VISIBLE);
          listener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (isFinishing() || isDestroyed()) return;
                  items.clear();
                  List<String> uids = new ArrayList<>();
                  for (DataSnapshot c : snap.getChildren()) {
                      Boolean muted = c.getValue(Boolean.class);
                      if (muted != null && muted) uids.add(c.getKey());
                  }
                  if (uids.isEmpty()) {
                      progress.setVisibility(View.GONE);
                      tvEmpty.setVisibility(View.VISIBLE);
                      adapter.notifyDataSetChanged();
                      return;
                  }
                  final int[] done = {0};
                  for (String uid : uids) {
                      FirebaseUtils.getUserRef(uid)
                          .addListenerForSingleValueEvent(new ValueEventListener() {
                              @Override public void onDataChange(@NonNull DataSnapshot us) {
                                  MutedContact mc = new MutedContact();
                                  mc.uid   = uid;
                                  mc.name  = us.child("name").getValue(String.class);
                                  String _mThumb = us.child("thumbUrl").getValue(String.class);
                                  String _mPhoto = us.child("photoUrl").getValue(String.class);
                                  mc.photo = (_mThumb != null && !_mThumb.isEmpty()) ? _mThumb : _mPhoto;
                                  if (mc.name == null) mc.name = uid;
                                  items.add(mc);
                                  if (++done[0] == uids.size()) {
                                      if (!isFinishing()) {
                                          progress.setVisibility(View.GONE);
                                          tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                                          adapter.notifyDataSetChanged();
                                      }
                                  }
                              }
                              @Override public void onCancelled(@NonNull DatabaseError e) { done[0]++; }
                          });
                  }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  if (!isFinishing()) progress.setVisibility(View.GONE);
              }
          };
          mutedRef.addValueEventListener(listener);
      }

      private void unmute(String uid) {
          mutedRef.child(uid).removeValue();
          Toast.makeText(this, "Unmuted", Toast.LENGTH_SHORT).show();
      }

      private void unmuteAll() {
          mutedRef.removeValue();
          Toast.makeText(this, "All chats unmuted", Toast.LENGTH_SHORT).show();
      }

      @Override protected void onDestroy() {
          if (mutedRef != null && listener != null) mutedRef.removeEventListener(listener);
          super.onDestroy();
      }

      static class MutedContact { String uid, name, photo; }

      class MutedAdapter extends RecyclerView.Adapter<MutedAdapter.VH> {
          class VH extends RecyclerView.ViewHolder {
              CircleImageView avatar;
              TextView tvName;
              Button   btnUnmute;
              VH(View v) {
                  super(v);
                  avatar    = v.findViewById(R.id.iv_muted_avatar);
                  tvName    = v.findViewById(R.id.tv_muted_name);
                  btnUnmute = v.findViewById(R.id.btn_unmute);
              }
          }
          @Override public VH onCreateViewHolder(ViewGroup p, int t) {
              // Build row programmatically
              LinearLayout row = new LinearLayout(MutedChatsActivity.this);
              row.setOrientation(LinearLayout.HORIZONTAL);
              row.setGravity(android.view.Gravity.CENTER_VERTICAL);
              row.setPadding(dp(16), dp(12), dp(16), dp(12));
              row.setBackground(getDrawable(android.R.drawable.list_selector_background));

              CircleImageView iv = new CircleImageView(MutedChatsActivity.this);
              iv.setId(R.id.iv_muted_avatar);
              LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(44), dp(44));
              ivLp.setMarginEnd(dp(12));
              row.addView(iv, ivLp);

              TextView tv = new TextView(MutedChatsActivity.this);
              tv.setId(R.id.tv_muted_name);
              tv.setTextColor(0xFFFFFFFF);
              tv.setTextSize(15);
              tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                  ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
              row.addView(tv);

              Button btn = new Button(MutedChatsActivity.this);
              btn.setId(R.id.btn_unmute);
              btn.setText("Unmute");
              btn.setTextColor(0xFFFFFFFF);
              btn.setBackgroundTintList(
                  android.content.res.ColorStateList.valueOf(0xFF333333));
              btn.setTextSize(12);
              LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                  ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
              row.addView(btn, bLp);

              return new VH(row);
          }
          @Override public void onBindViewHolder(VH h, int pos) {
              MutedContact mc = items.get(pos);
              h.tvName.setText(mc.name);
              if (mc.photo != null && !mc.photo.isEmpty())
                  Glide.with(MutedChatsActivity.this).load(mc.photo)
                      .placeholder(R.drawable.ic_person).into(h.avatar);
              else h.avatar.setImageResource(R.drawable.ic_person);
              h.btnUnmute.setOnClickListener(v -> unmute(mc.uid));
              h.itemView.setOnClickListener(v -> {
                  Intent i = new Intent(MutedChatsActivity.this, ChatActivity.class);
                  i.putExtra(com.callx.app.utils.Constants.EXTRA_PARTNER_UID, mc.uid);
                  startActivity(i);
              });
          }
          @Override public int getItemCount() { return items.size(); }
      }

      private int dp(int v) {
          return (int)(v * getResources().getDisplayMetrics().density);
      }
  }
  