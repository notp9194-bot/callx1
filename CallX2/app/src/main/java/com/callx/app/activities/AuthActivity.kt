package com.callx.app.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.callx.app.ui.screens.AuthScreen
import com.callx.app.ui.screens.AuthUiState
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.CloudinaryUploader
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.FirebaseDatabase

class AuthActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth
    private var pickedAvatarUri: Uri? = null
    private var pendingAvatarCallback: ((Uri?) -> Unit)? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        pickedAvatarUri = uri
        pendingAvatarCallback?.invoke(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()

        if (auth.currentUser != null) {
            goToMain(); return
        }

        setContent {
            CallXTheme {
                var uiState by remember { mutableStateOf(AuthUiState()) }

                pendingAvatarCallback = { uri ->
                    uiState = uiState.copy(avatarUri = uri)
                }

                AuthScreen(
                    state = uiState,
                    onNameChange     = { uiState = uiState.copy(name = it, errorMsg = "") },
                    onMobileChange   = { uiState = uiState.copy(mobile = it, errorMsg = "") },
                    onEmailChange    = { uiState = uiState.copy(email = it, errorMsg = "") },
                    onPasswordChange = { uiState = uiState.copy(password = it, errorMsg = "") },
                    onToggleMode     = {
                        uiState = AuthUiState(isLoginMode = !uiState.isLoginMode)
                    },
                    onSubmit = {
                        if (uiState.isLoginMode) login(uiState) { updated ->
                            uiState = updated
                        } else register(uiState) { updated ->
                            uiState = updated
                        }
                    },
                    onPickAvatar = { imagePicker.launch("image/*") }
                )
            }
        }
    }

    private fun login(state: AuthUiState, onState: (AuthUiState) -> Unit) {
        val email = state.email.trim()
        val pass  = state.password.trim()
        if (email.isEmpty() || pass.isEmpty()) {
            onState(state.copy(errorMsg = "Email and password required")); return
        }
        onState(state.copy(isLoading = true, errorMsg = ""))
        auth.signInWithEmailAndPassword(email, pass)
            .addOnSuccessListener { goToMain() }
            .addOnFailureListener { e ->
                val msg = when (e) {
                    is FirebaseAuthInvalidCredentialsException -> "Wrong email or password"
                    else -> e.message ?: "Login failed"
                }
                onState(state.copy(isLoading = false, errorMsg = msg))
            }
    }

    private fun register(state: AuthUiState, onState: (AuthUiState) -> Unit) {
        val name   = state.name.trim()
        val mobile = state.mobile.trim()
        val email  = state.email.trim()
        val pass   = state.password.trim()
        if (name.isEmpty())   { onState(state.copy(errorMsg = "Name is required")); return }
        if (mobile.isEmpty()) { onState(state.copy(errorMsg = "Mobile number is required")); return }
        if (email.isEmpty())  { onState(state.copy(errorMsg = "Email is required")); return }
        if (pass.length < 6)  { onState(state.copy(errorMsg = "Password must be at least 6 characters")); return }

        onState(state.copy(isLoading = true, errorMsg = ""))
        auth.createUserWithEmailAndPassword(email, pass)
            .addOnSuccessListener { result ->
                val uid  = result.user!!.uid
                val callxId = mobile.filter { it.isDigit() }
                if (state.avatarUri != null) {
                    onState(state.copy(infoMsg = "Uploading profile photo..."))
                    CloudinaryUploader.upload(this, state.avatarUri, "callx/avatars", "image",
                        object : CloudinaryUploader.UploadCallback {
                            override fun onSuccess(r: CloudinaryUploader.Result) {
                                saveUserToDb(uid, name, email, callxId, r.secureUrl)
                                result.user!!.updateProfile(
                                    UserProfileChangeRequest.Builder()
                                        .setDisplayName(name)
                                        .setPhotoUri(Uri.parse(r.secureUrl))
                                        .build()
                                )
                                goToMain()
                            }
                            override fun onError(err: String?) {
                                saveUserToDb(uid, name, email, callxId, "")
                                goToMain()
                            }
                        })
                } else {
                    saveUserToDb(uid, name, email, callxId, "")
                    result.user!!.updateProfile(
                        UserProfileChangeRequest.Builder().setDisplayName(name).build()
                    )
                    goToMain()
                }
            }
            .addOnFailureListener { e ->
                val msg = when (e) {
                    is FirebaseAuthUserCollisionException -> "This email is already registered"
                    else -> e.message ?: "Registration failed"
                }
                onState(state.copy(isLoading = false, errorMsg = msg))
            }
    }

    private fun saveUserToDb(uid: String, name: String, email: String,
                             callxId: String, photoUrl: String) {
        val user = mapOf(
            "uid"      to uid,
            "name"     to name,
            "email"    to email,
            "callxId"  to callxId,
            "photoUrl" to photoUrl,
            "about"    to "Hey there! I am using CallX",
            "online"   to true,
            "lastSeen" to System.currentTimeMillis()
        )
        FirebaseUtils.getUserRef(uid).setValue(user)
        FirebaseDatabase.getInstance().getReference("callxIds")
            .child(callxId).setValue(uid)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }
}
