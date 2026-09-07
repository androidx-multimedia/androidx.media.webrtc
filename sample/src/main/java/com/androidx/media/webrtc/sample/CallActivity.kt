package com.androidx.media.webrtc.sample

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.androidx.media.webrtc.WebrtcClient
import com.androidx.media.webrtc.call.CallListener
import com.androidx.media.webrtc.models.CallType
import com.androidx.media.webrtc.models.IncomingCall
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallActivity : AppCompatActivity() {

    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var peerNameText: TextView

    private var egl: EglBase? = null
    private var localTrack: VideoTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var answered = false

    private val listener = object : CallListener {
        override fun onIncomingCall(call: IncomingCall) {
            runOnUiThread {
                WebrtcClient.startForegroundService("${call.caller?.displayName ?: "Incoming"} ${if (call.type == CallType.VIDEO) "video" else "voice"} call")
            }
        }
        override fun onCallStateChanged(state: com.androidx.media.webrtc.models.CallState) {
            runOnUiThread { peerNameText.text = state.name }
        }
        override fun onCallConnected(roomId: String) {
            runOnUiThread { peerNameText.text = "Connected ($roomId)" }
        }
        override fun onCallEnded(roomId: String, reason: String) {
            runOnUiThread { peerNameText.text = "Call ended: $reason" }
            finish()
        }
        override fun onRemoteTrackAdded() {
            runOnUiThread { attachRemoteVideo() }
        }
        override fun onError(message: String) {
            runOnUiThread { peerNameText.text = "Error: $message" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        localRenderer = findViewById(R.id.localVideo)
        remoteRenderer = findViewById(R.id.remoteVideo)
        peerNameText = findViewById(R.id.peerNameText)

        egl = WebrtcClient.getWebRtcManager().eglBase ?: EglBase.create()
        setupRenderer(localRenderer)
        setupRenderer(remoteRenderer)

        WebrtcClient.addCallListener(listener)

        val isCaller = intent.hasExtra("targetClientId")
        if (isCaller) {
            val clientId = intent.getStringExtra("targetClientId")!!
            val displayName = intent.getStringExtra("targetDisplayName") ?: clientId
            WebrtcClient.lookup(clientId) { user ->
                if (user != null) {
                    runOnUiThread {
                        peerNameText.text = "Calling $displayName..."
                        WebrtcClient.startCall(user, CallType.VIDEO)
                    }
                }
            }
        } else {
            peerNameText.text = "Incoming call..."
        }

        findViewById<Button>(R.id.acceptBtn).setOnClickListener {
            if (!answered) { answered = true; WebrtcClient.acceptIncoming() }
        }
        findViewById<Button>(R.id.rejectBtn).setOnClickListener {
            if (!answered) WebrtcClient.rejectIncoming() else WebrtcClient.endCall()
        }
        findViewById<Button>(R.id.switchBtn).setOnClickListener { WebrtcClient.switchCamera() }
        findViewById<Button>(R.id.muteBtn).setOnClickListener { WebrtcClient.mute(true) }
        findViewById<Button>(R.id.speakerBtn).setOnClickListener { WebrtcClient.speaker(true) }

        attachLocalVideo()
    }

    private fun setupRenderer(r: SurfaceViewRenderer) {
        r.setMirror(true)
        r.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        r.init(egl!!.eglBaseContext, null)
    }

    private fun attachLocalVideo() {
        val track = WebrtcClient.getWebRtcManager().localVideoTrack ?: return
        if (track !== localTrack) {
            localTrack?.removeSink(localRenderer)
            localTrack = track
            track.addSink(localRenderer)
        }
    }

    private fun attachRemoteVideo() {
        val track = WebrtcClient.getRemoteVideoTrack() ?: return
        if (track !== remoteTrack) {
            remoteTrack?.removeSink(remoteRenderer)
            remoteTrack = track
            track.addSink(remoteRenderer)
        }
    }

    override fun onDestroy() {
        localTrack?.removeSink(localRenderer)
        remoteTrack?.removeSink(remoteRenderer)
        localRenderer.release()
        remoteRenderer.release()
        egl?.release()
        super.onDestroy()
    }
}