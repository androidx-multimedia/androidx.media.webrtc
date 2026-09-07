package com.androidx.media.webrtc.signaling

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

/**
 * Thin Socket.IO wrapper matching backend events (src/sockets).
 * All payloads travel as Maps; listeners receive them directly.
 */
class SignalingClient(
    private val baseUrl: String,
) {
    private var socket: Socket? = null

    /** (event, payload Map) callbacks fired on the socket thread. */
    private val listeners = mutableListOf<(String, Map<String, Any>) -> Unit>()

    fun onEvent(cb: (String, Map<String, Any>) -> Unit) {
        listeners.add(cb)
    }

    @Suppress("UNCHECKED_CAST")
    fun connect(token: String) {
        val opts = IO.Options.builder()
            .setTransports(arrayOf("websocket"))
            .setReconnection(true)
            .setReconnectionAttempts(Int.MAX_VALUE)
            .setReconnectionDelay(500)
            .setAuth(mapOf("token" to token))
            .build()
        socket = IO.socket(baseUrl, opts).also { s ->
            s.on(Socket.EVENT_CONNECT) { emit("presence:query", mapOf("userIds" to emptyList<String>())) }
            s.on(Socket.EVENT_DISCONNECT) { }
            s.on(Socket.EVENT_CONNECT_ERROR) { args -> notify("connect:error", mapOf("error" to (args.firstOrNull()?.toString() ?: "unknown"))) }
            s.on("*") { args ->
                s.listeners("*")
            }
            // Register all known events explicitly so wildcard isn't needed
            KNOWN_EVENTS.forEach { ev ->
                s.on(ev) { args ->
                    val payload = args.firstOrNull() as? Map<String, Any> ?: emptyMap()
                    notify(ev, payload)
                }
            }
            s.connect()
        }
    }

    fun emit(event: String, payload: Map<String, Any>, ack: ((Boolean) -> Unit)? = null) {
        val s = socket ?: return
        if (ack == null) {
            s.emit(event, JSONObject(payload))
        } else {
            s.emit(event, JSONObject(payload)) { args ->
                @Suppress("UNCHECKED_CAST")
                val ok = (args?.firstOrNull() as? Map<String, Any>)?.get("ok") as? Boolean ?: false
                ack(ok)
            }
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        listeners.clear()
    }

    private fun notify(event: String, payload: Map<String, Any>) {
        listeners.forEach { cb -> cb(event, payload) }
    }

    companion object {
        val KNOWN_EVENTS = listOf(
            "user:presence", "presence:result",
            "call:invite", "call:accept", "call:reject", "call:end",
            "call:offer", "call:answer", "ice:candidate", "ice:candidates-done",
        )
    }
}