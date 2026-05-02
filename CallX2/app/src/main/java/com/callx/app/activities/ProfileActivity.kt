package com.callx.app.activities

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.callx.app.ui.screens.ProfileScreen
import com.callx.app.ui.screens.ProfileUiState
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.CloudinaryUploader
import com.callx.app.utils.FirebaseUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

class ProfileActivity : ComponentActivity() {

    private var onAvatarPicked: ((Uri) -> Unit)? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onAvatarPicked?.invoke(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val currentUid = FirebaseUtils.getCurrentUid()

        setContent {
            CallXTheme {
                var state by remember { mutableStateOf(ProfileUiState()) }

                onAvatarPicked = { uri ->
                    state = state.copy(localAvatarUri = uri, isUploading = true)
                    uploadAvatar(uri) { url ->
                        state = state.copy(
                            photoUrl = url,
                            localAvatarUri = null,
                            isUploading = false
                        )
                    }
                }

                LaunchedEffect(currentUid) {
                    if (currentUid != null) loadProfile(currentUid) { state = it }
                }

                ProfileScreen(
                    state = state,
                    onNameChange  = { state = state.copy(name = it, errorMsg = "") },
                    onAboutChange = { state = state.copy(about = it) },
                    onPickAvatar  = { imagePicker.launch("image/*") },
                    onSave = {
                        if (state.name.trim().isEmpty()) {
                            state = state.copy(errorMsg = "Name cannot be empty")
                        } else {
                            state = state.copy(isSaving = true)
                            saveProfile(currentUid, state.name.trim(), state.about.trim()) {
                                state = state.copy(isSaving = false)
                                finish()
                            }
                        }
                    },
                    onBack = ::finish
                )
            }
        }
    }

    private fun loadProfile(uid: String, onResult: (ProfileUiState) -> Unit) {
        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                onResult(ProfileUiState(
                    name     = s.child("name").getValue(String::class.java) ?: "",
                    about    = s.child("about").getValue(String::class.java) ?: "",
                    callxId  = s.child("callxId").getValue(String::class.java) ?: "",
                    email    = s.child("email").getValue(String::class.java) ?: "",
                    photoUrl = s.child("photoUrl").getValue(String::class.java)
                        ?.takeIf { it.isNotEmpty() }
                ))
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun uploadAvatar(uri: Uri, onDone: (String) -> Unit) {
        CloudinaryUploader.upload(this, uri, "callx/avatars", "image",
            object : CloudinaryUploader.UploadCallback {
                override fun onSuccess(r: CloudinaryUploader.Result) {
                    val uid = FirebaseUtils.getCurrentUid() ?: return
                    FirebaseUtils.getUserRef(uid).child("photoUrl").setValue(r.secureUrl)
                    Toast.makeText(this@ProfileActivity,
                        "Profile photo updated", Toast.LENGTH_SHORT).show()
                    onDone(r.secureUrl)
                }
                override fun onError(err: String?) {
                    Toast.makeText(this@ProfileActivity,
                        err ?: "Upload failed", Toast.LENGTH_LONG).show()
                    onDone("")
                }
            })
    }

    private fun saveProfile(uid: String?, name: String, about: String, onDone: () -> Unit) {
        if (uid == null) { onDone(); return }
        val updates = mapOf("name" to name, "about" to about)
        FirebaseUtils.getUserRef(uid).updateChildren(updates)
        FirebaseAuth.getInstance().currentUser?.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(name).build()
        )
        Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
        onDone()
    }
}
