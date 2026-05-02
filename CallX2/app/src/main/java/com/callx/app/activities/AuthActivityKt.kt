package com.callx.app.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.callx.app.ui.screens.AuthScreen
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.CloudinaryUploader
import com.callx.app.utils.Constants
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging

class AuthActivityKt : ComponentActivity() {

    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (auth.currentUser != null) { goToMain(); return }

        setContent {
            CallXTheme {
                var isLoading by remember { mutableStateOf(false) }
                var errorMsg  by remember { mutableStateOf("") }

                AuthScreen(
                    isLoading    = isLoading,
                    errorMessage = errorMsg,
                    onLogin = { email, password ->
                        isLoading = true; errorMsg = ""
                        auth.signInWithEmailAndPassword(email, password)
                            .addOnSuccessListener {
                                isLoading = false
                                saveFcmToken(); goToMain()
                            }
                            .addOnFailureListener { e ->
                                isLoading = false
                                errorMsg = e.message ?: "Login failed"
                            }
                    },
                    onSignup = { email, password, name, mobile, avatarUri ->
                        if (name.isEmpty()) { errorMsg = "Naam bhi daalo"; return@AuthScreen }
                        val cleanMobile = mobile.replace(Regex("[^0-9]"), "")
                        if (cleanMobile.length < 10 || cleanMobile.length > 15) {
                            errorMsg = "Sahi mobile number daalo (10-15 digits)"; return@AuthScreen
                        }
                        isLoading = true; errorMsg = ""
                        checkMobileAvailable(cleanMobile) { available ->
                            if (!available) {
                                runOnUiThread { isLoading = false; errorMsg = "Ye mobile number pehle se registered hai" }
                                return@checkMobileAvailable
                            }
                            auth.createUserWithEmailAndPassword(email, password)
                                .addOnSuccessListener { result ->
                                    val user = result.user ?: return@addOnSuccessListener
                                    user.updateProfile(
                                        UserProfileChangeRequest.Builder().setDisplayName(name).build()
                                    )
                                    if (avatarUri != null) {
                                        CloudinaryUploader.upload(this, avatarUri, "callx/avatars", "image",
                                            object : CloudinaryUploader.UploadCallback {
                                                override fun onSuccess(res: CloudinaryUploader.Result) {
                                                    saveProfile(user, email, name, cleanMobile, res.secureUrl) {
                                                        isLoading = false; goToMain()
                                                    }
                                                }
                                                override fun onError(err: String) {
                                                    saveProfile(user, email, name, cleanMobile, null) {
                                                        isLoading = false; goToMain()
                                                    }
                                                }
                                            })
                                    } else {
                                        saveProfile(user, email, name, cleanMobile, null) {
                                            isLoading = false; goToMain()
                                        }
                                    }
                                }
                                .addOnFailureListener { e ->
                                    isLoading = false; errorMsg = e.message ?: "Signup failed"
                                }
                        }
                    }
                )
            }
        }
    }

    private fun checkMobileAvailable(callxId: String, cb: (Boolean) -> Unit) {
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").orderByChild("callxId").equalTo(callxId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) = cb(!snap.exists())
                override fun onCancelled(e: DatabaseError) = cb(true)
            })
    }

    private fun saveProfile(user: FirebaseUser, email: String, name: String,
                            callxId: String, photoUrl: String?, onDone: () -> Unit) {
        val data = mutableMapOf<String, Any>(
            "uid" to user.uid, "email" to email, "name" to name,
            "emoji" to "😊", "callxId" to callxId, "mobile" to callxId,
            "about" to "Hey, I'm on CallX!",
            "lastSeen" to System.currentTimeMillis()
        )
        if (photoUrl != null) data["photoUrl"] = photoUrl
        FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("users").child(user.uid)
            .setValue(data)
            .addOnSuccessListener {
                Toast.makeText(this, "Account ready!\nCallX ID: $callxId", Toast.LENGTH_LONG).show()
                saveFcmToken(); onDone()
            }
            .addOnFailureListener { onDone() }
    }

    private fun saveFcmToken() {
        val uid = auth.currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token != null) {
                FirebaseDatabase.getInstance(Constants.DB_URL)
                    .getReference("users").child(uid).child("fcmToken").setValue(token)
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
