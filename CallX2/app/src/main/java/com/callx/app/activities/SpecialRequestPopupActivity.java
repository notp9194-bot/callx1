package com.callx.app.activities;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;

public class SpecialRequestPopupActivity extends AppCompatActivity {

    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        final String fromUid   = getIntent().getStringExtra("fromUid");
        final String fromName  = getIntent().getStringExtra("fromName") == null
            ? "User" : getIntent().getStringExtra("fromName");
        final String fromPhoto = getIntent().getStringExtra("fromPhoto");
        final String fromThumb = getIntent().getStringExtra("fromThumb");
        final String reqText   = getIntent().getStringExtra("text") == null
            ? "Please unblock me" : getIntent().getStringExtra("text");

        if (fromUid == null || fromUid.isEmpty()) { finish(); return; }

        com.google.firebase.auth.FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) { finish(); return; }
        final String myUid   = me.getUid();
        final String myName  = me.getDisplayName() != null ? me.getDisplayName() : "";

        // 7-day auto-expire check — request purani hai to permaBlock enforce karo, sheet mat dikhao
        FirebaseUtils.db().getReference("specialRequests")
                .child(myUid).child(fromUid).child("ts")
                .get().addOnSuccessListener(tsSnap -> {
                    Long reqTs = tsSnap.exists() ? tsSnap.getValue(Long.class) : null;
                    if (reqTs != null && (System.currentTimeMillis() - reqTs) > SEVEN_DAYS_MS) {
                        // 7 din ho gaye, blocker ne response nahi diya — permaBlock enforce
                        FirebaseUtils.db().getReference("permaBlocked")
                                .child(myUid).child(fromUid).setValue(true);
                        FirebaseUtils.db().getReference("specialRequests")
                                .child(myUid).child(fromUid).removeValue();
                        FirebaseUtils.db().getReference("seenRequests")
                                .child(myUid).child(fromUid).removeValue();
                        finish();
                        return;
                    }
                    showSheet(myUid, myName, fromUid, fromName, fromPhoto, fromThumb, reqText);
                });
    }

    private void showSheet(String myUid, String myName,
                           String fromUid, String fromName,
                           String fromPhoto, String fromThumb, String reqText) {
        View sheet = LayoutInflater.from(this)
            .inflate(R.layout.dialog_special_request, null);
        TextView tvName       = sheet.findViewById(R.id.tv_sp_name);
        TextView tvText       = sheet.findViewById(R.id.tv_sp_text);
        ImageView iv          = sheet.findViewById(R.id.iv_sp_avatar);
        MaterialButton btnUnblock = sheet.findViewById(R.id.btn_sp_unblock);
        MaterialButton btnLater   = sheet.findViewById(R.id.btn_sp_later);

        tvName.setText(fromName);
        tvText.setText(reqText);

        String popupAvatar = (fromThumb != null && !fromThumb.isEmpty()) ? fromThumb : fromPhoto;
        if (popupAvatar != null && !popupAvatar.isEmpty()) {
            Glide.with(this).load(popupAvatar).circleCrop().into(iv);
        } else {
            iv.setImageResource(R.drawable.ic_person);
        }

        final BottomSheetDialog dlg = new BottomSheetDialog(this);
        dlg.setContentView(sheet);
        dlg.setCancelable(true);
        dlg.setOnDismissListener(d -> finish());

        btnUnblock.setOnClickListener(v -> {
            // 3 nodes clean karo
            FirebaseUtils.db().getReference("blocked")
                .child(myUid).child(fromUid).removeValue();
            FirebaseUtils.db().getReference("permaBlocked")
                .child(myUid).child(fromUid).removeValue();
            FirebaseUtils.db().getReference("specialRequests")
                .child(myUid).child(fromUid).removeValue();
            FirebaseUtils.db().getReference("seenRequests")
                .child(myUid).child(fromUid).removeValue();

            // Blocked user ko notify karo — unblock ho gaya
            PushNotify.notifyUnblock(fromUid, myUid, myName);

            dlg.dismiss();
        });

        btnLater.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }
}
