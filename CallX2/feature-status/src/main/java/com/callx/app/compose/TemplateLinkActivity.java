package com.callx.app.compose;

import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusPrivacyManager;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/**
 * v750: target of a template link ({@code https://…/template/{ownerUid}/{statusId}}, routed by DeepLinkRouterActivity).
 * Opens the template composer straight away:
 *   1. the live status still has the template → use it (the owner's reuse switch is checked by {@link TemplateActions#use});
 *   2. otherwise the viewer's own saved snapshot (statuses expire after 24h, saved templates don't);
 *   3. otherwise "Template ab available nahi hai".
 * A template from a status limited to close friends / "only" / "except" lists is never opened for anybody but its owner.
 */
public class TemplateLinkActivity extends AppCompatActivity {

    public static final String EXTRA_OWNER_UID = "templateOwnerUid";
    public static final String EXTRA_STATUS_ID = "templateStatusId";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final ProgressBar pb = new ProgressBar(this);
        final FrameLayout root = new FrameLayout(this);
        root.addView(pb, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);

        final String owner = getIntent().getStringExtra(EXTRA_OWNER_UID);
        final String id = getIntent().getStringExtra(EXTRA_STATUS_ID);
        final String me = FirebaseUtils.getCurrentUid();
        if (owner == null || owner.isEmpty() || id == null || id.isEmpty() || me == null || me.isEmpty()
                || owner.contains("/") || id.contains("/")) { finish(); return; }

        FirebaseUtils.db().getReference("status").child(owner).child(id).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (isFinishing() || isDestroyed()) return;
                StatusItem it = null;
                try { it = s.getValue(StatusItem.class); } catch (Exception ignored) { }
                final boolean mine = me.equals(owner);
                final boolean restricted = it != null && !mine && (it.isCloseFriends
                        || StatusPrivacyManager.PRIVACY_ONLY.equals(it.privacy)
                        || StatusPrivacyManager.PRIVACY_EXCEPT.equals(it.privacy));
                if (it != null && !restricted && StatusItem.isLive(it, System.currentTimeMillis())
                        && it.templateJson != null && !it.templateJson.isEmpty()) {
                    TemplateActions.use(TemplateLinkActivity.this, it.templateJson, it.stickersJson, owner, id, it.ownerName,
                            null, TemplateLinkActivity.this::finish);
                } else {
                    fromSaved(owner, id, me);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { fromSaved(owner, id, me); }
        });
    }

    private void fromSaved(final String owner, final String id, String me) {
        FirebaseUtils.db().getReference("savedTemplates").child(me).child(TemplateActions.savedKey(owner, id))
                .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                if (isFinishing() || isDestroyed()) return;
                final String json = s.child("templateJson").getValue(String.class);
                if (json == null || json.isEmpty()) { gone(); return; }
                TemplateActions.use(TemplateLinkActivity.this, json, s.child("stickersJson").getValue(String.class), owner, id,
                        s.child("ownerName").getValue(String.class), null, TemplateLinkActivity.this::finish);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { gone(); }
        });
    }

    private void gone() {
        Toast.makeText(this, "Template ab available nahi hai", Toast.LENGTH_SHORT).show();
        finish();
    }
}
