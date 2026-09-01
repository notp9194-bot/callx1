package com.callx.app.admin;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.admin.databinding.ActivityAdminLoginBinding;
import com.callx.app.corelite.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

/**
 * Entry point of the standalone Admin app. Signs in with Firebase Auth
 * (email/password — same auth users as the main app, or dedicated admin
 * accounts, either works), then checks admins/{uid} — the manual allowlist
 * documented in firebase_rules/firebase_verification_rules.json — before
 * letting the person into {@link AdminVerificationListActivity}. Anyone who
 * authenticates but isn't on that list is signed back out immediately; there
 * is no in-app way to add oneself to the allowlist, by design.
 */
public class AdminLoginActivity extends AppCompatActivity {

    private ActivityAdminLoginBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAdminLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnAdminLogin.setOnClickListener(v -> attemptLogin());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Already signed in from a previous session? Re-check the allowlist
        // (it could have changed since) before skipping straight to the list.
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            checkAdminAndProceed();
        }
    }

    private void attemptLogin() {
        String email    = binding.etAdminEmail.getText().toString().trim();
        String password = binding.etAdminPassword.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            showError("Enter both email and password");
            return;
        }
        setLoading(true);
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            .addOnSuccessListener(result -> checkAdminAndProceed())
            .addOnFailureListener(e -> {
                setLoading(false);
                showError(e.getMessage() != null ? e.getMessage() : "Login failed");
            });
    }

    private void checkAdminAndProceed() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) { setLoading(false); return; }
        setLoading(true);
        FirebaseUtils.getAdminsRef().child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                setLoading(false);
                boolean isAdmin = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                if (isAdmin) {
                    startActivity(new Intent(AdminLoginActivity.this, AdminVerificationListActivity.class));
                    finish();
                } else {
                    FirebaseAuth.getInstance().signOut();
                    showError("This account is not an admin.");
                }
            }
            @Override public void onCancelled(DatabaseError error) {
                setLoading(false);
                showError("Couldn't verify admin status: " + error.getMessage());
            }
        });
    }

    private void setLoading(boolean loading) {
        binding.progressAdminLogin.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnAdminLogin.setEnabled(!loading);
    }

    private void showError(String msg) {
        binding.tvAdminLoginError.setText(msg);
        binding.tvAdminLoginError.setVisibility(View.VISIBLE);
    }
}
