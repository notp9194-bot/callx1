package com.callx.app.conversation.controllers;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.callx.app.chat.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.callx.app.views.EmojiRainView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles all block / perma-block / unblock-joy / special-request logic.
 * Extracted from ChatActivity to keep the activity thin.
 */
public class ChatBlockController {

    private static final int MAX_SPECIAL_REQUESTS = 3;

    private final ChatActivityDelegate delegate;

    private ValueEventListener blockListener;
    private ValueEventListener permaBlockListener;
    private ValueEventListener myPermaBlockListener;

    public ChatBlockController(ChatActivityDelegate delegate) {
        this.delegate = delegate;
    }

    // ── Init ──────────────────────────────────────────────────────────────

    public void init() {
        watchBlock();
        watchMyPermaBlock();
        watchPartnerPermaBlock();
        checkAndShowUnblockJoy();
        checkAndShowPendingSpecialRequest();
    }

    // ── Watch block (I blocked partner) ───────────────────────────────────

    private void watchBlock() {
        String currentUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (currentUid == null || currentUid.isEmpty() || partnerUid == null || partnerUid.isEmpty()) return;

        blockListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                delegate.setBlocked(Boolean.TRUE.equals(s.getValue(Boolean.class)));
                applyBlockUi();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getBlocksRef(currentUid)
                .child(partnerUid)
                .addValueEventListener(blockListener);
    }

    // ── Watch if I have already perma-blocked partner ─────────────────────

    private void watchMyPermaBlock() {
        String currentUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (currentUid == null || currentUid.isEmpty() || partnerUid == null || partnerUid.isEmpty()) return;

        myPermaBlockListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                delegate.setIPermaBlockedPartner(Boolean.TRUE.equals(s.getValue(Boolean.class)));
                applyBlockUi();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("permaBlocked")
                .child(currentUid).child(partnerUid)
                .addValueEventListener(myPermaBlockListener);
    }

    // ── Watch if partner perma-blocked me ─────────────────────────────────

    private void watchPartnerPermaBlock() {
        String currentUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (partnerUid == null || partnerUid.isEmpty() || currentUid == null || currentUid.isEmpty()) return;

        permaBlockListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                delegate.setPartnerPermaBlockedMe(Boolean.TRUE.equals(s.getValue(Boolean.class)));
                applyPermaBlockUi();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("permaBlocked")
                .child(partnerUid).child(currentUid)
                .addValueEventListener(permaBlockListener);
    }

    // ── Apply UI based on block state ─────────────────────────────────────

    public void applyBlockUi() {
        com.callx.app.chat.databinding.ActivityChatBinding binding = delegate.getBinding();
        boolean isBlocked = delegate.isBlocked();
        String partnerName = delegate.getPartnerName();

        binding.etMessage.setEnabled(!isBlocked);
        if (isBlocked) {
            binding.etMessage.setHint("You have blocked " + partnerName);
            binding.btnSend.setVisibility(View.GONE);
            binding.btnMic.setVisibility(View.GONE);
            binding.llBlockBanner.setVisibility(View.VISIBLE);
            binding.tvBlockBannerText.setText("You have blocked " + partnerName);
            if (delegate.isIPermaBlockedPartner()) {
                binding.btnPermanentBlock.setVisibility(View.GONE);
                binding.tvBlockBannerText.setText("You have permanently blocked " + partnerName);
            } else {
                binding.btnPermanentBlock.setVisibility(View.VISIBLE);
                binding.btnPermanentBlock.setOnClickListener(v -> confirmPermanentBlock());
            }
        } else {
            binding.etMessage.setHint(delegate.getActivity().getString(R.string.hint_message));
            binding.btnMic.setVisibility(View.VISIBLE);
            binding.llBlockBanner.setVisibility(View.GONE);
        }
    }

    // ── Apply UI based on perma-block state ───────────────────────────────

    public void applyPermaBlockUi() {
        com.callx.app.chat.databinding.ActivityChatBinding binding = delegate.getBinding();
        String partnerName = delegate.getPartnerName();

        if (!delegate.isPartnerPermaBlockedMe()) {
            binding.etMessage.setEnabled(true);
            binding.etMessage.setHint(delegate.getActivity().getString(R.string.hint_message));
            return;
        }
        binding.etMessage.setEnabled(false);
        binding.etMessage.setHint(partnerName + " has blocked you");
        binding.btnSend.setVisibility(View.GONE);
        binding.btnMic.setVisibility(View.GONE);

        FirebaseUtils.db().getReference("specialRequests")
                .child(delegate.getPartnerUid()).child(delegate.getCurrentUid()).child("attemptCount")
                .get().addOnSuccessListener(snap -> {
                    long count = (snap.exists() && snap.getValue(Long.class) != null)
                            ? snap.getValue(Long.class) : 0L;
                    if (count >= MAX_SPECIAL_REQUESTS) {
                        Snackbar.make(binding.getRoot(),
                                partnerName + " has permanently blocked you. No more requests allowed.",
                                Snackbar.LENGTH_LONG).show();
                    } else {
                        long remaining = MAX_SPECIAL_REQUESTS - count;
                        Snackbar.make(binding.getRoot(),
                                        partnerName + " has permanently blocked you",
                                        Snackbar.LENGTH_INDEFINITE)
                                .setAction("Send request (" + remaining + " left)",
                                        v -> openSpecialRequestDialog())
                                .show();
                    }
                });
    }

    // ── Confirm block / unblock ───────────────────────────────────────────

    public void confirmBlockUser() {
        boolean isBlocked = delegate.isBlocked();
        String partnerName = delegate.getPartnerName();
        String label = isBlocked ? "Unblock" : "Block";
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle(label + " " + partnerName + "?")
                .setPositiveButton(label, (d, w) ->
                    FirebaseUtils.getBlocksRef(delegate.getCurrentUid())
                            .child(delegate.getPartnerUid())
                            .setValue(!isBlocked))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Confirm permanent block ───────────────────────────────────────────

    public void confirmPermanentBlock() {
        String partnerName = delegate.getPartnerName();
        new AlertDialog.Builder(delegate.getActivity())
                .setTitle("⛔ Permanently Block " + partnerName + "?")
                .setMessage(partnerName + " will be permanently blocked. They will NOT be able to "
                        + "send you any requests or contact you ever again.\n\nThis action cannot be undone.")
                .setPositiveButton("Permanent Block", (d, w) -> {
                    FirebaseUtils.db().getReference("permaBlocked")
                            .child(delegate.getCurrentUid()).child(delegate.getPartnerUid())
                            .setValue(true);
                    FirebaseUtils.getBlocksRef(delegate.getCurrentUid())
                            .child(delegate.getPartnerUid())
                            .setValue(true);
                    Toast.makeText(delegate.getActivity(),
                            partnerName + " has been permanently blocked.", Toast.LENGTH_LONG).show();
                    delegate.getBinding().llBlockBanner.setVisibility(View.GONE);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Unblock joy sheet ─────────────────────────────────────────────────

    public void checkAndShowUnblockJoy() {
        String currentUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (currentUid == null || partnerUid == null) return;

        boolean fromNotif = delegate.getActivity().getIntent()
                .getBooleanExtra("show_unblock_joy", false);

        FirebaseUtils.db().getReference("unblockEvents")
                .child(currentUid).child(partnerUid)
                .get().addOnSuccessListener(snap -> {
                    if (!snap.exists()) return;
                    String unblockName = snap.child("unblockedBy").getValue(String.class);
                    if (unblockName == null) unblockName = delegate.getPartnerName();
                    snap.getRef().removeValue();
                    final String displayName = unblockName;
                    delegate.getBinding().getRoot().postDelayed(
                            () -> showUnblockJoySheet(displayName),
                            fromNotif ? 400 : 800);
                });
    }

    private void showUnblockJoySheet(String unblockerName) {
        android.app.Activity activity = delegate.getActivity();
        if (activity.isFinishing() || activity.isDestroyed()) return;

        View sheet = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_unblock_joy, null);

        de.hdodenhof.circleimageview.CircleImageView ivAvatar =
                sheet.findViewById(R.id.iv_joy_avatar);
        android.widget.TextView tvName  = sheet.findViewById(R.id.tv_joy_name);
        EmojiRainView rain              = sheet.findViewById(R.id.emoji_rain_joy);
        com.google.android.material.button.MaterialButton btnOpen  = sheet.findViewById(R.id.btn_joy_open_chat);
        com.google.android.material.button.MaterialButton btnLater = sheet.findViewById(R.id.btn_joy_later);

        tvName.setText(unblockerName);

        String photo = delegate.getPartnerPhoto();
        String thumb = delegate.getPartnerThumb();
        if (photo != null && !photo.isEmpty()) {
            com.bumptech.glide.Glide.with(activity).load(photo).into(ivAvatar);
        } else if (thumb != null && !thumb.isEmpty()) {
            com.bumptech.glide.Glide.with(activity).load(thumb).into(ivAvatar);
        }

        rain.setHappyMode(true);
        rain.startRain();

        BottomSheetDialog dlg = new BottomSheetDialog(activity,
                com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog);
        dlg.setContentView(sheet);
        dlg.setCancelable(true);
        dlg.setOnDismissListener(d -> rain.stopRain());
        btnOpen.setOnClickListener(v -> dlg.dismiss());
        btnLater.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    // ── Special request ───────────────────────────────────────────────────

    public void checkAndShowPendingSpecialRequest() {
        String currentUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();
        if (currentUid == null || partnerUid == null) return;

        DatabaseReference reqRef  = FirebaseUtils.db()
                .getReference("specialRequests").child(currentUid).child(partnerUid);
        DatabaseReference seenRef = FirebaseUtils.db()
                .getReference("seenRequests").child(currentUid).child(partnerUid);

        reqRef.get().addOnSuccessListener(reqSnap -> {
            if (!reqSnap.exists()) return;
            Long reqTs = reqSnap.child("ts").getValue(Long.class);
            if (reqTs == null) return;

            seenRef.get().addOnSuccessListener(seenSnap -> {
                Long seenAt = seenSnap.exists() ? seenSnap.getValue(Long.class) : 0L;
                if (seenAt == null) seenAt = 0L;
                if (reqTs <= seenAt) return;

                seenRef.setValue(reqTs).addOnSuccessListener(v -> {
                    String fromName  = reqSnap.child("fromName").getValue(String.class);
                    String fromPhoto = reqSnap.child("fromPhoto").getValue(String.class);
                    String text      = reqSnap.child("text").getValue(String.class);

                    Intent popup = new Intent();
                    popup.setClassName(delegate.getActivity().getPackageName(),
                            "com.callx.app.activities.SpecialRequestPopupActivity");
                    popup.putExtra("fromUid",   partnerUid);
                    popup.putExtra("fromName",  fromName  != null ? fromName  : delegate.getPartnerName());
                    popup.putExtra("fromPhoto", fromPhoto != null ? fromPhoto : "");
                    popup.putExtra("text",      text      != null ? text      : "Please unblock me");
                    delegate.getActivity().startActivity(popup);
                });
            });
        });
    }

    public void openSpecialRequestDialog() {
        FirebaseUtils.db().getReference("specialRequests")
                .child(delegate.getPartnerUid()).child(delegate.getCurrentUid())
                .get().addOnSuccessListener(snap -> {
                    long count = 0L;
                    if (snap.exists()) {
                        Long raw = snap.child("attemptCount").getValue(Long.class);
                        if (raw != null) count = raw;
                    }
                    if (count >= MAX_SPECIAL_REQUESTS) {
                        Toast.makeText(delegate.getActivity(),
                                "No more attempts allowed.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showSendRequestSheet(count);
                });
    }

    private void showSendRequestSheet(long currentAttemptCount) {
        android.app.Activity activity = delegate.getActivity();
        View sheet = LayoutInflater.from(activity)
                .inflate(R.layout.bottom_sheet_send_request, null);

        de.hdodenhof.circleimageview.CircleImageView ivBlocker =
                sheet.findViewById(R.id.iv_blocker_avatar);
        android.widget.TextView tvBlockerName  = sheet.findViewById(R.id.tv_blocker_name);
        android.widget.TextView tvAttemptsLeft = sheet.findViewById(R.id.tv_attempts_left);
        android.widget.TextView tvAttemptInfo  = sheet.findViewById(R.id.tv_attempt_info);
        android.widget.TextView tvCharCount    = sheet.findViewById(R.id.tv_char_count);
        android.widget.EditText etMsg          = sheet.findViewById(R.id.et_request_message);
        EmojiRainView rain                     = sheet.findViewById(R.id.emoji_rain_send);
        com.google.android.material.button.MaterialButton btnSend   = sheet.findViewById(R.id.btn_send_request);
        com.google.android.material.button.MaterialButton btnCancel = sheet.findViewById(R.id.btn_cancel_request);

        String partnerName = delegate.getPartnerName();
        tvBlockerName.setText(partnerName);
        long remaining = MAX_SPECIAL_REQUESTS - currentAttemptCount;
        tvAttemptsLeft.setText("has blocked you \u2022 " + remaining + " attempt(s) left");
        tvAttemptInfo.setText("Attempt " + (currentAttemptCount + 1) + " of " + MAX_SPECIAL_REQUESTS);

        FirebaseUtils.getUserRef(delegate.getPartnerUid()).child("photoUrl").get()
                .addOnSuccessListener(photoSnap -> {
                    String url = photoSnap.exists() ? photoSnap.getValue(String.class) : null;
                    if (url != null && !url.isEmpty()) {
                        com.bumptech.glide.Glide.with(activity).load(url).into(ivBlocker);
                    }
                });

        etMsg.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                tvCharCount.setText(s.length() + " / 300");
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        rain.startRain();

        BottomSheetDialog dlg = new BottomSheetDialog(activity,
                com.google.android.material.R.style.Theme_Material3_Dark_BottomSheetDialog);
        dlg.setContentView(sheet);
        dlg.setCancelable(true);
        dlg.setOnDismissListener(d -> rain.stopRain());
        btnCancel.setOnClickListener(v -> dlg.dismiss());

        btnSend.setOnClickListener(v -> {
            String txt = etMsg.getText().toString().trim();
            if (txt.isEmpty()) txt = "Please unblock me \uD83D\uDE4F";

            FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
            String myPhoto = (me != null && me.getPhotoUrl() != null)
                    ? me.getPhotoUrl().toString() : "";

            long newCount = currentAttemptCount + 1;
            Map<String, Object> entry = new HashMap<>();
            entry.put("text",         txt);
            entry.put("ts",           System.currentTimeMillis());
            entry.put("fromName",     delegate.getCurrentName());
            entry.put("fromUid",      delegate.getCurrentUid());
            entry.put("fromPhoto",    myPhoto);
            entry.put("attemptCount", newCount);

            FirebaseUtils.db().getReference("specialRequests")
                    .child(delegate.getPartnerUid()).child(delegate.getCurrentUid()).setValue(entry);

            if (newCount >= MAX_SPECIAL_REQUESTS) {
                FirebaseUtils.db().getReference("permaBlocked")
                        .child(delegate.getPartnerUid()).child(delegate.getCurrentUid()).setValue(true);
                Toast.makeText(activity,
                        "Last attempt used. You are now permanently blocked.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(activity,
                        "Request sent (" + newCount + "/" + MAX_SPECIAL_REQUESTS + ")",
                        Toast.LENGTH_SHORT).show();
            }

            PushNotify.notifySpecialRequest(delegate.getPartnerUid(), delegate.getCurrentUid(),
                    delegate.getCurrentName(), myPhoto, txt);
            dlg.dismiss();
        });

        dlg.show();
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    public void release() {
        String currentUid = delegate.getCurrentUid();
        String partnerUid = delegate.getPartnerUid();

        if (blockListener != null && currentUid != null && partnerUid != null) {
            FirebaseUtils.getBlocksRef(currentUid).child(partnerUid)
                    .removeEventListener(blockListener);
        }
        if (permaBlockListener != null && partnerUid != null && currentUid != null) {
            FirebaseUtils.db().getReference("permaBlocked").child(partnerUid).child(currentUid)
                    .removeEventListener(permaBlockListener);
        }
        if (myPermaBlockListener != null && currentUid != null && partnerUid != null) {
            FirebaseUtils.db().getReference("permaBlocked").child(currentUid).child(partnerUid)
                    .removeEventListener(myPermaBlockListener);
        }
    }
}
