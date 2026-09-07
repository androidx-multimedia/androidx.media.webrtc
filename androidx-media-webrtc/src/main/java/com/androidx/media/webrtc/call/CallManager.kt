package com.androidx.media.webrtc.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.androidx.media.webrtc.core.WebRtcManager
import com.androidx.media.webrtc.models.CallState
import com.androidx.media.webrtc.models.CallType
import com.androidx.media.webrtc.models.IncomingCall
import com.androidx.media.webrtc.models.User
import com.androidx.media.webrtc.rest.ApiClient
import com.androidx.media.webrtc.signaling.SignalingClient
import com.androidx.media.webrtc.utils.Json
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.LinkedBlockingQueue

/**
 * 1:1 call state machine, matching backend signaling exactly.
 * One media path: the CALLEE always offers SDP; caller answers.
 *
 *  Caller:  call:invite → (callee accepts) → recv call:offer? No:
 *  Callee:  accepts → offers media → caller answers → ICE both ways.
 */
class CallManager(
    @Suppress("unused") context: Context,
    private val webrtc: WebRtcManager,
    private val signaling: SignalingClient,
    listeners: List<CallListener>,
) {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = listeners.toMutableList()

    var state: CallState = CallState.IDLE
        private set
    var currentRoomId: String? = null
        private set
    var isCaller = false
        private set

    private var pc: PeerConnection? = null
    private var peerUid: String? = null
    private var currentType: CallType = CallType.AUDIO
    private val pendingCandidates = LinkedBlockingQueue<IceCandidate>()

    fun addListener(l: CallListener) { listeners.add(l) }

    fun bindSignals() {
        signaling.onEvent { event, payload ->
            when (event) {
                "call:invite" -> handleIncoming(payload)
                "call:accept" -> if (isCaller) onPeerAccept(payload)
                "call:reject" -> if (isCaller) finish("rejected")
                "call:end" -> handleRemoteEnd(payload)
                "call:offer" -> handleOffer(payload)
                "call:answer" -> handleAnswer(payload)
                "ice:candidate" -> handleIceCandidate(payload)
            }
        }
    }

    // ── Caller side ────────────────────────────────────────
    fun startCall(callee: User, type: CallType) {
        peerUid = callee.id
        currentType = type
        isCaller = true
        setState(CallState.RINGING_OUTGOING)
        signaling.emit("call:invite", mapOf("calleeId" to callee.id, "type" to type.wire))
    }

    private fun onPeerAccept(payload: Map<String, Any>) {
        if (state != CallState.RINGING_OUTGOING) return
        currentRoomId = (payload["roomId"] as? String) ?: return
        setState(CallState.CONNECTING)
        // Callee offers media — we wait for call:offer. Note: PeerConnection
        // must exist before candidates arrive, create it now.
        createPeer()
    }

    // ── Callee side ────────────────────────────────────────
    fun acceptIncoming() {
        val roomId = currentRoomId ?: return
        isCaller = false
        setState(CallState.CONNECTING)
        signaling.emit("call:accept", mapOf("roomId" to roomId))
        createPeer()
        sendOffer() // callee offers media
    }

    fun rejectIncoming() {
        currentRoomId?.let { signaling.emit("call:reject", mapOf("roomId" to it)) }
        finish("rejected")
    }

    fun ignoreIncoming() {
        currentRoomId?.let { signaling.emit("call:retry", mapOf("roomId" to it)) }
        finish("missed")
    }

    // ── SDP / ICE ──────────────────────────────────────────
    private fun createPeer() {
        pc = webrtc.createPeerConnection(fetchIceConfig(), peerObserver())
        flushPendingCandidates()
    }

    private fun sendOffer() {
        val p = pc ?: return
        p.createOffer(offerObserver(), sdpConstraints())
    }

    private fun offerObserver() = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {
            val p = pc ?: return
            if (desc == null) return
            p.setLocalDescription(this, desc)
            signaling.emit("call:offer", mapOf(
                "targetId" to (peerUid ?: ""),
                "sdp" to mapOf("type" to "offer", "sdp" to desc.description),
                "callType" to currentType.wire,
            ))
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { listeners.forEach { it.onError("offer failed: $error") } }
        override fun onSetFailure(error: String?) { listeners.forEach { it.onError("setLocal failed: $error") } }
    }

    private fun handleOffer(payload: Map<String, Any>) {
        val sdp = Json.sdp(payload["sdp"]) ?: return
        peerUid = (payload["from"] as? String) ?: peerUid
        val p = pc ?: createPeer()!!
        p.setRemoteDescription(remoteSetObserver(), SessionDescription(SessionDescription.Type.OFFER, sdp.description))
    }

    private fun handleAnswer(payload: Map<String, Any>) {
        val sdp = Json.sdp(payload["sdp"]) ?: return
        val p = pc ?: createPeer()!!
        p.setRemoteDescription(remoteSetObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp.description))
    }

    private fun remoteSetObserver() = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() { flushPendingCandidates() }
        override fun onCreateFailure(error: String?) { listeners.forEach { it.onError("remote set failed: $error") } }
        override fun onSetFailure(error: String?) { listeners.forEach { it.onError("remote set failed: $error") } }
    }

    private fun handleIceCandidate(payload: Map<String, Any>) {
        @Suppress("UNCHECKED_CAST")
        val m = payload["candidate"] as? Map<String, Any> ?: return
        val candidate = IceCandidate(
            m["sdpMid"] as? String,
            (m["sdpMLineIndex"] as? Number)?.toInt(),
            m["candidate"] as? String ?: return,
        )
        val p = pc
        if (p != null && p.remoteDescription != null) p.addIceCandidate(candidate)
        else pendingCandidates.add(candidate)
    }

    private fun flushPendingCandidates() {
        val p = pc ?: return
        while (true) {
            val c = pendingCandidates.poll() ?: break
            if (p.remoteDescription != null) p.addIceCandidate(c)
        }
    }

    private fun peerObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signaling.emit("ice:candidate", mapOf(
                "targetId" to (peerUid ?: ""),
                "candidate" to mapOf(
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate" to candidate.sdp,
                ),
            ))
        }
        override fun onAddStream(stream: MediaStream) {
            remoteVideoTrack = stream.videoTracks.firstOrNull()
            listeners.forEach { it.onRemoteTrackAdded() }
        }
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out MediaStream>?) {
            remoteVideoTrack = streams?.firstOrNull()?.videoTracks?.firstOrNull()
            listeners.forEach { it.onRemoteTrackAdded() }
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    setState(CallState.CONNECTED)
                    currentRoomId?.let { listeners.forEach { it.onCallConnected(it) } }
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    // App can auto-reconnect; we only surface state change
                }
                else -> {}
            }
        }
        override fun onSignalingChange(signalingChange: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(gatheringState: PeerConnection.IceGatheringState?) {
            if (gatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                val target = peerUid ?: return
                signaling.emit("ice:candidates-done", mapOf("targetId" to target))
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}
    }

    private fun handleRemoteEnd(payload: Map<String, Any>) {
        val reason = (payload["reason"] as? String) ?: "ended"
        if (reason == "callee-offline" && isCaller) finish("missed") else finish(reason)
    }

    fun endCall() {
        currentRoomId?.let { signaling.emit("call:end", mapOf("roomId" to it)) }
        finish("ended")
    }

    // ── Media controls ─────────────────────────────────────
    fun mute(muted: Boolean) = webrtc.setMicrophoneEnabled(!muted)
    fun switchCamera() = webrtc.switchCamera()
    fun enableVideo(on: Boolean) = webrtc.setCameraEnabled(on)
    fun speaker(on: Boolean) = webrtc.setAudioSpeaker(on)

    /** Remote video track (null until call connects / audio-only call). */
    var remoteVideoTrack: org.webrtc.VideoTrack? = null
        private set

    // ── Internals ──────────────────────────────────────────
    private fun handleIncoming(payload: Map<String, Any>) {
        val caller = parseUser(payload["caller"])
        val callee = parseUser(payload["callee"])
        peerUid = caller?.id
        val call = IncomingCall(
            sessionId = payload["sessionId"] as? String ?: "",
            roomId = payload["roomId"] as? String ?: "",
            type = CallType.from(payload["type"] as? String),
            caller = caller,
            callee = callee,
        )
        currentType = call.type ?: CallType.AUDIO
        currentRoomId = call.roomId
        setState(CallState.RINGING_INCOMING)
        listeners.forEach { it.onIncomingCall(call) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseUser(obj: Any?): User? {
        val m = obj as? Map<String, Any> ?: return null
        return User(
            id = m["id"] as? String ?: return null,
            clientId = m["clientId"] as? String ?: "",
            displayName = m["displayName"] as? String ?: "",
            avatar = m["avatar"] as? String?,
            status = m["status"] as? String?,
            lastSeen = (m["lastSeen"] as? Number)?.toLong() ?: 0L,
        )
    }

    private fun sdpConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", currentType == CallType.VIDEO))
    }

    private fun fetchIceConfig(): List<PeerConnection.IceServer> {
        return try {
            val cfg = ApiClient.get().service.iceConfig()
            cfg.iceServers.map {
                PeerConnection.IceServer.builder(it.urls).setUsername(it.username).setCredential(it.credential).createIceServer()
            }
        } catch (_: Exception) {
            listOf(PeerConnection.IceServer("stun:stun.l.google.com:19302"))
        }
    }

    private fun finish(reason: String) {
        val roomId = currentRoomId
        currentRoomId = null
        cleanup()
        when (reason) {
            "missed" -> {
                setState(CallState.MISSED)
                if (roomId != null) listeners.forEach { it.onCallMissed(roomId) }
            }
            "rejected" -> setState(CallState.REJECTED)
            else -> setState(CallState.ENDED)
        }
        if (roomId != null) listeners.forEach { it.onCallEnded(roomId, reason) }
    }

    private fun cleanup() {
        pc?.dispose()
        pc = null
        pendingCandidates.clear()
        peerUid = null
        isCaller = false
    }

    private fun setState(newState: CallState) {
        state = newState
        main.post { listeners.forEach { it.onCallStateChanged(newState) } }
    }
}