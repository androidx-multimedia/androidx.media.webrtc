package com.androidx.media.webrtc.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.androidx.media.webrtc.WebrtcClient
import com.androidx.media.webrtc.WebrtcConfig

/** Enter your clientId + peer's clientId, hit "Video Call". */
class MainActivity : AppCompatActivity() {

    private val perms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private lateinit var myIdInput: EditText
    private lateinit var peerIdInput: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        myIdInput = findViewById(R.id.myIdInput)
        peerIdInput = findViewById(R.id.peerIdInput)
        statusText = findViewById(R.id.statusText)
        val callBtn = findViewById<Button>(R.id.callBtn)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(perms)
        }

        statusText.setTextIsSelectable(true)
        callBtn.setOnClickListener {
            val myId = myIdInput.text.toString().trim()
            val peerId = peerIdInput.text.toString().trim()
            if (myId.isEmpty() || peerId.isEmpty()) {
                statusText.text = "Fill both ids"
                return@setOnClickListener
            }

            WebrtcClient.init(this, WebrtcConfig.BASE_URL)
            connectAndCall(myId, peerId)
        }
    }

    private fun connectAndCall(myId: String, peerId: String) {
        statusText.text = "Registering (URL: ${WebrtcConfig.BASE_URL})..."
        WebrtcClient.connect(myId) {
            statusText.text = "Connected. Looking up $peerId..."
            WebrtcClient.lookup(peerId) { user ->
                if (user == null) {
                    statusText.text = "Peer not found (peer must register once on their device)"
                    return@lookup
                }
                statusText.text = "Calling ${user.displayName}..."
                val intent = Intent(this, CallActivity::class.java)
                    .putExtra("targetClientId", peerId)
                    .putExtra("targetDisplayName", user.displayName)
                startActivity(intent)
            }
        }
    }
}