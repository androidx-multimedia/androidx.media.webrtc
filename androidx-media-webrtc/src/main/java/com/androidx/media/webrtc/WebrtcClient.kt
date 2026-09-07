package com.androidx.media.webrtc

import android.content.Context
import com.androidx.media.webrtc.call.CallForegroundService
import com.androidx.media.webrtc.call.CallListener
import com.androidx.media.webrtc.call.CallManager
import com.androidx.media.webrtc.core.WebRtcManager
import com.androidx.media.webrtc.models.CallState
import com.androidx.media.webrtc.models.CallType
import com.androidx.media.webrtc.models.RegisterResponse
import com.androidx.media.webrtc.models.User
import com.androidx.media.webrtc.rest.ApiClient
import com.androidx.media.webrtc.signaling.SignalingClient
import com.androidx.media.webrtc.utils.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import kotlin.coroutines.CoroutineContext

/**
 * Facade for app developers. One object to set up calling.
 *
 * ```kotlin
 * WebrtcClient.init(context, "https://your-server.onrender.com")
 * WebrtcClient.addCallListener(myListener)
 * WebrtcClient.connect("my-user-id", "Ravi")            // registers + opens socket
 *
 * // outgoing
 * val user = WebrtcClient.lookup("other-user-id")!!
 * WebrtcClient.startCall(user, CallType.VIDEO)
 *
 * // incoming (from onIncomingCall)
 * WebrtcClient.acceptIncoming()
 * ```
 */
object WebrtcClient : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO

    private lateinit var appContext: Context
    private var baseUrl: String = ""
    private lateinit var signaling: SignalingClient
    private lateinit var webrtc: WebRtcManager
    lateinit var callManager: CallManager
        private set
    var myUser: User? = null
        private set
    var token: String? = null
        private set

    var isConnected: Boolean = false
        private set
    val callState: CallState get() = callManager.state

    @Volatile private var initialized = false

    fun init(context: Context, serverUrl: String) {
        if (initialized) return
        appContext = context.applicationContext
        baseUrl = serverUrl
        ApiClient.init(serverUrl)
        webrtc = WebRtcManager.init(context)
        signaling = SignalingClient(serverUrl)
        callManager = CallManager(context, webrtc, signaling, emptyList())
        callManager.bindSignals()
        initialized = true
    }

    /** Register with the backend (anonymous, by device id) then open the signaling socket. */
    fun connect(displayName: String, onReady: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val clientId = DeviceId.get(appContext)
        launch {
            runCatching {
                val res: RegisterResponse = ApiClient.get().service
                    .register(com.androidx.media.webrtc.rest.RegisterBody(clientId, displayName))
                myUser = res.user
                token = res.token
                res.token
            }.onSuccess { tk ->
                refreshIceServers()
                signaling.connect(tk)
                isConnected = true
                onReady?.invoke()
            }.onFailure { e ->
                onError?.invoke(e.message ?: "connect failed")
            }
        }
    }

    /** Pull /rtc/ice-config once so calls start with real STUN/TURN. */
    private fun refreshIceServers() {
        launch {
            runCatching { ApiClient.get().service.iceConfig() }.onSuccess { cfg ->
                callManager.iceServers = cfg.iceServers.map { s ->
                    PeerConnection.IceServer.builder(s.urls)
                        .setUsername(s.username)
                        .setPassword(s.credential)
                        .createIceServer()
                }
            }
        }
    }

    /** Resolve another user by their clientId (what you dial). */
    fun lookup(clientId: String, onResult: (User?) -> Unit) {
        launch {
            val user = runCatching { ApiClient.get().service.lookupUser(clientId).user }.getOrNull()
            onResult(user)
        }
    }

    fun addCallListener(listener: CallListener) = callManager.addListener(listener)

    fun startCall(callee: User, type: CallType) = callManager.startCall(callee, type)
    fun acceptIncoming() = callManager.acceptIncoming()
    fun rejectIncoming() = callManager.rejectIncoming()
    fun ignoreIncoming() = callManager.ignoreIncoming()
    fun endCall() = callManager.endCall()

    fun mute(muted: Boolean) = callManager.mute(muted)
    fun switchCamera() = callManager.switchCamera()
    fun enableVideo(on: Boolean) = callManager.enableVideo(on)
    fun speaker(on: Boolean) = callManager.speaker(on)

    /** Show a WhatsApp-style foreground notification while in a call. */
    fun startForegroundService(notificationTitle: String) {
        CallForegroundService.start(appContext, notificationTitle)
    }

    /** Media control access (video track, capturer) for custom UI renderers. */
    fun getWebRtcManager(): WebRtcManager = webrtc

    /** Remote video track (attach to a SurfaceViewRenderer). */
    fun getRemoteVideoTrack() = callManager.remoteVideoTrack

    fun release() {
        runCatching { callManager.endCall() }
        signaling.disconnect()
        webrtc.destroy()
        initialized = false
    }
}