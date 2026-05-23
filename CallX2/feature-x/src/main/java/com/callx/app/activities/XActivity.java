package com.callx.app.activities;

  import android.os.Bundle;
  import android.view.View;
  import androidx.annotation.NonNull;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.fragment.app.Fragment;
  import androidx.fragment.app.FragmentTransaction;
  import com.callx.app.fragments.XExploreFragment;
  import com.callx.app.fragments.XHomeFragment;
  import com.callx.app.fragments.XMessagesFragment;
  import com.callx.app.fragments.XNotificationsFragment;
  import com.callx.app.notifications.XNotificationChannelManager;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.android.material.badge.BadgeDrawable;
  import com.google.android.material.bottomnavigation.BottomNavigationView;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;

  public class XActivity extends AppCompatActivity {

      private BottomNavigationView bottomNav;
      private ValueEventListener xNotifBadgeListener;
      private ValueEventListener xDmBadgeListener;
      private String myUid;

      // Fragment instances (kept alive)
      private final XHomeFragment          homeFragment          = new XHomeFragment();
      private final XExploreFragment       exploreFragment       = new XExploreFragment();
      private final XNotificationsFragment notificationsFragment = new XNotificationsFragment();
      private final XMessagesFragment      messagesFragment      = new XMessagesFragment();

      private Fragment activeFragment = homeFragment;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_x);

          myUid = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

          XNotificationChannelManager.ensureChannels(this);

          // Set up back button
          View btnBack = findViewById(R.id.btn_x_back);
          if (btnBack != null) btnBack.setOnClickListener(v -> finish());

          // Set up compose button in header
          View btnCompose = findViewById(R.id.btn_x_header_compose);
          if (btnCompose != null) btnCompose.setOnClickListener(v ->
              startActivity(new android.content.Intent(this, XComposeActivity.class)));

          // Init all fragments in FM
          getSupportFragmentManager().beginTransaction()
              .add(R.id.x_fragment_container, messagesFragment,      "x_msg").hide(messagesFragment)
              .add(R.id.x_fragment_container, notificationsFragment, "x_notif").hide(notificationsFragment)
              .add(R.id.x_fragment_container, exploreFragment,       "x_explore").hide(exploreFragment)
              .add(R.id.x_fragment_container, homeFragment,          "x_home")
              .commit();

          bottomNav = findViewById(R.id.x_bottom_nav);
          bottomNav.setOnItemSelectedListener(item -> {
              Fragment target = homeFragment;
              int id = item.getItemId();
              if      (id == R.id.x_nav_home)   target = homeFragment;
              else if (id == R.id.x_nav_explore) target = exploreFragment;
              else if (id == R.id.x_nav_notif)  { target = notificationsFragment; clearNotifBadge(); }
              else if (id == R.id.x_nav_dm)     { target = messagesFragment; clearDmBadge(); }
              switchFragment(target);
              return true;
          });

          startBadgeListeners();
      }

      private void switchFragment(Fragment target) {
          if (target == activeFragment) return;
          FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
          ft.hide(activeFragment).show(target);
          // Slide animation
          ft.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);
          ft.commit();
          activeFragment = target;
      }

      private void startBadgeListeners() {
          if (myUid.isEmpty()) return;
          // Notifications badge
          xNotifBadgeListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  Long count = snap.getValue(Long.class);
                  if (count != null && count > 0) {
                      BadgeDrawable badge = bottomNav.getOrCreateBadge(R.id.x_nav_notif);
                      badge.setVisible(true);
                      badge.setNumber(count.intValue());
                  } else {
                      bottomNav.removeBadge(R.id.x_nav_notif);
                  }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          };
          XFirebaseUtils.xUnreadNotifCountRef(myUid).addValueEventListener(xNotifBadgeListener);

          // DM badge — count unread conversations
          xDmBadgeListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  int unread = 0;
                  for (DataSnapshot ds : snap.getChildren()) {
                      Boolean seen = ds.child("seen").getValue(Boolean.class);
                      if (!Boolean.TRUE.equals(seen)) unread++;
                  }
                  if (unread > 0) {
                      BadgeDrawable badge = bottomNav.getOrCreateBadge(R.id.x_nav_dm);
                      badge.setVisible(true);
                      badge.setNumber(unread);
                  } else {
                      bottomNav.removeBadge(R.id.x_nav_dm);
                  }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          };
          XFirebaseUtils.xDmConversationsRef(myUid).addValueEventListener(xDmBadgeListener);
      }

      private void clearNotifBadge() {
          bottomNav.removeBadge(R.id.x_nav_notif);
          XFirebaseUtils.xUnreadNotifCountRef(myUid).setValue(0);
      }

      private void clearDmBadge() {
          bottomNav.removeBadge(R.id.x_nav_dm);
      }

      @Override protected void onDestroy() {
          super.onDestroy();
          if (myUid.isEmpty()) return;
          if (xNotifBadgeListener != null)
              XFirebaseUtils.xUnreadNotifCountRef(myUid).removeEventListener(xNotifBadgeListener);
          if (xDmBadgeListener != null)
              XFirebaseUtils.xDmConversationsRef(myUid).removeEventListener(xDmBadgeListener);
      }

      @Override public void finish() {
          super.finish();
          overridePendingTransition(0, android.R.anim.slide_out_right);
      }
  }