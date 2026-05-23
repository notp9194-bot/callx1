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
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
public class SpecialRequestPopupActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        final String fromUid   = getIntent().getStringExtra("fromUid");
        final String fromName  = getIntent().getStringExtra("fromName") == null
            ? "User" : getIntent().getStringExtra("fromName");
        final String fromPhoto = getIntent().getStringExtra("fromPhoto");
        final String reqText   = getIntent().getStringExtra("text") == null
            ? "Please unblock me" : getIntent().getStringExtra("text");
        if (fromUid == null || fromUid.isEmpty()) { finish(); return; }
        View sheet = LayoutInflater.from(this)
            .inflate(R.layout.dialog_special_request, null);
        TextView tvName = sheet.findViewById(R.id.tv_sp_name);
        TextView tvText = sheet.findViewById(R.id.tv_sp_text);
        ImageView iv    = sheet.findViewById(R.id.iv_sp_avatar);
        MaterialButton btnUnblock = sheet.findViewById(R.id.btn_sp_unblock);
        MaterialButton btnLater   = sheet.findViewById(R.id.btn_sp_later);
        tvName.setText(fromName);
        tvText.setText(reqText);
        if (fromPhoto != null && !fromPhoto.isEmpty()) {
            Glide.with(this).load(fromPhoto).into(iv);
        } else {
            iv.setImageResource(R.drawable.ic_person);
        }
        final BottomSheetDialog dlg = new BottomSheetDialog(this);
        dlg.setContentView(sheet);
        dlg.setCancelable(true);
        dlg.setOnDismissListener(d -> finish());
        // Feature 16: Unblock {fromName} → block + permaBlock + specialReq remove
        btnUnblock.setOnClickListener(v -> {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) {
                dlg.dismiss(); return;
            }
            String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            FirebaseUtils.db().getReference("blocked")
                .child(myUid).child(fromUid).removeValue();
            FirebaseUtils.db().getReference("permaBlocked")
                .child(myUid).child(fromUid).removeValue();
            FirebaseUtils.db().getReference("specialRequests")
                .child(myUid).child(fromUid).removeValue();
            dlg.dismiss();
        });
        btnLater.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }
}
