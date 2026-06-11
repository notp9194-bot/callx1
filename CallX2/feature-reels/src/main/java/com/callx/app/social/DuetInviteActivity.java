package com.callx.app.social;

  import android.content.Intent;
  import android.os.Bundle;
  import android.text.Editable;
  import android.text.TextWatcher;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.models.ReelModel;
  import com.callx.app.reels.R;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.*;

  /**
   * DuetInviteActivity — Send a duet invite to a specific user.
   *
   * Flow:
   *  1. Shows a searchable list of followers
   *  2. User picks one → an invite record is written to Firebase:
   *       duet_invites/{targetUid}/{inviteId} = { fromUid, fromName, fromPhoto,
   *                                               reelId, reelThumb, reelOwner, sentAt }
   *  3. Target user sees a "Duet Invite" notification chip in their
   *     ReelNotificationsActivity (TYPE_DUET_INVITE handled by ReelFCMNotificationHandler)
   *  4. Target opens the invite → lands on DuetReelActivity pre-loaded with the reel
   *
   * Extras required:
   *   EXTRA_REEL_ID      — reel to be dueted
   *   EXTRA_REEL_THUMB   — thumbnail URL for notification
   *   EXTRA_OWNER_NAME   — display name of reel owner
   */
  public class DuetInviteActivity extends AppCompatActivity {

      public static final String EXTRA_REEL_ID    = "invite_reel_id";
      public static final String EXTRA_REEL_THUMB = "invite_reel_thumb";
      public static final String EXTRA_OWNER_NAME = "invite_owner_name";
      public static final String EXTRA_VIDEO_URL  = "invite_video_url";
      public static final String EXTRA_OWNER_UID  = "invite_owner_uid";

      private EditText   etSearch;
      private RecyclerView rvUsers;
      private ProgressBar  progress;
      private TextView     tvTitle, tvEmpty;

      private String myUid, myName, myPhoto;
      private String reelId, reelThumb, ownerName, videoUrl, ownerUid;

      private final List<UserItem> allUsers     = new ArrayList<>();
      private final List<UserItem> filteredList = new ArrayList<>();
      private UserAdapter adapter;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_duet_invite);

          reelId    = getIntent().getStringExtra(EXTRA_REEL_ID);
          reelThumb = getIntent().getStringExtra(EXTRA_REEL_THUMB);
          ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
          videoUrl  = getIntent().getStringExtra(EXTRA_VIDEO_URL);
          ownerUid  = getIntent().getStringExtra(EXTRA_OWNER_UID);

          myUid   = FirebaseAuth.getInstance().getUid();
          tvTitle = findViewById(R.id.tv_invite_title);
          etSearch = findViewById(R.id.et_invite_search);
          rvUsers  = findViewById(R.id.rv_invite_users);
          progress = findViewById(R.id.progress_invite);
          tvEmpty  = findViewById(R.id.tv_invite_empty);

          tvTitle.setText("Invite to Duet");
          rvUsers.setLayoutManager(new LinearLayoutManager(this));
          adapter = new UserAdapter(filteredList, this::sendInvite);
          rvUsers.setAdapter(adapter);

          findViewById(R.id.btn_invite_back).setOnClickListener(v -> finish());

          etSearch.addTextChangedListener(new TextWatcher() {
              @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
              @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
              @Override public void afterTextChanged(Editable s) {}
          });

          loadFollowers();
      }

      private void loadFollowers() {
          progress.setVisibility(View.VISIBLE);
          FirebaseUtils.db().getReference("followers").child(myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      List<String> uids = new ArrayList<>();
                      for (DataSnapshot ds : snap.getChildren()) uids.add(ds.getKey());
                      if (uids.isEmpty()) { progress.setVisibility(View.GONE); showEmpty(true); return; }
                      loadUserDetails(uids);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      progress.setVisibility(View.GONE);
                      showEmpty(true);
                  }
              });
      }

      private void loadUserDetails(List<String> uids) {
          final int[] loaded = {0};
          for (String uid : uids) {
              FirebaseUtils.db().getReference("users").child(uid)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
                      @Override public void onDataChange(@NonNull DataSnapshot ds) {
                          String name  = ds.child("displayName").getValue(String.class);
                          String photo = ds.child("photoUrl").getValue(String.class);
                          String uname = ds.child("username").getValue(String.class);
                          if (name != null) {
                              allUsers.add(new UserItem(uid, name, uname != null ? uname : "", photo != null ? photo : ""));
                          }
                          loaded[0]++;
                          if (loaded[0] == uids.size()) {
                              allUsers.sort((a, b2) -> a.name.compareToIgnoreCase(b2.name));
                              filteredList.addAll(allUsers);
                              adapter.notifyDataSetChanged();
                              progress.setVisibility(View.GONE);
                              showEmpty(filteredList.isEmpty());
                          }
                      }
                      @Override public void onCancelled(@NonNull DatabaseError e) {
                          loaded[0]++;
                          if (loaded[0] == uids.size()) progress.setVisibility(View.GONE);
                      }
                  });
          }
      }

      private void filter(String query) {
          filteredList.clear();
          String q = query.toLowerCase().trim();
          for (UserItem u : allUsers) {
              if (q.isEmpty() || u.name.toLowerCase().contains(q) || u.username.toLowerCase().contains(q)) {
                  filteredList.add(u);
              }
          }
          adapter.notifyDataSetChanged();
          showEmpty(filteredList.isEmpty());
      }

      private void sendInvite(UserItem user) {
          if (reelId == null || myUid == null) return;

          // Load my profile
          FirebaseUtils.db().getReference("users").child(myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot ds) {
                      String fromName  = ds.child("displayName").getValue(String.class);
                      String fromPhoto = ds.child("photoUrl").getValue(String.class);
                      if (fromName == null) fromName = "Someone";

                      Map<String, Object> invite = new HashMap<>();
                      invite.put("fromUid",    myUid);
                      invite.put("fromName",   fromName);
                      invite.put("fromPhoto",  fromPhoto != null ? fromPhoto : "");
                      invite.put("reelId",     reelId);
                      invite.put("reelThumb",  reelThumb != null ? reelThumb : "");
                      invite.put("videoUrl",   videoUrl  != null ? videoUrl  : "");
                      invite.put("ownerUid",   ownerUid  != null ? ownerUid  : "");
                      invite.put("ownerName",  ownerName != null ? ownerName : "");
                      invite.put("sentAt",     com.google.firebase.database.ServerValue.TIMESTAMP);
                      invite.put("status",     "pending"); // pending / accepted / declined

                      String inviteKey = FirebaseUtils.db().getReference("duet_invites")
                          .child(user.uid).push().getKey();
                      if (inviteKey == null) return;

                      FirebaseUtils.db().getReference("duet_invites")
                          .child(user.uid).child(inviteKey).setValue(invite)
                          .addOnSuccessListener(v -> {
                              Toast.makeText(DuetInviteActivity.this,
                                  "Invite sent to " + user.name + " ✅", Toast.LENGTH_SHORT).show();
                              finish();
                          })
                          .addOnFailureListener(e ->
                              Toast.makeText(DuetInviteActivity.this,
                                  "Failed to send invite", Toast.LENGTH_SHORT).show());
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void showEmpty(boolean show) {
          tvEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
          rvUsers.setVisibility(show ? View.GONE : View.VISIBLE);
      }

      // ── Data model ────────────────────────────────────────────────────────────
      static class UserItem {
          String uid, name, username, photoUrl;
          UserItem(String uid, String name, String username, String photoUrl) {
              this.uid = uid; this.name = name;
              this.username = username; this.photoUrl = photoUrl;
          }
      }

      // ── Adapter ───────────────────────────────────────────────────────────────
      static class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {
          interface OnInvite { void onInvite(UserItem user); }
          private final List<UserItem> items;
          private final OnInvite listener;
          UserAdapter(List<UserItem> items, OnInvite listener) {
              this.items = items; this.listener = listener;
          }
          @NonNull @Override
          public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
              View v = LayoutInflater.from(p.getContext())
                  .inflate(R.layout.item_duet_invite_user, p, false);
              return new VH(v);
          }
          @Override public void onBindViewHolder(@NonNull VH h, int pos) {
              UserItem u = items.get(pos);
              h.tvName.setText(u.name);
              h.tvUsername.setText(u.username.isEmpty() ? "" : "@" + u.username);
              if (!u.photoUrl.isEmpty()) {
                  Glide.with(h.ivAvatar).load(u.photoUrl).circleCrop().into(h.ivAvatar);
              }
              h.btnInvite.setOnClickListener(v -> listener.onInvite(u));
          }
          @Override public int getItemCount() { return items.size(); }
          static class VH extends RecyclerView.ViewHolder {
              ImageView ivAvatar; TextView tvName, tvUsername; Button btnInvite;
              VH(View v) {
                  super(v);
                  ivAvatar   = v.findViewById(R.id.iv_invite_avatar);
                  tvName     = v.findViewById(R.id.tv_invite_name);
                  tvUsername = v.findViewById(R.id.tv_invite_username);
                  btnInvite  = v.findViewById(R.id.btn_send_invite);
              }
          }
      }
  }
  