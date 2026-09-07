package com.androidx.media.webrtc.models

data class RegisterResponse(
    val user: User,
    val token: String,
)

data class LookupResponse(
    val user: User?,
)

data class IceServer(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

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

data class CallSession(
    val id: String,
    val roomId: String,
    val caller: User?,
    val callee: User?,
    val type: CallType?,
)

data class IncomingCall(
    val sessionId: String,
    val roomId: String,
    val type: CallType?,
    val caller: User?,
    val callee: User?,
)

data class ServerAck(
    val ok: Boolean,
    val error: String? = null,
)