package com.androidx.media.webrtc.utils

import android.content.Context
import android.provider.Settings

/** Stable, per-device id — acts as the backend `clientId` (no registration UI). */
object DeviceId {
    fun get(context: Context): String {
        val existing = context.getSharedPreferences("webrtc_id", Context.MODE_PRIVATE)
            .getString("client_id", null)
        if (existing != null) return existing
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val id = raw ?: java.util.UUID.randomUUID().toString()
        context.getSharedPreferences("webrtc_id", Context.MODE_PRIVATE)
            .edit().putString("client_id", id).apply()
        return id
    }
}