# 📱 Android usage guide — androidx.media.webrtc

Complete walkthrough to add WhatsApp-style audio/video calls to your Android app.

---

## 1. Add the library

**Option A (local, while developing):**

```kotlin
// settings.gradle.kts
include(":androidxmediawebrtc")
project(":androidxmediawebrtc").projectDir = file("../androidx-media-webrtc/lib")
```

```kotlin
// app/build.gradle.kts
implementation(project(":androidxmediawebrtc"))
```

**Option B (published, e.g. JitPack/Maven):**

```kotlin
implementation("io.github.yourorg:androidx.media.webrtc:1.0.0")
```

---

## 2. Permissions

Add to `AndroidManifest.xml` (the library merges the rest for you):

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" /> <!-- Android 12+ -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />  <!-- Android 13+ -->
```

Request them at runtime before the first call:

```kotlin
val perms = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.POST_NOTIFICATIONS,
)
if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }.launch(perms)
}
```

---

## 3. 👉 Set your Render URL — the ONE place on Android

Edit `WeRtcConfig.kt` in the library (or the host app passes its own value):

```kotlin
object WebrtcConfig {
    const val BASE_URL: String = "https://androidx-media-webrtc-server.onrender.com" // ← CHANGE HERE
}
```

```kotlin
WebrtcClient.init(this, WebrtcConfig.BASE_URL)   // or your own BuildConfig.SERVER_URL
```

The **same single source** is used by the sample app, the ICE config fetch
(in `CallManager.fetchIceConfig`) and the signaling socket.

> Testing values:
> - Android emulator → your PC: `http://10.0.2.2:3000`
> - Physical phone + backend on same Wi-Fi: `http://<your-pc-LAN-ip>:3000`
> - Production → Render URL: `https://<your-service>.onrender.com`
> - For physical devices in dev, also add `android:usesCleartextTraffic="true"` to the app's manifest (HTTP only).

---

## 4. Connect (no account/signup)

```kotlin
override fun onCreate(...) {
    WebrtcClient.init(applicationContext, WebrtcConfig.BASE_URL)
    WebrtcClient.connect("ravi-m9001") {              // clientId = your user's "number"
        Log.d("Call", "ready to receive calls")
    }
}
```

The lib keeps your JWT, opens the Socket.IO signaling socket, and registers
you with the backend. **You're now reachable.**

---

## 5. Handle incoming calls

```kotlin
WebrtcClient.addCallListener(object : CallListener {
    override fun onIncomingCall(call: IncomingCall) {
        // show incoming UI — call.caller?.displayName, call.type is AUDIO/VIDEO
        showIncomingScreen(call)
    }

    override fun onCallStateChanged(state: CallState) {
        // IDLE → RINGING_* → CONNECTING → CONNECTED → ENDED
    }

    override fun onCallConnected(roomId: String) {
        // call established — show in-call UI, start foreground service
        WebrtcClient.startForegroundService("${peerName} · video call")
    }

    override fun onCallEnded(roomId: String, reason: String) {
        // cleanup in-call UI; stopForegroundService
    }

    override fun onRemoteTrackAdded() {
        // a video track from the peer just arrived — attach it (step 7)
    }

    override fun onError(message: String) { /* surface to user */ }
})
```

---

## 6. Make / control calls

```kotlin
// Find someone by their clientId (what the user dials)
WebrtcClient.lookup("sneha-m9002") { user ->
    if (user == null) {
        // not yet registered on the backend — ask them to connect once
    } else {
        WebrtcClient.startCall(user, CallType.VIDEO)   // or CallType.AUDIO
    }
}

// Incoming call actions
WebrtcClient.acceptIncoming()
WebrtcClient.rejectIncoming()
WebtrcClient.ignoreIncoming()     // → MISSED

// In-call controls
WebrtcClient.mute(true / false)        // microphone
WebrtcClient.enableVideo(true / false) // video on/off
WebrtcClient.switchCamera()            // front ⟷ back
WebrtcClient.speaker(true / false)     // speaker / earpiece
WebtrcClient.endCall()
```

---

## 7. Render video (your call UI)

Two `SurfaceViewRenderer`s — one for your preview, one for the peer:

```kotlin
// CallActivity / Fragment
private lateinit var localRenderer: SurfaceViewRenderer
private lateinit var remoteRenderer: SurfaceViewRenderer

override fun onResume() {
    super.onResume()
    val egl = WebrtcClient.getWebRtcManager().eglBase!!.eglBaseContext

    localRenderer.init(egl, null)
    remoteRenderer.init(egl, null)
    localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

    WebrtcClient.getWebRtcManager().localVideoTrack?.addSink(localRenderer)   // your camera
    WebrtcClient.getRemoteVideoTrack()?.addSink(remoteRenderer)               // peer
}

override fun onPause() {
    localRenderer.release()
    remoteRenderer.release()
    super.onPause()
}
```

`CallListener.onRemoteTrackAdded()` is your signal to call
`WebtrcClient.getRemoteVideoTrack()?.addSink(remoteRenderer)`.

---

## 8. Two-phone test checklist

1. Backend deployed on Render (see `DEPLOY_RENDER.md`).
2. Set `WebrtcConfig.BASE_URL` to the Render URL on **both** phones.
3. Phone A connects with `clientId="a-phone"`, Phone B with `"b-phone"`.
4. On A, `lookup("b-phone")` → should find B → tap video call.
5. B gets `onIncomingCall` → tap **Accept**.
6. Both see video; ICE connects over STUN (free).

**If the call never connects:**
- Both users must be **online** (socket connected) — check server logs.
- Behind restrictive NAT, WebRTC needs a **TURN server** — set `TURN_URLS`,
  `TURN_USERNAME`, `TURN_CREDENTIAL` in backend env (self-host `coturn`).
- In dev with HTTP, ensure `android:usesCleartextTraffic="true"`.

---

## How it's wired (no chat, signaling only)

```
Your app → WebrtcClient (facade)
            ├── ApiClient      REST: register / lookup / ice-config
            ├── SignalingClient Socket.IO (invite/accept/offer/answer/ICE)
            ├── CallManager     state machine + PeerConnection
            └── WebRtcManager   camera/mic capture, HW codecs, EGL
                         ↓
                    Render backend (signaling relay only)
                         ↓
           media flows DIRECT peer-to-peer between phones
```

Full protocol reference: `Backend-service/…/README.md`.