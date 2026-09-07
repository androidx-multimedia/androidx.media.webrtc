package com.androidx.media.webrtc.call

import com.androidx.media.webrtc.models.CallState
import com.androidx.media.webrtc.models.IncomingCall
import com.androidx.media.webrtc.models.User

/** Consumer-facing callbacks. All on the main thread. */
interface CallListener {
    fun onIncomingCall(call: IncomingCall) {}
    fun onCallStateChanged(state: CallState) {}
    fun onCallConnected(roomId: String) {}
    fun onCallEnded(roomId: String, reason: String) {}
    fun onCallMissed(roomId: String) {}
    fun onRemoteTrackAdded() {}
    fun onError(message: String) {}
}