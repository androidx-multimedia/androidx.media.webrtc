# androidx.media.webrtc — Android Library

Drop-in audio/video call SDK for Android (WhatsApp-style 1:1 calls).
No chat, no accounts — each device identifies itself with a `clientId`.

## Gradle setup

In your root `settings.gradle.kts`:
```kotlin
include(":androidx-media-webrtc")
project(":androidx-media-webrtc").projectDir = file("../androidx-media-webrtc-sample/lib")
```

Add dependency (source module):
```kotlin
implementation(project(":androidx-media-webrtc"))
```

JitPack (published):
```kotlin
maven { url = uri("https://jitpack.io") }
implementation("com.github.androidx-multimedia:androidx-media-webrtc:1.0.0")
```

## Usage (3 steps)

> Full walkthrough incl. permissions, video rendering and 2-phone testing:
> **[docs/USAGE.md](docs/USAGE.md)**

### 1. Set your Render URL — ONE place
```kotlin
// WebrtcConfig.kt (in the lib, or pass your own value to init())
const val BASE_URL = "https://androidx-media-webrtc-server.onrender.com"
```

### 2. Init + connect (MainActivity / Application)
```kotlin
WebrtcClient.init(this, WebrtcConfig.BASE_URL)

WebrtcClient.connect(displayName) {
    // socket open, JWT ready — you can receive calls now
}
```

### 2. Listen for calls
```kotlin
WebrtcClient.addCallListener(object : CallListener {
    override fun onIncomingCall(call: IncomingCall) {
        // show incoming UI: call.caller.displayName, call.type
    }
    override fun onCallConnected(roomId: String) { /* show in-call UI */ }
    override fun onCallEnded(roomId: String, reason: String) { /* cleanup UI */ }
    override fun onRemoteTrackAdded() {
        attachRemoteVideo() // see sample CallActivity
    }
})
```

### 3. Make / accept / control calls
```kotlin
// outgoing
WebrtcClient.lookup("friend-client-id") { user ->
    user?.let { WebrtcClient.startCall(it, CallType.VIDEO) } // or CallType.AUDIO
}

// incoming
WebrtcClient.acceptIncoming()   // or rejectIncoming() / ignoreIncoming()

// during call
WebrtcClient.mute(true)          // microphone
WebrtcClient.switchCamera()      // front/back
WebrtcClient.enableVideo(false)  // video off
WebrtcClient.speaker(true)       // speakerphone
WebrtcClient.endCall()

// WhatsApp-style background call notification
WebrtcClient.startForegroundService("Ravi · video call")
```

### Permissions (must be requested by the host app)
- `CAMERA`, `RECORD_AUDIO`, `BLUETOOTH_CONNECT` (Android 12+), `POST_NOTIFICATIONS` (13+)
- plus `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_PHONE_CALL` (in manifest — already merged)

## Rendering video

Get the WebRTC manager for renderers:
```kotlin
val mgr = WebrtcClient.getWebRtcManager()
localRenderer.init(mgr.eglBase!!.eglBaseContext, null)
mgr.localVideoTrack?.addSink(localRenderer)   // your preview
WebrtcClient.getRemoteVideoTrack()?.addSink(remoteRenderer) // peer (after onRemoteTrackAdded)
```

## Performance baked in

- 720p30 hardware-accelerated capture, auto-adapting (HW codecs + fallback).
- `iceCandidatePoolSize = 10` → candidates pre-gathered → near-instant connect.
- `max-bundle`, `rtcp-mux`, Opus 48k + hardware AEC/NS, speaker/earpiece routing.
- Signaling socket forced to `websocket` transport (skips polling handshake).

## Structure (MVC)

```
com.androidx.media.webrtc
├── WebrtcClient.kt        facade (entry point)
├── models/                User, CallSession, IceConfig, CallState...
├── rest/                  Retrofit (register, lookup, ice-config)
├── signaling/             Socket.IO signaling client (backend-matching events)
├── core/                  WebRtcManager: native stack, capture, peer conn
├── call/                  CallManager (state machine), CallListener, foreground service
└── utils/                 DeviceId, JSON helpers
```

## Dialing model

Every user is an anonymous record keyed by `clientId`:
- Your "phone number" = your device's `ANDROID_ID` (stable, no permission needed).
- To call someone, call `WebrtcClient.lookup("their-clientId")` (they must have
  connected at least once so the backend knows them).
- For a username-style dialing UX, let the user pick a username as `clientId`
  in your app and show it as their "number".