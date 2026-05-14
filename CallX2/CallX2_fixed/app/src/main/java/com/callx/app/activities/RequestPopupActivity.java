package com.callx.app.activities;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import java.util.HashMap;
import java.util.Map;
public class RequestPopupActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        final String fromUid  = getIntent().getStringExtra("fromUid");
        final String fromName = getIntent().getStringExtra("fromName") == null
            ? "Friend" : getIntent().getStringExtra("fromName");
        if (fromUid == null || fromUid.isEmpty()) { finish(); return; }
        View sheet = LayoutInflater.from(this)
            .inflate(R.layout.dialog_request_popup, null);
        TextView tvName = sheet.findViewById(R.id.tv_name);
        TextView tvSub  = sheet.findViewById(R.id.tv_sub);
        MaterialButton btnAccept = sheet.findViewById(R.id.btn_accept);
        MaterialButton btnCancel = sheet.findViewById(R.id.btn_cancel);
        tvName.setText(fromName);
        tvSub.setText("Aapko contact request bheji hai");
        final BottomSheetDialog dlg = new BottomSheetDialog(this);
        dlg.setContentView(sheet);
        dlg.setCancelable(true);
        dlg.setOnDismissListener(d -> finish());
        btnAccept.setOnClickListener(v -> {
            String myUid  = FirebaseUtils.getCurrentUid();
            String myName = FirebaseUtils.getCurrentName();
            if (myUid == null) { dlg.dismiss(); return; }
            Map<String, Object> them = new HashMap<>();
            them.put("uid", fromUid);
            them.put("name", fromName);
            them.put("emoji", "😊");
            FirebaseUtils.getContactsRef(myUid).child(fromUid).setValue(them);
            Map<String, Object> me = new HashMap<>();
            me.put("uid", myUid);
            me.put("name", myName);
            me.put("emoji", "😊");
            FirebaseUtils.getContactsRef(fromUid).child(myUid).setValue(me);
            FirebaseUtils.getRequestsRef(myUid).child(fromUid).removeValue();
            dlg.dismiss();
        });
        btnCancel.setOnClickListener(v -> {
            String myUid = FirebaseUtils.getCurrentUid();
            if (myUid != null) {
                FirebaseUtils.getRequestsRef(myUid).child(fromUid).removeValue();
            }
            dlg.dismiss();
        });
        dlg.show();
    }
}
