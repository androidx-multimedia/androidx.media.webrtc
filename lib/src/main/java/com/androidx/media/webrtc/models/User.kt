package com.androidx.media.webrtc.models

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class User(
    val id: String,
    val clientId: String,
    val displayName: String,
    val avatar: String?,
    val status: String?,
    val lastSeen: Long,
) {
    val isOnline: Boolean get() = status == "online"
}