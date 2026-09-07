package com.androidx.media.webrtc.core

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.os.Build
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Owns the WebRTC native stack: factory, EGL, media capture and the live PeerConnection.
 * Tuned for low latency (max-bundle, pre-gathered ICE, opus, HW acceleration).
 */
class WebRtcManager private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    var eglBase: EglBase? = null
        private set
    var surfaceTextureHelper: SurfaceTextureHelper? = null
        private set
    var factory: PeerConnectionFactory? = null
        private set
    var videoCapturer: VideoCapturer? = null
        private set
    var localVideoTrack: VideoTrack? = null
        private set
    var localAudioTrack: AudioTrack? = null
        private set
    var audioSource: AudioSource? = null
        private set
    var videoSource: VideoSource? = null
        private set
    var peerConnection: PeerConnection? = null
        private set

    private var isFrontCamera = true

    fun initialize() {
        if (factory != null) return
        eglBase = EglBase.create()
        surfaceTextureHelper = SurfaceTextureHelper.create("capture-thread", eglBase!!.eglBaseContext)

        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseSoftwareAcousticEchoCanceler(false)
            .setUseSoftwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        // Default encoder/decoder factories ship hardware support (VP8/H264) with
        // automatic software fallback — no need to override.
        val pcf = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        factory = pcf

        localAudioTrack = createAudioTrack()
        if (hasCamera()) startVideoCapture()
    }

    private fun createAudioTrack(): AudioTrack? {
        audioSource = factory?.createAudioSource(MediaConstraints().apply {
            // Opus 48k, low-latency, echo/NS handled by the hardware ADM above
            mandatory.add(MediaConstraints.KeyValuePair("audio/opus", "1"))
        })
        return audioSource?.createAudioTrack("audio0")
    }

    fun hasCamera(): Boolean = appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA)

    fun startVideoCapture() {
        if (videoCapturer != null || localVideoTrack != null) return
        val capturer = when (isFrontCamera) {
            true -> createCameraCapturer(CameraEnumerationAndroid.getDeviceNames().firstOrNull { it.contains("front", true) })
            false -> createCameraCapturer(CameraEnumerationAndroid.getDeviceNames().firstOrNull { it.contains("back", true) })
        } ?: return
        videoCapturer = capturer
        videoSource = factory?.createVideoSource(capturer.isScreencast)
        localVideoTrack = videoSource?.createVideoTrack("video0")
        capturer.initialize(surfaceTextureHelper, appContext, videoSource!!.capturerObserver)
        val fps = 30
        val width = 1280; val height = 720 // 720p30 → good balance; falls back automatically
        capturer.startCapture(width, height, fps)
    }

    private fun createCameraCapturer(names: Array<String>): VideoCapturer? {
        for (name in names) {
            val byName = CameraEnumerationAndroid.getCapturerByName(name)
            if (byName != null) return byName
        }
        return CameraEnumerationAndroid.getDeviceNames().firstOrNull()?.let { CameraEnumerationAndroid.getCapturerByName(it) }
    }

    fun switchCamera() {
        isFrontCamera = !isFrontCamera
        videoCapturer?.let {
            if (it is org.webrtc.CameraVideoCapturer) it.switchCamera(null)
        }
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>, pcObserver: PeerConnection.Observer): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = org.webrtc.PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAX_BUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceCandidatePoolSize = 10 // pre-gather → faster connect
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            audioJitterBufferFastAccelerate = true
            audioJitterBufferMaxPackets = 60
        }
        return factory?.createPeerConnection(config, pcObserver)?.also { pc ->
            pc.addTrack(localAudioTrack!!, listOf("audio"))
            if (localVideoTrack != null) {
                pc.addTrack(localVideoTrack!!, listOf("video"))
            }
            peerConnection = pc
        }
    }

    fun isMicrophoneEnabled(): Boolean = localAudioTrack?.enabled() ?: false
    fun isCameraEnabled(): Boolean = localVideoTrack?.enabled() ?: false

    fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun setAudioSpeaker(speaker: Boolean) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (speaker) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } else {
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_IN_COMMUNICATION // earpiece
        }
    }

    fun destroy() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        peerConnection?.dispose()
        peerConnection = null
        videoSource?.dispose()
        audioSource?.dispose()
        factory?.dispose()
        factory = null
        localVideoTrack = null
        localAudioTrack = null
        surfaceTextureHelper?.dispose()
        eglBase?.release()
        eglBase = null
    }

    companion object {
        @Volatile private var instance: WebRtcManager? = null

        /** Singleton — call once from App context. */
        fun init(context: Context): WebRtcManager {
            return instance ?: synchronized(this) {
                instance ?: WebRtcManager(context).also {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
                    )
                    it.initialize()
                }
            }
        }
        fun get(): WebRtcManager = checkNotNull(instance) { "WebRtcManager.init(context) not called" }
    }
}