package com.androidx.media.webrtc.models

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RegisterResponse(
    val user: User,
    val token: String,
)

@JsonClass(generateAdapter = true)
data class LookupResponse(
    val user: User?,
)

@JsonClass(generateAdapter = true)
data class IceServer(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

@JsonClass(generateAdapter = true)
data class IceConfig(
    val iceServers: List<IceServer> = emptyList(),
    val sfuMode: String = "memory",
    val sfuUrl: String? = null,
)

enum class CallType(val wire: String) {
    AUDIO("AUDIO"),
    VIDEO("VIDEO");

    companion object {
        fun from(wire: String?): CallType = if (wire == "VIDEO") VIDEO else AUDIO
    }
}

enum class CallState { IDLE, RINGING_OUTGOING, RINGING_INCOMING, CONNECTING, CONNECTED, ENDED, REJECTED, MISSED }

@JsonClass(generateAdapter = true)
data class CallSession(
    val id: String,
    val roomId: String,
    val caller: User?,
    val callee: User?,
    val type: CallType?,
)

@JsonClass(generateAdapter = true)
data class IncomingCall(
    val sessionId: String,
    val roomId: String,
    val type: CallType?,
    val caller: User?,
    val callee: User?,
)

@JsonClass(generateAdapter = true)
data class ServerAck(
    val ok: Boolean,
    val error: String? = null,
)