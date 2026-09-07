package com.androidx.media.webrtc

/**
 * SINGLE SOURCE OF TRUTH for the backend URL in the Android library.
 *
 * Change it HERE once — the sample app and every consumer that reads this
 * constant automatically use your deployed Render URL.
 *
 *   • Emulator → host machine: http://10.0.2.2:3000
 *   • Physical device + backend on your LAN: http://<your-pc-ip>:3000
 *   • Deployed on Render: https://<your-service-name>.onrender.com
 */
object WebrtcConfig {
    /** Render URL — deployed backend. */
    const val BASE_URL: String = "https://androidx-media-webrtc-server.onrender.com"
}