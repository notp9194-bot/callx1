package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.models.XNotification;
import com.callx.app.models.XProfile;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.utils.XProfileManager;
import com.callx.app.x.R;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * XProfileSheet — X Profile as a BottomSheetDialogFragment.
 *
 * Replaces XProfileActivity. Shown from:
 *   - Tweet avatar click
 *   - 3-dot menu "View profile" option
 *   - @mention click in tweet text
 *   - Own avatar tap in XActivity header
 *
 * Entry point:  XProfileSheet.showProfile(fragmentManager, uid)
 */
public class XProfileSheet extends BottomSheetDialogFragment {

    private static final String ARG_UID = "uid";

    private String targetUid;
    private String myUid;
    private XProfile xProfile;
    private boolean isFollowing;

    // ── Static entry point ────────────────────────────────────────────────────

    public static void showProfile(@NonNull FragmentManager fm, @Nullable String uid) {
        if (uid == null || uid.isEmpty()) return;
        if (fm.findFragmentByTag("x_profile_sheet") != null) return;
        XProfileSheet sheet = new XProfileSheet();
        Bundle args = new Bundle();
        args.putString(ARG_UID, uid);
        sheet.setArguments(args);
        sheet.show(fm, "x_profile_sheet");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.XSheet_Theme);
        targetUid = getArguments() != null ? getArguments().getString(ARG_UID, "") : "";
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_x_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        BottomSheetDialog dialog = (BottomSheetDialog) requireDialog();
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);

        showLoading(root, true);

        if (targetUid.isEmpty()) {
            dismiss();
            return;
        }
        loadProfile(root);
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    private void loadProfile(View root) {
        XProfileManager.load(targetUid, profile -> {
            if (!isAdded() || getView() == null) return;
            showLoading(root, false);
            if (profile == null) {
                Toast.makeText(requireContext(), "Profile not found", Toast.LENGTH_SHORT).show();
                dismiss();
                return;
            }
            xProfile = profile;
            bindProfile(root);
        });
    }

    // ── Bind ─────────────────────────────────────────────────────────────────

    private void bindProfile(View root) {
        // Banner
        ImageView ivBanner = root.findViewById(R.id.iv_xps_banner);
        if (ivBanner != null && xProfile.bannerUrl != null && !xProfile.bannerUrl.isEmpty())
            Glide.with(this).load(xProfile.bannerUrl)
                .apply(new RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.ALL))
                .into(ivBanner);

        // Avatar
        CircleImageView ivAvatar = root.findViewById(R.id.iv_xps_avatar);
        String av = xProfile.avatarForList();
        if (ivAvatar != null && av != null && !av.isEmpty())
            Glide.with(this).load(av)
                .apply(new RequestOptions().circleCrop().diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_person))
                .into(ivAvatar);

        // Name
        TextView tvName = root.findViewById(R.id.tv_xps_name);
        if (tvName != null) tvName.setText(xProfile.name != null ? xProfile.name : "");

        // Verified badge
        View ivVerified = root.findViewById(R.id.iv_xps_verified);
        if (ivVerified != null)
            ivVerified.setVisibility(
                (xProfile.verified || xProfile.blueVerified) ? View.VISIBLE : View.GONE);

        // Handle
        TextView tvHandle = root.findViewById(R.id.tv_xps_handle);
        if (tvHandle != null)
            tvHandle.setText(xProfile.handle != null ? "@" + xProfile.handle : "");

        // Bio
        TextView tvBio = root.findViewById(R.id.tv_xps_bio);
        if (tvBio != null) {
            boolean hasBio = xProfile.bio != null && !xProfile.bio.isEmpty();
            tvBio.setVisibility(hasBio ? View.VISIBLE : View.GONE);
            if (hasBio) tvBio.setText(xProfile.bio);
        }

        // Location
        TextView tvLoc = root.findViewById(R.id.tv_xps_location);
        if (tvLoc != null) {
            boolean has = xProfile.location != null && !xProfile.location.isEmpty();
            tvLoc.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) tvLoc.setText("📍 " + xProfile.location);
        }

        // Website
        TextView tvWeb = root.findViewById(R.id.tv_xps_website);
        if (tvWeb != null) {
            boolean has = xProfile.website != null && !xProfile.website.isEmpty();
            tvWeb.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) {
                tvWeb.setText("🔗 " + xProfile.website);
                tvWeb.setOnClickListener(v -> {
                    String url = xProfile.website.startsWith("http")
                        ? xProfile.website : "https://" + xProfile.website;
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                });
            }
        }

        // Joined date
        TextView tvJoined = root.findViewById(R.id.tv_xps_joined);
        if (tvJoined != null && xProfile.joinedTs > 0) {
            tvJoined.setVisibility(View.VISIBLE);
            tvJoined.setText("📅 Joined " +
                new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(new Date(xProfile.joinedTs)));
        }

        // Stats
        TextView tvFollowing = root.findViewById(R.id.tv_xps_following);
        TextView tvFollowers  = root.findViewById(R.id.tv_xps_followers);
        TextView tvTweets     = root.findViewById(R.id.tv_xps_tweets);
        if (tvFollowing != null) tvFollowing.setText(fmt(xProfile.followingCount) + " Following");
        if (tvFollowers  != null) tvFollowers.setText(fmt(xProfile.followerCount)  + " Followers");
        if (tvTweets     != null) tvTweets.setText(fmt(xProfile.tweetCount)        + " Posts");

        boolean isOwn = targetUid.equals(myUid);

        MaterialButton btnFollow = root.findViewById(R.id.btn_xps_follow);
        View llDm   = root.findViewById(R.id.ll_xps_dm);
        View llEdit = root.findViewById(R.id.ll_xps_edit);

        if (isOwn) {
            // Own profile: show Edit; hide Follow & DM
            if (btnFollow != null) {
                btnFollow.setText("Edit profile");
                btnFollow.setOnClickListener(v -> {
                    startActivity(new Intent(requireContext(), XEditProfileActivity.class));
                    dismiss();
                });
            }
            if (llDm   != null) llDm.setVisibility(View.GONE);
            if (llEdit != null) {
                llEdit.setVisibility(View.VISIBLE);
                llEdit.setOnClickListener(v -> {
                    startActivity(new Intent(requireContext(), XEditProfileActivity.class));
                    dismiss();
                });
            }

            // Settings row — only shown on own profile
            View llSettings = root.findViewById(R.id.ll_xps_settings);
            if (llSettings != null) {
                llSettings.setVisibility(View.VISIBLE);
                llSettings.setOnClickListener(v -> {
                    startActivity(new Intent(requireContext(), XSettingsActivity.class));
                    dismiss();
                });
            }
        } else {
            if (llEdit != null) llEdit.setVisibility(View.GONE);

            // DM row
            if (llDm != null) {
                llDm.setVisibility(View.VISIBLE);
                TextView tvDmLabel = root.findViewById(R.id.tv_xps_dm_label);
                if (tvDmLabel != null && xProfile.handle != null)
                    tvDmLabel.setText("Message @" + xProfile.handle);
                llDm.setOnClickListener(v -> {
                    if (!myUid.isEmpty()) {
                        startActivity(new Intent(requireContext(), XDMConversationActivity.class)
                            .putExtra("uid",      targetUid)
                            .putExtra("name",     xProfile.name != null ? xProfile.name : "")
                            .putExtra("photoUrl", av != null ? av : ""));
                    }
                    dismiss();
                });
            }

            // Follow button — check current state then show
            if (btnFollow != null && !myUid.isEmpty()) {
                XFirebaseUtils.userFollowersRef(targetUid).child(myUid).get()
                    .addOnSuccessListener(ds -> {
                        if (!isAdded()) return;
                        isFollowing = Boolean.TRUE.equals(ds.getValue(Boolean.class));
                        btnFollow.setText(isFollowing ? "Following" : "Follow");
                        btnFollow.setOnClickListener(v -> toggleFollow(btnFollow));
                    });
            }
        }

        // More (⋯) popup
        View btnMore = root.findViewById(R.id.btn_xps_more);
        if (btnMore != null) btnMore.setOnClickListener(v -> showMoreMenu(v, isOwn));

        // Increment profile views (not for own profile)
        if (!isOwn && !myUid.isEmpty()) XProfileManager.incrementProfileViews(targetUid);
    }

    // ── Follow / Unfollow ─────────────────────────────────────────────────────

    private void toggleFollow(MaterialButton btn) {
        if (myUid.isEmpty()) return;
        isFollowing = !isFollowing;
        btn.setText(isFollowing ? "Following" : "Follow");

        if (isFollowing) {
            XFirebaseUtils.userFollowersRef(targetUid).child(myUid).setValue(true);
            XFirebaseUtils.userFollowingRef(myUid).child(targetUid).setValue(true);
            pushFollowNotif();
        } else {
            XFirebaseUtils.userFollowersRef(targetUid).child(myUid).removeValue();
            XFirebaseUtils.userFollowingRef(myUid).child(targetUid).removeValue();
        }

        incRef(XFirebaseUtils.xUserRef(targetUid).child("followerCount"),  isFollowing);
        incRef(XFirebaseUtils.xUserRef(myUid).child("followingCount"),     isFollowing);
    }

    private void pushFollowNotif() {
        if (myUid.isEmpty()) return;
        com.callx.app.utils.FirebaseUtils.getUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    XNotification n   = new XNotification();
                    n.type            = "follow";
                    n.fromUid         = myUid;
                    n.fromName        = snap.child("name").getValue(String.class);
                    n.fromPhotoUrl    = snap.child("photoUrl").getValue(String.class);
                    if (n.fromName == null) n.fromName = "Someone";
                    n.timestamp       = System.currentTimeMillis();
                    n.read            = false;
                    n.notified        = false;
                    XFirebaseUtils.xNotificationsRef(targetUid).push().setValue(n);
                    incRef(XFirebaseUtils.xUnreadNotifCountRef(targetUid), true);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void incRef(DatabaseReference ref, boolean up) {
        ref.runTransaction(new Transaction.Handler() {
            @NonNull @Override
            public Transaction.Result doTransaction(@NonNull MutableData d) {
                Long c = d.getValue(Long.class);
                d.setValue(Math.max(0, (c != null ? c : 0) + (up ? 1 : -1)));
                return Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError e, boolean c, DataSnapshot s) {}
        });
    }

    // ── More popup menu ───────────────────────────────────────────────────────

    private void showMoreMenu(View anchor, boolean isOwn) {
        android.widget.PopupMenu popup =
            new android.widget.PopupMenu(requireContext(), anchor);
        popup.getMenu().add(0, 1, 0, "Copy profile link");
        if (!isOwn && !myUid.isEmpty() && xProfile != null) {
            String h = xProfile.handle != null ? "@" + xProfile.handle : "user";
            popup.getMenu().add(0, 2, 1, "Block " + h);
            popup.getMenu().add(0, 3, 2, "Mute "  + h);
            popup.getMenu().add(0, 4, 3, "Report " + h);
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    ClipboardManager cm = (ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && xProfile != null && xProfile.handle != null)
                        cm.setPrimaryClip(ClipData.newPlainText("Profile",
                            "https://callx.app/x/@" + xProfile.handle));
                    Toast.makeText(requireContext(), "Profile link copied", Toast.LENGTH_SHORT).show();
                    return true;
                case 2:
                    XFirebaseUtils.userBlockedRef(myUid).child(targetUid).setValue(true);
                    Toast.makeText(requireContext(),
                        (xProfile != null && xProfile.handle != null
                            ? "@" + xProfile.handle : "User") + " blocked",
                        Toast.LENGTH_SHORT).show();
                    dismiss();
                    return true;
                case 3:
                    XFirebaseUtils.userMutedRef(myUid).child(targetUid).setValue(true);
                    Toast.makeText(requireContext(),
                        (xProfile != null && xProfile.handle != null
                            ? "@" + xProfile.handle : "User") + " muted",
                        Toast.LENGTH_SHORT).show();
                    return true;
                case 4:
                    Toast.makeText(requireContext(), "Reported. Thank you.", Toast.LENGTH_SHORT).show();
                    return true;
            }
            return false;
        });
        popup.show();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void showLoading(View root, boolean show) {
        View pb = root.findViewById(R.id.pb_xps);
        if (pb != null) pb.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private String fmt(long n) {
        if (n <= 0) return "0";
        if (n < 1_000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.US, "%.1fK", n / 1000.0);
        return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
    }
}
