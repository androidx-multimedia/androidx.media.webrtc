package com.androidx.media.webrtc.utils

import com.squareup.moshi.Moshi
import org.webrtc.SessionDescription

/** Adapter from Socket.IO JSON (Map) to our models, plus small helpers. */
object Json {
    val moshi: Moshi = Moshi.Builder().build()

    @Suppress("UNCHECKED_CAST", "ImplicitNullableToTypeParameter")
    fun asMap(obj: Any?): Map<String, Any> {
        if (obj == null) return emptyMap()
        if (obj is Map<*, *>) return obj as Map<String, Any>
        // Socket.IO 2.x may deliver JSONObject — alias to map via toString is lossy;
        // prefer Map. This branch just guards against unexpected shapes.
        return emptyMap()
    }

    fun sdp(obj: Any?): SessionDescription? {
        val m = asMap(obj)
        if (m["sdp"] == null) return null
        val sdp = when (val d = m["sdp"]) {
            is Map<*, *> -> (asMap(d))["sdp"] as? String
            is String -> d
            else -> null
        } ?: return null
        val typeStr = (m["type"] as? String) ?: "offer"
        val type = if (typeStr == "answer") SessionDescription.Type.ANSWER else SessionDescription.Type.OFFER
        return SessionDescription(type, sdp)
    }
}