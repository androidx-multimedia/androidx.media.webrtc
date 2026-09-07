# WebRTC native lib is already fine; keep socket.io + retrofit classes
-keep class org.webrtc.** { *; }
-keep class io.socket.** { *; }
-keep class com.androidx.media.webrtc.** { *; }
-keepattributes Signature