package com.callx.app.activities

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import com.callx.app.services.CallForegroundService
import com.callx.app.ui.screens.CallScreen
import com.callx.app.ui.screens.CallUiState
import com.callx.app.ui.theme.CallXTheme
import com.callx.app.utils.Constants
import com.callx.app.utils.FirebaseUtils
import com.callx.app.utils.PushNotify
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject
import org.webrtc.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CallActivity : ComponentActivity() {

    private var partnerUid: String?  = null
    private var partnerName: String? = null
    private var partnerPhoto: String? = null
    private var callId: String?      = null
    private var isCaller = false
    private var isVideo  = false

    private var micOn       = true
    private var camOn       = true
    private var speakerOn   = false
    private var usingFront  = true
    private var startedAt   = 0L
    private var callConnected = false
    private var finishing   = false

    private val tick = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private val iceRestartHandler = Handler(Looper.getMainLooper())
    private var iceRestartCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var callRef: DatabaseReference? = null
    private var statusListener: ValueEventListener? = null
    private var remoteCandidateListener: ChildEventListener? = null
    private var remoteDescSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var localSurfaceView: SurfaceViewRenderer? = null
    private var remoteSurfaceView: SurfaceViewRenderer? = null

    private val uiStateFlow = mutableStateOf(CallUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        partnerUid   = intent.getStringExtra("partnerUid")
        partnerName  = intent.getStringExtra("partnerName")
        partnerPhoto = intent.getStringExtra("partnerPhoto")
        isCaller     = intent.getBooleanExtra("isCaller", false)
        isVideo      = intent.getBooleanExtra("video", false)
        callId       = intent.getStringExtra("callId")

        if (partnerUid == null) { finish(); return }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        acquireWakeLock()

        uiStateFlow.value = CallUiState(
            partnerName  = partnerName ?: "Unknown",
            partnerPhoto = partnerPhoto,
            statusText   = if (isCaller) (if (isVideo) "Video calling..." else "Calling...") else "Connecting...",
            isVideo      = isVideo,
            micEnabled   = true,
            cameraEnabled = true,
            speakerEnabled = false,
            isConnected  = false
        )

        if (isVideo) {
            localSurfaceView  = SurfaceViewRenderer(this)
            remoteSurfaceView = SurfaceViewRenderer(this)
        }

        setContent {
            CallXTheme {
                val state by uiStateFlow
                CallScreen(
                    state           = state,
                    localVideoView  = localSurfaceView,
                    remoteVideoView = remoteSurfaceView,
                    onEndCall       = ::endCall,
                    onToggleMic     = ::toggleMic,
                    onToggleCamera  = ::toggleCamera,
                    onSwitchCamera  = ::switchCamera,
                    onToggleSpeaker = ::toggleSpeaker
                )
            }
        }

        checkPermsAndInit()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "callx:call_active")
            wakeLock?.acquire(3 * 60 * 60 * 1000L)
        } catch (_: Exception) {}
    }

    private fun checkPermsAndInit() {
        val perms = if (isVideo)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else
            arrayOf(Manifest.permission.RECORD_AUDIO)
        val ok = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!ok) ActivityCompat.requestPermissions(this, perms, 401)
        else     fetchTurnThenInitWebRTC()
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == 401) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) fetchTurnThenInitWebRTC()
            else { Toast.makeText(this, "Permission required", Toast.LENGTH_LONG).show(); finish() }
        }
    }

    private fun fetchTurnThenInitWebRTC() {
        bgExecutor.execute {
            val iceServers = buildFallbackIce().toMutableList()
            try {
                val url = Constants.SERVER_URL + Constants.TURN_CREDENTIALS_PATH
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000; conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val sb = StringBuilder()
                    BufferedReader(InputStreamReader(conn.inputStream)).forEachLine { sb.append(it) }
                    val j = JSONObject(sb.toString())
                    val turnUrl = j.optString("url","")
                    val user    = j.optString("username","")
                    val cred    = j.optString("credential","")
                    if (turnUrl.isNotEmpty() && user.isNotEmpty() && cred.isNotEmpty()) {
                        iceServers.clear()
                        iceServers.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_1).createIceServer())
                        iceServers.add(PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_2).createIceServer())
                        iceServers.add(PeerConnection.IceServer.builder(turnUrl)
                            .setUsername(user).setPassword(cred).createIceServer())
                    }
                }
            } catch (_: Exception) {}
            runOnUiThread { initWebRTC(iceServers) }
        }
    }

    private fun buildFallbackIce(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_1).createIceServer(),
        PeerConnection.IceServer.builder(Constants.STUN_GOOGLE_2).createIceServer()
    )

    private fun initWebRTC(iceServers: List<PeerConnection.IceServer>) {
        eglBase = EglBase.create()
        if (isVideo) {
            remoteSurfaceView?.apply { init(eglBase!!.eglBaseContext, null); setMirror(false) }
            localSurfaceView?.apply  { init(eglBase!!.eglBaseContext, null); setMirror(true)  }
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics               = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType          = PeerConnection.IceTransportsType.ALL
            bundlePolicy               = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy              = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy   = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) { sendCandidate(c) }
            override fun onIceCandidatesRemoved(c: Array<IceCandidate>) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                runOnUiThread { handleIceStateChange(s) }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: MediaStream) {
                runOnUiThread {
                    if (isVideo && stream.videoTracks.isNotEmpty()) {
                        stream.videoTracks[0].apply { setEnabled(true); addSink(remoteSurfaceView) }
                    }
                }
            }
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, streams: Array<MediaStream>) {
                if (isVideo && r.track() is VideoTrack) {
                    (r.track() as VideoTrack).apply {
                        runOnUiThread { setEnabled(true); addSink(remoteSurfaceView) }
                    }
                }
            }
        })

        audioSource = factory!!.createAudioSource(buildAudioConstraints())
        localAudioTrack = factory!!.createAudioTrack("audio0", audioSource).also { it.setEnabled(true) }

        if (isVideo) {
            videoSource   = factory!!.createVideoSource(false)
            surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
            videoCapturer = createCapturer(true)?.also {
                it.initialize(surfaceHelper, this, videoSource!!.capturerObserver)
                it.startCapture(1280, 720, 30)
            }
            localVideoTrack = factory!!.createVideoTrack("video0", videoSource).also {
                it.setEnabled(true); it.addSink(localSurfaceView)
            }
        }

        val streamIds = listOf("stream0")
        peerConnection!!.addTrack(localAudioTrack, streamIds)
        if (isVideo && localVideoTrack != null) peerConnection!!.addTrack(localVideoTrack, streamIds)

        enableSpeaker(isVideo)

        if (callId == null) callId = FirebaseUtils.db().getReference("activeCalls").push().key
        callRef = FirebaseUtils.db().getReference("activeCalls").child(callId!!)

        if (isCaller) {
            val myUid  = FirebaseUtils.getCurrentUid()
            val myName = FirebaseUtils.getCurrentName()
            callRef!!.setValue(mapOf(
                "from" to myUid, "fromName" to myName,
                "to" to partnerUid, "video" to isVideo,
                "at" to System.currentTimeMillis(), "status" to "ringing"
            )).addOnCompleteListener { createOffer() }
            PushNotify.notifyUser(partnerUid, myUid, myName,
                if (isVideo) "video_call" else "call", callId)
        } else {
            callRef!!.child("status").setValue("accepted")
            watchForOffer()
        }

        watchCallStatus()
        watchRemoteCandidates()
        registerNetworkCallback()
    }

    private fun buildAudioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
    }

    private fun createCapturer(front: Boolean): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        val names = enumerator.deviceNames
        for (n in names) { if (front == enumerator.isFrontFacing(n)) return enumerator.createCapturer(n, null) }
        for (n in names) return enumerator.createCapturer(n, null)
        return null
    }

    private fun handleIceStateChange(s: PeerConnection.IceConnectionState) {
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> onCallConnected()
            PeerConnection.IceConnectionState.DISCONNECTED -> scheduleIceRestart()
            PeerConnection.IceConnectionState.FAILED -> if (iceRestartCount < 3) scheduleIceRestart() else endCall()
            else -> {}
        }
    }

    private fun scheduleIceRestart() {
        iceRestartHandler.removeCallbacksAndMessages(null)
        iceRestartHandler.postDelayed({
            iceRestartCount++
            peerConnection?.restartIce()
        }, 2000)
    }

    private fun onCallConnected() {
        if (callConnected) return
        callConnected = true; iceRestartCount = 0
        startedAt = System.currentTimeMillis()
        uiStateFlow.value = uiStateFlow.value.copy(
            statusText = "Connected • 0:00", isConnected = true)
        val fg = Intent(this, CallForegroundService::class.java).apply {
            putExtra("name",    partnerName ?: "")
            putExtra("callId",  callId ?: "")
            putExtra("isVideo", isVideo)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(fg)
        else startService(fg)
        ticker = object : Runnable {
            override fun run() {
                val e = (System.currentTimeMillis() - startedAt) / 1000
                uiStateFlow.value = uiStateFlow.value.copy(
                    statusText = "Connected • ${e / 60}:${"%02d".format(e % 60)}")
                tick.postDelayed(this, 1_000)
            }
        }
        tick.post(ticker!!)
    }

    private fun createOffer() {
        val mc = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio","true"))
            if (isVideo) mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo","true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(noopSdp(), sdp)
                callRef?.child("offer")?.setValue(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String) {}
            override fun onSetFailure(e: String) {}
        }, mc)
    }

    private fun watchForOffer() {
        callRef?.child("offer")?.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val type = s.child("type").getValue(String::class.java) ?: return
                val sdp  = s.child("sdp").getValue(String::class.java) ?: return
                val offer = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() { remoteDescSet = true; drainCandidates(); createAnswer() }
                    override fun onCreateSuccess(s: SessionDescription) {}
                    override fun onSetFailure(e: String) {}
                    override fun onCreateFailure(e: String) {}
                }, offer)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun createAnswer() {
        val mc = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio","true"))
            if (isVideo) mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo","true"))
        }
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(noopSdp(), sdp)
                callRef?.child("answer")?.setValue(mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String) {}
            override fun onSetFailure(e: String) {}
        }, mc)
    }

    private fun sendCandidate(c: IceCandidate) {
        val myUid = FirebaseUtils.getCurrentUid() ?: return
        callRef?.child("candidates")?.child(myUid)?.push()?.setValue(mapOf(
            "candidate" to c.sdp, "sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex
        ))
    }

    private fun watchRemoteCandidates() {
        remoteCandidateListener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) {
                val cand = s.child("candidate").getValue(String::class.java) ?: return
                val mid  = s.child("sdpMid").getValue(String::class.java) ?: return
                val idx  = s.child("sdpMLineIndex").getValue(Int::class.java) ?: return
                val ic   = IceCandidate(mid, idx, cand)
                if (remoteDescSet) peerConnection?.addIceCandidate(ic)
                else pendingCandidates.add(ic)
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        callRef?.child("candidates")?.child(partnerUid!!)
            ?.addChildEventListener(remoteCandidateListener!!)
    }

    private fun drainCandidates() {
        pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    private fun watchCallStatus() {
        statusListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val st = s.getValue(String::class.java)
                if (st == "ended" || st == "rejected" || st == "cancelled") {
                    runOnUiThread { endCall() }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        callRef?.child("status")?.addValueEventListener(statusListener!!)
    }

    private fun toggleMic() {
        micOn = !micOn
        localAudioTrack?.setEnabled(micOn)
        uiStateFlow.value = uiStateFlow.value.copy(micEnabled = micOn)
    }

    private fun toggleCamera() {
        camOn = !camOn
        localVideoTrack?.setEnabled(camOn)
        uiStateFlow.value = uiStateFlow.value.copy(cameraEnabled = camOn)
    }

    private fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
        usingFront = !usingFront
    }

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        enableSpeaker(speakerOn)
        uiStateFlow.value = uiStateFlow.value.copy(speakerEnabled = speakerOn)
    }

    private fun enableSpeaker(on: Boolean) {
        audioManager?.apply {
            mode = AudioManager.MODE_IN_COMMUNICATION
            isSpeakerphoneOn = on
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    runOnUiThread { if (callConnected) peerConnection?.restartIce() }
                }
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback!!
            )
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
    }

    fun endCall() {
        if (finishing) return
        finishing = true
        iceRestartHandler.removeCallbacksAndMessages(null)
        ticker?.let { tick.removeCallbacks(it) }
        try { stopService(Intent(this, CallForegroundService::class.java)) } catch (_: Exception) {}
        callRef?.let { ref ->
            val dur = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt
            ref.child("status").setValue("ended")
            val myUid = FirebaseUtils.getCurrentUid()
            if (myUid != null) {
                FirebaseUtils.getCallsRef(myUid).push().setValue(mapOf(
                    "partnerUid"  to partnerUid,
                    "partnerName" to partnerName,
                    "direction"   to if (isCaller) "outgoing" else "incoming",
                    "mediaType"   to if (isVideo) "video" else "audio",
                    "timestamp"   to System.currentTimeMillis(),
                    "duration"    to dur
                ))
            }
        }
        audioManager?.apply { isSpeakerphoneOn = false; mode = AudioManager.MODE_NORMAL }
        releaseWebRTC()
        finish()
    }

    private fun releaseWebRTC() {
        try { unregisterNetworkCallback() } catch (_: Exception) {}
        try {
            remoteCandidateListener?.let {
                callRef?.child("candidates")?.child(partnerUid!!)?.removeEventListener(it)
            }
            statusListener?.let { callRef?.child("status")?.removeEventListener(it) }
            videoCapturer?.stopCapture(); videoCapturer?.dispose()
            localVideoTrack?.dispose(); localAudioTrack?.dispose()
            videoSource?.dispose(); audioSource?.dispose()
            peerConnection?.close(); peerConnection?.dispose()
            surfaceHelper?.dispose()
            if (isVideo) {
                try { localSurfaceView?.release() } catch (_: Exception) {}
                try { remoteSurfaceView?.release() } catch (_: Exception) {}
            }
            eglBase?.release(); factory?.dispose()
        } catch (_: Exception) {}
        try { bgExecutor.shutdownNow() } catch (_: Exception) {}
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
    }

    private fun noopSdp() = object : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String) {}
        override fun onSetFailure(e: String) {}
    }

    override fun onDestroy() {
        if (!finishing) releaseWebRTC()
        ticker?.let { tick.removeCallbacks(it) }
        iceRestartHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
